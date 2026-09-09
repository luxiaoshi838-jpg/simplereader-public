#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
layout = Path('app/src/main/res/layout/activity_reader.xml').read_text(encoding='utf-8')

# One row only: 字号/A-/数值/A+ -> 音量键翻页 -> 选中文本.
assert layout.count('@+id/selectTextToggleButton') == 1
assert 'android:text="文本"' not in layout, 'selection toggle must not create a separate settings row'
font_text = layout.index('android:text="字号"')
row_start = layout.rfind('<LinearLayout', 0, font_text)
row_end = layout.index('</LinearLayout>', font_text)
assert row_start >= 0
row = layout[row_start:row_end]
for token in [
    '@+id/fontDecreaseButton',
    '@+id/fontSizeLabel',
    '@+id/fontIncreaseButton',
    '@+id/volumeKeyToggleButton',
    '@+id/selectTextToggleButton',
]:
    assert token in row, f'missing from compact settings row: {token}'
assert row.index('@+id/fontDecreaseButton') < row.index('@+id/fontSizeLabel') < row.index('@+id/fontIncreaseButton')
assert row.index('@+id/fontIncreaseButton') < row.index('@+id/volumeKeyToggleButton') < row.index('@+id/selectTextToggleButton')

# Font controls are intentionally narrower/smaller than V766 so they move left and free room.
assert 'android:layout_width="42dp"' in row
assert row.count('android:layout_width="50dp"') >= 2
assert 'android:layout_width="38dp"' in row
assert row.count('android:textSize="15sp"') >= 2
assert 'android:textSize="14sp"' in row

# Toggle captions contain no 开/关 suffix; state is communicated by background color in code.
assert 'android:text="音量键翻页"' in row
assert 'android:text="选中文本"' in row
assert '音量键翻页 开' not in layout and '音量键翻页 关' not in layout
print('v767 compact settings-row layout gate: PASS')
PY
