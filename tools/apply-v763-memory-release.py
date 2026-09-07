from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# Version
p = Path('app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = replace_once(s, '"2098000762"', '"2098000763"', 'version code env default')
s = replace_once(s, '?: 2098000762', '?: 2098000763', 'version code fallback')
s = replace_once(s, '?: "762"', '?: "763"', 'version name fallback')
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# MainActivity: release the entire non-virtualized shelf before ReaderActivity
# starts, gate background UI rebuilds while stopped, and sample EPUB covers.
# -----------------------------------------------------------------------------
p = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '    private var pendingBackup: SimpleReaderBackupDecoder.DecodedBackup? = null\n',
    '    private var pendingBackup: SimpleReaderBackupDecoder.DecodedBackup? = null\n    private var shelfUiVisible = false\n',
    'shelf visibility flag'
)
s = replace_once(
    s,
'''    override fun onResume() {\n        super.onResume()\n        applyShelfAppearance()\n        updateUI()\n    }\n''',
'''    override fun onResume() {\n        super.onResume()\n        shelfUiVisible = true\n        applyShelfAppearance()\n        updateUI()\n    }\n\n    override fun onStop() {\n        shelfUiVisible = false\n        releaseShelfUiMemory("main_onStop")\n        super.onStop()\n    }\n''',
    'MainActivity lifecycle release'
)
s = replace_once(
    s,
'''    private fun updateUI() {\n        applyShelfAppearance()\n''',
'''    private fun updateUI() {\n        if (!shelfUiVisible) return\n        applyShelfAppearance()\n''',
    'gate stopped shelf rebuilds'
)
s = replace_once(
    s,
'''    private fun openBook(bookId: Long) {\n        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bookId))\n    }\n''',
'''    private fun openBook(bookId: Long) {\n        // V763: MainActivity uses a non-virtualized GridLayout. Release every card/ImageView and\n        // decoded cover before constructing ReaderActivity so the two full UI trees never overlap.\n        shelfUiVisible = false\n        releaseShelfUiMemory("before_open_reader")\n        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bookId))\n    }\n\n    private fun releaseShelfUiMemory(reason: String) {\n        if (::shelfGrid.isInitialized) shelfGrid.removeAllViews()\n        coverBitmapCache.evictAll()\n        BookCoverAssets.clearMemoryCache()\n        CrashLogStore.recordMemorySnapshot(\n            this,\n            "shelf_release_$reason",\n            "books=${books.size} groups=${groups.size} visible=$shelfUiVisible"\n        )\n    }\n''',
    'release shelf before reader'
)
s = replace_once(
    s,
'''                        StructuredBookCache.coverFile(this@MainActivity, book.id)\n                            ?.takeIf { it.isFile }\n                            ?.let { BitmapFactory.decodeFile(it.absolutePath) }\n                            ?: contentResolver.openInputStream(Uri.parse(book.filePath))?.use { input ->\n                                EpubParser.readCoverImage(input)?.let { bytes ->\n                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)\n                                }\n                            }\n''',
'''                        StructuredBookCache.coverFile(this@MainActivity, book.id)\n                            ?.takeIf { it.isFile }\n                            ?.let(::decodeShelfCoverFile)\n                            ?: contentResolver.openInputStream(Uri.parse(book.filePath))?.use { input ->\n                                EpubParser.readCoverImage(input)?.let(::decodeShelfCoverBytes)\n                            }\n''',
    'sample shelf cover decode'
)
s = replace_once(
    s,
'''                if (bitmap != null) {\n                    coverBitmapCache.put(book.id, bitmap)\n                    showBitmap(bitmap)\n                }\n''',
'''                if (bitmap != null && shelfUiVisible && !isFinishing && !isDestroyed) {\n                    coverBitmapCache.put(book.id, bitmap)\n                    showBitmap(bitmap)\n                }\n''',
    'do not repopulate shelf cache while hidden'
)
s = replace_once(
    s,
'''        return frame\n    }\n\n    private fun wrapSelectableShelfCard''',
'''        return frame\n    }\n\n    private fun decodeShelfCoverFile(file: File): Bitmap? {\n        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }\n        BitmapFactory.decodeFile(file.absolutePath, bounds)\n        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null\n        return BitmapFactory.decodeFile(\n            file.absolutePath,\n            BitmapFactory.Options().apply { inSampleSize = shelfCoverSample(bounds.outWidth, bounds.outHeight) }\n        )\n    }\n\n    private fun decodeShelfCoverBytes(bytes: ByteArray): Bitmap? {\n        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }\n        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)\n        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null\n        return BitmapFactory.decodeByteArray(\n            bytes, 0, bytes.size,\n            BitmapFactory.Options().apply { inSampleSize = shelfCoverSample(bounds.outWidth, bounds.outHeight) }\n        )\n    }\n\n    private fun shelfCoverSample(width: Int, height: Int): Int {\n        var sample = 1\n        while (width / sample > 384 || height / sample > 512) sample *= 2\n        return sample.coerceAtLeast(1)\n    }\n\n    private fun wrapSelectableShelfCard''',
    'shelf cover sampling helpers'
)
p.write_text(s, encoding='utf-8')

