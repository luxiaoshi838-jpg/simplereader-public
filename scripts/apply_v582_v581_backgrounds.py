from pathlib import Path


def replace_once(text: str, old: str, new: str, name: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"拒绝修改：{name}目标块应匹配 1 次，实际 {count} 次")
    return text.replace(old, new, 1)


reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = reader.read_text(encoding="utf-8-sig")

v575_theme = """    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        contentView.setBackgroundColor(palette.backgroundColor)
        contentView.setTextColor(palette.textColor)
        window.decorView.setBackgroundColor(palette.backgroundColor)
        updateThemeControls()
    }
"""
v581_theme = """    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        contentView.background = PaperPageDrawable(palette.backgroundColor)
        contentView.setTextColor(palette.textColor)
        readerProgressLabel.setTextColor(palette.textColor)
        readerScrollView.setBackgroundColor(palette.backgroundColor)
        window.decorView.setBackgroundColor(palette.backgroundColor)
        updateThemeControls()
    }
"""
if "contentView.background = PaperPageDrawable(palette.backgroundColor)" not in text and "ReaderBackgrounds.drawable(activePreset)" not in text:
    text = replace_once(text, v575_theme, v581_theme, "v581 阅读页纹理与页码颜色")

old_rendered_pages = """    private fun renderedPageCountLabel(): String? {
        if (pageTurnMode != TURN_MODE_VERTICAL) return null
        val viewportHeight = readerScrollView.height.coerceAtLeast(0)
        val contentHeight = contentView.height.coerceAtLeast(0)
        if (viewportHeight <= 0 || contentHeight <= 0) return null
        val pageHeight = viewportHeight.coerceAtLeast(1)
        val totalPages = ((contentHeight + pageHeight - 1) / pageHeight).coerceAtLeast(1)
        val currentPage = (readerScrollView.scrollY / pageHeight + 1).coerceIn(1, totalPages)
        return "$currentPage/$totalPages"
    }
"""
new_rendered_pages = """    private fun renderedPageCountLabel(): String? {
        // 右下角与目录只使用 pageSize=2000 的同一分页结果，不按屏幕高度另算。
        return pageCountLabel()
    }
"""
if "return pageCountLabel()" not in text:
    text = replace_once(text, old_rendered_pages, new_rendered_pages, "v581 目录页码统一")

old_fields = """    private var currentBackgroundColor: Int = Color.rgb(245, 233, 200)
    private var currentTextColor: Int = Color.rgb(59, 52, 40)
    private var pageTurnMode: String = TURN_MODE_OVERLAP
"""
new_fields = """    private var currentBackgroundColor: Int = Color.rgb(245, 233, 200)
    private var currentTextColor: Int = Color.rgb(59, 52, 40)
    private var readerBackgroundStyleId: String = ReaderBackgrounds.DEFAULT_ID
    private var readerChromeActivationMode: String = CHROME_ACTIVATION_CENTER
    private var pageTurnMode: String = TURN_MODE_OVERLAP
"""
text = replace_once(text, old_fields, new_fields, "v582 背景与唤起状态", "private var readerBackgroundStyleId")

old_palette = """    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
        ReaderAppearance.saveDayPalette(this, backgroundColor, textColor)
        applyActiveReaderMode(ReaderAppearance.palette(this))
    }

"""
new_palette = """    private fun applyReaderPalette(backgroundColor: Int, textColor: Int) {
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

"""
text = replace_once(text, old_palette, new_palette, "v582 背景选择入口", "private fun selectReaderBackground")

advanced_theme = """    private fun applyActiveReaderMode(palette: ReaderAppearance.Palette) {
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
"""
if "ReaderBackgrounds.drawable(activePreset)" not in text:
    if v581_theme in text:
        text = replace_once(text, v581_theme, advanced_theme, "v582 多类型背景渲染")
    elif v575_theme in text:
        text = replace_once(text, v575_theme, advanced_theme, "v582 多类型背景渲染")
    else:
        raise SystemExit("拒绝修改：无法定位 applyActiveReaderMode")

old_load = """    private fun loadReaderPrefs() {
        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)
        readerTextSize = prefs.getFloat(PREF_TEXT_SIZE, 18f)
        val palette = ReaderAppearance.palette(this)
        currentBackgroundColor = palette.backgroundColor
        currentTextColor = palette.textColor
        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP
        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)
    }
"""
new_load = """    private fun loadReaderPrefs() {
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
"""
text = replace_once(text, old_load, new_load, "v582 读取背景与唤起偏好", "prefs.getString(PREF_BACKGROUND_STYLE")

old_save = """    private fun saveReaderPrefs() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSize)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .apply()
    }
"""
new_save = """    private fun saveReaderPrefs() {
        getSharedPreferences(READER_PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_TEXT_SIZE, readerTextSize)
            .putString(PREF_BACKGROUND_STYLE, readerBackgroundStyleId)
            .putString(PREF_CHROME_ACTIVATION, readerChromeActivationMode)
            .putString(PREF_TURN_MODE, pageTurnMode)
            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)
            .apply()
    }
"""
text = replace_once(text, old_save, new_save, "v582 保存背景与唤起偏好", ".putString(PREF_BACKGROUND_STYLE")

