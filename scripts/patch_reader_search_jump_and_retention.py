from pathlib import Path

path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
text = path.read_text(encoding='utf-8')

old = '''    private fun showContentSearch() {
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
                    showAllContentSearchResults(query)
                }
            }
            .show()
    }

    private fun showAllContentSearchResults(query: String) {
'''
new = '''    private fun showContentSearch() {
        if (currentContent.isBlank()) {
            Toast.makeText(this, "当前没有可搜索的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val retained = loadRetainedSearch()
        val input = EditText(this).apply {
            hint = "输入要查找的文字"
            setSingleLine(true)
            retained?.query?.takeIf { it.isNotBlank() }?.let(::setText)
            setSelection(text.length)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("搜索书内内容")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("重新搜索") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                } else {
                    showAllContentSearchResults(query)
                }
            }
        if (retained != null && retained.hits.isNotEmpty()) {
            builder.setNeutralButton("上次结果（${retained.hits.size}）") { _, _ ->
                showAllContentSearchResults(retained.query, retained.hits)
            }
        }
        builder.show()
    }

    private fun showAllContentSearchResults(
        query: String,
        retainedHits: List<ReaderSearchHit>? = null
    ) {
'''
if old not in text:
    raise SystemExit('search dialog block not found')
text = text.replace(old, new, 1)

old = '''        fun refreshRows(ordered: List<ReaderSearchHit>, message: String) {
            hits.clear()
            hits.addAll(ordered)
            listAdapter.notifyDataSetChanged()
            statusView.text = message
        }

        fun openHit(hit: ReaderSearchHit) {
            pageTurnMode = TURN_MODE_VERTICAL
            saveReaderPrefs()
            updateSettingsLabels()
            if (txtStreamingMode) {
                showStreamingTxtPage(hit.position, saveImmediately = true, keepContextBeforeTarget = true)
            } else {
                currentPosition = hit.position.toInt().coerceIn(0, currentContent.length)
                displayContent()
                markProgressDirty()
                saveProgressNow()
                readerControls.visibility = View.GONE
            }
            statusView.text = "Selected ${hits.indexOf(hit) + 1} / ${hits.size}"
        }
'''
new = '''        fun refreshRows(ordered: List<ReaderSearchHit>, message: String, persist: Boolean = true) {
            hits.clear()
            hits.addAll(ordered)
            listAdapter.notifyDataSetChanged()
            statusView.text = message
            if (persist) saveRetainedSearch(query, ordered)
        }

        fun openHit(hit: ReaderSearchHit) {
            dialog?.dismiss()
            readerControls.visibility = View.GONE
            when {
                txtStreamingMode -> {
                    showStreamingTxtPage(hit.position, saveImmediately = true, keepContextBeforeTarget = true)
                }
                isStructuredChapterDocument() && structuredWholeText != null &&
                    hit.stableKey.startsWith("whole:") -> {
                    val globalPosition = hit.position.toInt().coerceIn(0, structuredWholeText!!.length)
                    val chapterIndex = epubChapterStartPositions.indexOfLast { it <= globalPosition }
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
'''
if old not in text:
    raise SystemExit('refresh/open block not found')
text = text.replace(old, new, 1)

old = '''        dialog.show()

        loadJob = lifecycleScope.launch {
            val results = mutableListOf<ReaderSearchHit>()
'''
new = '''        dialog.show()

        if (retainedHits != null) {
            val ordered = retainedHits.distinctBy { it.stableKey }
                .sortedBy { it.position }
                .take(MAX_SEARCH_RESULTS)
            refreshRows(ordered, "已保留 ${ordered.size} 条结果", persist = false)
            return
        }

        loadJob = lifecycleScope.launch {
            val results = mutableListOf<ReaderSearchHit>()
'''
if old not in text:
    raise SystemExit('dialog/load block not found')
text = text.replace(old, new, 1)

old = '''                    } else {
                        results += findInMemorySearchAll(query)
                    }
'''
new = '''                    } else if (isStructuredChapterDocument() && structuredWholeText != null) {
                        results += findWholeStructuredSearchAll(query)
                    } else {
                        results += findInMemorySearchAll(query)
                    }
'''
if old not in text:
    raise SystemExit('search source block not found')
text = text.replace(old, new, 1)

