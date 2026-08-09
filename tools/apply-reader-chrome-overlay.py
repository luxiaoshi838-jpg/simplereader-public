from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
reader = root / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
text = reader.read_text(encoding="utf-8")

# System ActionBar is permanently forbidden for reader chrome because show/hide changes content layout.
text = text.replace("import android.view.Menu\n", "")
text = text.replace("import android.view.MenuItem\n", "")
text = re.sub(r"^.*supportActionBar.*\n", "", text, flags=re.M)

text = text.replace(
    "    private lateinit var readerControls: LinearLayout\n    private lateinit var readerSettingsPanel: LinearLayout\n",
    "    private lateinit var readerTopBar: LinearLayout\n    private lateinit var readerTopTitle: TextView\n    private lateinit var readerControls: LinearLayout\n    private lateinit var readerSettingsPanel: LinearLayout\n",
    1,
)
text = text.replace(
    "        progressLabel = findViewById(R.id.readerProgressLabel)\n        readerControls = findViewById(R.id.readerControls)\n",
    "        progressLabel = findViewById(R.id.readerProgressLabel)\n        readerTopBar = findViewById(R.id.readerTopBar)\n        readerTopTitle = findViewById(R.id.readerTopTitle)\n        readerControls = findViewById(R.id.readerControls)\n",
    1,
)

text = re.sub(
    r"\n    override fun onCreateOptionsMenu\(menu: Menu\): Boolean \{.*?\n    override fun onKeyDown",
    "\n    override fun onKeyDown",
    text,
    flags=re.S,
    count=1,
)

marker = "    private fun bindControls() {\n"
if "readerTopSearchButton" not in text:
    if marker not in text:
        raise SystemExit("bindControls marker missing")
    text = text.replace(
        marker,
        marker
        + "        findViewById<TextView>(R.id.readerTopSearchButton).setOnClickListener { showContentSearch() }\n"
        + "        findViewById<TextView>(R.id.readerTopBookmarkButton).setOnClickListener { addBookmark() }\n",
        1,
    )

text = text.replace(
    "        title = chapterTitle\n",
    "        title = \"\"\n        readerTopTitle.text = chapterTitle\n",
    1,
)

chrome = re.search(
    r"    private fun setReaderChromeVisible\(visible: Boolean\) \{.*?\n    private fun loadPreferences\(\)",
    text,
    re.S,
)
if not chrome:
    raise SystemExit("setReaderChromeVisible block missing")
new_chrome = """    private fun setReaderChromeVisible(visible: Boolean) {
        chromeVisible = visible
        readerTopBar.visibility = if (visible) View.VISIBLE else View.GONE
        readerControls.visibility = if (visible) View.VISIBLE else View.GONE
        progressLabel.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) readerSettingsPanel.visibility = View.GONE
        if (visible) updateCurrentChapterTitle()
    }

    private fun loadPreferences()"""
text = text[: chrome.start()] + new_chrome + text[chrome.end() :]

insets = re.search(
    r"    private fun bindReaderInsets\(\) \{.*?\n    private fun applyReaderContentPadding\(\)",
    text,
    re.S,
)
if not insets:
    raise SystemExit("bindReaderInsets block missing")
new_insets = """    private fun bindReaderInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(readerRoot) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (statusTop != statusBarInsetPx || navigationBottom != navigationBarInsetPx) {
                statusBarInsetPx = statusTop
                navigationBarInsetPx = navigationBottom
                applyReaderContentPadding()
                applyReaderChromeInsets()
            }
            insets
        }
        ViewCompat.requestApplyInsets(readerRoot)
    }

    /** Overlay-only chrome: margins move only the bars, never the reader content. */
    private fun applyReaderChromeInsets() {
        (readerTopBar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.topMargin != statusBarInsetPx) {
                params.topMargin = statusBarInsetPx
                readerTopBar.layoutParams = params
            }
        }
        (readerControls.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.bottomMargin != navigationBarInsetPx) {
                params.bottomMargin = navigationBarInsetPx
                readerControls.layoutParams = params
            }
        }
    }

    private fun applyReaderContentPadding()"""
text = text[: insets.start()] + new_insets + text[insets.end() :]

if "supportActionBar" in text:
    raise SystemExit("supportActionBar reference remains")
if "paginateAndDisplay(stableOffset)" in text:
    raise SystemExit("inset repagination path remains")
reader.write_text(text, encoding="utf-8")

