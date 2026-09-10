#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v780-live-font-preview-idempotent.py

R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
E=app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt
G=app/build.gradle.kts
S=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
X=app/src/main/res/layout/activity_main.xml
Q=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt

# Version.
grep -Fq '2098000780' "$G"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "780"' "$G"

# Font-size feedback must be immediate and display-only on the already complete ReaderBook.
grep -Fq 'private fun applyTransientFontPreview(anchorOffset: Int, requestId: Long)' "$R"
grep -Fq 'applyTransientFontPreview(currentOffset, requestId)' "$R"
grep -Fq 'paginateAndDisplay(currentOffset, requestId)' "$R"
! grep -Fq 'progressLabel.text = "字体分页中…"' "$R"
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
a=s.index('    private fun applyTransientFontPreview(anchorOffset: Int, requestId: Long) {')
b=s.index('    private fun setTurnMode(', a)
block=s[a:b]
assert 'readerBook = ' not in block, 'transient preview must never replace authoritative ReaderBook'
assert 'verticalAdapter?.refresh()' in block
assert 'configurePagedReaderStyle()' in block
assert 'bindHorizontalPages()' in block
assert 'currentPageIndex = paged.pageForOffset(safeOffset).globalPageIndex' in block
assert 'lastStableSourceOffset = safeOffset' in block

f0=s.index('    private fun changeTextSize(delta: Float) {')
f1=s.index('    private fun applyTransientFontPreview(', f0)
f=s[f0:f1]
assert f.index('applyTransientFontPreview(currentOffset, requestId)') < f.index('mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS)')
assert 'paginationEpoch += 1L' in f
assert f.index('paginationEpoch += 1L') < f.index('paginationJob?.cancel()') < f.index('readerTextSizeSp = requested')
assert 'if (pendingFontRollback == null)' in f
PY

# Background authoritative pagination is isolated by epoch, reader generation, book id and font request.
grep -Fq 'private var paginationEpoch: Long = 0L' "$R"
grep -Fq 'private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null)' "$R"
grep -Fq 'val epoch = paginationEpoch' "$R"
grep -Fq 'val generation = readerGeneration' "$R"
grep -Fq 'val expectedBookId = selectedBook.id' "$R"
grep -Fq 'fontRequestId != null && fontRequestId != fontChangeRequestId' "$R"
grep -Fq 'stale pagination result suppressed' "$R"
grep -Fq 'val liveFontOffset = if (fontRequestId != null) currentVisibleSourceOffset() else null' "$R"
grep -Fq 'if (epoch == paginationEpoch) paginationInProgress = false' "$R"

# Old work must cooperatively stop instead of burning CPU until a whole large novel is paginated.
grep -Fq 'shouldCancel: (() -> Boolean)? = null' "$E"
grep -Fq 'throwIfPaginationCancelled(shouldCancel)' "$E"
grep -Fq 'java.util.concurrent.CancellationException("PageEngine pagination cancelled")' "$E"
grep -Fq 'runningJob?.isActive != true' "$R"
grep -Fq '!ReaderRuntimeState.isOwner(generation)' "$R"

# Preserve v779 cancellation-is-control-flow behavior; it must stay ahead of Throwable failure UI.
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
assert s.count('catch (cancelled: CancellationException)') >= 2
for marker in ('loadBook:cancelled', 'paginate:cancelled'):
    p=s.index(marker)
    start=s.rfind('catch (cancelled: CancellationException)', 0, p)
    end=s.index('catch (error: Throwable)', p)
    block=s[start:end]
    assert 'throw cancelled' in block
    assert 'showFatal(' not in block
    assert 'showContinuousFallback(' not in block
PY

# The old full page table remains the only navigation truth while preview is active.
grep -Fq 'val pages = readerBook?.pages.orEmpty()' "$R"
grep -Fq 'val target = (currentPageIndex + direction)' "$R"
grep -Fq 'verticalAdapter?.setPages(paged.pages)' "$R"

# Preserve v778/v777 shelf/group scrollbar geometry exactly.
grep -Fq 'val thumbCenterX = right - thumbWidth / 2f' "$S"
grep -Fq 'thumbCenterX - trackWidth / 2f' "$S"
grep -Fq 'thumbCenterX + trackWidth / 2f' "$S"
grep -Fq 'thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)' "$S"
grep -Fq 'android:paddingEnd="28dp"' "$X"
grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$Q"

echo 'v780 live font preview / guarded background repagination gates: PASS'
