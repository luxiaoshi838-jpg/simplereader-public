from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def ensure_replace(path: str, old: str, new: str, label: str) -> None:
    text = read(path)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 old match, found {count}")
    write(path, text.replace(old, new, 1))


def ensure_import(path: str, anchor: str, import_line: str, label: str) -> None:
    text = read(path)
    if import_line in text:
        return
    if anchor not in text:
        raise SystemExit(f"{label}: import anchor missing")
    write(path, text.replace(anchor, anchor + import_line, 1))


# MainActivity: both selection-mode refresh paths must show the same top-right exit glyph.
main_path = "app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
main = read(main_path)
for old in ('moreButton.text = "\\u53d6\\u6d88"', 'moreButton.text = "取消"'):
    main = main.replace(old, 'moreButton.text = "×"')
if main.count('moreButton.text = "×"') != 2:
    raise SystemExit(f"MainActivity selection label: expected 2 source paths, found {main.count('moreButton.text = \'×\'')}")
write(main_path, main)


# TXT reader: source-level bookmark icon, transactional duplicate prevention, and exact v722 bounds.
reader = "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
ensure_import(reader, "import android.widget.FrameLayout\n", "import android.widget.ImageView\n", "ReaderActivity ImageView")
ensure_import(reader, "import androidx.lifecycle.lifecycleScope\n", "import androidx.room.withTransaction\n", "ReaderActivity Room transaction")
ensure_replace(
    reader,
    '''        addItem.actionView = TextView(this).apply {
            text = "添"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            contentDescription = "添加书签"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(239, 122, 40))
            }
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { addBookmark() }
        }''',
    '''        addItem.actionView = ImageView(this).apply {
            setImageResource(R.drawable.ic_bookmark_add)
            contentDescription = "添加书签"
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { addBookmark() }
        }''',
    "ReaderActivity bookmark icon",
)
ensure_replace(
    reader,
    '''        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookmarkDao().insert(
                    Bookmark(
                        bookId = bookId,
                        position = page.startOffset.toString(),
                        content = preview,
                        globalPageIndex = page.globalPageIndex,
                        chapterIndex = page.chapterIndex,
                        pageIndexInChapter = page.pageIndexInChapter,
                        startOffset = page.startOffset
                    )
                )
            }
            Toast.makeText(this@ReaderActivity, "书签已添加 ${page.globalPageIndex + 1}/${page.totalPageCount}", Toast.LENGTH_SHORT).show()
        }''',
    '''        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                database.withTransaction {
                    val dao = database.bookmarkDao()
                    if (dao.getBookmarkByPage(bookId, page.globalPageIndex) != null) {
                        false
                    } else {
                        dao.insert(
                            Bookmark(
                                bookId = bookId,
                                position = page.startOffset.toString(),
                                content = preview,
                                globalPageIndex = page.globalPageIndex,
                                chapterIndex = page.chapterIndex,
                                pageIndexInChapter = page.pageIndexInChapter,
                                startOffset = page.startOffset
                            )
                        )
                        true
                    }
                }
            }
            val message = if (added) {
                "书签已添加 ${page.globalPageIndex + 1}/${page.totalPageCount}"
            } else {
                "本页已有书签"
            }
            Toast.makeText(this@ReaderActivity, message, Toast.LENGTH_SHORT).show()
        }''',
    "ReaderActivity bookmark uniqueness",
)
reader_text = read(reader)
reader_text = reader_text.replace(
    "val bottomPaddingPx = navigationBarInsetPx + oneCharacterPx",
    "val bottomPaddingPx = navigationBarInsetPx + oneCharacterPx * 3",
)
reader_text = reader_text.replace(
    "// Upper limit = notification/status-bar bottom + one complete text character.\n        // This is intentionally not measured from the physical top edge of the screen.",
    "// Exact v722 content bounds: one current-font character below the status bar and\n        // three current-font characters above the navigation bar.",
)
if "val bottomPaddingPx = navigationBarInsetPx + oneCharacterPx * 3" not in reader_text:
    raise SystemExit("ReaderActivity v722 lower bound missing")
write(reader, reader_text)


