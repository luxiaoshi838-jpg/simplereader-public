#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v775-single-mode-icon.py

GRADLE=app/build.gradle.kts
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
ICON=app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt
MAIN_XML=app/src/main/res/layout/activity_main.xml
READER_XML=app/src/main/res/layout/activity_reader.xml
DAY=app/src/main/res/drawable/ic_mode_day_a.xml
NIGHT=app/src/main/res/drawable/ic_mode_night_a.xml

grep -Fq '2098000775' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "775"' "$GRADLE"
test -s "$DAY"
test -s "$NIGHT"

# Hard semantic lock: current DAY shows sun, current NIGHT shows moon.
grep -Fq 'val isDay = mode == ReaderAppearance.MODE_DAY' "$ICON"
grep -Fq 'if (isDay) R.drawable.ic_mode_day_a else R.drawable.ic_mode_night_a' "$ICON"
grep -Fq '当前日间模式，点击切换夜间模式' "$ICON"
grep -Fq '当前夜间模式，点击切换日间模式' "$ICON"

# One-slot image implementation: compound drawables/text carriers are forbidden.
grep -Fq 'fun apply(button: ImageView, context: Context, tintColor: Int)' "$ICON"
grep -Fq 'button.setImageDrawable(null)' "$ICON"
grep -Fq 'button.setImageDrawable(drawable)' "$ICON"
! grep -Fq 'setCompoundDrawables' "$ICON"
! grep -Fq 'button.text =' "$ICON"

grep -Fq 'findViewById<ImageView>(R.id.shelfNightButton)' "$MAIN"
grep -Fq 'findViewById<ImageButton>(R.id.nightButton)' "$READER"
grep -Fq 'ReaderAppearance.toggleMode(this@MainActivity)' "$MAIN"
grep -Fq 'ReaderAppearance.toggleMode(this@ReaderActivity)' "$READER"
grep -Fq 'DayNightModeIcon.apply(findViewById<ImageView>(R.id.shelfNightButton), this, primaryText)' "$MAIN"
grep -Fq 'refreshDayNightModeIcon()' "$READER"

# Converted ids must never return to TextView-only APIs; these caused the v774 two-icon/swap bug.
! grep -Fq 'shelfNightButton).setTextColor' "$MAIN"
! grep -Fq 'nightButton).text =' "$READER"
! grep -Fq 'findViewById<TextView>(R.id.shelfNightButton)' "$MAIN"
! grep -Fq 'findViewById<TextView>(R.id.nightButton)' "$READER"

python3 - <<'PY'
from pathlib import Path
import re

main_xml = Path('app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
reader_xml = Path('app/src/main/res/layout/activity_reader.xml').read_text(encoding='utf-8')
icon = Path('app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt').read_text(encoding='utf-8')

def view_block(text, view_id):
    pos = text.index(f'android:id="@+id/{view_id}"')
    start = text.rfind('<', 0, pos)
    end = text.index('/>', pos) + 2
    return text[start:end]

for text, view_id in [(main_xml, 'shelfNightButton'), (reader_xml, 'nightButton')]:
    b = view_block(text, view_id)
    assert b.startswith('<ImageButton'), b
    assert 'android:scaleType="centerInside"' in b
    assert 'android:text=' not in b
    assert 'android:textColor=' not in b
    assert 'android:textSize=' not in b

# Exactly one drawable resource is selected per call, then installed into one ImageView slot.
assert icon.count('setImageDrawable(drawable)') == 1
assert 'setCompoundDrawables' not in icon
assert re.search(r'val drawableRes = if \(isDay\) R\.drawable\.ic_mode_day_a else R\.drawable\.ic_mode_night_a', icon)
PY

# Compile/startup smoke + preserve v773 startup crash fix and v771 handoff regression.
bash tools/v773-startup-fastscroll-gates.sh

echo 'v775 single-mode-icon gates: PASS (one image only; DAY=sun, NIGHT=moon)'