old_theme_listeners = """        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            applyReaderPalette(Color.rgb(245, 233, 200), Color.rgb(59, 52, 40))
            saveReaderPrefs()
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            applyReaderPalette(Color.rgb(218, 238, 205), Color.rgb(48, 60, 42))
            saveReaderPrefs()
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            applyReaderPalette(Color.WHITE, Color.rgb(35, 35, 35))
            saveReaderPrefs()
        }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            applyActiveReaderMode(ReaderAppearance.toggleMode(this))
        }
"""
new_theme_listeners = """        findViewById<TextView>(R.id.themePaperButton).setOnClickListener {
            selectReaderBackground(ReaderBackgrounds.DEFAULT_ID)
        }
        findViewById<TextView>(R.id.themeEyeButton).setOnClickListener {
            selectReaderBackground("solid_eye")
        }
        findViewById<TextView>(R.id.themeWhiteButton).setOnClickListener {
            selectReaderBackground("solid_white")
        }
        findViewById<TextView>(R.id.themeNightButton).setOnClickListener {
            applyActiveReaderMode(ReaderAppearance.toggleMode(this))
        }
        findViewById<TextView>(R.id.themeMoreButton).setOnClickListener {
            showReaderBackgroundPicker()
        }
        findViewById<TextView>(R.id.chromeCenterButton).setOnClickListener {
            setReaderChromeActivationMode(CHROME_ACTIVATION_CENTER)
        }
        findViewById<TextView>(R.id.chromeLongPressButton).setOnClickListener {
            setReaderChromeActivationMode(CHROME_ACTIVATION_LONG_PRESS)
        }
"""
text = replace_once(text, old_theme_listeners, new_theme_listeners, "v582 快捷背景与唤起监听", "R.id.themeMoreButton")

insert_after_panel = """        updateSettingsLabels()
    }

    private fun toggleReaderSettingsPanel() {
"""
insert_panel_methods = """        updateSettingsLabels()
    }

    private fun showReaderBackgroundPicker() {
        ReaderBackgroundPicker.show(
            activity = this,
            selectedId = readerBackgroundStyleId
        ) { preset ->
            selectReaderBackground(preset.id)
        }
    }

    private fun setReaderChromeActivationMode(mode: String) {
        readerChromeActivationMode = mode
        saveReaderPrefs()
        updateSettingsLabels()
        Toast.makeText(
            this,
            if (mode == CHROME_ACTIVATION_LONG_PRESS) "菜单唤起：全屏长按" else "菜单唤起：中央单击",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleReaderSettingsPanel() {
"""
text = replace_once(text, insert_after_panel, insert_panel_methods, "v582 背景完整页与唤起方式", "private fun showReaderBackgroundPicker")

old_theme_controls = """    private fun updateThemeControls() {
        val night = ReaderAppearance.currentMode(this) == ReaderAppearance.MODE_NIGHT
        listOf(R.id.themePaperButton, R.id.themeEyeButton, R.id.themeWhiteButton).forEach { id ->
            findViewById<TextView>(id).apply {
                isEnabled = !night
                alpha = if (night) 0.35f else 1f
            }
        }
        findViewById<TextView>(R.id.themeNightButton).apply {
            text = if (night) "☀" else "☾"
            setTextColor(Color.WHITE)
        }
        findViewById<TextView>(R.id.nightButton).text = if (night) "☀" else "☾"
    }
"""
new_theme_controls = """    private fun updateThemeControls() {
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
"""
text = replace_once(text, old_theme_controls, new_theme_controls, "v582 背景及唤起选中态", "ReaderBackgrounds.previewDrawable")

old_tap_condition = """                if (movedX <= tapSlop &&
                    movedY <= tapSlop &&
                    duration <= READER_CHROME_TAP_TIMEOUT_MS &&
                    isReaderChromeCenter(event.rawX, event.rawY)
                ) {
"""
new_tap_condition = """                if (readerChromeActivationMode == CHROME_ACTIVATION_CENTER &&
                    movedX <= tapSlop &&
                    movedY <= tapSlop &&
                    duration <= READER_CHROME_TAP_TIMEOUT_MS &&
                    isReaderChromeCenter(event.rawX, event.rawY)
                ) {
"""
text = replace_once(text, old_tap_condition, new_tap_condition, "v582 中央单击唤起门禁", "readerChromeActivationMode == CHROME_ACTIVATION_CENTER &&")

old_long_press = """    override fun onLongPress(e: MotionEvent) {}
"""
new_long_press = """    override fun onLongPress(e: MotionEvent) {
        if (readerChromeActivationMode == CHROME_ACTIVATION_LONG_PRESS) {
            contentView.clearFocus()
            toggleReaderChrome()
        }
    }
"""
text = replace_once(text, old_long_press, new_long_press, "v582 全屏长按唤起", "readerChromeActivationMode == CHROME_ACTIVATION_LONG_PRESS")

