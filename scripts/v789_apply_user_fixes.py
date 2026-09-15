from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    write(path, text)


# Version
replace_once(
    "app/build.gradle.kts",
    'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000788")\n        .toIntOrNull()\n        ?: 2098000788\n    val generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME") ?: "788"',
    'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000789")\n        .toIntOrNull()\n        ?: 2098000789\n    val generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME") ?: "789"'
)

# Default grid mode should offer the destination action "列表".
replace_once(
    "app/src/main/res/layout/activity_main.xml",
    'android:id="@+id/editButton"\n            android:layout_width="52dp"\n            android:layout_height="match_parent"\n            android:gravity="center"\n            android:text="宫格"',
    'android:id="@+id/editButton"\n            android:layout_width="52dp"\n            android:layout_height="match_parent"\n            android:gravity="center"\n            android:text="列表"'
)

# Manifest: allow Android/WeChat/etc. to offer 简阅 for TXT/EPUB documents.
manifest_insert = '''        <activity
            android:name=".ui.ExternalBookOpenActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:mimeType="text/plain" />
                <data android:mimeType="application/epub+zip" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
                <data android:mimeType="application/epub+zip" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
        </activity>

'''
replace_once(
    "app/src/main/AndroidManifest.xml",
    '        <activity\n            android:name=".ui.GroupBooksActivity"',
    manifest_insert + '        <activity\n            android:name=".ui.GroupBooksActivity"'
)

# Pending external books must never appear on the bookshelf before the exit decision.
replace_once(
    "app/src/main/java/com/simplereader/app/data/dao/BookDao.kt",
    '@Query("SELECT * FROM books WHERE groupId IS NULL ORDER BY lastReadTime DESC")',
    '@Query("SELECT * FROM books WHERE groupId IS NULL AND fileStatus != \'EXTERNAL_PENDING\' ORDER BY lastReadTime DESC")'
)
replace_once(
    "app/src/main/java/com/simplereader/app/data/dao/BookDao.kt",
    '@Query("SELECT * FROM books WHERE groupId = :groupId ORDER BY lastReadTime DESC")',
    '@Query("SELECT * FROM books WHERE groupId = :groupId AND fileStatus != \'EXTERNAL_PENDING\' ORDER BY lastReadTime DESC")'
)
replace_once(
    "app/src/main/java/com/simplereader/app/data/dao/BookDao.kt",
    '@Query("SELECT * FROM books ORDER BY lastReadTime DESC")',
    '@Query("SELECT * FROM books WHERE fileStatus != \'EXTERNAL_PENDING\' ORDER BY lastReadTime DESC")'
)
replace_once(
    "app/src/main/java/com/simplereader/app/data/dao/BookDao.kt",
    '        FROM books\n        LEFT JOIN read_progress ON books.id = read_progress.bookId\n        ORDER BY books.lastReadTime DESC, books.addTime DESC',
    '        FROM books\n        LEFT JOIN read_progress ON books.id = read_progress.bookId\n        WHERE books.fileStatus != \'EXTERNAL_PENDING\'\n        ORDER BY books.lastReadTime DESC, books.addTime DESC'
)
replace_once(
    "app/src/main/java/com/simplereader/app/data/dao/BookDao.kt",
    '        WHERE books.groupId = :groupId\n        ORDER BY books.lastReadTime DESC, books.addTime DESC',
    '        WHERE books.groupId = :groupId\n          AND books.fileStatus != \'EXTERNAL_PENDING\'\n        ORDER BY books.lastReadTime DESC, books.addTime DESC'
)
replace_once(
    "app/src/main/java/com/simplereader/app/data/dao/BookDao.kt",
    '    @Query("DELETE FROM books WHERE id = :id")\n    suspend fun deleteById(id: Long)',
    '    @Query("SELECT * FROM books WHERE fileStatus = :status ORDER BY addTime ASC")\n    suspend fun getByFileStatus(status: String): List<Book>\n\n    @Query("DELETE FROM books WHERE id = :id")\n    suspend fun deleteById(id: Long)'
)

