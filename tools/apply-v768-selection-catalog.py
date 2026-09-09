#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    if old not in s:
        raise SystemExit(f"V768 patch anchor missing in {path}: {old[:120]!r}")
    p.write_text(s.replace(old, new, 1), encoding="utf-8")


def replace_all(path: str, pairs: list[tuple[str, str]]) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    for old, new in pairs:
        if old not in s:
            raise SystemExit(f"V768 patch anchor missing in {path}: {old[:120]!r}")
        s = s.replace(old, new)
    p.write_text(s, encoding="utf-8")


# Version bump.
replace_all(
    "app/build.gradle.kts",
    [
        ('System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000767"', 'System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2098000768"'),
        ('?: 2098000767', '?: 2098000768'),
        ('System.getenv("SIMPLE_READER_VERSION_NAME") ?: "767"', 'System.getenv("SIMPLE_READER_VERSION_NAME") ?: "768"'),
    ],
)

# TXT recognizer: V768/Rule115 extends V767's whole-line trailing-punctuation allowance from
# 第N章 to the requested 第N章/节/回/卷/篇 families, without changing ordinary sentence guards.
direct = Path("app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt")
s = direct.read_text(encoding="utf-8")
s = s.replace("Rule 114 keeps the Rule 113 numeral safeguards and lets only 第N章 headings ignore trailing punctuation.",
              "Rule 115 keeps the Rule 113 numeral safeguards and lets 第N章/节/回/卷/篇 headings ignore punctuation only at the end of the whole title line.")
s = s.replace("const val RULE_VERSION = 114", "const val RULE_VERSION = 115")
s = s.replace('private val prefixedChapter = Regex("第\\\\s*$NUM\\\\s*章")',
              'private val prefixedTrailingPunctuationUnit = Regex("第\\\\s*$NUM\\\\s*(?:章|节|回|卷|篇)")')
s = s.replace(
    "// Rule 114: ONLY 第N章/第一章 style headings may ignore punctuation at the END of the\n"
    "        // whole title line. Punctuation inside the title still follows Rule 113, and other units\n"
    "        // (节/回/卷/篇...) are deliberately unchanged. This also keeps 第12章鱼 from matching.\n"
    "        if (recognizePrefixedChapterIgnoringTrailingPunctuation(s)) return s",
    "// Rule 115: 第N章/节/回/卷/篇 may ignore punctuation only at the END of the whole title line.\n"
    "        // Punctuation inside the title still follows Rule 113, and the structural-tail guard still\n"
    "        // rejects ordinary words such as 第3节课 and 第12章鱼.\n"
    "        if (recognizePrefixedStructuralIgnoringTrailingPunctuation(s)) return s",
)
s = s.replace("private fun recognizePrefixedChapterIgnoringTrailingPunctuation(s: String): Boolean {",
              "private fun recognizePrefixedStructuralIgnoringTrailingPunctuation(s: String): Boolean {")
s = s.replace("for (m in prefixedChapter.findAll(candidate)) {", "for (m in prefixedTrailingPunctuationUnit.findAll(candidate)) {")
for forbidden in ["const val RULE_VERSION = 114", "recognizePrefixedChapterIgnoringTrailingPunctuation", "prefixedChapter.findAll(candidate)"]:
    if forbidden in s:
        raise SystemExit(f"V768 direct-catalog old token survived: {forbidden}")
for required in ["const val RULE_VERSION = 115", "prefixedTrailingPunctuationUnit", "(?:章|节|回|卷|篇)", "recognizePrefixedStructuralIgnoringTrailingPunctuation"]:
    if required not in s:
        raise SystemExit(f"V768 direct-catalog token missing: {required}")
direct.write_text(s, encoding="utf-8")

replace_once(
    "app/src/main/java/com/simplereader/app/parser/TxtParser.kt",
    "const val CATALOG_RULE_VERSION = 114",
    "const val CATALOG_RULE_VERSION = 115",
)

# ReaderSelectionActions: make the source offset dynamic for the fallback/continuous TextView while
# retaining a fixed-offset overload for paged/RecyclerView rows.
selection = Path("app/src/main/java/com/simplereader/app/ui/ReaderSelectionActions.kt")
s = selection.read_text(encoding="utf-8")
old_attach = '''    fun attach(\n        view: TextView,\n        bookId: Long,\n        sourceOffset: Int,\n        sourceTextProvider: () -> String?\n    ) {\n        view.setCustomSelectionActionModeCallback(object : ActionMode.Callback {'''
new_attach = '''    fun attach(\n        view: TextView,\n        bookId: Long,\n        sourceOffset: Int,\n        sourceTextProvider: () -> String?\n    ) = attach(view, bookId, { sourceOffset }, sourceTextProvider)\n\n    fun attach(\n        view: TextView,\n        bookId: Long,\n        sourceOffsetProvider: () -> Int,\n        sourceTextProvider: () -> String?\n    ) {\n        view.setCustomSelectionActionModeCallback(object : ActionMode.Callback {'''
if old_attach not in s:
    raise SystemExit("V768 selection dynamic-offset anchor missing")
