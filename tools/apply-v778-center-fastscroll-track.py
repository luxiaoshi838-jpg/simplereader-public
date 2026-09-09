#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

# Version bump.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000777"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000778"')
s = s.replace('?: 2098000777', '?: 2098000778')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "777"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "778"')
gradle.write_text(s, encoding='utf-8')

# Shared shelf/group scroller: center the track exactly on the thumb horizontal centerline.
scroller = root / 'app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt'
s = scroller.read_text(encoding='utf-8')
old = '''        val right = parent.width - edgeInset
        trackRect.set(
            right - trackWidth,
            parent.paddingTop.toFloat(),
            right,
            (parent.height - parent.paddingBottom).toFloat()
        )'''
new = '''        val right = parent.width - edgeInset
        val thumbCenterX = right - thumbWidth / 2f
        trackRect.set(
            thumbCenterX - trackWidth / 2f,
            parent.paddingTop.toFloat(),
            thumbCenterX + trackWidth / 2f,
            (parent.height - parent.paddingBottom).toFloat()
        )'''
if old in s:
    s = s.replace(old, new)
elif new not in s:
    raise SystemExit('fast-scroll track drawing marker not found')

required = [
    'val thumbCenterX = right - thumbWidth / 2f',
    'thumbCenterX - trackWidth / 2f',
    'thumbCenterX + trackWidth / 2f',
    'thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)',
]
missing = [marker for marker in required if marker not in s]
if missing:
    raise SystemExit('v778 center-track patch incomplete: ' + repr(missing))
scroller.write_text(s, encoding='utf-8')

print('v778 applied: fast-scroll track shares the exact horizontal centerline of the thumb')
