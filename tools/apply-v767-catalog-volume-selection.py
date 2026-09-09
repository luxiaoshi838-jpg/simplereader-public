from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# Version ---------------------------------------------------------------------
p = Path('app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('"2098000766"', '"2098000767"')
s = s.replace('?: 2098000766', '?: 2098000767')
s = s.replace('?: "766"', '?: "767"')
p.write_text(s, encoding='utf-8')

# TXT catalog rule 114 --------------------------------------------------------
p = Path('app/src/main/java/com/simplereader/app/parser/TxtParser.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('const val CATALOG_RULE_VERSION = 113', 'const val CATALOG_RULE_VERSION = 114')
p.write_text(s, encoding='utf-8')

p = Path('app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('Rule 113 tightens', 'Rule 114 keeps the Rule 113 numeral safeguards and lets only 第N章 headings ignore trailing punctuation.')
s = s.replace('const val RULE_VERSION = 113', 'const val RULE_VERSION = 114')
s = replace_once(
    s,
    '    private val prefixedStructural = Regex("第\\\\s*$NUM\\\\s*$STRUCTURE")\n',
    '    private val prefixedStructural = Regex("第\\\\s*$NUM\\\\s*$STRUCTURE")\n    private val prefixedChapter = Regex("第\\\\s*$NUM\\\\s*章")\n',
    'prefixed chapter regex',
    'private val prefixedChapter = Regex'
)
s = replace_once(
    s,
    '''        if (numericOnly.matches(s)) return null\n\n        // 第N章/节/回... may occur after a short title prefix in historical books, but the''',
    '''        if (numericOnly.matches(s)) return null\n\n        // Rule 114: ONLY 第N章/第一章 style headings may ignore punctuation at the END of the\n        // whole title line. Punctuation inside the title still follows Rule 113, and other units\n        // (节/回/卷/篇...) are deliberately unchanged. This also keeps 第12章鱼 from matching.\n        if (recognizePrefixedChapterIgnoringTrailingPunctuation(s)) return s\n\n        // 第N章/节/回... may occur after a short title prefix in historical books, but the''',
    'special trailing punctuation rule',
    'recognizePrefixedChapterIgnoringTrailingPunctuation(s)'
)
s = replace_once(
    s,
    '''    /**\n     * The chapter unit is independent only when the next source character is a boundary.''',
    '''    private fun recognizePrefixedChapterIgnoringTrailingPunctuation(s: String): Boolean {\n        var end = s.length\n        while (end > 0 && (s[end - 1].isWhitespace() || isUnicodePunctuation(s[end - 1]))) end--\n        if (end == s.length || end <= 0) return false\n        val candidate = s.substring(0, end).trimEnd()\n        if (candidate.isEmpty()) return false\n        if (candidate.count { !it.isWhitespace() } > MAX_VISIBLE_TITLE_CHARS) return false\n        if ('“' in candidate || '”' in candidate || candidate.contains("http", ignoreCase = true)) return false\n        for (m in prefixedChapter.findAll(candidate)) {\n            val markerEnd = m.range.last + 1\n            if (hasValidStructuralTail(candidate, markerEnd) && !hasTerminatorIgnoringSeparatorAt(candidate, markerEnd)) {\n                return true\n            }\n        }\n        return false\n    }\n\n    private fun isUnicodePunctuation(c: Char): Boolean = when (Character.getType(c)) {\n        Character.CONNECTOR_PUNCTUATION.toInt(),\n        Character.DASH_PUNCTUATION.toInt(),\n        Character.START_PUNCTUATION.toInt(),\n        Character.END_PUNCTUATION.toInt(),\n        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),\n        Character.FINAL_QUOTE_PUNCTUATION.toInt(),\n        Character.OTHER_PUNCTUATION.toInt() -> true\n        else -> false\n    }\n\n    /**\n     * The chapter unit is independent only when the next source character is a boundary.''',
    'chapter trailing punctuation helper',
    'private fun recognizePrefixedChapterIgnoringTrailingPunctuation'
)
p.write_text(s, encoding='utf-8')

# Reader settings layout -------------------------------------------------------
p = Path('app/src/main/res/layout/activity_reader.xml')
s = p.read_text(encoding='utf-8')
s = s.replace('android:text="音量键翻页 开"', 'android:text="音量键翻页"')
anchor = '''            </LinearLayout>\n\n            <LinearLayout\n                android:layout_width="match_parent"\n                android:layout_height="46dp"\n                android:gravity="center_vertical"\n                android:orientation="horizontal">\n\n                <TextView\n                    android:layout_width="54dp"\n                    android:layout_height="match_parent"\n                    android:gravity="center_vertical"\n                    android:text="背景"'''
insert = '''            </LinearLayout>\n\n            <LinearLayout\n                android:layout_width="match_parent"\n                android:layout_height="46dp"\n                android:gravity="center_vertical"\n                android:orientation="horizontal">\n\n                <TextView\n                    android:layout_width="54dp"\n                    android:layout_height="match_parent"\n                    android:gravity="center_vertical"\n                    android:text="文本"\n                    android:textColor="#EEE9DD"\n                    android:textSize="15sp" />\n\n                <TextView\n                    android:id="@+id/selectTextToggleButton"\n                    android:layout_width="0dp"\n                    android:layout_height="36dp"\n                    android:layout_weight="1"\n                    android:gravity="center"\n                    android:background="#4A4842"\n                    android:text="选中文本"\n                    android:textColor="#EEE9DD"\n                    android:textSize="15sp" />\n            </LinearLayout>\n\n            <LinearLayout\n                android:layout_width="match_parent"\n                android:layout_height="46dp"\n                android:gravity="center_vertical"\n                android:orientation="horizontal">\n\n                <TextView\n                    android:layout_width="54dp"\n                    android:layout_height="match_parent"\n                    android:gravity="center_vertical"\n                    android:text="背景"'''
s = replace_once(s, anchor, insert, 'selection-only settings row', '@+id/selectTextToggleButton')
p.write_text(s, encoding='utf-8')

# PagedReaderView: native selection only, no historical extras ----------------
p = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '    private var longPressTriggered = false\n',
    '    private var longPressTriggered = false\n    private var textSelectionEnabled = false\n',
    'selection state',
    'private var textSelectionEnabled = false'
)
s = replace_once(
    s,
    '''    fun currentSnapshot(): ReaderPageSnapshot? = currentPage\n\n    fun release() {''',
    '''    fun currentSnapshot(): ReaderPageSnapshot? = currentPage\n\n    /** V767 restores only the native text-selection switch; no rejected legacy reader actions. */\n    fun setTextSelectionEnabled(enabled: Boolean) {\n        textSelectionEnabled = enabled\n        if (enabled) cancelNavigation()\n        previousView.setTextIsSelectable(false)\n        previousView.isLongClickable = false\n        nextView.setTextIsSelectable(false)\n        nextView.isLongClickable = false\n        currentView.setTextIsSelectable(enabled)\n        currentView.isLongClickable = enabled\n    }\n\n    fun release() {''',
    'paged selection setter',
    'fun setTextSelectionEnabled(enabled: Boolean)'
)
p.write_text(s, encoding='utf-8')

# Vertical rows ---------------------------------------------------------------
p = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '''        view.textSize = activity.verticalTextSizeSp()\n        view.setLineSpacing(0f, activity.verticalLineSpacingMultiplier())''',
    '''        view.textSize = activity.verticalTextSizeSp()\n        view.setTextIsSelectable(activity.isTextSelectionEnabled())\n        view.isLongClickable = activity.isTextSelectionEnabled()\n        view.setLineSpacing(0f, activity.verticalLineSpacingMultiplier())''',
    'vertical native selection',
    'view.setTextIsSelectable(activity.isTextSelectionEnabled())'
)
p.write_text(s, encoding='utf-8')

# ReaderActivity --------------------------------------------------------------
p = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '    private var volumeKeyTurnEnabled: Boolean = true\n',
    '    private var volumeKeyTurnEnabled: Boolean = true\n    private var textSelectionEnabled: Boolean = false\n',
    'reader selection state',
    'private var textSelectionEnabled: Boolean = false'
)
s = replace_once(
    s,
    '''        bindContinuousReader()\n        bindControls()\n        applyReaderAppearance(rebindPages = false)''',
    '''        bindContinuousReader()\n        bindControls()\n        applyTextSelectionSetting()\n        applyReaderAppearance(rebindPages = false)''',
    'apply saved selection state',
    'bindControls()\n        applyTextSelectionSetting()'
)
s = replace_once(
    s,
    '''        findViewById<TextView>(R.id.volumeKeyToggleButton).setOnClickListener {\n            volumeKeyTurnEnabled = !volumeKeyTurnEnabled\n            savePreferences()\n            updateSettingsLabels()\n        }\n        findViewById<TextView>(R.id.themePaperButton)''',
    '''        findViewById<TextView>(R.id.volumeKeyToggleButton).setOnClickListener {\n            volumeKeyTurnEnabled = !volumeKeyTurnEnabled\n            savePreferences()\n            updateSettingsLabels()\n        }\n        findViewById<TextView>(R.id.selectTextToggleButton).setOnClickListener {\n            textSelectionEnabled = !textSelectionEnabled\n            savePreferences()\n            applyTextSelectionSetting()\n            updateSettingsLabels()\n        }\n        findViewById<TextView>(R.id.themePaperButton)''',
    'selection toggle binding',
    'R.id.selectTextToggleButton).setOnClickListener'
)
s = replace_once(
    s,
    '''    internal fun verticalTextColor(): Int = activePalette().textColor\n    internal fun verticalPaddingLeft(): Int = continuousTextView.paddingLeft''',
    '''    internal fun verticalTextColor(): Int = activePalette().textColor\n    internal fun isTextSelectionEnabled(): Boolean = textSelectionEnabled\n    internal fun verticalPaddingLeft(): Int = continuousTextView.paddingLeft''',
    'vertical selection accessor',
    'internal fun isTextSelectionEnabled()'
)
s = replace_once(
    s,
    '''    private fun loadPreferences() {\n        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)\n        readerTextSizeSp = prefs.getFloat(PREF_TEXT_SIZE, ReaderAppearance.textSize(this))\n        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP\n        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)''',
    '''    private fun loadPreferences() {\n        val prefs = getSharedPreferences(READER_PREFS, MODE_PRIVATE)\n        readerTextSizeSp = prefs.getFloat(PREF_TEXT_SIZE, ReaderAppearance.textSize(this))\n        pageTurnMode = prefs.getString(PREF_TURN_MODE, TURN_MODE_OVERLAP) ?: TURN_MODE_OVERLAP\n        volumeKeyTurnEnabled = prefs.getBoolean(PREF_VOLUME_KEY, true)\n        textSelectionEnabled = prefs.getBoolean(PREF_TEXT_SELECTION, false)''',
    'load selection preference',
    'textSelectionEnabled = prefs.getBoolean(PREF_TEXT_SELECTION, false)'
)
s = replace_once(
    s,
    '''            .putString(PREF_TURN_MODE, pageTurnMode)\n            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)\n            .putInt(PREF_AUTO_READ_SPEED, autoReadSpeedCpm)''',
    '''            .putString(PREF_TURN_MODE, pageTurnMode)\n            .putBoolean(PREF_VOLUME_KEY, volumeKeyTurnEnabled)\n            .putBoolean(PREF_TEXT_SELECTION, textSelectionEnabled)\n            .putInt(PREF_AUTO_READ_SPEED, autoReadSpeedCpm)''',
    'save selection preference',
    '.putBoolean(PREF_TEXT_SELECTION, textSelectionEnabled)'
)
s = replace_once(
    s,
    '''    private fun updateSettingsLabels() {\n        findViewById<TextView>(R.id.fontSizeLabel).text = String.format(Locale.US, "%.0f", readerTextSizeSp)\n        findViewById<TextView>(R.id.volumeKeyToggleButton).text = "音量键翻页 ${if (volumeKeyTurnEnabled) "开" else "关"}"''',
    '''    private fun applyTextSelectionSetting() {\n        continuousTextView.setTextIsSelectable(textSelectionEnabled)\n        continuousTextView.isLongClickable = textSelectionEnabled\n        pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)\n        verticalAdapter?.refresh()\n    }\n\n    private fun updateSettingsLabels() {\n        findViewById<TextView>(R.id.fontSizeLabel).text = String.format(Locale.US, "%.0f", readerTextSizeSp)\n        findViewById<TextView>(R.id.volumeKeyToggleButton).apply {\n            text = "音量键翻页"\n            setBackgroundColor(if (volumeKeyTurnEnabled) Color.rgb(239, 122, 40) else Color.rgb(74, 72, 66))\n        }\n        findViewById<TextView>(R.id.selectTextToggleButton).apply {\n            text = "选中文本"\n            setBackgroundColor(if (textSelectionEnabled) Color.rgb(239, 122, 40) else Color.rgb(74, 72, 66))\n        }''',
    'settings state colors',
    'setBackgroundColor(if (volumeKeyTurnEnabled) Color.rgb(239, 122, 40)'
)
s = replace_once(
    s,
    '        private const val PREF_VOLUME_KEY = "volume_key_turn"\n',
    '        private const val PREF_VOLUME_KEY = "volume_key_turn"\n        private const val PREF_TEXT_SELECTION = "text_selection_enabled"\n',
    'selection pref constant',
    'PREF_TEXT_SELECTION = "text_selection_enabled"'
)
p.write_text(s, encoding='utf-8')

print('v767 catalog trailing-punctuation + volume state color + text-selection-only toggle applied')
