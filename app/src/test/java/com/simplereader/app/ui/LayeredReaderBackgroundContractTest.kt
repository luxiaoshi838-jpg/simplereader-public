package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayeredReaderBackgroundContractTest {
    private val backgrounds = File("src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt").readText()
    private val picker = File("src/main/java/com/simplereader/app/ui/ReaderBackgroundPicker.kt").readText()
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test
    fun colorTextureAndMaterialAreIndependent() {
        assertTrue(backgrounds.contains("val colorId: String"))
        assertTrue(backgrounds.contains("val textureId: String"))
        assertTrue(backgrounds.contains("val materialId: String"))
        assertTrue(reader.contains("selection.copy(colorId = colorId)"))
        assertTrue(reader.contains("PREF_BACKGROUND_TEXTURE_ID"))
        assertTrue(reader.contains("PREF_BACKGROUND_MATERIAL_ID"))
    }

    @Test
    fun textureAndMaterialBothKeepPureOptions() {
        assertTrue(backgrounds.contains("TextureOption(NONE_TEXTURE_ID, "纯净""))
        assertTrue(backgrounds.contains("MaterialOption(NONE_MATERIAL_ID, "纯净""))
        assertFalse(backgrounds.contains("细麻纹"))
        assertFalse(backgrounds.contains("LINEN"))
    }

    @Test
    fun realBitmapLayersAreUsedInsteadOfColorOnlyPresets() {
        assertTrue(backgrounds.contains("reader_texture_paper_grain"))
        assertTrue(backgrounds.contains("reader_texture_paper_fiber"))
        assertTrue(backgrounds.contains("reader_material_frosted"))
        assertTrue(backgrounds.contains("reader_material_patina"))
        assertTrue(backgrounds.contains("BitmapShader"))
        assertTrue(backgrounds.contains("PorterDuffXfermode"))
    }

    @Test
    fun pickerKeepsDialogOpenForLayerCombinations() {
        assertTrue(picker.contains("onSelectionChanged(currentSelection)"))
        assertTrue(picker.contains("阅读背景 · 自由搭配"))
        assertTrue(picker.contains("颜色决定底色"))
        assertFalse(picker.contains("dialog?.dismiss()"))
    }
}
