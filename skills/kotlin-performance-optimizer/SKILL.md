---
name: kotlin-performance-optimizer
license: MIT
description: >-
  Diagnose and optimize Kotlin hot paths on Android and JVM. Use for CPU or allocation bottlenecks in computation, data processing, collections, Coroutines and Flow, or Android frame updates and drawing. Prioritize memory access and CPU cache locality, and verify changes with benchmarks. Use dedicated tools for Compose recomposition, UI layout, and detailed Perfetto trace analysis.
---

# Kotlin Performance Optimizer

呼び出し頻度と 1 回あたりのコストを見積もり、対象の負荷に大きく寄与する処理を特定します。まずデータ配置、走査順序、処理境界でのコピーを確認します。計算量が同じ実装でも、連続走査と参照をたどる走査では、キャッシュミスによる待ち時間が変わります。C/C++ への移植を検討する前に、Kotlin 側のコピーやボクシングを減らす効果を計測します。

UI レイアウトや Jetpack Compose の再コンポーズは UI 性能の専用手法で調べます。Perfetto トレースやシステム全体の詳細な解析には、利用可能であれば `android-profiler` や `perfetto-trace-analysis` を使います。

## 1. 対象コードを調べる

対象のモジュールを読み、ホットパスの入力から出力まで、データの表現と呼び出し経路を追います。[パフォーマンス上の問題と対策](./references/anti-patterns.md) の該当箇所を、次の順で確認します。

1. **メモリアクセスとコピー**。ストライドの大きい走査、参照追跡、同じデータの再走査を確認します。メモ化では、ヒット時の読み出しとミス時の再計算・格納を含めて、直接計算する場合と比較します。[メモリアクセスと CPU キャッシュ局所性](./references/anti-patterns.md#メモリアクセスと-cpu-キャッシュ局所性) · [データフロー上のメモリコピー](./references/anti-patterns.md#データフロー上のメモリコピー)
2. **コレクション、割り当て、アルゴリズム**。多段操作の中間結果、ボクシング、高頻度のオブジェクト生成、反復探索やソートを確認します。索引を使う場合は、構築や更新も含めて比較します。[コレクションと Sequence](./references/anti-patterns.md#コレクションと-sequence) · [ボクシングとオブジェクト割り当て](./references/anti-patterns.md#ボクシングとオブジェクト割り当て) · [アルゴリズムとデータ構造](./references/anti-patterns.md#アルゴリズムとデータ構造)
3. **呼び出し先と並行処理**。構文や API に隠れた処理、時刻取得の繰り返し、共有状態の競合を確認します。Coroutines / Flow では、切り替えやタスク生成、上流処理の再実行、処理待ちの滞留を確認します。[構文や API に隠れた処理](./references/anti-patterns.md#構文や-api-に隠れた処理) · [時刻取得の繰り返し](./references/anti-patterns.md#時刻取得の繰り返し) · [共有状態の競合](./references/anti-patterns.md#共有状態の競合) · [Coroutines / Flow](./references/anti-patterns.md#coroutines--flow-のオーバーヘッド)

Android のアニメーション、フレーム更新、カスタム描画を扱う場合は、VSync との同期、フレーム単位の割り当て、スレッド間の受け渡し、描画リソースの使い方を確認します。[Android アニメーション・フレーム駆動処理の最適化](./references/android-animation.md)

### 補助スクリプトで候補を探す

スキルのディレクトリで、対象モジュールのディレクトリを指定して実行します。Bash と `--include` に対応する `grep` が必要です。

```bash
./scripts/scan-kotlin-hotspots.sh /path/to/module
```

引数を省略すると、カレントディレクトリを検索します。`*.kt` を再帰的に検索し、一致したファイル名、行番号、行の内容をパターン別に表示します。候補がない場合は `(none found)` を表示し、検索を完了すれば終了コードは 0 です。存在しないディレクトリや検索エラーでは 0 以外で終了します。

コメントや文字列も一致し、複数行に分かれた操作や独自 API は見落とすことがあります。結果を手がかりに、実際の呼び出し頻度や型、呼び出し先の実装を確認します。

## 2. 改善仮説を立てる

候補ごとに、箇所、負荷が生じる条件、改善案を整理します。コピー量と頻度、計算量と入力規模など、効果を見積もる根拠を示します。可読性やメモリ使用量に影響する場合は、採用条件も添えます。診断や提案の依頼では、静的解析で得た仮説と、計測で確認できた事実を区別して報告します。

## 3. 変更前の値を測る

実際の入力サイズ・分布・アクセス順序を再現し、仮説に合う区間を測ります。既存の計測環境を活用し、手軽な JVM 計測には JUnit で `kotlin.time.measureTime` や `System.nanoTime()` を使います。ウォームアップ、短い処理の反復、統計値の取り方は [JUnit による簡易計測](./references/micro-benchmarking.md) を参照してください。

- コピー削減では、対象の受け渡しを含む経路全体を測り、入力 1 件あたりのコピー回数とデータ量も記録します。コードから算出した量は推定値として記録します。
- ランタイムに依存する割り当てや、実機でのフレーム締め切りは対象環境で確認します。[実機計測の読み方](./references/micro-benchmarking.md#実機計測の読み方) · [フレーム処理の計測と検証の切り分け](./references/android-animation.md#フレーム処理の計測と検証の切り分け)

## 4. 修正して既存の挙動を検証する

仮説に基づいて修正し、既存のテストと同じ入力に対する出力比較で挙動を確認します。要素の順序、`null` や空入力、例外、スレッド安全性など、変更の影響を受ける条件を確認します。

コピーを省く場合は、入力を変更できるか、結果の独立性が必要か、利用が終わるまでバッファを保持できるかを確認します。[データフロー上のメモリコピー](./references/anti-patterns.md#データフロー上のメモリコピー)

## 5. 再計測して効果を判定する

変更前と同じ条件で測り、実行時間と割り当て量をそれぞれ比較します。効果が確認できた場合は、変更前後の値、短縮時間と改善倍率または削減率、計測条件を報告します。効果が出ない場合は、メモ化の検索や格納、`Sequence` のイテレータやボクシングなど、変更で増えたコストを確認します。効果の出なかった変更を戻し、計測結果に沿って仮説を見直します。[最適化レポートの出力例](./examples/optimization-report.md)
