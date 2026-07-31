from pathlib import Path

reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = reader.read_text(encoding="utf-8")

replacements = []

replacements.append((
'''    private var readerBackgroundStyleId: String = ReaderBackgrounds.DEFAULT_ID
''',
'''    private var readerBackgroundColorId: String = ReaderBackgrounds.DEFAULT_COLOR_ID
    private var readerBackgroundTextureId: String = ReaderBackgrounds.DEFAULT_TEXTURE_ID
    private var readerBackgroundMaterialId: String = ReaderBackgrounds.DEFAULT_MATERIAL_ID
'''))

replacements.append((
'''    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
        val styleId = ReaderBackgrounds.closestId(backgroundColor)
        selectReaderBackground(styleId)
    }

    private fun selectReaderBackground(styleId: String) {
        val preset = ReaderBackgrounds.preset(styleId)
        readerBackgroundStyleId = preset.id
        ReaderAppearance.saveDayPalette(this, preset.backgroundColor, preset.textColor)
        saveReaderPrefs()
        applyActiveReaderMode(ReaderAppearance.palette(this))
    }

    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        val activePreset = if (night) {
            ReaderBackgrounds.nightPreset
        } else {
            ReaderBackgrounds.preset(readerBackgroundStyleId)
        }
        currentBackgroundColor = if (night) palette.backgroundColor else activePreset.backgroundColor
        currentTextColor = if (night) palette.textColor else activePreset.textColor
        contentView.background = ReaderBackgrounds.drawable(activePreset)
        contentView.setTextColor(currentTextColor)
        readerProgressLabel.setTextColor(currentTextColor)
        readerScrollView.setBackgroundColor(currentBackgroundColor)
        window.decorView.setBackgroundColor(currentBackgroundColor)
        updateThemeControls()
    }
''',
'''    private fun currentReaderBackgroundSelection(): ReaderBackgrounds.Selection =
        ReaderBackgrounds.validated(
            ReaderBackgrounds.Selection(
                colorId = readerBackgroundColorId,
                textureId = readerBackgroundTextureId,
                materialId = readerBackgroundMaterialId
            )
        )

    private fun applyReaderPalette(backgroundColor: Int, _textColor: Int) {
        selectReaderBackground(
            currentReaderBackgroundSelection().copy(
                colorId = ReaderBackgrounds.closestColorId(backgroundColor)
            )
        )
    }

    private fun selectReaderBackground(selection: ReaderBackgrounds.Selection) {
        val safe = ReaderBackgrounds.validated(selection)
        readerBackgroundColorId = safe.colorId
        readerBackgroundTextureId = safe.textureId
        readerBackgroundMaterialId = safe.materialId
        val color = ReaderBackgrounds.color(safe.colorId)
        ReaderAppearance.saveDayPalette(this, color.backgroundColor, color.textColor)
        saveReaderPrefs()
        applyActiveReaderMode(ReaderAppearance.palette(this))
    }

    private fun selectReaderBackgroundColor(colorId: String) {
        selectReaderBackground(currentReaderBackgroundSelection().copy(colorId = colorId))
    }

    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        val selection = currentReaderBackgroundSelection()
        val selectedColor = ReaderBackgrounds.color(selection.colorId)
        currentBackgroundColor = if (night) palette.backgroundColor else selectedColor.backgroundColor
        currentTextColor = if (night) palette.textColor else selectedColor.textColor
        contentView.background = if (night) {
            ReaderBackgrounds.nightDrawable(this)
        } else {
            ReaderBackgrounds.drawable(this, selection)
        }
        contentView.setTextColor(currentTextColor)
        readerProgressLabel.setTextColor(currentTextColor)
        readerScrollView.setBackgroundColor(currentBackgroundColor)
        window.decorView.setBackgroundColor(currentBackgroundColor)
        updateThemeControls()
    }
'''))

