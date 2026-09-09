#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v773-startup-safe-fastscroll.py

XML=app/src/main/res/layout/activity_main.xml
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
GROUP=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
SCROLLER=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
TEST=app/src/test/java/com/simplereader/app/ui/MainActivityStartupSmokeTest.kt

# Startup safety: the launch layout must inflate the stable platform RecyclerView.
grep -Fq '<androidx.recyclerview.widget.RecyclerView' "$XML"
! grep -Fq '<com.simplereader.app.ui.FastScrollRecyclerView' "$XML"
! grep -Fq 'FastScrollRecyclerView(this@GroupBooksActivity)' "$GROUP"

# Same attach-only scroller is used by main shelf and group shelf.
grep -Fq 'ShelfFastScroller.attach(shelfGrid)' "$MAIN"
grep -Fq 'ShelfFastScroller.attach(this)' "$GROUP"
grep -Fq 'class ShelfFastScroller private constructor' "$SCROLLER"
grep -Fq 'addItemDecoration(scroller)' "$SCROLLER"
grep -Fq 'addOnItemTouchListener(scroller)' "$SCROLLER"
grep -Fq 'addOnScrollListener(scroller.scrollListener)' "$SCROLLER"

# Thumb cannot be grabbed until real list movement made it active; hit must be on the visible thumb.
grep -Fq 'if (dy != 0 && geometry() != null) activateThumb()' "$SCROLLER"
grep -Fq 'thumbActive &&' "$SCROLLER"
grep -Fq 'isOnVisibleThumb(event.x, event.y)' "$SCROLLER"
grep -Fq 'RecyclerView.SCROLL_STATE_DRAGGING -> Unit' "$SCROLLER"

# Shelf day/night button remains immediately before search and uses the exact reader mode store.
python3 - <<'PY'
from pathlib import Path
xml = Path('app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
assert xml.index('@+id/shelfNightButton') < xml.index('@+id/searchButton')
main = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text(encoding='utf-8')
assert 'ReaderAppearance.toggleMode(this@MainActivity)' in main
PY

# Cold-start smoke test must exist and explicitly prove the shelf is the platform RecyclerView.
grep -Fq 'mainActivityColdStartReachesShelf' "$TEST"
grep -Fq 'assertTrue(shelf.javaClass == RecyclerView::class.java)' "$TEST"
./gradlew testDebugUnitTest --tests com.simplereader.app.ui.MainActivityStartupSmokeTest --stacktrace --console=plain

# Preserve v771 shelf/reader handoff regression.
bash tools/v771-shelf-reader-handoff-gates.sh

echo 'v773 startup-safe shelf fast-scroll/day-night gates: PASS'
