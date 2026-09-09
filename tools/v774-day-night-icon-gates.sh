#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v774-day-night-icons.py

GRADLE=app/build.gradle.kts
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
ICON=app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt
DAY=app/src/main/res/drawable/ic_mode_day_a.xml
NIGHT=app/src/main/res/drawable/ic_mode_night_a.xml

# Version and A-style vector resources.
grep -Fq '2098000774' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "774"' "$GRADLE"
test -s "$DAY"
test -s "$NIGHT"
grep -Fq 'android:strokeLineCap="round"' "$DAY"
grep -Fq 'M15.9,2.9C10.5,3.8' "$NIGHT"

# Semantics are state-based and must never be inverted: DAY -> SUN, NIGHT -> MOON.
grep -Fq 'val isDay = mode == ReaderAppearance.MODE_DAY' "$ICON"
grep -Fq 'if (isDay) R.drawable.ic_mode_day_a else R.drawable.ic_mode_night_a' "$ICON"
grep -Fq '当前日间模式，点击切换夜间模式' "$ICON"
grep -Fq '当前夜间模式，点击切换日间模式' "$ICON"

# Both shelf and reader must use the same shared binder; reader appearance refreshes after any mode path.
grep -Fq 'DayNightModeIcon.apply(' "$MAIN"
grep -Fq 'refreshDayNightModeIcon()' "$READER"
grep -Fq 'DayNightModeIcon.apply(button, this, Color.rgb(238, 233, 221))' "$READER"
python3 - <<'PY'
from pathlib import Path
import re

main = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
main_xml = Path('app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
reader_xml = Path('app/src/main/res/layout/activity_reader.xml').read_text(encoding='utf-8')

assert 'ReaderAppearance.toggleMode(this@MainActivity)' in main
assert 'ReaderAppearance.toggleMode(this@ReaderActivity)' in reader
assert re.search(r'private fun applyReaderAppearance\(rebindPages: Boolean\) \{\s*refreshDayNightModeIcon\(\)', reader)

# The two toggle views must no longer contain the old text moon; other night-background swatches may still use their own UI.
def block(text, view_id):
    start = text.index(f'android:id="@+id/{view_id}"')
    return text[start:start+600]
assert 'android:text="☾"' not in block(main_xml, 'shelfNightButton')
assert 'android:text="☾"' not in block(reader_xml, 'nightButton')
PY

# Preserve the v773 Android-35 startup-crash fix and inherited handoff regression.
bash tools/v773-startup-fastscroll-gates.sh

echo 'v774 A-style day/night icon gates: PASS (DAY=sun, NIGHT=moon)'
