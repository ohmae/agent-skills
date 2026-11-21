package com.example.perf

data class User(
    val id: String,
    val name: String,
    val region: String,
    val isActive: Boolean
)

data class Transaction(
    val id: String,
    val userId: String,
    val rawTags: String,
    val amountCents: Int
)

data class RegionSummary(
    val region: String,
    val activeTransactionCount: Int,
    val totalAmountCents: Long,
    val topTagSummary: String
)

/**
 * スキル検証用のサンプルクラス。
 * 意図的に以下のパフォーマンス劣化要因（アンチパターン）を含めています：
 * 1. [メモリアクセス / キャッシュの罠] 軽量なタグ正規化や税計算に対する逆効果な HashMap キャッシュ（ボクシング・ハッシュ計算・キャッシュミスの方が重い）
 * 2. [メモリアクセス / ループ分断] 複数パスに分かれた多段 map/filter チェーンによる中間リスト生成とキャッシュ汚染
 * 3. [計算量] ループ内での List.find による O(N * M) の線形探索
 * 4. [アロケーション] ループ内での Regex("...") の毎回コンパイルと文字列 `+` 結合
 */
class TransactionAnalyzer {

    // ❌ アンチパターン1 (逆効果なキャッシュ):
    // 単純な整数演算（amountCents * 110 / 100）を節約しようとして HashMap にメモ化しているが、
    // Int のオートボクシング (Integer 生成) と HashMap の Node ポインタ追跡（CPU キャッシュミス）の方が
    // 元の乗除算（1〜2クロック）より数十倍遅い
    private val taxMemoCache = HashMap<Int, Long>()

    private fun calculateAmountWithTaxSlow(amountCents: Int): Long {
        return taxMemoCache.getOrPut(amountCents) {
            (amountCents.toLong() * 110L) / 100L
        }
    }

    fun summarizeByRegionSlow(
        users: List<User>,
        transactions: List<Transaction>,
        targetRegions: List<String>
    ): List<RegionSummary> {
        taxMemoCache.clear()

        // ❌ アンチパターン2 & 3 & 4:
        // transactions ごとに users.find (O(N*M)) と targetRegions.contains を呼び、
        // さらに多段 map / filter で中間リストを大量に作って CPU キャッシュを汚染している
        val enriched = transactions
            .map { tx ->
                val user = users.find { u -> u.id == tx.userId }
                tx to user
            }
            .filter { (_, user) ->
                user != null && user.isActive && targetRegions.contains(user.region)
            }
            .map { (tx, user) ->
                // ❌ ループ内で毎回 Regex インスタンスをコンパイル
                val normalizedTags = tx.rawTags
                    .split(Regex("[,;|]+"))
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                val taxedAmount = calculateAmountWithTaxSlow(tx.amountCents)
                Triple(user!!.region, taxedAmount, normalizedTags)
            }

        val grouped = enriched.groupBy { it.first }

        return grouped.entries
            .sortedBy { it.key }
            .map { (region, entries) ->
                val totalAmount = entries.map { it.second }.sum()

                // タグ集計
                val allTags = entries.flatMap { it.third }
                val uniqueTags = mutableListOf<String>()
                for (tag in allTags) {
                    if (!uniqueTags.contains(tag)) {
                        uniqueTags.add(tag)
                    }
                }
                val topTags = uniqueTags
                    .map { tag -> tag to allTags.count { it == tag } }
                    .sortedByDescending { it.second }
                    .take(3)

                // ❌ ループ内での文字列 += 結合
                var summaryText = ""
                for ((index, pair) in topTags.withIndex()) {
                    if (index > 0) summaryText += ", "
                    summaryText += pair.first + ":" + pair.second
                }

                RegionSummary(
                    region = region,
                    activeTransactionCount = entries.size,
                    totalAmountCents = totalAmount,
                    topTagSummary = summaryText
                )
            }
    }
}
