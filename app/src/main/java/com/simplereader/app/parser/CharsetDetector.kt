package com.simplereader.app.parser

import org.mozilla.universalchardet.UniversalDetector
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Charset detection backed by Mozilla universalchardet. */
object CharsetDetector {
    fun detectCharset(bytes: ByteArray): Charset {
        detectBom(bytes)?.let { return it }

        val detectedName = ByteArrayInputStream(bytes).use(UniversalDetector::detectCharset)
        if (!detectedName.isNullOrBlank()) {
            runCatching { Charset.forName(normalizeCharsetName(detectedName)) }
                .getOrNull()
                ?.let { return it }
        }

        // Deterministic fallbacks for uncommon or inconclusive files.
        val utf8 = Charset.forName("UTF-8")
        if (canDecodeStrictly(bytes, utf8)) return utf8

        val gb18030 = Charset.forName("GB18030")
        if (canDecodeStrictly(bytes, gb18030)) return gb18030

        return utf8
    }

    private fun normalizeCharsetName(name: String): String {
        return when (name.uppercase()) {
            "GB2312", "HZ-GB-2312", "GBK" -> "GB18030"
            else -> name
        }
    }

    private fun detectBom(bytes: ByteArray): Charset? {
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> Charset.forName("UTF-8")

            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> Charset.forName("UTF-16LE")

            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> Charset.forName("UTF-16BE")

            else -> null
        }
    }

    private fun canDecodeStrictly(bytes: ByteArray, charset: Charset): Boolean {
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
