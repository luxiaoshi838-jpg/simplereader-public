#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'{label} marker not found')
    return text.replace(old, new, 1)

# Version 784.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000783"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000784"')
s = s.replace('?: 2098000783', '?: 2098000784')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "783"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "784"')
gradle.write_text(s, encoding='utf-8')

# V783 deliberately lets the RecyclerView extend 16dp through the shelf root's right padding so
# the scrollbar can sit at the physical screen edge. A ViewGroup clips children to its padding by
# default, which clipped roughly half of the 28dp pill. Disable BOTH padding and child clipping on
# the shelf root; the ordinary grid padding remains unchanged, so this does not reclaim more layout
# width or move books into the system edge.
xml = root / 'app/src/main/res/layout/activity_main.xml'
s = xml.read_text(encoding='utf-8')
old = '''    android:background="#F2EFE6"\n    android:orientation="vertical"'''
new = '''    android:background="#F2EFE6"\n    android:clipChildren="false"\n    android:clipToPadding="false"\n    android:orientation="vertical"'''
s = replace_once(s, old, new, 'main root unclipped overlay')
xml.write_text(s, encoding='utf-8')

# Group shelf is built in Kotlin and had the same 16dp root padding + -16dp RecyclerView margin.
# Disable ancestor clipping there as well so the exact same pill is fully visible.
group = root / 'app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt'
s = group.read_text(encoding='utf-8')
old = '''            orientation = LinearLayout.VERTICAL\n            setBackgroundColor(ReaderAppearance.palette(this@GroupBooksActivity).backgroundColor)'''
new = '''            orientation = LinearLayout.VERTICAL\n            clipChildren = false\n            clipToPadding = false\n            setBackgroundColor(ReaderAppearance.palette(this@GroupBooksActivity).backgroundColor)'''
s = replace_once(s, old, new, 'group root unclipped overlay')
group.write_text(s, encoding='utf-8')

# Keep the handle in RecyclerView.onDrawOver(), which is above every book/group child. The v784
# fix is specifically ancestor unclipping: do not move the thumb into the grid or create a gutter.
scroller = root / 'app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt'
s = scroller.read_text(encoding='utf-8')
s = s.replace(' * V783_OVERLAY_FAST_SCROLL', ' * V783_OVERLAY_FAST_SCROLL\n * V784_UNCLIPPED_TOPMOST_FAST_SCROLL')
scroller.write_text(s, encoding='utf-8')

print('v784 topmost/unclipped fast-scroll patch applied')
