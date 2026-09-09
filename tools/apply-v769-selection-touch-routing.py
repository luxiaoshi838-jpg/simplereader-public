from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str, marker: str | None = None) -> str:
    if marker and marker in text:
        return text
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# Version
build = Path('app/build.gradle.kts')
s = build.read_text(encoding='utf-8')
s = s.replace('2098000768', '2098000769').replace('?: "768"', '?: "769"')
build.write_text(s, encoding='utf-8')

# Horizontal reader: observe the event before selectable TextView consumes it, but still dispatch the
# same event to TextView so native long-press selection/handles continue to work.
p = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '    override fun onTouchEvent(event: MotionEvent): Boolean {\n',
    '''    override fun dispatchTouchEvent(event: MotionEvent): Boolean {\n        if (textSelectionEnabled) handleReaderTouchEvent(event)\n        return super.dispatchTouchEvent(event)\n    }\n\n    override fun onTouchEvent(event: MotionEvent): Boolean {\n        // With selectable text enabled the gesture was already observed in dispatchTouchEvent.\n        // Return handled here only as a fallback if the child unexpectedly declines the event.\n        if (textSelectionEnabled) return true\n        return handleReaderTouchEvent(event)\n    }\n\n    private fun handleReaderTouchEvent(event: MotionEvent): Boolean {\n''',
    'PagedReaderView dispatch observer',
    'private fun handleReaderTouchEvent(event: MotionEvent): Boolean'
)
s = replace_once(
    s,
    '''                if (longPressTriggered) {\n                    longPressTriggered = false\n                    return true\n                }''',
    '''                if (longPressTriggered) {\n                    // Do not clear this flag during the same long-press gesture. Native TextView\n                    // selection may now be dragging a word/handle; reader page navigation must stay\n                    // suppressed until ACTION_UP/CANCEL.\n                    return true\n                }''',
    'long press keeps navigation suppressed',
    'selection may now be dragging a word/handle'
)
p.write_text(s, encoding='utf-8')

# Continuous and RecyclerView vertical readers: observe at ViewGroup dispatch level rather than an
# OnTouchListener that only runs when the container itself wins the touch target.
r = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = r.read_text(encoding='utf-8')
s = replace_once(
    s,
    '        val recycler = RecyclerView(this).apply {\n',
    '        val recycler = ReaderTouchObservingRecyclerView(this).apply {\n',
    'vertical observing RecyclerView',
    'val recycler = ReaderTouchObservingRecyclerView(this).apply'
)
s = replace_once(
    s,
    '        recycler.setOnTouchListener(VerticalTouchListener(this))\n',
    '        recycler.touchObserver = { event -> verticalHandleTouch(event) }\n',
    'vertical observer hookup',
    'recycler.touchObserver = { event -> verticalHandleTouch(event) }'
)
s = replace_once(
    s,
    '''        verticalRecyclerView?.apply {\n            stopScroll()\n            clearOnScrollListeners()\n            setOnTouchListener(null)''',
    '''        verticalRecyclerView?.apply {\n            stopScroll()\n            clearOnScrollListeners()\n            (this as? ReaderTouchObservingRecyclerView)?.touchObserver = null\n            setOnTouchListener(null)''',
    'vertical observer release',
    '(this as? ReaderTouchObservingRecyclerView)?.touchObserver = null'
)
old_continuous = '''    private fun bindContinuousReader() {\n        readerScrollView.setOnTouchListener { _, event ->\n            when (event.actionMasked) {\n                MotionEvent.ACTION_DOWN -> continuousTouchActive = true\n                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {\n                    continuousTouchActive = false\n                    scheduleContinuousWindowShift(readerScrollView.scrollY)\n                }\n            }\n            continuousGesture.onTouchEvent(event)\n            false\n        }\n        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->\n'''
new_continuous = '''    private fun handleContinuousReaderTouch(event: MotionEvent) {\n        when (event.actionMasked) {\n            MotionEvent.ACTION_DOWN -> continuousTouchActive = true\n            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {\n                continuousTouchActive = false\n                scheduleContinuousWindowShift(readerScrollView.scrollY)\n            }\n        }\n        continuousGesture.onTouchEvent(event)\n    }\n\n    private fun bindContinuousReader() {\n        val observingScroll = readerScrollView as? ReaderTouchObservingNestedScrollView\n        if (observingScroll != null) {\n            observingScroll.touchObserver = { event -> handleContinuousReaderTouch(event) }\n            readerScrollView.setOnTouchListener(null)\n        } else {\n            readerScrollView.setOnTouchListener { _, event ->\n                handleContinuousReaderTouch(event)\n                false\n            }\n        }\n        readerScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->\n'''
s = replace_once(
    s,
    old_continuous,
    new_continuous,
    'continuous dispatch observer',
    'private fun handleContinuousReaderTouch(event: MotionEvent)'
)
s = replace_once(
    s,
    '''        if (::readerScrollView.isInitialized) readerScrollView.background = null\n''',
    '''        if (::readerScrollView.isInitialized) {\n            (readerScrollView as? ReaderTouchObservingNestedScrollView)?.touchObserver = null\n            readerScrollView.setOnTouchListener(null)\n            readerScrollView.background = null\n        }\n''',
    'continuous observer release',
    '(readerScrollView as? ReaderTouchObservingNestedScrollView)?.touchObserver = null'
)
r.write_text(s, encoding='utf-8')

# XML continuous fallback uses the dispatch-observing subclass. It remains assignable to the
# existing NestedScrollView field.
layout = Path('app/src/main/res/layout/activity_reader.xml')
s = layout.read_text(encoding='utf-8')
s = s.replace('<androidx.core.widget.NestedScrollView\n            android:id="@+id/readerScrollView"',
              '<com.simplereader.app.ui.ReaderTouchObservingNestedScrollView\n            android:id="@+id/readerScrollView"')
s = s.replace('</androidx.core.widget.NestedScrollView>', '</com.simplereader.app.ui.ReaderTouchObservingNestedScrollView>')
layout.write_text(s, encoding='utf-8')

print('v769 selection/touch coexistence patch applied')
