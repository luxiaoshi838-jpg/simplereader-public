#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v751-22-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }

# 23: the strongest haze/core edge must coincide with the guarded reader viewport boundary
# and fade inward, not sit one haze-width inside the body.
F=app/src/main/java/com/simplereader/app/ui/ReaderBoundaryFogDrawable.kt
grep -Fq 'LinearGradient(0f, area.top.toFloat(), 0f, area.bottom.toFloat(), colors, positions' "$F" || fail 23 'top haze does not start at top reader boundary'
grep -Fq 'LinearGradient(0f, area.bottom.toFloat(), 0f, area.top.toFloat(), colors, positions' "$F" || fail 23 'bottom haze does not start at bottom reader boundary'
grep -Fq 'val start = if (topEdge) area.top.toFloat() else area.bottom.toFloat()' "$F" || fail 23 'soft core not anchored to reader boundary'
grep -Fq 'val direction = if (topEdge) 1f else -1f' "$F" || fail 23 'soft core does not fade inward'
grep -Fq 'intArrayOf(alpha52, alpha22, alpha22, transparent)' "$F" || fail 23 'soft core maximum is not at boundary'
! grep -Fq 'val start = if (topEdge) area.bottom.toFloat() else area.top.toFloat()' "$F" || fail 23 'old inward-shifted haze core returned'
pass 23 'top/bottom haze line anchored exactly to guarded reading-page bounds and fades inward'

# 24: TXT bookmark shifts left exactly 1/3 of active reading character while retaining the
# explicit search spacing inherited from gate 21. EPUB mirrors the same relative rule.
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -Fq 'translationX = -bookmarkOneThirdCharacterPx()' "$R" || fail 24 'TXT bookmark one-third-character shift missing'
grep -Fq 'readerTextSizeSp * resources.displayMetrics.scaledDensity / 3f' "$R" || fail 24 'TXT one-third-character calculation missing'
grep -Fq 'marginStart = dp(8)' "$R" || fail 24 'TXT search/bookmark spacing removed'
E=app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt
grep -Fq 'translationX = -(20.0 * fontScale * resources.displayMetrics.scaledDensity / 3.0).toFloat()' "$E" || fail 24 'EPUB bookmark one-third-character shift missing'
grep -Fq 'marginStart = dp(8)' "$E" || fail 24 'EPUB search/bookmark spacing removed'
pass 24 'bookmark marker moved left by exactly one-third character while preserving search gap'
printf 'ALL_24_GATES_PASS\n'
