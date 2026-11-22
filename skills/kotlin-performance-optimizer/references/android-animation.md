# Android アニメーション・フレーム駆動処理の最適化

60Hz（約 16.6ms）〜 120Hz（約 8.3ms）のフレームバジェット内で毎フレーム実行される Kotlin 処理（カスタム描画、パーティクル、物理演算、補間、ゲームループなど）を対象とします。View のレイアウト階層や Jetpack Compose の再コンポーズ最適化は対象外とし、フレームループの駆動、フレーム単位のデータ保持と計算、スレッド間の受け渡し、グラフィックコンテキスト・描画リソースの扱いを確認します。

## アニメーションループの設計

### VSync 同期とフレーム駆動

`Handler.postDelayed(16)`、`Timer`、`while (isActive) { delay(16) }` のような固定スリープ駆動はディスプレイの VSync 周期とずれるため、フレームの取りこぼし、二重更新、リフレッシュレート変更（60Hz / 90Hz / 120Hz）への追従失敗を招きます。

- 描画に連動する更新は、`Choreographer.FrameCallback`、`ValueAnimator`、Compose 側であれば `withFrameNanos` など VSync に同期したコールバックで駆動します。
- 物理演算など固定タイムステップが必要な場合も、駆動の起点は VSync に合わせ、経過時間（`dt`）をアキュムレータに積んで固定幅でステップを進めます。

### フレーム時刻と経過時間（`dt`）の扱い

VSync コールバックからフレーム時刻（`frameTimeNanos`）が渡される場合、ループ内や要素ごとに `System.nanoTime()` や `System.currentTimeMillis()` を取り直すと、同一フレーム内の判定時刻がずれ、システムコールも増えます。

- 同一フレームの計算にはコールバックから渡された単調時刻（`frameTimeNanos`）を基準値として使い回します。壁時計（`System.currentTimeMillis()`）は時刻補正で逆行・ジャンプするため経過時間の計算に使いません。
- アプリのバックグラウンド復帰、GC、重いフレームの直後は `dt` が極端に大きくなることがあります。物理演算の発散や固定タイムステップの蓄積ループが終わらなくなる現象（Spiral of Death）を防ぐため、`dt` に上限（例: 50ms〜100ms）を設け、1 フレーム内の最大ステップ回数も制限します。

### 停止条件とライフサイクル連携

変化がない状態や画面外・バックグラウンド状態でもコールバックや `invalidate()` を回し続けると、CPU・GPU とバッテリーを浪費します。

- 収束・終了条件に達したらコールバック登録を解除し、再描画要求を止めます。
- 画面の可視状態（`onVisibilityChanged` / `onDetachedFromWindow` / Lifecycle の `ON_STOP` など）に合わせてループを停止・再開します。
- 状態が前フレームから変化していない場合は、不要な再計算や `invalidate()` を省きます。

## フレームごとのデータ保持と計算

### 毎フレームの割り当て回避（ゼロアロケーション）

60〜120fps のホットパスでは、1 フレームあたりの小さなオブジェクト生成でも秒間数千〜数十万回の割り当てになり、TLAB の消費と GC の頻度が増えてフレームドロップの原因になります。

- 毎フレームの更新で `data class` の `.copy()`、新しい `List` の生成、`PointF` / `Pair` の生成、外部変数をキャプチャするラムダ生成を避けます。
- 要素数が多いパーティクルやエンティティは、オブジェクト配列（AoS）ではなくプリミティブ配列による Structure of Arrays（SoA）で事前確保し、アクティブ件数（`activeCount`）とスワップ削除で固定長プールとして管理します。

