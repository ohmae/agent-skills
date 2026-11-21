# Kotlin のパフォーマンス上の問題と対策

静的解析で確認する問題と対策をまとめます。メモリアクセスと CPU キャッシュ局所性を優先して確認します。

## メモリアクセスと CPU キャッシュ局所性

現代の CPU では、演算器（ALU）での計算そのものよりも、メインメモリ（RAM）から CPU キャッシュ（L1/L2/L3）へデータを読み込む待ち時間（Memory Stall）がボトルネックの大半を占めます。計算量オーダーが変わらなくても、CPU キャッシュヒット率を高めるだけで処理速度が数倍〜十数倍向上 します。

### ループの処理順序とストライド

- 問題: 2次元データ（画像ピクセル、グリッド、行列など）を列方向（外側の添字を内側ループで動かす）に走査すると、アクセスするメモリアドレスが毎回大きく飛び（ストライドアクセス）、CPU キャッシュライン（通常 64 bytes）に載った隣接データがすべて無駄になりキャッシュミスが多発します。
- 対策: メモリ上で連続している方向（Row-major: 行方向）に内側ループが走るようループ順序を入れ替えます。また、2次元配列 `Array<IntArray>`（ポインタの配列）よりも、1次元の `IntArray(width * height)` にフラット化する方がオブジェクトヘッダとポインタ参照が消え、プリフェッチャが最大限に効きます。

```kotlin
// 変更前。列優先走査 + Array<IntArray> によるポインタ間接参照とキャッシュミス多発
fun sumGridColumnMajor(grid: Array<IntArray>, width: Int, height: Int): Long {
    var sum = 0L
    for (x in 0 until width) {
        for (y in 0 until height) {
            sum += grid[y][x] // 毎回異なる IntArray オブジェクトへジャンプ（キャッシュミス）
        }
    }
    return sum
}

// 変更後。連続メモリの走査順序（行優先）への変更、または1次元フラット配列化
fun sumGridRowMajor(grid: IntArray, width: Int, height: Int): Long {
    var sum = 0L
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            sum += grid[rowOffset + x] // 配列の連続した要素を読み込む
        }
    }
    return sum
}
```

### ループ統合

- 問題: 大きな配列やリストに対して「パス1: 全要素を正規化」「パス2: 全要素をフィルタ・集計」のようにループを分けると、パス1の途中でデータが L1/L2 キャッシュから追い出され（Eviction）、パス2でもう一度メインメモリから読み直すことになります。
- 対策: 可能な限り 1 回のループ（Single Pass）に処理を統合し、データが L1 キャッシュ（あるいは CPU レジスタ）に載っているうちにすべての演算を完了させます。

### ポインタチェイシングとデータ構造のフラット化

- 問題: JVM / Kotlin では、`List<Particle>` や `Array<Particle>` は「`Particle` オブジェクトへの参照（ポインタ）が並んだ配列」です。各 `Particle` インスタンス自体はヒープ上のバラバラな位置に配置される可能性があり、要素ごとにオブジェクトヘッダ（12〜16 bytes）と参照ジャンプが発生します。
- 対策:
  - 要素数が多く高頻度で走査するデータは、オブジェクトの配列（AoS: Array of Structures）からプリミティブ配列（SoA: Structure of Arrays、またはインターリーブされた単一 `FloatArray` / `LongArray`）へ変更することを検討します。
  - 2つの `Int`（例: `x, y` 座標）を持つ小さなクラスであれば、`Long` 1つにビットパックした `@JvmInline value class` と `LongArray` で表現すると、完全に連続したメモリ領域でヒープオブジェクト割り当てゼロになります。

### メモ化のコスト

- 問題: 計算量を減らそうとして `val cache = HashMap<Key, Result>()` や `ConcurrentHashMap` を導入した結果、元の計算をそのまま実行するよりも遅くなるケースが非常に多く存在します。
- なぜ起きるか:
  1. `HashMap` のルックアップには、キーオブジェクトの生成（またはプリミティブのボクシング）、`hashCode()` 計算、`table` 配列へのランダムアクセス（巨大な Map ほど L2/L3 キャッシュミスが起きる）、`Node` オブジェクトのポインタ追跡、`equals()` 比較が必要です。
  2. 元の計算が「四則演算・ビット演算・短い配列走査・軽量な文字列操作（数十ナノ秒以下）」である場合、キャッシュのルックアップ（特にキャッシュミス時や GC 負荷増加時）の方がはるかに高コストになります。