# Local app-owned copies need normal File support in the reader loader.
replace_once(
    "app/src/main/java/com/simplereader/app/reader/ReaderDocumentLoader.kt",
    '''    fun resolveDocument(context: Context, book: Book): DocumentFile? {
        val directUri = runCatching { Uri.parse(book.filePath) }.getOrNull()
        directUri?.let { uri ->
            val direct = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            if (direct != null && runCatching { direct.exists() && direct.isFile }.getOrDefault(false)) {
                return direct
            }
        }
        val treeUri = book.sourceTreeUri?.takeIf(String::isNotBlank) ?: return null
''',
    '''    fun resolveDocument(context: Context, book: Book): DocumentFile? {
        val directUri = runCatching { Uri.parse(book.filePath) }.getOrNull()
        val directFile = when {
            directUri?.scheme.equals("file", ignoreCase = true) -> directUri?.path?.let(::File)
            directUri?.scheme.isNullOrBlank() -> File(book.filePath)
            else -> null
        }
        if (directFile != null && directFile.isFile) {
            return DocumentFile.fromFile(directFile)
        }
        directUri?.takeIf { it.scheme.equals("content", ignoreCase = true) }?.let { uri ->
            val direct = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            if (direct != null && runCatching { direct.exists() && direct.isFile }.getOrDefault(false)) {
                return direct
            }
        }
        val treeUri = book.sourceTreeUri?.takeIf(String::isNotBlank) ?: return null
'''
)

# Allow a discarded external read to remove its page cache too.
replace_once(
    "app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt",
    '    fun textFingerprint(text: String): String {',
    '    fun clearBook(context: Context, bookId: Long) {\n        bookDir(context, bookId).deleteRecursively()\n    }\n\n    fun textFingerprint(text: String): String {'
)

# Log files are the durable route; clipboard is no longer the primary transport.
write(
    "app/src/main/java/com/simplereader/app/operation/DiagnosticLogFiles.kt",
    r'''package com.simplereader.app.operation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simplereader.app.crash.CrashLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object DiagnosticLogFiles {
    private const val PREFS = "diagnostic_log_locations"
    private const val KEY_CRASH_TREE = "crash_tree_uri"
    private const val KEY_OPERATION_TREE = "operation_tree_uri"
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jianyu-log-export").apply { isDaemon = true }
    }

    fun setCrashFolder(context: Context, uri: Uri) = setFolder(context, KEY_CRASH_TREE, uri)
    fun setOperationFolder(context: Context, uri: Uri) = setFolder(context, KEY_OPERATION_TREE, uri)

    fun crashFolderLabel(context: Context): String = folderLabel(context, KEY_CRASH_TREE)
    fun operationFolderLabel(context: Context): String = folderLabel(context, KEY_OPERATION_TREE)

    fun scheduleCrashSnapshot(context: Context) {
        if (savedUri(context, KEY_CRASH_TREE) == null) return
        val appContext = context.applicationContext
        ioExecutor.execute { exportCrashSnapshotNow(appContext) }
    }

    fun scheduleOperationSnapshot(context: Context) {
        if (savedUri(context, KEY_OPERATION_TREE) == null) return
        val appContext = context.applicationContext
        ioExecutor.execute { writeOperationSnapshot(appContext, fixedName = true) }
    }

    fun exportCrashSnapshotNow(context: Context): Result<String> = runCatching {
        val tree = savedTree(context, KEY_CRASH_TREE)
            ?: error("尚未设置崩溃日志文件夹")
        val entries = CrashLogStore.listCrashHistory(context)
        val pending = CrashLogStore.readPending(context).orEmpty()
        val content = buildString {
            appendLine("简阅崩溃/异常日志导出")
            appendLine("导出时间：${displayTime(System.currentTimeMillis())}")
            appendLine("历史记录：${entries.size} 条")
            if (pending.isNotBlank()) {
                appendLine()
                appendLine("================ 当前待处理日志 ================")
                appendLine(pending)
            }
            entries.forEachIndexed { index, entry ->
                appendLine()
                appendLine("================ 历史 ${index + 1}/${entries.size} · ${entry.headline} ================")
                appendLine(CrashLogStore.readCrashHistoryEntry(context, entry.id).orEmpty())
            }
            if (pending.isBlank() && entries.isEmpty()) appendLine("暂无崩溃/异常日志")
        }
        val name = "简阅_崩溃日志_${fileStamp()}.txt"
        writeDocument(context, tree, name, content, replace = false)
        name
    }

    fun exportOperationSnapshotNow(context: Context): Result<String> =
        writeOperationSnapshot(context, fixedName = false)

    private fun writeOperationSnapshot(context: Context, fixedName: Boolean): Result<String> = runCatching {
        val tree = savedTree(context, KEY_OPERATION_TREE)
            ?: error("尚未设置操作日志文件夹")
        val entries = OperationLogStore.list(context)
        val content = buildString {
            appendLine("简阅操作日志导出")
            appendLine("导出时间：${displayTime(System.currentTimeMillis())}")
            appendLine("记录：${entries.size} 条")
            entries.forEachIndexed { index, entry ->
                appendLine()
                appendLine("================ 操作 ${index + 1}/${entries.size} · ${entry.title} ================")
                appendLine(entry.body)
            }
            if (entries.isEmpty()) appendLine("暂无操作日志")
        }
        val name = if (fixedName) "简阅_操作日志_当前.txt" else "简阅_操作日志_${fileStamp()}.txt"
        writeDocument(context, tree, name, content, replace = fixedName)
        name
    }

    private fun setFolder(context: Context, key: String, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, uri.toString())
            .apply()
    }

    private fun savedUri(context: Context, key: String): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)

    private fun savedTree(context: Context, key: String): DocumentFile? =
        savedUri(context, key)?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory }
        }

    private fun folderLabel(context: Context, key: String): String {
        val uri = savedUri(context, key) ?: return "未设置"
        return DocumentFile.fromTreeUri(context, uri)?.name?.takeIf(String::isNotBlank)
            ?: uri.toString()
    }

    private fun writeDocument(
        context: Context,
        tree: DocumentFile,
        fileName: String,
        content: String,
        replace: Boolean
    ) {
        if (replace) runCatching { tree.findFile(fileName)?.delete() }
        val target = tree.createFile("text/plain", fileName)
            ?: error("无法在所选文件夹创建日志文件")
        val output = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: error("无法写入日志文件")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }

    private fun fileStamp(): String = SimpleDateFormat(
        "yyyyMMdd_HHmmss",
        Locale.getDefault()
    ).format(Date())

    private fun displayTime(value: Long): String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date(value))
}
'''
)

