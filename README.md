# Agent Skills

個人用の Agent Skills を開発・検証・公開するリポジトリです。
公開スキルは標準仕様に準拠しており、Agent Skills に対応する環境で利用できます。[Agent Skills 標準仕様](https://agentskills.io/)

## スキル一覧

### kotlin-performance-optimizer

Android / JVM 上の Kotlin の計算、データ処理、フレーム更新・描画のボトルネックを調べ、修正の効果を計測します。[SKILL.md](./skills/kotlin-performance-optimizer/SKILL.md)

## インストール

### `npx skills` で追加する

```bash
npx skills add ohmae/agent-skills --skill kotlin-performance-optimizer
```

### シンボリックリンクで登録する

Antigravity のグローバルスキルとして登録する例です。このリポジトリをクローンし、リポジトリのルートで実行します。

```bash
mkdir -p ~/.gemini/config/skills
ln -s "$(pwd)/skills/kotlin-performance-optimizer" ~/.gemini/config/skills/kotlin-performance-optimizer
```

## スキルの開発と検証

1. `skills/<skill-name>/SKILL.md` と必要な参照資料を作成します。
2. `playpen/` にスキルの手順を試せる課題コードを用意します。
3. スキルの手順に沿って課題コードを解析・改善し、テストや簡易計測で効果と手順の明確さを確認します。
4. リポジトリのルートで `./tools/validate-skills.sh` を実行します。`skills/` 直下の各ディレクトリで、`SKILL.md` の有無と、先頭のフロントマターにディレクトリ名と一致する `name` 行および `description` 行があることを確認します。

設計方針とディレクトリごとの規約は開発ガイドラインを参照してください。[AGENTS.md](./AGENTS.md)

ワークスペースでは [skills.json](./.agents/skills.json) で `skills/` を登録しています。

### Kotlin スキルの検証用プロジェクト

[kotlin-perf-sandbox](./playpen/kotlin-perf-sandbox/) のテストと簡易計測を実行するには、JDK 21 と Gradle（9.9.2 で確認）を用意し、リポジトリのルートで次を実行します。`--rerun` を付けると、ソースに変更がなくても `test` タスクが実行され、計測結果が標準出力に表示されます。

```bash
gradle -p playpen/kotlin-perf-sandbox test --rerun
```

Qiita 記事の実験を再計測する場合は、`kotlin-perf-sandbox` を IDE で開き、テストソースの [BenchmarkMain.kt](./playpen/kotlin-perf-sandbox/src/test/kotlin/com/example/perf/BenchmarkMain.kt) の `main` を実行します。

## ライセンス

このリポジトリは [MIT ライセンス](./LICENSE) で公開しています。
