#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v784-unclipped-topmost-fastscroll.py

GRADLE=app/build.gradle.kts
XML=app/src/main/res/layout/activity_main.xml
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
READER_XML=app/src/main/res/layout/activity_reader.xml

grep -Fq '2098000784' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "784"' "$GRADLE"

# Main and group roots must not clip the RecyclerView portion extended through the 16dp right
# padding. This is the exact regression that made only the left half of the 28dp pill visible.
grep -Fq 'android:clipChildren="false"' "$XML"
grep -Fq 'android:clipToPadding="false"' "$XML"
grep -Fq 'clipChildren = false' "$GROUP"
grep -Fq 'clipToPadding = false' "$GROUP"

# Preserve v783 full-width grid layout: no dedicated scrollbar column returns.
grep -Fq 'android:layout_marginEnd="-16dp"' "$XML"
grep -Fq 'android:paddingEnd="16dp"' "$XML"
grep -Fq 'setPadding(0, dp(8), dp(16), dp(18))' "$GROUP"
grep -Fq 'marginEnd = -dp(16)' "$GROUP"
! grep -Fq 'android:paddingEnd="28dp"' "$XML"
! grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$GROUP"

# The pill stays the user-approved 28x52dp reference geometry and is drawn above item children.
grep -Fq 'V784_UNCLIPPED_TOPMOST_FAST_SCROLL' "$SCROLLER"
grep -Fq 'private val thumbWidth = 28f * density' "$SCROLLER"
grep -Fq 'private val fixedThumbHeight = 52f * density' "$SCROLLER"
grep -Fq 'override fun onDrawOver' "$SCROLLER"
grep -Fq 'drawExpandedThumb(canvas)' "$SCROLLER"

# Preserve Android-35 safety and keep reader entirely unrelated to shelf fast-scroll.
! grep -Fq 'isScrollbarFadingEnabled' "$SCROLLER"
! grep -Fq 'isVerticalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'isHorizontalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'ShelfFastScroller' "$READER"
! grep -Fq 'ShelfFastScroller' "$READER_XML"

echo 'V784_UNCLIPPED_TOPMOST_FAST_SCROLL_GATES_PASS'
