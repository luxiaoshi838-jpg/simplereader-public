#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"v786 anchor missing: {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Version.
build = ROOT / "app/build.gradle.kts"
text = build.read_text(encoding="utf-8")
text = text.replace('"2098000785"', '"2098000786"').replace('?: 2098000785', '?: 2098000786')
text = text.replace('?: "785"', '?: "786"')
build.write_text(text, encoding="utf-8")

# 2) Crash history: persist up to 20 distinct real crash/exit records and consume pending once.
crash = ROOT / "app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt"
replace_once(
    crash,
    "import java.io.File\nimport java.text.SimpleDateFormat",
    "import java.io.File\nimport java.security.MessageDigest\nimport java.text.SimpleDateFormat"
)
replace_once(
    crash,
    '    private const val PROCESS_SESSION_META_FILE_NAME = "process_session_meta.json"\n    private const val MAX_LOG_CHARS = 512_000',
    '    private const val PROCESS_SESSION_META_FILE_NAME = "process_session_meta.json"\n'
    '    private const val CRASH_HISTORY_DIR_NAME = "crash_history_v786"\n'
    '    private const val CRASH_HISTORY_LIMIT = 20\n'
    '    private const val PENDING_SEPARATOR = "\\n\\n================ 之前尚未清除的记录 ================\\n\\n"\n'
    '    private const val MAX_LOG_CHARS = 512_000'
)
old_pending = '''    fun readPending(context: Context): String? = runCatching {
        pendingFile(context)
            .takeIf { it.isFile && it.length() > 0L }
            ?.readText(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun clear(context: Context): Boolean = runCatching {
        val file = pendingFile(context)
        !file.exists() || file.delete()
    }.getOrDefault(false)
'''
new_pending = '''    data class CrashHistoryItem(
        val id: String,
        val savedAt: Long,
        val headline: String
    )

    /**
     * Moves the one-shot pending crash into the durable 20-entry history and deletes pending.
     * The returned text is non-null only when the newest crash was not already stored, so the
     * same Android exit can never be presented as a fresh crash on every MainActivity launch.
     */
    fun consumePendingIntoHistory(context: Context): String? = synchronized(pendingLock) {
        val appContext = context.applicationContext
        val pending = pendingFile(appContext)
        val raw = runCatching {
            pending.takeIf { it.isFile && it.length() > 0L }?.readText(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")
        if (raw.isBlank()) return@synchronized null
        val newestNew = storeCrashSectionsLocked(appContext, raw)
        runCatching { pending.delete() }
        newestNew
    }

    fun listCrashHistory(context: Context): List<CrashHistoryItem> = synchronized(pendingLock) {
        val directory = crashHistoryDirectory(context.applicationContext)
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "log" }
            .sortedByDescending(File::lastModified)
            .take(CRASH_HISTORY_LIMIT)
            .map { file ->
                val headline = runCatching {
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        val first = reader.readLine().orEmpty().trim()
                        val second = reader.readLine().orEmpty().trim()
                        listOf(first, second).filter(String::isNotBlank).joinToString(" · ").take(180)
                    }
                }.getOrDefault("异常退出记录")
                CrashHistoryItem(file.nameWithoutExtension, file.lastModified(), headline.ifBlank { "异常退出记录" })
            }
    }

    fun readCrashHistoryEntry(context: Context, id: String): String? = synchronized(pendingLock) {
        if (!id.matches(Regex("[0-9a-f]{64}"))) return@synchronized null
        runCatching {
            crashHistoryDirectory(context.applicationContext)
                .resolve("$id.log")
                .takeIf { it.isFile && it.length() > 0L }
                ?.readText(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    fun readPending(context: Context): String? = runCatching {
        pendingFile(context)
            .takeIf { it.isFile && it.length() > 0L }
            ?.readText(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun clear(context: Context): Boolean = runCatching {
        val file = pendingFile(context)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    private fun storeCrashSectionsLocked(context: Context, raw: String): String? {
        val sections = raw.split(PENDING_SEPARATOR).map(String::trim).filter(String::isNotBlank)
        if (sections.isEmpty()) return null
        val directory = crashHistoryDirectory(context).apply { mkdirs() }
        val now = System.currentTimeMillis()
        var newestNew: String? = null
        sections.forEachIndexed { index, section ->
            val bounded = section.take(MAX_LOG_CHARS)
            val id = crashEntryId(bounded)
            val target = directory.resolve("$id.log")
            if (!target.isFile) {
                writeAtomic(target, bounded)
                // Pending is newest-first. Preserve that order even when migrating several old records.
                target.setLastModified((now - index).coerceAtLeast(1L))
                if (index == 0) newestNew = bounded
            }
        }
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "log" }
            .sortedByDescending(File::lastModified)
            .drop(CRASH_HISTORY_LIMIT)
            .forEach { runCatching { it.delete() } }
        return newestNew
    }

    private fun crashEntryId(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
'''
replace_once(crash, old_pending, new_pending)
replace_once(
    crash,
    '''            if (old.isNotBlank()) {
                val history = File(context.filesDir, HISTORY_FILE_NAME)
''',
    '''            if (old.isNotBlank()) {
                storeCrashSectionsLocked(context, old)
                val history = File(context.filesDir, HISTORY_FILE_NAME)
'''
)
replace_once(
    crash,
    '''    private fun pendingFile(context: Context): File = File(context.filesDir, PENDING_FILE_NAME)
    private fun readerStateFile(context: Context): File = File(context.filesDir, READER_STATE_FILE_NAME)
''',
    '''    private fun pendingFile(context: Context): File = File(context.filesDir, PENDING_FILE_NAME)
    private fun crashHistoryDirectory(context: Context): File = File(context.filesDir, CRASH_HISTORY_DIR_NAME)
    private fun readerStateFile(context: Context): File = File(context.filesDir, READER_STATE_FILE_NAME)
'''
)