- 判断基準と対策:
  - 安易に `HashMap` でメモ化しない: 元の計算コストが十分に高い（I/O、複雑な正規表現パース、重い再帰や暗号計算など）か、またはヒット率が極めて高くワーキングセットが L1/L2 キャッシュに収まるサイズかを必ずベンチマークで検証します。
  - キャッシュが必要な場合も連続メモリを使う: キーが狭い範囲の整数（`0..N`）なら `HashMap<Int, V>` ではなく単純な配列（`IntArray` / `Array<V>`）のインデックス参照にします。どうしてもプリミティブキーの Map が必要な場合は、ボクシングと Node ラッパーを排除したオープンアドレス法の `androidx.collection.IntIntMap` / `LongLongMap` 等を使用します。

### 小〜中規模データの検索

- 問題: 「検索は `O(N)` の `List` より `O(1)` の `HashSet` が常に速い」と考えて、要素数が 10〜50 個程度の小さなリストに対しても毎回 `toHashSet()` や `associateBy` を実行してしまう。
- 対策:
  - 要素数が少ない（目安として数十要素以下）かつ検索回数も少ない場合、`HashSet` を構築するアロケーション＋ハッシュ計算コストの方が、連続した `ArrayList` を数回線形走査するコストよりも高くつきます。
  - 「構築コスト（1回）＋ 検索コスト（`M` 回）」のトータルでペイするかを考え、小規模データや検索回数が少ない箇所ではフラットな配列・リストを維持します。

## コレクションと Sequence

### 多段コレクション操作の実装方法

- 問題: Kotlin の `Iterable.map`, `filter`, `flatMap`, `sortedBy` 等は、各ステップごとに新しい `ArrayList` をヒープに確保して全要素を書き込みます。これはアロケーション負荷だけでなく、CPU キャッシュを中間リストの読み書きで塗りつぶす（Cache Pollution）原因になります。
- 実測に基づく判断基準（Kotlin 2.x + JDK 21 / Android ART 共通）:
  - `Sequence` の注意点: `asSequence()` は中間リスト生成を省けますが、各ステップの `Sequence` / `Iterator` ラッパーと非インラインのラムダ仮想呼び出し（およびプリミティブのボクシング）が残るため、要素ごとの処理が軽い場合は `Iterable` と大差ないか、場合によっては遅くなります（早期終了 `take` / `first` がある場合や、中間リストが巨大でメモリ帯域を圧迫する場合にのみ有効）。
  - ホットパスでは「ベタ書き `for` ループ」が圧倒的に最速（約 10 倍以上高速）: 単一の `for` ループ（Loop Fusion）に書き下すと、インライン展開によってプリミティブが一切ボクシングされず CPU レジスタ上で処理が完結するため、`Iterable` と `Sequence` の両方に対して 10倍以上の速度差が出ます。可読性とのバランスを見極め、真のホットパスでは迷わずベタ書きループへ書き換えます。

```kotlin
// 変更前。map, filter のたびに中間 ArrayList 生成 + ボクシング発生
val sum = list.map { it.first }.filter { it > 0 }.sum()

// Sequence。中間リストは消えるが、イテレータの仮想呼び出しとボクシングが残る
val sum = list.asSequence().map { it.first }.filter { it > 0 }.sum()

// 変更後（単一のループ）。抽出と集計を 1 回の走査で行う
var sum = 0
for (item in list) {
    val first = item.first
    if (first > 0) {
        sum += first
    }
}
```

### コレクションの初期容量

- 問題: サイズが事前に分かっている、あるいは見積もれるにもかかわらずデフォルトコンストラクタの `ArrayList()` や `HashMap()` に大量に `add` / `put` すると、内部配列の再確保（リサイズと `Arrays.copyOf` / 再ハッシュ）が複数回発生します。
- 対策: `ArrayList(expectedSize)`、`HashMap(expectedSize)`、`buildList(expectedSize) { ... }` を利用します。

### プリミティブ配列

