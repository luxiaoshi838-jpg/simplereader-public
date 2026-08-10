from pathlib import Path

reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = reader.read_text(encoding='utf-8')

s = s.replace(
    '    private var continuousScrollGeneration = 0L\n',
    '    private var continuousTouchActive = false\n    private var continuousLastScrollUptime = 0L\n',
    1,
)

old_bind = '''    private fun bindContinuousReader() {
        readerScrollView.setOnTouchListener { _, event ->
            continuousGesture.onTouchEvent(event)
            false
        }
        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (suppressContinuousScroll || pageTurnMode != TURN_MODE_VERTICAL) return@setOnScrollChangeListener
            continuousScrollGeneration += 1L
            clearSearchHighlight()
            updateContinuousPosition(scrollY)
            scheduleContinuousWindowShift(scrollY)
        }
    }
'''
new_bind = '''    private fun bindContinuousReader() {
        readerScrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> continuousTouchActive = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    continuousTouchActive = false
                    scheduleContinuousWindowShift(readerScrollView.scrollY)
                }
            }
            continuousGesture.onTouchEvent(event)
            false
        }
        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (suppressContinuousScroll || pageTurnMode != TURN_MODE_VERTICAL) return@setOnScrollChangeListener
            continuousLastScrollUptime = android.os.SystemClock.uptimeMillis()
            clearSearchHighlight()
            updateContinuousPosition(scrollY)
            scheduleContinuousWindowShift(scrollY)
        }
    }
'''
if old_bind not in s:
    raise SystemExit('bindContinuousReader block not found')
s = s.replace(old_bind, new_bind, 1)

start = s.index('    private fun scheduleContinuousWindowShift(scrollY: Int) {')
end = s.index('    private fun viewportOffsetForSourceOffset', start)
new_shift = '''    private fun scheduleContinuousWindowShift(scrollY: Int) {
        val paged = readerBook ?: return
        if (continuousWindowShiftPosted || continuousTextView.height <= 0 || readerScrollView.height <= 0) return
        val threshold = readerScrollView.height * 4
        val nearTop = scrollY < threshold && continuousWindowStartOffset > 0
        val nearBottom = scrollY + readerScrollView.height > continuousTextView.height - threshold &&
            continuousWindowEndOffset < paged.text.length
        if (!nearTop && !nearBottom) return

        continuousWindowShiftPosted = true
        readerScrollView.postDelayed({
            continuousWindowShiftPosted = false
            if (pageTurnMode != TURN_MODE_VERTICAL || continuousTouchActive) return@postDelayed
            val idleMs = android.os.SystemClock.uptimeMillis() - continuousLastScrollUptime
            if (idleMs < CONTINUOUS_SHIFT_IDLE_MS) {
                scheduleContinuousWindowShift(readerScrollView.scrollY)
                return@postDelayed
            }
            val stableScrollY = readerScrollView.scrollY
            val anchorOffset = sourceOffsetAtScroll(stableScrollY)
            val anchorViewportOffsetPx = viewportOffsetForSourceOffset(anchorOffset, stableScrollY)
            renderContinuousWindow(anchorOffset, anchorViewportOffsetPx)
        }, CONTINUOUS_SHIFT_DELAY_MS)
    }

'''
s = s[:start] + new_shift + s[end:]

if 'private const val CONTINUOUS_SHIFT_IDLE_MS' not in s:
    s = s.replace(
        'private const val CONTINUOUS_SHIFT_DELAY_MS = 180L',
        'private const val CONTINUOUS_SHIFT_DELAY_MS = 200L\n        private const val CONTINUOUS_SHIFT_IDLE_MS = 180L',
        1,
    )
reader.write_text(s, encoding='utf-8')

guard = Path('tools/verify-rejected-reader-implementations.sh')
g = guard.read_text(encoding='utf-8')
old_guard = '''grep -Fq 'continuousScrollGeneration += 1L' "$reader" || fail 'vertical scrolling must track active scroll generations'
grep -Fq 'generationAtSchedule != continuousScrollGeneration' "$reader" || fail 'window shift must wait until scrolling settles'
'''
new_guard = '''grep -Fq 'continuousTouchActive = true' "$reader" || fail 'vertical shift must know when a finger is touching the reader'
grep -Fq 'continuousLastScrollUptime = android.os.SystemClock.uptimeMillis()' "$reader" || fail 'vertical shift must track the last real scroll time'
grep -Fq 'val threshold = readerScrollView.height * 4' "$reader" || fail 'vertical shift must start four viewports before the bounded-window edge'
grep -Fq 'pageTurnMode != TURN_MODE_VERTICAL || continuousTouchActive' "$reader" || fail 'vertical shift must never swap the window under an active finger'
grep -Fq 'idleMs < CONTINUOUS_SHIFT_IDLE_MS' "$reader" || fail 'vertical shift must wait for fling settling without starvation'
'''
if old_guard not in g:
    raise SystemExit('old vertical-shift guard block not found')
guard.write_text(g.replace(old_guard, new_guard, 1), encoding='utf-8')
