#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v777-fastscroll-gutter.py

GRADLE=app/build.gradle.kts
MAIN_XML=app/src/main/res/layout/activity_main.xml
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt

# Version.
grep -Fq '2098000777' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "777"' "$GRADLE"

# Main shelf and group shelf must reserve the same dedicated right-side gutter.
grep -Fq 'android:paddingEnd="28dp"' "$MAIN_XML"
grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$GROUP"

# Thumb must be drawn against the RecyclerView outer right edge, inside that gutter,
# not pulled left to the content edge by paddingRight.
grep -Fq 'val right = parent.width - edgeInset' "$SCROLLER"
grep -Fq 'val right = recyclerView.width - edgeInset' "$SCROLLER"
! grep -Fq 'width - parent.paddingRight - edgeInset' "$SCROLLER"
! grep -Fq 'width - recyclerView.paddingRight - edgeInset' "$SCROLLER"

# Horizontal touch ownership is fenced inside the gutter. It may not spill left into books/groups.
grep -Fq 'private val thumbHorizontalTouchPadding = 4f * density' "$SCROLLER"
grep -Fq 'val contentRight = (recyclerView.width - recyclerView.paddingRight).toFloat()' "$SCROLLER"
grep -Fq 'max(contentRight, thumbRect.left - thumbHorizontalTouchPadding)' "$SCROLLER"
grep -Fq 'coerceAtMost(recyclerView.width.toFloat())' "$SCROLLER"

# Existing behavior remains: thumb only becomes grabbable after real scrolling and deactivates later.
grep -Fq 'if (dy != 0 && geometry() != null) activateThumb()' "$SCROLLER"
grep -Fq 'thumbActive &&' "$SCROLLER"
grep -Fq 'isOnVisibleThumb(event.x, event.y)' "$SCROLLER"
grep -Fq 'postDelayed(deactivateRunnable, 1200L)' "$SCROLLER"

# User explicitly limited this fix to shelf/group UI. Reader page stays byte-for-byte v776.
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
READER_XML=app/src/main/res/layout/activity_reader.xml
SHARED_BINDER=app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt
SHARED_DAY=app/src/main/res/drawable/ic_mode_day_a.xml
SHARED_NIGHT=app/src/main/res/drawable/ic_mode_night_a.xml
git diff --quiet origin/source-v776 -- "$READER" "$READER_XML" "$SHARED_BINDER" "$SHARED_DAY" "$SHARED_NIGHT" || {
  echo 'FAIL: v777 touched reader-page implementation; slider fix must remain shelf/group only'
  git diff -- origin/source-v776 -- "$READER" "$READER_XML" "$SHARED_BINDER" "$SHARED_DAY" "$SHARED_NIGHT"
  exit 1
}

# Preserve the Android-35 startup-safe fast-scroller fix and v771 foreground/background handoff.
bash tools/v773-startup-fastscroll-gates.sh

echo 'v777 shelf/group fast-scroll gutter gates: PASS'
