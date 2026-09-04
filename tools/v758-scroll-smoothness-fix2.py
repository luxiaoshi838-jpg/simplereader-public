from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
reader_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
adapter_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt'
build_path = ROOT / 'app/build.gradle.kts'
workflow_path = ROOT / '.github/workflows/android-release-v2.yml'
log_path = ROOT / 'TXT_READER_RENDERING_MAINTENANCE_LOG.md'

r = reader_path.read_text(encoding='utf-8')
a = adapter_path.read_text(encoding='utf-8')
b = build_path.read_text(encoding='utf-8')
w = workflow_path.read_text(encoding='utf-8')
log = log_path.read_text(encoding='utf-8')

# 1. Same-page RecyclerView callbacks must be no-ops for reader UI/state.
visible_pattern = re.compile(
    r'    internal fun verticalOnPageVisible\(index: Int\) \{.*?\n    \}\n(?=    internal fun verticalOnUserDrag)',
    re.S,
)
visible_match = visible_pattern.search(r)
if not visible_match:
    raise SystemExit('v758: verticalOnPageVisible not found')
new_visible = r'''    internal fun verticalOnPageVisible(index: Int) {
        val pages = readerBook?.pages.orEmpty()
        // RecyclerView emits pixel-level onScrolled callbacks. Reader state changes only when a
        // different ReaderPage becomes the first visible page, so do no UI work inside one page.
        if (index !in pages.indices || currentPageIndex == index) return
        currentPageIndex = index
        lastStableSourceOffset = pages[index].startOffset
        continuousWindowStartOffset = pages[index].startOffset
        continuousWindowEndOffset = pages[index].endOffset
        updateProgressUi()
        scheduleProgressCheckpoint(pages[index].startOffset)
    }
'''
r = visible_pattern.sub(new_visible, r, count=1)

# 2. Do not allocate Regex or rewrite title/actionbar when chapter title is unchanged.
state_anchor = '    private var progressCheckpointRunnable: Runnable? = null\n'
if 'private var lastDisplayedChapterTitle: String? = null' not in r:
    if state_anchor not in r:
        raise SystemExit('v758: progress checkpoint state anchor missing')
    r = r.replace(state_anchor, state_anchor + '    private var lastDisplayedChapterTitle: String? = null\n', 1)

title_pattern = re.compile(
    r'    private fun updateCurrentChapterTitle\(\) \{.*?\n    \}\n\n(?=    private fun setReaderChromeVisible)',
    re.S,
)
if not title_pattern.search(r):
    raise SystemExit('v758: updateCurrentChapterTitle not found')
new_title = r'''    private fun updateCurrentChapterTitle() {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        val rawTitle = paged.chapters.getOrNull(page.chapterIndex)?.title.orEmpty().trim()
        val explicitChapter = EXPLICIT_CHAPTER_REGEX.find(rawTitle)
        val chapterTitle = (explicitChapter?.let { rawTitle.substring(it.range.first) }
            ?: LEADING_NUMERIC_TITLE_REGEX.replace(rawTitle, ""))
            .trim()
            .ifBlank { book?.title.orEmpty() }
        if (chapterTitle == lastDisplayedChapterTitle) return
        lastDisplayedChapterTitle = chapterTitle
        title = chapterTitle
        supportActionBar?.title = chapterTitle
    }

'''
r = title_pattern.sub(lambda _: new_title, r, count=1)

if 'private val EXPLICIT_CHAPTER_REGEX' not in r:
    anchor = '        private const val PROGRESS_CHECKPOINT_DELAY_MS = 600L\n'
    if anchor not in r:
        raise SystemExit('v758: companion anchor missing')
    regexes = r'''        private val EXPLICIT_CHAPTER_REGEX = Regex(
            "第\\s*[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*(?:单元|章|节|篇|部|卷|回|集)"
        )
        private val LEADING_NUMERIC_TITLE_REGEX = Regex("^\\s*\\d+[.、．]\\s*")
'''
    r = r.replace(anchor, anchor + regexes, 1)

# 3. RecyclerView rows: cheaper TextView line breaking and a larger bounded render cache.
if 'import android.text.Layout\n' not in a:
    if 'import android.text.Spannable\n' not in a:
        raise SystemExit('v758: adapter Spannable import missing')
    a = a.replace('import android.text.Spannable\n', 'import android.text.Layout\nimport android.text.Spannable\n', 1)
a = a.replace('private val rendered = LruCache<Int, CharSequence>(32)',
              'private val rendered = LruCache<Int, CharSequence>(64)', 1)
if 'breakStrategy = Layout.BREAK_STRATEGY_SIMPLE' not in a:
    a = a.replace(
        '            includeFontPadding = false\n',
        '            includeFontPadding = false\n'
        '            // Chinese novel rows do not need balanced breaking or hyphenation. SIMPLE keeps\n'
        '            // TextView measurement cheap when RecyclerView prepares the next page.\n'
        '            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE\n'
        '            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE\n',
        1,
    )

# 4. Deduplicate scroll callbacks by first-visible page. Boundary haze becomes a page-boundary cue,
# not a per-pixel animation restart.
listener_pattern = re.compile(
    r'class VerticalScrollListener\(.*?\n\}\n\n(?=class VerticalTouchListener)',
    re.S,
)
if not listener_pattern.search(a):
    raise SystemExit('v758: VerticalScrollListener not found')
