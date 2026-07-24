from pathlib import Path

path = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = path.read_text(encoding="utf-8")

if "import android.text.style.BackgroundColorSpan" not in text:
    text = text.replace(
        "import android.text.style.StyleSpan\n",
        "import android.text.style.StyleSpan\nimport android.text.style.BackgroundColorSpan\n",
        1,
    )
if "import android.widget.Button" not in text:
    text = text.replace(
        "import android.widget.BaseAdapter\n",
        "import android.widget.BaseAdapter\nimport android.widget.Button\n",
        1,
    )

field_marker = "    private var chapterScanJob: Job? = null\n"
field_insert = """    private var chapterScanJob: Job? = null
    private var readerSearchSession: ReaderSearchSession? = null
    private var pendingReaderSearchHighlight: ReaderSearchHighlight? = null
    private var activeReaderSearchHighlight: Boolean = false
"""
if field_marker not in text:
    raise SystemExit("chapterScanJob marker not found")
text = text.replace(field_marker, field_insert, 1)

scroll_old = """        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateVerticalScrollProgress(scrollY)
"""
scroll_new = """        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            clearReaderSearchHighlightOnUserScroll()
            updateVerticalScrollProgress(scrollY)
"""
if scroll_old not in text:
    raise SystemExit("scroll listener marker not found")
text = text.replace(scroll_old, scroll_new, 1)

start = text.index("    private fun showContentSearch() {")
end = text.index("    private fun showContentSearchResults(query: String) {", start)

