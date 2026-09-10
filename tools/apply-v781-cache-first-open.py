#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
reader_path = root / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
cache_path = root / "app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt"
gradle_path = root / "app/build.gradle.kts"

reader = reader_path.read_text(encoding="utf-8")
cache = cache_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")

# v781 is an inherited behaviour patch. Later versions are allowed to extend loadBook() and the
# background failure guard, so idempotency is determined by the runtime contract rather than a
# historical version literal or an exact two-line call-site shape.
markers = (
    "private suspend fun showCachedBookImmediately(): Int?",
    "PageCacheStore.loadCompatiblePages(this@ReaderActivity, identity, loaded.text)",
    "backgroundOpen: Boolean",
    "open_cache_preview:applied",
    "open_cache_refresh:failed_keep_preview",
    "val transientLayout = layoutSettings?.stableHash()?.let { it != paged.settingsHash } == true",
)
if (
    all(marker in reader for marker in markers)
    and "fun loadCompatiblePages(context: Context, identity: CacheIdentity, text: String): ReaderBook?" in cache
):
    print("v781 already applied: compatible-cache immediate open + silent authoritative refresh")
    raise SystemExit(0)

# Version bump for a genuine v780-style input tree only.
gradle = gradle.replace('SIMPLE_READER_VERSION_CODE") ?: "2098000780"', 'SIMPLE_READER_VERSION_CODE") ?: "2098000781"', 1)
gradle = gradle.replace('?: 2098000780', '?: 2098000781', 1)
gradle = gradle.replace('SIMPLE_READER_VERSION_NAME") ?: "780"', 'SIMPLE_READER_VERSION_NAME") ?: "781"', 1)

# Add a source-compatible complete-page fallback. It deliberately ignores only the layout hash;
# source fingerprint/catalog identity remain mandatory, and it never rewrites the fallback under
# the current settings hash.
anchor = '''    fun savePages(context: Context, identity: CacheIdentity, book: ReaderBook) {\n'''
if anchor not in cache:
    raise SystemExit("PageCacheStore savePages anchor missing")
compatible_loader = '''    /**
     * Loads a complete page table for the same source even when the reader layout hash differs.
     * This is a temporary navigation scaffold only: callers render it with current settings while
     * an exact page table is rebuilt in the background. Source fingerprint and catalog rule must
     * still match, so a changed/replaced book can never inherit stale offsets.
     */
    fun loadCompatiblePages(context: Context, identity: CacheIdentity, text: String): ReaderBook? {
        val directory = bookDir(context, identity.bookId)
        val exactFile = manifestFile(directory, identity.settingsHash)
        val candidates = pageManifestFiles(directory)
            .filter { it.isFile && it != exactFile }
            .sortedByDescending { it.lastModified() }
        for (file in candidates) {
            val value = runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val version = root.optInt("cacheVersion")
                require(version in MIN_COMPATIBLE_CACHE_VERSION..CACHE_VERSION)
                require(root.optLong("bookId") == identity.bookId)
                if (root.optInt("catalogRuleVersion", 0) != identity.catalogRuleVersion) return@runCatching null

                val storedFingerprint = root.optString("textFingerprint")
                if (storedFingerprint.isNotBlank()) {
                    if (storedFingerprint != identity.textFingerprint) return@runCatching null
                } else {
                    if (root.optString("filePath") != identity.filePath) return@runCatching null
                    if (root.optLong("fileSize") != identity.fileSize) return@runCatching null
                    if (root.optLong("lastModified") != identity.lastModified) return@runCatching null
                }

                val storedSettingsHash = root.optString("readerSettingsHash")
                if (storedSettingsHash.isBlank()) return@runCatching null
                val chapters = root.getJSONArray("chapters").toBookChapters(text.length)
                require(chapters.isNotEmpty())
                val rawPages = root.getJSONArray("pages")
                val total = rawPages.length()
                require(total > 0)
                val pages = (0 until total).map { index ->
                    val item = rawPages.getJSONObject(index)
                    val chapterIndex = item.getInt("chapterIndex")
                    require(chapterIndex in chapters.indices)
                    ReaderPage(
                        globalPageIndex = index,
                        totalPageCount = total,
                        chapterIndex = chapterIndex,
                        pageIndexInChapter = item.getInt("pageIndexInChapter"),
                        chapterPageCount = item.getInt("chapterPageCount"),
                        startOffset = item.getInt("startOffset").coerceIn(0, text.length),
                        endOffset = item.getInt("endOffset").coerceIn(0, text.length)
                    )
                }
                require(pages.all { it.startOffset <= it.endOffset })
                require(pages.zipWithNext().all { (a, b) -> a.endOffset <= b.endOffset })
                ReaderBook(text, chapters, pages, storedSettingsHash)
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

'''
cache = cache.replace(anchor, compatible_loader + anchor, 1)