# 3) Main shelf: one-shot crash presentation + durable history browser; restore intended card spacing.
main = ROOT / "app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
old_dialog = '''    private fun showPendingCrashLogIfNeeded() {
        val crashLog = CrashLogStore.readPending(this) ?: return
        val logView = TextView(this).apply {
            text = crashLog
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val content = ScrollView(this).apply {
            isFillViewport = true
            addView(logView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("异常退出/闪退/崩溃日志")
            .setView(content)
            .setPositiveButton("复制并清除", null)
            .setNegativeButton("暂不复制", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("简阅异常退出日志", crashLog))
                CrashLogStore.clear(this)
                Toast.makeText(this, "日志已复制并清除", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
'''
new_dialog = '''    private fun showPendingCrashLogIfNeeded() {
        val crashLog = CrashLogStore.consumePendingIntoHistory(this) ?: return
        showCrashLogDetail(crashLog, title = "新异常退出/闪退/崩溃日志")
    }

    private fun showCrashHistoryDialog() {
        val entries = CrashLogStore.listCrashHistory(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "暂无异常日志", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.mapIndexed { index, entry ->
            "${index + 1}. ${entry.headline}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("异常日志 · 最近 ${entries.size}/20 条")
            .setItems(labels) { _, which ->
                val entry = entries.getOrNull(which) ?: return@setItems
                val content = CrashLogStore.readCrashHistoryEntry(this, entry.id) ?: return@setItems
                showCrashLogDetail(content, title = "异常日志 ${which + 1}/${entries.size}")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showCrashLogDetail(crashLog: String, title: String) {
        val logView = TextView(this).apply {
            text = crashLog
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val content = ScrollView(this).apply {
            isFillViewport = true
            addView(logView)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("简阅异常退出日志", crashLog))
                Toast.makeText(this, "日志已复制；历史记录仍保留", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("最近20条") { _, _ -> showCrashHistoryDialog() }
            .setNegativeButton("关闭", null)
            .show()
    }
'''
replace_once(main, old_dialog, new_dialog)
replace_once(
    main,
    '''        override fun onBindViewHolder(holder: ShelfHolder, position: Int) {
            clearShelfImageRefs(holder.container)
            holder.container.removeAllViews()
            val child = when (val item = items[position]) {
''',
    '''        override fun onBindViewHolder(holder: ShelfHolder, position: Int) {
            clearShelfImageRefs(holder.container)
            holder.container.removeAllViews()
            val boundItem = items[position]
            if (boundItem is ShelfRenderItem.EmptyItem) {
                holder.container.setPadding(0, 0, 0, 0)
            } else {
                // The historical createShelfCard margins were lost when its LayoutParams were replaced.
                // Put the intended normal-shelf spacing on the stable ViewHolder container instead.
                holder.container.setPadding(dp(3), 0, dp(3), dp(18))
            }
            val child = when (val item = boundItem) {
'''
)
replace_once(
    main,
    '''            .setItems(arrayOf("书架目录缓存", "批量管理分组", "同步书架")) { _, which ->
                when (which) {
                    0 -> showShelfCacheOptions()
                    1 -> showBatchGroupManagement()
                    2 -> confirmSyncBookshelf()
                }
''',
    '''            .setItems(arrayOf("书架目录缓存", "批量管理分组", "同步书架", "异常日志（最近20条）")) { _, which ->
                when (which) {
                    0 -> showShelfCacheOptions()
                    1 -> showBatchGroupManagement()
                    2 -> confirmSyncBookshelf()
                    3 -> showCrashHistoryDialog()
                }
'''
)

