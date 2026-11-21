package com.example.perf

/**
 * 計算量（Big-O）は全く同じ O(W * H) だが、メモリアクセスパターン（CPU キャッシュヒット率）と
 * 逆効果なメモ化キャッシュによって速度が劇的に変わることを検証するためのサンプルクラス。
 */
class GridSignalProcessor(
    val width: Int,
    val height: Int
) {
    // ポインタの配列（各行が別々のヒープオブジェクトになる可能性がある）
    private val boxed2DGrid: Array<IntArray> = Array(height) { y ->
        IntArray(width) { x -> ((y * 31 + x * 17) and 0xFFFF) }
    }

    // 1次元にフラット化した連続メモリ配列
    private val flatGrid: IntArray = IntArray(width * height) { idx ->
        val y = idx / width
        val x = idx % width
        ((y * 31 + x * 17) and 0xFFFF)
    }

    // ❌ アンチパターン1: 列優先（Column-Major）走査 + 逆効果な HashMap メモ化
    // 計算量は O(width * height) だが、内側のループで y を動かすと毎回別オブジェクトの離れたメモリアドレスへ飛び、
    // CPU キャッシュライン（64 bytes）が全く活かされない。
    // さらに軽量なビット・算術演算を HashMap にキャッシュしているため、ボクシングとキャッシュミスで劇的に遅くなる。
    fun computeEnergySlowWithBadLocalityAndCache(): Long {
        val memo = HashMap<Int, Int>()
        var totalEnergy = 0L

        for (x in 0 until width) {
            for (y in 0 until height) {
                val raw = boxed2DGrid[y][x]
                // 軽い計算をキャッシュして逆に遅くなる典型例
                val transformed = memo.getOrPut(raw) {
                    ((raw * 3) xor (raw ushr 4)) + 7
                }
                totalEnergy += transformed
            }
        }
        return totalEnergy
    }
}
