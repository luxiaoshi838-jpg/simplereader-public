from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def replace_section(text: str, start: str, end: str, replacement: str, label: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"missing section start: {label}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"missing section end: {label}")
    return text[:a] + replacement + text[b:]

# -----------------------------------------------------------------------------
# Version
# -----------------------------------------------------------------------------
gradle = Path('app/build.gradle.kts')
g = gradle.read_text(encoding='utf-8')
g = g.replace('"2098000763"', '"2098000764')
g = g.replace('?: 2098000763', '?: 2098000764')
g = g.replace('?: "763"', '?: "764"')
gradle.write_text(g, encoding='utf-8')

# -----------------------------------------------------------------------------
# Runtime reader ownership / foreground state.
# -----------------------------------------------------------------------------
runtime = Path('app/src/main/java/com/simplereader/app/runtime/ReaderRuntimeState.kt')
runtime.parent.mkdir(parents=True, exist_ok=True)
runtime.write_text('''package com.simplereader.app.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local ownership for ReaderActivity instances.
 *
 * Only the newest ReaderActivity generation may persist reading progress/recovery state. This
 * prevents an older hidden Activity from overwriting the foreground reader after a duplicate
 * launch. Foreground state is also exposed so the full-shelf cache worker can yield while the user
 * is actively reading/searching.
 */
object ReaderRuntimeState {
    private val generationCounter = AtomicLong(0L)

    @Volatile private var currentGeneration: Long = 0L
    @Volatile private var foregroundGeneration: Long = 0L

    fun claim(): Long {
        val generation = generationCounter.incrementAndGet()
        currentGeneration = generation
        return generation
    }

    fun isOwner(generation: Long): Boolean = generation > 0L && currentGeneration == generation

    fun markResumed(generation: Long) {
        if (isOwner(generation)) foregroundGeneration = generation
    }

    fun markPaused(generation: Long) {
        if (foregroundGeneration == generation) foregroundGeneration = 0L
    }

    fun isReaderForeground(): Boolean = foregroundGeneration != 0L
    fun ownerGeneration(): Long = currentGeneration
}
''', encoding='utf-8')

# -----------------------------------------------------------------------------
# Manifest: Android-level duplicate Activity suppression.
# -----------------------------------------------------------------------------
manifest = Path('app/src/main/AndroidManifest.xml')
m = manifest.read_text(encoding='utf-8')
m = replace_once(
    m,
    '''        <activity\n            android:name=".ui.ReaderActivity"\n            android:exported="false" />''',
    '''        <activity\n            android:name=".ui.ReaderActivity"\n            android:exported="false"\n            android:launchMode="singleTop" />''',
    'ReaderActivity singleTop',
    'android:launchMode="singleTop"'
)
manifest.write_text(m, encoding='utf-8')

# -----------------------------------------------------------------------------
# Main layout: true RecyclerView virtualization instead of ScrollView + 5715-view GridLayout.
# -----------------------------------------------------------------------------
layout = Path('app/src/main/res/layout/activity_main.xml')
x = layout.read_text(encoding='utf-8')
old_shelf = '''    <com.simplereader.app.ui.FastScrollView\n        android:id="@+id/shelfScrollView"\n        android:layout_width="match_parent"\n        android:layout_height="0dp"\n        android:layout_marginTop="6dp"\n        android:layout_weight="1"\n        android:fillViewport="true">\n\n        <GridLayout\n            android:id="@+id/shelfGrid"\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:columnCount="3"\n            android:paddingEnd="14dp"\n            android:useDefaultMargins="false" />\n    </com.simplereader.app.ui.FastScrollView>'''
new_shelf = '''    <androidx.recyclerview.widget.RecyclerView\n        android:id="@+id/shelfGrid"\n        android:layout_width="match_parent"\n        android:layout_height="0dp"\n        android:layout_marginTop="6dp"\n        android:layout_weight="1"\n        android:clipToPadding="false"\n        android:paddingEnd="14dp"\n        android:scrollbars="vertical" />'''
x = replace_once(x, old_shelf, new_shelf, 'virtualized shelf layout', 'androidx.recyclerview.widget.RecyclerView')
layout.write_text(x, encoding='utf-8')

# -----------------------------------------------------------------------------
# MainActivity: virtualized shelf, cover-job cancellation/concurrency, duplicate launch guard.
# -----------------------------------------------------------------------------
main = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt')
s = main.read_text(encoding='utf-8')
s = replace_once(
    s,
    'import androidx.room.withTransaction\n',
    'import androidx.room.withTransaction\nimport androidx.recyclerview.widget.GridLayoutManager\nimport androidx.recyclerview.widget.RecyclerView\n',
    'RecyclerView imports',
    'import androidx.recyclerview.widget.GridLayoutManager'
)
s = replace_once(
    s,
    'import kotlinx.coroutines.launch\n',
    'import kotlinx.coroutines.Job\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withPermit\n',
    'cover coroutine imports',
    'import kotlinx.coroutines.sync.Semaphore'
)
s = replace_once(
    s,
    'import java.io.File\n',
    'import java.io.File\nimport java.util.Collections\n',
    'Collections import',
    'import java.util.Collections'
)
s = s.replace('private lateinit var shelfGrid: GridLayout', 'private lateinit var shelfGrid: RecyclerView')
s = replace_once(
    s,
    '    private var shelfUiVisible = false\n',
    '''    private var shelfUiVisible = false\n    private var readerLaunchInFlight = false\n    private var shelfCoverGeneration = 0L\n    private val shelfCoverJobs = Collections.synchronizedSet(mutableSetOf<Job>())\n    private val shelfCoverSemaphore = Semaphore(3)\n    private val shelfAdapter by lazy { ShelfAdapter() }\n''',
    'shelf runtime fields',
    'private var readerLaunchInFlight = false'
)

s = replace_once(
    s,
    '''        shelfGrid = findViewById(R.id.shelfGrid)\n        readingStatsTextView = findViewById(R.id.readingStatsTextView)''',
    '''        shelfGrid = findViewById(R.id.shelfGrid)\n        val shelfLayoutManager = GridLayoutManager(this, 3)\n        shelfLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {\n            override fun getSpanSize(position: Int): Int = if (shelfAdapter.isFullSpan(position)) 3 else 1\n        }\n        shelfGrid.layoutManager = shelfLayoutManager\n        shelfGrid.adapter = shelfAdapter\n        shelfGrid.itemAnimator = null\n        shelfGrid.setItemViewCacheSize(12)\n        readingStatsTextView = findViewById(R.id.readingStatsTextView)''',
    'configure virtualized shelf',
    'shelfLayoutManager.spanSizeLookup'
)

s = replace_once(
    s,
    '''    override fun onResume() {\n        super.onResume()\n        shelfUiVisible = true''',
    '''    override fun onResume() {\n        super.onResume()\n        readerLaunchInFlight = false\n        shelfUiVisible = true''',
    'reset reader launch guard',
    'readerLaunchInFlight = false\n        shelfUiVisible = true'
)

new_update = '''    private sealed interface ShelfRenderItem {\n        data class GroupItem(val group: BookGroup, val books: List<ShelfBookItem>) : ShelfRenderItem\n        data class BookItem(val book: ShelfBookItem) : ShelfRenderItem\n        data class EmptyItem(val message: String) : ShelfRenderItem\n    }\n\n    private inner class ShelfHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)\n\n    private inner class ShelfAdapter : RecyclerView.Adapter<ShelfHolder>() {\n        private var items: List<ShelfRenderItem> = emptyList()\n\n        fun submit(newItems: List<ShelfRenderItem>) {\n            items = newItems\n            notifyDataSetChanged()\n        }\n\n        fun isFullSpan(position: Int): Boolean = items.getOrNull(position) is ShelfRenderItem.EmptyItem\n\n        override fun getItemCount(): Int = items.size\n\n        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ShelfHolder {\n            val container = FrameLayout(parent.context).apply {\n                layoutParams = RecyclerView.LayoutParams(\n                    RecyclerView.LayoutParams.MATCH_PARENT,\n                    RecyclerView.LayoutParams.WRAP_CONTENT\n                )\n            }\n            return ShelfHolder(container)\n        }\n\n        override fun onBindViewHolder(holder: ShelfHolder, position: Int) {\n            clearShelfImageRefs(holder.container)\n            holder.container.removeAllViews()\n            val child = when (val item = items[position]) {\n                is ShelfRenderItem.GroupItem -> buildGroupCard(item.group, item.books)\n                is ShelfRenderItem.BookItem -> buildBookCard(item.book)\n                is ShelfRenderItem.EmptyItem -> buildEmptyText(item.message)\n            }\n            child.layoutParams = FrameLayout.LayoutParams(\n                FrameLayout.LayoutParams.MATCH_PARENT,\n                FrameLayout.LayoutParams.WRAP_CONTENT\n            )\n            holder.container.addView(child)\n        }\n\n        override fun onViewRecycled(holder: ShelfHolder) {\n            clearShelfImageRefs(holder.container)\n            holder.container.removeAllViews()\n            super.onViewRecycled(holder)\n        }\n    }\n\n    private fun clearShelfImageRefs(view: View) {\n        when (view) {\n            is ImageView -> view.setImageDrawable(null)\n            is android.view.ViewGroup -> for (i in 0 until view.childCount) clearShelfImageRefs(view.getChildAt(i))\n        }\n    }\n\n    private fun updateUI() {\n        if (!shelfUiVisible) return\n        applyShelfAppearance()\n        val filteredBooks = books.filter {\n            shelfSearchQuery.isBlank() || it.title.contains(shelfSearchQuery, ignoreCase = true)\n        }\n        val visibleBooks = if (showingHistory) {\n            filteredBooks.filter { it.lastReadTime > 0L }\n        } else {\n            filteredBooks\n        }.sortedByDescending(::activityTime)\n\n        val activeGroup = selectedGroupId?.let { id -> groups.firstOrNull { it.id == id } }\n        if (selectedGroupId != null && activeGroup == null) selectedGroupId = null\n        shelfTabTextView.text = if (selectedGroupId == null) "书架" else "‹ 全部书架"\n\n        val renderItems = mutableListOf<ShelfRenderItem>()\n        if (!showingHistory && selectedGroupId != null) {\n            val group = groups.firstOrNull { it.id == selectedGroupId }\n            val groupBooks = visibleBooks.filter { it.groupId == selectedGroupId }\n            if (!ShelfCacheUiController.isLocked(this)) {\n                readingStatsTextView.text = "${group?.displayName?.ifBlank { group.name } ?: "分组"} · ${groupBooks.size} 本"\n            }\n            renderItems += groupBooks.map(ShelfRenderItem::BookItem)\n            if (groupBooks.isEmpty()) renderItems += ShelfRenderItem.EmptyItem("该分组暂无书籍")\n            shelfAdapter.submit(renderItems)\n            return\n        }\n\n        if (!ShelfCacheUiController.isLocked(this)) readingStatsTextView.text = "累计导入 ${books.size} 本"\n\n        if (showingHistory) {\n            renderItems += visibleBooks.map(ShelfRenderItem::BookItem)\n            if (visibleBooks.isEmpty()) renderItems += ShelfRenderItem.EmptyItem("暂无阅读历史")\n            shelfAdapter.submit(renderItems)\n            return\n        }\n\n        val booksByGroup = visibleBooks.groupBy { it.groupId }\n        groups.mapNotNull { group ->\n            val groupBooks = booksByGroup[group.id].orEmpty().sortedByDescending(::activityTime)\n            if (groupBooks.isEmpty()) null else group to groupBooks\n        }.sortedByDescending { (_, groupBooks) -> groupBooks.maxOf(::activityTime) }\n            .forEach { (group, groupBooks) -> renderItems += ShelfRenderItem.GroupItem(group, groupBooks) }\n\n        renderItems += booksByGroup[null].orEmpty()\n            .sortedByDescending(::activityTime)\n            .map(ShelfRenderItem::BookItem)\n\n        if (visibleBooks.isEmpty()) {\n            renderItems += ShelfRenderItem.EmptyItem(\n                if (shelfSearchQuery.isBlank()) "点击导入选择小说文件夹" else "没有匹配的书籍"\n            )\n        }\n        shelfAdapter.submit(renderItems)\n    }\n\n'''
s = replace_section(
    s,
    '    private fun updateUI() {\n',
    '    private fun applyShelfAppearance() {\n',
    new_update,
    'virtualized updateUI',
    'private sealed interface ShelfRenderItem'
)

s = s.replace('private fun addGroupCard(group: BookGroup, groupBooks: List<ShelfBookItem>) {',
              'private fun buildGroupCard(group: BookGroup, groupBooks: List<ShelfBookItem>): View {')
s = s.replace('        shelfGrid.addView(wrapSelectableShelfCard(card, selectedShelfGroupIds.contains(group.id)))\n    }\n\n    private fun groupPreviewLayoutParams',
              '        return wrapSelectableShelfCard(card, selectedShelfGroupIds.contains(group.id))\n    }\n\n    private fun groupPreviewLayoutParams', 1)
s = s.replace('private fun addBookCard(book: ShelfBookItem) {', 'private fun buildBookCard(book: ShelfBookItem): View {')
s = s.replace('        shelfGrid.addView(wrapSelectableShelfCard(card, selectedShelfBookIds.contains(book.id)))\n    }\n\n    private fun createBookCover',
              '        return wrapSelectableShelfCard(card, selectedShelfBookIds.contains(book.id))\n    }\n\n    private fun createBookCover', 1)

old_cover_launch = '''        if (book.format.equals("EPUB", ignoreCase = true) && coverBitmapCache.get(book.id) == null) {\n            lifecycleScope.launch {\n                val bitmap = withContext(Dispatchers.IO) {\n                    runCatching {\n                        StructuredBookCache.coverFile(this@MainActivity, book.id)\n                            ?.takeIf { it.isFile }\n                            ?.let(::decodeShelfCoverFile)\n                            ?: contentResolver.openInputStream(Uri.parse(book.filePath))?.use { input ->\n                                EpubParser.readCoverImage(input)?.let(::decodeShelfCoverBytes)\n                            }\n                    }.getOrNull()\n                }\n                if (bitmap != null && shelfUiVisible && !isFinishing && !isDestroyed) {\n                    coverBitmapCache.put(book.id, bitmap)\n                    showBitmap(bitmap)\n                }\n            }\n        }'''
new_cover_launch = '''        if (book.format.equals("EPUB", ignoreCase = true) && coverBitmapCache.get(book.id) == null) {\n            val generation = shelfCoverGeneration\n            val job = lifecycleScope.launch {\n                val bitmap = withContext(Dispatchers.IO) {\n                    shelfCoverSemaphore.withPermit {\n                        runCatching {\n                            StructuredBookCache.coverFile(this@MainActivity, book.id)\n                                ?.takeIf { it.isFile }\n                                ?.let(::decodeShelfCoverFile)\n                                ?: contentResolver.openInputStream(Uri.parse(book.filePath))?.use { input ->\n                                    EpubParser.readCoverImage(input)?.let(::decodeShelfCoverBytes)\n                                }\n                        }.getOrNull()\n                    }\n                }\n                if (bitmap != null && generation == shelfCoverGeneration && shelfUiVisible && !isFinishing && !isDestroyed) {\n                    coverBitmapCache.put(book.id, bitmap)\n                    showBitmap(bitmap)\n                }\n            }\n            shelfCoverJobs.add(job)\n            job.invokeOnCompletion { shelfCoverJobs.remove(job) }\n        }'''
s = replace_once(s, old_cover_launch, new_cover_launch, 'bounded cancellable cover loads', 'shelfCoverSemaphore.withPermit')

# Empty card now returns a full-span RecyclerView child.
empty_start = '    private fun addEmptyText(message: String) {\n'
empty_end = '    private fun openBook(bookId: Long) {\n'
new_empty = '''    private fun buildEmptyText(message: String): View = TextView(this).apply {\n        text = message\n        textSize = 18f\n        setTextColor(Color.rgb(110, 106, 98))\n        gravity = Gravity.CENTER\n        minHeight = dp(160)\n    }\n\n'''
s = replace_section(s, empty_start, empty_end, new_empty, 'empty shelf renderer', 'private fun buildEmptyText')

old_open = '''    private fun openBook(bookId: Long) {\n        // V763: MainActivity uses a non-virtualized GridLayout. Release every card/ImageView and\n        // decoded cover before constructing ReaderActivity so the two full UI trees never overlap.\n        shelfUiVisible = false\n        releaseShelfUiMemory("before_open_reader")\n        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bookId))\n    }'''
new_open = '''    private fun openBook(bookId: Long) {\n        // V764: block repeated taps/events until MainActivity resumes from the one reader instance.\n        if (readerLaunchInFlight) {\n            CrashLogStore.recordEvent(this, "reader_launch_suppressed book=$bookId")\n            return\n        }\n        readerLaunchInFlight = true\n        shelfUiVisible = false\n        releaseShelfUiMemory("before_open_reader")\n        runCatching {\n            startActivity(\n                Intent(this, ReaderActivity::class.java)\n                    .putExtra("bookId", bookId)\n                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)\n            )\n        }.onFailure {\n            readerLaunchInFlight = false\n            shelfUiVisible = true\n            updateUI()\n            throw it\n        }\n    }'''
s = replace_once(s, old_open, new_open, 'reader launch guard', 'reader_launch_suppressed')

old_release = '''    private fun releaseShelfUiMemory(reason: String) {\n        if (::shelfGrid.isInitialized) shelfGrid.removeAllViews()\n        coverBitmapCache.evictAll()\n        BookCoverAssets.clearMemoryCache()'''
new_release = '''    private fun releaseShelfUiMemory(reason: String) {\n        shelfCoverGeneration += 1L\n        shelfCoverJobs.toList().forEach(Job::cancel)\n        shelfCoverJobs.clear()\n        if (::shelfGrid.isInitialized) {\n            shelfAdapter.submit(emptyList())\n            shelfGrid.stopScroll()\n            shelfGrid.recycledViewPool.clear()\n        }\n        coverBitmapCache.evictAll()\n        BookCoverAssets.clearMemoryCache()'''
s = replace_once(s, old_release, new_release, 'release virtualized shelf', 'shelfCoverJobs.toList().forEach(Job::cancel)')
main.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# ReaderActivity: newest-instance-only progress/recovery ownership. Search code is not touched.
# -----------------------------------------------------------------------------
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
r = reader.read_text(encoding='utf-8')
r = replace_once(r, 'import android.os.Bundle\n', 'import android.content.Intent\nimport android.os.Bundle\n', 'Intent import', 'import android.content.Intent')
r = replace_once(
    r,
    'import com.simplereader.app.reader.page.ReaderPage\n',
    'import com.simplereader.app.reader.page.ReaderPage\nimport com.simplereader.app.runtime.ReaderRuntimeState\n',
    'ReaderRuntimeState import',
    'import com.simplereader.app.runtime.ReaderRuntimeState'
)
r = replace_once(
    r,
    '    private var pendingVerticalDiagnosticEvent: String? = null\n',
    '    private var pendingVerticalDiagnosticEvent: String? = null\n    private var readerGeneration: Long = 0L\n',
    'reader generation field',
    'private var readerGeneration: Long = 0L'
)
r = replace_once(
    r,
    '        bookId = intent.getLongExtra("bookId", 0L)\n\n        loadPreferences()',
    '        bookId = intent.getLongExtra("bookId", 0L)\n        readerGeneration = ReaderRuntimeState.claim()\n\n        loadPreferences()',
    'claim reader ownership',
    'readerGeneration = ReaderRuntimeState.claim()'
)
r = r.replace(
    'CrashLogStore.recordEvent(this, "ReaderActivity.onCreate book=$bookId mode=$pageTurnMode")',
    'CrashLogStore.recordEvent(this, "ReaderActivity.onCreate book=$bookId mode=$pageTurnMode generation=$readerGeneration")'
)

old_pause = '''    override fun onPause() {\n        stopAutoReading(false)\n        if (pageTurnMode == TURN_MODE_VERTICAL) {\n            persistVerticalDiagnosticState("vertical_pause", force = true)\n        }\n        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset")\n        CrashLogStore.recordMemorySnapshot(this, "reader_onPause", memoryDiagnosticDetails())\n        saveProgress()\n        super.onPause()\n    }'''
new_pause = '''    override fun onResume() {\n        super.onResume()\n        ReaderRuntimeState.markResumed(readerGeneration)\n    }\n\n    override fun onNewIntent(intent: Intent) {\n        super.onNewIntent(intent)\n        val requestedBookId = intent.getLongExtra("bookId", 0L)\n        // A rapid duplicate launch is intentionally ignored by the existing top reader.\n        CrashLogStore.recordEvent(\n            this,\n            "ReaderActivity.onNewIntent requested=$requestedBookId current=$bookId generation=$readerGeneration"\n        )\n    }\n\n    override fun onPause() {\n        ReaderRuntimeState.markPaused(readerGeneration)\n        stopAutoReading(false)\n        if (ownsReaderSession()) {\n            if (pageTurnMode == TURN_MODE_VERTICAL) {\n                persistVerticalDiagnosticState("vertical_pause", force = true)\n            }\n            saveProgress()\n        } else {\n            CrashLogStore.recordEvent(\n                this,\n                "stale_reader_pause_write_suppressed book=$bookId generation=$readerGeneration owner=${ReaderRuntimeState.ownerGeneration()}"\n            )\n        }\n        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset generation=$readerGeneration owner=${ownsReaderSession()}")\n        CrashLogStore.recordMemorySnapshot(this, "reader_onPause", memoryDiagnosticDetails())\n        super.onPause()\n    }'''
r = replace_once(r, old_pause, new_pause, 'owner-aware pause', 'stale_reader_pause_write_suppressed')

r = replace_once(
    r,
    '''        if (cleanFinish) {\n            CrashLogStore.finishReaderSession(this, bookId)\n        }''',
    '''        if (cleanFinish && ownsReaderSession()) {\n            CrashLogStore.finishReaderSession(this, bookId)\n        } else if (cleanFinish) {\n            CrashLogStore.recordEvent(\n                this,\n                "stale_reader_finish_suppressed book=$bookId generation=$readerGeneration owner=${ReaderRuntimeState.ownerGeneration()}"\n            )\n        }''',
    'owner-aware finish',
    'stale_reader_finish_suppressed'
)

# Insert ownership helper before releaseReaderMemory.
r = replace_once(
    r,
    '    private fun releaseReaderMemory() {\n',
    '''    private fun ownsReaderSession(): Boolean = ReaderRuntimeState.isOwner(readerGeneration)\n\n    private fun releaseReaderMemory() {\n''',
    'ownership helper',
    'private fun ownsReaderSession()'
)

r = replace_once(
    r,
    '''    private fun persistVerticalDiagnosticState(event: String, force: Boolean = false) {\n        val pages = readerBook?.pages.orEmpty()''',
    '''    private fun persistVerticalDiagnosticState(event: String, force: Boolean = false) {\n        if (!ownsReaderSession()) return\n        val pages = readerBook?.pages.orEmpty()''',
    'diagnostic ownership guard',
    'private fun persistVerticalDiagnosticState(event: String, force: Boolean = false) {\n        if (!ownsReaderSession()) return'
)

r = replace_once(
    r,
    '''    private fun scheduleProgressCheckpoint(sourceOffset: Int) {\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)''',
    '''    private fun scheduleProgressCheckpoint(sourceOffset: Int) {\n        if (!ownsReaderSession()) return\n        val generation = readerGeneration\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)''',
    'checkpoint owner guard',
    'val generation = readerGeneration\n        progressCheckpointRunnable'
)
r = replace_once(
    r,
    '''            lifecycleScope.launch(Dispatchers.IO) {\n                database.readProgressDao().insert(snapshot)\n                database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())\n            }''',
    '''            lifecycleScope.launch(Dispatchers.IO) {\n                if (!ReaderRuntimeState.isOwner(generation)) return@launch\n                database.readProgressDao().insert(snapshot)\n                database.bookDao().updateLastReadTime(bookId, System.currentTimeMillis())\n            }''',
    'checkpoint async owner guard',
    'if (!ReaderRuntimeState.isOwner(generation)) return@launch'
)
r = replace_once(
    r,
    '''    private fun saveProgress() {\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)''',
    '''    private fun saveProgress() {\n        if (!ownsReaderSession()) return\n        val generation = readerGeneration\n        progressCheckpointRunnable?.let(mainHandler::removeCallbacks)''',
    'final progress owner guard',
    'private fun saveProgress() {\n        if (!ownsReaderSession()) return'
)
r = replace_once(
    r,
    '''        (application as App).applicationScope.launch {\n            database.readProgressDao().insert(snapshot)''',
    '''        (application as App).applicationScope.launch {\n            if (!ReaderRuntimeState.isOwner(generation)) return@launch\n            database.readProgressDao().insert(snapshot)''',
    'final async owner guard',
    '(application as App).applicationScope.launch {\n            if (!ReaderRuntimeState.isOwner(generation)) return@launch'
)

# Add generation/ownership to diagnostic details without touching search code.
r = r.replace(
    'return "book=$bookId mode=$pageTurnMode textChars=$sourceTextChars pages=${paged?.pages?.size ?: 0}',
    'return "book=$bookId mode=$pageTurnMode generation=$readerGeneration owner=${ownsReaderSession()} textChars=$sourceTextChars pages=${paged?.pages?.size ?: 0}'
)
reader.write_text(r, encoding='utf-8')

# -----------------------------------------------------------------------------
# CrashLogStore: invalidate delayed memory samples from older reader sessions; add process identity.
# -----------------------------------------------------------------------------
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt')
c = crash.read_text(encoding='utf-8')
c = replace_once(
    c,
    'import java.util.concurrent.atomic.AtomicReference\n',
    'import java.util.concurrent.atomic.AtomicLong\nimport java.util.concurrent.atomic.AtomicReference\n',
    'AtomicLong import',
    'import java.util.concurrent.atomic.AtomicLong'
)
c = replace_once(
    c,
    '    private val stateRef = AtomicReference<ReaderState?>(null)\n',
    '''    private val stateRef = AtomicReference<ReaderState?>(null)\n    private val memoryScheduleGeneration = AtomicLong(0L)\n    private val processSessionId = "${Process.myPid()}-${System.currentTimeMillis()}"\n''',
    'memory schedule generation',
    'private val memoryScheduleGeneration = AtomicLong(0L)'
)
c = replace_once(
    c,
    '''    fun beginReaderSession(context: Context, bookId: Long, turnMode: String) {\n        if (bookId <= 0L) return''',
    '''    fun beginReaderSession(context: Context, bookId: Long, turnMode: String) {\n        if (bookId <= 0L) return\n        // A new reader invalidates delayed samples that belonged to an older finished reader.\n        memoryScheduleGeneration.incrementAndGet()''',
    'invalidate old delayed samples',
    'A new reader invalidates delayed samples'
)
c = replace_once(
    c,
    '''        recordMemorySnapshot(appContext, event)\n        schedulePostReaderMemorySnapshots(appContext)''',
    '''        recordMemorySnapshot(appContext, event)\n        val scheduleGeneration = memoryScheduleGeneration.incrementAndGet()\n        schedulePostReaderMemorySnapshots(appContext, scheduleGeneration)''',
    'generation-tag finished samples',
    'val scheduleGeneration = memoryScheduleGeneration.incrementAndGet()'
)
c = replace_once(
    c,
    '''    private fun schedulePostReaderMemorySnapshots(context: Context) {\n        val appContext = context.applicationContext''',
    '''    private fun schedulePostReaderMemorySnapshots(context: Context, scheduleGeneration: Long) {\n        val appContext = context.applicationContext''',
    'scheduled snapshot generation argument',
    'schedulePostReaderMemorySnapshots(context: Context, scheduleGeneration: Long)'
)
c = replace_once(
    c,
    '''            ioExecutor.schedule({\n                runCatching { writeMemorySnapshot(appContext, "post_reader_finish_${delay}_${unit.name.lowercase()}", "") }\n            }, delay, unit)''',
    '''            ioExecutor.schedule({\n                if (memoryScheduleGeneration.get() != scheduleGeneration) return@schedule\n                runCatching {\n                    writeMemorySnapshot(\n                        appContext,\n                        "post_reader_finish_${delay}_${unit.name.lowercase()}",\n                        "scheduleGeneration=$scheduleGeneration"\n                    )\n                }\n            }, delay, unit)''',
    'skip stale delayed samples',
    'memoryScheduleGeneration.get() != scheduleGeneration'
)
# Version/pid/process session are background-only metadata, useful after an upgrade.
c = replace_once(
    c,
    '''        val line = buildString {\n            append(formatTimestamp(System.currentTimeMillis()))\n            append(" | memory reason=").append(reason)''',
    '''        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()\n        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo?.longVersionCode else {\n            @Suppress("DEPRECATION") packageInfo?.versionCode?.toLong()\n        }\n        val line = buildString {\n            append(formatTimestamp(System.currentTimeMillis()))\n            append(" | memory version=").append(packageInfo?.versionName ?: "?")\n            append('(').append(versionCode ?: -1L).append(')')\n            append(" pid=").append(Process.myPid())\n            append(" processSession=").append(processSessionId)\n            append(" reason=").append(reason)''',
    'memory version/process identity',
    'append(" processSession=").append(processSessionId)'
)
crash.write_text(c, encoding='utf-8')

# -----------------------------------------------------------------------------
# ShelfCacheWorker yields CPU/memory-heavy full-shelf pagination while user is reading/searching.
# -----------------------------------------------------------------------------
worker = Path('app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt')
w = worker.read_text(encoding='utf-8')
w = replace_once(
    w,
    'import com.simplereader.app.reader.page.ReaderLayoutSettings\n',
    'import com.simplereader.app.reader.page.ReaderLayoutSettings\nimport com.simplereader.app.runtime.ReaderRuntimeState\n',
    'worker reader runtime import',
    'import com.simplereader.app.runtime.ReaderRuntimeState'
)
w = replace_once(w, 'import kotlinx.coroutines.Dispatchers\n', 'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay\n', 'delay import', 'import kotlinx.coroutines.delay')
w = replace_once(
    w,
    '''        for (index in resumeIndex until total) {\n            coroutineContext.ensureActive()''',
    '''        for (index in resumeIndex until total) {\n            coroutineContext.ensureActive()\n            awaitForegroundReaderIdle()''',
    'yield worker at book boundary',
    'awaitForegroundReaderIdle()\n            val bookId'
)
w = replace_once(
    w,
    '''                val paged = withContext(Dispatchers.Default) {\n                    val images = ReaderImageRepository(applicationContext, book.id)''',
    '''                awaitForegroundReaderIdle()\n                val paged = withContext(Dispatchers.Default) {\n                    val images = ReaderImageRepository(applicationContext, book.id)''',
    'yield worker before pagination',
    'awaitForegroundReaderIdle()\n                val paged = withContext'
)
# Insert helper before reusable-cache helper.
w = replace_once(
    w,
    '    private suspend fun hasReusableCurrentCache(\n',
    '''    private suspend fun awaitForegroundReaderIdle() {\n        while (ReaderRuntimeState.isReaderForeground()) {\n            coroutineContext.ensureActive()\n            delay(250L)\n        }\n    }\n\n    private suspend fun hasReusableCurrentCache(\n''',
    'reader foreground yield helper',
    'private suspend fun awaitForegroundReaderIdle()'
)
worker.write_text(w, encoding='utf-8')

print('v764 duplicate-reader ownership + virtualized shelf + stale-diagnostic fix applied')