marker = '''    private fun findInMemorySearchAll(query: String): List<ReaderSearchHit> {
'''
insert = '''    private fun findWholeStructuredSearchAll(query: String): List<ReaderSearchHit> {
        val wholeText = structuredWholeText ?: return emptyList()
        if (query.isBlank() || wholeText.isEmpty()) return emptyList()
        val hits = mutableListOf<ReaderSearchHit>()
        var cursor = 0
        while (cursor < wholeText.length && hits.size < MAX_SEARCH_RESULTS) {
            val index = wholeText.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (index < 0) break
            val previewStart = (index - 45).coerceAtLeast(0)
            val previewEnd = (index + query.length + 90).coerceAtMost(wholeText.length)
            val preview = wholeText.substring(previewStart, previewEnd)
                .replace(Regex("\\s+"), " ")
                .trim()
            val chapterIndex = epubChapterStartPositions.indexOfLast { it <= index }
                .coerceAtLeast(0)
                .coerceAtMost(epubChapters.lastIndex)
            val chapterTitle = epubChapters.getOrNull(chapterIndex)?.text
                ?.ifBlank { epubChapters.getOrNull(chapterIndex)?.name?.substringAfterLast('/') ?: "" }
                .orEmpty()
                .ifBlank { "第 ${chapterIndex + 1} 章" }
            val percent = ((index.toDouble() / wholeText.length) * 100).toInt().coerceIn(0, 100)
            hits += ReaderSearchHit(
                stableKey = "whole:$index",
                position = index.toLong(),
                positionLabel = "$chapterTitle · 约 $percent%",
                preview = preview.ifBlank { "位置 $index" }
            )
            cursor = (index + query.length.coerceAtLeast(1)).coerceAtMost(wholeText.length)
        }
        return hits
    }

'''
if marker not in text:
    raise SystemExit('findInMemory marker not found')
text = text.replace(marker, insert + marker, 1)

marker = '''    private fun findInMemorySearchAll(query: String): List<ReaderSearchHit> {
'''
# persistence helpers placed before the search function family
helpers = '''    private fun saveRetainedSearch(query: String, hits: List<ReaderSearchHit>) {
        if (bookId <= 0L) return
        val items = JSONArray()
        hits.take(MAX_SEARCH_RESULTS).forEach { hit ->
            items.put(
                JSONObject()
                    .put("stableKey", hit.stableKey)
                    .put("position", hit.position)
                    .put("positionLabel", hit.positionLabel)
                    .put("preview", hit.preview)
            )
        }
        val payload = JSONObject()
            .put("query", query)
            .put("hits", items)
        getSharedPreferences(SEARCH_RESULTS_PREFS, MODE_PRIVATE)
            .edit()
            .putString("book:$bookId", payload.toString())
            .apply()
    }

    private fun loadRetainedSearch(): RetainedReaderSearch? {
        if (bookId <= 0L) return null
        return runCatching {
            val raw = getSharedPreferences(SEARCH_RESULTS_PREFS, MODE_PRIVATE)
                .getString("book:$bookId", null)
                ?: return null
            val payload = JSONObject(raw)
            val query = payload.optString("query")
            val items = payload.optJSONArray("hits") ?: JSONArray()
            val hits = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    add(
                        ReaderSearchHit(
                            stableKey = item.optString("stableKey"),
                            position = item.optLong("position"),
                            positionLabel = item.optString("positionLabel"),
                            preview = item.optString("preview")
                        )
                    )
                }
            }
            RetainedReaderSearch(query, hits)
        }.getOrNull()
    }

'''
text = text.replace(marker, helpers + marker, 1)

old = '''        private const val READER_PREFS = "reader_prefs"
'''
new = '''        private const val READER_PREFS = "reader_prefs"
        private const val SEARCH_RESULTS_PREFS = "reader_search_results"
'''
if old not in text:
    raise SystemExit('prefs constant marker not found')
text = text.replace(old, new, 1)

old = '''    private data class LoadedContent(
'''
new = '''    private data class RetainedReaderSearch(
        val query: String,
        val hits: List<ReaderSearchHit>
    )

    private data class LoadedContent(
'''
if old not in text:
    raise SystemExit('LoadedContent marker not found')
text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
print('patched ReaderActivity.kt')
