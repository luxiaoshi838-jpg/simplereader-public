from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
reader_path = ROOT / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
group_path = ROOT / "app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


reader = reader_path.read_text(encoding="utf-8")
reader = replace_once(
    reader,
    """    private var structuredWholeBookMode: Boolean = false\n    private var structuredReadingBuffer: StructuredReadingBuffer? = null\n    private var chmCachedFile: File? = null\n""",
    """    private var structuredWholeBookMode: Boolean = false\n    private var structuredReadingBuffer: StructuredReadingBuffer? = null\n    private var structuredWholeText: String? = null\n    private var structuredBufferLoadJob: Job? = null\n    private var chmCachedFile: File? = null\n""",
    "reader fields",
)
reader = replace_once(
    reader,
    """            updateVerticalScrollProgress(scrollY)\n            maybeExtendTxtContinuousBuffer(scrollY)\n""",
    """            updateVerticalScrollProgress(scrollY)\n            maybeExtendTxtContinuousBuffer(scrollY)\n            maybeExtendStructuredContinuousBuffer(scrollY)\n""",
    "scroll listener",
)

loaded_pattern = re.compile(
    r"    private fun loadedContentFromCache\(cached: CachedBook, progress: ReadProgress\?\): LoadedContent \{.*?\n    \}\n\n    private suspend fun applyLoadedContent",
    re.S,
)
loaded_replacement = """    private fun loadedContentFromCache(cached: CachedBook, progress: ReadProgress?): LoadedContent {
        val wholeText = cached.textFile.readText(Charsets.UTF_8)
        val chapters = cached.chapters.map { chapter ->
            EpubChapter(name = chapter.source, text = chapter.title)
        }
        require(chapters.isNotEmpty()) { "缓存中没有可读取的章节" }
        val starts = cached.chapters.map { it.startChar.coerceIn(0, wholeText.length) }
        val hrefIndex = progress?.epubChapterHref
            ?.takeIf { it.isNotBlank() }
            ?.let { href -> cached.chapters.indexOfFirst { it.source.equals(href, ignoreCase = true) } }
            ?.takeIf { it >= 0 }
        val targetIndex = (hrefIndex ?: progress?.epubSpineIndex ?: 0)
            .coerceIn(0, chapters.lastIndex)
        val chapterStart = starts.getOrElse(targetIndex) { 0 }
        val chapterEnd = cached.chapters.getOrNull(targetIndex)
            ?.endChar
            ?.coerceIn(chapterStart, wholeText.length)
            ?: starts.getOrNull(targetIndex + 1)?.coerceAtLeast(chapterStart)
            ?: wholeText.length
        val targetOffset = (progress?.epubChapterOffset ?: 0)
            .coerceIn(0, (chapterEnd - chapterStart).coerceAtLeast(0))
        structuredWholeText = wholeText
        val buffer = buildEpubReadingBuffer(
            wholeText = wholeText,
            chapters = chapters,
            starts = starts,
            centerIndex = targetIndex
        )
        val initialPosition = buffer.positionFor(targetIndex, targetOffset) ?: 0
        return LoadedContent(
            text = buffer.content,
            epubChapters = chapters,
            epubChapterStartPositions = starts,
            structuredCatalogEntries = cached.catalog.map { entry ->
                StructuredCatalogEntry(
                    title = entry.title,
                    depth = entry.depth,
                    targetChapterIndex = entry.targetChapterIndex,
                    isSection = entry.isSection
                )
            },
            structuredChapterIndex = targetIndex,
            structuredInitialPosition = initialPosition,
            structuredWholeBookMode = false,
            structuredReadingBuffer = buffer
        )
    }

    private suspend fun applyLoadedContent"""
reader, loaded_count = loaded_pattern.subn(loaded_replacement, reader, count=1)
if loaded_count != 1:
    raise RuntimeError(f"loadedContentFromCache: expected 1 match, found {loaded_count}")