# Operation logs keep a rolling complete TXT mirror in the configured folder.
replace_once(
    "app/src/main/java/com/simplereader/app/operation/OperationLogStore.kt",
    '''        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }
''',
    '''        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
        DiagnosticLogFiles.scheduleOperationSnapshot(context)
    }
'''
)

write(
    "app/src/main/java/com/simplereader/app/operation/OperationLogDialogs.kt",
    r'''package com.simplereader.app.operation

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.simplereader.app.crash.CrashLogStore

/** Operation-log UI. Complete logs are exported as files; clipboard length is not relied on. */
object OperationLogDialogs {

    fun showLogHub(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("日志")
            .setItems(arrayOf("闪退/崩溃日志", "操作日志")) { _, which ->
                when (which) {
                    0 -> showPendingCrashLog(activity)
                    1 -> showOperationList(activity)
                }
            }
            .show()
    }

    fun showOperationList(activity: AppCompatActivity) {
        val entries = OperationLogStore.list(activity)
        if (entries.isEmpty()) {
            Toast.makeText(activity, "暂无操作日志", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("操作日志")
            .setItems(entries.map { it.title }.toTypedArray()) { _, which ->
                entries.getOrNull(which)?.let { showOperationDetail(activity, it) }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showOperationDetail(activity: AppCompatActivity, entry: OperationLogStore.Entry) {
        val bodyView = TextView(activity).apply {
            text = entry.body
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 12))
        }
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(bodyView)
        }
        val seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            contentDescription = "日志滚动进度"
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 420)
                )
            )
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        var dragging = false
        fun maxScroll(): Int = (bodyView.height - scrollView.height).coerceAtLeast(0)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val max = maxScroll()
                val target = if (max <= 0) 0 else (max * (progress / 1000f)).toInt()
                scrollView.scrollTo(0, target)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) { dragging = false }
        })
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (dragging) return@addOnScrollChangedListener
            val max = maxScroll()
            val progress = if (max <= 0) 0 else (scrollView.scrollY * 1000L / max).toInt()
            if (seekBar.progress != progress) seekBar.progress = progress.coerceIn(0, 1000)
        }

        AlertDialog.Builder(activity)
            .setTitle(entry.title)
            .setView(content)
            .setPositiveButton("保存日志文件") { _, _ ->
                DiagnosticLogFiles.exportOperationSnapshotNow(activity)
                    .onSuccess { name -> Toast.makeText(activity, "已保存：$name", Toast.LENGTH_LONG).show() }
                    .onFailure { error -> Toast.makeText(activity, error.message ?: "保存日志失败", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showPendingCrashLog(activity: AppCompatActivity) {
        val body = CrashLogStore.readPending(activity)
        if (body.isNullOrBlank()) {
            Toast.makeText(activity, "暂无闪退/崩溃日志", Toast.LENGTH_SHORT).show()
            return
        }
        val text = TextView(activity).apply {
            this.text = body
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 12))
        }
        AlertDialog.Builder(activity)
            .setTitle("闪退/崩溃日志")
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("保存日志文件") { _, _ ->
                DiagnosticLogFiles.exportCrashSnapshotNow(activity)
                    .onSuccess { name -> Toast.makeText(activity, "已保存：$name", Toast.LENGTH_LONG).show() }
                    .onFailure { error -> Toast.makeText(activity, error.message ?: "保存日志失败", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
'''
)

