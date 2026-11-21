# 最適化レポートの出力例

`kotlin-performance-optimizer` を実行した際の、静的解析・仮説整理・修正・簡易計測レポートの記述例です。

---

## 対象

- ファイル: `GridSignalProcessor.kt` (`computeEnergy`)
- 入力条件: `1024 × 1024` の 2 次元整数グリッド（要素数 1,048,576）

## 1. 静的解析と改善仮説

### 検出箇所 1: 列優先走査とネスト配列のポインタチェイシング

- **箇所**: `GridSignalProcessor.kt:12-21` (`computeEnergy`)
- **問題（なぜ遅いか）**:
  - `Array<IntArray>` は行ごとにヒープ上の別配列として配置されているにもかかわらず、外側を `x`、内側を `y` で回す列優先走査を行っている。
  - 1 要素読むたびに別配列へのポインタ参照とキャッシュライン跨ぎが発生し、CPU キャッシュヒット率が低下する。
- **改善仮説**:
  - データを 1 次元の `IntArray`（行優先フラット配置）にし、内側ループを `x` にして連続走査することでキャッシュ局所性を高める。
  - **トレードオフ**: 2 次元添字 `[y][x]` の代わりに `y * width + x` の計算が必要になる。

### 検出箇所 2: 軽量算術に対する `HashMap<Int, Int>` メモ化

- **箇所**: `GridSignalProcessor.kt:17-20`
- **問題（なぜ遅いか）**:
  - 単純な整数演算 `(v * v) + (v ushr 1)` の結果を `HashMap<Int, Int>` にキャッシュしている。
  - 参照のたびに `Int` のボクシング、ハッシュ計算、バケットからエントリノードへのポインタ追跡が発生し、再計算より高いコストになっている。
- **改善仮説**:
  - メモ化を廃止し、ループ内でプリミティブのまま直接計算する。

## 2. 修正内容

```kotlin
// 修正前
fun computeEnergySlow(grid: Array<IntArray>, width: Int, height: Int): Long {
    val cache = HashMap<Int, Int>()
    var total = 0L
    for (x in 0 until width) {
        for (y in 0 until height) {
            val v = grid[y][x]
            val energy = cache.getOrPut(v) { (v * v) + (v ushr 1) }
            total += energy
        }
    }
    return total
}

// 修正後
fun computeEnergyFast(flatGrid: IntArray, width: Int, height: Int): Long {
    var total = 0L
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            val v = flatGrid[rowOffset + x]
            total += ((v * v) + (v ushr 1)).toLong()
        }
    }
    return total
}
```

## 3. 検証結果（記入例）

> 注: 以下に記載する確認結果および計測値は、レポート書式を示すための**架空の記入例**です。実際に報告する際は、対象環境で実施したテスト結果と実測値に置き換えてください。

- **正確性**: 既存のユニットテストの通過、および同一入力に対する修正前後の出力一致（`assertEquals` 等）を記載します（例: 既存テスト全件通過、前後出力の一致を確認）。
- **計測環境**: 対象ランタイム・ビルド条件・計測条件を記載します（例: OpenJDK 21 / JUnit 簡易ベンチマーク、ウォームアップ 20 回、計測 50 回の中央値）。

| 実装 | Median（記入例） | 比較（記入例） |
| --- | ---: | ---: |
| 修正前（列優先 + `HashMap` メモ化） | 7.500 ms | baseline |
| 修正後（行優先フラット配列 + 直接計算） | 0.650 ms | 約 11.5x 高速化（91.3% 削減） |