# EPUB reader: the same bookmark icon and per-book/page uniqueness rule.
epub = "app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt"
ensure_import(epub, "import android.widget.FrameLayout\n", "import android.widget.ImageView\n", "Readium ImageView")
ensure_import(epub, "import androidx.lifecycle.lifecycleScope\n", "import androidx.room.withTransaction\n", "Readium Room transaction")
ensure_replace(
    epub,
    '''        addItem.actionView = TextView(this).apply {
            text = "签"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            contentDescription = "添加书签"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(239, 122, 40))
            }
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { addBookmark() }
        }''',
    '''        addItem.actionView = ImageView(this).apply {
            setImageResource(R.drawable.ic_bookmark_add)
            contentDescription = "添加书签"
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setOnClickListener { addBookmark() }
        }''',
    "Readium bookmark icon",
)
ensure_replace(
    epub,
    '''        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.bookmarkDao().insert(
                    Bookmark(
                        bookId = bookId,
                        position = locator.toJSON().toString(),
                        content = label
                    )
                )
            }
            Toast.makeText(this@ReadiumEpubActivity, "已添加书签", Toast.LENGTH_SHORT).show()
        }''',
    '''        val globalPageIndex = (pageNumberForLocator(locator) - 1).coerceAtLeast(0)
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                database.withTransaction {
                    val dao = database.bookmarkDao()
                    if (dao.getBookmarkByPage(bookId, globalPageIndex) != null) {
                        false
                    } else {
                        dao.insert(
                            Bookmark(
                                bookId = bookId,
                                position = locator.toJSON().toString(),
                                content = label,
                                globalPageIndex = globalPageIndex
                            )
                        )
                        true
                    }
                }
            }
            Toast.makeText(
                this@ReadiumEpubActivity,
                if (added) "已添加书签" else "本页已有书签",
                Toast.LENGTH_SHORT
            ).show()
        }''',
    "Readium bookmark uniqueness",
)


# Shelf caching: later behavior is reimplemented directly in CoroutineWorker source.
worker = "app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt"
ensure_import(worker, "import kotlinx.coroutines.Dispatchers\n", "import kotlinx.coroutines.CancellationException\n", "ShelfCache cancellation")
ensure_import(worker, "import kotlinx.coroutines.flow.first\n", "import kotlinx.coroutines.isActive\n", "ShelfCache isActive")
worker_text = read(worker).replace("ExistingWorkPolicy.KEEP", "ExistingWorkPolicy.REPLACE")
if "ExistingWorkPolicy.REPLACE" not in worker_text:
    raise SystemExit("ShelfCacheWorker REPLACE policy missing")
worker_text = worker_text.replace(
    '''            val result = runCatching {
                withContext(Dispatchers.IO) {''',
    '''            val result = runCatching {
                withContext(Dispatchers.IO) {''',
    1,
)
if "if (failure is CancellationException) throw failure" not in worker_text:
    marker = "            if (result.isSuccess) completed += 1 else failed += 1\n"
    if marker not in worker_text:
        raise SystemExit("ShelfCacheWorker result classification marker missing")
    worker_text = worker_text.replace(
        marker,
        '''            result.exceptionOrNull()?.let { failure ->
                if (failure is CancellationException) throw failure
            }
            if (result.isSuccess) completed += 1 else failed += 1
''',
        1,
    )
# Pass the worker's parent coroutine activity into the CPU paginator so replacement cancels promptly.
old_paginate = '''                val paged = withContext(Dispatchers.Default) {
                    val images = ReaderImageRepository(applicationContext, book.id)
                    PageEngine.paginate(
                        text = document.text,
                        sourceChapters = document.chapters,
                        settings = settings,
                        typeface = Typeface.DEFAULT
                    ) { href, width, height -> images.span(href, width, height) }
                }'''
new_paginate = '''                val activeContext = coroutineContext
                val paged = withContext(Dispatchers.Default) {
                    val images = ReaderImageRepository(applicationContext, book.id)
                    PageEngine.paginate(
                        text = document.text,
                        sourceChapters = document.chapters,
                        settings = settings,
                        typeface = Typeface.DEFAULT,
                        shouldContinue = { activeContext.isActive },
                        imageSpanProvider = { href, width, height -> images.span(href, width, height) }
                    )
                }'''