old_branch_pattern = re.compile(
    r"        val cachedFile = chmCachedFile\n        if \(cachedFile == null \|\| !cachedFile\.isFile\) \{.*?\n        \}\n    \}\n\n    private fun buildChmReadingBuffer",
    re.S,
)
new_branch = """        val wholeText = structuredWholeText
        if (wholeText == null) {
            Toast.makeText(this, "EPUB 缓存不可用，请重新打开书籍", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val buffer = withContext(Dispatchers.Default) {
                    buildEpubReadingBuffer(
                        wholeText = wholeText,
                        chapters = epubChapters,
                        starts = epubChapterStartPositions,
                        centerIndex = targetIndex
                    )
                }
                if (buffer.content.isBlank()) {
                    Toast.makeText(this@ReaderActivity, "该章节没有可显示内容", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                structuredReadingBuffer = buffer
                structuredChapterIndex = targetIndex
                currentContent = buffer.content
                val chapterLength = buffer.chapterLength(targetIndex)
                val targetOffset = when {
                    openAtEnd -> (chapterLength - if (pageTurnMode == TURN_MODE_VERTICAL) 1 else pageSize)
                        .coerceAtLeast(0)
                    offsetFraction != null -> (chapterLength * offsetFraction.coerceIn(0f, 1f)).toInt()
                    else -> offset.coerceIn(0, chapterLength)
                }
                currentPosition = buffer.positionFor(targetIndex, targetOffset) ?: 0
                displayContent()
                if (direction != 0) animatePageTurn(direction)
                markProgressDirty()
                if (saveImmediately) saveProgressNow() else scheduleProgressSave()
            } catch (error: Throwable) {
                showError("读取章节失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun buildEpubReadingBuffer(
        wholeText: String,
        chapters: List<EpubChapter>,
        starts: List<Int>,
        centerIndex: Int
    ): StructuredReadingBuffer {
        val safeCenter = centerIndex.coerceIn(0, chapters.lastIndex)
        val indices = (safeCenter - 1..safeCenter + 1).filter { it in chapters.indices }
        val texts = indices.map { index ->
            val start = starts.getOrElse(index) { 0 }.coerceIn(0, wholeText.length)
            val end = starts.getOrNull(index + 1)
                ?.coerceIn(start, wholeText.length)
                ?: wholeText.length
            index to wholeText.substring(start, end).trimEnd()
        }
        return StructuredReadingBuffer.build(safeCenter, texts)
    }

    private fun maybeExtendStructuredContinuousBuffer(scrollY: Int) {
        if (
            suppressNextScrollProgress ||
            pageTurnMode != TURN_MODE_VERTICAL ||
            !isStructuredChapterDocument() ||
            structuredWholeBookMode ||
            structuredBufferLoadJob?.isActive == true
        ) return
        val buffer = structuredReadingBuffer ?: return
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        if (maxScroll <= 0) return
        val fraction = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        val nextCenter = when {
            fraction >= STRUCTURED_PREFETCH_FORWARD_FRACTION && buffer.lastChapterIndex < epubChapters.lastIndex ->
                (buffer.centerChapterIndex + 1).coerceAtMost(epubChapters.lastIndex)
            fraction <= STRUCTURED_PREFETCH_BACKWARD_FRACTION && buffer.firstChapterIndex > 0 ->
                (buffer.centerChapterIndex - 1).coerceAtLeast(0)
            else -> return
        }
        val wholeText = structuredWholeText ?: return
        val oldLocation = currentStructuredLocation()
        structuredBufferLoadJob = lifecycleScope.launch {
            val newBuffer = withContext(Dispatchers.Default) {
                buildEpubReadingBuffer(
                    wholeText = wholeText,
                    chapters = epubChapters,
                    starts = epubChapterStartPositions,
                    centerIndex = nextCenter
                )
            }
            val preservedPosition = newBuffer.positionFor(oldLocation.chapterIndex, oldLocation.offset)
                ?: newBuffer.positionFor(nextCenter, 0)
                ?: 0
            structuredReadingBuffer = newBuffer
            structuredChapterIndex = oldLocation.chapterIndex.coerceIn(0, epubChapters.lastIndex)
            currentContent = newBuffer.content
            currentPosition = preservedPosition
            suppressNextScrollProgress = true
            contentView.text = currentContent
            contentView.textSize = readerTextSize
            configureVerticalScrollIfNeeded()
            contentView.post {
                val newMaxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                val targetFraction = if (currentContent.isEmpty()) 0f else {
                    (currentPosition.toFloat() / currentContent.length).coerceIn(0f, 1f)
                }
                readerScrollView.scrollTo(0, (newMaxScroll * targetFraction).toInt().coerceIn(0, newMaxScroll))
                readerScrollView.post {
                    suppressNextScrollProgress = false
                    updateVerticalScrollProgress(readerScrollView.scrollY)
                }
            }
        }
    }

    private fun buildChmReadingBuffer"""
reader, branch_count = old_branch_pattern.subn(new_branch, reader, count=1)
if branch_count != 1:
    raise RuntimeError(f"structured branch: expected 1 match, found {branch_count}")

reader = replace_once(
    reader,
    """        structuredReadingBuffer = loadedContent.structuredReadingBuffer\n        chmCachedFile = loadedContent.chmCachePath?.let(::File)\n""",
    """        structuredReadingBuffer = loadedContent.structuredReadingBuffer\n        if (!format.equals("EPUB", ignoreCase = true)) structuredWholeText = null\n        chmCachedFile = loadedContent.chmCachePath?.let(::File)\n""",
    "applyLoadedContent cache reset",
)

