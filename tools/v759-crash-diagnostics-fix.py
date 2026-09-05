from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"v759 patch failed [{label}]: expected 1 match, got {count}")
    return text.replace(old, new, 1)


reader_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
reader = reader_path.read_text(encoding='utf-8')

reader = replace_once(
    reader,
    'import com.simplereader.app.App\nimport com.simplereader.app.R\n',
    'import com.simplereader.app.App\nimport com.simplereader.app.R\nimport com.simplereader.app.crash.CrashLogStore\n',
    'reader crash import',
)

reader = replace_once(
    reader,
    '        bookId = intent.getLongExtra("bookId", 0L)\n\n        loadPreferences()\n        bindReaderInsets()\n',
    '        bookId = intent.getLongExtra("bookId", 0L)\n\n        loadPreferences()\n        CrashLogStore.beginReaderSession(this, bookId, pageTurnMode)\n        CrashLogStore.recordEvent(this, "ReaderActivity.onCreate book=$bookId mode=$pageTurnMode")\n        bindReaderInsets()\n',
    'reader session begin',
)

reader = replace_once(
    reader,
    '    override fun onPause() {\n        stopAutoReading(false)\n        saveProgress()\n        super.onPause()\n    }\n',
    '    override fun onPause() {\n        stopAutoReading(false)\n        CrashLogStore.recordEvent(this, "ReaderActivity.onPause book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset")\n        saveProgress()\n        super.onPause()\n    }\n',
    'onPause diagnostic',
)

reader = replace_once(
    reader,
    '        cancelVerticalStateUnlockGuard()\n        stopAutoReading(false)\n        super.onDestroy()\n',
    '        cancelVerticalStateUnlockGuard()\n        stopAutoReading(false)\n        CrashLogStore.recordEvent(this, "ReaderActivity.onDestroy book=$bookId finishing=$isFinishing changingConfig=$isChangingConfigurations page=$currentPageIndex stable=$lastStableSourceOffset")\n        if (isFinishing && !isChangingConfigurations) {\n            CrashLogStore.finishReaderSession(this, bookId)\n        }\n        super.onDestroy()\n',
    'onDestroy diagnostic',
)

reader = replace_once(
    reader,
    '    private fun loadBook() {\n        if (bookId <= 0L) return showFatal("书籍记录不存在")\n        lifecycleScope.launch {\n',
    '    private fun loadBook() {\n        if (bookId <= 0L) return showFatal("书籍记录不存在")\n        CrashLogStore.recordEvent(this, "loadBook:start book=$bookId")\n        lifecycleScope.launch {\n',
    'loadBook start diagnostic',
)

reader = replace_once(
    reader,
    '            } catch (error: Throwable) {\n                showFatal(error.message ?: "打开书籍失败")\n            }\n        }\n    }\n\n    private fun paginateAndDisplay',
    '            } catch (error: Throwable) {\n                CrashLogStore.recordEvent(this@ReaderActivity, "loadBook:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()}")\n                showFatal(error.message ?: "打开书籍失败")\n            }\n        }\n    }\n\n    private fun paginateAndDisplay',
    'loadBook failure diagnostic',
)

reader = replace_once(
    reader,
    '        paginationJob?.cancel()\n        paginationInProgress = true\n        progressLabel.text = "分页中…"\n        paginationJob = lifecycleScope.launch {\n',
    '        paginationJob?.cancel()\n        paginationInProgress = true\n        progressLabel.text = "分页中…"\n        CrashLogStore.recordEvent(this, "paginate:start book=$bookId preserve=$preserveOffset stable=$lastStableSourceOffset")\n        paginationJob = lifecycleScope.launch {\n',
    'paginate start diagnostic',
)

reader = replace_once(
    reader,
    '                val stableOffset = preserveOffset\n                    ?: lastStableSourceOffset\n                    ?: progress?.startOffset\n',
    '                val stableOffset = preserveOffset\n                    ?: lastStableSourceOffset\n                    ?: CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)\n                    ?: progress?.startOffset\n',
    'recovery offset before DB fallback',
)

