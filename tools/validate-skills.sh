#!/usr/bin/env bash
# skills/ 配下の各 SKILL.md が必須のフロントマター (name, description) を持っているか検証するスクリプト

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILLS_DIR="$REPO_ROOT/skills"

if [ ! -d "$SKILLS_DIR" ]; then
  echo "Error: skills directory not found at $SKILLS_DIR" >&2
  exit 1
fi

EXIT_CODE=0
COUNT=0

for skill_dir in "$SKILLS_DIR"/*/; do
  [ -d "$skill_dir" ] || continue
  skill_name="$(basename "$skill_dir")"
  skill_file="${skill_dir}SKILL.md"
  COUNT=$((COUNT + 1))

  if [ ! -f "$skill_file" ]; then
    echo "❌ [$skill_name] SKILL.md is missing"
    EXIT_CODE=1
    continue
  fi

  # フロントマター内の name と description を確認
  if ! grep -q "^name: ${skill_name}$" "$skill_file"; then
    echo "❌ [$skill_name] Frontmatter 'name: $skill_name' is missing or mismatched in SKILL.md"
    EXIT_CODE=1
  fi

  if ! grep -q "^description:" "$skill_file"; then
    echo "❌ [$skill_name] Frontmatter 'description:' is missing in SKILL.md"
    EXIT_CODE=1
  fi

  lines=$(wc -l < "$skill_file" | tr -d ' ')
  echo "✅ [$skill_name] Valid ($lines lines)"
done

echo "Checked $COUNT skill(s)."
exit $EXIT_CODE
