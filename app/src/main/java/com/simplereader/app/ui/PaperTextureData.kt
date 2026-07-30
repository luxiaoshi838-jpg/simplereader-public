package com.simplereader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/** 用户确认的第二版纸张纹理，仅供普通封面绘制。 */
internal object PaperTextureData {
    private val bitmap: Bitmap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val encoded = buildString {
            append(PaperTextureChunk00.DATA)
            append(PaperTextureChunk01.DATA)
            append(PaperTextureChunk02.DATA)
            append(PaperTextureChunk03.DATA)
            append(PaperTextureChunk04.DATA)
            append(PaperTextureChunk05.DATA)
            append(PaperTextureChunk06.DATA)
        }
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "纸质封面纹理解码失败"
        }
    }

    fun bitmap(): Bitmap = bitmap
}
