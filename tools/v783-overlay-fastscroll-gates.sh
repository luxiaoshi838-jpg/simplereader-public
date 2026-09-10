#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v783-overlay-fastscroll.py

GRADLE=app/build.gradle.kts
XML=app/src/main/res/layout/activity_main.xml
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
READER_XML=app/src/main/res/layout/activity_reader.xml

grep -Fq '2098000783' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "783"' "$GRADLE"

# The old 28dp dedicated scrollbar gutter must be gone. The RecyclerView canvas reaches the
# physical right edge while normal shelf content keeps the ordinary 16dp right margin.
grep -Fq 'android:layout_marginEnd="-16dp"' "$XML"
grep -Fq 'android:paddingEnd="16dp"' "$XML"
! grep -Fq 'android:paddingEnd="28dp"' "$XML"
grep -Fq 'setPadding(0, dp(8), dp(16), dp(18))' "$GROUP"
grep -Fq 'marginEnd = -dp(16)' "$GROUP"
! grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$GROUP"

# Main shelf's three columns must also drop the older hidden 14dp width reservation.
grep -Fq 'val horizontalPadding = dp(16 * 2)' "$MAIN"
! grep -Fq 'val horizontalPadding = dp(16 * 2 + 14)' "$MAIN"

# User-approved geometry from the uploaded reference: wide vertical pill, about 28x52dp,
# three horizontal grip marks, thin right-edge rail, and a collapsed edge indicator at rest.
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

# The handle is an ItemDecoration overlay, not a grid column. It may cover the right-most item,
# but may only intercept DOWN when it is visibly expanded after real scrolling.
grep -Fq 'override fun onDrawOver' "$SCROLLER"
grep -Fq 'if (dy != 0 && geometry() != null) activateThumb()' "$SCROLLER"
grep -Fq 'thumbActive &&' "$SCROLLER"
grep -Fq 'isOnVisibleThumb(event.x, event.y)' "$SCROLLER"
grep -Fq 'V783 intentionally allows the active hit target to overlap' "$SCROLLER"
! grep -Fq 'max(contentRight, thumbRect.left - thumbHorizontalTouchPadding)' "$SCROLLER"

# Preserve v773 Android-35 system-scrollbar safety.
! grep -Fq 'isScrollbarFadingEnabled' "$SCROLLER"
! grep -Fq 'isVerticalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'isHorizontalScrollBarEnabled' "$SCROLLER"
grep -Fq 'android:scrollbars="none"' "$XML"

# The change is shelf/group-only. Reader must not acquire shelf fast-scroll coupling.
! grep -Fq 'ShelfFastScroller' "$READER"
! grep -Fq 'ShelfFastScroller' "$READER_XML"

echo 'v783 right-edge overlay fast-scroll + reclaimed grid width gates: PASS'