# External book hand-off and delayed shelf decision.
write(
    "app/src/main/java/com/simplereader/app/ui/ExternalBookStore.kt",
    r'''package com.simplereader.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.simplereader.app.data.cache.StructuredBookCache
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.entity.Book
import com.simplereader.app.reader.page.PageCacheStore
import java.io.File

object ExternalBookStore {
    const val STATUS_PENDING = "EXTERNAL_PENDING"
    const val EXTRA_EXTERNAL_PENDING = "externalPendingShelfDecision"
    private const val STALE_PENDING_MS = 24L * 60L * 60L * 1000L

    data class Prepared(val bookId: Long, val pendingDecision: Boolean)

    suspend fun prepare(context: Context, database: SimpleReaderDatabase, uri: Uri): Prepared {
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "外部书籍"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf(String::isNotBlank)?.let { displayName = it }
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
        }
        val mime = resolver.getType(uri).orEmpty()
        val format = when {
            displayName.endsWith(".txt", true) -> "TXT"
            displayName.endsWith(".epub", true) -> "EPUB"
            mime.equals("text/plain", true) -> "TXT"
            mime.equals("application/epub+zip", true) -> "EPUB"
            else -> error("简阅当前只能从其他应用打开 TXT 或 EPUB 文件")
        }
        if (!displayName.contains('.')) displayName += ".${format.lowercase()}"

        val dao = database.bookDao()
        if (size != null) {
            dao.getByFileNameAndSize(displayName, size!!)?.let { existing ->
                if (existing.fileStatus != STATUS_PENDING) return Prepared(existing.id, false)
            }
        }

        cleanupStale(context, database)
        val directory = managedDirectory(context).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120)
        val target = directory.resolve("${System.currentTimeMillis()}_${System.nanoTime()}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("无法读取外部文件")
        if (!target.isFile || target.length() <= 0L) {
            target.delete()
            error("外部文件为空或复制失败")
        }

        val book = Book(
            title = displayName.removeSuffixIgnoreCase(".txt").removeSuffixIgnoreCase(".epub"),
            filePath = target.absolutePath,
            format = format,
            groupId = null,
            fileName = displayName,
            fileSize = target.length(),
            lastModified = target.lastModified().takeIf { it > 0L },
            fileStatus = STATUS_PENDING
        )
        val id = dao.insert(book)
        if (id <= 0L) {
            target.delete()
            error("临时阅读记录创建失败")
        }
        return Prepared(id, true)
    }

    suspend fun confirm(database: SimpleReaderDatabase, bookId: Long) {
        database.bookDao().updateFileStatus(bookId, "AVAILABLE")
    }

    suspend fun discard(context: Context, database: SimpleReaderDatabase, bookId: Long) {
        val book = database.bookDao().getBook(bookId)
        database.withTransaction {
            database.bookmarkDao().deleteByBookId(bookId)
            database.readProgressDao().deleteByBookId(bookId)
            database.bookDao().deleteById(bookId)
        }
        StructuredBookCache.clearBook(context, bookId)
        PageCacheStore.clearBook(context, bookId)
        book?.takeIf { it.fileStatus == STATUS_PENDING }?.let { deleteManagedFile(context, it.filePath) }
    }

    suspend fun cleanupStale(context: Context, database: SimpleReaderDatabase) {
        val cutoff = System.currentTimeMillis() - STALE_PENDING_MS
        database.bookDao().getByFileStatus(STATUS_PENDING)
            .filter { it.addTime in 1 until cutoff }
            .forEach { discard(context, database, it.id) }
    }

    private fun managedDirectory(context: Context): File = File(context.filesDir, "external_books")

    private fun deleteManagedFile(context: Context, path: String) {
        runCatching {
            val root = managedDirectory(context).canonicalFile
            val file = File(path).canonicalFile
            if (file.path.startsWith(root.path + File.separator)) file.delete()
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
'''
)

