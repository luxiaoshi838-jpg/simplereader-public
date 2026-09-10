#!/usr/bin/env bash
set -euo pipefail

reader="app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
engine="app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt"
workflow=".github/workflows/android-release-v2.yml"

for file in "$reader" "$engine" "$workflow"; do
  test -f "$file"
done

grep -Fq 'private var zeroProgressOpeningPreviewVisible = false' "$reader"
grep -Fq 'private suspend fun showZeroProgressFirstPageImmediately(): Int?' "$reader"
grep -Fq 'showCachedBookImmediately()' "$reader"
grep -Fq '?: showZeroProgressFirstPageImmediately()' "$reader"
grep -Fq 'PageEngine.layoutFirstPage(' "$reader"
grep -Fq 'open_zero_progress_preview:applied' "$reader"
grep -Fq 'progressLabel.text = "1/…"' "$reader"
grep -Fq 'backgroundOpen && (readerBook != null || zeroProgressOpeningPreviewVisible)' "$reader"
grep -Fq 'zeroProgressOpeningPreviewVisible = false' "$reader"

grep -Fq 'data class FirstPageLayout(' "$engine"
grep -Fq 'fun layoutFirstPage(' "$engine"
grep -Fq 'private const val FIRST_PAGE_PREVIEW_CHARS = 16_384' "$engine"
grep -Fq '.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)' "$engine"
grep -Fq '.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)' "$engine"

# The zero-progress preview must stay display-only. It must not create a partial ReaderBook,
# local pages collection, or write a fake global page sequence.
python3 - <<'PY'
from pathlib import Path
s = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
start = s.index('    private suspend fun showZeroProgressFirstPageImmediately(): Int?')
end = s.index('    // Historical two-argument entry point', start)
body = s[start:end]
for forbidden in ('ReaderBook(', 'readerBook = ReaderBook', 'mutableListOf<ReaderPage>', 'listOf(ReaderPage'):
    if forbidden in body:
        raise SystemExit(f'v782 gate: zero-progress preview illegally creates page truth: {forbidden}')
if 'progressLabel.text = "分页中…"' in body:
    raise SystemExit('v782 gate: zero-progress preview must never show blocking pagination label')

engine = Path('app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt').read_text(encoding='utf-8')
start = engine.index('    fun layoutFirstPage(')
end = engine.index('    fun paginate(', start)
preview = engine[start:end]
if 'ReaderBook(' in preview or 'paginateChapter(' in preview:
    raise SystemExit('v782 gate: first-page primitive must not build a whole/partial ReaderBook')
PY

# Inherited behavior gate: later releases must retain the v782 contract without being pinned
# to versionName/versionCode or the historical branch/workflow title.
grep -Fq 'v782-zero-progress-first-page-gates.sh' "$workflow"
grep -Fq 'PageEngineFirstPagePreviewTest' "$workflow"

echo 'V782_ZERO_PROGRESS_FIRST_PAGE_GATES_PASS'