replacements.append((
'''    private fun loadReaderPrefs() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSize = prefs.getFloat(PREF_TEXT_SIZE, 18f)
        val palette = ReaderAppearance.palette(this)
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        readerBackgroundStyleId = prefs.getString(PREF_BACKGROUND_STYLE, null)
            ?: ReaderBackgrounds.closestId(palette.backgroundColor)
        readerChromeActivationMode = prefs.getString(
            PREF_CHROME_ACTIVATION,
            CHROME_ACTIVATION_CENTER
        ) ?: CHROME_ACTIVATION_CENTER
        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP
        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
    }

    private fun saveReaderPrefs() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSize)
            .putString(PREF_BACKGROUND_STYLE, readerBackgroundStyleId)
            .putString(PREF_CHROME_ACTIVATION, readerChromeActivationMode)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .apply()
    }
''',
'''    private fun loadReaderPrefs() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSize = prefs.getFloat(PREF_TEXT_SIZE, 18f)
        val palette = ReaderAppearance.palette(this)
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        val migrated = ReaderBackgrounds.selectionFromLegacy(
            styleId = prefs.getString(PREF_BACKGROUND_STYLE, null),
            paletteBackground = palette.backgroundColor
        )
        val loadedSelection = ReaderBackgrounds.validated(
            ReaderBackgrounds.Selection(
                colorId = prefs.getString(PREF_BACKGROUND_COLOR_ID, migrated.colorId) ?: migrated.colorId,
                textureId = prefs.getString(PREF_BACKGROUND_TEXTURE_ID, migrated.textureId) ?: migrated.textureId,
                materialId = prefs.getString(PREF_BACKGROUND_MATERIAL_ID, migrated.materialId) ?: migrated.materialId
            )
        )
        readerBackgroundColorId = loadedSelection.colorId
        readerBackgroundTextureId = loadedSelection.textureId
        readerBackgroundMaterialId = loadedSelection.materialId
        readerChromeActivationMode = prefs.getString(
            PREF_CHROME_ACTIVATION,
            CHROME_ACTIVATION_CENTER
        ) ?: CHROME_ACTIVATION_CENTER
        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP
        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
    }

    private fun saveReaderPrefs() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSize)
            .putString(PREF_BACKGROUND_COLOR_ID, readerBackgroundColorId)
            .putString(PREF_BACKGROUND_TEXTURE_ID, readerBackgroundTextureId)
            .putString(PREF_BACKGROUND_MATERIAL_ID, readerBackgroundMaterialId)
            .putString(PREF_CHROME_ACTIVATION, readerChromeActivationMode)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .apply()
    }
'''))

replacements.append((
'''        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            selectReaderBackground(ReaderBackgrounds.DEFAULT_ID)
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            selectReaderBackground("solid_eye")
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            selectReaderBackground("solid_white")
        }
''',
'''        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            selectReaderBackgroundColor(ReaderBackgrounds.DEFAULT_COLOR_ID)
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            selectReaderBackgroundColor("solid_eye")
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            selectReaderBackgroundColor("solid_white")
        }
'''))

replacements.append((
'''    private fun showReaderBackgroundPicker() {
        ReaderBackgroundPicker.show(
            activity = this,
            selectedId = readerBackgroundStyleId
        ) { preset ->
            selectReaderBackground(preset.id)
        }
    }
''',
'''    private fun showReaderBackgroundPicker() {
        ReaderBackgroundPicker.show(
            activity = this,
            selected = currentReaderBackgroundSelection()
        ) { selection ->
            selectReaderBackground(selection)
        }
    }
'''))

