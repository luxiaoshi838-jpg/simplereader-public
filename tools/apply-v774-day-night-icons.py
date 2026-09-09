#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]

# Version defaults.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000773"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000774"')
s = s.replace('?: 2098000773', '?: 2098000774')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "773"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "774"')
gradle.write_text(s, encoding='utf-8')

# Main shelf: state icon is semantic state, DAY=sun / NIGHT=moon.
main = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
s = main.read_text(encoding='utf-8')
old = '''        findViewById<TextView>(R.id.shelfNightButton).apply {
            text = "☾"
            contentDescription = "日间夜间模式"
            setOnClickListener {
                ReaderAppearance.toggleMode(this@MainActivity)
                applyShelfAppearance()
                shelfAdapter.notifyDataSetChanged()
            }
        }
'''
new = '''        findViewById<TextView>(R.id.shelfNightButton).apply {
            DayNightModeIcon.apply(
                this,
                this@MainActivity,
                ReaderAppearance.shelfTextColor(this@MainActivity)
            )
            setOnClickListener {
                ReaderAppearance.toggleMode(this@MainActivity)
                applyShelfAppearance()
                DayNightModeIcon.apply(
                    this,
                    this@MainActivity,
                    ReaderAppearance.shelfTextColor(this@MainActivity)
                )
                shelfAdapter.notifyDataSetChanged()
            }
        }
'''
if old in s:
    s = s.replace(old, new, 1)
elif 'DayNightModeIcon.apply(' not in s:
    raise SystemExit('MainActivity shelfNightButton block not found')

resume_old = '''        applyShelfAppearance()
        updateUI()
'''
resume_new = '''        applyShelfAppearance()
        findViewById<TextView>(R.id.shelfNightButton).let { button ->
            DayNightModeIcon.apply(
                button,
                this,
                ReaderAppearance.shelfTextColor(this)
            )
        }
        updateUI()
'''
if resume_new not in s:
    if resume_old not in s:
        raise SystemExit('MainActivity onResume appearance anchor not found')
    s = s.replace(resume_old, resume_new, 1)
main.write_text(s, encoding='utf-8')

# Reader page: same shared icon logic. applyReaderAppearance refreshes the icon so every mode path
# (bottom toggle, night background button, custom background/day return) keeps the icon in sync.
reader = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = reader.read_text(encoding='utf-8')
old = '''        findViewById<TextView>(R.id.nightButton).setOnClickListener {
            ReaderAppearance.toggleMode(this)
            applyReaderAppearance(rebindPages = true)
        }
'''
new = '''        findViewById<TextView>(R.id.nightButton).apply {
            refreshDayNightModeIcon()
            setOnClickListener {
                ReaderAppearance.toggleMode(this@ReaderActivity)
                applyReaderAppearance(rebindPages = true)
            }
        }
'''
if old in s:
    s = s.replace(old, new, 1)
elif 'findViewById<TextView>(R.id.nightButton).apply {' not in s:
    raise SystemExit('ReaderActivity nightButton block not found')

signature = '    private fun applyReaderAppearance(rebindPages: Boolean) {\n'
helper = '''    private fun refreshDayNightModeIcon() {
        val button = findViewById<TextView>(R.id.nightButton)
        DayNightModeIcon.apply(button, this, Color.rgb(238, 233, 221))
    }

'''
if helper not in s:
    if signature not in s:
        raise SystemExit('ReaderActivity applyReaderAppearance signature not found')
    s = s.replace(signature, helper + signature, 1)

appearance_with_refresh = signature + '        refreshDayNightModeIcon()\n'
if appearance_with_refresh not in s:
    if signature not in s:
        raise SystemExit('ReaderActivity appearance signature missing after helper insert')
    s = s.replace(signature, appearance_with_refresh, 1)
reader.write_text(s, encoding='utf-8')

# Remove old text glyphs from launch layouts to avoid a one-frame old moon before binding.
def clear_text_glyph(path: Path, view_id: str, glyph: str):
    text = path.read_text(encoding='utf-8')
    pattern = rf'(android:id="@\+id/{re.escape(view_id)}"[\s\S]{{0,500}}?android:text=")({re.escape(glyph)})(")'
    updated, count = re.subn(pattern, r'\1\3', text, count=1)
    if count == 0 and f'android:id="@+id/{view_id}"' not in text:
        raise SystemExit(f'{path.name}: {view_id} not found')
    path.write_text(updated, encoding='utf-8')

clear_text_glyph(root / 'app/src/main/res/layout/activity_main.xml', 'shelfNightButton', '☾')
clear_text_glyph(root / 'app/src/main/res/layout/activity_reader.xml', 'nightButton', '☾')

print('v774 A-style day/night icons applied: DAY=sun, NIGHT=moon')
