package com.simplereader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** User-supplied Duokan cover and reading background materials. */
object DuokanAssets {
    enum class Asset {
        COVER_TXT, COVER_EPUB,
        TEXTURE_WHITE, TEXTURE_BLUE, TEXTURE_GREEN, TEXTURE_YELLOW,
        MATERIAL_WHITE, MATERIAL_BLUE, MATERIAL_GREEN, MATERIAL_YELLOW
    }

    private val cache = ConcurrentHashMap<Asset, Bitmap>()

    fun bitmap(asset: Asset): Bitmap? = cache[asset] ?: runCatching {
        val bytes = Base64.decode(encoded(asset), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()?.also { cache[asset] = it }

    private fun encoded(asset: Asset): String = when (asset) {
        Asset.COVER_TXT -> DuokanCoverData.TXT
        Asset.COVER_EPUB -> DuokanCoverData.EPUB
        Asset.TEXTURE_WHITE -> DuokanTextureData.WHITE
        Asset.TEXTURE_BLUE -> DuokanTextureData.BLUE
        Asset.TEXTURE_GREEN -> DuokanTextureData.GREEN
        Asset.TEXTURE_YELLOW -> DuokanTextureData.YELLOW
        Asset.MATERIAL_WHITE -> DuokanMaterialDataA.WHITE
        Asset.MATERIAL_BLUE -> DuokanMaterialDataA.BLUE
        Asset.MATERIAL_GREEN -> DuokanMaterialDataB.GREEN
        Asset.MATERIAL_YELLOW -> DuokanMaterialDataB.YELLOW
    }
}
