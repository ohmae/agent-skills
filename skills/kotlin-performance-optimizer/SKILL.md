---
name: kotlin-performance-optimizer
license: MIT
description: >-
  Optimizes Kotlin calculation logic, memory access patterns (CPU cache locality), data structures, collections, and processing flows (primarily for Android and JVM) through static code analysis, hypothesis-driven refactoring, and lightweight benchmarking loops. Use this skill when analyzing Kotlin code for CPU or memory bottlenecks, improving CPU cache hit rates and memory layout, avoiding counterproductive memoization/caching overhead, fixing O(N^2) collection operations, or reducing boxing and temporary object allocations. Don't use for Jetpack Compose UI recomposition issues, XML layout rendering, or deep Perfetto system trace analysis.
---

# Kotlin Performance Optimizer

Kotlin の計算ロジック・データ処理・処理フローにおけるパフォーマンス劣化要因を静的解析によって特定し、**「検出 → 仮説に基づく修正 → 簡易計測による効果検証」** のイテレーションを回して最適なコードへ改善するスキルです。

## コア哲学：計算量（Big-O）よりも「メモリアクセスと CPU キャッシュ」を疑う

パフォーマンス最適化において、理論上の計算量（$O(N)$ や $O(1)$）だけに注目するのは危険です。現代の CPU では **「演算そのもののコスト」よりも「メモリからデータをフェッチ・コピーするコスト（CPU キャッシュミス）」の方が圧倒的に高い**（L1 キャッシュ参照に対しメインメモリ参照は 100〜200 倍以上遅い）ためです。

このスキルでは、常に以下の原則を念頭に置いて解析と仮説立案を行います：
1. **パレートの法則と段階的アプローチ（安易に C/C++ や難解なコードへ飛ばない）**
   - 処理時間の大部分はコード全体のごく一部（ホットパス）が占めます。ホットパス以外の可読性を犠牲にしてはいけません。
   - また、Kotlin が遅いのではなく「無自覚なメモリコピーとボクシング」が遅さの原因であることが大半です。ネイティブ（C/C++）実装などを検討する前に、まず Kotlin 上での無駄なアロケーションとメモリアクセスを削ぎ落とします。
2. **計算量が同じでも、メモリアクセス順序とデータ配置で速度は数倍〜数十倍変わる**
   - オブジェクトの参照を辿るポインタチェイシング（Pointer Chasing）やランダムアクセスを避け、CPU のプリフェッチャが効くシーケンシャルアクセス（連続メモリ走査）と時間的・空間的局所性（Cache Locality）を最大化する処理順序を優先します。
3. **「計算結果をキャッシュ（メモ化）すれば速くなる」という思い込みを疑う**
   - 軽い〜中程度の計算を省くために `HashMap` 等で結果をキャッシュすると、キーの生成・ボクシング・ハッシュ計算・内部 Node のポインタ追跡による **CPU キャッシュミス（Cache Thrashing）の負荷が元の計算コストを上回り、かえって遅くなる** ことが頻繁にあります。
4. **データサイズ ($N$) と定数倍のトレードオフを見極める**
   - $N$ が極小〜小規模（例: $N \le 32$ 程度）の場合、再帰や複雑な管理構造を持つ $O(N \log N)$ / $O(1)$ アルゴリズムより、連続メモリ上の単純な $O(N^2)$（挿入ソートやバブルソート）や線形走査の方が分岐予測とキャッシュヒット率に優れ高速です（標準の `IntArray.sort()` / `Arrays.sort()` が内部で要素数に応じて挿入ソートと Dual-Pivot Quicksort を切り替えているのもこのためです）。

## スコープと前提

- **対象**: Kotlin で書かれた計算ロジック、メモリアクセスパターン、コレクション操作、アルゴリズム・データ構造、メモリ割り当て（Allocation / Boxing）、および基本的な Coroutines 処理フロー。
- **対象外（外部スキルへ委譲）**:
  - Jetpack Compose の再コンポーズや UI カクつき → `jetpack-compose-performance` 等を使用
  - Perfetto トレースやシステム全体の詳細なプロファイリング → `android-profiler` / `perfetto-trace-analysis` 等を使用

---

## ワークフロー

ユーザーの要求（「どこが遅いか診断してほしい」または「この処理・ファイルを高速化してほしい」）に応じて、以下の 5 ステップを実行します。

### Step 1: 対象コードの特定と静的解析（Hotspot 検出）

対象のファイルまたはモジュールを読み込み、[references/anti-patterns.md](./references/anti-patterns.md) のカタログおよび必要に応じた検索スクリプト（[scripts/scan-kotlin-hotspots.sh](./scripts/scan-kotlin-hotspots.sh)）を用いてボトルネック候補を洗い出します。

重点的にチェックする 4 大カテゴリ:
1. **メモリアクセスパターンと CPU キャッシュ局所性（Cache Locality）**
   - **キャッシュ非効率なループ順序**: 2次元データ（行列・画像ピクセル・グリッド等）での列方向（ストライド飛び）走査や、複数パスに分かれたループ（Loop Fusion で 1 パスに統合し L1 キャッシュに乗ったまま処理できないか）
   - **ポインタチェイシング（Pointer Chasing）**: `List<Point>` やネストしたオブジェクト配列の大量走査（プリミティブ配列 `IntArray` / `FloatArray` へのフラット化や Structure of Arrays (SoA) 化ができないか）
   - **逆効果なキャッシュ・メモ化**: 再計算コストが低い処理を `Map` / `HashMap` にキャッシュして毎回ボクシングやハッシュ探索（キャッシュミス）を発生させていないか