new_block = r'''    private fun showContentSearch() {
        if (currentContent.isBlank()) {
            Toast.makeText(this, "当前没有可搜索的内容", Toast.LENGTH_SHORT).show()
            return
        }
        showReaderSearchPanel()
    }

    private fun showReaderSearchPanel() {
        val density = resources.displayMetrics.density
        fun localDp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(localDp(8), localDp(6), localDp(8), localDp(4))
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "输入当前书内关键词"
            setSingleLine(true)
            setText(readerSearchSession?.query.orEmpty())
            setSelection(text.length)
        }
        val searchButton = Button(this).apply {
            text = "搜索"
            isAllCaps = false
        }
        searchRow.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        searchRow.addView(
            searchButton,
            LinearLayout.LayoutParams(
                localDp(88),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(110, 100, 84))
            setPadding(localDp(12), localDp(6), localDp(12), localDp(6))
        }
        val rows = mutableListOf<ReaderSearchHit>().apply {
            addAll(readerSearchSession?.hits.orEmpty())
        }
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): ReaderSearchHit = rows[position]
            override fun getItemId(position: Int): Long = rows[position].stableKey.hashCode().toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View {
                val row = (convertView as? TextView) ?: TextView(parent.context).apply {
                    textSize = 15f
                    setTextColor(Color.rgb(38, 35, 31))
                    setPadding(localDp(14), localDp(10), localDp(14), localDp(10))
                    maxLines = 4
                }
                val hit = rows[position]
                row.text = "${position + 1}. ${hit.positionLabel}\n${hit.preview}"
                return row
            }
        }
        val listView = ListView(this).apply {
            adapter = listAdapter
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }

        container.addView(searchRow)
        container.addView(statusView)
        container.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.63f).toInt()
            )
        )

        var dialog: AlertDialog? = null
        var loadJob: Job? = null

        fun saveListPosition() {
            val session = readerSearchSession ?: return
            session.listFirstVisible = listView.firstVisiblePosition.coerceAtLeast(0)
            session.listTopOffset = listView.getChildAt(0)?.top ?: 0
        }

        fun updateStatus() {
            val session = readerSearchSession
            statusView.text = when {
                session == null || session.query.isBlank() ->
                    "输入关键词后点击搜索"
                session.loading ->
                    "正在搜索……已加载 ${rows.size} 条"
                session.endReached && rows.isEmpty() ->
                    "没有找到结果 · 到底了"
                session.endReached ->
                    "共 ${rows.size} 条结果 · 到底了"
                else ->
                    "已加载 ${rows.size} 条，继续向下滑动加载更多"
            }
        }

        fun refreshRows() {
            rows.clear()
            rows.addAll(readerSearchSession?.hits.orEmpty())
            listAdapter.notifyDataSetChanged()
            updateStatus()
        }

        fun loadNextPage() {
            val session = readerSearchSession ?: return
            if (session.loading || session.endReached || session.query.isBlank()) return
            val remaining = MAX_SEARCH_RESULTS - session.hits.size
            if (remaining <= 0) {
                session.endReached = true
                updateStatus()
                return
            }
            session.loading = true
            updateStatus()
            val pageSize = minOf(SEARCH_RESULT_PAGE_SIZE, remaining)
            loadJob?.cancel()
            loadJob = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        when {
                            txtStreamingMode -> {
                                val selectedBook = book
                                val charsetName = txtCharsetName
                                    ?: selectedBook?.txtCharset
                                    ?: Charsets.UTF_8.name()
                                val parsed = selectedBook?.let { activeBook ->
                                    contentResolver.openInputStream(Uri.parse(activeBook.filePath))?.use { stream ->
                                        TxtParser.findTextPage(
                                            inputStream = stream,
                                            charsetName = charsetName,
                                            query = session.query,
                                            startByte = session.nextPosition,
                                            pageSize = pageSize
                                        )
                                    }
                                }
                                if (parsed == null) {
                                    ReaderSearchPage(emptyList(), session.nextPosition, true)
                                } else {
                                    ReaderSearchPage(
                                        hits = parsed.hits.map { hit ->
                                            val percent = if (txtTotalBytes > 0L) {
                                                ((hit.byteOffset.toDouble() / txtTotalBytes) * 100)
                                                    .toInt()
                                                    .coerceIn(0, 100)
                                            } else {
                                                0
                                            }
                                            ReaderSearchHit(
                                                stableKey = "byte:${hit.byteOffset}",
                                                position = hit.byteOffset,
                                                positionLabel = "约 $percent% · 字节 ${hit.byteOffset}",
                                                preview = hit.preview
                                            )
                                        },
                                        nextPosition = parsed.nextByte,
                                        endReached = parsed.endReached
                                    )
                                }
                            }
                            isStructuredChapterDocument() && structuredWholeText != null ->
                                findWholeStructuredSearchPage(
                                    query = session.query,
                                    startIndex = session.nextPosition.toInt(),
                                    pageSize = pageSize
                                )
                            else ->
                                findInMemorySearchPage(
                                    query = session.query,
                                    startIndex = session.nextPosition.toInt(),
                                    pageSize = pageSize
                                )
                        }
                    }
                    if (readerSearchSession !== session) return@launch
                    val merged = (session.hits + page.hits)
                        .distinctBy { it.stableKey }
                        .sortedBy { it.position }
                        .take(MAX_SEARCH_RESULTS)
                    session.hits.clear()
                    session.hits.addAll(merged)
                    val madeProgress = page.nextPosition > session.nextPosition
                    session.nextPosition = page.nextPosition
                    session.endReached = page.endReached ||
                        !madeProgress ||
                        session.hits.size >= MAX_SEARCH_RESULTS
                    refreshRows()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (readerSearchSession === session) {
                        session.endReached = true
                        Toast.makeText(
                            this@ReaderActivity,
                            "搜索失败：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    if (readerSearchSession === session) {
                        session.loading = false
                        updateStatus()
                    }
                }
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val session = readerSearchSession ?: return@setOnItemClickListener
            val hit = rows.getOrNull(position) ?: return@setOnItemClickListener
            saveListPosition()
            pendingReaderSearchHighlight = ReaderSearchHighlight(
                query = session.query,
                stableKey = hit.stableKey,
                position = hit.position
            )
            activeReaderSearchHighlight = false
            dialog?.dismiss()
            jumpToReaderSearchHit(hit)
        }

        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(
                view: android.widget.AbsListView?,
                scrollState: Int
            ) = Unit

            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                val session = readerSearchSession ?: return
                session.listFirstVisible = firstVisibleItem.coerceAtLeast(0)
                session.listTopOffset = listView.getChildAt(0)?.top ?: 0
                if (
                    totalItemCount > 0 &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - 4
                ) {
                    loadNextPage()
                }
            }
        })

        searchButton.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            clearReaderSearchHighlight()
            readerSearchSession = ReaderSearchSession(query = query)
            refreshRows()
            loadNextPage()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("书内搜索")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.setOnDismissListener {
            saveListPosition()
            loadJob?.cancel()
            readerSearchSession?.loading = false
        }
        dialog?.show()

        val session = readerSearchSession
        updateStatus()
        if (session != null) {
            listView.post {
                listView.setSelectionFromTop(
                    session.listFirstVisible.coerceIn(0, rows.lastIndex.coerceAtLeast(0)),
                    session.listTopOffset
                )
                if (rows.isEmpty() && !session.endReached) {
                    loadNextPage()
                }
            }
        }
    }

    private fun jumpToReaderSearchHit(hit: ReaderSearchHit) {
        readerControls.visibility = View.GONE
        when {
            txtStreamingMode -> {
                showStreamingTxtPage(
                    hit.position,
                    saveImmediately = true,
                    keepContextBeforeTarget = true
                )
            }
            isStructuredChapterDocument() &&
                structuredWholeText != null &&
                hit.stableKey.startsWith("whole:") -> {
                val wholeText = structuredWholeText ?: return
                val globalPosition = hit.position.toInt().coerceIn(0, wholeText.length)
                val chapterIndex = epubChapterStartPositions
                    .indexOfLast { it <= globalPosition }
                    .coerceAtLeast(0)
                    .coerceAtMost(epubChapters.lastIndex)
                val chapterStart = epubChapterStartPositions.getOrElse(chapterIndex) { 0 }
                loadStructuredChapter(
                    chapterIndex = chapterIndex,
                    offset = (globalPosition - chapterStart).coerceAtLeast(0),
                    saveImmediately = true
                )
            }
            else -> {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
            }
        }
    }

    private fun findWholeStructuredSearchPage(
        query: String,
        startIndex: Int,
        pageSize: Int
    ): ReaderSearchPage {
        val wholeText = structuredWholeText
            ?: return ReaderSearchPage(emptyList(), startIndex.toLong(), true)
        if (query.isBlank() || wholeText.isEmpty()) {
            return ReaderSearchPage(emptyList(), startIndex.toLong(), true)
        }
        val hits = mutableListOf<ReaderSearchHit>()
        var cursor = startIndex.coerceIn(0, wholeText.length)
        var endReached = false
        while (hits.size < pageSize) {
            val index = wholeText.indexOf(
                query,
                startIndex = cursor,
                ignoreCase = true
            )
            if (index < 0) {
                endReached = true
                break
            }
            val previewStart = (index - 45).coerceAtLeast(0)
            val previewEnd = (index + query.length + 90).coerceAtMost(wholeText.length)
            val preview = wholeText.substring(previewStart, previewEnd)
                .replace(Regex("\\s+"), " ")
                .trim()
            val chapterIndex = epubChapterStartPositions
                .indexOfLast { it <= index }
                .coerceAtLeast(0)
                .coerceAtMost(epubChapters.lastIndex)
            val chapterTitle = epubChapters.getOrNull(chapterIndex)
                ?.name
                ?.substringAfterLast('/')
                .orEmpty()
                .ifBlank { "第 ${chapterIndex + 1} 章" }
            val percent = ((index.toDouble() / wholeText.length) * 100)
                .toInt()
                .coerceIn(0, 100)
            hits += ReaderSearchHit(
                stableKey = "whole:$index",
                position = index.toLong(),
                positionLabel = "$chapterTitle · 约 $percent%",
                preview = preview.ifBlank { "位置 $index" }
            )
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(wholeText.length)
            if (cursor >= wholeText.length) {
                endReached = true
                break
            }
        }
        return ReaderSearchPage(
            hits = hits,
            nextPosition = cursor.toLong(),
            endReached = endReached
        )
    }

    private fun scheduleReaderSearchHighlight() {
        val pending = pendingReaderSearchHighlight ?: return
        contentView.post {
            if (pendingReaderSearchHighlight == pending) {
                applyReaderSearchHighlight(pending)
            }
        }
    }

    private fun applyReaderSearchHighlight(highlight: ReaderSearchHighlight) {
        val displayedText = contentView.text.toString()
        if (displayedText.isEmpty() || highlight.query.isBlank()) {
            pendingReaderSearchHighlight = null
            return
        }
        val anchor = readerSearchDisplayAnchor(highlight, displayedText.length)
        val start = ReaderSearchSupport.nearestMatch(
            text = displayedText,
            query = highlight.query,
            anchor = anchor,
            ignoreCase = true
        )
        if (start < 0) {
            pendingReaderSearchHighlight = null
            return
        }
        val end = (start + highlight.query.length).coerceAtMost(displayedText.length)
        val styled = SpannableString(displayedText).apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                BackgroundColorSpan(Color.rgb(255, 232, 125)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        pendingReaderSearchHighlight = null
        activeReaderSearchHighlight = true
        contentView.text = styled
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            contentView.post {
                val layout = contentView.layout ?: return@post
                val line = layout.getLineForOffset(start.coerceIn(0, displayedText.length))
                val lineTop = layout.getLineTop(line)
                val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                val targetScroll = (lineTop - readerScrollView.height / 3).coerceIn(0, maxScroll)
                suppressNextScrollProgress = true
                readerScrollView.scrollTo(0, targetScroll)
                readerScrollView.post { suppressNextScrollProgress = false }
            }
        }
    }

    private fun readerSearchDisplayAnchor(
        highlight: ReaderSearchHighlight,
        displayLength: Int
    ): Int {
        val bufferPosition = when {
            txtStreamingMode -> {
                val byteSpan = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
                val fraction = (
                    (highlight.position - txtCurrentPageStartByte).toDouble() /
                        byteSpan.toDouble()
                    ).coerceIn(0.0, 1.0)
                (currentContent.length * fraction).toInt()
            }
            highlight.stableKey.startsWith("whole:") &&
                isStructuredChapterDocument() -> {
                val globalPosition = highlight.position.toInt().coerceAtLeast(0)
                if (structuredWholeBookMode) {
                    globalPosition
                } else {
                    val chapterIndex = epubChapterStartPositions
                        .indexOfLast { it <= globalPosition }
                        .coerceAtLeast(0)
                        .coerceAtMost(epubChapters.lastIndex)
                    val chapterStart = epubChapterStartPositions.getOrElse(chapterIndex) { 0 }
                    structuredReadingBuffer
                        ?.positionFor(chapterIndex, (globalPosition - chapterStart).coerceAtLeast(0))
                        ?: currentPosition
                }
            }
            else -> highlight.position.toInt().coerceAtLeast(0)
        }
        val displayAnchor = if (
            txtStreamingMode ||
            pageTurnMode == TURN_MODE_VERTICAL
        ) {
            bufferPosition
        } else {
            bufferPosition - currentPosition
        }
        return displayAnchor.coerceIn(0, displayLength)
    }

    private fun clearReaderSearchHighlightOnUserScroll() {
        if (suppressNextScrollProgress || !activeReaderSearchHighlight) return
        clearReaderSearchHighlight()
    }

    private fun clearReaderSearchHighlight() {
        if (!activeReaderSearchHighlight) return
        val oldScrollY = readerScrollView.scrollY
        activeReaderSearchHighlight = false
        contentView.text = contentView.text.toString()
        contentView.post {
            val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
            readerScrollView.scrollTo(0, oldScrollY.coerceIn(0, maxScroll))
        }
    }

'''
text = text[:start] + new_block + text[end:]

