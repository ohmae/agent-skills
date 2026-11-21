# JUnit + measureNanoTime / measureTime による簡易計測ガイド

大規模なベンチマークフレームワーク（JMH や Android Microbenchmark）を導入しなくても、既存の JUnit テスト上で素早く「仮説に基づく修正が本当に効果を生んだか」を検証するための手順です。

---

## 1. 簡易計測の鉄則（ノイズを減らすために）

JVM 上でマイクロ秒〜ミリ秒単位の計測を行う際、単に 1 回だけ `measureTime { ... }` を実行すると、クラスロードや JIT コンパイル（インタプリタ実行）の時間が支配的になり、正しい比較ができません。必ず以下のルールを守ってください。

1. **十分なデータ量 ($N$) を用意する**:
   - 1 回の実行が数ミリ秒〜数十ミリ秒程度になる入力サイズ（例: $N = 1,000 \sim 50,000$）でテストデータを固定生成（シード固定の `Random(42)` など）します。
2. **ウォームアップ（Warm-up）を必ず行う**:
   - 計測本番の前に、同じ関数を最低 10〜50 回（または合計 200ms〜500ms 程度）空回しして JIT コンパイルを促します。
3. **デッドコード削除（DCE）を防ぐ**:
   - 関数の戻り値を全く使わないと、JIT コンパイラが計算自体をスキップする可能性があります。戻り値のサイズやハッシュ値を合算（`blackhole` 変数に加算）し、最後に `assertTrue` や `println` で参照してください。
4. **複数回計測して中央値（Median）または平均値（Mean）をとる**:
   - GC 等による単発のスパイクを除外するため、複数イテレーション計測して中央値を比較指標にします。

---

## 2. コピペで使える軽量ベンチマークハーネス

対象プロジェクトの `src/test/kotlin/` 配下に一時的（または回帰確認用）に以下のようなテストを作成して `./gradlew test --tests "*PerfTest*"` で実行します。

```kotlin
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTime
import org.junit.Test

class QuickPerfBenchmarkTest {

    /**
     * 外部依存ゼロの軽量ベンチマークヘルパー
     */
    private fun <T> runBenchmark(
        name: String,
        warmupIterations: Int = 20,
        measureIterations: Int = 50,
        block: () -> T
    ): Double {
        var blackhole = 0

        // 1. Warm-up
        repeat(warmupIterations) {
            blackhole = blackhole xor (block()?.hashCode() ?: 0)
        }

        // 2. GC 影響を軽減し計測開始
        System.gc()
        val samplesMs = DoubleArray(measureIterations)
        repeat(measureIterations) { i ->
            val duration: Duration = measureTime {
                blackhole = blackhole xor (block()?.hashCode() ?: 0)
            }
            samplesMs[i] = duration.toDouble(DurationUnit.MILLISECONDS)
        }

        samplesMs.sort()
        val medianMs = samplesMs[measureIterations / 2]
        val minMs = samplesMs.first()
        val p90Ms = samplesMs[(measureIterations * 0.9).toInt()]

        println(
            "[Perf] %-28s | Median: %8.3f ms | Min: %8.3f ms | P90: %8.3f ms (bh=%d)".format(
                name, medianMs, minMs, p90Ms, blackhole
            )
        )
        return medianMs
    }

    @Test
    fun compareBeforeAndAfter() {
        // 固定シードや決定論的なテストデータを準備（計測ループの外で作る）
        val inputData = prepareTestData(size = 10_000)

        val beforeMedian = runBenchmark("Before (Original)") {
            processOriginal(inputData)
        }

        val afterMedian = runBenchmark("After (Optimized)") {
            processOptimized(inputData)
        }

        val speedup = beforeMedian / afterMedian
        println("[Perf] Speedup: %.2fx (%.1f%% reduction)".format(
            speedup,
            (1.0 - afterMedian / beforeMedian) * 100.0
        ))
    }
}
```

---

## 3. Before / After の比較方法

1. **同一プロセス内での並走比較（推奨）**:
   - リファクタリング前のロジックを一時的にテスト内の別関数（`processOriginal`）として残し、リファクタリング後の関数（`processOptimized`）と **1つのテスト内で連続して計測** すると、マシン負荷の揺らぎによる誤差を最小限に抑えられます。
   - さらにこの際、`assertEquals(processOriginal(inputData), processOptimized(inputData))` を最初に実行することで、**「出力結果が完全に一致していること（デグレしていないこと）」と「どれだけ速くなったか」を同時に証明** できます。
2. **確認後のクリーンアップ**:
   - 計測と検証が完了したら、比較用の旧実装コードは削除し、機能テスト（エッジケースを含む正確性テスト）をクリーンな状態で残します。