write(
    "app/src/main/java/com/simplereader/app/ui/ExternalBookOpenActivity.kt",
    r'''package com.simplereader.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.simplereader.app.data.db.SimpleReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExternalBookOpenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val uri = incomingUri(intent)
        if (uri == null) {
            Toast.makeText(this, "没有收到可打开的书籍文件", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val database = SimpleReaderDatabase.getDatabase(this)
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { ExternalBookStore.prepare(this@ExternalBookOpenActivity, database, uri) }
            }
            prepared.onSuccess { result ->
                startActivity(
                    Intent(this@ExternalBookOpenActivity, ReaderActivity::class.java)
                        .putExtra("bookId", result.bookId)
                        .putExtra(ExternalBookStore.EXTRA_EXTERNAL_PENDING, result.pendingDecision)
                )
                finish()
            }.onFailure { error ->
                Toast.makeText(
                    this@ExternalBookOpenActivity,
                    error.message ?: "无法打开外部书籍",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun incomingUri(source: Intent): Uri? {
        source.data?.let { return it }
        source.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }
        return source.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
'''
)

# MainActivity: safe manager replacement, action label, separate log entries and folder settings.
replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    'import com.simplereader.app.operation.OperationLogDialogs\n',
    'import com.simplereader.app.operation.DiagnosticLogFiles\nimport com.simplereader.app.operation.OperationLogDialogs\n'
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    '''    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
''',
    '''    private val crashLogFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            DiagnosticLogFiles.setCrashFolder(this, uri)
            DiagnosticLogFiles.scheduleCrashSnapshot(this)
            Toast.makeText(this, "已设置崩溃日志文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    private val operationLogFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            DiagnosticLogFiles.setOperationFolder(this, uri)
            DiagnosticLogFiles.scheduleOperationSnapshot(this)
            Toast.makeText(this, "已设置操作日志文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
'''
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    '''    private fun showPendingCrashLogIfNeeded() {
        val crashLog = CrashLogStore.consumePendingIntoHistory(this) ?: return
        showCrashLogDetail(crashLog, title = "新异常退出/闪退/崩溃日志")
    }
''',
    '''    private fun showPendingCrashLogIfNeeded() {
        val crashLog = CrashLogStore.consumePendingIntoHistory(this) ?: return
        DiagnosticLogFiles.scheduleCrashSnapshot(this)
        showCrashLogDetail(crashLog, title = "新异常退出/闪退/崩溃日志")
    }
'''
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    '''            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("简阅异常退出日志", crashLog))
                Toast.makeText(this, "日志已复制；历史记录仍保留", Toast.LENGTH_SHORT).show()
            }
''',
    '''            .setPositiveButton("保存日志文件") { _, _ ->
                DiagnosticLogFiles.exportCrashSnapshotNow(this)
                    .onSuccess { name -> Toast.makeText(this, "已保存：$name", Toast.LENGTH_LONG).show() }
                    .onFailure { error -> Toast.makeText(this, error.message ?: "保存日志失败", Toast.LENGTH_LONG).show() }
            }
'''
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    '''    private fun createShelfLayoutManager(): GridLayoutManager {
        return GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (shelfListMode || shelfAdapter.isFullSpan(position)) 3 else 1
            }
        }
    }

    private fun applyShelfLayoutMode(updateButton: Boolean = true) {
        if (!::shelfGrid.isInitialized) return
        val layoutManager = (shelfGrid.layoutManager as? GridLayoutManager)
            ?: createShelfLayoutManager().also { shelfGrid.layoutManager = it }
        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
        layoutManager.spanSizeLookup.invalidateSpanGroupIndexCache()
        shelfGrid.recycledViewPool.clear()
        if (updateButton && ::editButton.isInitialized && !shelfSelectionMode) updateShelfModeButton()
        shelfAdapter.notifyDataSetChanged()
        shelfGrid.requestLayout()
    }

    private fun updateShelfModeButton() {
        if (!::editButton.isInitialized || shelfSelectionMode) return
        editButton.text = if (shelfListMode) "列表" else "宫格"
''',
    '''    private fun createShelfLayoutManager(): RecyclerView.LayoutManager {
        if (shelfListMode) return LinearLayoutManager(this)
        return GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (shelfAdapter.isFullSpan(position)) 3 else 1
            }
        }
    }

    private fun applyShelfLayoutMode(updateButton: Boolean = true) {
        if (!::shelfGrid.isInitialized) return
        val firstVisible = (shelfGrid.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?.takeIf { it >= 0 }
            ?: 0
        shelfGrid.stopScroll()
        shelfGrid.layoutManager = null
        shelfGrid.recycledViewPool.clear()
        shelfGrid.layoutManager = createShelfLayoutManager()
        shelfAdapter.notifyDataSetChanged()
        shelfGrid.scrollToPosition(firstVisible.coerceAtMost((shelfAdapter.itemCount - 1).coerceAtLeast(0)))
        if (updateButton && ::editButton.isInitialized && !shelfSelectionMode) updateShelfModeButton()
        shelfGrid.requestLayout()
    }

    private fun updateShelfModeButton() {
        if (!::editButton.isInitialized || shelfSelectionMode) return
        editButton.text = if (shelfListMode) "宫格" else "列表"
'''
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    '''    private fun showDataExportOptions() {
        AlertDialog.Builder(this)
            .setTitle("数据导出")
            .setItems(arrayOf("导出", "同步", "日志")) { _, which ->
                when (which) {
                    0 -> launchDataExport()
                    1 -> syncDataExport()
                    2 -> OperationLogDialogs.showLogHub(this)
                }
            }
            .show()
    }
''',
    '''    private fun showDataExportOptions() {
        AlertDialog.Builder(this)
            .setTitle("数据导出")
            .setItems(arrayOf("导出", "同步", "崩溃日志", "操作日志", "日志位置设置")) { _, which ->
                when (which) {
                    0 -> launchDataExport()
                    1 -> syncDataExport()
                    2 -> showCrashHistoryDialog()
                    3 -> OperationLogDialogs.showOperationList(this)
                    4 -> showLogLocationSettings()
                }
            }
            .show()
    }

    private fun showLogLocationSettings() {
        val crashLabel = DiagnosticLogFiles.crashFolderLabel(this)
        val operationLabel = DiagnosticLogFiles.operationFolderLabel(this)
        AlertDialog.Builder(this)
            .setTitle("日志位置设置")
            .setItems(
                arrayOf(
                    "崩溃日志文件夹：$crashLabel",
                    "操作日志文件夹：$operationLabel"
                )
            ) { _, which ->
                when (which) {
                    0 -> crashLogFolderLauncher.launch(null)
                    1 -> operationLogFolderLauncher.launch(null)
                }
            }
            .show()
    }

    private fun persistTreePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
'''
)

