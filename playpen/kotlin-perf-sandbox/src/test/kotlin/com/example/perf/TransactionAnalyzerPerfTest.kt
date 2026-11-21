package com.example.perf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.measureTime

class TransactionAnalyzerPerfTest {

    private val analyzer = TransactionAnalyzer()

    private fun generateTestData(
        userCount: Int = 4_000,
        txCount: Int = 12_000
    ): Triple<List<User>, List<Transaction>, List<String>> {
        val random = Random(42)
        val regions = listOf("JP", "US", "EU", "APAC", "LATAM", "MEA")
        val targetRegions = listOf("JP", "US", "EU")
        val sampleTags = listOf("food", "tech", "travel", "book", "game", "music", "health", "gift")

        val users = List(userCount) { i ->
            User(
                id = "user-$i",
                name = "User $i",
                region = regions[random.nextInt(regions.size)],
                isActive = random.nextBoolean()
            )
        }

        val transactions = List(txCount) { i ->
            val t1 = sampleTags[random.nextInt(sampleTags.size)]
            val t2 = sampleTags[random.nextInt(sampleTags.size)]
            val t3 = sampleTags[random.nextInt(sampleTags.size)]
            Transaction(
                id = "tx-$i",
                userId = "user-${random.nextInt(userCount)}",
                rawTags = "$t1, $t2; $t3",
                amountCents = random.nextInt(100, 50_000)
            )
        }

        return Triple(users, transactions, targetRegions)
    }

    private fun <T> runBenchmark(
        name: String,
        warmupIterations: Int = 10,
        measureIterations: Int = 20,
        block: () -> T
    ): Double {
        var blackhole = 0
        repeat(warmupIterations) {
            blackhole = blackhole xor (block()?.hashCode() ?: 0)
        }

        System.gc()
        val samplesMs = DoubleArray(measureIterations)
        repeat(measureIterations) { i ->
            val duration = measureTime {
                blackhole = blackhole xor (block()?.hashCode() ?: 0)
            }
            samplesMs[i] = duration.toDouble(DurationUnit.MILLISECONDS)
        }

        samplesMs.sort()
        val medianMs = samplesMs[measureIterations / 2]
        val minMs = samplesMs.first()
        println(
            "[Perf] %-28s | Median: %8.3f ms | Min: %8.3f ms (bh=%d)".format(
                name, medianMs, minMs, blackhole
            )
        )
        return medianMs
    }

    @Test
    fun verifyBaselineAndBenchmark() {
        val (users, transactions, targetRegions) = generateTestData()

        val result = analyzer.summarizeByRegionSlow(users, transactions, targetRegions)
        assertEquals(listOf("EU", "JP", "US"), result.map { it.region })

        runBenchmark("summarizeByRegionSlow") {
            analyzer.summarizeByRegionSlow(users, transactions, targetRegions)
        }
    }
}
