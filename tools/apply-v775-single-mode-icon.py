#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]

# Version defaults.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000774"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000775"')
s = s.replace('?: 2098000774', '?: 2098000775')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "774"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "775"')
gradle.write_text(s, encoding='utf-8')

# Shared binder: use one ImageView image slot only. This structurally prevents sun+moon coexistence.
icon = root / 'app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt'
s = icon.read_text(encoding='utf-8')
s = s.replace('import android.widget.TextView', 'import android.widget.ImageView')
s = s.replace('fun apply(button: TextView, context: Context, tintColor: Int)', 'fun apply(button: ImageView, context: Context, tintColor: Int)')
s = s.replace('        button.text = ""\n        button.setCompoundDrawables(drawable, null, null, null)\n', '        button.setImageDrawable(null)\n        button.setImageDrawable(drawable)\n')
if 'setCompoundDrawables' in s or 'button.text =' in s:
    raise SystemExit('DayNightModeIcon still contains TextView compound-drawable path')
icon.write_text(s, encoding='utf-8')

# Main shelf uses ImageButton.
main = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
s = main.read_text(encoding='utf-8')
s = s.replace('findViewById<TextView>(R.id.shelfNightButton)', 'findViewById<ImageView>(R.id.shelfNightButton)')
main.write_text(s, encoding='utf-8')

# Reader uses ImageButton.
reader = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = reader.read_text(encoding='utf-8')
s = s.replace('findViewById<TextView>(R.id.nightButton)', 'findViewById<ImageButton>(R.id.nightButton)')
reader.write_text(s, encoding='utf-8')

# Replace the two toggle view tags with ImageButton and remove all TextView-only visual attributes.
def convert_toggle(path: Path, view_id: str, padding_dp: int):
    text = path.read_text(encoding='utf-8')
    start = text.find('<TextView\n', max(0, text.find(f'android:id="@+id/{view_id}"') - 200))
    if start < 0:
        # Already converted.
        if f'<ImageButton\n' in text and f'android:id="@+id/{view_id}"' in text:
            return
        raise SystemExit(f'{path.name}: start tag for {view_id} not found')
    id_pos = text.find(f'android:id="@+id/{view_id}"', start)
    end = text.find('/>', id_pos)
    if id_pos < 0 or end < 0:
        raise SystemExit(f'{path.name}: block for {view_id} not found')
    block = text[start:end+2]
    if f'android:id="@+id/{view_id}"' not in block:
        raise SystemExit(f'{path.name}: wrong block selected for {view_id}')
    block = block.replace('<TextView', '<ImageButton', 1)
    # Remove TextView-only lines.
    block = re.sub(r'\n\s*android:gravity="[^"]*"', '', block)
    block = re.sub(r'\n\s*android:text="[^"]*"', '', block)
    block = re.sub(r'\n\s*android:textColor="[^"]*"', '', block)
    block = re.sub(r'\n\s*android:textSize="[^"]*"', '', block)
    if 'android:background=' not in block:
        block = block.replace('/>', '    android:background="@android:color/transparent"\n            />')
    if 'android:scaleType=' not in block:
        block = block.replace('/>', f'    android:scaleType="centerInside"\n            android:padding="{padding_dp}dp"\n            />')
    else:
        block = re.sub(r'android:padding="[^"]*"', f'android:padding="{padding_dp}dp"', block)
    text = text[:start] + block + text[end+2:]
    path.write_text(text, encoding='utf-8')

convert_toggle(root / 'app/src/main/res/layout/activity_main.xml', 'shelfNightButton', 8)
convert_toggle(root / 'app/src/main/res/layout/activity_reader.xml', 'nightButton', 17)

print('v775 single-slot mode icons applied: one ImageButton, DAY=sun, NIGHT=moon')
