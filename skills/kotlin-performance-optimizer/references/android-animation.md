# Android アニメーション・フレーム駆動処理の最適化

カスタム描画、パーティクル、物理演算、補間など、フレームごとに実行する Kotlin 処理を扱います。

## アニメーションループの設計

### VSync 同期とフレーム駆動

`Handler.postDelayed(16)`、`Timer`、`while (isActive) { delay(16) }` のような固定スリープ駆動はディスプレイの VSync 周期とずれるため、フレームの取りこぼしや二重更新につながり、リフレッシュレートの変化にも追従できません。

- 描画に連動する更新は、`Choreographer.FrameCallback`、`ValueAnimator`、Compose 側であれば `withFrameNanos` など VSync に同期したコールバックで駆動します。
- 物理演算など固定タイムステップが必要な場合も、駆動の起点は VSync に合わせ、経過時間（`dt`）をアキュムレータに積んで固定幅でステップを進めます。

### フレーム時刻と経過時間（`dt`）の扱い

VSync コールバックからフレーム時刻（`frameTimeNanos`）が渡される場合、ループ内や要素ごとに `System.nanoTime()` や `System.currentTimeMillis()` を取り直すと、同一フレーム内の判定時刻がずれ、時計の読み取り回数も増えます。

- 同一フレームの計算にはコールバックから渡された単調時刻（`frameTimeNanos`）を基準値として使い回します。壁時計（`System.currentTimeMillis()`）は時刻補正で逆行・ジャンプするため経過時間の計算に使いません。
- アプリのバックグラウンド復帰、GC、重いフレームの直後は `dt` が極端に大きくなることがあります。物理演算の発散や固定タイムステップの蓄積ループが終わらなくなる現象（Spiral of Death）を防ぐため、`dt` に上限（たとえば 50〜100 ms）を設け、1 フレーム内の最大ステップ回数も制限します。

### 停止条件とライフサイクル連携

変化がない状態や画面外・バックグラウンド状態でもコールバックや `invalidate()` を回し続けると、CPU・GPU とバッテリーを浪費します。

- 収束・終了条件に達したらコールバック登録を解除し、再描画要求を止めます。
- 画面の可視状態（`onVisibilityChanged` / `onDetachedFromWindow` / Lifecycle の `ON_STOP` など）に合わせてループを停止・再開します。
- 状態が前フレームから変化していない場合は、不要な再計算や `invalidate()` を省きます。

## フレームごとのデータ保持と計算

### フレームごとの割り当てを減らす

短命オブジェクトを要素ごとに生成すると、要素数とフレームレートに比例して割り当てが増え、GC が描画の遅れにつながる場合があります。

- 毎フレームの更新で `data class` の `.copy()`、新しい `List` の生成、`PointF` / `Pair` の生成、外部変数をキャプチャするラムダ生成を避けます。
- 要素数が多いパーティクルでは、フィールドごとのプリミティブ配列（SoA）を事前確保する固定長プールを比較します。以下では、アクティブ件数（`activeCount`）を保持し、消滅した要素を末尾の要素で置き換えます。

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

描画順序を保つ必要がある場合は、順序を維持して詰め直すか、別途描画順序を管理します。

### 補間と重複計算の削減

