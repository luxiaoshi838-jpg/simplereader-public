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

# Main shelf uses ImageButton/ImageView API.
main = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
s = main.read_text(encoding='utf-8')
s = s.replace('findViewById<TextView>(R.id.shelfNightButton)', 'findViewById<ImageView>(R.id.shelfNightButton)')
main.write_text(s, encoding='utf-8')

# Reader uses ImageButton.
reader = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = reader.read_text(encoding='utf-8')
s = s.replace('findViewById<TextView>(R.id.nightButton)', 'findViewById<ImageButton>(R.id.nightButton)')
reader.write_text(s, encoding='utf-8')

# Replace the two toggle view tags with ImageButton. Re-running is intentionally a no-op.
def convert_toggle(path: Path, view_id: str, padding_dp: int):
    text = path.read_text(encoding='utf-8')
    marker = f'android:id="@+id/{view_id}"'
    id_pos = text.find(marker)
    if id_pos < 0:
        raise SystemExit(f'{path.name}: {view_id} not found')
    start = text.rfind('<', 0, id_pos)
    end = text.find('/>', id_pos)
    if start < 0 or end < 0:
        raise SystemExit(f'{path.name}: block for {view_id} not found')
    block = text[start:end + 2]
    if block.startswith('<ImageButton'):
        # Already converted by an earlier CI pass; verify the one-slot carrier contract and stop.
        if 'android:scaleType="centerInside"' not in block:
            raise SystemExit(f'{path.name}: converted {view_id} lacks centerInside scaleType')
        return
    if not block.startswith('<TextView'):
        raise SystemExit(f'{path.name}: unexpected carrier for {view_id}')

    block = block.replace('<TextView', '<ImageButton', 1)
    block = re.sub(r'\s+android:gravity="[^"]*"', '', block)
    block = re.sub(r'\s+android:text="[^"]*"', '', block)
    block = re.sub(r'\s+android:textColor="[^"]*"', '', block)
    block = re.sub(r'\s+android:textSize="[^"]*"', '', block)
    if 'android:background=' not in block:
        block = block[:-2].rstrip() + '\n                android:background="@android:color/transparent"\n            />'
    if 'android:scaleType=' not in block:
        block = block[:-2].rstrip() + f'\n                android:scaleType="centerInside"\n                android:padding="{padding_dp}dp"\n            />'
    text = text[:start] + block + text[end + 2:]
    path.write_text(text, encoding='utf-8')

convert_toggle(root / 'app/src/main/res/layout/activity_main.xml', 'shelfNightButton', 8)
convert_toggle(root / 'app/src/main/res/layout/activity_reader.xml', 'nightButton', 17)

print('v775 single-slot mode icons applied: one ImageButton, DAY=sun, NIGHT=moon')