# Default cover cache must not pin decoded bitmaps after the shelf is deliberately released.
p = Path('app/src/main/java/com/simplereader/app/ui/BookCoverAssets.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
'''    fun drawable(context: Context, format: String, radiusPx: Float): Drawable =\n        DefaultBookCoverDrawable(\n            bitmap = CoverBitmapCache.load(context, defaultCoverRes(format)),\n            radiusPx = radiusPx\n        )\n''',
'''    fun drawable(context: Context, format: String, radiusPx: Float): Drawable =\n        DefaultBookCoverDrawable(\n            bitmap = CoverBitmapCache.load(context, defaultCoverRes(format)),\n            radiusPx = radiusPx\n        )\n\n    fun clearMemoryCache() = CoverBitmapCache.clear()\n''',
    'default cover clear API'
)
s = replace_once(
    s,
'''    fun load(context: Context, resId: Int): Bitmap = synchronized(bitmaps) {\n        bitmaps[resId] ?: requireNotNull(BitmapFactory.decodeResource(context.resources, resId)) {\n            "无法读取默认封面资源：$resId"\n        }.also { bitmaps[resId] = it }\n    }\n''',
'''    fun load(context: Context, resId: Int): Bitmap = synchronized(bitmaps) {\n        bitmaps[resId] ?: requireNotNull(BitmapFactory.decodeResource(context.resources, resId)) {\n            "无法读取默认封面资源：$resId"\n        }.also { bitmaps[resId] = it }\n    }\n\n    fun clear() = synchronized(bitmaps) { bitmaps.clear() }\n''',
    'default cover cache clear implementation'
)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Reader backgrounds: one full-resolution bitmap at a time + sampled previews.
# The old unlimited Map decoded full-size assets even for tiny preview controls.
# -----------------------------------------------------------------------------
p = Path('app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'import android.content.Context\n', 'import android.content.Context\nimport android.content.ComponentCallbacks2\n', 'ComponentCallbacks2 import')
s = replace_once(s, 'import android.graphics.drawable.Drawable\n', 'import android.graphics.drawable.Drawable\nimport android.util.LruCache\n', 'LruCache import')
s = replace_once(
    s,
'''    fun nightDrawable(context: Context): Drawable = drawable(context, nightSelection())\n\n    fun previewDrawable''',
'''    fun nightDrawable(context: Context): Drawable = drawable(context, nightSelection())\n\n    fun clearMemoryCaches() = ReaderBackgroundBitmapCache.clear()\n\n    fun trimMemory(level: Int) = ReaderBackgroundBitmapCache.trim(level)\n\n    fun previewDrawable''',
    'reader background cache APIs'
)
start = s.index('private object ReaderBackgroundBitmapCache')
replacement = r'''private object ReaderBackgroundBitmapCache {
    private const val PREVIEW_CACHE_KB = 4 * 1024
    private var fullResId: Int = 0
    private var fullBitmap: Bitmap? = null
    private val previews = object : LruCache<Int, Bitmap>(PREVIEW_CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    @Synchronized
    fun full(context: Context, resId: Int): Bitmap? {
        val existing = fullBitmap
        if (fullResId == resId && existing != null && !existing.isRecycled) return existing
        val decoded = BitmapFactory.decodeResource(context.applicationContext.resources, resId) ?: return null
        fullResId = resId
        fullBitmap = decoded
        return decoded
    }

    @Synchronized
    fun preview(context: Context, resId: Int): Bitmap? {
        previews.get(resId)?.takeIf { !it.isRecycled }?.let { return it }
        val resources = context.applicationContext.resources
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 256) sample *= 2
        val decoded = BitmapFactory.decodeResource(
            resources,
            resId,
            BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        ) ?: return null
        previews.put(resId, decoded)
        return decoded
    }

    @Synchronized
    fun clear() {
        fullResId = 0
        fullBitmap = null
        previews.evictAll()
    }

    @Synchronized
    fun trim(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> previews.evictAll()
        }
    }
}

private class FullPageReaderBackgroundDrawable(
    context: Context,
    private val option: ReaderBackgrounds.BackgroundOption,
    private val preview: Boolean = false
) : Drawable() {
    private val appContext = context.applicationContext
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = option.representativeColor }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        fillPaint.alpha = globalAlpha
        canvas.drawRect(area, fillPaint)
        val bitmap = if (preview) {
            ReaderBackgroundBitmapCache.preview(appContext, option.drawableRes)
        } else {
            ReaderBackgroundBitmapCache.full(appContext, option.drawableRes)
        } ?: error("Confirmed v625 background asset is missing: ${option.id}")
        destination.set(area)
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val areaAspect = area.width().toFloat() / area.height().coerceAtLeast(1).toFloat()
        if (bitmapAspect > areaAspect) {
            val targetWidth = (bitmap.height * areaAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - targetWidth) / 2
            source.set(left, 0, left + targetWidth, bitmap.height)
        } else {
            val targetHeight = (bitmap.width / areaAspect.coerceAtLeast(0.01f)).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - targetHeight) / 2
            source.set(0, top, bitmap.width, top + targetHeight)
        }
        bitmapPaint.alpha = globalAlpha
        canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class ReaderBackgroundPreviewDrawable(
    context: Context,
    selection: ReaderBackgrounds.Selection,
    private val selected: Boolean
) : Drawable() {
    private val safeSelection = ReaderBackgrounds.validated(selection)
    private val option = ReaderBackgrounds.option(safeSelection)
    private val background = if (option.category == ReaderBackgrounds.Category.COLOR) null else
        FullPageReaderBackgroundDrawable(context, option, preview = true)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = option.representativeColor }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 5f else 2f
        color = if (selected) Color.rgb(239, 122, 40) else Color.argb(80, 92, 84, 72)
    }

    override fun draw(canvas: Canvas) {
        if (background != null) {
            background.bounds = bounds
            background.draw(canvas)
        } else {
            canvas.drawRect(bounds, fillPaint)
        }
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRect(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half, borderPaint)
    }

    override fun setAlpha(alpha: Int) {
        background?.alpha = alpha
        fillPaint.alpha = alpha.coerceIn(0, 255)
        borderPaint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        background?.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
'''
s = s[:start] + replacement
p.write_text(s, encoding='utf-8')

# App-level memory callbacks release reader background caches under system pressure.
p = Path('app/src/main/java/com/simplereader/app/App.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'import com.simplereader.app.crash.CrashLogStore\n', 'import com.simplereader.app.crash.CrashLogStore\nimport com.simplereader.app.ui.ReaderBackgrounds\n', 'ReaderBackgrounds import')
s = replace_once(
    s,
'''    override fun onTrimMemory(level: Int) {\n        CrashLogStore.recordMemorySnapshot(this, "app_onTrimMemory_$level")\n        super.onTrimMemory(level)\n    }\n\n    override fun onLowMemory() {\n        CrashLogStore.recordMemorySnapshot(this, "app_onLowMemory")\n        super.onLowMemory()\n    }\n''',
'''    override fun onTrimMemory(level: Int) {\n        ReaderBackgrounds.trimMemory(level)\n        CrashLogStore.recordMemorySnapshot(this, "app_onTrimMemory_$level")\n        super.onTrimMemory(level)\n    }\n\n    override fun onLowMemory() {\n        ReaderBackgrounds.clearMemoryCaches()\n        CrashLogStore.recordMemorySnapshot(this, "app_onLowMemory")\n        super.onLowMemory()\n    }\n''',
    'App cache trimming'
)
p.write_text(s, encoding='utf-8')

# EPUB/image cache releases references when ReaderActivity is destroyed.
p = Path('app/src/main/java/com/simplereader/app/reader/ReaderImageRepository.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
'''    fun span(href: String, maxWidth: Int, maxHeight: Int): ReplacementSpan {\n        val bitmap = synchronized(cache) {\n            cache[href] ?: loadBitmap(href, maxWidth, maxHeight)?.also { cache[href] = it }\n        }\n        return ScaledImageSpan(bitmap, maxWidth, maxHeight)\n    }\n''',
'''    fun span(href: String, maxWidth: Int, maxHeight: Int): ReplacementSpan {\n        val bitmap = synchronized(cache) {\n            cache[href] ?: loadBitmap(href, maxWidth, maxHeight)?.also { cache[href] = it }\n        }\n        return ScaledImageSpan(bitmap, maxWidth, maxHeight)\n    }\n\n    fun clear() = synchronized(cache) { cache.clear() }\n''',
    'reader image cache clear API'
)
p.write_text(s, encoding='utf-8')

# Vertical rendered-page cache can be explicitly dropped without a RecyclerView rebind.
p = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
'''    fun refresh() {\n        rendered.evictAll()\n        notifyDataSetChanged()\n    }\n''',
'''    fun refresh() {\n        rendered.evictAll()\n        notifyDataSetChanged()\n    }\n\n    fun release() {\n        pages = emptyList()\n        rendered.evictAll()\n    }\n''',
    'vertical adapter release API'
)
p.write_text(s, encoding='utf-8')

# PagedReaderView releases Text/Spans/Drawables/listeners explicitly on Activity destruction.
p = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
'''    fun currentSnapshot(): ReaderPageSnapshot? = currentPage\n\n    fun turn(direction: Int): Boolean {\n''',
'''    fun currentSnapshot(): ReaderPageSnapshot? = currentPage\n\n    fun release() {\n        cancelNavigation()\n        previousPage = null\n        currentPage = null\n        nextPage = null\n        style = null\n        listOf(previousView, currentView, nextView).forEach { page ->\n            page.text = ""\n            page.background = null\n        }\n        edgeShadow.background = null\n        onTurnCommitted = null\n        onBoundaryTurn = null\n        onCenterTap = null\n        onLongPress = null\n    }\n\n    fun turn(direction: Int): Boolean {\n''',
    'paged reader release API'
)
p.write_text(s, encoding='utf-8')