layout = root / "app/src/main/res/layout/activity_reader.xml"
xml = layout.read_text(encoding="utf-8")
if '@+id/readerTopBar' not in xml:
    anchor = '    <TextView\n        android:id="@+id/readerProgressLabel"'
    topbar = '''    <LinearLayout
        android:id="@+id/readerTopBar"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:layout_gravity="top"
        android:background="#302F2A"
        android:clickable="true"
        android:elevation="10dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="16dp"
        android:paddingEnd="10dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/readerTopTitle"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:ellipsize="end"
            android:gravity="center_vertical"
            android:maxLines="1"
            android:textColor="#EEE9DD"
            android:textSize="17sp" />

        <TextView
            android:id="@+id/readerTopSearchButton"
            android:layout_width="48dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="⌕"
            android:textColor="#EEE9DD"
            android:textSize="26sp" />

        <TextView
            android:id="@+id/readerTopBookmarkButton"
            android:layout_width="48dp"
            android:layout_height="40dp"
            android:gravity="center"
            android:text="添"
            android:textColor="#FFFFFF"
            android:textSize="16sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/readerProgressLabel"'''
    if anchor not in xml:
        raise SystemExit("progress label anchor missing")
    xml = xml.replace(anchor, topbar, 1)
layout.write_text(xml, encoding="utf-8")

styles = root / "app/src/main/res/values/styles.xml"
s = styles.read_text(encoding="utf-8")
if "Theme.SimpleReader.Reader" not in s:
    s = s.replace(
        "</resources>",
        '    <style name="Theme.SimpleReader.Reader" parent="Theme.SimpleReader">\n'
        '        <item name="windowActionBar">false</item>\n'
        '        <item name="windowNoTitle">true</item>\n'
        '    </style>\n</resources>',
    )
styles.write_text(s, encoding="utf-8")

manifest = root / "app/src/main/AndroidManifest.xml"
m = manifest.read_text(encoding="utf-8")
m = m.replace(
    '<activity\n            android:name=".ui.ReaderActivity"\n            android:exported="false" />',
    '<activity\n            android:name=".ui.ReaderActivity"\n            android:exported="false"\n            android:theme="@style/Theme.SimpleReader.Reader" />',
    1,
)
if 'android:theme="@style/Theme.SimpleReader.Reader"' not in m:
    raise SystemExit("ReaderActivity theme patch failed")
manifest.write_text(m, encoding="utf-8")

baseline = root / "UI_BASELINE.md"
b = baseline.read_text(encoding="utf-8")
if "阅读上下栏覆盖规则（永久）" not in b:
    b += """
- 阅读上下栏覆盖规则（永久）：上栏、下栏都必须是 `readerRoot`/`FrameLayout` 内的覆盖层，显示或隐藏时禁止改变 `readerRoot`、`readerScrollView`、`pagedReaderView` 的高度、padding、margin 或布局测量结果。
- 阅读上下栏禁止恢复（永久）：`ReaderActivity` 禁止使用 `supportActionBar.show()/hide()` 作为阅读上栏；禁止因阅读上下栏显示/隐藏或其 inset 变化调用 `paginateAndDisplay()`、`renderContinuousWindow()`、`scrollTo()`、`jumpToPage()` 做位置补偿。
- 系统栏 inset 只允许用于正文固定安全边距，以及覆盖式上栏/下栏自身的 topMargin/bottomMargin；不得通过 inset 改变阅读正文容器尺寸。
"""
baseline.write_text(b, encoding="utf-8")

verify = root / "tools/verify-reader-chrome-overlay.sh"
verify.write_text(
    '''#!/usr/bin/env bash
set -euo pipefail
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
L=app/src/main/res/layout/activity_reader.xml
M=app/src/main/AndroidManifest.xml
B=UI_BASELINE.md
! grep -q "supportActionBar" "$R"
! grep -q "paginateAndDisplay(stableOffset)" "$R"
grep -q 'android:id="@+id/readerTopBar"' "$L"
grep -q 'android:layout_gravity="top"' "$L"
grep -q 'android:id="@+id/readerControls"' "$L"
grep -q 'android:layout_gravity="bottom"' "$L"
grep -q 'android:theme="@style/Theme.SimpleReader.Reader"' "$M"
grep -q '阅读上下栏覆盖规则（永久）' "$B"
echo "reader chrome overlay policy: PASS"
''',
    encoding="utf-8",
)
