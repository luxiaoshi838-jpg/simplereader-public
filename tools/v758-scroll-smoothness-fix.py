from pathlib import Path

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

# ---------------------------------------------------------------------------
# V758 goal: make the normal vertical RecyclerView scroll path frame-light.
# Keep the V757 stability/progress rules intact; do not change paging semantics.
# ---------------------------------------------------------------------------

# 1) ReaderActivity: only commit UI/progress work when the visible ReaderPage actually changes.
old_visible = '''    internal fun verticalOnPageVisible(index: Int) {
        val pages = readerBook?.pages.orEmpty()
        if (index !in pages.indices) return
        val changed = currentPageIndex != index
        currentPageIndex = index
        lastStableSourceOffset = pages[index].startOffset
        continuousWindowStartOffset = pages[index].startOffset
        continuousWindowEndOffset = pages[index].endOffset
        updateProgressUi()
        if (changed) scheduleProgressCheckpoint(pages[index].startOffset)
    }
'''
new_visible = '''    internal fun verticalOnPageVisible(index: Int) {
        val pages = readerBook?.pages.orEmpty()
        // RecyclerView reports onScrolled for pixel-level motion. Nothing in the reader state
        // changes until a different ReaderPage becomes the first visible page, so avoid doing
        // TextView/SeekBar/action-bar work on every frame.
        if (index !in pages.indices || currentPageIndex == index) return
        currentPageIndex = index
        lastStableSourceOffset = pages[index].startOffset
        continuousWindowStartOffset = pages[index].startOffset
        continuousWindowEndOffset = pages[index].endOffset
        updateProgressUi()
        scheduleProgressCheckpoint(pages[index].startOffset)
    }
'''
if old_visible not in r:
    raise SystemExit('v758: verticalOnPageVisible block does not match v757 baseline')
r = r.replace(old_visible, new_visible, 1)

# 2) Cache the chapter title and compile the chapter regex once. The v757 implementation rebuilt
# a Regex and reassigned Activity/action-bar titles for each onScrolled callback.
state_anchor = '    private var progressCheckpointRunnable: Runnable? = null\n'
if 'private var lastDisplayedChapterTitle: String? = null' not in r:
    if state_anchor not in r:
        raise SystemExit('v758: reader state anchor missing')
    r = r.replace(
        state_anchor,
        state_anchor + '    private var lastDisplayedChapterTitle: String? = null\n',
        1,
    )

old_title = '''    private fun updateCurrentChapterTitle() {
        val paged = readerBook ?: return
        val page = paged.pages.getOrNull(currentPageIndex) ?: return
        val rawTitle = paged.chapters.getOrNull(page.chapterIndex)?.title.orEmpty().trim()
        val explicitChapter = Regex(
            "第\\s*[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*(?:单元|章|节|篇|部|卷|回|集)"
        ).find(rawTitle)
        val chapterTitle = (explicitChapter?.let { rawTitle.substring(it.range.first) }
            ?: rawTitle.replace(Regex("^\\s*\\d+[.、．]\\s*"), ""))
            .trim()
            .ifBlank { book?.title.orEmpty() }
        title = chapterTitle
        supportActionBar?.title = chapterTitle
    }
'''
new_title = '''    private fun updateCurrentChapterTitle() {
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
if old_title not in r:
    raise SystemExit('v758: updateCurrentChapterTitle block does not match v757 baseline')
r = r.replace(old_title, new_title, 1)

companion_anchor = '        private const val PROGRESS_CHECKPOINT_DELAY_MS = 600L\n'
regex_block = '''        private val EXPLICIT_CHAPTER_REGEX = Regex(
            "第\\s*[0-9０-９零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*(?:单元|章|节|篇|部|卷|回|集)"
        )
        private val LEADING_NUMERIC_TITLE_REGEX = Regex("^\\s*\\d+[.、．]\\s*")
'''
if 'private val EXPLICIT_CHAPTER_REGEX' not in r:
    if companion_anchor not in r:
        raise SystemExit('v758: companion insertion anchor missing')
    r = r.replace(companion_anchor, companion_anchor + regex_block, 1)

# 3) VerticalPageAdapter: use Android's inexpensive line-breaking mode for RecyclerView text,
# keep a larger bounded render cache, and de-duplicate page-visible callbacks.
if 'import android.text.Layout\n' not in a:
    a = a.replace('import android.text.Spannable\n', 'import android.text.Layout\nimport android.text.Spannable\n', 1)

a = a.replace('private val rendered = LruCache<Int, CharSequence>(32)',
              'private val rendered = LruCache<Int, CharSequence>(64)', 1)

old_holder = '''            includeFontPadding = false
        }