reader = replace_once(
    reader,
    '                currentPageIndex = target.coerceIn(0, paged.pages.lastIndex)\n                lastStableSourceOffset = paged.pages.getOrNull(currentPageIndex)?.startOffset\n                paginationInProgress = false\n',
    '                currentPageIndex = target.coerceIn(0, paged.pages.lastIndex)\n                lastStableSourceOffset = paged.pages.getOrNull(currentPageIndex)?.startOffset\n                paged.pages.getOrNull(currentPageIndex)?.let { restored ->\n                    CrashLogStore.recordReaderPosition(\n                        this@ReaderActivity, bookId, restored.globalPageIndex, paged.pages.size,\n                        restored.startOffset, pageTurnMode, "paginate_success", force = true\n                    )\n                }\n                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:success book=$bookId pages=${paged.pages.size} target=$currentPageIndex stable=$lastStableSourceOffset cached=${cached != null}")\n                paginationInProgress = false\n',
    'paginate success recovery snapshot',
)

reader = replace_once(
    reader,
    '            } catch (error: Throwable) {\n                val rollback = pendingFontRollback\n',
    '            } catch (error: Throwable) {\n                CrashLogStore.recordEvent(this@ReaderActivity, "paginate:failure book=$bookId type=${error.javaClass.name} message=${error.message.orEmpty()} page=$currentPageIndex stable=$lastStableSourceOffset")\n                val rollback = pendingFontRollback\n',
    'paginate failure diagnostic',
)

reader = replace_once(
    reader,
    '        val offset = targetOffset ?: paged.pages.getOrNull(currentPageIndex)?.startOffset ?: 0\n',
    '        val offset = targetOffset\n            ?: lastStableSourceOffset?.takeIf { it in 0..paged.text.length }\n            ?: CrashLogStore.recoveryOffset(this, bookId)\n            ?: paged.pages.getOrNull(currentPageIndex)?.startOffset\n            ?: 0\n',
    'vertical show recovery fallback',
)

reader = replace_once(
    reader,
    '        continuousWindowStartOffset = page.startOffset\n        continuousWindowEndOffset = page.endOffset\n        verticalRecyclerView?.visibility = View.VISIBLE\n',
    '        continuousWindowStartOffset = page.startOffset\n        continuousWindowEndOffset = page.endOffset\n        CrashLogStore.recordReaderPosition(\n            this, bookId, page.globalPageIndex, paged.pages.size, page.startOffset,\n            pageTurnMode, "vertical_show", force = true\n        )\n        verticalRecyclerView?.visibility = View.VISIBLE\n',
    'vertical show snapshot',
)

reader = replace_once(
    reader,
    '        continuousWindowStartOffset = pages[index].startOffset\n        continuousWindowEndOffset = pages[index].endOffset\n        updateProgressUi()\n        scheduleProgressCheckpoint(pages[index].startOffset)\n    }\n    internal fun verticalOnUserDrag(): Int? {\n',
    '        continuousWindowStartOffset = pages[index].startOffset\n        continuousWindowEndOffset = pages[index].endOffset\n        CrashLogStore.recordReaderPosition(\n            this, bookId, index, pages.size, pages[index].startOffset,\n            pageTurnMode, "vertical_page", force = false\n        )\n        updateProgressUi()\n        scheduleProgressCheckpoint(pages[index].startOffset)\n    }\n\n    internal fun verticalShouldSuppressReportedIndex(previousIndex: Int, index: Int, dy: Int): Boolean {\n        if (previousIndex < 0 || index < 0 || previousIndex == index) return false\n        val wrongDirectionJump =\n            (dy > 0 && index + 2 < previousIndex) ||\n            (dy < 0 && index > previousIndex + 2)\n        val zeroTeleport = index == 0 && previousIndex >= 4 && dy >= 0\n        if (!wrongDirectionJump && !zeroTeleport) return false\n        CrashLogStore.recordEvent(\n            this,\n            "vertical_suppressed_position_reset book=$bookId from=$previousIndex to=$index dy=$dy current=$currentPageIndex stable=$lastStableSourceOffset"\n        )\n        return true\n    }\n\n    internal fun verticalOnUserDrag(): Int? {\n',
    'vertical transient reset guard',
)

