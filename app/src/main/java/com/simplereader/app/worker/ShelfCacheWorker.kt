package com.simplereader.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.operation.OperationLogStore
import com.simplereader.app.parser.TxtParser
import com.simplereader.app.reader.ReaderDocument
import com.simplereader.app.reader.ReaderDocumentLoader
import com.simplereader.app.reader.ReaderImageRepository
import com.simplereader.app.reader.page.PageCacheStore
import com.simplereader.app.reader.page.PageEngine
import com.simplereader.app.reader.page.ReaderCacheProfile
import com.simplereader.app.reader.page.ReaderLayoutSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * User-triggered shelf catalog + full pagination cache task.
 *
 * The worker owns the work independently of MainActivity. Once started it is promoted to a
 * foreground WorkManager task, so switching activities/apps does not cancel the cache pass.
 * A book counts as successful only after the persisted page cache can be loaded back with the
 * same identity that the visible v722 reader uses.
 *
 * The WorkRequest id also owns a durable checkpoint. If Android recreates this worker after
 * process death, it resumes from the first unfinished book instead of rescanning the shelf.
 */
class ShelfCacheWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        createNotificationChannel()

        val mode = inputData.getString(KEY_MODE) ?: MODE_ALL_BOOKS
        val operationTitle = modeTitle(mode)
        val workId = id.toString()
        val database = SimpleReaderDatabase.getDatabase(applicationContext)

        val currentBooks = withContext(Dispatchers.IO) {
            database.bookDao().getAllBooks().first()
        }
        val persistedWorkProgress = withContext(Dispatchers.IO) {
            runCatching {
                WorkManager.getInstance(applicationContext).getWorkInfoById(id).get()?.progress
            }.getOrNull() ?: workDataOf()
        }

        var checkpoint = withContext(Dispatchers.IO) {
            ShelfCacheCheckpointStore.load(applicationContext, workId)
        }
        if (checkpoint == null || checkpoint.mode != mode) {
            val legacyCompleted = persistedWorkProgress.getInt(KEY_COMPLETED, 0).coerceAtLeast(0)
            val legacySkipped = persistedWorkProgress.getInt(KEY_SKIPPED, 0).coerceAtLeast(0)
            val legacyFailed = persistedWorkProgress.getInt(KEY_FAILED, 0).coerceAtLeast(0)
            val legacyCurrent = persistedWorkProgress.getInt(KEY_CURRENT, 0).coerceAtLeast(0)
            val classifiedCount = (legacyCompleted + legacySkipped + legacyFailed)
                .coerceAtMost(currentBooks.size)
            // v725 published the current book number before doing that book. If old counters are
            // unavailable, current-1 safely resumes by redoing at most the interrupted book.
            val legacyResumeIndex = maxOf(
                classifiedCount,
                (legacyCurrent - 1).coerceAtLeast(0)
            ).coerceAtMost(currentBooks.size)

            checkpoint = withContext(Dispatchers.IO) {
                ShelfCacheCheckpointStore.create(
                    context = applicationContext,
                    workId = workId,
                    mode = mode,
                    bookIds = currentBooks.map { it.id },
                    nextIndex = legacyResumeIndex,
                    completed = legacyCompleted,
                    skipped = legacySkipped,
                    failed = legacyFailed
                )
            }
        }

        var completed = checkpoint.completed
        var skipped = checkpoint.skipped
        var failed = checkpoint.failed
        val total = checkpoint.total
        val resumeIndex = checkpoint.nextIndex.coerceIn(0, total)
        val nextTitle = if (resumeIndex < total) {
            withContext(Dispatchers.IO) {
                database.bookDao().getBook(checkpoint.bookIds[resumeIndex])?.title
            }.orEmpty()
        } else {
            "已完成"
        }

        setForeground(
            foregroundInfo(
                resumeIndex,
                total,
                if (resumeIndex > 0 && resumeIndex < total) {
                    "继续：${nextTitle.ifBlank { "下一本" }}（$resumeIndex/$total）"
                } else if (resumeIndex >= total) {
                    "正在完成任务…"
                } else {
                    "正在读取书架…"
                }
            )
        )
        setProgress(
            progressData(
                current = resumeIndex,
                total = total,
                title = nextTitle.ifBlank { if (resumeIndex > 0) "继续中" else "准备中" },
                completed = completed,
                skipped = skipped,
                failed = failed
            )
        )

        OperationLogStore.beginShelfCache(
            context = applicationContext,
            workId = workId,
            modeTitle = operationTitle,
            total = total
        )
        OperationLogStore.updateShelfCache(
            context = applicationContext,
            workId = workId,
            modeTitle = operationTitle,
            state = if (resumeIndex > 0 && resumeIndex < total) "继续中" else "运行中",
            currentIndex = resumeIndex,
            total = total,
            currentTitle = nextTitle,
            completed = completed,
            failed = failed,
            skipped = skipped
        )

        for (index in resumeIndex until total) {
            coroutineContext.ensureActive()
            val bookId = checkpoint.bookIds[index]
            val book = withContext(Dispatchers.IO) {
                database.bookDao().getBook(bookId)
            }

            if (book == null) {
                skipped += 1
                checkpoint = checkpoint.copy(
                    nextIndex = index + 1,
                    completed = completed,
                    skipped = skipped,
                    failed = failed
                )
                withContext(Dispatchers.IO) {
                    ShelfCacheCheckpointStore.save(applicationContext, workId, checkpoint)
                }
                publishProgress(
                    current = index + 1,
                    total = total,
                    title = "书籍已从书架移除",
                    completed = completed,
                    skipped = skipped,
                    failed = failed
                )
                OperationLogStore.updateShelfCache(
                    context = applicationContext,
                    workId = workId,
                    modeTitle = operationTitle,
                    state = "运行中",
                    currentIndex = index + 1,
                    total = total,
                    currentTitle = "书籍已从书架移除",
                    completed = completed,
                    failed = failed,
                    skipped = skipped
                )
                continue
            }

            val displayedIndex = index + 1
            publishProgress(
                current = displayedIndex,
                total = total,
                title = book.title,
                completed = completed,
                skipped = skipped,
                failed = failed
            )
            OperationLogStore.updateShelfCache(
                context = applicationContext,
                workId = workId,
                modeTitle = operationTitle,
                state = "运行中",
                currentIndex = displayedIndex,
                total = total,
                currentTitle = book.title,
                completed = completed,
                failed = failed,
                skipped = skipped
            )

            val currentSource = withContext(Dispatchers.IO) {
                ReaderDocumentLoader.resolveDocument(applicationContext, book)
            }
            val currentFileName = currentSource?.name?.takeIf { it.isNotBlank() } ?: book.fileName
            val currentFileSize = currentSource?.length()?.takeIf { it >= 0L } ?: book.fileSize

            val alreadyReusable = if (mode == MODE_BOOKS_WITHOUT_CATALOG) {
                hasReusableCurrentCache(
                    bookId = book.id,
                    filePath = book.filePath,
                    fileName = currentFileName,
                    fileSize = currentFileSize,
                    loadDocument = {
                        ReaderDocumentLoader.load(
                            context = applicationContext,
                            book = book,
                            forceCatalogRefresh = false
                        )
                    }
                )
            } else {
                false
            }

            if (alreadyReusable) {
                skipped += 1
                checkpoint = checkpoint.copy(
                    nextIndex = index + 1,
                    completed = completed,
                    skipped = skipped,
                    failed = failed
                )
                withContext(Dispatchers.IO) {
                    ShelfCacheCheckpointStore.save(applicationContext, workId, checkpoint)
                }
                publishProgress(
                    current = displayedIndex,
                    total = total,
                    title = book.title,
                    completed = completed,
                    skipped = skipped,
                    failed = failed
                )
                OperationLogStore.updateShelfCache(
                    context = applicationContext,
                    workId = workId,
                    modeTitle = operationTitle,
                    state = "运行中",
                    currentIndex = displayedIndex,
                    total = total,
                    currentTitle = book.title,
                    completed = completed,
                    failed = failed,
                    skipped = skipped
                )
                continue
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PageCacheStore.clearDerivedCatalogAndPages(applicationContext, book.id)
                }
                coroutineContext.ensureActive()

                val document = withContext(Dispatchers.IO) {
                    ReaderDocumentLoader.load(
                        context = applicationContext,
                        book = book,
                        forceCatalogRefresh = true
                    )
                }
                coroutineContext.ensureActive()

                val settings = ReaderCacheProfile.createSettings(applicationContext)
                val identity = cacheIdentity(
                    bookId = book.id,
                    filePath = book.filePath,
                    document = document,
                    settings = settings
                )

                val paged = withContext(Dispatchers.Default) {
                    val images = ReaderImageRepository(applicationContext, book.id)
                    PageEngine.paginate(
                        text = document.text,
                        sourceChapters = document.chapters,
                        settings = settings,
                        typeface = Typeface.DEFAULT
                    ) { href, width, height -> images.span(href, width, height) }
                }
                require(paged.pages.isNotEmpty()) { "分页结果为空" }
                coroutineContext.ensureActive()

                withContext(Dispatchers.IO) {
                    PageCacheStore.savePages(applicationContext, identity, paged)
                }
                coroutineContext.ensureActive()

                val verified = withContext(Dispatchers.IO) {
                    PageCacheStore.loadPages(applicationContext, identity, document.text)
                }
                require(verified != null) { "分页缓存写入后无法重新读取" }
                require(verified.pages.size == paged.pages.size) { "分页缓存页数校验失败" }
                require(verified.chapters.size == paged.chapters.size) { "分页缓存章节校验失败" }

                withContext(Dispatchers.IO) {
                    PageCacheStore.markRecognitionComplete(
                        context = applicationContext,
                        bookId = book.id,
                        fileName = currentFileName,
                        fileSize = currentFileSize ?: document.sourceSize,
                        chapterCount = verified.chapters.count { it.catalogVisible },
                        pageCount = verified.pages.size
                    )
                }
            }

            if (result.isSuccess) completed += 1 else failed += 1
            checkpoint = checkpoint.copy(
                nextIndex = index + 1,
                completed = completed,
                skipped = skipped,
                failed = failed
            )
            // Commit the checkpoint only after this book is fully classified. If the process dies
            // during the current book, only that one book may be retried; finished books never are.
            withContext(Dispatchers.IO) {
                ShelfCacheCheckpointStore.save(applicationContext, workId, checkpoint)
            }

            publishProgress(
                current = displayedIndex,
                total = total,
                title = book.title,
                completed = completed,
                skipped = skipped,
                failed = failed
            )
            OperationLogStore.updateShelfCache(
                context = applicationContext,
                workId = workId,
                modeTitle = operationTitle,
                state = "运行中",
                currentIndex = displayedIndex,
                total = total,
                currentTitle = book.title,
                completed = completed,
                failed = failed,
                skipped = skipped
            )
        }

        setProgress(progressData(total, total, "已完成", completed, skipped, failed))
        val output = workDataOf(
            KEY_TOTAL to total,
            KEY_COMPLETED to completed,
            KEY_SKIPPED to skipped,
            KEY_FAILED to failed
        )
        OperationLogStore.updateShelfCache(
            context = applicationContext,
            workId = workId,
            modeTitle = operationTitle,
            state = "已完成",
            currentIndex = total,
            total = total,
            currentTitle = "",
            completed = completed,
            failed = failed,
            skipped = skipped
        )
        // Keep the completed checkpoint. If Android recreates this WorkRequest before WorkManager
        // commits SUCCEEDED, nextIndex == total makes the recreated worker finish immediately rather
        // than starting the whole shelf again. A later user action has a different workId.
        return Result.success(output)
    }

    private suspend fun hasReusableCurrentCache(
        bookId: Long,
        filePath: String,
        fileName: String,
        fileSize: Long?,
        loadDocument: suspend () -> ReaderDocument
    ): Boolean {
        if (!PageCacheStore.hasCurrentCatalog(
                applicationContext,
                bookId,
                fileName,
                fileSize
            )
        ) return false

        return runCatching {
            val document = withContext(Dispatchers.IO) { loadDocument() }
            val settings = ReaderCacheProfile.createSettings(applicationContext)
            val identity = cacheIdentity(bookId, filePath, document, settings)
            val cached = withContext(Dispatchers.IO) {
                PageCacheStore.loadPages(applicationContext, identity, document.text)
            }
            cached != null && cached.pages.isNotEmpty()
        }.getOrDefault(false)
    }

    private fun cacheIdentity(
        bookId: Long,
        filePath: String,
        document: ReaderDocument,
        settings: ReaderLayoutSettings
    ): PageCacheStore.CacheIdentity = PageCacheStore.CacheIdentity(
        bookId = bookId,
        filePath = filePath,
        fileSize = document.sourceSize,
        lastModified = document.sourceModified,
        settingsHash = settings.stableHash(),
        textFingerprint = PageCacheStore.textFingerprint(document.text),
        catalogRuleVersion = TxtParser.CATALOG_RULE_VERSION
    )

    private suspend fun publishProgress(
        current: Int,
        total: Int,
        title: String,
        completed: Int,
        skipped: Int,
        failed: Int
    ) {
        setForeground(
            foregroundInfo(
                current,
                total,
                "$title（$current/$total）"
            )
        )
        setProgress(progressData(current, total, title, completed, skipped, failed))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val mode = inputData.getString(KEY_MODE) ?: MODE_ALL_BOOKS
        return foregroundInfo(0, 0, modeTitle(mode, prefix = "准备"))
    }

    private fun progressData(
        current: Int,
        total: Int,
        title: String,
        completed: Int,
        skipped: Int,
        failed: Int
    ): Data = workDataOf(
        KEY_CURRENT to current,
        KEY_TOTAL to total,
        KEY_TITLE to title,
        KEY_COMPLETED to completed,
        KEY_SKIPPED to skipped,
        KEY_FAILED to failed
    )

    private fun modeTitle(mode: String, prefix: String = ""): String {
        val scope = if (mode == MODE_BOOKS_WITHOUT_CATALOG) "无目录书籍" else "全书架"
        return if (prefix.isBlank()) "${scope}目录缓存" else "${prefix}${scope}目录缓存"
    }

    private fun foregroundInfo(current: Int, total: Int, text: String): ForegroundInfo {
        val manager = WorkManager.getInstance(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("简阅：${modeTitle(inputData.getString(KEY_MODE) ?: MODE_ALL_BOOKS)}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total.coerceAtLeast(0), current.coerceAtLeast(0), total <= 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                manager.createCancelPendingIntent(id)
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "书架目录缓存",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在后台识别全书架目录并生成可直接复用的完整分页缓存"
                setSound(null, null)
            }
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "simple_reader_cache_all_shelf_books"
        const val TAG = "shelf_cache"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_TITLE = "title"
        const val KEY_COMPLETED = "completed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_MODE = "mode"
        const val MODE_ALL_BOOKS = "all_books"
        const val MODE_BOOKS_WITHOUT_CATALOG = "books_without_catalog"

        private const val CHANNEL_ID = "simple_reader_shelf_cache"
        private const val NOTIFICATION_ID = 61313

        fun enqueue(context: Context, mode: String) {
            require(mode == MODE_ALL_BOOKS || mode == MODE_BOOKS_WITHOUT_CATALOG)
            val request = OneTimeWorkRequestBuilder<ShelfCacheWorker>()
                .setInputData(Data.Builder().putString(KEY_MODE, mode).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
