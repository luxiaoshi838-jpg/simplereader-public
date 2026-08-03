package com.simplereader.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.parser.TxtParser
import com.simplereader.app.reader.ReaderDocumentLoader
import com.simplereader.app.reader.ReaderImageRepository
import com.simplereader.app.reader.page.PageCacheStore
import com.simplereader.app.reader.page.PageEngine
import com.simplereader.app.reader.page.ReaderCacheProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * User-triggered shelf catalog cache task. It recognizes chapters and builds the current
 * device's page index one book at a time. WorkManager keeps it alive while the reader is
 * open or the app is in the background.
 */
class ShelfCacheWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        createNotificationChannel()
        setForeground(foregroundInfo(0, 0, "正在读取书架…"))

        val mode = inputData.getString(KEY_MODE) ?: MODE_ALL_BOOKS
        val database = SimpleReaderDatabase.getDatabase(applicationContext)
        val books = withContext(Dispatchers.IO) { database.bookDao().getAllBooks().first() }
        var completed = 0
        var skipped = 0
        var failed = 0

        books.forEachIndexed { index, book ->
            coroutineContext.ensureActive()
            val displayedIndex = index + 1
            setForeground(
                foregroundInfo(
                    displayedIndex,
                    books.size,
                    "${book.title}（$displayedIndex/${books.size}）"
                )
            )
            setProgress(
                workDataOf(
                    KEY_CURRENT to displayedIndex,
                    KEY_TOTAL to books.size,
                    KEY_TITLE to book.title,
                    KEY_COMPLETED to completed,
                    KEY_SKIPPED to skipped,
                    KEY_FAILED to failed
                )
            )

            val currentSource = withContext(Dispatchers.IO) {
                ReaderDocumentLoader.resolveDocument(applicationContext, book)
            }
            val currentFileName = currentSource?.name?.takeIf { it.isNotBlank() } ?: book.fileName
            val currentFileSize = currentSource?.length()?.takeIf { it >= 0L } ?: book.fileSize
            val shouldSkip = mode == MODE_BOOKS_WITHOUT_CATALOG &&
                PageCacheStore.hasCurrentCatalog(
                    applicationContext,
                    book.id,
                    currentFileName,
                    currentFileSize
                )
            if (shouldSkip) {
                skipped += 1
                return@forEachIndexed
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PageCacheStore.clearDerivedCatalogAndPages(applicationContext, book.id)
                }
                val document = withContext(Dispatchers.IO) {
                    ReaderDocumentLoader.load(
                        context = applicationContext,
                        book = book,
                        forceCatalogRefresh = true
                    )
                }
                val settings = ReaderCacheProfile.createSettings(applicationContext)
                val identity = PageCacheStore.CacheIdentity(
                    bookId = book.id,
                    filePath = book.filePath,
                    fileSize = document.sourceSize,
                    lastModified = document.sourceModified,
                    settingsHash = settings.stableHash(),
                    textFingerprint = PageCacheStore.textFingerprint(document.text),
                    catalogRuleVersion = TxtParser.CATALOG_RULE_VERSION
                )
                val cached = withContext(Dispatchers.IO) {
                    PageCacheStore.loadPages(applicationContext, identity, document.text)
                }
                val paged = cached ?: withContext(Dispatchers.Default) {
                    val images = ReaderImageRepository(applicationContext, book.id)
                    PageEngine.paginate(
                        text = document.text,
                        sourceChapters = document.chapters,
                        settings = settings,
                        typeface = Typeface.DEFAULT
                    ) { href, width, height -> images.span(href, width, height) }
                }
                if (cached == null) {
                    withContext(Dispatchers.IO) {
                        PageCacheStore.savePages(applicationContext, identity, paged)
                    }
                }
                withContext(Dispatchers.IO) {
                    PageCacheStore.markRecognitionComplete(
                        context = applicationContext,
                        bookId = book.id,
                        fileName = currentFileName,
                        fileSize = currentFileSize ?: document.sourceSize,
                        chapterCount = paged.chapters.count { it.catalogVisible },
                        pageCount = paged.pages.size
                    )
                }
            }
            if (result.isSuccess) completed += 1 else failed += 1
        }

        val output = workDataOf(
            KEY_TOTAL to books.size,
            KEY_COMPLETED to completed,
            KEY_SKIPPED to skipped,
            KEY_FAILED to failed
        )
        return Result.success(output)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val mode = inputData.getString(KEY_MODE) ?: MODE_ALL_BOOKS
        return foregroundInfo(0, 0, modeTitle(mode, prefix = "准备"))
    }

    private fun modeTitle(mode: String, prefix: String = "") : String {
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
                description = "在后台识别全书架目录并生成分页缓存"
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