- 問題: 数値計算、画像処理、座標計算、DP（動的計画法）などで `List<Int>` や `Array<Int>` を使うと、各要素が `java.lang.Integer` にボクシングされ、メモリ消費量が 3〜4 倍になりキャッシュ局所性も大幅に悪化します。
- 対策: 数値の配列操作には `IntArray`, `FloatArray`, `DoubleArray` 等のプリミティブ配列を使用します。

## ボクシングとオブジェクト割り当て

Android / JVM では、短命オブジェクトの大量生成が GC（ガベージコレクション）ポーズを引き起こすだけでなく、新しく確保されるメモリ領域が毎回変わることで L1/L2 キャッシュのヒット率を下げます。

### 複数値の返却

- 問題: 座標計算などで複数の値を返すために `Pair<Float, Float>` やデータクラスを返し、呼び出し側で `val (x, y) = calc(p)` のように分解宣言を使うと、呼び出しのたびに `Pair` インスタンスの生成 ＋ ジェネリクスによる `Float` → `java.lang.Float` のオートボクシング（計 3 オブジェクト生成）が発生します。
  - 近年のサーバー JVM (HotSpot C2) では関数が完全にインライン展開されると Escape Analysis（Scalar Replacement）で消えることもありますが、Android Runtime (ART) やインライン閾値を超える関数、インターフェース越しの呼び出しでは Escape Analysis が効かず約 8〜10 倍の速度低下を招きます。
- 対策（2つのアプローチ）:
  1. 引数への書き出し（Out Parameter パターン）: 呼び出し側で結果格納用のミュータブルオブジェクト（例: `PointF`）を 1 つだけ生成してループ外で使い回し、関数引数として渡してプロパティを書き換えます。
  2. ビットパックした `@JvmInline value class`（推奨・イミュータブル維持）: 2つの 32-bit 値（`Float` 2つや `Int` 2つ）であれば、1つのプリミティブ `Long`（64-bit）にビットシフトでパックした `value class` を返すことで、イミュータブルな関数シグネチャと分解宣言の書き味を保ったまま、Out Parameter と完全に同等のゼロアロケーション（プリミティブ `long` 返却）を実現できます（Android Compose の `Offset` や `IntSize` でも採用されているモダン手法です）。

```kotlin
// 変更前。毎回 Pair とボクシングされた Float×2 がヒープに生成される (ART等で約9〜10倍遅い)
fun doublePointSlow(p: PointF): Pair<Float, Float> = Pair(p.x * 2f, p.y * 2f)

// 変更後 A（Out Parameter）。引数で受け取ったインスタンスを再利用して書き込む
fun doublePointOut(p: PointF, out: PointF) {
    out.x = p.x * 2f
    out.y = p.y * 2f
}

// 変更後 B（Packed Value Class）。64bit Long に Float×2 をパックしてプリミティブ返却
@JvmInline
value class PackedPointF(val packed: Long) {
    constructor(x: Float, y: Float) : this(
        (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)
    )
    val x: Float get() = Float.fromBits((packed ushr 32).toInt())
    val y: Float get() = Float.fromBits(packed.toInt())
    inline operator fun component1(): Float = x
    inline operator fun component2(): Float = y
}
```

### 可変長引数と配列コピー

- 問題: `fun process(vararg values: Int)` のような可変長引数関数に対して、既存の配列をスプレッド演算子 `process(*array)` で渡すと、Kotlin コンパイラは呼び出し先での配列書き換えを防ぐため毎回必ず `Arrays.copyOf`（または `.clone()`）による配列全体のメモリコピーを生成します。さらに `process(*arr1, x, *arr2)` のように複数要素を結合すると、`IntSpreadBuilder` インスタンス生成と複数回の配列コピーが発生し、3〜5倍以上遅くなります。
- 対策: ホットパスやループ内から呼ばれる関数では `vararg` を使わず、`IntArray` や `List<T>` をそのまま受け取るオーバーロードを用意して配列コピーを回避します。

### ループや高頻度関数でのインスタンス生成

- 問題: `Regex("...")` や `".*".toRegex()`、`DateTimeFormatter` などを関数やループが呼ばれるたびに生成する。
- 対策: `Regex` 等は `companion object` やトップレベルの `private val` に抽出して再利用します。また、単純な区切り文字分割なら `String.split(',', ';')` のように `Char` 引数のオーバーロードを使うと正規表現エンジン自体を回避できます。

