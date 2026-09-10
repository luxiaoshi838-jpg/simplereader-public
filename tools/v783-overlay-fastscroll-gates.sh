#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

XML=app/src/main/res/layout/activity_main.xml
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
READER_XML=app/src/main/res/layout/activity_reader.xml

# Historical v783 gate is behavior-only. Later versions may change how the RecyclerView reaches
# the physical right edge, so do not require the old negative-margin / unclipped-root mechanism.

# The old dedicated 28dp scrollbar gutter must stay gone; ordinary shelf content remains 16dp inset.
! grep -Fq 'android:paddingEnd="28dp"' "$XML"
! grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$GROUP"
grep -Fq 'android:paddingEnd="16dp"' "$XML"

# Main shelf's three columns must also keep the reclaimed width from v783.
grep -Fq 'val horizontalPadding = dp(16 * 2)' "$MAIN"
! grep -Fq 'val horizontalPadding = dp(16 * 2 + 14)' "$MAIN"

# User-approved geometry: wide vertical pill, about 28x52dp, three horizontal grip marks,
# thin right-edge rail, and a collapsed edge indicator at rest.
grep -Fq 'V783_OVERLAY_FAST_SCROLL' "$SCROLLER"
grep -Fq 'private val thumbWidth = 28f * density' "$SCROLLER"
grep -Fq 'private val fixedThumbHeight = 52f * density' "$SCROLLER"
grep -Fq 'private val trackWidth = 1f * density' "$SCROLLER"
grep -Fq 'parent.width.toFloat() - trackWidth' "$SCROLLER"
grep -Fq 'drawExpandedThumb(canvas)' "$SCROLLER"
grep -Fq 'drawCollapsedThumb(canvas)' "$SCROLLER"
grep -Fq 'canvas.drawLine(left, centerY - gripGap' "$SCROLLER"
grep -Fq 'canvas.drawLine(left, centerY, right, centerY, gripPaint)' "$SCROLLER"
grep -Fq 'canvas.drawLine(left, centerY + gripGap' "$SCROLLER"

# The handle remains an ItemDecoration overlay and only becomes draggable when visibly expanded.
grep -Fq 'override fun onDrawOver' "$SCROLLER"
grep -Fq 'if (dy != 0 && geometry() != null) activateThumb()' "$SCROLLER"
grep -Fq 'thumbActive &&' "$SCROLLER"
grep -Fq 'isOnVisibleThumb(event.x, event.y)' "$SCROLLER"

# Preserve v773 Android-35 system-scrollbar safety.
! grep -Fq 'isScrollbarFadingEnabled' "$SCROLLER"
! grep -Fq 'isVerticalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'isHorizontalScrollBarEnabled' "$SCROLLER"
grep -Fq 'android:scrollbars="none"' "$XML"

# Reader remains unrelated to shelf fast-scroll.
! grep -Fq 'ShelfFastScroller' "$READER"
! grep -Fq 'ShelfFastScroller' "$READER_XML"

echo 'v783 overlay fast-scroll behavior gates: PASS'
