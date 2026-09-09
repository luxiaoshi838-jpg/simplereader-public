#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v777-fastscroll-gutter.py
python3 tools/apply-v778-center-fastscroll-track.py

GRADLE=app/build.gradle.kts
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
MAIN_XML=app/src/main/res/layout/activity_main.xml
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt

# Version.
grep -Fq '2098000778' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "778"' "$GRADLE"

# Preserve v777 dedicated right-side gutters for both main shelf and group shelf.
grep -Fq 'android:paddingEnd="28dp"' "$MAIN_XML"
grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$GROUP"

# The track and thumb must use one exact horizontal centerline.
grep -Fq 'val thumbCenterX = right - thumbWidth / 2f' "$SCROLLER"
grep -Fq 'thumbCenterX - trackWidth / 2f' "$SCROLLER"
grep -Fq 'thumbCenterX + trackWidth / 2f' "$SCROLLER"
grep -Fq 'thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)' "$SCROLLER"
! grep -Fq 'right - trackWidth,' "$SCROLLER"

# Mathematical invariant: track center == thumb center.
python3 - <<'PY'
from pathlib import Path
s = Path('app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt').read_text(encoding='utf-8')
assert 'val thumbCenterX = right - thumbWidth / 2f' in s
assert 'thumbCenterX - trackWidth / 2f' in s
assert 'thumbCenterX + trackWidth / 2f' in s
assert 'thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)' in s
PY

# Existing anti-mistouch and activation behavior remains unchanged.
grep -Fq 'val contentRight = (recyclerView.width - recyclerView.paddingRight).toFloat()' "$SCROLLER"
grep -Fq 'max(contentRight, thumbRect.left - thumbHorizontalTouchPadding)' "$SCROLLER"
grep -Fq 'if (dy != 0 && geometry() != null) activateThumb()' "$SCROLLER"
grep -Fq 'postDelayed(deactivateRunnable, 1200L)' "$SCROLLER"

# Preserve all v777 gutter/startup/reader-unchanged regressions.
bash tools/v777-fastscroll-gutter-gates.sh

echo 'v778 fast-scroll track centered on thumb: PASS'
