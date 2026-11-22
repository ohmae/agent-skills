#!/usr/bin/env bash
# Kotlin コードベースからパフォーマンス劣化の典型的な候補箇所を素早くスキャンする補助スクリプト
# 使い方: ./scan-kotlin-hotspots.sh <target_directory>

set -euo pipefail

TARGET_DIR="${1:-.}"

if [ ! -d "$TARGET_DIR" ]; then
  echo "Error: Directory '$TARGET_DIR' does not exist." >&2
  exit 1
fi

echo "=== Scanning Kotlin hotspots in: $TARGET_DIR ==="

echo ""
echo "[1] 多段コレクションチェーン (map/filter/flatMap/sorted の連続):"
grep -rnE --include="*.kt" '\.(map|filter|flatMap|sortedBy|sortedWith)\s*\{.*\}\s*\.(map|filter|flatMap|sortedBy|take|first)' "$TARGET_DIR" || echo "  (none found on single lines)"

echo ""
echo "[2] Regex / Pattern / Formatter の都度生成候補:"
grep -rnE --include="*.kt" '(Regex\(|toRegex\(|Pattern\.compile\(|SimpleDateFormat\()' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[3] 線形探索・重複操作の候補 (.contains / .find / .indexOf):"
grep -rnE --include="*.kt" '\.(contains|find|indexOf|firstOrNull)\s*(\(|\{)' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[4] プリミティブのボクシング配列・リスト候補 (Array<Int|Long|Double|Float>):"
grep -rnE --include="*.kt" 'Array<(Int|Long|Double|Float|Boolean)>' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[5] 可変長引数・スプレッド演算子や Pair/Triple 生成候補:"
grep -rnE --include="*.kt" '(vararg\s+|\bPair\(|\bTriple\(|\sto\s)' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[6] 先頭削除・LinkedList の利用候補 (removeAt(0) / removeFirst / LinkedList):"
grep -rnE --include="*.kt" '(removeAt\(\s*0\s*\)|\.removeFirst\(|LinkedList)' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[7] 構文・API の隠れた割り当て候補 (values() / withIndex() / by lazy / runCatching):"
grep -rnE --include="*.kt" '(\.values\(\)|\.withIndex\(\)|\bby\s+lazy\b|\brunCatching\s*\{)' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[8] 時刻取得の繰り返し・個別ピクセル取得候補 (Clock.System.now / currentTimeMillis / getPixel):"
grep -rnE --include="*.kt" '(Clock\.System\.now\(|System\.currentTimeMillis\(|System\.nanoTime\(|\.getPixel\()' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[9] 文字列の部分処理連鎖候補 (substring / trim / lowercase / uppercase / replace):"
grep -rnE --include="*.kt" '\.(substring|trim|lowercase|uppercase|replace)\([^)]*\)\s*\.(substring|trim|lowercase|uppercase|replace)\(' "$TARGET_DIR" || echo "  (none found)"

echo ""
echo "[10] アニメーションループ・描画ホットパスの候補 (postDelayed / saveLayer / createBitmap / setPixel / Gradient / lockCanvas):"
grep -rnE --include="*.kt" '(postDelayed\(|\.saveLayer\(|createBitmap\(|\.setPixel\(|(Linear|Radial|Sweep)Gradient\(|\.lock(Hardware)?Canvas\()' "$TARGET_DIR" || echo "  (none found)"