# ReaderActivity asks only when the external book is actually being exited.
replace_once(
    "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt",
    '    private var zeroProgressOpeningPreviewVisible = false\n',
    '    private var zeroProgressOpeningPreviewVisible = false\n    private var externalShelfDecisionPending = false\n    private var externalDiscardInProgress = false\n    private var externalDecisionDialogShowing = false\n'
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt",
    '        bookId = intent.getLongExtra("bookId", 0L)\n        readerGeneration = ReaderRuntimeState.claim()\n',
    '        bookId = intent.getLongExtra("bookId", 0L)\n        externalShelfDecisionPending = intent.getBooleanExtra(ExternalBookStore.EXTRA_EXTERNAL_PENDING, false)\n        readerGeneration = ReaderRuntimeState.claim()\n'
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt",
    '''        if (ownsReaderSession()) {
            if (pageTurnMode == TURN_MODE_VERTICAL) {
                persistVerticalDiagnosticState("vertical_pause", force = true)
            }
            saveProgress()
        } else {
''',
    '''        if (ownsReaderSession() && !externalDiscardInProgress) {
            if (pageTurnMode == TURN_MODE_VERTICAL) {
                persistVerticalDiagnosticState("vertical_pause", force = true)
            }
            saveProgress()
        } else if (externalDiscardInProgress) {
            CrashLogStore.recordEvent(this, "external_pending_discard_skip_progress book=$bookId")
        } else {
'''
)
replace_once(
    "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt",
    '''    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyTurnEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
''',
    '''    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (externalShelfDecisionPending) {
            showExternalShelfDecision()
        } else {
            super.onBackPressed()
        }
    }

    private fun showExternalShelfDecision() {
        if (externalDecisionDialogShowing || isFinishing || isDestroyed) return
        externalDecisionDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("加入书架？")
            .setMessage("这本书是从其他应用临时打开的。加入书架后会保留在简阅；不加入则删除简阅保存的临时副本。")
            .setPositiveButton("加入书架") { _, _ -> finishExternalBook(keep = true) }
            .setNegativeButton("不加入") { _, _ -> finishExternalBook(keep = false) }
            .setOnCancelListener { externalDecisionDialogShowing = false }
            .show()
    }

    private fun finishExternalBook(keep: Boolean) {
        externalDecisionDialogShowing = false
        if (!keep) externalDiscardInProgress = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (keep) {
                        ExternalBookStore.confirm(database, bookId)
                    } else {
                        ExternalBookStore.discard(this@ReaderActivity, database, bookId)
                    }
                }
            }
            result.onSuccess {
                externalShelfDecisionPending = false
                Toast.makeText(
                    this@ReaderActivity,
                    if (keep) "已加入书架" else "未加入书架",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure { error ->
                if (!keep) externalDiscardInProgress = false
                Toast.makeText(
                    this@ReaderActivity,
                    error.message ?: if (keep) "加入书架失败" else "清理临时书籍失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyTurnEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
'''
)

