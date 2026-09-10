#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v781-cache-first-open.py

R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
C=app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt

# This is an inherited BEHAVIOUR gate. Do not pin the current release to version 781.
# Opening another already-cached book after a font change must show a cache immediately instead
# of waiting for a new whole-book layout hash to finish.
grep -Fq 'private suspend fun showCachedBookImmediately(): Int?' "$R"
grep -Fq 'val previewOffset = showCachedBookImmediately()' "$R"
grep -Fq 'paginateAndDisplay(previewOffset, null, backgroundOpen = previewOffset != null)' "$R"
grep -Fq 'PageCacheStore.loadPages(this@ReaderActivity, identity, loaded.text)' "$R"
grep -Fq 'PageCacheStore.loadCompatiblePages(this@ReaderActivity, identity, loaded.text)' "$R"
grep -Fq 'open_cache_preview:applied' "$R"

# Compatible fallback may ignore only settingsHash; it must still validate book/source/catalog.
grep -Fq 'fun loadCompatiblePages(context: Context, identity: CacheIdentity, text: String): ReaderBook?' "$C"
grep -Fq 'require(root.optLong("bookId") == identity.bookId)' "$C"
grep -Fq 'root.optInt("catalogRuleVersion", 0) != identity.catalogRuleVersion' "$C"
grep -Fq 'storedFingerprint != identity.textFingerprint' "$C"
grep -Fq 'val storedSettingsHash = root.optString("readerSettingsHash")' "$C"
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt').read_text(encoding='utf-8')
a=s.index('    fun loadCompatiblePages(')
b=s.index('    fun savePages(', a)
block=s[a:b]
assert 'identity.settingsHash)' in block, 'exact-layout manifest should be excluded from fallback scan'
assert 'storedSettingsHash' in block
assert 'ReaderBook(text, chapters, pages, storedSettingsHash)' in block
assert 'savePages(' not in block, 'compatible fallback must never be persisted as current-layout pages'
assert 'readerSettingsHash") != identity.settingsHash' not in block, 'fallback must be allowed to cross layout hash'
PY

# Background exact-layout refresh remains silent. Newer releases may also keep a direct first-page
# preview while the authoritative table is rebuilt, so the guard can include that state.
grep -Fq 'backgroundOpen: Boolean' "$R"
grep -Fq 'if (!backgroundOpen) paginationInProgress = true' "$R"
grep -Fq 'if (fontRequestId == null && !backgroundOpen) progressLabel.text = "分页中…"' "$R"
grep -Fq 'val liveOpenOffset = if (backgroundOpen)' "$R"
grep -Fq 'open_cache_refresh:failed_keep_preview' "$R"
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
# Historical public two-argument entry remains, so v780 font behaviour is not silently rewritten.
assert 'private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null)' in s
assert 'paginateAndDisplay(preserveOffset, fontRequestId, backgroundOpen = false)' in s
# A preview failure in the authoritative refresh must keep the already-visible book/page.
p=s.index('open_cache_refresh:failed_keep_preview')
window=s[p-750:p+500]
assert ('backgroundOpen && readerBook != null' in window or
        'backgroundOpen && (readerBook != null || zeroProgressOpeningPreviewVisible)' in window)
assert 'return@launch' in window
# Source offset remains truth while current typography and cached page-table hashes differ.
assert 'val transientLayout = layoutSettings?.stableHash()?.let { it != paged.settingsHash } == true' in s
assert 'if (transientLayout) return stable ?: current' in s
# The compatible-cache path itself still does not construct a local/partial ReaderBook.
a=s.index('    private suspend fun showCachedBookImmediately(): Int?')
b=s.index('    // Historical two-argument entry point', a)
preview=s[a:b]
cache_part=preview.split('    private suspend fun showZeroProgressFirstPageImmediately()', 1)[0]
assert 'ReaderBook(' not in cache_part
assert 'readerBook = cached' in cache_part
assert 'showActiveReader()' in cache_part
PY

# v780 cancellation/isolation controls remain mandatory.
grep -Fq 'private var paginationEpoch: Long = 0L' "$R"
grep -Fq 'fontRequestId != null && fontRequestId != fontChangeRequestId' "$R"
grep -Fq 'stale pagination result suppressed' "$R"
grep -Fq 'runningJob?.isActive != true' "$R"
grep -Fq '!ReaderRuntimeState.isOwner(generation)' "$R"
grep -Fq 'catch (cancelled: CancellationException)' "$R"

# Core full-page navigation stays authoritative; no return to the historical partial-window ReaderBook.
grep -Fq 'val pages = readerBook?.pages ?: return' "$R"
grep -Fq 'val target = (currentPageIndex + direction)' "$R" || true
grep -Fq 'verticalAdapter?.setPages(paged.pages)' "$R"

echo 'v781 cache-first cross-font open / silent refresh gates: PASS'
