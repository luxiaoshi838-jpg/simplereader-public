#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

# Version defaults.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000775"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000776"')
s = s.replace('?: 2098000775', '?: 2098000776')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "775"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "776"')
gradle.write_text(s, encoding='utf-8')

# Shelf only: switch MainActivity to its own binder. ReaderActivity and the shared
# DayNightModeIcon remain byte-for-byte unchanged from v775.
main = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
s = main.read_text(encoding='utf-8')
s = s.replace('DayNightModeIcon.apply(', 'ShelfDayNightModeIcon.apply(')
if 'DayNightModeIcon.apply(' in s:
    raise SystemExit('MainActivity still contains shared DayNightModeIcon binding')
main.write_text(s, encoding='utf-8')

# Required shelf-only assets must already be source-controlled.
shelf_binder = root / 'app/src/main/java/com/simplereader/app/ui/ShelfDayNightModeIcon.kt'
shelf_day = root / 'app/src/main/res/drawable/ic_shelf_mode_day_outline.xml'
if not shelf_binder.is_file() or not shelf_day.is_file():
    raise SystemExit('v776 shelf-only binder/drawable missing')

print('v776 applied: shelf DAY=black-outline white-fill sun; reader unchanged from v775')