'''
new_holder = '''            includeFontPadding = false
            // Chinese novel text does not benefit from balanced paragraph breaking/hyphenation.
            // SIMPLE avoids expensive reflow work when recycled rows are measured during a fling.
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
'''
if old_holder not in a:
    raise SystemExit('v758: TextView holder anchor missing')
a = a.replace(old_holder, new_holder, 1)

old_listener = '''class VerticalScrollListener(
    private val activity: ReaderActivity,
    private val layoutManager: LinearLayoutManager
) : RecyclerView.OnScrollListener() {
    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (activity.verticalShouldIgnoreScroll()) return
        val index = layoutManager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (index >= 0) {
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
new_listener = '''class VerticalScrollListener(
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
            // Boundary haze is a page-boundary cue, not a per-pixel animation. Running it once per
            // page removes repeated animator cancel/start and Handler callback churn during flings.
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
if old_listener not in a:
    raise SystemExit('v758: VerticalScrollListener block does not match v757 baseline')
a = a.replace(old_listener, new_listener, 1)

# 4) Version bump. Keep application id/signing policy unchanged for an in-place upgrade.
b = b.replace('"2098000757"', '"2098000758"')
b = b.replace('?: 2098000757', '?: 2098000758')
b = b.replace('?: "757"', '?: "758"')
if '2098000758' not in b or '?: "758"' not in b:
    raise SystemExit('v758: version patch failed')

# 5) Release workflow follows the source-vNNN branch convention and preserves v757 stability gates.
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
for must in ['source-v758', '2098000758', 'versionName=\'758\'', 'v758-scroll-smoothness-gates.sh']:
    if must not in w:
        raise SystemExit(f'v758: release workflow patch missing {must}')

# 6) Maintenance record: log the root causes and lock the no-regression rules.
section = '''

## 2026-09-04 — V758

### 问题
V757 已解决“滑动后偶发卡死/异常退出进度回退”的稳定性问题，但用户真机继续反馈：正常竖向滑动时卡顿感很强，尤其连续滑动和快速甩动时不够跟手。

### 审计定位
1. `VerticalScrollListener.onScrolled()` 在每一个像素级滚动回调里都会调用 `verticalShowBoundaryHaze()`，从而反复 cancel/start 两个 View 动画并 remove/post Handler 回调。
2. 同一个像素级回调还会调用 `verticalOnPageVisible()`；V757 即使页号没有变化也继续执行 `updateProgressUi()`，造成进度 TextView、SeekBar 和章节标题在每帧重复赋值。
3. `updateCurrentChapterTitle()` 每次都会重新构造两个 Regex，并重复写 Activity/action-bar title。
4. RecyclerView 新页 TextView 默认段落断行策略会承担不必要的平衡断行/连字符布局成本；中文小说连续阅读无需该开销。
5. 垂直页渲染缓存只有 32 页，快速前后滚动更容易重新进入页面文本绑定路径。

### V758 修法
- `VerticalScrollListener` 记录 `lastReportedIndex`，只有“第一可见 ReaderPage 真正改变”时才提交页位置和边界雾效果；同一页内部的像素滚动不再触发这些 UI 工作。
- `verticalOnPageVisible()` 对相同 `currentPageIndex` 立即返回；只在跨页时更新稳定 source offset、进度 UI 和 600 ms checkpoint。V757 的进度稳定规则保持不变。
- 章节标题正则提升为 companion 单例，并缓存 `lastDisplayedChapterTitle`；标题不变时不再重复写 Activity/action bar。
- 垂直 RecyclerView 的 TextView 使用 `BREAK_STRATEGY_SIMPLE` + `HYPHENATION_FREQUENCY_NONE`，降低回收页重新测量时的文本布局成本；不改变字体、行距、分页源 offset 或章节样式。
- 页面渲染 LRU 从 32 扩到 64，仍保持有界缓存，不回退到整本单 TextView。

### 禁止回归
- 禁止在同一可见页内的每个 `onScrolled` 回调执行 `updateProgressUi()`、章节 Regex、action-bar title 更新或边界雾动画重启。
- 禁止恢复每像素 `verticalShowBoundaryHaze()`。
- 禁止把连续阅读恢复成 `NestedScrollView + 整本 TextView`。
- 必须继续通过 V757 稳定性 gates：搜索高亮清除不得全缓存 evict/rebind，稳定进度锚点与 checkpoint 规则不得退化。
'''
if '## 2026-09-04 — V758' not in log:
    log += section

reader_path.write_text(r, encoding='utf-8')
adapter_path.write_text(a, encoding='utf-8')
build_path.write_text(b, encoding='utf-8')
workflow_path.write_text(w, encoding='utf-8')
log_path.write_text(log, encoding='utf-8')
print('v758 vertical scroll smoothness patch applied')