if new_paginate not in worker_text:
    if old_paginate not in worker_text:
        raise SystemExit("ShelfCacheWorker pagination block missing")
    worker_text = worker_text.replace(old_paginate, new_paginate, 1)
write(worker, worker_text)


# PageEngine stays behavior-identical for normal readers, but offers an optional cooperative
# cancellation check used only by the background source worker.
engine = "app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt"
ensure_import(engine, "import java.security.MessageDigest\n", "import java.util.concurrent.CancellationException\n", "PageEngine cancellation")
engine_text = read(engine)
old_signature = '''        settings: ReaderLayoutSettings,
        typeface: Typeface = Typeface.DEFAULT,
        imageSpanProvider: ImageSpanProvider? = null
    ): ReaderBook {'''
new_signature = '''        settings: ReaderLayoutSettings,
        typeface: Typeface = Typeface.DEFAULT,
        shouldContinue: (() -> Boolean)? = null,
        imageSpanProvider: ImageSpanProvider? = null
    ): ReaderBook {'''
if new_signature not in engine_text:
    if old_signature not in engine_text:
        raise SystemExit("PageEngine paginate signature missing")
    engine_text = engine_text.replace(old_signature, new_signature, 1)
if "ensureContinue(shouldContinue)\n            val chapterPages" not in engine_text:
    engine_text = engine_text.replace(
        "        chapters.forEachIndexed { chapterIndex, chapter ->\n            val chapterPages = paginateChapter(",
        "        chapters.forEachIndexed { chapterIndex, chapter ->\n            ensureContinue(shouldContinue)\n            val chapterPages = paginateChapter(",
        1,
    )
old_chapter_call = '''                settings = settings,
                typeface = typeface,
                imageSpanProvider = imageSpanProvider
            )'''
new_chapter_call = '''                settings = settings,
                typeface = typeface,
                shouldContinue = shouldContinue,
                imageSpanProvider = imageSpanProvider
            )'''
if new_chapter_call not in engine_text:
    if old_chapter_call not in engine_text:
        raise SystemExit("PageEngine chapter call missing")
    engine_text = engine_text.replace(old_chapter_call, new_chapter_call, 1)
old_chapter_sig = '''        chapter: BookChapter,
        settings: ReaderLayoutSettings,
        typeface: Typeface,
        imageSpanProvider: ImageSpanProvider?
    ): List<Pair<Int, Int>> {'''
new_chapter_sig = '''        chapter: BookChapter,
        settings: ReaderLayoutSettings,
        typeface: Typeface,
        shouldContinue: (() -> Boolean)?,
        imageSpanProvider: ImageSpanProvider?
    ): List<Pair<Int, Int>> {'''
if new_chapter_sig not in engine_text:
    if old_chapter_sig not in engine_text:
        raise SystemExit("PageEngine chapter signature missing")
    engine_text = engine_text.replace(old_chapter_sig, new_chapter_sig, 1)
if "while (cursor < chapter.endOffset) {\n            ensureContinue(shouldContinue)" not in engine_text:
    engine_text = engine_text.replace(
        "        while (cursor < chapter.endOffset) {\n            val windowEnd = chooseWindowEnd",
        "        while (cursor < chapter.endOffset) {\n            ensureContinue(shouldContinue)\n            val windowEnd = chooseWindowEnd",
        1,
    )
if "while (firstLine < layout.lineCount) {\n                    ensureContinue(shouldContinue)" not in engine_text:
    engine_text = engine_text.replace(
        "                while (firstLine < layout.lineCount) {\n                    val pageTop = layout.getLineTop(firstLine)",
        "                while (firstLine < layout.lineCount) {\n                    ensureContinue(shouldContinue)\n                    val pageTop = layout.getLineTop(firstLine)",
        1,
    )
if "private fun ensureContinue(shouldContinue: (() -> Boolean)?)" not in engine_text:
    marker = "    private fun chooseWindowEnd(text: String, start: Int, chapterEnd: Int): Int {"
    helper = '''    private fun ensureContinue(shouldContinue: (() -> Boolean)?) {
        if (shouldContinue != null && !shouldContinue()) {
            throw CancellationException("Pagination cancelled")
        }
    }

'''
    if marker not in engine_text:
        raise SystemExit("PageEngine helper insertion marker missing")
    engine_text = engine_text.replace(marker, helper + marker, 1)