- 非負の半径を使う距離判定では `sqrt(dx * dx + dy * dy) < r` の代わりに `dx * dx + dy * dy < r * r` を使い、平方根の計算を省きます。
- 同一フレーム内で全要素に共通する値（回転角の `sin` / `cos`、補間係数、スケーリング係数など）はループの外で一度だけ計算します。
- 複雑なイージングや三角関数を大量に評価する場合は、ルックアップテーブル（LUT）化も候補になります。ただし、テーブルが大きすぎてキャッシュミスが増える場合は再計算より遅くなるため、[メモ化のコスト](./anti-patterns.md#メモ化のコスト) と同様に実測して判断します。

## スレッド設計とデータ受け渡し

### UI スレッドと計算スレッドの分離

重い物理演算、衝突判定、大量の頂点・座標計算を UI スレッド上で実行すると、フレームバジェットを消費して入力応答や他 UI の描画まで遅延させます。計算コストがフレームバジェットに対して無視できない場合は、計算をワーカースレッドへ移す構成を検討します。

### ダブルバッファリングによる受け渡し

計算スレッドと描画スレッド（UI スレッドまたは `SurfaceView` の専用描画スレッド）の間で、更新処理全体にわたって `synchronized` や `Mutex` を保持すると、描画側が計算完了を待ってブロックされます。逆に、競合を避けるために毎フレーム全配列をディープコピーすると、コピーと GC の負荷が増えます。

- 事前確保した 2 つ（必要に応じて 3 つ）のバッファを用意し、計算スレッドがバックバッファへ書き込み終えたタイミングで参照だけを短時間ロックまたはアトミックに交換します。
- 次の例では書き込み側を 1 スレッドに限定し、描画中のバッファはロックで保護します。描画側はコールバックの終了後に配列の参照を保持しないようにします。

```kotlin
class DoubleBufferedPositions(capacity: Int) {
    private var front = FloatArray(capacity * 2)
    private var back = FloatArray(capacity * 2)
    private var frontCount: Int = 0
    private val swapLock = Any()

    // 単一の計算スレッドで書き込んでから交換
    fun publish(count: Int, fill: (FloatArray) -> Unit) {
        fill(back)
        synchronized(swapLock) {
            val tmp = front
            front = back
            back = tmp
            frontCount = count
        }
    }

    // 描画が終わるまでフロントバッファを保護
    fun readFront(draw: (positions: FloatArray, count: Int) -> Unit) {
        synchronized(swapLock) {
            draw(front, frontCount)
        }
    }
}
```

描画側の処理時間が長い場合は、2 バッファの `synchronized` だと計算スレッドのスワップが描画完了を待つことになります。計算側も待たせたくない場合は、フロント・レディ・バックの 3 バッファ（トリプルバッファ）構成を比較します。

### Coroutines の起動頻度とコンテキスト切り替え

毎フレーム `launch` や `withContext(Dispatchers.Default)` を細粒度で発行すると、ディスパッチと継続オブジェクト生成のオーバーヘッドが積み上がります。計算をオフロードする場合は、専用ループやバッチ処理で切り替え回数を減らせるか比較します。[Coroutines / Flow のオーバーヘッド](./anti-patterns.md#coroutines--flow-のオーバーヘッド)

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

- `Canvas.saveLayer()` はオフスクリーン描画を伴います。単一の図形や互いに重ならない描画など、レイヤー全体への透過と同じ合成結果を保てる場合に限って `Paint.alpha` の調整で置き換えます（重なる複数の図形を個別に半透明で描くと、重なり部分の合成結果が変わります）。レイヤー合成が必要な場合は、対象範囲を最小矩形に絞るか、再利用できる表現であれば `RenderNode` などの代替手段を比較します。
- 一部の `MaskFilter`、`setShadowLayer` の組み合わせ、特殊な `PorterDuff` モードやクリッピング設定など、端末や API レベルによってハードウェア描画から外れたり高コストになったりする操作を毎フレーム実行していないか確認します。
- `Shader` の位置や角度が変わるだけであれば `Shader` 自体を作り直さず、`Shader.setLocalMatrix(matrix)` で変換を更新できるか確認します。

### `Bitmap` の再利用と一括ピクセル転送

- 毎フレーム `Bitmap.createBitmap(...)` で新しいビットマップを確保すると、大きなメモリ割り当てとテクスチャ再アップロードが発生します。サイズと形式が固定なら既存の `Bitmap` を使い回し、デコード時も `BitmapFactory.Options.inBitmap` での再利用を検討します。
- ピクセル単位の読み書きで `Bitmap.getPixel(x, y)` / `setPixel(x, y, color)` をループ内で呼ぶと、1 ピクセルごとに JNI 境界を跨ぎます。`getPixels(...)` / `setPixels(...)` で事前確保した `IntArray` に一括転送して Kotlin 側で連続走査します。

### 変換プロパティと再描画範囲

平行移動、拡大縮小、回転、透過度の変化だけで済むアニメーションを、毎フレーム `invalidate()` して CPU 側で `onDraw` し直している場合、描画リストの再記録と再転送が毎フレーム発生します。

- レイアウトや描画内容自体が変わらない変換であれば、View の変換プロパティ（`translationX`, `scaleX`, `rotation`, `alpha` 等）や `RenderNode` を使い、RenderThread 側で処理できるか確認します。
- 動的に内容が変わる部分と静的な背景・レイヤーが混在する場合は、静的な部分を分離して毎フレーム再描画する範囲を絞ります。

## フレーム処理の計測と検証の切り分け

フレームの実行時間は、中央値に加え、p95・p99・最大値とフレームあたりの割り当て量を確認します。中央値では見えにくい GC やロック待ちの影響を把握するためです。60 Hz で約 16.7 ms、120 Hz で約 8.3 ms のリフレッシュ周期は、予算を見積もる目安にします。

- 状態更新や物理演算など Android グラフィックスに依存しない処理は、代表的な入力を使い、分位点を評価できる回数のフレームを連続実行して、1 フレームあたりの時間分布を比較します。[JUnit による簡易計測](./micro-benchmarking.md)
- 描画コストや締め切り超過は、実機でアプリの表示結果とともに確認します。View のウィンドウ描画では API 31 以降の `FrameMetrics.DEADLINE` など、描画方式に合う指標を選びます。RenderThread や GPU の詳細な解析では、利用可能なプロファイラや Perfetto を使います。