2. **コレクション・Sequence の中間オブジェクト生成**
   - 複数段に連なる `map { ... }.filter { ... }.take(...)` 等による不要な中間 `List` の生成（その都度ヒープ確保とメモリ書き戻しが発生しキャッシュを汚染する）
   - 要素数が既知のコレクション生成における初期容量（Initial Capacity）未指定や、`buildList` / `asSequence()` / ループへの統合余地
   - プリミティブを扱う Map / Set（標準の `HashMap<Int, Int>` ではなく AndroidX `androidx.collection` の `IntIntMap`, `LongObjectMap` や配列インデックス直接参照に置き換え可能か）
3. **ボクシングと不要なオブジェクト割り当て・配列コピー（Allocation / GC 負荷）**
   - ホットループ内での `Pair` / `Triple` / データクラス返却や分解宣言（引数への書き出し `Out Parameter` や、2つの 32bit 値を `Long` にパックした `@JvmInline value class` でヒープ確保とボクシングをゼロにできないか）
   - 可変長引数（`vararg`）とスプレッド演算子（`*array`）のループ内使用（裏側で毎回 `Arrays.copyOf` や `SpreadBuilder` による配列コピーが発生していないか）
   - ホットループ内での `Regex(...)`, `SimpleDateFormat`, `Gson`/`Json` インスタンスの都度生成
   - Nullable プリミティブ（`Int?`, `Long?`）やジェネリクス境界によるオートボクシング、高階関数の `inline` 化
4. **アルゴリズムとデータ構造の非効率 ($O(N^2)$ 等)**
   - 大規模データ（$N$ が大きい場合）のループ内での `List.contains()`, `List.find()`, `List.indexOf()`, `List.firstOrNull()`
   - ネストされたループでの線形探索、ループ内での文字列連結（`+=`）や毎回のソート（`sortedBy`）
   - ※ただし要素数 $N$ が小さい場合は、`HashMap` 化のオーバーヘッドと配列走査のキャッシュ効率を比較すること。

*(※補足: Coroutines / Flow に関しては、ループ内での不要な `withContext` 切り替えや細粒度すぎる `async` 生成がある場合のみ指摘します)*

### Step 2: 仮説の立案とユーザーへの提示（または改善案の策定）

検出した各ボトルネックについて、以下のフォーマットで仮説を整理します。ユーザーが「提案のみ」を求めている場合はここで提示し、修正まで求めている場合は Step 3 へ進みます。

- **箇所**: ファイル名と行番号、対象メソッド
- **問題（なぜ遅いか）**:
  - **メモリアクセス / キャッシュ効率の観点**: ポインタ参照回数、キャッシュラインの利用効率、ヒープアロケーション回数、キャッシュルックアップのオーバーヘッド
  - **計算量の観点**: 時間計算量（例: $O(N \times M)$）およびデータサイズ $N$ に対する定数倍コスト
- **改善仮説**: どの手法でメモリアクセス効率の改善・アロケーション削減・計算量削減を行うか、およびトレードオフ（可読性やメモリ使用量など）

### Step 3: ベースラインの簡易計測（Before 計測）

コードを変更する前に、現在の処理速度を確認するための軽量ベンチマークテストを作成・実行します。
詳細な計測手順と JUnit ハーネスのテンプレートは [references/micro-benchmarking.md](./references/micro-benchmarking.md) を参照してください。

- 既存の JUnit テスト環境（`src/test/`）を利用し、外部ライブラリの追加なしで `kotlin.time.measureTime` または `System.nanoTime()` を用いて計測します。
- JIT コンパイルの影響を排除するため、**必ずウォームアップ実行（Warm-up）** を行ってから複数回の平均値または中央値を記録します。
- キャッシュの逆転現象（$N$ が小さいときと大きいときで有利な実装が変わる現象）が疑われる場合は、**現実的なデータサイズ ($N$)** で計測します。

### Step 4: コードの修正と正確性の検証

仮説に基づいてコードをリファクタリングします。
- **重要**: 既存の振る舞い（エッジケース、要素の順序、`null` 安全性、スレッド安全性）を壊さないよう注意し、既存のユニットテストがすべてパスすることを確認します。

### Step 5: 再計測（After 計測）とイテレーション判定

Step 3 と同じ条件で簡易計測を実行し、Before / After を比較します。

- **改善が確認できた場合**: 短縮された時間（ms / ns）と改善倍率（または削減率 %）をレポートにまとめます。
- **改善が見られない・悪化した場合（特にキャッシュ導入やデータ構造変更時）**:
  - 「計算量を減らすためにキャッシュ（`Map` 等）や `Sequence` を入れたが、メモリアクセス負荷やボクシングコストの方が高くなった」可能性を疑います。
  - 変更をリバートし、よりメモリアクセスが 단순・連続的になるアプローチ（フラットな配列・単一ループへの統合など）に切り替えて再計測します。
