# 最適化レポートの出力例

以下は架空のレポートです。実際の報告では、対象コードの箇所、テスト結果、実測値を記録します。

## 1. 静的解析と改善仮説

`GridEnergy.kt` にある集計処理を調べました。入力は 1024 × 1024 の整数グリッドです。

- `computeEnergySlow` の内側ループは、要素ごとに別の行配列を参照します。行内を連続して走査し、グリッドを行優先の `IntArray` に格納すると、参照追跡とメモリ待ち時間を減らせると考えました。
- 各要素で `HashMap<Int, Int>` を引き、軽量な整数演算の結果を再利用しています。キーのボクシング、ハッシュ計算、エントリの参照が、直接計算するより高くつくと考えました。

## 2. 修正内容

グリッドの生成時に行優先で格納し、集計では行内を連続走査します。メモ化を外して集計時に直接計算します。一次元配列の添字は行の先頭位置と列番号から求めます。

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

既存のユニットテストはすべて通過し、同じ整数グリッドを使った変更前後の集計結果も一致しました。

OpenJDK 21 上の JUnit でウォームアップ 20 回、計測 50 回を実施しました。入力の生成は計測の外で行い、修正前後とも集計処理を計測しました。

| 実装 | 中央値 |
| --- | ---: |
| 修正前（列優先走査、`HashMap` でメモ化） | 7.500 ms |
| 修正後（行優先の一次元配列、直接計算） | 0.650 ms |

両方の変更を合わせて、集計時間の中央値は 6.850 ms 短縮し、91.3% 減少しました。入力の格納形式に変更があるため、既存の二次元配列を毎回変換する呼び出し経路では、その変換時間も含めて比較します。
