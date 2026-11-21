---
name: kotlin-performance-optimizer
license: MIT
description: >-
  Optimizes Kotlin calculation logic, memory access patterns (CPU cache locality), data structures, collections, and processing flows (primarily for Android and JVM) through static code analysis, hypothesis-driven refactoring, and lightweight benchmarking loops. Use this skill when analyzing Kotlin code for CPU or memory bottlenecks, improving CPU cache hit rates and memory layout, eliminating redundant memory copies across processing stages, avoiding counterproductive memoization/caching overhead, fixing O(N^2) collection operations, or reducing boxing and temporary object allocations. Don't use for Jetpack Compose UI recomposition issues, XML layout rendering, or deep Perfetto system trace analysis.
---

# Kotlin Performance Optimizer

Kotlin の計算ロジック、データ処理、処理フローから静的解析でボトルネックの候補を特定します。仮説に基づいて修正し、簡易計測で効果を検証します。

## 最適化の原則

パフォーマンス最適化において、理論上の計算量（`O(N)` や `O(1)`）だけに注目するのは危険です。現代の CPU では「演算そのもののコスト」よりも「メモリからデータをフェッチ・コピーするコスト（CPU キャッシュミス）」の方が圧倒的に高い（L1 キャッシュ参照に対しメインメモリ参照は 100〜200 倍以上遅い）ためです。

1. ホットパスへの集中
   - 処理時間の大部分はコード全体のごく一部（ホットパス）が占めます。ホットパス以外の可読性を犠牲にしてはいけません。
   - また、Kotlin が遅いのではなく「無自覚なメモリコピーとボクシング」が遅さの原因であることが大半です。ネイティブ（C/C++）実装などを検討する前に、まず Kotlin 上での無駄なアロケーションとメモリアクセスを削ぎ落とします。
2. メモリアクセス順序とデータ配置
   - オブジェクトの参照を辿るポインタチェイシング（Pointer Chasing）やランダムアクセスを避け、CPU のプリフェッチャが効くシーケンシャルアクセス（連続メモリ走査）と時間的・空間的局所性（Cache Locality）を最大化する処理順序を優先します。
3. メモ化のコスト
   - 軽い〜中程度の計算を省くために `HashMap` 等で結果をキャッシュすると、キーの生成・ボクシング・ハッシュ計算・内部 Node のポインタ追跡による CPU キャッシュミス（Cache Thrashing）の負荷が元の計算コストを上回り、かえって遅くなることが頻繁にあります。
4. データサイズと定数倍のコスト
   - `N` が極小〜小規模（例: `N ≤ 32` 程度）の場合、再帰や複雑な管理構造を持つ `O(N log N)` / `O(1)` アルゴリズムより、連続メモリ上の単純な `O(N^2)`（挿入ソートやバブルソート）や線形走査の方が分岐予測とキャッシュヒット率に優れ高速です（標準の `IntArray.sort()` / `Arrays.sort()` が内部で要素数に応じて挿入ソートと Dual-Pivot Quicksort を切り替えているのもこのためです）。

## 対象範囲

- 対象: Kotlin で書かれた計算ロジック、メモリアクセスパターン、コレクション操作、アルゴリズム・データ構造、メモリ割り当て（Allocation / Boxing）、および基本的な Coroutines 処理フロー。
- 対象外（外部スキルへ委譲）:
  - Jetpack Compose の再コンポーズや UI カクつき → `jetpack-compose-performance` 等を使用
  - Perfetto トレースやシステム全体の詳細なプロファイリング → `android-profiler` / `perfetto-trace-analysis` 等を使用

## ワークフロー

ユーザーの要求（「どこが遅いか診断してほしい」または「この処理・ファイルを高速化してほしい」）に応じて、以下の 5 ステップを実行します。

### 1. 対象コードの特定と静的解析

対象のファイルまたはモジュールを読み込み、[references/anti-patterns.md](./references/anti-patterns.md) のカタログおよび必要に応じた検索スクリプト（[scripts/scan-kotlin-hotspots.sh](./scripts/scan-kotlin-hotspots.sh)）を用いてボトルネック候補を洗い出します。

次の項目を優先して確認します。

