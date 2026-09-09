#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

# Version bump.
gradle = root / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000776"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000777"')
s = s.replace('?: 2098000776', '?: 2098000777')
s = s.replace('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "776"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "777"')
gradle.write_text(s, encoding='utf-8')

# Main shelf: reserve a real right-side gutter for the fast-scroll thumb.
main_xml = root / 'app/src/main/res/layout/activity_main.xml'
s = main_xml.read_text(encoding='utf-8')
old = 'android:paddingEnd="14dp"\n        android:scrollbars="none"'
new = 'android:paddingEnd="28dp"\n        android:scrollbars="none"'
if old not in s and new not in s:
    raise SystemExit('main shelf RecyclerView padding marker not found')
s = s.replace(old, new)
main_xml.write_text(s, encoding='utf-8')

# Group shelf: match the exact same right-side gutter.
group = root / 'app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt'
s = group.read_text(encoding='utf-8')
old = 'setPadding(0, dp(8), 0, dp(18))'
new = 'setPadding(0, dp(8), dp(28), dp(18))'
if old not in s and new not in s:
    raise SystemExit('group shelf RecyclerView padding marker not found')
s = s.replace(old, new)
group.write_text(s, encoding='utf-8')

# Shared shelf/group scroller: draw in the reserved gutter, not at the content edge.
scroller = root / 'app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt'
s = scroller.read_text(encoding='utf-8')
s = s.replace('private val edgeInset = 3f * density', 'private val edgeInset = 4f * density')
s = s.replace(
'''private val thumbTouchPadding = max(\n        8f * density,\n        ViewConfiguration.get(recyclerView.context).scaledTouchSlop.toFloat()\n    )''',
'''private val thumbHorizontalTouchPadding = 4f * density\n    private val thumbVerticalTouchPadding = max(\n        8f * density,\n        ViewConfiguration.get(recyclerView.context).scaledTouchSlop.toFloat()\n    )'''
)
s = s.replace(
'val right = parent.width - parent.paddingRight - edgeInset',
'val right = parent.width - edgeInset'
)
s = s.replace(
'''val hitRect = RectF(\n            thumbRect.left - thumbTouchPadding,\n            thumbRect.top - thumbTouchPadding,\n            thumbRect.right + thumbTouchPadding,\n            thumbRect.bottom + thumbTouchPadding\n        )''',
'''val contentRight = (recyclerView.width - recyclerView.paddingRight).toFloat()\n        val hitRect = RectF(\n            max(contentRight, thumbRect.left - thumbHorizontalTouchPadding),\n            thumbRect.top - thumbVerticalTouchPadding,\n            (thumbRect.right + thumbHorizontalTouchPadding).coerceAtMost(recyclerView.width.toFloat()),\n            thumbRect.bottom + thumbVerticalTouchPadding\n        )'''
)
s = s.replace(
'val right = recyclerView.width - recyclerView.paddingRight - edgeInset',
'val right = recyclerView.width - edgeInset'
)
required = [
    'private val thumbHorizontalTouchPadding = 4f * density',
    'val right = parent.width - edgeInset',
    'val contentRight = (recyclerView.width - recyclerView.paddingRight).toFloat()',
    'max(contentRight, thumbRect.left - thumbHorizontalTouchPadding)',
    'val right = recyclerView.width - edgeInset',
]
missing = [marker for marker in required if marker not in s]
if missing:
    raise SystemExit('fast-scroll gutter patch incomplete: ' + repr(missing))
scroller.write_text(s, encoding='utf-8')

print('v777 applied: shelf/group share 28dp fast-scroll gutter; thumb hit area cannot extend into content')