# Update superseded shelf contract tests and add v789-specific regression coverage.
write(
    "app/src/test/java/com/simplereader/app/ui/ShelfV787ContractTest.kt",
    r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV787ContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()
    private val group = File("src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt").readText()

    @Test fun `top-level groups and books share one activity sort`() {
        assertTrue(main.contains("val topLevelItems = mutableListOf<Pair<Long, ShelfRenderItem>>()"))
        assertTrue(main.contains("topLevelItems += groupBooks.maxOf(::activityTime) to ShelfRenderItem.GroupItem"))
        assertTrue(main.contains("topLevelItems += activityTime(book) to ShelfRenderItem.BookItem(book)"))
        assertTrue(main.contains("compareByDescending<Pair<Long, ShelfRenderItem>> { it.first }"))
    }

    @Test fun `grid-list control remains distinct from selection operation`() {
        assertTrue(layout.contains("android:text=\"列表\""))
        assertTrue(main.contains("private var shelfListMode = false"))
        assertTrue(main.contains("toggleShelfLayoutMode()"))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"宫格\" else \"列表\""))
        assertTrue(main.contains("bookCount > 0 -> \"操作\""))
        assertTrue(main.contains("groupCount == 1 && bookCount == 0 -> \"操作\""))
    }

    @Test fun `main shelf grid uses same portrait height as books inside groups`() {
        assertTrue(group.contains("LinearLayout.LayoutParams.MATCH_PARENT, dp(148)"))
        assertTrue(main.contains("LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148))"))
        assertTrue(main.contains("card.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)))"))
        assertTrue(main.contains("LinearLayout.LayoutParams(dp(72), dp(104))"))
    }

    @Test fun `shelf management no longer duplicates recent crash history`() {
        assertFalse(main.contains("\"异常日志（最近20条）\""))
        assertTrue(main.contains("CrashLogStore.consumePendingIntoHistory(this)"))
        assertTrue(main.contains("setNeutralButton(\"最近20条\")"))
    }
}
'''
)
write(
    "app/src/test/java/com/simplereader/app/ui/ShelfV788LayoutSwitchContractTest.kt",
    r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfV788LayoutSwitchContractTest {
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()

    @Test fun `mode button names the destination layout`() {
        assertTrue(layout.contains("android:text=\"列表\""))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \"宫格\" else \"列表\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }

    @Test fun `list and grid use fresh dedicated layout managers`() {
        assertTrue(main.contains("if (shelfListMode) return LinearLayoutManager(this)"))
        assertTrue(main.contains("return GridLayoutManager(this, 3)"))
        assertTrue(main.contains("shelfGrid.layoutManager = null"))
        assertTrue(main.contains("shelfGrid.recycledViewPool.clear()"))
        assertTrue(main.contains("shelfGrid.layoutManager = createShelfLayoutManager()"))
    }

    @Test fun `mode switch is serialized off the click callback`() {
        val start = main.indexOf("private fun toggleShelfLayoutMode")
        val end = main.indexOf("private fun statusBarHeight", start)
        val block = main.substring(start, end)
        assertTrue(block.contains("shelfLayoutSwitchInFlight"))
        assertTrue(block.contains("shelfGrid.stopScroll()"))
        assertTrue(block.contains("shelfGrid.post"))
        assertTrue(block.contains("shelfListMode = !shelfListMode"))
        assertTrue(block.contains("applyShelfLayoutMode()"))
    }

    @Test fun `selection operation mode remains separate from layout mode`() {
        assertTrue(main.contains("handleShelfSelectionPrimaryAction()"))
        assertTrue(main.contains("bookCount > 0 -> \"操作\""))
        assertTrue(main.contains("groupCount == 1 && bookCount == 0 -> \"操作\""))
    }
}
'''
)
write(
    "app/src/test/java/com/simplereader/app/ui/V789ExternalOpenAndLogContractTest.kt",
    r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V789ExternalOpenAndLogContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val main = File("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()
    private val externalStore = File("src/main/java/com/simplereader/app/ui/ExternalBookStore.kt").readText()
    private val logFiles = File("src/main/java/com/simplereader/app/operation/DiagnosticLogFiles.kt").readText()
    private val dao = File("src/main/java/com/simplereader/app/data/dao/BookDao.kt").readText()

    @Test fun `external TXT and EPUB can be offered to SimpleReader`() {
        assertTrue(manifest.contains(".ui.ExternalBookOpenActivity"))
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("android.intent.action.SEND"))
        assertTrue(manifest.contains("text/plain"))
        assertTrue(manifest.contains("application/epub+zip"))
    }

    @Test fun `external book stays hidden until exit decision`() {
        assertTrue(externalStore.contains("EXTERNAL_PENDING"))
        assertTrue(dao.contains("fileStatus != 'EXTERNAL_PENDING'"))
        assertTrue(reader.contains("setTitle(\"加入书架？\")"))
        assertTrue(reader.contains("setPositiveButton(\"加入书架\")"))
        assertTrue(reader.contains("setNegativeButton(\"不加入\")"))
        assertTrue(reader.contains("ExternalBookStore.discard"))
    }

    @Test fun `data export exposes independent crash and operation log locations`() {
        assertTrue(main.contains("\"崩溃日志\", \"操作日志\", \"日志位置设置\""))
        assertTrue(main.contains("crashLogFolderLauncher"))
        assertTrue(main.contains("operationLogFolderLauncher"))
        assertTrue(logFiles.contains("KEY_CRASH_TREE"))
        assertTrue(logFiles.contains("KEY_OPERATION_TREE"))
        assertTrue(logFiles.contains("简阅_操作日志_当前.txt"))
    }
}
'''
)

# Maintenance log.
write(
    "maintenance/V789_SHELF_LOG_EXTERNAL_OPEN.md",
    '''# v789 · 宫格切换、日志文件与外部打开\n\n## 用户反馈\n1. 点击“宫格”切换时发生闪退。\n2. 日志过长时复制不完整；后续日志以完整 TXT 文件为主，并可分别设置崩溃日志与操作日志目录。\n3. 微信等应用“用其他应用打开”时应可选择简阅；简阅先正常阅读，退出书籍时再询问是否加入书架。\n\n## 实现\n- 列表与宫格不再复用同一个 GridLayoutManager；切换时丢弃旧 LayoutManager/回收池并创建真正的 LinearLayoutManager 或 GridLayoutManager。\n- 默认宫格模式按钮显示“列表”；列表模式按钮显示“宫格”，按钮文字表示点击后的目标。\n- 数据导出菜单拆出“崩溃日志 / 操作日志 / 日志位置设置”。崩溃日志、操作日志可分别选择 SAF 文件夹；操作日志持续镜像为“简阅_操作日志_当前.txt”，崩溃历史可完整导出为时间戳 TXT。\n- 新增 ExternalBookOpenActivity 接收 TXT/EPUB ACTION_VIEW/ACTION_SEND。新外部书复制到 app 私有稳定目录并标记 EXTERNAL_PENDING，书架查询排除该状态。\n- ReaderActivity 退出待决定外部书时弹“加入书架？”；加入则转 AVAILABLE，不加入则删除临时副本、数据库记录、进度与缓存。\n- 已在书架的同名同大小文件从外部打开时直接复用已有记录，不重复创建。\n\n## v789 回归重点\n- 宫格 ↔ 列表反复切换不得崩溃。\n- 长崩溃日志不再依赖剪贴板完整性。\n- 微信/文件管理器外部打开 TXT/EPUB：阅读正常；返回时确认加入/不加入两条路径均正确。\n'''
)

print("v789 patch applied")
