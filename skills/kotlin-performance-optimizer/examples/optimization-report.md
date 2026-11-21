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

## 3. 検証結果

- **正確性**: 既存のユニットテストおよび同一入力での前後出力一致（`assertEquals`）を確認済み。
- **計測環境**: OpenJDK 21 / JUnit 簡易ベンチマーク（ウォームアップ 20 回、計測 50 回の中央値）

| 実装 | Median | 比較 |
| --- | ---: | ---: |
| 修正前（列優先 + `HashMap` メモ化） | 7.699 ms | baseline |
| 修正後（行優先フラット配列 + 直接計算） | 0.652 ms | **約 11.8x 高速化（91.5% 削減）** |

> 注: 数値は計測環境の例です。実際のプロジェクトでは対象ランタイム・代表的な入力サイズで計測した結果を記載します。
