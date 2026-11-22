#!/usr/bin/env bash
# skills/ 直下の各スキルで SKILL.md と必須のフロントマター項目を確認します。

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

  # ファイル先頭の区切り行で囲まれた範囲だけを読み取ります。
  if ! frontmatter=$(awk '
    { sub(/\r$/, "") }
    NR == 1 { if ($0 != "---") exit 1; next }
    $0 == "---" { closed = 1; exit }
    { print }
    END { if (!closed) exit 1 }
  ' "$skill_file"); then
    echo "❌ [$skill_name] SKILL.md frontmatter must be enclosed by --- at the start of the file"
    EXIT_CODE=1
    continue
  fi

  valid=1
  if ! grep -Fxq "name: $skill_name" <<< "$frontmatter"; then
    echo "❌ [$skill_name] Frontmatter 'name: $skill_name' is missing or mismatched in SKILL.md"
    valid=0
  fi

  if ! grep -q "^description:" <<< "$frontmatter"; then
    echo "❌ [$skill_name] Frontmatter 'description:' is missing in SKILL.md"
    valid=0
  fi

  if [ "$valid" -eq 0 ]; then
    EXIT_CODE=1
    continue
  fi

  lines=$(wc -l < "$skill_file" | tr -d ' ')
  echo "✅ [$skill_name] Valid ($lines lines)"
done

echo "Checked $COUNT skill(s)."
exit $EXIT_CODE
