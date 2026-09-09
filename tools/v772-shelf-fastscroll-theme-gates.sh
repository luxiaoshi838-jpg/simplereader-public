#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

build="app/build.gradle.kts"
layout="app/src/main/res/layout/activity_main.xml"
main="app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
group="app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt"
fast="app/src/main/java/com/simplereader/app/ui/FastScrollRecyclerView.kt"

require_fixed() {
  local needle="$1" file="$2"
  grep -Fq "$needle" "$file" || {
    echo "missing: $needle in $file" >&2
    exit 1
  }
}

require_fixed '2098000772' "$build"
require_fixed 'generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME") ?: "772"' "$build"

# Main shelf and group shelf must use the same custom RecyclerView fast-scroll behavior.
require_fixed '<com.simplereader.app.ui.FastScrollRecyclerView' "$layout"
require_fixed 'addView(FastScrollRecyclerView(this@GroupBooksActivity).apply {' "$group"
require_fixed 'class FastScrollRecyclerView' "$fast"
require_fixed 'if (!thumbActive && !draggingThumb) return' "$fast"
require_fixed 'thumbActive &&' "$fast"
require_fixed 'isOnVisibleThumb(event.x, event.y)' "$fast"
require_fixed 'postDelayed(deactivateRunnable, 1200L)' "$fast"
require_fixed 'stopScroll()' "$fast"

# The thumb must NOT be a permanently active touch target.
if grep -Fq 'private var thumbActive = true' "$fast"; then
  echo 'fast-scroll thumb is permanently active; must only be grabbable while scrolling' >&2
  exit 1
fi

# Shelf day/night button must sit immediately to the left of search and use the exact reader mode store.
require_fixed '@+id/shelfNightButton' "$layout"
require_fixed 'ReaderAppearance.toggleMode(this@MainActivity)' "$main"
require_fixed 'findViewById<TextView>(R.id.shelfNightButton).setTextColor(primaryText)' "$main"
python3 - <<'PY'
from pathlib import Path
layout = Path('app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
night = layout.index('@+id/shelfNightButton')
search = layout.index('@+id/searchButton')
if not night < search:
    raise SystemExit('shelf day/night button is not left of search')
if 'android:scrollbars="vertical"' in layout:
    raise SystemExit('native passive shelf scrollbar still enabled')
PY

# Preserve the v771 shelf/reader handoff regression gate under the new version.
TMP="$(mktemp tools/v772-handoff-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
s = Path('tools/v771-shelf-reader-handoff-gates.sh').read_text(encoding='utf-8')
s = s.replace('2098000771', '2098000772').replace('"771"', '"772"')
Path(sys.argv[1]).write_text(s, encoding='utf-8')
PY
bash "$TMP"

echo 'v772 shelf fast-scroll + day/night gates passed'
