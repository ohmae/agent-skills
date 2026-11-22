# Agent Skills

個人用の Agent Skills を開発・検証・公開するリポジトリです。
公開スキルは標準仕様に準拠しており、Agent Skills に対応する環境で利用できます。[Agent Skills 標準仕様](https://agentskills.io/)

## スキル一覧

### kotlin-performance-optimizer

Android / JVM 上の Kotlin の計算、データ処理、フレーム更新・描画のボトルネックを調べ、修正の効果を計測します。ステータスは開発中です。[SKILL.md](./skills/kotlin-performance-optimizer/SKILL.md)

## インストール

### `npx skills` で追加する

```bash
npx skills add ohmae/agent-skills
```

### シンボリックリンクで登録する

Antigravity のグローバルスキルとして登録する例です。

```bash
ln -s "$(pwd)/skills/kotlin-performance-optimizer" ~/.gemini/config/skills/kotlin-performance-optimizer
```

## スキルの開発と検証

1. `skills/<skill-name>/SKILL.md` と必要な参照資料を作成します。
2. `playpen/` にスキルの手順を試せる課題コードを用意します。
3. スキルの手順に沿って課題コードを解析・改善し、テストや簡易計測で効果と手順の明確さを確認します。
4. リポジトリのルートで `./tools/validate-skills.sh` を実行します。`skills/` 直下の各ディレクトリで、`SKILL.md` の有無と、先頭のフロントマターにディレクトリ名と一致する `name` 行および `description` 行があることを確認します。

設計方針とディレクトリごとの規約は開発ガイドラインを参照してください。[AGENTS.md](./AGENTS.md)

Kotlin スキルの検証用プロジェクトは [kotlin-perf-sandbox](./playpen/kotlin-perf-sandbox/) にあります。ワークスペースでは [skills.json](./.agents/skills.json) で `skills/` を登録しています。

## ライセンス

このリポジトリは [MIT ライセンス](./LICENSE) で公開しています。
