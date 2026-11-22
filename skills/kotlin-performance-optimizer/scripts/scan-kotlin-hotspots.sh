#!/usr/bin/env bash
# Kotlin のパフォーマンス上の問題につながり得る記述を検索します。
# 使い方: ./scan-kotlin-hotspots.sh [target_directory]

set -euo pipefail

TARGET_DIR="${1:-.}"

if [ ! -d "$TARGET_DIR" ]; then
  echo "Error: Directory '$TARGET_DIR' does not exist." >&2
  exit 1
fi

scan() {
  local heading="$1"
  local pattern="$2"
  local status

  printf '\n%s\n' "$heading"
  if grep -rnE --include="*.kt" -- "$pattern" "$TARGET_DIR/"; then
    return 0
  else
    status=$?
    if [ "$status" -eq 1 ]; then
      echo "  (none found)"
      return 0
    fi
    return "$status"
  fi
}

echo "=== Scanning Kotlin hotspots in: $TARGET_DIR ==="

scan "[1] 同一行の多段コレクション操作" \
  '\.(map|filter|flatMap|sortedBy|sortedWith)[[:space:]]*\{.*\}[[:space:]]*\.(map|filter|flatMap|sorted|sortedBy|sortedWith|take|first)[[:space:]]*[({]'
scan "[2] 正規表現・日付フォーマッタの生成候補" \
  '(Regex\(|toRegex\(|Pattern\.compile\(|SimpleDateFormat\()'
scan "[3] 探索の候補（contains / find / indexOf / firstOrNull）" \
  '\.(contains|find|indexOf|firstOrNull)[[:space:]]*[({]'
scan "[4] プリミティブ値をボクシングする配列の候補" \
  'Array<(Int|Long|Double|Float|Boolean)>'
scan "[5] 可変長引数の宣言と Pair / Triple の生成候補" \
  '((^|[^[:alnum:]_])(vararg[[:space:]]|Pair\(|Triple\()|[[:space:]]to[[:space:]])'
scan "[6] 先頭削除・LinkedList の利用候補" \
  '(removeAt\([[:space:]]*0[[:space:]]*\)|\.removeFirst\(|LinkedList)'
scan "[7] 割り当てを確認する API の候補（values / withIndex / by lazy / runCatching）" \
  '(\.values\(\)|\.withIndex\(\)|(^|[^[:alnum:]_])(by[[:space:]]+lazy([^[:alnum:]_]|$)|runCatching[[:space:]]*\{))'
scan "[8] 時刻取得と単一ピクセル読み取りの候補" \
  '(Clock\.System\.now\(|System\.currentTimeMillis\(|System\.nanoTime\(|\.getPixel\()'
scan "[9] 同一行の文字列操作の連鎖" \
  '\.(substring|trim|lowercase|uppercase|replace)\([^)]*\)[[:space:]]*\.(substring|trim|lowercase|uppercase|replace)\('
scan "[10] アニメーションの駆動と描画 API の利用候補" \
  '(postDelayed\(|\.saveLayer\(|createBitmap\(|\.setPixel\(|(Linear|Radial|Sweep)Gradient\(|\.lock(Hardware)?Canvas\()'