reader = replace_once(
    reader,
    '        val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset()).coerceIn(0, loaded.text.length)\n',
    '        val anchor = (lastStableSourceOffset\n            ?: CrashLogStore.recoveryOffset(this, bookId)\n            ?: currentVisibleSourceOffset()).coerceIn(0, loaded.text.length)\n',
    'fallback recovery anchor',
)

reader = replace_once(
    reader,
    '        val snapshot = progressSnapshotForOffset(sourceOffset) ?: return\n        // Final write survives Activity destruction. The snapshot is immutable and does not rewrite\n',
    '        val snapshot = progressSnapshotForOffset(sourceOffset) ?: return\n        readerBook?.let { paged ->\n            val page = paged.pageForOffset(sourceOffset)\n            CrashLogStore.recordReaderPosition(\n                this, bookId, page.globalPageIndex, paged.pages.size, page.startOffset,\n                pageTurnMode, "save_progress", force = true\n            )\n        }\n        // Final write survives Activity destruction. The snapshot is immutable and does not rewrite\n',
    'save progress recovery snapshot',
)

reader = replace_once(
    reader,
    '        return lastStableSourceOffset?.coerceIn(0, paged.text.length)\n            ?: paged.pages.getOrNull(currentPageIndex)?.startOffset\n            ?: 0\n',
    '        return lastStableSourceOffset?.coerceIn(0, paged.text.length)\n            ?: CrashLogStore.recoveryOffset(this, bookId)?.coerceIn(0, paged.text.length)\n            ?: paged.pages.getOrNull(currentPageIndex)?.startOffset\n            ?: 0\n',
    'current visible recovery fallback',
)

reader = replace_once(
    reader,
    '    override fun onWindowFocusChanged(hasFocus: Boolean) {\n        super.onWindowFocusChanged(hasFocus)\n        val rv = verticalRecyclerView\n',
    '    override fun onWindowFocusChanged(hasFocus: Boolean) {\n        super.onWindowFocusChanged(hasFocus)\n        CrashLogStore.recordEvent(this, "reader_focus hasFocus=$hasFocus book=$bookId page=$currentPageIndex stable=$lastStableSourceOffset suspended=$verticalWindowSuspended programmatic=$verticalProgrammaticScroll")\n        val rv = verticalRecyclerView\n',
    'focus diagnostic',
)

reader = replace_once(
    reader,
    '    private fun showFatal(message: String) {\n        AlertDialog.Builder(this)\n',
    '    private fun showFatal(message: String) {\n        CrashLogStore.recordEvent(this, "showFatal book=$bookId message=${message.replace("\\n", " ").take(500)}")\n        AlertDialog.Builder(this)\n',
    'fatal diagnostic',
)

reader_path.write_text(reader, encoding='utf-8')

adapter_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt'
adapter = adapter_path.read_text(encoding='utf-8')
adapter = replace_once(
    adapter,
    '        if (index >= 0 && index != lastReportedIndex) {\n            lastReportedIndex = index\n            activity.verticalShowBoundaryHaze()\n            activity.verticalOnPageVisible(index)\n        }\n',
    '        if (index >= 0 && index != lastReportedIndex) {\n            if (activity.verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)) return\n            lastReportedIndex = index\n            activity.verticalShowBoundaryHaze()\n            activity.verticalOnPageVisible(index)\n        }\n',
    'RecyclerView transient row-zero guard',
)
adapter_path.write_text(adapter, encoding='utf-8')

