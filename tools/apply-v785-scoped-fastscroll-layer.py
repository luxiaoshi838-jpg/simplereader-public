#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'{label} marker not found')
    return text.replace(old, new, 1)

# Version 785.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000784"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000785"')
s = s.replace('?: 2098000784', '?: 2098000785')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "784"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "785"')
gradle.write_text(s, encoding='utf-8')

# Main shelf: do NOT raise/unclip the whole shelf hierarchy. Instead make the RecyclerView itself
# span the physical screen width, keep the same 16dp book/content insets, and restore normal root
# clipping so scrolled book/group contents can never paint over the toolbar rows. The fast-scroll
# pill still uses RecyclerView.onDrawOver(), so only the pill is above the grid items.
xml = root / 'app/src/main/res/layout/activity_main.xml'
s = xml.read_text(encoding='utf-8')
s = replace_once(
    s,
    '''    android:background="#F2EFE6"\n    android:clipChildren="false"\n    android:clipToPadding="false"\n    android:orientation="vertical"\n    android:paddingStart="16dp"\n    android:paddingTop="18dp"\n    android:paddingEnd="16dp"\n    android:paddingBottom="8dp">''',
    '''    android:background="#F2EFE6"\n    android:clipChildren="true"\n    android:clipToPadding="true"\n    android:orientation="vertical"\n    android:paddingStart="0dp"\n    android:paddingTop="18dp"\n    android:paddingEnd="0dp"\n    android:paddingBottom="8dp">''',
    'main root scoped clipping',
)
s = replace_once(
    s,
    '''        android:layout_height="48dp"\n        android:gravity="center_vertical"\n        android:orientation="horizontal">''',
    '''        android:layout_height="48dp"\n        android:gravity="center_vertical"\n        android:orientation="horizontal"\n        android:paddingStart="16dp"\n        android:paddingEnd="16dp">''',
    'main title toolbar horizontal inset',
)
s = replace_once(
    s,
    '''        android:layout_height="56dp"\n        android:layout_marginTop="7dp"\n        android:gravity="center_vertical"\n        android:orientation="horizontal">''',
    '''        android:layout_height="56dp"\n        android:layout_marginTop="7dp"\n        android:gravity="center_vertical"\n        android:orientation="horizontal"\n        android:paddingStart="16dp"\n        android:paddingEnd="16dp">''',
    'main data toolbar horizontal inset',
)
s = replace_once(
    s,
    '''        android:clipToPadding="false"\n        android:layout_marginEnd="-16dp"\n        android:paddingEnd="16dp"\n        android:scrollbars="none" />''',
    '''        android:clipToPadding="false"\n        android:paddingStart="16dp"\n        android:paddingEnd="16dp"\n        android:scrollbars="none" />''',
    'main full-width recycler without negative margin',
)
xml.write_text(s, encoding='utf-8')

# Group shelf: same structure as the main shelf. The root/header keep normal clipping and 16dp
# visual insets, while the RecyclerView spans the full screen width. This keeps the handle fully
# visible at the physical right edge without allowing book rows to bleed into the top action bar.
group = root / 'app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt'
s = group.read_text(encoding='utf-8')
s = replace_once(
    s,
    '''            orientation = LinearLayout.VERTICAL\n            clipChildren = false\n            clipToPadding = false\n            setBackgroundColor(ReaderAppearance.palette(this@GroupBooksActivity).backgroundColor)\n            setPadding(dp(16), statusBarHeight + dp(12), dp(16), dp(8))''',
    '''            orientation = LinearLayout.VERTICAL\n            clipChildren = true\n            clipToPadding = true\n            setBackgroundColor(ReaderAppearance.palette(this@GroupBooksActivity).backgroundColor)\n            setPadding(0, statusBarHeight + dp(12), 0, dp(8))''',
    'group root scoped clipping',
)
s = replace_once(
    s,
    '''                orientation = LinearLayout.HORIZONTAL\n                gravity = Gravity.CENTER_VERTICAL\n                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))''',
    '''                orientation = LinearLayout.HORIZONTAL\n                gravity = Gravity.CENTER_VERTICAL\n                setPadding(dp(16), 0, dp(16), 0)\n                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))''',
    'group toolbar horizontal inset',
)
s = replace_once(
    s,
    '''                clipToPadding = false\n                setPadding(0, dp(8), dp(16), dp(18))\n                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {\n                    marginEnd = -dp(16)\n                }''',
    '''                clipToPadding = false\n                setPadding(dp(16), dp(8), dp(16), dp(18))\n                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)''',
    'group full-width recycler without negative margin',
)
group.write_text(s, encoding='utf-8')

scroller = root / 'app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt'
s = scroller.read_text(encoding='utf-8')
if 'V785_SCOPED_TOPMOST_FAST_SCROLL' not in s:
    s = s.replace(' * V784_UNCLIPPED_TOPMOST_FAST_SCROLL', ' * V784_UNCLIPPED_TOPMOST_FAST_SCROLL\n * V785_SCOPED_TOPMOST_FAST_SCROLL', 1)
scroller.write_text(s, encoding='utf-8')

print('v785 applied: only fast-scroll pill stays topmost; shelf/grid content is clipped below toolbars')
