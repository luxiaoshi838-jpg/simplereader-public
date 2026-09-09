#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v779-font-repagination-fix.py

R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
G=app/build.gradle.kts
S=app/src/main/java/com/simplereader/app/ui/ShelfFastScroller.kt
X=app/src/main/res/layout/activity_main.xml
Q=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt

# Version.
grep -Fq '2098000779' "$G"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "779"' "$G"

# Restore and strengthen the historical v754 font contract.
grep -Fq 'FONT_CHANGE_DEBOUNCE_MS = 320L' "$R"
grep -Fq 'currentVisibleSourceOffset()' "$R"
grep -Fq 'if (pendingFontRollback == null)' "$R"
grep -Fq 'val requestId = fontChangeRequestId' "$R"
grep -Fq 'requestId != fontChangeRequestId' "$R"
grep -Fq 'paginateAndDisplay(currentOffset)' "$R"
grep -Fq 'lastStableSourceOffset = rollback.sourceOffset' "$R"

# A new font request cancels any old in-flight whole-book pagination before starting the final one.
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
a=s.index('    private fun changeTextSize(delta: Float) {')
b=s.index('    private fun setTurnMode(', a)
f=s[a:b]
assert 'fontChangeRunnable?.let(mainHandler::removeCallbacks)' in f
assert 'if (pendingFontRollback == null)' in f
assert 'paginationJob?.cancel()' in f
assert f.index('paginationJob?.cancel()') < f.index('readerTextSizeSp = requested')
assert 'mainHandler.postDelayed(it, FONT_CHANGE_DEBOUNCE_MS)' in f
assert 'paginateAndDisplay(currentOffset)' in f
PY

# CancellationException must be handled before Throwable and may never show failure/fatal UI.
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
assert 'import kotlinx.coroutines.CancellationException' in s
assert s.count('catch (cancelled: CancellationException)') >= 2
for marker in ('loadBook:cancelled', 'paginate:cancelled'):
    p=s.index(marker)
    start=s.rfind('catch (cancelled: CancellationException)', 0, p)
    end=s.index('catch (error: Throwable)', p)
    block=s[start:end]
    assert 'throw cancelled' in block
    assert 'showFatal(' not in block
    assert 'showContinuousFallback(' not in block
    assert 'Toast.makeText' not in block
assert 'showFatal:suppressed' in s
assert 'if (isFinishing || isDestroyed || !ownsReaderSession()) return' in s
PY

# Preserve v778/v777 shelf and group fast-scroll geometry exactly; reader fix must not touch it.
grep -Fq 'val thumbCenterX = right - thumbWidth / 2f' "$S"
grep -Fq 'thumbCenterX - trackWidth / 2f' "$S"
grep -Fq 'thumbCenterX + trackWidth / 2f' "$S"
grep -Fq 'thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)' "$S"
grep -Fq 'android:paddingEnd="28dp"' "$X"
grep -Fq 'setPadding(0, dp(8), dp(28), dp(18))' "$Q"

echo 'v779 font repagination regression gates: PASS'