s = s.replace(old_attach, new_attach, 1)
s = s.replace("val selected = selected(view, sourceOffset, sourceTextProvider()) ?: return false",
              "val selected = selected(view, sourceOffsetProvider(), sourceTextProvider()) ?: return false", 1)
selection.write_text(s, encoding="utf-8")

# ReaderActivity: wire the restored action toolbar to all live text surfaces and release TTS resources.
reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
s = reader.read_text(encoding="utf-8")
field_anchor = "    private lateinit var autoReadStopView: TextView\n"
if field_anchor not in s:
    raise SystemExit("V768 ReaderActivity field anchor missing")
s = s.replace(field_anchor, field_anchor + "    private lateinit var selectionActions: ReaderSelectionActions\n", 1)
init_anchor = "        database = SimpleReaderDatabase.getDatabase(this)\n"
if init_anchor not in s:
    raise SystemExit("V768 ReaderActivity init anchor missing")
s = s.replace(init_anchor, init_anchor + "        selectionActions = ReaderSelectionActions(this)\n", 1)
release_anchor = "        ReaderBackgrounds.clearMemoryCaches()\n"
if release_anchor not in s:
    raise SystemExit("V768 ReaderActivity release anchor missing")
s = s.replace(release_anchor, "        if (::selectionActions.isInitialized) selectionActions.release()\n" + release_anchor, 1)

bind_anchor = "    private fun bindPagedReader() {\n"
if bind_anchor not in s:
    raise SystemExit("V768 ReaderActivity paged binder anchor missing")
s = s.replace(
    bind_anchor,
    bind_anchor
    + "        pagedReaderView.onBindSelectionActions = { view, sourceOffset ->\n"
      "            bindSelectionActions(view, sourceOffset.toInt())\n"
      "        }\n"
      "        pagedReaderView.onDecorateSelectionText = { sourceOffset, text ->\n"
      "            decorateSelectionText(sourceOffset.toInt(), text)\n"
      "        }\n",
    1,
)

old_vertical = "    internal fun verticalRenderPage(page: ReaderPage): CharSequence = renderPage(page)\n"
new_vertical = '''    internal fun verticalRenderPage(page: ReaderPage): CharSequence = renderPage(page)\n    internal fun verticalDecorateSelectionText(sourceOffset: Int, text: CharSequence): CharSequence =\n        if (::selectionActions.isInitialized) selectionActions.decorate(bookId, sourceOffset, text) else text\n\n    internal fun bindSelectionActions(view: TextView, sourceOffset: Int) {\n        if (!::selectionActions.isInitialized || !textSelectionEnabled) {\n            if (::selectionActions.isInitialized) selectionActions.clearCallback(view)\n            return\n        }\n        selectionActions.attach(view, bookId, sourceOffset) { readerBook?.text ?: document?.text }\n    }\n\n    private fun decorateSelectionText(sourceOffset: Int, text: CharSequence): CharSequence =\n        if (::selectionActions.isInitialized) selectionActions.decorate(bookId, sourceOffset, text) else text\n'''
if old_vertical not in s:
    raise SystemExit("V768 ReaderActivity vertical selection anchor missing")
s = s.replace(old_vertical, new_vertical, 1)

old_setting = '''    private fun applyTextSelectionSetting() {\n        continuousTextView.setTextIsSelectable(textSelectionEnabled)\n        continuousTextView.isLongClickable = textSelectionEnabled\n        pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)\n        verticalAdapter?.refresh()\n    }'''
new_setting = '''    private fun applyTextSelectionSetting() {\n        continuousTextView.setTextIsSelectable(textSelectionEnabled)\n        continuousTextView.isLongClickable = textSelectionEnabled\n        if (textSelectionEnabled) {\n            selectionActions.attach(continuousTextView, bookId, { continuousWindowStartOffset }) {\n                readerBook?.text ?: document?.text\n            }\n        } else {\n            selectionActions.clearCallback(continuousTextView)\n        }\n        pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)\n        verticalAdapter?.refresh()\n    }'''
if old_setting not in s:
    raise SystemExit("V768 ReaderActivity applyTextSelectionSetting anchor missing")
s = s.replace(old_setting, new_setting, 1)
reader.write_text(s, encoding="utf-8")

# Vertical RecyclerView: keep the 32-entry render cache unchanged, decorate underlines after cache
# lookup, and attach the action toolbar to the exact page/source offset.
vertical = Path("app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt")
s = vertical.read_text(encoding="utf-8")
old = '''        if (position != pages.lastIndex && text.isNotEmpty() && text.last() == '\\n') {\n            text = text.subSequence(0, text.length - 1)\n        }\n        view.setText(text, TextView.BufferType.SPANNABLE)'''
new = '''        text = activity.verticalDecorateSelectionText(pages[position].startOffset, text)\n        if (position != pages.lastIndex && text.isNotEmpty() && text.last() == '\\n') {\n            text = text.subSequence(0, text.length - 1)\n        }\n        view.setText(text, TextView.BufferType.SPANNABLE)\n        activity.bindSelectionActions(view, pages[position].startOffset)'''
if old not in s:
    raise SystemExit("V768 VerticalPageAdapter bind anchor missing")