```kotlin
// 毎フレーム data class と List を再生成する例
data class Particle(val x: Float, val y: Float, val vx: Float, val vy: Float, val life: Float)

fun updateParticlesAllocating(particles: List<Particle>, dtSec: Float): List<Particle> =
    particles.mapNotNull { p ->
        val nextLife = p.life - dtSec
        if (nextLife <= 0f) null
        else p.copy(x = p.x + p.vx * dtSec, y = p.y + p.vy * dtSec, life = nextLife)
    }

// 事前確保したプリミティブ配列（SoA）をインプレース更新する例
class ParticlePool(capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    var activeCount: Int = 0
        private set

    fun spawn(px: Float, py: Float, pvx: Float, pvy: Float, pLife: Float): Boolean {
        if (activeCount >= x.size) return false
        val idx = activeCount
        x[idx] = px
        y[idx] = py
        vx[idx] = pvx
        vy[idx] = pvy
        life[idx] = pLife
        activeCount = idx + 1
        return true
    }

    fun update(dtSec: Float) {
        var i = 0
        while (i < activeCount) {
            val nextLife = life[i] - dtSec
            if (nextLife <= 0f) {
                val last = activeCount - 1
                x[i] = x[last]
                y[i] = y[last]
                vx[i] = vx[last]
                vy[i] = vy[last]
                life[i] = life[last]
                activeCount = last
            } else {
                x[i] += vx[i] * dtSec
                y[i] += vy[i] * dtSec
                life[i] = nextLife
                i++
            }
        }
    }
}
```

要素の描画順序を保つ必要がある場合は、スワップ削除ではなく順序維持の詰め直しや別管理を選びます。

### 補間と重複計算の削減

