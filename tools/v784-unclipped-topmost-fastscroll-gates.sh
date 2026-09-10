#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
READER_XML=app/src/main/res/layout/activity_reader.xml

# v784's durable behavior is that the custom handle is fully drawn above shelf/group items and
# remains independent of Android's platform scrollbar. Later versions may change ancestor layout
# containment, so this inherited gate must not re-apply the historical v784 patch or require its
# clipChildren=false implementation.
grep -Fq 'V784_UNCLIPPED_TOPMOST_FAST_SCROLL' "$SCROLLER"
grep -Fq 'private val thumbWidth = 28f * density' "$SCROLLER"
grep -Fq 'private val fixedThumbHeight = 52f * density' "$SCROLLER"
grep -Fq 'override fun onDrawOver' "$SCROLLER"
grep -Fq 'drawExpandedThumb(canvas)' "$SCROLLER"
grep -Fq 'parent.width.toFloat() - trackWidth' "$SCROLLER"

# Preserve Android-35 safety and reader isolation.
! grep -Fq 'isScrollbarFadingEnabled' "$SCROLLER"
! grep -Fq 'isVerticalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'isHorizontalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'ShelfFastScroller' "$READER"
! grep -Fq 'ShelfFastScroller' "$READER_XML"

echo 'V784_TOPMOST_FAST_SCROLL_BEHAVIOR_GATES_PASS'