reader = replace_once(
    reader,
    """        private const val TXT_PREFETCH_BACKWARD_FRACTION = 0.12f\n""",
    """        private const val TXT_PREFETCH_BACKWARD_FRACTION = 0.12f\n        private const val STRUCTURED_PREFETCH_FORWARD_FRACTION = 0.86f\n        private const val STRUCTURED_PREFETCH_BACKWARD_FRACTION = 0.14f\n""",
    "structured prefetch constants",
)
reader_path.write_text(reader, encoding="utf-8")


group = group_path.read_text(encoding="utf-8")
for old, new, label in [
    ("import android.graphics.Color\n", "import android.graphics.Color\nimport android.graphics.BitmapFactory\n", "bitmap import"),
    ("import android.os.Bundle\n", "import android.os.Bundle\nimport android.net.Uri\n", "uri import"),
    ("import android.widget.LinearLayout\n", "import android.widget.LinearLayout\nimport android.widget.FrameLayout\nimport android.widget.ImageView\n", "cover view imports"),
    ("import com.simplereader.app.data.db.SimpleReaderDatabase\n", "import com.simplereader.app.data.cache.StructuredBookCache\nimport com.simplereader.app.data.db.SimpleReaderDatabase\n", "cache import"),
    ("import com.simplereader.app.data.repository.BookRepository\n", "import com.simplereader.app.data.repository.BookRepository\nimport com.simplereader.app.parser.EpubParser\n", "parser import"),
]:
    group = replace_once(group, old, new, label)

card_pattern = re.compile(
    r"    private class BookCardView\(parent: ViewGroup\) : LinearLayout\(parent\.context\) \{.*?\n    \}\n\n    private fun dp\(value: Int\)",
    re.S,
)
card_replacement = """    private class BookCardView(parent: ViewGroup) : LinearLayout(parent.context) {
        private val coverFallback = TextView(context)
        private val coverImage = ImageView(context)
        private val title = TextView(context)
        private val progress = TextView(context)
        private var boundBookId: Long = -1L

        init {
            orientation = VERTICAL
            setPadding(dp(3), 0, dp(3), dp(18))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )

            val coverFrame = FrameLayout(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(148))
            }
            coverFallback.apply {
                gravity = Gravity.CENTER
                maxLines = 5
                textSize = 11f
                setTextColor(Color.rgb(232, 238, 244))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.rgb(74, 126, 172), Color.rgb(47, 94, 136))
                ).apply {
                    cornerRadius = dp(5).toFloat()
                }
            }
            coverImage.apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            coverFrame.addView(
                coverFallback,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            coverFrame.addView(
                coverImage,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            addView(coverFrame)

            title.apply {
                textSize = 15f
                maxLines = 2
                setTextColor(Color.rgb(30, 30, 30))
            }
            addView(title)

            progress.apply {
                textSize = 12f
                setTextColor(Color.rgb(130, 126, 118))
            }
            addView(progress)
        }

        fun bind(book: ShelfBookItem) {
            boundBookId = book.id
            coverFallback.text = book.title.take(22)
            coverFallback.visibility = View.VISIBLE
            coverImage.visibility = View.GONE
            coverImage.setImageDrawable(null)
            coverImage.contentDescription = "《${book.title}》封面"
            title.text = book.title
            title.setTextColor(ReaderAppearance.shelfTextColor(context))
            progress.text = "已读 ${book.progressPercent()}%"
            progress.setTextColor(ReaderAppearance.shelfSecondaryTextColor(context))

            if (!book.format.equals("EPUB", ignoreCase = true)) return
            val activity = context as? AppCompatActivity ?: return
            activity.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        StructuredBookCache.coverFile(context, book.id)
                            ?.takeIf { it.isFile }
                            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                            ?: context.contentResolver.openInputStream(Uri.parse(book.filePath))?.use { input ->
                                EpubParser.readCoverImage(input)?.let { bytes ->
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                            }
                    }.getOrNull()
                }
                if (boundBookId == book.id && bitmap != null) {
                    coverImage.setImageBitmap(bitmap)
                    coverImage.visibility = View.VISIBLE
                    coverFallback.visibility = View.GONE
                }
            }
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Int)"""
group, card_count = card_pattern.subn(card_replacement, group, count=1)
if card_count != 1:
    raise RuntimeError(f"group cover card: expected 1 match, found {card_count}")
group_path.write_text(group, encoding="utf-8")

print("v14 EPUB hotfix applied")
