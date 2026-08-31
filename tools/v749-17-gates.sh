#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v748-14-gates.sh
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
F=app/src/main/java/com/simplereader/app/ui/ReaderBoundaryFogDrawable.kt
M=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
# 15: v745 background is applied on root/content; fallback scroll body stays transparent in normal appearance.
grep -Fq 'readerRoot.background = activeBackgroundDrawable()' "$R" || fail 15 'reader root background missing'
grep -Fq 'verticalAdapter?.refresh()' "$R" || fail 15 'vertical adapter refresh missing'
grep -Fq 'readerScrollView.background = ColorDrawable(Color.TRANSPARENT)' "$R" || fail 15 'scroll surface not transparent'
grep -Fq 'findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()' "$R" || fail 15 'window content background missing'
grep -Fq 'window.statusBarColor = Color.TRANSPARENT' "$R" || fail 15 'status bar not transparent'
grep -Fq 'window.navigationBarColor = Color.TRANSPARENT' "$R" || fail 15 'navigation bar not transparent'
grep -Fq 'window.isStatusBarContrastEnforced = false' "$R" || fail 15 'status contrast not disabled'
grep -Fq 'window.isNavigationBarContrastEnforced = false' "$R" || fail 15 'nav contrast not disabled'
grep -Fq 'updateBoundaryHazeStyle()' "$R" || fail 15 'haze style not refreshed'
B=app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt
grep -Fq 'ReaderBackgrounds.selectionFromLegacy(' "$R" || fail 15 'v745 legacy background preference migration not called'
for token in 'reader_background_color_id' 'reader_background_texture_id' 'reader_background_material_id'; do
  grep -Fq "$token" "$R" || fail 15 "legacy preference key missing: $token"
done
for token in \
  'material_duokan_dark" to "vine_black' 'material_duokan_green" to "vine_green' \
  'texture_duokan_blue" to "texture_blue' 'texture_duokan_yellow" to "texture_yellow' \
  'solid_ivory" to "scene_yellow' 'solid_night" to "scene_black' \
  'closestSceneId(legacyBackgroundColor)'; do
  grep -Fq "$token" "$B" || fail 15 "legacy background token missing: $token"
done
grep -Fq 'prefs.getInt(PREF_AUTO_READ_SPEED, 500)' "$R" || fail 15 'v745 automatic-reading default speed not restored'
pass 15 'v745 reading-background attachment/system-bar + legacy preference migration path'
# 16: exact shipped-v745 boundary fog constants and timing.
test -f "$F" || fail 16 'ReaderBoundaryFogDrawable source missing'
for token in 'scaledAlpha(138)' 'scaledAlpha(72)' 'scaledAlpha(24)' '0.20f' '0.55f' '0.72f' '0.34f' 'scaledAlpha(52)' 'scaledAlpha(22)' '0.58f' '0.16f' '0.38f'; do
  grep -Fq "$token" "$F" || fail 16 "fog token missing: $token"
done
grep -Fq 'if (pageTurnMode != TURN_MODE_VERTICAL) return' "$R" || fail 16 'haze must be vertical-only'
grep -Fq 'haze.alpha = 0.86f' "$R" || fail 16 'show alpha mismatch'
grep -Fq 'postDelayed(hideBoundaryHazeRunnable, 130L)' "$R" || fail 16 'show hold time mismatch'
grep -Fq 'alpha(0.52f).setDuration(260L)' "$R" || fail 16 'hide animation mismatch'
grep -Fq 'ReaderBoundaryFogDrawable(backgroundColor, true)' "$R" || fail 16 'top fog drawable missing'
grep -Fq 'ReaderBoundaryFogDrawable(backgroundColor, false)' "$R" || fail 16 'bottom fog drawable missing'
pass 16 'v745 top/bottom transition fog drawable and animation'
# 17: primary shelf card title exists only in cover; no second title TextView below it.
python3 - <<'PY' || exit 1
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text()
start=s.index('private fun addBookCard(')
end=s.index('private fun createBookCover(', start)
block=s[start:end]
if 'text = book.title' in block:
    raise SystemExit('FAIL 17 duplicate title remains below cover in addBookCard')
if 'card.addView(createBookCover(book, compact = false))' not in block:
    raise SystemExit('FAIL 17 shelf cover missing')
if 'text = "已读 ${book.progressPercent()}%$status"' not in block:
    raise SystemExit('FAIL 17 progress/status unexpectedly removed')
PY
pass 17 'shelf book title only on cover; no duplicate below-cover title'
printf 'ALL_17_GATES_PASS\n'
