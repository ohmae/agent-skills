package com.example.perf

import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.measureTime

/**
 * Qiita記事「Kotlinハイパフォーマンスプログラミング」(https://qiita.com/ryo_mm2d/items/aaafcd67f63455dbb1f8)
 * の実験内容を最新環境 (Kotlin 2.4.x + JDK 21) で再検証し、現在でも通用するかと最新手法（Packed value class 等）を検証するテスト。
 */
class QiitaArticleExperimentsBenchmarkTest {

    private fun <T> runBenchmark(
        name: String,
        warmupIterations: Int = 30,
        measureIterations: Int = 50,
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
            "[Perf] %-36s | Median: %8.3f ms | Min: %8.3f ms (bh=%d)".format(
                name, medianMs, minMs, blackhole
            )
        )
        return medianMs
    }

    // =========================================================================
    // 実験1: Pair返却 (ボクシング + インスタンス生成) vs 引数書き出し (Out parameter) vs Packed Value Class
    // =========================================================================
    class MutablePointF(var x: Float = 0f, var y: Float = 0f)

    // 最新の代替案: Long 1つに Float 2つをビットパックした @JvmInline value class (ヒープ割り当てゼロ & 不変風API)
    @JvmInline
    value class PackedPointF(val packed: Long) {
        constructor(x: Float, y: Float) : this(
            (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)
        )
        val x: Float get() = Float.fromBits((packed ushr 32).toInt())
        val y: Float get() = Float.fromBits(packed.toInt())
    }

    private fun doubleWithPair(p: MutablePointF): Pair<Float, Float> =
        Pair(p.x * 2f, p.y * 2f)

    private fun doubleWithOutParam(p: MutablePointF, out: MutablePointF) {
        out.x = p.x * 2f
        out.y = p.y * 2f
    }

    private fun doubleWithPackedValueClass(p: MutablePointF): PackedPointF =
        PackedPointF(p.x * 2f, p.y * 2f)

    fun experiment1_PairVsOutParamVsPackedValueClass() {
        println("\n=== 実験1: Pair返却 vs 引数書き出し vs Packed value class (100,000要素) ===")
        val n = 100_000
        val points = Array(n) { i -> MutablePointF(i * 0.5f, i * 1.5f) }

        val pairTime = runBenchmark("1a. Return Pair<Float, Float>") {
            var sum = 0f
            for (p in points) {
                val (x, y) = doubleWithPair(p)
                sum += x + y
            }
            sum
        }

        val outTime = runBenchmark("1b. Out Parameter (MutablePointF)") {
            var sum = 0f
            val out = MutablePointF()
            for (p in points) {
                doubleWithOutParam(p, out)
                sum += out.x + out.y
            }
            sum
        }

        val packedTime = runBenchmark("1c. Return @JvmInline PackedPointF") {
            var sum = 0f
            for (p in points) {
                val res = doubleWithPackedValueClass(p)
                sum += res.x + res.y
            }
            sum
        }

        println("  -> OutParam speedup over Pair: %.2fx".format(pairTime / outTime))
        println("  -> PackedValueClass speedup over Pair: %.2fx".format(pairTime / packedTime))
    }

    // =========================================================================
    // 実験2: コレクション操作メソッド (Iterable) vs Sequence vs ベタ書きループ
    // =========================================================================
    fun experiment2_CollectionVsSequenceVsLoop() {
        println("\n=== 実験2: Iterable vs Sequence vs ベタ書きループ (500,000要素) ===")
        val random = Random(42)
        val list = List(500_000) {
            Pair(random.nextInt(-1000, 1000), random.nextInt(-1000, 1000))
        }

        val iterableTime = runBenchmark("2a. Iterable (map.filter.sum)") {
            list.map { it.first }
                .filter { it > 0 }
                .sum()
        }

        val sequenceTime = runBenchmark("2b. Sequence (asSequence.map...)") {
            list.asSequence()
                .map { it.first }
                .filter { it > 0 }
                .sum()
        }

        val loopTime = runBenchmark("2c. Raw for-loop") {
            var sum = 0
            for (item in list) {
                val first = item.first
                if (first > 0) {
                    sum += first
                }
            }
            sum
        }

        println("  -> Sequence speedup over Iterable: %.2fx".format(iterableTime / sequenceTime))
        println("  -> Raw loop speedup over Iterable: %.2fx".format(iterableTime / loopTime))
        println("  -> Raw loop speedup over Sequence: %.2fx".format(sequenceTime / loopTime))
    }

    // =========================================================================
    // 実験3: 可変長引数 (vararg) + スプレッド演算子 (*) vs 配列渡し (IntArray)
    // =========================================================================
    private fun sumVararg(vararg values: Int): Int {
        var s = 0
        for (v in values) s += v
        return s
    }

    private fun sumIntArray(values: IntArray): Int {
        var s = 0
        for (v in values) s += v
        return s
    }

    fun experiment3_VarargSpreadVsArrayPass() {
        println("\n=== 実験3: 可変長引数 (vararg + spread *) vs 配列直接渡し ===")
        val arr1 = IntArray(1000) { it }
        val arr2 = IntArray(1000) { it * 2 }

        val spreadSingleTime = runBenchmark("3a. vararg single spread (*arr1) x10k") {
            var total = 0
            repeat(10_000) {
                // 単一配列の *arr1 でも防御的コピー (Arrays.copyOf / clone) が毎回走る
                total += sumVararg(*arr1)
            }
            total
        }

        val spreadMultiTime = runBenchmark("3b. vararg multi spread (*a1, *a2) x10k") {
            var total = 0
            repeat(10_000) {
                // IntSpreadBuilder による結合とコピーが毎回走る
                total += sumVararg(*arr1, 999, *arr2)
            }
            total
        }

        val directArrayTime = runBenchmark("3c. Direct IntArray pass x10k") {
            var total = 0
            repeat(10_000) {
                total += sumIntArray(arr1)
            }
            total
        }

        println("  -> Direct IntArray speedup over single spread (*arr): %.2fx".format(spreadSingleTime / directArrayTime))
        println("  -> Direct IntArray speedup over multi spread: %.2fx".format(spreadMultiTime / directArrayTime))
    }

    // =========================================================================
    // 実験4: ソートアルゴリズムと要素数 (N=10,000 vs N=10)
    // =========================================================================
    private fun bubbleSort(a: IntArray) {
        val n = a.size
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                if (a[i] > a[j]) {
                    val tmp = a[i]
                    a[i] = a[j]
                    a[j] = tmp
                }
            }
        }
    }

    private fun insertionSort(a: IntArray) {
        for (i in 1 until a.size) {
            val key = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > key) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = key
        }
    }

    private fun quickSortSimple(a: IntArray, low: Int = 0, high: Int = a.size - 1) {
        if (low >= high) return
        val pivot = a[(low + high) ushr 1]
        var i = low
        var j = high
        while (i <= j) {
            while (a[i] < pivot) i++
            while (a[j] > pivot) j--
            if (i <= j) {
                val tmp = a[i]
                a[i] = a[j]
                a[j] = tmp
                i++
                j--
            }
        }
        if (low < j) quickSortSimple(a, low, j)
        if (i < high) quickSortSimple(a, i, high)
    }

    fun experiment4_SortingLargeVsTiny() {
        println("\n=== 実験4: ソートアルゴリズム比較 (N=10,000 x 10回 vs N=10 x 10,000回) ===")
        val random = Random(42)
        val largeSource = Array(10) { IntArray(10_000) { random.nextInt() } }
        val tinySource = Array(10_000) { IntArray(10) { random.nextInt() } }

        runBenchmark("4a. Large (N=10k x10) IntArray.sort()") {
            var checksum = 0
            for (src in largeSource) {
                val copy = src.copyOf()
                copy.sort()
                checksum += copy[0]
            }
            checksum
        }

        runBenchmark("4b. Large (N=10k x10) QuickSort") {
            var checksum = 0
            for (src in largeSource) {
                val copy = src.copyOf()
                quickSortSimple(copy)
                checksum += copy[0]
            }
            checksum
        }

        runBenchmark("4c. Tiny (N=10 x10k) IntArray.sort()") {
            var checksum = 0
            for (src in tinySource) {
                val copy = src.copyOf()
                copy.sort()
                checksum += copy[0]
            }
            checksum
        }

        runBenchmark("4d. Tiny (N=10 x10k) QuickSort") {
            var checksum = 0
            for (src in tinySource) {
                val copy = src.copyOf()
                quickSortSimple(copy)
                checksum += copy[0]
            }
            checksum
        }

        runBenchmark("4e. Tiny (N=10 x10k) BubbleSort") {
            var checksum = 0
            for (src in tinySource) {
                val copy = src.copyOf()
                bubbleSort(copy)
                checksum += copy[0]
            }
            checksum
        }

        runBenchmark("4f. Tiny (N=10 x10k) InsertionSort") {
            var checksum = 0
            for (src in tinySource) {
                val copy = src.copyOf()
                insertionSort(copy)
                checksum += copy[0]
            }
            checksum
        }
    }

    // =========================================================================
    // 実験5: GridSignalProcessor (列優先 + 逆効果なHashMapメモ化 vs 行優先 + フラット配列直接計算)
    // =========================================================================
    fun experiment5_CacheLocalityAndCounterproductiveMemoization() {
        println("\n=== 実験5: CPUキャッシュ局所性 (列優先+HashMapメモ化 vs 行優先フラット配列直接演算) ===")
        val width = 1024
        val height = 1024
        val processor = GridSignalProcessor(width, height)

        val flatGrid = IntArray(width * height) { idx ->
            val y = idx / width
            val x = idx % width
            ((y * 31 + x * 17) and 0xFFFF)
        }

        val slowTime = runBenchmark("5a. ColumnMajor + HashMap Memo") {
            processor.computeEnergySlowWithBadLocalityAndCache()
        }

        val fastTime = runBenchmark("5b. RowMajor Flat + Direct Calc") {
            var totalEnergy = 0L
            for (i in flatGrid.indices) {
                val raw = flatGrid[i]
                totalEnergy += ((raw * 3) xor (raw ushr 4)) + 7
            }
            totalEnergy
        }

        println("  -> RowMajor Direct speedup over ColumnMajor+Memo: %.2fx".format(slowTime / fastTime))
    }
}