main_path = ROOT / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
main = main_path.read_text(encoding='utf-8')
main = replace_once(main, '.setTitle("闪退/崩溃日志")', '.setTitle("异常退出/闪退日志")', 'crash dialog title')
main = replace_once(main, 'ClipData.newPlainText("简阅闪退日志", crashLog)', 'ClipData.newPlainText("简阅异常退出日志", crashLog)', 'clipboard crash label')
main_path.write_text(main, encoding='utf-8')

build_path = ROOT / 'app/build.gradle.kts'
build = build_path.read_text(encoding='utf-8')
build = build.replace('"2098000758"', '"2098000759"')
build = build.replace('?: 2098000758', '?: 2098000759')
build = build.replace('?: "758"', '?: "759"')
if '2098000759' not in build or '?: "759"' not in build:
    raise SystemExit('v759 patch failed [version bump]')
build_path.write_text(build, encoding='utf-8')

log_path = ROOT / 'TXT_READER_RENDERING_MAINTENANCE_LOG.md'
log = log_path.read_text(encoding='utf-8')
marker = '## 2026-09-05 — V759'
if marker not in log:
    log += '''\n\n## 2026-09-05 — V759\n\n### 用户仍然观察到的问题\nV758 真机仍会偶发“先突然明显卡顿，然后阅读位置闪到第一页，随后应用直接退出”。V758 已有的 CrashLogStore 只覆盖 Java/Kotlin `uncaughtException`，因此 ANR、native/signal 退出、系统低内存终止等进程级异常可能没有可见日志。\n\n### V759 诊断与保护\n- Android 11+ 启动时读取 `ApplicationExitInfo` 历史退出原因，记录 ANR、Java/native crash、signal、low-memory、excessive-resource 等系统级退出信息。\n- Java/Kotlin 未捕获异常日志继续保留，并新增 Java heap、系统内存、设备、线程、最后阅读状态。\n- 新增 `reader_recovery_state.json`：最后书籍、ReaderPage、sourceOffset、翻页模式独立于 Room 保存；写入通过单线程后台 executor 且限频，禁止在滚动主线程直接做磁盘 IO。\n- 新增 `reader_diagnostic_journal.txt` 保存关键生命周期/分页事件；异常退出后与系统退出原因一起展示。\n- 恢复顺序调整为：显式 preserveOffset → 内存稳定 offset → 未正常结束会话的 recovery offset → Room 进度 → 旧页号；避免异常链丢失稳定 offset 后直接回页0。\n- `showContinuousBook()`、分页失败 fallback、`currentVisibleSourceOffset()` 同样优先使用稳定/recovery offset，不再轻易落到 0。\n- RecyclerView 增加“明显违背滚动方向的跨页跳变/从高页无向下滚动依据瞬间报 row 0”保护；这种瞬时布局报告不会被提交为真实阅读位置，并写一条 `vertical_suppressed_position_reset` 诊断事件。\n- 主界面下次启动自动显示“异常退出/闪退日志”，用户可复制；暂不复制不会清除。\n\n### 仍需真机日志才能最终定位\nV759 的位置保护可以阻止一类 RecyclerView 瞬时 row-0 污染，但当前没有 V758 真机异常时的系统 exit reason/stack，因此不能把根因武断认定为某一行代码。V759 的首要目标是让下一次复现必然留下尽可能多的可用证据，然后依据 `退出原因 + 最后 ReaderPage/sourceOffset + 诊断流水 + Java stack（若有）` 做下一轮定点修复。\n\n### 禁止回归\n- 禁止把磁盘日志写入放进像素级 `onScrolled` 主线程路径。\n- 禁止异常恢复优先级退回无条件 page 0。\n- 禁止删除 V757 的稳定锚点/600 ms checkpoint 和 V758 的同页去重/轻量文本布局。\n- 禁止用扩大无界缓存解决此问题。\n'''
    log_path.write_text(log, encoding='utf-8')

print('v759 crash diagnostics + recovery guard patch applied')
