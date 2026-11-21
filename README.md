# Agent Skills

個人用の Agent Skills を開発・検証・公開するリポジトリです。
公開スキルは標準仕様に準拠しており、Agent Skills に対応する環境で利用できます。[Agent Skills 標準仕様](https://agentskills.io/)

## スキル一覧

### kotlin-performance-optimizer

Android / JVM を中心に、Kotlin の計算ロジックや処理フローのボトルネックを静的解析で絞り込み、仮説に基づく修正の効果を簡易計測で確かめます。ステータスは開発中です。[SKILL.md](./skills/kotlin-performance-optimizer/SKILL.md)

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
4. `tools/validate-skills.sh` でフロントマターとファイル構成を検証します。

設計方針とディレクトリごとの規約は開発ガイドラインを参照してください。[AGENTS.md](./AGENTS.md)

### ディレクトリ構成

- `skills/` に公開スキルを置きます。各スキルは `SKILL.md` と、必要に応じて参照資料や補助スクリプトを持ちます。
- `playpen/` にスキルの検証用プロジェクトを置きます。Kotlin スキルの検証には `playpen/kotlin-perf-sandbox/` を使います。
- `tools/` にリポジトリの保守・検証用スクリプトを置きます。
- `.agents/skills.json` はこのワークスペースで `skills/` を認識させるための設定です。

## ライセンス

このリポジトリは [MIT ライセンス](./LICENSE) で公開しています。
