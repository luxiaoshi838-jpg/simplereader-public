val applyReaderPagedSearch = tasks.register("applyReaderPagedSearch") {
    doLast {
        val readerFile = file("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        var source = readerFile.readText()

        val startMarker = "    private fun showContentSearch() {"
        val endMarker = "    private fun jumpChapter(direction: Int) {"
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(start >= 0 && end > start) { "无法替换书内搜索实现" }

        val replacement = """    private fun showContentSearch() {
        if (currentContent.isBlank()) {
            Toast.makeText(this, "当前没有可搜索的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "输入要查找的文字"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("搜索书内内容")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("显示全部结果") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                } else {
                    showContentSearchResults(query)
                }
            }
            .show()
    }

    private fun showContentSearchResults(query: String) {
        val density = resources.displayMetrics.density
        fun localDp(value: Int) = (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(localDp(8), localDp(4), localDp(8), localDp(4))
        }
        val statusView = TextView(this).apply {
            text = "正在搜索…"
            textSize = 14f
            setTextColor(Color.rgb(110, 100, 84))
            setPadding(localDp(12), localDp(8), localDp(12), localDp(8))
        }
        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            this.layoutManager = layoutManager
            itemAnimator = null
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        container.addView(statusView)
        container.addView(
            recyclerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.68f).toInt()
            )
        )

        var dialog: AlertDialog? = null
        var loading = false
        var endReached = false
        var nextPosition = 0L
        var loadJob: Job? = null

        val adapter = ReaderSearchResultAdapter { hit ->
            dialog?.dismiss()
            if (txtStreamingMode) {
                showStreamingTxtPage(hit.position, saveImmediately = true)
            } else {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
                readerControls.visibility = View.GONE
            }
        }
        recyclerView.adapter = adapter

        fun updateStatus() {
            statusView.text = when {
                adapter.currentList.isEmpty() && endReached -> "没有找到“${'$'}query”"
                endReached -> "共 ${'$'}{adapter.itemCount} 条结果"
                loading -> "正在加载更多结果…"
                else -> "已加载 ${'$'}{adapter.itemCount} 条，继续下拉加载"
            }
        }

        fun loadNextPage() {
            if (loading || endReached) return
            loading = true
            updateStatus()
            loadJob = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        if (txtStreamingMode) {
                            val selectedBook = book
                            val charsetName = txtCharsetName
                                ?: selectedBook?.txtCharset
                                ?: Charsets.UTF_8.name()
                            val parsed = selectedBook?.let { activeBook ->
                                contentResolver.openInputStream(Uri.parse(activeBook.filePath))?.let { stream ->
                                    TxtParser.findTextPage(
                                        inputStream = stream,
                                        charsetName = charsetName,
                                        query = query,
                                        startByte = nextPosition,
                                        pageSize = 40
                                    )
                                }
                            }
                            if (parsed == null) {
                                ReaderSearchPage(emptyList(), nextPosition, true)
                            } else {
                                ReaderSearchPage(
                                    hits = parsed.hits.map { hit ->
                                        val percent = if (txtTotalBytes > 0L) {
                                            ((hit.byteOffset.toDouble() / txtTotalBytes) * 100).toInt()
                                                .coerceIn(0, 100)
                                        } else 0
                                        ReaderSearchHit(
                                            stableKey = "byte:${'$'}{hit.byteOffset}",
                                            position = hit.byteOffset,
                                            positionLabel = "约 ${'$'}percent% · 字节 ${'$'}{hit.byteOffset}",
                                            preview = hit.preview
                                        )
                                    },
                                    nextPosition = parsed.nextByte,
                                    endReached = parsed.endReached
                                )
                            }
                        } else {
                            findInMemorySearchPage(query, nextPosition.toInt(), 40)
                        }
                    }

                    val merged = (adapter.currentList + page.hits)
                        .distinctBy { it.stableKey }
                    adapter.submitList(merged)
                    nextPosition = page.nextPosition
                    endReached = page.endReached
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    endReached = true
                    Toast.makeText(
                        this@ReaderActivity,
                        "搜索失败：${'$'}{error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    loading = false
                    updateStatus()
                    recyclerView.post {
                        if (!endReached && !recyclerView.canScrollVertically(1)) {
                            loadNextPage()
                        }
                    }
                }
            }
        }

        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(view: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(view, dx, dy)
                if (dy < 0 || loading || endReached) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - 6) {
                    loadNextPage()
                }
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("“${'$'}query”的搜索结果")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.setOnDismissListener { loadJob?.cancel() }
        dialog?.show()
        loadNextPage()
    }

    private fun findInMemorySearchPage(
        query: String,
        startIndex: Int,
        pageSize: Int
    ): ReaderSearchPage {
        if (query.isBlank() || currentContent.isEmpty()) {
            return ReaderSearchPage(emptyList(), startIndex.toLong(), true)
        }
        val hits = mutableListOf<ReaderSearchHit>()
        var cursor = startIndex.coerceIn(0, currentContent.length)
        var endReached = false
        while (hits.size < pageSize) {
            val index = currentContent.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (index < 0) {
                endReached = true
                break
            }
            val previewStart = (index - 45).coerceAtLeast(0)
            val previewEnd = (index + query.length + 90).coerceAtMost(currentContent.length)
            val preview = currentContent.substring(previewStart, previewEnd)
                .replace(Regex("\\s+"), " ")
                .trim()
            val percent = if (currentContent.isNotEmpty()) {
                ((index.toDouble() / currentContent.length) * 100).toInt().coerceIn(0, 100)
            } else 0
            hits += ReaderSearchHit(
                stableKey = "char:${'$'}index",
                position = index.toLong(),
                positionLabel = "约 ${'$'}percent% · 位置 ${'$'}index",
                preview = preview.ifBlank { "位置 ${'$'}index" }
            )
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(currentContent.length)
            if (cursor >= currentContent.length) {
                endReached = true
                break
            }
        }
        return ReaderSearchPage(
            hits = hits,
            nextPosition = cursor.toLong(),
            endReached = endReached
        )
    }"""

        source = source.substring(0, start) + replacement.trimEnd() + "\n\n" + source.substring(end)
        readerFile.writeText(source)
    }
}

applyReaderPagedSearch.configure {
    mustRunAfter("applyReaderBookmarkAndContinuousScrollFixes")
    mustRunAfter("applyRuntimeUiPatches")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(applyReaderPagedSearch)
}