# 4) Full-shelf cache: current heavy book must yield as soon as ReaderActivity becomes foreground.
worker = ROOT / "app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt"
replace_once(
    worker,
    "import com.simplereader.app.data.db.SimpleReaderDatabase\nimport com.simplereader.app.operation.OperationLogStore",
    "import com.simplereader.app.data.db.SimpleReaderDatabase\nimport com.simplereader.app.crash.CrashLogStore\nimport com.simplereader.app.operation.OperationLogStore"
)
replace_once(
    worker,
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay",
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay"
)
replace_once(
    worker,
    "import java.util.concurrent.atomic.AtomicBoolean" if "import java.util.concurrent.atomic.AtomicBoolean" in worker.read_text(encoding="utf-8") else "import kotlin.coroutines.coroutineContext",
    "import kotlin.coroutines.coroutineContext\nimport java.util.concurrent.atomic.AtomicBoolean"
)
# If the preceding idempotent path duplicated coroutineContext, normalize it.
wtext = worker.read_text(encoding="utf-8").replace("import kotlin.coroutines.coroutineContext\nimport kotlin.coroutines.coroutineContext\n", "import kotlin.coroutines.coroutineContext\n")
worker.write_text(wtext, encoding="utf-8")
replace_once(
    worker,
    '''            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PageCacheStore.clearDerivedCatalogAndPages(applicationContext, book.id)
                }
                coroutineContext.ensureActive()

                val document = withContext(Dispatchers.IO) {
''',
    '''            if (ReaderRuntimeState.isReaderForeground()) {
                ShelfCacheHandoff.releaseWorker(book.id)
                CrashLogStore.recordEvent(applicationContext, "shelf_cache_yield_before_book book=${book.id} title=${book.title}")
                return Result.retry()
            }

            val pausedForReader = AtomicBoolean(false)
            val paginationOwnerJob = coroutineContext[Job]
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PageCacheStore.clearDerivedCatalogAndPages(applicationContext, book.id)
                }
                coroutineContext.ensureActive()

                val document = withContext(Dispatchers.IO) {
'''
)
replace_once(
    worker,
    '''                coroutineContext.ensureActive()

                val settings = ReaderCacheProfile.createSettings(applicationContext)
''',
    '''                coroutineContext.ensureActive()
                if (ReaderRuntimeState.isReaderForeground()) {
                    pausedForReader.set(true)
                    throw java.util.concurrent.CancellationException("Reader foreground during shelf cache document load")
                }

                val settings = ReaderCacheProfile.createSettings(applicationContext)
'''
)
old_paged = '''                val paged = withContext(Dispatchers.Default) {
                    val images = ReaderImageRepository(applicationContext, book.id)
                    PageEngine.paginate(
                        text = document.text,
                        sourceChapters = document.chapters,
                        settings = settings,
                        typeface = Typeface.DEFAULT
                    ) { href, width, height -> images.span(href, width, height) }
                }
'''
new_paged = '''                val paged = withContext(Dispatchers.Default) {
                    val images = if (book.format.equals("EPUB", ignoreCase = true)) {
                        ReaderImageRepository(applicationContext, book.id)
                    } else null
                    try {
                        PageEngine.paginate(
                            text = document.text,
                            sourceChapters = document.chapters,
                            settings = settings,
                            typeface = Typeface.DEFAULT,
                            shouldCancel = {
                                val readerForeground = ReaderRuntimeState.isReaderForeground()
                                if (readerForeground) pausedForReader.set(true)
                                readerForeground || paginationOwnerJob?.isActive == false
                            },
                            imageSpanProvider = images?.let { repository ->
                                { href: String, width: Int, height: Int -> repository.span(href, width, height) }
                            }
                        )
                    } finally {
                        images?.clear()
                    }
                }
'''
replace_once(worker, old_paged, new_paged)
replace_once(
    worker,
    '''            ShelfCacheHandoff.releaseWorker(book.id)
            if (result.isSuccess) completed += 1 else failed += 1
''',
    '''            val failure = result.exceptionOrNull()
            if (failure is java.util.concurrent.CancellationException) {
                ShelfCacheHandoff.releaseWorker(book.id)
                if (pausedForReader.get() && paginationOwnerJob?.isActive != false) {
                    CrashLogStore.recordEvent(
                        applicationContext,
                        "shelf_cache_yield_during_pagination book=${book.id} title=${book.title} index=$displayedIndex/$total"
                    )
                    OperationLogStore.updateShelfCache(
                        context = applicationContext,
                        workId = workId,
                        modeTitle = operationTitle,
                        state = "阅读中暂停",
                        currentIndex = index,
                        total = total,
                        currentTitle = book.title,
                        completed = completed,
                        failed = failed,
                        skipped = skipped
                    )
                    return Result.retry()
                }
                throw failure
            }
            if (failure is VirtualMachineError || failure is LinkageError || failure is ThreadDeath) {
                ShelfCacheHandoff.releaseWorker(book.id)
                throw failure
            }

            ShelfCacheHandoff.releaseWorker(book.id)
            if (result.isSuccess) completed += 1 else failed += 1
'''
)
replace_once(
    worker,
    '''        // Keep the completed checkpoint. If Android recreates this WorkRequest before WorkManager
        // commits SUCCEEDED, nextIndex == total makes the recreated worker finish immediately rather
        // than starting the whole shelf again. A later user action has a different workId.
        return Result.success(output)
''',
    '''        // Keep the completed checkpoint. If Android recreates this WorkRequest before WorkManager
        // commits SUCCEEDED, nextIndex == total makes the recreated worker finish immediately rather
        // than starting the whole shelf again. A later user action has a different workId.
        CrashLogStore.recordMemorySnapshot(
            applicationContext,
            "shelf_cache_complete",
            "mode=$mode total=$total completed=$completed failed=$failed skipped=$skipped"
        )
        return Result.success(output)
'''
)

print("v786 crash-history/cache-yield/history-spacing patch applied")
