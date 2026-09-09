#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

main = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
s = main.read_text(encoding='utf-8')
anchor = '        shelfGrid.setItemViewCacheSize(12)\n'
insert = anchor + '        ShelfFastScroller.attach(shelfGrid)\n'
if 'ShelfFastScroller.attach(shelfGrid)' not in s:
    if anchor not in s:
        raise SystemExit('MainActivity shelfGrid anchor not found')
    s = s.replace(anchor, insert, 1)
main.write_text(s, encoding='utf-8')

group = root / 'app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt'
s = group.read_text(encoding='utf-8')
s = s.replace('            addView(FastScrollRecyclerView(this@GroupBooksActivity).apply {',
              '            addView(RecyclerView(this@GroupBooksActivity).apply {', 1)
anchor = '                adapter = this@GroupBooksActivity.adapter\n'
insert = anchor + '                ShelfFastScroller.attach(this)\n'
if 'ShelfFastScroller.attach(this)' not in s:
    if anchor not in s:
        raise SystemExit('GroupBooksActivity RecyclerView adapter anchor not found')
    s = s.replace(anchor, insert, 1)
group.write_text(s, encoding='utf-8')

xml = root / 'app/src/main/res/layout/activity_main.xml'
s = xml.read_text(encoding='utf-8')
s = s.replace('<com.simplereader.app.ui.FastScrollRecyclerView', '<androidx.recyclerview.widget.RecyclerView', 1)
xml.write_text(s, encoding='utf-8')

print('v773 startup-safe fast scroll patch applied')