replacements.append((
'''    private fun updateThemeControls() {
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        val quickThemes = listOf(
            R.id.themePaperButton to ReaderBackgrounds.DEFAULT_ID,
            R.id.themeEyeButton to "solid_eye",
            R.id.themeWhiteButton to "solid_white"
        )
        quickThemes.forEach { (id, styleId) ->
            val selected = !night && readerBackgroundStyleId == styleId
            findViewById<TextView>(id).apply {
                isEnabled = true
                alpha = 1f
                gravity = Gravity.CENTER
                text = if (selected) "✓" else ""
                setTextColor(ReaderBackgrounds.preset(styleId).textColor)
                background = ReaderBackgrounds.previewDrawable(styleId, selected)
            }
        }
        findViewById<TextView>(R.id.themeMoreButton).apply {
            setBackgroundColor(Color.rgb(74, 72, 66))
            setTextColor(Color.WHITE)
        }
        findViewById<TextView>(R.id.themeNightButton).apply {
            text = if (night) "☀" else "☾"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (night) Color.rgb(239, 122, 40) else Color.BLACK)
        }
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
        findViewById<TextView>(R.id.chromeCenterButton).setBackgroundColor(
            if (readerChromeActivationMode == CHROME_ACTIVATION_CENTER) Color.rgb(239, 122, 40)
            else Color.rgb(74, 72, 66)
        )
        findViewById<TextView>(R.id.chromeLongPressButton).setBackgroundColor(
            if (readerChromeActivationMode == CHROME_ACTIVATION_LONG_PRESS) Color.rgb(239, 122, 40)
            else Color.rgb(74, 72, 66)
        )
    }
''',
'''    private fun updateThemeControls() {
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        val selection = currentReaderBackgroundSelection()
        val quickColors = listOf(
            R.id.themePaperButton to ReaderBackgrounds.DEFAULT_COLOR_ID,
            R.id.themeEyeButton to "solid_eye",
            R.id.themeWhiteButton to "solid_white"
        )
        quickColors.forEach { (id, colorId) ->
            val selected = !night && selection.colorId == colorId
            val previewSelection = selection.copy(colorId = colorId)
            findViewById<TextView>(id).apply {
                isEnabled = true
                alpha = 1f
                gravity = Gravity.CENTER
                text = if (selected) "✓" else ""
                setTextColor(ReaderBackgrounds.color(colorId).textColor)
                background = ReaderBackgrounds.previewDrawable(
                    context = this@ReaderActivity,
                    selection = previewSelection,
                    selected = selected
                )
            }
        }
        findViewById<TextView>(R.id.themeMoreButton).apply {
            setBackgroundColor(Color.rgb(74, 72, 66))
            setTextColor(Color.WHITE)
        }
        findViewById<TextView>(R.id.themeNightButton).apply {
            text = if (night) "☀" else "☾"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (night) Color.rgb(239, 122, 40) else Color.BLACK)
        }
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
        findViewById<TextView>(R.id.chromeCenterButton).setBackgroundColor(
            if (readerChromeActivationMode == CHROME_ACTIVATION_CENTER) Color.rgb(239, 122, 40)
            else Color.rgb(74, 72, 66)
        )
        findViewById<TextView>(R.id.chromeLongPressButton).setBackgroundColor(
            if (readerChromeActivationMode == CHROME_ACTIVATION_LONG_PRESS) Color.rgb(239, 122, 40)
            else Color.rgb(74, 72, 66)
        )
    }
'''))

replacements.append((
'''        private const val PREF_BACKGROUND_STYLE = "background_style_v582"
        private const val PREF_CHROME_ACTIVATION = "chrome_activation_v582"
''',
'''        private const val PREF_BACKGROUND_STYLE = "background_style_v582"
        private const val PREF_BACKGROUND_COLOR_ID = "background_color_v585"
        private const val PREF_BACKGROUND_TEXTURE_ID = "background_texture_v585"
        private const val PREF_BACKGROUND_MATERIAL_ID = "background_material_v585"
        private const val PREF_CHROME_ACTIVATION = "chrome_activation_v582"
'''))

for old, new in replacements:
    if old not in text:
        raise SystemExit(f"required ReaderActivity block not found: {old[:90]!r}")
    text = text.replace(old, new, 1)

text = text.replace(
    'val items = arrayOf("字号减小", "字号增大", "纸张背景（日间）", "护眼背景（日间）", "白色背景（日间）", "切换日间 / 夜间")',
    'val items = arrayOf("字号减小", "字号增大", "象牙色（日间）", "护眼背景（日间）", "白色背景（日间）", "切换日间 / 夜间")',
    1
)

for forbidden in (
    "readerBackgroundStyleId",
    "ReaderBackgrounds.DEFAULT_ID",
    "ReaderBackgrounds.closestId(",
    "ReaderBackgrounds.preset("
):
    if forbidden in text:
        raise SystemExit(f"legacy background reference remains: {forbidden}")

reader.write_text(text, encoding="utf-8")

test = Path("app/src/test/java/com/simplereader/app/ui/LayeredReaderBackgroundContractTest.kt")
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package com.simplereader.app.ui

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
        assertTrue(backgrounds.contains("TextureOption(NONE_TEXTURE_ID, \"纯净\""))
        assertTrue(backgrounds.contains("MaterialOption(NONE_MATERIAL_ID, \"纯净\""))
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
''', encoding="utf-8")

print("v585 layered background source materialized")
