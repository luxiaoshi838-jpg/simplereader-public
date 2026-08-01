from pathlib import Path

reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = reader.read_text(encoding="utf-8-sig")
old = """        contentView = findViewById(R.id.contentView)
        contentView.setTextIsSelectable(false)
        readerScrollView = findViewById(R.id.readerScrollView)
"""
new = """        contentView = findViewById(R.id.contentView)
        contentView.setTextIsSelectable(false)
        contentView.isLongClickable = false
        contentView.isFocusable = false
        contentView.isFocusableInTouchMode = false
        readerScrollView = findViewById(R.id.readerScrollView)
"""
if "contentView.isLongClickable = false" not in text:
    if text.count(old) != 1:
        raise SystemExit("无法定位阅读正文选择设置")
    text = text.replace(old, new, 1)
for marker in [
    "contentView.setTextIsSelectable(false)",
    "contentView.isLongClickable = false",
    "contentView.isFocusable = false",
    "contentView.isFocusableInTouchMode = false",
]:
    if marker not in text:
        raise SystemExit(f"正文选词禁用标记缺失：{marker}")
reader.write_text(text, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_reader.xml")
xml = layout.read_text(encoding="utf-8-sig")
old_view = """        <TextView
            android:id="@+id/contentView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:lineSpacingMultiplier="1.75"
"""
new_view = """        <TextView
            android:id="@+id/contentView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:focusable="false"
            android:focusableInTouchMode="false"
            android:longClickable="false"
            android:textIsSelectable="false"
            android:lineSpacingMultiplier="1.75"
"""
if 'android:textIsSelectable="false"' not in xml:
    if xml.count(old_view) != 1:
        raise SystemExit("无法定位阅读正文 TextView")
    xml = xml.replace(old_view, new_view, 1)
for marker in [
    'android:textIsSelectable="false"',
    'android:longClickable="false"',
    'android:focusableInTouchMode="false"',
]:
    if marker not in xml:
        raise SystemExit(f"正文布局选词禁用标记缺失：{marker}")
layout.write_text(xml, encoding="utf-8")

print("v582 text selection disabled; long press remains reserved for reader menu activation")