new_listener = r'''class VerticalScrollListener(
    private val activity: ReaderActivity,
    private val layoutManager: LinearLayoutManager
) : RecyclerView.OnScrollListener() {
    private var lastReportedIndex = RecyclerView.NO_POSITION

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (activity.verticalShouldIgnoreScroll()) return
        val index = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (index >= 0 && index != lastReportedIndex) {
            lastReportedIndex = index
            activity.verticalShowBoundaryHaze()
            activity.verticalOnPageVisible(index)
        }
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            val hitPage = activity.verticalOnUserDrag()
            if (hitPage != null) {
                (recyclerView.adapter as? VerticalPageAdapter)
                    ?.clearTransientSearchHighlight(recyclerView, hitPage)
            }
        }
    }
}

'''
a = listener_pattern.sub(lambda _: new_listener, a, count=1)

# 5. Version bump only; package and signing policy stay unchanged so v758 overlays v757.
b = b.replace('"2098000757"', '"2098000758"')
b = b.replace('?: 2098000757', '?: 2098000758')
b = b.replace('?: "757"', '?: "758"')
if '2098000758' not in b or '?: "758"' not in b:
    raise SystemExit('v758: build version bump failed')

# 6. Release workflow moves from source-v757 to source-v758 and runs both stability + smoothness gates.
w = w.replace('name: Build 简阅 v757', 'name: Build 简阅 v758')
w = w.replace('      - source-v757', '      - source-v758')
w = w.replace('SIMPLE_READER_VERSION_CODE: "2098000757"', 'SIMPLE_READER_VERSION_CODE: "2098000758"')
w = w.replace('SIMPLE_READER_VERSION_NAME: "757"', 'SIMPLE_READER_VERSION_NAME: "758"')
w = w.replace('SimpleReader_v757_github_unsigned.apk', 'SimpleReader_v758_github_unsigned.apk')
w = w.replace('chmod +x ./gradlew tools/v757-52-gates.sh',
              'chmod +x ./gradlew tools/v757-52-gates.sh tools/v758-scroll-smoothness-gates.sh')
w = w.replace('      - name: Run v757 52 gates\n        run: bash tools/v757-52-gates.sh',
              '      - name: Run v757 stability gates\n        run: bash tools/v757-52-gates.sh\n      - name: Run v758 scroll smoothness gates\n        run: bash tools/v758-scroll-smoothness-gates.sh')
w = w.replace('Test changed behavior and build v757', 'Test changed behavior and build v758')
w = w.replace('Verify v757 APK and export official signer', 'Verify v758 APK and export official signer')
w = w.replace("versionCode='2098000757'", "versionCode='2098000758'")
w = w.replace("versionName='757'", "versionName='758'")
w = w.replace('name: SimpleReader-v757-${{ github.run_number }}',
              'name: SimpleReader-v758-${{ github.run_number }}')
for required in ('source-v758', '2098000758', "versionName='758'", 'v758-scroll-smoothness-gates.sh'):
    if required not in w:
        raise SystemExit(f'v758: release workflow missing {required}')

# 7. Maintenance log.
if '## 2026-09-04 — V758' not in log:
    log += r'''

## 2026-09-04 — V758

### 问题
V757 已解决“滑动后偶发卡死/异常退出进度回退”，但用户真机继续反馈：竖向正常滑动时卡顿感很强，连续滑动和快速甩动不够跟手。

### 审计定位
1. `VerticalScrollListener.onScrolled()` 在像素级滚动回调里反复调用 `verticalShowBoundaryHaze()`，持续 cancel/start 两个 View 动画并 remove/post Handler 回调。
2. 同一回调持续调用 `verticalOnPageVisible()`；即使可见页未变化，V757 仍执行 `updateProgressUi()`，重复刷新进度 TextView、SeekBar、章节标题和 action bar。
3. `updateCurrentChapterTitle()` 每次重新构造两个 Regex，并重复写入相同标题。
4. RecyclerView 页 TextView 使用默认段落断行/连字符策略，中文小说滚动无需这部分布局成本。
5. 垂直页渲染 LRU 仅 32 页，快速来回滚动更容易重新绑定文本。

### V758 修法
- `VerticalScrollListener` 增加 `lastReportedIndex`，只有第一可见 `ReaderPage` 真正改变时才提交页位置与边界雾效果；同一页内像素滚动不做这些工作。
- `verticalOnPageVisible()` 对相同 `currentPageIndex` 立即返回，只在跨页时更新稳定 source offset、进度 UI 与 600 ms checkpoint；V757 进度稳定规则不变。
- 章节正则提升为 companion 单例并缓存 `lastDisplayedChapterTitle`；章节不变时不再重复写 Activity/action bar。
- 垂直页 TextView 使用 `BREAK_STRATEGY_SIMPLE` 与 `HYPHENATION_FREQUENCY_NONE`，降低 RecyclerView 预取/测量下一页时的文字布局开销，不改变字体、行距、分页 offset 或章节样式。
- 页渲染 LRU 从 32 扩至 64，仍为有界缓存。

### 禁止回归
- 禁止同一可见页内每个 `onScrolled` 回调刷新进度、章节标题或重启边界雾动画。
- 禁止恢复每像素 `verticalShowBoundaryHaze()`。
- 禁止退回 `NestedScrollView + 整本 TextView` 主路径。
- 必须继续通过 V757 稳定性 gates，搜索高亮清除、稳定进度锚点与 checkpoint 不得退化。
'''

reader_path.write_text(r, encoding='utf-8')
adapter_path.write_text(a, encoding='utf-8')
build_path.write_text(b, encoding='utf-8')
workflow_path.write_text(w, encoding='utf-8')
log_path.write_text(log, encoding='utf-8')
print('v758 vertical scroll smoothness patch v2 applied')
