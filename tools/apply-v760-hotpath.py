from pathlib import Path

build_path = Path('app/build.gradle.kts')
reader_path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
adapter_path = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt')

build = build_path.read_text(encoding='utf-8')
if '2098000760' not in build:
    if '2098000759' not in build or '?: "759"' not in build:
        raise SystemExit('unexpected v759 build version shape')
    build = build.replace('2098000759', '2098000760').replace('?: "759"', '?: "760"')
    build_path.write_text(build, encoding='utf-8')

reader = reader_path.read_text(encoding='utf-8')
marker = '    private var lastDisplayedChapterTitle: String? = null\n'
if 'pendingVerticalDiagnosticEvent' not in reader:
    if marker not in reader:
        raise SystemExit('ReaderActivity field marker missing')
    reader = reader.replace(marker, marker + '    private var pendingVerticalDiagnosticEvent: String? = null\n', 1)

hot_log = '''        CrashLogStore.recordReaderPosition(
            this, bookId, index, pages.size, pages[index].startOffset,
            pageTurnMode, "vertical_page", force = false
        )
'''
if hot_log in reader:
    reader = reader.replace(hot_log, '', 1)
elif '"vertical_page", force = false' in reader:
    raise SystemExit('unexpected vertical_page hot-path shape')

old_suppress = '''        CrashLogStore.recordEvent(
            this,
            "vertical_suppressed_position_reset book=$bookId from=$previousIndex to=$index dy=$dy current=$currentPageIndex stable=$lastStableSourceOffset"
        )
'''
new_suppress = '''        pendingVerticalDiagnosticEvent =
            "vertical_suppressed_position_reset book=$bookId from=$previousIndex to=$index dy=$dy current=$currentPageIndex stable=$lastStableSourceOffset"
'''
if old_suppress in reader:
    reader = reader.replace(old_suppress, new_suppress, 1)
elif 'vertical_suppressed_position_reset' in reader and 'pendingVerticalDiagnosticEvent' not in reader:
    raise SystemExit('unexpected suppression diagnostic shape')

insert_before = '    internal fun verticalOnUserDrag(): Int? {\n'
new_methods = '''    private fun persistVerticalDiagnosticState(event: String, force: Boolean = false) {
        val pages = readerBook?.pages.orEmpty()
        val page = pages.getOrNull(currentPageIndex) ?: return
        CrashLogStore.recordReaderPosition(
            this, bookId, page.globalPageIndex, pages.size, page.startOffset,
            pageTurnMode, event, force = force
        )
        pendingVerticalDiagnosticEvent?.let { diagnostic ->
            pendingVerticalDiagnosticEvent = null
            CrashLogStore.recordEvent(this, diagnostic)
        }
    }

    internal fun verticalOnScrollIdle() {
        if (pageTurnMode != TURN_MODE_VERTICAL || verticalShouldIgnoreScroll()) return
        persistVerticalDiagnosticState("vertical_idle", force = false)
    }

'''
if 'internal fun verticalOnScrollIdle()' not in reader:
    if insert_before not in reader:
        raise SystemExit('verticalOnUserDrag insertion marker missing')
    reader = reader.replace(insert_before, new_methods + insert_before, 1)

old_pause = '''    override fun onPause() {
        stopAutoReading(false)
        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset")
        saveProgress()
        super.onPause()
    }
'''
new_pause = '''    override fun onPause() {
        stopAutoReading(false)
        if (pageTurnMode == TURN_MODE_VERTICAL) {
            persistVerticalDiagnosticState("vertical_pause", force = true)
        }
        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset")
        saveProgress()
        super.onPause()
    }
'''
if old_pause in reader:
    reader = reader.replace(old_pause, new_pause, 1)
elif 'persistVerticalDiagnosticState("vertical_pause", force = true)' not in reader:
    raise SystemExit('unexpected onPause shape')
reader_path.write_text(reader, encoding='utf-8')

adapter = adapter_path.read_text(encoding='utf-8')
old_state = '''    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            val hitPage = activity.verticalOnUserDrag()
            if (hitPage != null) {
                (recyclerView.adapter as? VerticalPageAdapter)
                    ?.clearTransientSearchHighlight(recyclerView, hitPage)
            }
        }
    }
'''
new_state = '''    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            val hitPage = activity.verticalOnUserDrag()
            if (hitPage != null) {
                (recyclerView.adapter as? VerticalPageAdapter)
                    ?.clearTransientSearchHighlight(recyclerView, hitPage)
            }
        } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            activity.verticalOnScrollIdle()
        }
    }
'''
if old_state in adapter:
    adapter = adapter.replace(old_state, new_state, 1)
elif 'activity.verticalOnScrollIdle()' not in adapter:
    raise SystemExit('unexpected VerticalScrollListener state block')
adapter_path.write_text(adapter, encoding='utf-8')

print('v760 hot-path patch applied/idempotent')