### Nullable プリミティブとジェネリクスによるオートボクシング

- 問題: `Int?` や `Double?` をローカル変数・返り値・配列要素に使うと、JVM 上では必ずボクシングされた `Integer` / `Double` オブジェクトになります。
- 対策: ホットパスではセンチネル値（例: `-1` や `Double.NaN`）や `@JvmInline value class` を活用して `null` によるボクシングを回避します。

### `@JvmInline value class` と `inline` 関数

- 問題: 型安全性のために導入した ID ラッパー（`data class UserId(val value: Long)`）や単位ラッパー（`data class Meter(val value: Double)`）が大量に生成される。
- 対策: `@JvmInline value class` に変更することで、実行時はプリミティブ型として扱われヒープ割り当てをゼロにできます。また、高頻度で呼ばれるラムダ引数付き関数には `inline` 修飾子を付与し、`Function` オブジェクトの生成と仮想メソッド呼び出しを排除します。

## アルゴリズムとデータ構造

### 大規模データのループ内線形探索

- 問題: 要素数 `N` と `M` がともに大きい（例: 数百〜数万件）ループ内で `List.contains` や `List.find` を呼ぶと `O(N × M)`になります。
- 対策: ループの前に `toHashSet()` または `associateBy { ... }` / `groupBy { ... }` でルックアップ用の `Set` / `Map` を構築します。なお、 `N, M` が極小の場合は配列のままの方が速いことがあるため、データ規模を確認します。

```kotlin
// 変更前。O(N * M) — N, M が数千件あると数千万回の比較が発生
fun matchUsers(orders: List<Order>, users: List<User>): List<Pair<Order, User?>> {
    return orders.map { order ->
        val user = users.find { it.id == order.userId }
        order to user
    }
}

// 変更後。O(N + M)
fun matchUsers(orders: List<Order>, users: List<User>): List<Pair<Order, User?>> {
    val userById = users.associateBy { it.id }
    return orders.map { order ->
        order to userById[order.userId]
    }
}
```

### ループ内の文字列結合

- 問題: ループ内で `var result = ""` に対して `result += item` を繰り返すと、毎回新しい `String`（と内部配列）がコピー生成され `O(N^2)` のアロケーションとメモリコピーが発生します。
- 対策: `buildString` または `joinToString` を使用します。サイズが見積もれる場合は `buildString(expectedCapacity) { ... }` を指定します。

### 小規模データのソート

- 問題: ソートや探索を自前でナイーブに実装したり、逆に要素数が極小（例: `N ≤ 10 〜 32`）の配列に対して過剰に複雑なデータ構造を導入する。
- 知見:
  - 標準の `IntArray.sort()`（JVM の `Arrays.sort()`）は高度にチューニングされた Dual-Pivot Quicksort であり、内部で要素数が小さい区間（44未満など）では自動的に 挿入ソート（Insertion Sort） に切り替わります。
  - 要素数が 10 個程度の極小データでは、再帰呼び出しを持つクイックソート（`O(N log N)`）よりも、連続メモリを単純ループで舐める挿入ソートやバブルソート（`O(N^2)`）の方が CPU キャッシュと分岐予測の効率が良く高速に動作します。したがって、自前のナイーブなクイックソート等は書かず標準の `sort()` を使うか、固定長の極小要素（3〜10要素など）の頻繁なソートではインラインの挿入ソートを利用します。

## Coroutines / Flow のオーバーヘッド

優先度は他の確認項目より低いですが、非同期処理が絡む計算ロジックで以下が見られた場合は改善します。

- ループ内での細粒度すぎる `withContext` / `async`:
  - 1 要素の計算がマイクロ秒単位で終わるにもかかわらず、ループ内で毎回 `withContext(Dispatchers.Default)` や `async` を呼ぶと、スレッドディスパッチと Continuation オブジェクト生成、さらに別コアへのスレッド移動による CPU キャッシュのコールド化が計算本体を圧倒します。適切なチャンクサイズ（`chunked(batchSize)`）にまとめてから並列化するか、ループの外側で 1 回だけ `withContext` を呼びます。
