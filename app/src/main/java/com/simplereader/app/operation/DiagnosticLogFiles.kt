package com.simplereader.app.operation

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
