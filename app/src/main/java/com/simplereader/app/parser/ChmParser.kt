package com.simplereader.app.parser

import android.net.Uri
import android.text.Html
import org.jchmlib.ChmFile
import org.jchmlib.ChmTopicsTree
import org.jchmlib.ChmUnitInfo
import java.io.File
import java.io.InputStream

/**
 * CHM reader backed by chimenchen/jchmlib v0.5.4.
 * Uses jchmlib's topics tree, title map and detected archive encoding.
 */
data class ChmChapter(val path: String, val title: String)

object ChmParser {
    private const val MAX_DOCUMENT_ENTRIES = 4000
    private const val MAX_TOTAL_TEXT_CHARS = 12 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024L

    fun readText(inputStream: InputStream, cacheDirectory: File): String {
        return withTemporaryChm(inputStream, cacheDirectory) { archive ->
            val combined = StringBuilder()
            chapterEntries(archive).forEach { chapter ->
                if (combined.length >= MAX_TOTAL_TEXT_CHARS) return@forEach
                val text = readEntryText(archive, chapter.path)
                if (text.isBlank()) return@forEach
                if (combined.isNotEmpty()) combined.append("\n\n")
                combined.append(text)
            }
            cleanText(combined.toString()).ifBlank { error("CHM 中没有读取到可显示内容") }
        }
    }

    fun readChapterIndex(inputStream: InputStream, cacheDirectory: File): List<ChmChapter> =
        withTemporaryChm(inputStream, cacheDirectory, ::chapterEntries)

    fun readChapterText(inputStream: InputStream, chapterPath: String, cacheDirectory: File): String =
        withTemporaryChm(inputStream, cacheDirectory) { archive -> readEntryText(archive, chapterPath) }

    private fun chapterEntries(archive: ChmFile): List<ChmChapter> {
        val result = mutableListOf<ChmChapter>()
        val seen = linkedSetOf<String>()

        fun add(pathValue: String?, titleValue: String?) {
            val path = normalizePath(pathValue.orEmpty())
            if (path.isBlank() || !isReadableDocument(path)) return
            if (!seen.add(path.lowercase())) return
            val resolved = resolveEntry(archive, path) ?: return
            if (resolved.length !in 1L..MAX_ENTRY_BYTES) return
            val title = titleValue?.trim()?.takeIf { it.isNotBlank() && it != "<Top>" }
                ?: archive.getTitleOfObject(resolved.path.orEmpty())
                    ?.trim()?.takeIf { it.isNotBlank() && it != resolved.path }
                ?: path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path }
            result += ChmChapter(resolved.path.orEmpty(), title)
        }

        fun walk(node: ChmTopicsTree?) {
            node?.children?.forEach { child ->
                add(child.path, child.title)
                walk(child)
            }
        }

        runCatching { walk(archive.topicsTree) }
        if (result.isEmpty()) {
            archive.homeFile?.let { add(it, archive.title) }
            readableEntries(archive).forEach { entry ->
                add(entry.path, archive.getTitleOfObject(entry.path.orEmpty()))
            }
        }
        return result.take(MAX_DOCUMENT_ENTRIES)
    }

    private fun readEntryText(archive: ChmFile, path: String): String {
        val entry = resolveEntry(archive, path) ?: return ""
        if (entry.length !in 1L..MAX_ENTRY_BYTES) return ""
        return cleanText(htmlOrPlainText(archive.retrieveObjectAsString(entry).orEmpty()))
    }

    private fun resolveEntry(archive: ChmFile, rawPath: String): ChmUnitInfo? {
        val normalized = normalizePath(rawPath)
        val candidates = linkedSetOf(
            rawPath.substringBefore('#').substringBefore('?'),
            normalized,
            Uri.decode(normalized)
        ).filter { it.isNotBlank() }
        candidates.forEach { candidate -> archive.resolveObject(candidate)?.let { return it } }
        val target = normalizePath(Uri.decode(normalized)).lowercase()
        return readableEntries(archive).firstOrNull {
            normalizePath(Uri.decode(it.path.orEmpty())).lowercase() == target
        }
    }

    private fun readableEntries(archive: ChmFile): List<ChmUnitInfo> {
        val entries = mutableListOf<ChmUnitInfo>()
        archive.enumerate(ChmFile.CHM_ENUMERATE_NORMAL or ChmFile.CHM_ENUMERATE_FILES) { unit ->
            val path = unit.path.orEmpty()
            if (unit.length in 1L..MAX_ENTRY_BYTES && isReadableDocument(path) && entries.size < MAX_DOCUMENT_ENTRIES) {
                entries += unit
            }
        }
        return entries
    }

    private fun <T> withTemporaryChm(
        inputStream: InputStream,
        cacheDirectory: File,
        block: (ChmFile) -> T
    ): T {
        cacheDirectory.mkdirs()
        val temporaryFile = File.createTempFile("simplereader_", ".chm", cacheDirectory)
        return try {
            temporaryFile.outputStream().buffered().use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }
            block(ChmFile(temporaryFile.absolutePath))
        } finally {
            temporaryFile.delete()
        }
    }

    private fun htmlOrPlainText(source: String): String {
        val sample = source.take(4096).lowercase()
        val isHtml = "<html" in sample || "<body" in sample || "<p" in sample || "<div" in sample
        return if (isHtml) Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY).toString() else source
    }

    private fun cleanText(text: String): String = text
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun normalizePath(value: String): String {
        val cleaned = value.substringBefore('#').substringBefore('?').replace('\\', '/').trim()
        if (cleaned.isBlank()) return ""
        return if (cleaned.startsWith('/')) cleaned else "/$cleaned"
    }

    private fun isReadableDocument(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/#") || path.startsWith("/$") || path.startsWith("::")) return false
        val normalized = path.substringBefore('?').lowercase()
        return normalized.endsWith(".htm") || normalized.endsWith(".html") || normalized.endsWith(".xhtml") || normalized.endsWith(".txt")
    }
}