# ReaderActivity: remove duplicate full-screen background and release all reader-owned memory.
p = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
'''    override fun onDestroy() {\n        paginationJob?.cancel()\n        continuousRenderJob?.cancel()\n        pagedReaderView.cancelNavigation()\n        verticalRecyclerView?.stopScroll()\n        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable = null\n        cancelVerticalStateUnlockGuard()\n        stopAutoReading(false)\n        CrashLogStore.recordEvent(this, "ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset")\n        CrashLogStore.recordMemorySnapshot(this, "reader_onDestroy", memoryDiagnosticDetails())\n        if (isFinishing && !isChangingConfigurations) {\n            CrashLogStore.finishReaderSession(this, bookId)\n        }\n        super.onDestroy()\n    }\n''',
'''    override fun onDestroy() {\n        val memoryDetails = memoryDiagnosticDetails()\n        val cleanFinish = isFinishing && !isChangingConfigurations\n        paginationJob?.cancel()\n        continuousRenderJob?.cancel()\n        fontChangeRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)\n        progressCheckpointRunnable = null\n        cancelVerticalStateUnlockGuard()\n        stopAutoReading(false)\n        CrashLogStore.recordEvent(this, "ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset")\n        releaseReaderMemory()\n        CrashLogStore.recordMemorySnapshot(this, "reader_onDestroy_after_release", memoryDetails)\n        if (cleanFinish) {\n            CrashLogStore.finishReaderSession(this, bookId)\n        }\n        super.onDestroy()\n    }\n\n    private fun releaseReaderMemory() {\n        verticalAdapter?.release()\n        verticalRecyclerView?.apply {\n            stopScroll()\n            clearOnScrollListeners()\n            setOnTouchListener(null)\n            adapter = null\n            recycledViewPool.clear()\n        }\n        verticalRecyclerView?.let { recycler ->\n            if (::readerViewport.isInitialized) readerViewport.removeView(recycler)\n        }\n        verticalRecyclerView = null\n        verticalLayoutManager = null\n        verticalAdapter = null\n\n        if (::pagedReaderView.isInitialized) pagedReaderView.release()\n        if (::continuousTextView.isInitialized) {\n            continuousTextView.text = ""\n            continuousTextView.background = null\n        }\n        if (::readerScrollView.isInitialized) readerScrollView.background = null\n        if (::readerRoot.isInitialized) readerRoot.background = null\n        findViewById<View>(android.R.id.content)?.background = null\n\n        imageRepository?.clear()\n        imageRepository = null\n        readerBook = null\n        document = null\n        book = null\n        layoutSettings = null\n        pendingFontRollback = null\n        searchHits = emptyList()\n        activeSearchHit = null\n        continuousHighlightSpan = null\n        lastDisplayedChapterTitle = null\n        ReaderBackgrounds.clearMemoryCaches()\n    }\n''',
    'ReaderActivity explicit release'
)
s = replace_once(
    s,
'''        readerRoot.background = activeBackgroundDrawable()\n        verticalAdapter?.refresh()\n''',
'''        readerRoot.background = activeBackgroundDrawable()\n        verticalAdapter?.refresh()\n''',
    'keep root background'
)
# Remove duplicate android.R.id.content full-screen background layer.
s = s.replace('        findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()\n', '        findViewById<View>(android.R.id.content).background = null\n', 1)
p.write_text(s, encoding='utf-8')

print('v763 memory-release patch applied')