s = s.replace(old, new, 1)
vertical.write_text(s, encoding="utf-8")

# Horizontal renderer: decorate source-coordinate text before V104 title normalization, then bind
# the toolbar only to the current page.  Page-turn/search/progress callbacks are untouched.
paged = Path("app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt")
s = paged.read_text(encoding="utf-8")
props = "    var onLongPress: (() -> Unit)? = null\n"
if props not in s:
    raise SystemExit("V768 PagedReaderView callback anchor missing")
s = s.replace(
    props,
    props
    + "    var onBindSelectionActions: ((TextView, Long) -> Unit)? = null\n"
      "    var onDecorateSelectionText: ((Long, CharSequence) -> CharSequence)? = null\n",
    1,
)

old_bind = '''        previousView.text = previous?.let(ReaderBodyTitleNormalizerV104::normalizeSnapshot) ?: ""\n        currentView.text = ReaderBodyTitleNormalizerV104.normalizeSnapshot(current)\n        nextView.text = next?.let(ReaderBodyTitleNormalizerV104::normalizeSnapshot) ?: ""\n        resetTransforms()'''
new_bind = '''        previousView.text = previous?.let(::renderSnapshot) ?: ""\n        currentView.text = renderSnapshot(current)\n        nextView.text = next?.let(::renderSnapshot) ?: ""\n        if (textSelectionEnabled) onBindSelectionActions?.invoke(currentView, current.startAnchor.sourceOffset)\n        resetTransforms()'''
if old_bind not in s:
    raise SystemExit("V768 PagedReaderView bind anchor missing")
s = s.replace(old_bind, new_bind, 1)
old_adj = '''        previousView.text = previous?.let(ReaderBodyTitleNormalizerV104::normalizeSnapshot) ?: ""\n        nextView.text = next?.let(ReaderBodyTitleNormalizerV104::normalizeSnapshot) ?: ""'''
new_adj = '''        previousView.text = previous?.let(::renderSnapshot) ?: ""\n        nextView.text = next?.let(::renderSnapshot) ?: ""'''
if old_adj not in s:
    raise SystemExit("V768 PagedReaderView adjacent anchor missing")
s = s.replace(old_adj, new_adj, 1)

old_selection = '''    /** V767 restores only the native text-selection switch; no rejected legacy reader actions. */\n    fun setTextSelectionEnabled(enabled: Boolean) {\n        textSelectionEnabled = enabled\n        if (enabled) cancelNavigation()\n        previousView.setTextIsSelectable(false)\n        previousView.isLongClickable = false\n        nextView.setTextIsSelectable(false)\n        nextView.isLongClickable = false\n        currentView.setTextIsSelectable(enabled)\n        currentView.isLongClickable = enabled\n    }'''
new_selection = '''    /** V768 restores the full selected-text toolbar on the current horizontal page. */\n    fun setTextSelectionEnabled(enabled: Boolean) {\n        textSelectionEnabled = enabled\n        if (enabled) cancelNavigation()\n        previousView.setTextIsSelectable(false)\n        previousView.isLongClickable = false\n        nextView.setTextIsSelectable(false)\n        nextView.isLongClickable = false\n        currentView.setTextIsSelectable(enabled)\n        currentView.isLongClickable = enabled\n        if (enabled) {\n            currentPage?.let { onBindSelectionActions?.invoke(currentView, it.startAnchor.sourceOffset) }\n        } else {\n            currentView.setCustomSelectionActionModeCallback(null)\n        }\n    }\n\n    private fun renderSnapshot(snapshot: ReaderPageSnapshot): CharSequence {\n        val sourceOffset = snapshot.startAnchor.sourceOffset\n        val decorated = if (sourceOffset >= 0L) {\n            onDecorateSelectionText?.invoke(sourceOffset, snapshot.content) ?: snapshot.content\n        } else {\n            snapshot.content\n        }\n        return ReaderBodyTitleNormalizerV104.normalizeSnapshot(snapshot.copy(content = decorated))\n    }'''
if old_selection not in s:
    raise SystemExit("V768 PagedReaderView selection anchor missing")
s = s.replace(old_selection, new_selection, 1)
release = "        onLongPress = null\n"
if release not in s:
    raise SystemExit("V768 PagedReaderView release anchor missing")
s = s.replace(release, release + "        onBindSelectionActions = null\n        onDecorateSelectionText = null\n", 1)
paged.write_text(s, encoding="utf-8")

print("v768 selection actions + Rule115 catalog punctuation patch applied")
