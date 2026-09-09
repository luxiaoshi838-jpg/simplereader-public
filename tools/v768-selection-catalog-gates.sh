#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 - <<'PY'
from pathlib import Path

B = Path('app/build.gradle.kts').read_text(encoding='utf-8')
T = Path('app/src/main/java/com/simplereader/app/parser/TxtParser.kt').read_text(encoding='utf-8')
D = Path('app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt').read_text(encoding='utf-8')
R = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
P = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt').read_text(encoding='utf-8')
V = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
S = Path('app/src/main/java/com/simplereader/app/ui/ReaderSelectionActions.kt').read_text(encoding='utf-8')

assert '2098000768' in B and '"768"' in B
assert 'CATALOG_RULE_VERSION = 115' in T
assert 'RULE_VERSION = 115' in D
assert '(?:章|节|回|卷|篇)' in D
assert 'recognizePrefixedStructuralIgnoringTrailingPunctuation' in D
assert 'recognizePrefixedChapterIgnoringTrailingPunctuation' not in D
# Structural-tail guards must remain, so ordinary words such as 第3节课 / 第12章鱼 stay rejected.
assert 'hasValidStructuralTail(candidate, markerEnd)' in D
assert 'hasValidStructuralTail(s, end)' in D

for label in ['翻译', '朗读选中', '笔记', '划线', '摘录', '分享']:
    assert f'"{label}"' in S, label
for token in [
    'Intent.ACTION_PROCESS_TEXT',
    'TextToSpeech',
    'UnderlineSpan()',
    'KIND_NOTE',
    'KIND_EXCERPT',
    'Intent.ACTION_SEND',
    'setCustomSelectionActionModeCallback',
    'reader_selection_annotations_v1',
]:
    assert token in S, token

for token in [
    'private lateinit var selectionActions: ReaderSelectionActions',
    'selectionActions = ReaderSelectionActions(this)',
    'pagedReaderView.onBindSelectionActions',
    'pagedReaderView.onDecorateSelectionText',
    'internal fun bindSelectionActions(view: TextView, sourceOffset: Int)',
    'internal fun verticalDecorateSelectionText(sourceOffset: Int, text: CharSequence)',
    'selectionActions.attach(continuousTextView, bookId, { continuousWindowStartOffset })',
    'selectionActions.release()',
]:
    assert token in R, token

for token in [
    'var onBindSelectionActions: ((TextView, Long) -> Unit)? = null',
    'var onDecorateSelectionText: ((Long, CharSequence) -> CharSequence)? = null',
    'private fun renderSnapshot(snapshot: ReaderPageSnapshot): CharSequence',
    'snapshot.copy(content = decorated)',
    'currentView.setCustomSelectionActionModeCallback(null)',
]:
    assert token in P, token

for token in [
    'activity.verticalDecorateSelectionText(pages[position].startOffset, text)',
    'activity.bindSelectionActions(view, pages[position].startOffset)',
    'private val rendered = LruCache<Int, CharSequence>(32)',
]:
    assert token in V, token

# Do not put memory/log writes back into hot scroll callbacks.
scroll_start = V.index('override fun onScrolled')
scroll_end = V.index('override fun onScrollStateChanged', scroll_start)
scroll = V[scroll_start:scroll_end]
for forbidden in ['CrashLogStore', 'recordReaderPosition', 'recordEvent', 'saveProgress']:
    assert forbidden not in scroll, forbidden

print('v768 selection actions + Rule115 catalog gates: PASS')
PY
