# JUnit による簡易計測

既存の JUnit テスト環境を使い、仮説に基づく修正の効果を測ります。

## 計測条件

計測範囲は仮説に合わせます。定常状態の比較では、初回のクラスロードや JIT コンパイルの影響を抑えます。起動直後の処理が対象なら、初回の条件を再現して計測します。

1. 入力と反復回数を決めます。
   - 実際の入力サイズ・分布・アクセス順序を再現し、前後で同じ条件を使います。乱数を使うならシードを固定します。1 回の処理が短い場合は、代表的な入力のまま複数回繰り返し、まとめて測った時間を 1 回あたりに換算します。
2. ウォームアップします。
   - 定常状態を測る場合は、計測と同じ処理を事前に繰り返します。下の回数は初期値として使い、計測値の推移を見て調整します。
3. 計測対象の結果を消費します。
   - 結果を軽量な方法で消費します。結果の利用まで含めて測る場合は実際の呼び出し側に合わせます。サイズの参照だけで計算の一部が省かれたり、出力全体のハッシュ計算が測定対象より重くなったりする場合は、出力に合う消費方法に変えます。
4. 複数回計測します。
   - 中央値や分位点を比較します。割り当てや遅延のばらつきが問題なら、GC を含む計測値の分布も確認します。

### ランタイムによる割り当ての違い

HotSpot C2 は、呼び出し先をインライン化でき、結果がその範囲の外に逃げない場合などに、Escape Analysis とスカラー置換で `Pair` などの割り当てを除去できます。ローカル JUnit で差が見えない場合は、結果の使い方や呼び出し先のインライン化のされやすさが実際の経路と同等か確認します。 [HotSpot の Escape Analysis](https://docs.oracle.com/en/java/javase/24/vm/java-hotspot-virtual-machine-performance-enhancements.html)

割り当て除去の影響を切り分けるには、必要に応じて HotSpot のテスト JVM に `-XX:-DoEscapeAnalysis` を付け、既定設定と比較します。このフラグはテスト JVM 全体に作用します。ART にも割り当てを除去する最適化があり、適用条件は HotSpot と異なります。Android が対象なら、実際の呼び出し方とリリース相当のビルド・コンパイル条件で、実機の実行時間と割り当て量を確認します。 [ART の最適化実装](https://android.googlesource.com/platform/art/+/582bbcf317c731c33ecb3ab001e4268466d5530f/compiler/optimizing/load_store_elimination.cc)

また、Android のリリースビルドでは R8 によるメソッドのインライン化、デバーチャライゼーション、不要コードやラッパーの除去が働きます。デバッグビルドだけで見えたオーバーヘッド（小さなヘルパー呼び出しやインターフェース経由の間接呼び出し等）がリリースビルドでは消えることがあるため、可読性を落とす最適化は R8 有効のビルド条件でも差が残るか確認してから採用します。

### 実機計測の読み方

- ART のプロセス単位の割り当てカウンタで差分が 0 の場合は「観測区間で増分なし」と記録します。JIT の状態や同一プロセス内の計測対象外の割り当てによって、観測値が変わることがあります。割り当て量が結論を左右する場合は、ウォームアップ条件をそろえて新しいプロセスでも測り、観測が揺れるなら対象箇所の割り当てをプロファイルします。
- 複数端末では同じ成果物と入力で、端末ごとに変更前後を比較します。端末名と Android バージョンを記録し、効果が出た方向と差の大きさを分けて報告します。SoC の影響を切り分けるには、OS、ランタイム、ビルド条件などもそろえる必要があります。

## ベンチマークのテンプレート

JUnit 4 で `Int` を返す処理を比べる例です。データ生成と比較対象の関数（`prepareTestData`、`processOriginal`、`processOptimized`）は対象コードに合わせて用意します。対象プロジェクトが JUnit Platform や `kotlin.test` を使う場合は、既存のテスト構成に合わせてインポートを変更します。

Gradle Wrapper を備えた JVM プロジェクトでは、たとえば `src/test/kotlin/` に置いて `./gradlew test --tests "*QuickPerfBenchmarkTest*"` を実行します。計測値が見えない場合はテスト結果の標準出力を確認します。

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
        callsPerSample: Int = 1,
        block: () -> Int
    ): Double {
        var blackhole = 0

        repeat(warmupIterations) {
            repeat(callsPerSample) {
                blackhole = 31 * blackhole + block()
            }
        }

        val samplesMs = DoubleArray(measureIterations)
        repeat(measureIterations) { i ->
            val duration: Duration = measureTime {
                repeat(callsPerSample) {
                    blackhole = 31 * blackhole + block()
                }
            }
            samplesMs[i] = duration.toDouble(DurationUnit.MILLISECONDS) / callsPerSample
        }

        samplesMs.sort()
        val lowerMiddle = samplesMs[(measureIterations - 1) / 2]
        val upperMiddle = samplesMs[measureIterations / 2]
        val medianMs = (lowerMiddle + upperMiddle) / 2
        val minMs = samplesMs.first()
        val p90Ms = samplesMs[kotlin.math.ceil(measureIterations * 0.9).toInt() - 1]

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

短い処理では `callsPerSample` を前後で同じ値に増やします。各計測値は 1 呼び出しあたりの時間です。

## 変更前後の比較

1. 前後の条件をそろえます。
   - 同じプロセスで手軽に比較できます。実行順序で差が変わる場合は順序を入れ替え、初回実行や共有状態の影響を見る場合はプロセスを分けます。入力を変更する処理では、毎回同等の状態から始めます。
   - 計測の外で、同じ入力に対する前後の結果が一致することを出力型に合う方法で確認します。
2. 比較専用に追加した旧実装は、計測と検証が終わったら削除します。変更後の挙動を確認するテストは残します。