old_load = '''                paginateAndDisplay(null)\n'''
new_load = '''                val previewOffset = showCachedBookImmediately()\n                paginateAndDisplay(previewOffset, null, backgroundOpen = previewOffset != null)\n'''
if old_load not in reader:
    raise SystemExit("ReaderActivity loadBook paginate anchor missing")
reader = reader.replace(old_load, new_load, 1)

paginate_anchor = '''    private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null) {\n'''
if paginate_anchor not in reader:
    raise SystemExit("ReaderActivity paginateAndDisplay signature anchor missing")
helper_and_overload = '''    /**
     * Opens an already-cached book immediately even when the cache was produced with another
     * font size/layout. The complete old page sequence remains the temporary navigation truth;
     * only typography comes from current settings. No partial ReaderBook is created.
     */
    private suspend fun showCachedBookImmediately(): Int? {
        val selectedBook = book ?: return null
        val loaded = document ?: return null
        while (pagedReaderView.width <= 0 || pagedReaderView.height <= 0) {
            if (isFinishing || isDestroyed || !ownsReaderSession()) return null
            delay(16L)
        }
        val settings = createLayoutSettings()
        val identity = PageCacheStore.CacheIdentity(
            selectedBook.id,
            selectedBook.filePath,
            loaded.sourceSize,
            loaded.sourceModified,
            settings.stableHash(),
            PageCacheStore.textFingerprint(loaded.text),
            TxtParser.CATALOG_RULE_VERSION
        )
        val cached = withContext(Dispatchers.IO) {
            PageCacheStore.loadPages(this@ReaderActivity, identity, loaded.text)
                ?: PageCacheStore.loadCompatiblePages(this@ReaderActivity, identity, loaded.text)
        } ?: return null
        if (selectedBook.id != bookId || !ownsReaderSession() || isFinishing || isDestroyed) return null

        val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
        val restoreOffset = (
            lastStableSourceOffset
                ?: CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)
                ?: progress?.startOffset
                ?: progress?.txtCharOffset
                ?: progress?.position?.toIntOrNull()
                ?: 0
        ).coerceIn(0, cached.text.length)

        readerBook = cached
        layoutSettings = settings
        currentPageIndex = cached.pageForOffset(restoreOffset).globalPageIndex
        lastStableSourceOffset = restoreOffset
        showActiveReader()
        lastStableSourceOffset = restoreOffset
        CrashLogStore.recordEvent(
            this,
            "open_cache_preview:applied book=$bookId exact=${cached.settingsHash == settings.stableHash()} anchor=$restoreOffset page=$currentPageIndex pages=${cached.pages.size}"
        )
        return restoreOffset
    }

    // Historical two-argument entry point stays intact for font-change and other callers.
    private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null) {
        paginateAndDisplay(preserveOffset, fontRequestId, backgroundOpen = false)
    }

    private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long?, backgroundOpen: Boolean) {
'''
reader = reader.replace(paginate_anchor, helper_and_overload, 1)

