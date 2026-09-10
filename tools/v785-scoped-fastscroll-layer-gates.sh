#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GRADLE=app/build.gradle.kts
XML=app/src/main/res/layout/activity_main.xml
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
READER_XML=app/src/main/res/layout/activity_reader.xml

grep -Fq '2098000785' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "785"' "$GRADLE"

# Main root must clip normally so RecyclerView/book contents cannot bleed upward over toolbar rows.
grep -Fq 'android:clipChildren="true"' "$XML"
grep -Fq 'android:clipToPadding="true"' "$XML"
grep -Fq 'android:paddingStart="0dp"' "$XML"
grep -Fq 'android:paddingEnd="0dp"' "$XML"
! grep -Fq 'android:layout_marginEnd="-16dp"' "$XML"

# Both top toolbars keep their original 16dp visual inset; the RecyclerView itself is full-width.
test "$(grep -Fc 'android:paddingStart="16dp"' "$XML")" -ge 3
test "$(grep -Fc 'android:paddingEnd="16dp"' "$XML")" -ge 3
grep -Fq 'android:id="@+id/exportButton"' "$XML"
grep -Fq 'android:id="@+id/importButton"' "$XML"
grep -Fq 'android:id="@+id/editButton"' "$XML"

# Group screen follows the same containment rule.
grep -Fq 'clipChildren = true' "$GROUP"
grep -Fq 'clipToPadding = true' "$GROUP"
grep -Fq 'setPadding(0, statusBarHeight + dp(12), 0, dp(8))' "$GROUP"
grep -Fq 'setPadding(dp(16), 0, dp(16), 0)' "$GROUP"
grep -Fq 'setPadding(dp(16), dp(8), dp(16), dp(18))' "$GROUP"
! grep -Fq 'marginEnd = -dp(16)' "$GROUP"

# Only the fast-scroll decoration is topmost relative to book/group items.
grep -Fq 'V785_SCOPED_TOPMOST_FAST_SCROLL' "$SCROLLER"
grep -Fq 'override fun onDrawOver' "$SCROLLER"
grep -Fq 'private val thumbWidth = 28f * density' "$SCROLLER"
grep -Fq 'private val fixedThumbHeight = 52f * density' "$SCROLLER"
grep -Fq 'parent.width.toFloat() - trackWidth' "$SCROLLER"
! grep -Fq 'shelfGrid.bringToFront' "$MAIN"
! grep -Fq 'shelfGrid.translationZ' "$MAIN"

# Preserve Android 35 safety and reader isolation.
! grep -Fq 'isScrollbarFadingEnabled' "$SCROLLER"
! grep -Fq 'isVerticalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'isHorizontalScrollBarEnabled' "$SCROLLER"
! grep -Fq 'ShelfFastScroller' "$READER"
! grep -Fq 'ShelfFastScroller' "$READER_XML"

echo 'V785_SCOPED_FAST_SCROLL_LAYER_GATES_PASS'
