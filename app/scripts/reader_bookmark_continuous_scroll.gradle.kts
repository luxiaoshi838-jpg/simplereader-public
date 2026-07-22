val applyReaderBookmarkAndContinuousScrollFixes = tasks.register("applyReaderBookmarkAndContinuousScrollFixes") {
    doLast {
        val layoutFile = file("src/main/res/layout/activity_reader.xml")
        var layoutSource = layoutFile.readText()
        if (!layoutSource.contains("@+id/readerScrollView")) {
            val textViewStartMarker = "    <TextView\n        android:id=\"@+id/contentView\""
            val textViewStart = layoutSource.indexOf(textViewStartMarker)
            val textViewEnd = layoutSource.indexOf(" />", textViewStart).let { index ->
                if (index >= 0) index + 3 else -1
            }
            check(textViewStart >= 0 && textViewEnd > textViewStart) {
                "无法将阅读正文替换为 AndroidX NestedScrollView"
            }
            val continuousScrollLayout = """    <androidx.core.widget.NestedScrollView
        android:id="@+id/readerScrollView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clickable="true"
        android:clipToPadding="false"
        android:fillViewport="true"
        android:focusable="true"
        android:overScrollMode="ifContentScrolls"
        android:scrollbars="vertical"
        android:smoothScrollingEnabled="true">

        <TextView
            android:id="@+id/contentView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:lineSpacingMultiplier="1.75"
            android:paddingStart="28dp"
            android:paddingTop="26dp"
            android:paddingEnd="28dp"
            android:paddingBottom="118dp"
            android:textColor="#3B3428"
            android:textSize="20sp" />
    </androidx.core.widget.NestedScrollView>"""
            layoutSource = layoutSource.substring(0, textViewStart) +
                continuousScrollLayout +
                layoutSource.substring(textViewEnd)
            layoutFile.writeText(layoutSource)
        }

        val readerFile = file("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        var readerSource = readerFile.readText()

        fun replaceSection(startMarker: String, endMarker: String, replacement: String) {
            val start = readerSource.indexOf(startMarker)
            val end = readerSource.indexOf(endMarker, start + startMarker.length)
            check(start >= 0 && end > start) {
                "无法替换阅读页代码区段：$startMarker"
            }
            readerSource = readerSource.substring(0, start) +
                replacement.trimEnd() + "\n\n" +
                readerSource.substring(end)
        }

        readerSource = readerSource.replace("import android.text.method.ScrollingMovementMethod\n", "")
        if (!readerSource.contains("import androidx.core.widget.NestedScrollView")) {
            readerSource = readerSource.replace(
                "import androidx.documentfile.provider.DocumentFile\n",
                "import androidx.documentfile.provider.DocumentFile\nimport androidx.core.widget.NestedScrollView\n"
            )
        }
        if (!readerSource.contains("import androidx.room.withTransaction")) {
            readerSource = readerSource.replace(
                "import androidx.lifecycle.lifecycleScope\n",
                "import androidx.lifecycle.lifecycleScope\nimport androidx.room.withTransaction\n"
            )
        }

        if (!readerSource.contains("private lateinit var readerScrollView: NestedScrollView")) {
            readerSource = readerSource.replace(
                "    private lateinit var contentView: TextView\n",
                "    private lateinit var contentView: TextView\n    private lateinit var readerScrollView: NestedScrollView\n"
            )
        }
        if (!readerSource.contains("readerScrollView = findViewById(R.id.readerScrollView)")) {
            readerSource = readerSource.replace(
                "        contentView = findViewById(R.id.contentView)\n",
                "        contentView = findViewById(R.id.contentView)\n        readerScrollView = findViewById(R.id.readerScrollView)\n"
            )
        }

        val oldTouchSetup = """        contentView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            pageTurnMode != TURN_MODE_VERTICAL || readerControls.visibility == View.VISIBLE
        }
        contentView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateVerticalScrollProgress(scrollY)
        }"""
        val newTouchSetup = """        readerScrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            pageTurnMode != TURN_MODE_VERTICAL
        }
        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateVerticalScrollProgress(scrollY)
        }"""
        check(readerSource.contains(oldTouchSetup) || readerSource.contains(newTouchSetup)) {
            "无法接入 AndroidX NestedScrollView 触摸与滚动监听"
        }
        readerSource = readerSource.replace(oldTouchSetup, newTouchSetup)

        replaceSection(
            "    private fun displayContent() {",
            "    private fun updateProgressViews(",
            """    private fun displayContent() {
        val continuous = pageTurnMode == TURN_MODE_VERTICAL
        if (txtStreamingMode) {
            contentView.text = currentContent
            contentView.textSize = readerTextSize
            configureVerticalScrollIfNeeded()
            if (continuous) {
                readerScrollView.post {
                    val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                    val windowBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
                    val fraction = ((currentPosition.toLong() - txtCurrentPageStartByte).toDouble() / windowBytes)
                        .coerceIn(0.0, 1.0)
                    readerScrollView.scrollTo(0, (maxScroll * fraction).toInt().coerceIn(0, maxScroll))
                }
            } else {
                readerScrollView.scrollTo(0, 0)
            }
            val progress = if (txtTotalBytes > 0L) {
                ((currentPosition.toFloat() / txtTotalBytes) * 1000).toInt()
            } else {
                0
            }
            updateProgressViews(progress.coerceIn(0, 1000))
            return
        }

        if (continuous) {
            contentView.text = currentContent
            contentView.post {
                val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
                if (maxScroll > 0 && currentContent.isNotEmpty()) {
                    val fraction = (currentPosition.toFloat() / currentContent.length).coerceIn(0f, 1f)
                    readerScrollView.scrollTo(0, (maxScroll * fraction).toInt().coerceIn(0, maxScroll))
                }
            }
        } else {
            val endPosition = (currentPosition + pageSize).coerceAtMost(currentContent.length)
            val safeStart = currentPosition.coerceIn(0, endPosition)
            contentView.text = currentContent.substring(safeStart, endPosition)
            readerScrollView.scrollTo(0, 0)
        }
        contentView.textSize = readerTextSize
        configureVerticalScrollIfNeeded()
        if (currentContent.isNotEmpty()) {
            updateProgressViews(
                ((currentPosition.toFloat() / currentContent.length) * 1000).toInt().coerceIn(0, 1000)
            )
        }
    }"""
        )

        replaceSection(
            "    private fun configureVerticalScrollIfNeeded() {",
            "    private fun progressForCurrentPosition()",
            """    private fun configureVerticalScrollIfNeeded() {
        val continuous = pageTurnMode == TURN_MODE_VERTICAL
        contentView.movementMethod = null
        contentView.isVerticalScrollBarEnabled = false
        readerScrollView.isVerticalScrollBarEnabled = continuous
        readerScrollView.isScrollbarFadingEnabled = false
        readerScrollView.isSmoothScrollingEnabled = true
        readerScrollView.overScrollMode = if (continuous) {
            View.OVER_SCROLL_IF_CONTENT_SCROLLS
        } else {
            View.OVER_SCROLL_NEVER
        }
    }

    private fun updateVerticalScrollProgress(scrollY: Int) {
        if (pageTurnMode != TURN_MODE_VERTICAL || !openSucceeded || currentContent.isBlank()) return
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        if (maxScroll <= 0) return
        val scrollProgress = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        currentPosition = if (txtStreamingMode) {
            val windowStart = txtCurrentPageStartByte
            val windowBytes = (txtCurrentPageEndByte - windowStart).coerceAtLeast(1L)
            (windowStart + (windowBytes * scrollProgress).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            (currentContent.length * scrollProgress).toInt().coerceIn(0, currentContent.length)
        }
        updateProgressViews(progressForCurrentPosition())
        markProgressDirty()
        scheduleProgressSave()
    }"""
        )

        replaceSection(
            "    private fun addBookmark() {",
            "    private fun showBookmarks() {",
            """    private fun addBookmark() {
        if (!openSucceeded || currentContent.isBlank() || bookId <= 0L) {
            Toast.makeText(this, "当前没有可添加书签的内容", Toast.LENGTH_SHORT).show()
            return
        }

        val positionToSave = currentPosition.coerceAtLeast(0)
        val preview = bookmarkPreviewAt(positionToSave)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        check(database.bookDao().getBook(bookId) != null) { "书籍记录不存在" }
                        database.bookmarkDao().insert(
                            Bookmark(
                                bookId = bookId,
                                position = positionToSave.toString(),
                                content = preview
                            )
                        )
                    }
                }
                Toast.makeText(this@ReaderActivity, "已添加书签", Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    this@ReaderActivity,
                    "添加书签失败：${'$'}{error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun bookmarkPreviewAt(globalPosition: Int): String {
        if (currentContent.isEmpty()) return "位置 ${'$'}globalPosition"
        val localStart = if (txtStreamingMode) {
            val windowBytes = (txtCurrentPageEndByte - txtCurrentPageStartByte).coerceAtLeast(1L)
            val fraction = ((globalPosition.toLong() - txtCurrentPageStartByte).toDouble() / windowBytes)
                .coerceIn(0.0, 1.0)
            (currentContent.length * fraction).toInt()
        } else {
            globalPosition
        }.coerceIn(0, currentContent.length)

        val readableStart = if (localStart >= currentContent.length && currentContent.isNotEmpty()) {
            (currentContent.length - 1).coerceAtLeast(0)
        } else {
            localStart
        }
        val end = (readableStart + 120).coerceAtMost(currentContent.length)
        return currentContent.substring(readableStart, end)
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "位置 ${'$'}globalPosition" }
    }"""
        )

        replaceSection(
            "    private fun scrollContinuousPage(direction: Int) {",
            "    private fun setupReaderSettingsPanel() {",
            """    private fun scrollContinuousPage(direction: Int) {
        val maxScroll = (contentView.height - readerScrollView.height).coerceAtLeast(0)
        val distance = (readerScrollView.height * 0.78f).toInt().coerceAtLeast(1)
        val target = (readerScrollView.scrollY + direction * distance).coerceIn(0, maxScroll)
        if (target != readerScrollView.scrollY) {
            readerScrollView.smoothScrollTo(0, target)
            return
        }

        if (txtStreamingMode && direction > 0 && txtCurrentPageEndByte < txtTotalBytes) {
            showStreamingTxtPage(txtCurrentPageEndByte, direction = 1)
        } else if (txtStreamingMode && direction < 0 && txtCurrentPageStartByte > 0L) {
            showStreamingTxtPage(
                (txtCurrentPageStartByte - TXT_STREAM_WINDOW_BYTES).coerceAtLeast(0L),
                direction = -1
            )
        } else {
            Toast.makeText(
                this,
                if (direction > 0) "已经到末尾" else "已经到开头",
                Toast.LENGTH_SHORT
            ).show()
        }
    }"""
        )

        replaceSection(
            "    private fun setTurnMode(mode: String) {",
            "    private fun updateSettingsLabels() {",
            """    private fun setTurnMode(mode: String) {
        pageTurnMode = mode
        saveReaderPrefs()
        updateSettingsLabels()
        displayContent()
        val label = if (mode == TURN_MODE_VERTICAL) "连续滚动" else turnModeLabel(mode)
        Toast.makeText(this, "阅读模式：${'$'}label", Toast.LENGTH_SHORT).show()
    }"""
        )

        replaceSection(
            "    override fun onSingleTapUp(e: MotionEvent): Boolean {",
            "    override fun onScroll(",
            """    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            toggleReaderControls()
            return true
        }
        val width = readerScrollView.width.takeIf { it > 0 } ?: return false
        val leftBoundary = width / 3
        val rightBoundary = width * 2 / 3
        if (e.x < leftBoundary) {
            previousPage()
        } else if (e.x > rightBoundary) {
            nextPage()
        } else {
            toggleReaderControls()
        }
        return true
    }"""
        )

        val oldFlingStart = """    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        val start = e1 ?: return false"""
        val newFlingStart = """    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        if (pageTurnMode == TURN_MODE_VERTICAL) return false
        val start = e1 ?: return false"""
        check(readerSource.contains(oldFlingStart) || readerSource.contains(newFlingStart)) {
            "无法关闭连续滚动模式中的翻页 fling"
        }
        readerSource = readerSource.replace(oldFlingStart, newFlingStart)

        readerFile.writeText(readerSource)
    }
}

applyReaderBookmarkAndContinuousScrollFixes.configure {
    mustRunAfter("applyRuntimeUiPatches")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(applyReaderBookmarkAndContinuousScrollFixes)
}
