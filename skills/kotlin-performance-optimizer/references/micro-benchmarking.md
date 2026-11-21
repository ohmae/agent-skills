# JUnit による簡易計測

大規模なベンチマークフレームワーク（JMH や Android Microbenchmark）を導入しなくても、既存の JUnit テスト上で素早く「仮説に基づく修正が本当に効果を生んだか」を検証するための手順です。

## 計測条件

計測範囲は仮説に合わせます。定常状態の比較では、初回のクラスロードや JIT コンパイルの影響を抑えます。起動直後の処理が対象なら、初回の条件を再現して計測します。

1. 入力サイズを決める
   - 実際の入力サイズ・分布・アクセス順序を再現し、前後で同じ条件を使います。乱数を使うならシードを固定します。1 回の処理が短すぎる場合は、入力を大きくするとアクセス特性が変わるため、代表的な入力のまま複数回分をまとめて計測する方法を検討します。
2. ウォームアップする
   - 定常状態を測る場合は、計測と同じ処理を事前に繰り返します。下の回数は初期値として使い、計測値の推移を見て調整します。
3. デッドコード削除（DCE）を防ぐ
   - 結果を軽量な方法で消費します。結果の利用まで含めて測る場合は実際の呼び出し側に合わせます。サイズの参照だけで計算の一部が省かれたり、出力全体のハッシュ計算が測定対象より重くなったりする場合は、出力に合う消費方法に変えます。
4. 複数回計測する
   - 中央値や分位点を比較します。割り当てや遅延のばらつきが問題なら、GC を含む計測値の分布も確認します。

## ベンチマークのテンプレート

以下は `Int` を返す処理を比べる JVM 用の例です。テストの配置と実行コマンドは対象モジュールに合わせます。たとえば標準の Gradle JVM プロジェクトでは `src/test/kotlin/` に置き、`./gradlew test --tests "*QuickPerfBenchmarkTest*"` で実行できます。

```kotlin
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTime
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickPerfBenchmarkTest {

    /**
     * ベンチマーク用ライブラリを追加せずに計測する
     */
    private inline fun runBenchmark(
        name: String,
        warmupIterations: Int = 20,
        measureIterations: Int = 50,
        block: () -> Int
    ): Double {
        var blackhole = 0

        // ウォームアップ
        repeat(warmupIterations) {
            blackhole = 31 * blackhole + block()
        }

        val samplesMs = DoubleArray(measureIterations)
        repeat(measureIterations) { i ->
            val duration: Duration = measureTime {
                blackhole = 31 * blackhole + block()
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
        assertEquals(processOriginal(inputData), processOptimized(inputData))

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

## 変更前後の比較

1. 前後の条件をそろえる
   - 同じプロセスで手軽に比較できます。実行順序で差が変わる場合は順序を入れ替え、初回実行や共有状態の影響を見る場合はプロセスを分けます。入力を変更する処理では、毎回同等の状態から始めます。
   - 計測の外で、同じ入力に対する前後の結果が一致することを出力型に合う方法で確認します。
2. 比較用のコードを片付ける
   - 計測と検証が完了したら、比較用の旧実装コードは削除し、機能テスト（エッジケースを含む正確性テスト）をクリーンな状態で残します。