- 距離の比較や閾値判定では `sqrt(dx * dx + dy * dy) < r` の代わりに `dx * dx + dy * dy < r * r` を使い、平方根の計算を省きます。
- 同一フレーム内で全要素に共通する値（回転角の `sin` / `cos`、補間係数、スケーリング係数など）はループの外で一度だけ計算します。
- 複雑なイージングや三角関数を大量に評価する場合は、ルックアップテーブル（LUT）化も候補になります。ただし、テーブルが大きすぎてキャッシュミスが増える場合は再計算より遅くなるため、[メモ化のコスト](./anti-patterns.md#メモ化のコスト) と同様に実測して判断します。

## スレッド設計とデータ受け渡し

### UI スレッドと計算スレッドの分離

重い物理演算、衝突判定、大量の頂点・座標計算を UI スレッド上で実行すると、フレームバジェットを消費して入力応答や他 UI の描画まで遅延させます。計算コストがフレームバジェットに対して無視できない場合は、計算をワーカースレッドへ逃がす構成を検討します。

### ダブルバッファリングによる受け渡し

計算スレッドと描画スレッド（UI スレッドまたは `SurfaceView` の専用描画スレッド）の間で、更新処理全体にわたって `synchronized` や `Mutex` を保持すると、描画側が計算完了を待ってブロックされます。逆に、競合を避けるために毎フレーム全配列をディープコピーすると、コピーと GC の負荷が増えます。

- 事前確保した 2 つ（必要に応じて 3 つ）のバッファを用意し、計算スレッドがバックバッファへ書き込み終えたタイミングで参照だけを短時間ロックまたはアトミックに交換します。
- 描画スレッドが読み取り中のバッファを計算スレッドが上書きしないよう、所有権とスワップのタイミングを明確にします。

```kotlin
class DoubleBufferedPositions(capacity: Int) {
    private var front = FloatArray(capacity * 2)
    private var back = FloatArray(capacity * 2)
    @Volatile
    private var frontCount: Int = 0
    private val swapLock = Any()

    // 計算スレッド: バックバッファに書き込んでからスワップする
    fun publish(count: Int, fill: (FloatArray) -> Unit) {
        fill(back)
        synchronized(swapLock) {
            val tmp = front
            front = back
            back = tmp
            frontCount = count
        }
    }

    // 描画スレッド: 必要な参照を受け取って描画する（描画と計算が同時進行する場合は 3 バッファ構成を検討）
    fun readFront(draw: (positions: FloatArray, count: Int) -> Unit) {
        synchronized(swapLock) {
            draw(front, frontCount)
        }
    }
}
```

描画側の処理時間が長い場合は、2 バッファの `synchronized` だと計算スレッドのスワップが描画完了を待つことになります。計算側も待たせたくない場合は、フロント・レディ・バックの 3 バッファ（トリプルバッファ）構成を比較します。

### Coroutines の起動頻度とコンテキスト切り替え

毎フレーム `launch` や `withContext(Dispatchers.Default)` を細粒度で発行すると、ディスパッチと継続オブジェクト生成のオーバーヘッドが積み上がります。計算をオフロードする場合は、専用のループやバッチ単位での実行とし、1 フレームあたりのスレッド切り替え回数を最小限にします。

### `SurfaceView` / `TextureView` における Canvas ロック時間の最小化

`SurfaceView` の `SurfaceHolder.lockCanvas()` / `SurfaceHolder.lockHardwareCanvas()` や、`TextureView` の `TextureView.lockCanvas()` を使う場合、ロックを取得してから `unlockCanvasAndPost()` するまでの間に物理演算やデータ変換を混ぜると、バッファの占有時間が伸びてコンポジタ側の待機を招きます。

- 状態更新と描画用データの準備はロックの外で完了させ、ロック区間では `Canvas` への描画コマンド発行だけを行います。
- `SurfaceView` で描画内容がハードウェアアクセラレーションに対応している場合、API レベルと要件に応じて `SurfaceHolder.lockHardwareCanvas()` の利用も確認します（利用できない操作がある場合はソフトウェア描画との差異を検証します）。

## グラフィックコンテキストと描画リソース

### 描画オブジェクトの再利用とネイティブメモリ負荷

`Paint`、`Path`、`Matrix`、`Rect` / `RectF`、`Shader`（`LinearGradient` 等）、`ColorFilter` などの Android グラフィックスオブジェクトの多くは、JVM ヒープ上のラッパーに加えてネイティブ側（Skia 等）のリソースを保持します。`onDraw` やフレーム描画コールバックの中でこれらを都度生成すると、GC に加えてネイティブメモリの確保・解放（`NativeAllocationRegistry` 経由の解放を含む）の負荷が毎フレーム発生します。

- 描画オブジェクトはクラスのフィールドとして一度だけ生成し、フレームごとに状態を再設定して使い回します。
- `Path` のクリアでは、頂点構造を毎フレーム再構築する場合に `Path.reset()` ではなく `Path.rewind()` を使うと、内部データ構造のバッファを再利用できることがあります。ただし、`rewind()` 後に `fillType` が保持されることを前提にせず、`EVEN_ODD` など既定以外の塗りつぶし規則を使う場合は `rewind()` のあとに必要な `fillType` を明示的に設定し直します。
- `Matrix` は `reset()` してから変換を設定し、`RectF` は `set(...)` で値を更新します。

```kotlin
// 描画のたびに Paint / Path / RectF を生成する例
fun drawBadgeAllocating(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3366FF.toInt() }
    val rect = RectF(left, top, right, bottom)
    val path = Path().apply { addRoundRect(rect, 16f, 16f, Path.Direction.CW) }
    canvas.drawPath(path, paint)
}

// フィールドの描画オブジェクトを再利用する例
class BadgeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3366FF.toInt() }
    private val rect = RectF()
    private val path = Path()

    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        rect.set(left, top, right, bottom)
        path.rewind()
        path.addRoundRect(rect, 16f, 16f, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }
}
```

### GPU パイプライン負荷とソフトウェアフォールバック

Kotlin 側の CPU 処理が軽くても、描画コマンドの組み方によっては GPU や RenderThread 側で大きなオーバーヘッドが発生します。

- **`Canvas.saveLayer()` の多用**: オフスクリーンバッファの確保と描画ターゲットの切り替え（GPU パイプラインのフラッシュ）を伴います。単一の図形や互いに重ならない描画など、レイヤー全体への透過と同じ合成結果を保てる場合に限って `Paint.alpha` の調整で置き換えます（重なる複数の図形を個別に半透明で描くと、重なり部分の合成結果が変わります）。レイヤー合成が必要な場合は、対象範囲を最小矩形に絞るか、再利用できる表現であれば `RenderNode` などの代替手段を比較します。
- **ハードウェアアクセラレーション非対応の操作**: 一部の `MaskFilter`、`setShadowLayer` の組み合わせ、特殊な `PorterDuff` モードやクリッピング設定など、端末や API レベルによってハードウェア描画から外れたり高コストになったりする操作を毎フレーム実行していないか確認します。
- **`Shader` / `PathEffect` の毎フレーム再生成**: 位置や角度が変わるだけであれば `Shader` 自体を作り直さず、`Shader.setLocalMatrix(matrix)` で変換を更新できるか確認します。

### `Bitmap` の再利用と一括ピクセル転送

- 毎フレーム `Bitmap.createBitmap(...)` で新しいビットマップを確保すると、大きなメモリ割り当てとテクスチャ再アップロードが発生します。サイズと形式が固定なら既存の `Bitmap` を使い回し、デコード時も `BitmapFactory.Options.inBitmap` での再利用を検討します。
- ピクセル単位の読み書きで `Bitmap.getPixel(x, y)` / `setPixel(x, y, color)` をループ内で呼ぶと、1 ピクセルごとに JNI 境界を跨ぎます。`getPixels(...)` / `setPixels(...)` で事前確保した `IntArray` に一括転送して Kotlin 側で連続走査します（[呼び出し境界の回数と一括コピー](./anti-patterns.md#呼び出し境界の回数と一括コピー) も参照）。

### 描画パスの選択（CPU 再描画 vs RenderThread）

平行移動、拡大縮小、回転、透過度の変化だけで済むアニメーションを、毎フレーム `invalidate()` して CPU 側で `onDraw` し直している場合、描画リストの再記録と再転送が毎フレーム発生します。

- レイアウトや描画内容自体が変わらない変換であれば、View の変換プロパティ（`translationX`, `scaleX`, `rotation`, `alpha` 等）や `RenderNode` を使い、RenderThread 側で処理できるか確認します。
- 動的に内容が変わる部分と静的な背景・レイヤーが混在する場合は、静的な部分を分離して毎フレーム再描画する範囲を絞ります。

## フレーム処理の計測と検証の切り分け

- **テールレイテンシと割り当て量の重視**: フレーム駆動処理では平均値や中央値が小さくても、GC やロック待機による一時的なスパイクがフレームの締め切り超過につながります。60Hz の約 16.6ms や 120Hz の約 8.3ms といったリフレッシュ周期は性能比較や予算見積もりの目安として扱い、計測では中央値に加えて **p95 / p99・最大値** と **フレームあたりの割り当て量** を確認します。
- **JUnit による更新ロジックの簡易計測**: 状態更新や物理演算（`step(dtNanos)` など）のように Android グラフィックスに依存しない部分は、既存の JUnit 簡易計測（[micro-benchmarking.md](./micro-benchmarking.md)）で「固定シード・代表データで 1,000 フレーム連続実行」し、1 フレームあたりの時間分布と最大値、変更前後の差分を比較します。
- **実機・描画パイプラインの検証**: 個別処理の計測値と、実際のフレームの締め切りや表示結果は別の指標です。`Canvas` / `RenderNode` / `SurfaceView` の描画コスト、RenderThread・GPU の負荷、VSync ズレや締め切り超過は JVM 上のテストでは再現できません。実機では利用可能なフレーム計測（例: View のウィンドウ描画における API 31 以降の `FrameMetrics.DEADLINE`）やプロファイラ・Perfetto トレースで締め切り超過と表示結果を確認し、詳細な解析は `android-profiler` / `perfetto-trace-analysis` に委譲します。
