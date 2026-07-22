package com.simplereader.app.parser

import android.text.Html
import org.jchmlib.ChmFile
import org.jchmlib.ChmUnitInfo
import java.io.File
import java.io.InputStream

/**
 * CHM text reader backed by chimenchen/jchmlib v0.5.4 (Apache-2.0).
 * The SAF stream is copied to a temporary file because ChmFile uses random access.
 */
object ChmParser {
    private const val MAX_DOCUMENT_ENTRIES = 4000
    private const val MAX_TOTAL_TEXT_CHARS = 12 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024L

    fun readText(inputStream: InputStream): String {
        val temporaryFile = File.createTempFile("simplereader_", ".chm")
        return try {
            temporaryFile.outputStream().buffered().use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }
            readWithJChm(temporaryFile)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun readWithJChm(file: File): String {
        val archive = ChmFile(file.absolutePath)
        val entries = mutableListOf<ChmUnitInfo>()
        archive.enumerate(
            ChmFile.CHM_ENUMERATE_NORMAL or ChmFile.CHM_ENUMERATE_FILES
        ) { unit ->
            val path = unit.path.orEmpty()
            if (
                unit.length in 1L..MAX_ENTRY_BYTES &&
                isReadableDocument(path) &&
                entries.size < MAX_DOCUMENT_ENTRIES
            ) {
                entries += unit
            }
        }

        entries.sortWith(
            compareBy<ChmUnitInfo>({ entryPriority(it.path.orEmpty()) }, { it.path.orEmpty().lowercase() })
        )

        val combined = StringBuilder()
        for (entry in entries) {
            if (combined.length >= MAX_TOTAL_TEXT_CHARS) break
            val source = archive.retrieveObjectAsString(entry).orEmpty()
            if (source.isBlank()) continue
            val text = htmlOrPlainText(source)
            if (text.isBlank()) continue
            if (combined.isNotEmpty()) combined.append("\n\n")
            combined.append(text)
        }

        return combined.toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { error("CHM 中没有读取到可显示内容") }
    }

    private fun htmlOrPlainText(source: String): String {
        val sample = source.take(4096).lowercase()
        val isHtml = "<html" in sample || "<body" in sample || "<p" in sample || "<div" in sample
        return if (isHtml) {
            Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            source
        }
    }

    private fun isReadableDocument(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/#") || path.startsWith("/$") || path.startsWith("::")) {
            return false
        }
        val normalized = path.substringBefore('?').lowercase()
        return normalized.endsWith(".htm") ||
            normalized.endsWith(".html") ||
            normalized.endsWith(".xhtml") ||
            normalized.endsWith(".txt")
    }

    private fun entryPriority(path: String): Int {
        val normalized = path.lowercase()
        return when {
            normalized.contains("index") -> 0
            normalized.contains("default") -> 1
            normalized.endsWith(".htm") || normalized.endsWith(".html") -> 2
            else -> 3
        }
    }
}