old_constants = """        private const val PREF_TURN_MODE = "turn_mode"
        private const val PREF_VOLUME_KEY = "volume_key"
        private const val TURN_MODE_OVERLAP = "overlap"
"""
new_constants = """        private const val PREF_TURN_MODE = "turn_mode"
        private const val PREF_VOLUME_KEY = "volume_key"
        private const val PREF_BACKGROUND_STYLE = "background_style_v582"
        private const val PREF_CHROME_ACTIVATION = "chrome_activation_v582"
        private const val CHROME_ACTIVATION_CENTER = "center_tap"
        private const val CHROME_ACTIVATION_LONG_PRESS = "long_press"
        private const val TURN_MODE_OVERLAP = "overlap"
"""
text = replace_once(text, old_constants, new_constants, "v582 偏好常量", "PREF_BACKGROUND_STYLE")

required = [
    "ReaderBackgroundPicker.show(",
    "ReaderBackgrounds.drawable(activePreset)",
    "R.id.themeMoreButton",
    "R.id.chromeCenterButton",
    "R.id.chromeLongPressButton",
    "PREF_BACKGROUND_STYLE",
    "PREF_CHROME_ACTIVATION",
    "CHROME_ACTIVATION_LONG_PRESS",
    "readerProgressLabel.setTextColor(currentTextColor)",
    "return pageCountLabel()",
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"修改后缺少门禁标记：{marker}")
if "readerScrollView.scrollY / pageHeight" in text:
    raise SystemExit("仍存在被淘汰的屏幕高度页码算法")

reader.write_text(text, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_reader.xml")
layout_text = layout.read_text(encoding="utf-8-sig")
layout_text = layout_text.replace('        android:background="#33FFFFFF"\n', "", 1)

old_background_row = """            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="54dp"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:text="背景"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/themePaperButton"
                    android:layout_width="54dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="12dp"
                    android:background="#F5E9C8" />

                <TextView
                    android:id="@+id/themeEyeButton"
                    android:layout_width="54dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="12dp"
                    android:background="#DAEECD" />

                <TextView
                    android:id="@+id/themeWhiteButton"
                    android:layout_width="54dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="12dp"
                    android:background="#FFFFFF" />

                <TextView
                    android:id="@+id/themeNightButton"
                    android:layout_width="54dp"
                    android:layout_height="32dp"
                    android:background="#000000" />
            </LinearLayout>
"""
new_background_rows = """            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="54dp"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:text="背景"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/themePaperButton"
                    android:layout_width="44dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="8dp"
                    android:background="#F5E9C8" />

                <TextView
                    android:id="@+id/themeEyeButton"
                    android:layout_width="44dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="8dp"
                    android:background="#DAEECD" />

                <TextView
                    android:id="@+id/themeWhiteButton"
                    android:layout_width="44dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="8dp"
                    android:background="#FFFFFF" />

                <TextView
                    android:id="@+id/themeNightButton"
                    android:layout_width="44dp"
                    android:layout_height="32dp"
                    android:layout_marginEnd="8dp"
                    android:background="#000000"
                    android:gravity="center"
                    android:text="☾"
                    android:textColor="#FFFFFF"
                    android:textSize="18sp" />

                <TextView
                    android:id="@+id/themeMoreButton"
                    android:layout_width="56dp"
                    android:layout_height="34dp"
                    android:background="#4A4842"
                    android:gravity="center"
                    android:text="更多"
                    android:textColor="#EEE9DD"
                    android:textSize="14sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="54dp"
                    android:layout_height="match_parent"
                    android:gravity="center_vertical"
                    android:text="唤起"
                    android:textColor="#EEE9DD"
                    android:textSize="15sp" />

                <TextView
                    android:id="@+id/chromeCenterButton"
                    android:layout_width="112dp"
                    android:layout_height="34dp"
                    android:layout_marginEnd="10dp"
                    android:background="#EF7A28"
                    android:gravity="center"
                    android:text="中央单击"
                    android:textColor="#EEE9DD"
                    android:textSize="14sp" />

                <TextView
                    android:id="@+id/chromeLongPressButton"
                    android:layout_width="112dp"
                    android:layout_height="34dp"
                    android:background="#4A4842"
                    android:gravity="center"
                    android:text="全屏长按"
                    android:textColor="#EEE9DD"
                    android:textSize="14sp" />
            </LinearLayout>
"""
if "@+id/themeMoreButton" not in layout_text:
    layout_text = replace_once(layout_text, old_background_row, new_background_rows, "v582 页面设置背景与唤起行")

for marker in ["@+id/themeMoreButton", "@+id/chromeCenterButton", "@+id/chromeLongPressButton"]:
    if marker not in layout_text:
        raise SystemExit(f"布局缺少：{marker}")
if 'android:background="#33FFFFFF"' in layout_text:
    raise SystemExit("页码透明方块未删除")
layout.write_text(layout_text, encoding="utf-8")

print("v582 applied: v581 baseline + categorized backgrounds + more picker + configurable menu activation")
