from pathlib import Path


def require_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'{label} not found')
    return text.replace(old, new, 1)

main_path = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt')
main = main_path.read_text()
if 'import com.simplereader.app.reader.cache.ReaderPageCacheManager' not in main:
    main = require_replace(
        main,
        'import com.simplereader.app.parser.EpubParser\n',
        'import com.simplereader.app.parser.EpubParser\nimport com.simplereader.app.reader.cache.ReaderPageCacheManager\n',
        'MainActivity import marker',
    )
main = require_replace(
    main,
    '.setItems(arrayOf("批量管理分组", "同步书架")) { _, which ->',
    '.setItems(arrayOf("批量管理分组", "同步书架", "一键缓存")) { _, which ->',
    'bookshelf menu items',
)
main = require_replace(
    main,
    '                    1 -> confirmSyncBookshelf()\n                }',
    '                    1 -> confirmSyncBookshelf()\n                    2 -> confirmCacheBookshelf()\n                }',
    'bookshelf menu actions',
)
cache_function = '''    private fun confirmCacheBookshelf() {
        val cacheable = books
            .filter { it.format.uppercase() in setOf("TXT", "EPUB", "CHM") }
            .distinctBy { it.id }
        if (cacheable.isEmpty()) {
            Toast.makeText(this, "书架中没有可缓存的书籍", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("一键缓存")
            .setMessage(
                "将在后台依次缓存书架中的 ${cacheable.size} 本书。\\n\\n" +
                    "原文件和排版设置未变化的完整缓存会直接跳过；" +
                    "未完成书籍从章节检查点继续。任务不启动前台服务。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始缓存") { _, _ ->
                val signature = ReaderPageCacheManager.currentLayoutSignature(this)
                cacheable.forEach { book ->
                    ReaderPageCacheManager.enqueue(
                        context = applicationContext,
                        bookId = book.id,
                        signature = signature
                    )
                }
                Toast.makeText(this, "已加入后台缓存：${cacheable.size} 本", Toast.LENGTH_LONG).show()
            }
            .show()
    }

'''
main = require_replace(
    main,
    '    private fun showBookActionsV2(book: ShelfBookItem) {',
    cache_function + '    private fun showBookActionsV2(book: ShelfBookItem) {',
    'MainActivity cache insertion marker',
)
main_path.write_text(main)

gradle_path = Path('app/build.gradle.kts')
gradle = gradle_path.read_text()
if 'androidx.work:work-runtime-ktx' not in gradle:
    gradle = require_replace(
        gradle,
        '    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")\n',
        '    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")\n'
        '    implementation("androidx.work:work-runtime-ktx:2.9.1")\n',
        'Gradle WorkManager dependency marker',
    )
gradle_path.write_text(gradle)

vertical_path = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageFlowView.kt')
vertical = vertical_path.read_text()
if 'import androidx.recyclerview.widget.PagerSnapHelper' not in vertical:
    vertical = require_replace(
        vertical,
        'import androidx.recyclerview.widget.LinearLayoutManager\n',
        'import androidx.recyclerview.widget.LinearLayoutManager\nimport androidx.recyclerview.widget.PagerSnapHelper\n',
        'VerticalPageFlowView import marker',
    )
vertical = vertical.replace(' * Continuous vertical reader', ' * Whole-page vertical reader')
vertical = require_replace(
    vertical,
    '        isVerticalScrollBarEnabled = true\n        isScrollbarFadingEnabled = false\n',
    '        isVerticalScrollBarEnabled = false\n        isHorizontalScrollBarEnabled = false\n',
    'VerticalPageFlowView scrollbar block',
)
vertical = require_replace(
    vertical,
    '    private val topFade = View(context).apply { isClickable = false }\n',
    '    private val pageSnapHelper = PagerSnapHelper()\n'
    '    private val topFade = View(context).apply { isClickable = false }\n',
    'VerticalPageFlowView snap helper marker',
)
vertical = require_replace(
    vertical,
    '    init {\n        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))\n',
    '    init {\n        pageSnapHelper.attachToRecyclerView(recyclerView)\n'
    '        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))\n',
    'VerticalPageFlowView init marker',
)
vertical = require_replace(
    vertical,
    '''    fun scrollByPage(direction: Int) {
        if (isReaderChromeVisible() || direction == 0) return
        val distance = (height * 0.78f).toInt().coerceAtLeast(1)
        recyclerView.smoothScrollBy(0, direction * distance)
    }
''',
    '''    fun scrollByPage(direction: Int) {
        if (isReaderChromeVisible() || direction == 0 || pageAdapter.itemCount == 0) return
        val current = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val target = (current + if (direction > 0) 1 else -1)
            .coerceIn(0, pageAdapter.itemCount - 1)
        recyclerView.smoothScrollToPosition(target)
    }
''',
    'VerticalPageFlowView page turn block',
)
vertical_path.write_text(vertical)

reader_path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
reader = reader_path.read_text()
reader = require_replace(
    reader,
    '''    private fun configureVerticalScrollIfNeeded() {
        val continuous = pageTurnMode == TURN_MODE_VERTICAL
        contentView.setPadding(
            dp(28),
            stableTopInsetPx() + dp(26),
            dp(28),
            stableBottomInsetPx() + dp(118)
        )
        contentView.setTextIsSelectable(false)
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
''',
    '''    private fun configureVerticalScrollIfNeeded() {
        contentView.setPadding(
            dp(28),
            stableTopInsetPx() + dp(26),
            dp(28),
            stableBottomInsetPx() + dp(118)
        )
        contentView.setTextIsSelectable(false)
        contentView.isVerticalScrollBarEnabled = false
        readerScrollView.isVerticalScrollBarEnabled = false
        readerScrollView.isHorizontalScrollBarEnabled = false
        readerScrollView.isSmoothScrollingEnabled = false
        readerScrollView.overScrollMode = View.OVER_SCROLL_NEVER
    }
''',
    'ReaderActivity continuous scroll block',
)
reader_path.write_text(reader)

worker = Path('app/src/main/java/com/simplereader/app/reader/cache/ReaderPageCacheWorker.kt').read_text()
if 'setForeground(' in worker or 'ForegroundInfo' in worker:
    raise SystemExit('Selected cache worker still contains foreground-service code')
