from pathlib import Path

reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = reader.read_text(encoding='utf-8')

def replace_once(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing ReaderActivity block: {label}')
    s = s.replace(old, new, 1)

replace_once(
'''    private var continuousWindowEndOffset = 0
    private var continuousWindowShiftPosted = false
    private var statusBarInsetPx = 0
''',
'''    private var continuousWindowEndOffset = 0
    private var continuousWindowShiftPosted = false
    private var continuousScrollGeneration = 0L
    private var statusBarInsetPx = 0
''', 'scroll generation field')

replace_once(
'''        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (suppressContinuousScroll || pageTurnMode != TURN_MODE_VERTICAL) return@setOnScrollChangeListener
            clearSearchHighlight()
            updateContinuousPosition(scrollY)
            scheduleContinuousWindowShift(scrollY)
        }
''',
'''        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (suppressContinuousScroll || pageTurnMode != TURN_MODE_VERTICAL) return@setOnScrollChangeListener
            continuousScrollGeneration += 1L
            clearSearchHighlight()
            updateContinuousPosition(scrollY)
            scheduleContinuousWindowShift(scrollY)
        }
''', 'scroll listener')

replace_once(
'    private fun renderContinuousWindow(targetOffset: Int) {',
'    private fun renderContinuousWindow(targetOffset: Int, anchorViewportOffsetPx: Int? = null) {',
'render signature')

replace_once(
'''            continuousWindowStartOffset = startOffset
            continuousWindowEndOffset = endOffset
            continuousTextView.setText(styled, TextView.BufferType.SPANNABLE)
            applyContinuousHighlight()
            scrollContinuousToOffset(targetOffset)
''',
'''            continuousWindowStartOffset = startOffset
            continuousWindowEndOffset = endOffset
            suppressContinuousScroll = true
            continuousTextView.setText(styled, TextView.BufferType.SPANNABLE)
            applyContinuousHighlight()
            scrollContinuousToOffset(targetOffset, anchorViewportOffsetPx)
''', 'render anchor restore')

replace_once(
'''    private fun scheduleContinuousWindowShift(scrollY: Int) {
        val paged = readerBook ?: return
        if (continuousWindowShiftPosted || continuousTextView.height <= 0 || readerScrollView.height <= 0) return
        val threshold = readerScrollView.height
        val nearTop = scrollY < threshold && continuousWindowStartOffset > 0
        val nearBottom = scrollY + readerScrollView.height > continuousTextView.height - threshold &&
            continuousWindowEndOffset < paged.text.length
        if (!nearTop && !nearBottom) return
        val anchorOffset = sourceOffsetAtScroll(scrollY)
        continuousWindowShiftPosted = true
        readerScrollView.postDelayed({
            continuousWindowShiftPosted = false
            if (pageTurnMode == TURN_MODE_VERTICAL) renderContinuousWindow(anchorOffset)
        }, CONTINUOUS_SHIFT_DELAY_MS)
    }

    private fun scrollContinuousToOffset(offset: Int) {
        continuousTextView.post {
            val layout = continuousTextView.layout ?: return@post
            val localOffset = (offset - continuousWindowStartOffset).coerceIn(0, continuousTextView.text.length)
            val line = layout.getLineForOffset(localOffset)
            suppressContinuousScroll = true
            readerScrollView.scrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
            readerScrollView.post { suppressContinuousScroll = false }
        }
    }
''',
'''    private fun scheduleContinuousWindowShift(scrollY: Int) {
        val paged = readerBook ?: return
        if (continuousWindowShiftPosted || continuousTextView.height <= 0 || readerScrollView.height <= 0) return
        val threshold = readerScrollView.height
        val nearTop = scrollY < threshold && continuousWindowStartOffset > 0
        val nearBottom = scrollY + readerScrollView.height > continuousTextView.height - threshold &&
            continuousWindowEndOffset < paged.text.length
        if (!nearTop && !nearBottom) return

        continuousWindowShiftPosted = true
        val generationAtSchedule = continuousScrollGeneration
        readerScrollView.postDelayed({
            continuousWindowShiftPosted = false
            if (pageTurnMode != TURN_MODE_VERTICAL) return@postDelayed
            if (generationAtSchedule != continuousScrollGeneration) {
                scheduleContinuousWindowShift(readerScrollView.scrollY)
                return@postDelayed
            }

            val stableScrollY = readerScrollView.scrollY
            val anchorOffset = sourceOffsetAtScroll(stableScrollY)
            val anchorViewportOffsetPx = viewportOffsetForSourceOffset(anchorOffset, stableScrollY)
            renderContinuousWindow(anchorOffset, anchorViewportOffsetPx)
        }, CONTINUOUS_SHIFT_DELAY_MS)
    }

    private fun viewportOffsetForSourceOffset(offset: Int, scrollY: Int): Int {
        val layout = continuousTextView.layout ?: return 0
        if (layout.lineCount <= 0) return 0
        val localOffset = (offset - continuousWindowStartOffset).coerceIn(0, continuousTextView.text.length)
        val line = layout.getLineForOffset(localOffset)
        val absoluteLineTop = continuousTextView.top + layout.getLineTop(line)
        return absoluteLineTop - scrollY
    }

    private fun scrollContinuousToOffset(offset: Int, anchorViewportOffsetPx: Int? = null) {
        suppressContinuousScroll = true
        continuousTextView.post {
            val layout = continuousTextView.layout
            if (layout == null) {
                suppressContinuousScroll = false
                return@post
            }
            val localOffset = (offset - continuousWindowStartOffset).coerceIn(0, continuousTextView.text.length)
            val line = layout.getLineForOffset(localOffset)
            val absoluteLineTop = continuousTextView.top + layout.getLineTop(line)
            val targetScrollY = if (anchorViewportOffsetPx == null) {
                absoluteLineTop
            } else {
                absoluteLineTop - anchorViewportOffsetPx
            }
            readerScrollView.scrollTo(0, targetScrollY.coerceAtLeast(0))
            readerScrollView.post { suppressContinuousScroll = false }
        }
    }
''', 'window shift and pixel anchor')

replace_once('private const val CONTINUOUS_SHIFT_DELAY_MS = 120L',
             'private const val CONTINUOUS_SHIFT_DELAY_MS = 180L',
             'settle delay')
reader.write_text(s, encoding='utf-8')

guard = Path('tools/verify-rejected-reader-implementations.sh')
g = guard.read_text(encoding='utf-8')
marker = '# 2) No-cover / blank-cover implementation is forbidden.\n'
insert = '''# 1b) Bounded vertical-window shifts must not jump during drag/fling.
grep -Fq 'continuousScrollGeneration += 1L' "$reader" || fail 'vertical scrolling must track active scroll generations'
grep -Fq 'generationAtSchedule != continuousScrollGeneration' "$reader" || fail 'window shift must wait until scrolling settles'
grep -Fq 'viewportOffsetForSourceOffset' "$reader" || fail 'vertical window shift must preserve viewport pixel anchor'
grep -Fq 'renderContinuousWindow(anchorOffset, anchorViewportOffsetPx)' "$reader" || fail 'window shift must restore character and pixel anchor together'
grep -Fq 'absoluteLineTop - anchorViewportOffsetPx' "$reader" || fail 'window shift must restore exact on-screen line position'
if grep -Fq 'renderContinuousWindow(anchorOffset)' "$reader"; then fail 'character-only vertical window repositioning is forbidden'; fi

'''
if marker not in g:
    raise SystemExit('guard insertion marker missing')
guard.write_text(g.replace(marker, insert + marker, 1), encoding='utf-8')

doc = Path('READER_FORBIDDEN_REGRESSIONS.md')
d = doc.read_text(encoding='utf-8')
marker = '## 2. 禁止无封面实现\n'
insert = '''### 纵向连续阅读换窗不得弹跳

- 有限窗口换窗不得在手指拖动或惯性滚动仍进行时执行；必须等待滚动事件稳定后再换窗。
- 换窗前必须同时记录当前源码字符位置和该行相对屏幕顶部的像素偏移。
- 换窗后必须同时恢复字符位置与像素偏移；禁止只按字符/行首重新 `scrollTo`。
- `setText()` 和内部定位期间必须屏蔽内部 scroll 回调，禁止换窗自身再次触发换窗或进度跳动。

'''
if marker not in d:
    raise SystemExit('regression doc insertion marker missing')
doc.write_text(d.replace(marker, insert + marker, 1), encoding='utf-8')
