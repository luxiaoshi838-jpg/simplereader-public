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
    fun originalBasicColorsAreRestored() {
        assertTrue(backgrounds.contains("ColorOption(\"solid_ivory\", \"纸张\", Color.rgb(245, 233, 200), Color.rgb(59, 52, 40))"))
        assertTrue(backgrounds.contains("ColorOption(\"solid_eye\", \"护眼\", Color.rgb(218, 238, 205), Color.rgb(48, 60, 42))"))
        assertTrue(backgrounds.contains("ColorOption(\"solid_white\", \"白色\", Color.WHITE, Color.rgb(35, 35, 35))"))
    }

    @Test
    fun textureAndMaterialBothKeepPureOptions() {
        assertTrue(backgrounds.contains("TextureOption(NONE_TEXTURE_ID"))
        assertTrue(backgrounds.contains("MaterialOption(NONE_MATERIAL_ID"))
        assertTrue(Regex("纯净").findAll(backgrounds).count() >= 2)
        assertFalse(backgrounds.contains("细麻纹"))
        assertFalse(backgrounds.contains("LINEN"))
    }

    @Test
    fun textureUsesNeutralLuminanceAndBalancedStrength() {
        assertTrue(backgrounds.contains("alpha = 118"))
        assertTrue(backgrounds.contains("alpha = 126"))
        assertTrue(backgrounds.contains("contrast = 0.42f"))
        assertTrue(backgrounds.contains("contrast = 0.48f"))
        assertTrue(backgrounds.contains("neutralTexture"))
        assertTrue(backgrounds.contains("val mean ="))
        assertTrue(backgrounds.contains("coerceIn(96, 160)"))
        assertTrue(backgrounds.contains("PorterDuff.Mode.OVERLAY"))
        assertFalse(backgrounds.contains("PorterDuff.Mode.MULTIPLY"))
    }

    @Test
    fun removedMaterialHasNoImplementation() {
        assertTrue(backgrounds.contains("reader_texture_paper_grain"))
        assertTrue(backgrounds.contains("reader_texture_paper_fiber"))
        assertTrue(backgrounds.contains("reader_material_frosted"))
        assertFalse(backgrounds.contains("reader_material_patina"))
        assertFalse(backgrounds.contains("PATINA"))
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