reader = reader.replace(
    'pagedReaderView.post { paginateAndDisplay(preserveOffset, fontRequestId) }',
    'pagedReaderView.post { paginateAndDisplay(preserveOffset, fontRequestId, backgroundOpen) }',
    1
)
reader = reader.replace(
    '''        paginationInProgress = true\n        if (fontRequestId == null) progressLabel.text = "分页中…"\n''',
    '''        if (!backgroundOpen) paginationInProgress = true\n        if (fontRequestId == null && !backgroundOpen) progressLabel.text = "分页中…"\n''',
    1
)

old_stable = '''                val liveFontOffset = if (fontRequestId != null) currentVisibleSourceOffset() else null
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val stableOffset = preserveOffset
                    ?.takeIf { fontRequestId == null }
                    ?: liveFontOffset
                    ?: lastStableSourceOffset
                    ?: CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)
                    ?: progress?.startOffset
                    ?: progress?.txtCharOffset
                    ?: progress?.position?.toIntOrNull()
'''
new_stable = '''                val liveFontOffset = if (fontRequestId != null) currentVisibleSourceOffset() else null
                val liveOpenOffset = if (backgroundOpen) {
                    lastStableSourceOffset?.coerceIn(0, loaded.text.length) ?: currentVisibleSourceOffset()
                } else null
                val progress = withContext(Dispatchers.IO) { database.readProgressDao().getProgress(bookId) }
                val stableOffset = preserveOffset
                    ?.takeIf { fontRequestId == null && !backgroundOpen }
                    ?: liveFontOffset
                    ?: liveOpenOffset
                    ?: preserveOffset
                    ?: lastStableSourceOffset
                    ?: CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)
                    ?: progress?.startOffset
                    ?: progress?.txtCharOffset
                    ?: progress?.position?.toIntOrNull()
'''
if old_stable not in reader:
    raise SystemExit("ReaderActivity stable-offset block missing")
reader = reader.replace(old_stable, new_stable, 1)

catch_anchor = '''                if (stale || isFinishing || isDestroyed) return@launch
                val rollback = pendingFontRollback
'''
catch_replacement = '''                if (stale || isFinishing || isDestroyed) return@launch
                if (backgroundOpen && readerBook != null) {
                    CrashLogStore.recordEvent(
                        this@ReaderActivity,
                        "open_cache_refresh:failed_keep_preview book=$bookId epoch=$epoch type=${error.javaClass.name} message=${error.message.orEmpty()}"
                    )
                    return@launch
                }
                val rollback = pendingFontRollback
'''
if catch_anchor not in reader:
    raise SystemExit("ReaderActivity pagination failure anchor missing")
reader = reader.replace(catch_anchor, catch_replacement, 1)

progress_anchor = '''        val current = paged.pages.getOrNull(currentPageIndex)?.startOffset
        val stable = lastStableSourceOffset?.coerceIn(0, paged.text.length)
        if (pageTurnMode != TURN_MODE_VERTICAL) return current ?: stable
'''
progress_replacement = '''        val current = paged.pages.getOrNull(currentPageIndex)?.startOffset
        val stable = lastStableSourceOffset?.coerceIn(0, paged.text.length)
        val transientLayout = layoutSettings?.stableHash()?.let { it != paged.settingsHash } == true
        if (transientLayout) return stable ?: current
        if (pageTurnMode != TURN_MODE_VERTICAL) return current ?: stable
'''
if progress_anchor not in reader:
    raise SystemExit("ReaderActivity stable progress anchor missing")
reader = reader.replace(progress_anchor, progress_replacement, 1)

reader_path.write_text(reader, encoding="utf-8")
cache_path.write_text(cache, encoding="utf-8")
gradle_path.write_text(gradle, encoding="utf-8")
print("v781 applied: cache-first cross-font opening with silent guarded exact-layout refresh")