write(engine, engine_text)


# Permanent source version for the first fully source-built post-v745 APK.
gradle = "app/build.gradle.kts"
gradle_text = read(gradle)
gradle_text = gradle_text.replace('?: "2098000725"', '?: "2098000746"')
gradle_text = gradle_text.replace('?: 2098000725', '?: 2098000746')
gradle_text = gradle_text.replace('?: "725"', '?: "746"')
if '?: "2098000746"' not in gradle_text or '?: "746"' not in gradle_text:
    raise SystemExit("V746 version defaults not applied")
write(gradle, gradle_text)


# Correct the locked UI gate to the already-recorded v722 bounds and source-level replacement policy.
ui_gate = "tools/verify-ui-policy.sh"
ui = read(ui_gate)
ui = ui.replace(
    "grep -q 'CONTENT_BOTTOM_PADDING_DP = 24' app/src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt || fail \"reader bottom guard must be one character\"",
    "grep -q 'CONTENT_BOTTOM_PADDING_DP = 0' app/src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt || fail \"reader static bottom padding must remain zero; v722 guard is runtime font-relative\"",
)
ui = ui.replace(
    "grep -q 'navigationBarInsetPx + oneCharacterPx' \"$reader\"",
    "grep -Fq 'navigationBarInsetPx + oneCharacterPx * 3' \"$reader\"",
)
ui = ui.replace("reader lower limit must leave one character above navigation bar", "reader lower limit must leave three current-font characters above navigation bar")
ui = ui.replace("ExistingWorkPolicy.KEEP", "ExistingWorkPolicy.REPLACE")
ui = ui.replace("background cache must remain unique and persistent", "new shelf cache action must replace and cancel the older unique task")
write(ui_gate, ui)


# Production gate: historical patch assets may remain under tools/ only, but app/src/main must be clean.
source_gate = '''#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if grep -RInE 'classes[0-9]*\\.dex|patch_classes|patch_v7[0-9][0-9]|dex offset|register patch' app/src/main --include='*.kt' --include='*.java' --include='*.xml'; then
  echo 'ERROR: production source references binary patch implementation' >&2
  exit 1
fi
[ "$(grep -c 'moreButton.text = "×"' app/src/main/java/com/simplereader/app/ui/MainActivity.kt)" -eq 2 ]
grep -q 'R.drawable.ic_bookmark_add' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'R.drawable.ic_bookmark_add' app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt
grep -q 'getBookmarkByPage' app/src/main/java/com/simplereader/app/data/dao/BookmarkDao.kt
grep -q 'withTransaction' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'withTransaction' app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt
grep -Fq 'navigationBarInsetPx + oneCharacterPx * 3' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'ExistingWorkPolicy.REPLACE' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
grep -q 'coroutineContext.ensureActive()' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
grep -q 'shouldContinue = { activeContext.isActive }' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
grep -q 'fun showLogHub(activity: AppCompatActivity) = showCrashLogList(activity)' app/src/main/java/com/simplereader/app/operation/OperationLogDialogs.kt
grep -q 'return emptyList()' app/src/main/java/com/simplereader/app/operation/OperationLogStore.kt
grep -q '?: "2098000746"' app/build.gradle.kts
grep -q '?: "746"' app/build.gradle.kts
echo 'Source-only V746 production guard passed.'
'''
Path("tools/verify-source-only-production.sh").write_text(source_gate, encoding="utf-8")
Path("tools/verify-source-only-production.sh").chmod(0o755)

# The vector is a real XML source asset, never a runtime-generated/DEX-patched drawable.
icon = Path("app/src/main/res/drawable/ic_bookmark_add.xml")
if not icon.exists() or "android:strokeColor=\"#FFFFFFFF\"" not in icon.read_text(encoding="utf-8"):
    raise SystemExit("bookmark vector source asset missing")

print("V746 source migration applied and verified for source-only build input.")
