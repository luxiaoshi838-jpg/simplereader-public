#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import subprocess

build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
parser = Path('app/src/main/java/com/simplereader/app/parser/TxtParser.kt').read_text(encoding='utf-8')
catalog = Path('app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
paged = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt').read_text(encoding='utf-8')
vertical = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
layout = Path('app/src/main/res/layout/activity_reader.xml').read_text(encoding='utf-8')

assert '2098000767' in build and '?: "767"' in build
assert 'CATALOG_RULE_VERSION = 114' in parser
assert 'RULE_VERSION = 114' in catalog
for token in [
    'private val prefixedChapter = Regex',
    'recognizePrefixedChapterIgnoringTrailingPunctuation(s)',
    'private fun recognizePrefixedChapterIgnoringTrailingPunctuation',
    'isUnicodePunctuation',
]:
    assert token in catalog, token

# Only 第N章 gets the trailing-punctuation exception. Other structural units keep Rule113 behavior.
assert 'prefixedChapter.findAll(candidate)' in catalog
assert 'prefixedStructural.findAll(s)' in catalog
assert 'STRUCTURE = "(?:单元|章|节|篇|部|卷|回|集)"' in catalog

# Volume-key control has no textual 开/关 state; enabled state is orange, disabled is existing gray.
assert 'android:text="音量键翻页"' in layout
assert '音量键翻页 开' not in layout and '音量键翻页 关' not in layout
assert 'text = "音量键翻页"' in reader
assert 'Color.rgb(239, 122, 40)' in reader
assert 'Color.rgb(74, 72, 66)' in reader

# Restore ONLY one text-selection toggle, backed by the native TextView selection mechanism.
assert layout.count('@+id/selectTextToggleButton') == 1
assert 'android:text="选中文本"' in layout
for token in [
    'private var textSelectionEnabled: Boolean = false',
    'PREF_TEXT_SELECTION = "text_selection_enabled"',
    'textSelectionEnabled = prefs.getBoolean(PREF_TEXT_SELECTION, false)',
    '.putBoolean(PREF_TEXT_SELECTION, textSelectionEnabled)',
    'R.id.selectTextToggleButton).setOnClickListener',
    'private fun applyTextSelectionSetting()',
    'continuousTextView.setTextIsSelectable(textSelectionEnabled)',
    'pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)',
    'verticalAdapter?.refresh()',
]:
    assert token in reader, token
assert 'fun setTextSelectionEnabled(enabled: Boolean)' in paged
assert 'currentView.setTextIsSelectable(enabled)' in paged
assert 'view.setTextIsSelectable(activity.isTextSelectionEnabled())' in vertical

# Do not revive unrelated historical reader controls/actions together with text selection.
for forbidden in [
    '翻译', '朗读选中', '加入笔记', '划线', '摘录', '分享选中', 'selectionToolbar',
    'textSelectionMenu', 'onSelectionAction', 'selectedTextAction'
]:
    assert forbidden not in (reader + paged + vertical + layout), f'unrelated legacy selection feature returned: {forbidden}'

# Search implementation must remain byte-for-byte identical to V766.
def block(text: str, start: str, end: str) -> str:
    a = text.index(start)
    b = text.index(end, a)
    return text[a:b]
base_reader = subprocess.check_output([
    'git', 'show', 'origin/source-v766:app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
], text=True)
cur_search = block(reader, '    private fun showContentSearch() {', '    private fun confirmDeleteBookmark(')
base_search = block(base_reader, '    private fun showContentSearch() {', '    private fun confirmDeleteBookmark(')
assert cur_search == base_search, 'reading-page search block changed from V766'

# V766 exit classification/session isolation must remain untouched.
for path in [
    'app/src/main/java/com/simplereader/app/App.kt',
    'app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt'
]:
    current = Path(path).read_text(encoding='utf-8')
    base = subprocess.check_output(['git', 'show', f'origin/source-v766:{path}'], text=True)
    assert current == base, f'V766 exit/session code changed: {path}'

# The V767 app-source diff is deliberately narrow; no hidden old feature bundle may return.
changed = subprocess.check_output([
    'git', 'diff', '--name-only', 'origin/source-v766', '--', 'app'
], text=True).splitlines()
allowed = {
    'app/build.gradle.kts',
    'app/src/main/java/com/simplereader/app/parser/TxtParser.kt',
    'app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt',
    'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt',
    'app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt',
    'app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt',
    'app/src/main/res/layout/activity_reader.xml',
    'app/src/test/java/com/simplereader/app/reader/DirectTxtCatalogV114ContractTest.kt',
}
unexpected = sorted(set(changed) - allowed)
assert not unexpected, f'unexpected V767 app changes: {unexpected}'

# Keep the vertical-scroll protections and bounded caches unchanged.
for token in [
    'LruCache<Int, CharSequence>(32)',
    'Layout.BREAK_STRATEGY_SIMPLE',
    'Layout.HYPHENATION_FREQUENCY_NONE',
    'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)',
    'vertical_idle_recovered_zero',
]:
    assert token in (reader + vertical), token

print('v767 catalog + volume-state + text-selection-only gates: PASS')
PY