display_start = text.index("    private fun displayContent() {")
display_end = text.index("    private fun updateProgressViews", display_start)
display_block = text[display_start:display_end]
display_block = display_block.replace(
    "    private fun displayContent() {\n        val continuous = pageTurnMode == TURN_MODE_VERTICAL\n",
    "    private fun displayContent() {\n        activeReaderSearchHighlight = false\n        val continuous = pageTurnMode == TURN_MODE_VERTICAL\n",
    1,
)
display_block = display_block.replace(
    "            updateProgressViews(progress.coerceIn(0, 1000))\n            return\n",
    "            updateProgressViews(progress.coerceIn(0, 1000))\n            scheduleReaderSearchHighlight()\n            return\n",
    1,
)
display_block = display_block.replace(
    """        if (currentContent.isNotEmpty()) {
            updateProgressViews(progressForCurrentPosition())
        }
    }

""",
    """        if (currentContent.isNotEmpty()) {
            updateProgressViews(progressForCurrentPosition())
        }
        scheduleReaderSearchHighlight()
    }

""",
    1,
)
text = text[:display_start] + display_block + text[display_end:]

destroy_marker = "    override fun onStop() {\n"
destroy_insert = """    override fun onDestroy() {
        readerSearchSession = null
        pendingReaderSearchHighlight = null
        activeReaderSearchHighlight = false
        super.onDestroy()
    }

    override fun onStop() {
"""
if destroy_marker not in text:
    raise SystemExit("onStop marker not found")
text = text.replace(destroy_marker, destroy_insert, 1)

old_data = """    private data class RetainedReaderSearch(
        val query: String,
        val hits: List<ReaderSearchHit>
    )

"""
new_data = """    private data class ReaderSearchSession(
        val query: String,
        val hits: MutableList<ReaderSearchHit> = mutableListOf(),
        var nextPosition: Long = 0L,
        var endReached: Boolean = false,
        var loading: Boolean = false,
        var listFirstVisible: Int = 0,
        var listTopOffset: Int = 0
    )

    private data class ReaderSearchHighlight(
        val query: String,
        val stableKey: String,
        val position: Long
    )

"""
if old_data not in text:
    raise SystemExit("RetainedReaderSearch data class not found")
text = text.replace(old_data, new_data, 1)
text = text.replace(
    "        private const val SEARCH_RESULT_PAGE_SIZE = 200\n",
    "        private const val SEARCH_RESULT_PAGE_SIZE = 40\n",
    1,
)
text = text.replace(
    '        private const val SEARCH_RESULTS_PREFS = "reader_search_results"\n',
    "",
    1,
)

path.write_text(text, encoding="utf-8")
print("patched ReaderActivity.kt")
