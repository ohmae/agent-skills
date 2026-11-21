# Agent Skills

個人用の Agent Skills を開発・検証・公開するリポジトリです。
[Agent Skills 標準仕様](https://agentskills.io/)（`skills/<skill-name>/SKILL.md`）に準拠しており、Claude Code / Antigravity / Cursor / `npx skills` など複数の AI エージェント環境で利用できます。

## ディレクトリ構成

```text
.
├── AGENTS.md                 # AI エージェント向けのリポジトリ開発・検証ガイドライン
├── README.md                 # リポジトリ概要・スキル一覧
├── .agents/
│   └── skills.json           # このワークスペース内で skills/ を自動認識させる設定
├── skills/                   # 公開用スキル本体（各ディレクトリが1つのスキル）
│   └── kotlin-performance-optimizer/
│       ├── SKILL.md          # スキルのメイン指示書
│       ├── references/       # 詳細なアンチパターンカタログ・計測ガイド等の参照ドキュメント
│       ├── scripts/          # 静的検査や補助用のスクリプト
│       └── examples/         # Before / After の改善サンプル
├── playpen/                  # スキルを検証・テストするための実験用プロジェクト群
│   └── kotlin-perf-sandbox/  # Kotlin パフォーマンス最適化スキルの検証用 Gradle プロジェクト
└── tools/                    # スキル作成・バリデーション用の共通スクリプト
```

## スキル一覧

| スキル名 | 概要 | ステータス |
| :--- | :--- | :--- |
| [kotlin-performance-optimizer](./skills/kotlin-performance-optimizer/SKILL.md) | Kotlin（主に Android / JVM 計算ロジック・処理フロー）のパフォーマンス劣化要因を静的解析で特定し、仮説に基づく修正と簡易計測ループで最適化するスキル | 🚧 開発中 |

## 使い方（インストール方法）

### `npx skills` を使う場合

```bash
npx skills add ohmae/agent-skills
```

### ローカルにシンボリックリンクを張る場合

```bash
# Antigravity のグローバルスキルとして登録する例
ln -s "$(pwd)/skills/kotlin-performance-optimizer" ~/.gemini/config/skills/kotlin-performance-optimizer
```

## スキルの開発と検証フロー

新しいスキルを作成・改善する際は、以下のサイクルで進めます。詳細は [AGENTS.md](./AGENTS.md) を参照してください。

1. **雛形作成**: `skills/<skill-name>/SKILL.md` と必要な `references/` を作成
2. **検証コード用意**: `playpen/` 配下にスキルの対象となる課題コード（例: 意図的に遅く書いた Kotlin コード）を用意
3. **スキル適用 & 計測**: スキルの手順に従って静的解析・改善・JUnit 等による簡易計測を実施し、スキルの指示が明確か検証
4. **バリデーション**: `tools/validate-skills.sh` でフロントマターやファイル構成をチェック

## ライセンス

このリポジトリは [MIT ライセンス](./LICENSE) で公開しています。