1. メモリアクセスパターンと CPU キャッシュ局所性（Cache Locality）
   - キャッシュ非効率なループ順序: 2 次元データ（行列・画像ピクセル・グリッド等）での列方向（ストライド飛び）走査や、複数パスに分かれたループ（Loop Fusion で 1 パスに統合し L1 キャッシュに乗ったまま処理できないか）
   - ポインタチェイシング（Pointer Chasing）: `List<Point>` やネストしたオブジェクト配列の大量走査（プリミティブ配列 `IntArray` / `FloatArray` へのフラット化や Structure of Arrays (SoA) 化ができないか）
   - 逆効果なキャッシュ・メモ化: 再計算コストが低い処理を `Map` / `HashMap` にキャッシュして毎回ボクシングやハッシュ探索（キャッシュミス）を発生させていないか
   - データフロー上のメモリコピー。ホットパスの入力から最終出力までデータの表現と受け渡しを追い、処理境界での全体・部分コピーや中間バッファへの書き出しを確認します。コピー量、頻度、必要性の見積もり方と削減例は [データフロー上のメモリコピー](./references/anti-patterns.md#データフロー上のメモリコピー) を参照してください。
2. コレクション・Sequence の中間オブジェクト生成
   - 複数段に連なる `map { ... }.filter { ... }.take(...)` 等による不要な中間 `List` の生成（その都度ヒープ確保とメモリ書き戻しが発生しキャッシュを汚染する）
   - 要素数が既知のコレクション生成における初期容量（Initial Capacity）未指定や、`buildList` / `asSequence()` / ループへの統合余地
   - プリミティブを扱う Map / Set（標準の `HashMap<Int, Int>` ではなく AndroidX `androidx.collection` の `IntIntMap`, `LongObjectMap` や配列インデックス直接参照に置き換え可能か）
3. ボクシングと不要なオブジェクト割り当て・配列コピー（Allocation / GC 負荷）
   - ホットループ内での `Pair` / `Triple` / データクラス返却や分解宣言（引数への書き出し `Out Parameter` や、2 つの 32 ビット 値を `Long` にパックした `@JvmInline value class` でヒープ確保とボクシングをゼロにできないか）
   - 可変長引数（`vararg`）とスプレッド演算子（`*array`）のループ内使用（裏側で毎回 `Arrays.copyOf` や `SpreadBuilder` による配列コピーが発生していないか）
   - ホットループ内での `Regex(...)`, `SimpleDateFormat`, `Gson`/`Json` インスタンスの都度生成
   - Nullable プリミティブ（`Int?`, `Long?`）やジェネリクス境界によるオートボクシング、高階関数の `inline` 化
4. アルゴリズムとデータ構造の非効率 (`O(N^2)` 等)
   - 大規模データ（`N` が大きい場合）のループ内での `List.contains()`, `List.find()`, `List.indexOf()`, `List.firstOrNull()`
   - ネストされたループでの線形探索、ループ内での文字列連結（`+=`）や毎回のソート（`sortedBy`）
   - 要素数が少ない場合は、`HashMap` の構築・検索コストと配列走査のキャッシュ効率を比較します。

Coroutines / Flow では、ループ内の不要な `withContext` による切り替えや、処理単位が小さすぎる `async` の生成を確認します。

### 2. 改善仮説の整理

検出した各ボトルネックについて、箇所、原因、改善仮説を整理します。提案のみを求められた場合はここで提示し、修正も求められた場合はベースライン計測へ進みます。

- 箇所: ファイル名と行番号、対象メソッド
- 問題（なぜ遅いか）:
  - メモリアクセス / キャッシュ効率の観点: ポインタ参照回数、キャッシュラインの利用効率、ヒープアロケーション回数、キャッシュルックアップのオーバーヘッド
  - 計算量の観点: 時間計算量（例: `O(N × M)`）およびデータサイズ `N` に対する定数倍コスト
- 改善仮説: どの手法でメモリアクセス効率の改善・アロケーション削減・計算量削減を行うか、およびトレードオフ（可読性やメモリ使用量など）

### 3. ベースライン計測

コードを変更する前に、現在の処理速度を確認するための軽量ベンチマークテストを作成・実行します。
詳細な計測手順と JUnit ハーネスのテンプレートは [references/micro-benchmarking.md](./references/micro-benchmarking.md) を参照してください。

- 既存の JUnit テスト環境（`src/test/`）を利用し、外部ライブラリの追加なしで `kotlin.time.measureTime` または `System.nanoTime()` を用いて計測します。
- JIT コンパイルの影響を排除するため、必ずウォームアップ実行を行ってから複数回の平均値または中央値を記録します。
- キャッシュの逆転現象（`N` が小さいときと大きいときで有利な実装が変わる現象）が疑われる場合は、現実的なデータサイズ (`N`) で計測します。
- コピー削減では、変更対象の受け渡しを含む処理経路全体を代表的な入力サイズで計測し、前後の実行時間と入力 1 件あたりのコピー回数・概算データ量を比較します。コピー量をコードから算出した場合は推定値として記録します。

### 4. コードの修正と正確性の検証

仮説に基づいてコードを修正し、既存のユニットテストがすべて通ることを確認します。エッジケースでの挙動、要素の順序、`null` 安全性、スレッド安全性を維持します。

コピーを削減する場合は、入力を書き換えられるか、結果が入力の後続変更から独立している必要があるか、下流の利用が終わるまでバッファを保持できるかを確認します。

### 5. 再計測と効果の判定

ベースラインと同じ条件で簡易計測を実行し、変更前後の結果を比較します。

- 改善が確認できた場合: 短縮された時間（ms / ns）と改善倍率（または削減率 %）をレポートにまとめます。
- 改善が見られない・悪化した場合（特にキャッシュ導入やデータ構造変更時）:
  - 「計算量を減らすためにキャッシュ（`Map` 等）や `Sequence` を入れたが、メモリアクセス負荷やボクシングコストの方が高くなった」可能性を疑います。
  - 変更をリバートし、よりメモリアクセスが単純・連続的になるアプローチ（フラットな配列・単一ループへの統合など）に切り替えて再計測します。
