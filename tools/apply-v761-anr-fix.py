from pathlib import Path

build_path = Path('app/build.gradle.kts')
reader_path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
crash_path = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt')

# Version bump.
build = build_path.read_text(encoding='utf-8')
if '2098000761' not in build:
    if '2098000760' not in build or '?: "760"' not in build:
        raise SystemExit('unexpected v760 build version shape')
    build = build.replace('2098000760', '2098000761').replace('?: "760"', '?: "761"')
    build_path.write_text(build, encoding='utf-8')

reader = reader_path.read_text(encoding='utf-8')
old_suppress = '''    internal fun verticalShouldSuppressReportedIndex(previousIndex: Int, index: Int, dy: Int): Boolean {
        if (previousIndex < 0 || index < 0 || previousIndex == index) return false
        val wrongDirectionJump =
            (dy > 0 && index + 2 < previousIndex) ||
            (dy < 0 && index > previousIndex + 2)
        val zeroTeleport = index == 0 && previousIndex >= 4 && dy >= 0
        if (!wrongDirectionJump && !zeroTeleport) return false
        pendingVerticalDiagnosticEvent =
            "vertical_suppressed_position_reset book=$bookId from=$previousIndex to=$index dy=$dy current=$currentPageIndex stable=$lastStableSourceOffset"
        return true
    }
'''
new_suppress = '''    internal fun verticalShouldSuppressReportedIndex(previousIndex: Int, index: Int, dy: Int): Boolean {
        if (index < 0) return false
        val current = currentPageIndex
        val baseline = when {
            previousIndex >= 0 && kotlin.math.abs(previousIndex - current) <= 2 -> previousIndex
            current >= 0 -> current
            else -> previousIndex
        }
        if (baseline < 0 || baseline == index) return false
        val wrongDirectionJump =
            (dy > 0 && index + 2 < baseline) ||
            (dy < 0 && index > baseline + 2)
        val zeroTeleport = index == 0 && baseline >= 4 && dy >= 0
        if (!wrongDirectionJump && !zeroTeleport) return false
        pendingVerticalDiagnosticEvent =
            "vertical_suppressed_position_reset book=$bookId previous=$previousIndex baseline=$baseline to=$index dy=$dy current=$current stable=$lastStableSourceOffset"
        return true
    }
'''
if old_suppress in reader:
    reader = reader.replace(old_suppress, new_suppress, 1)
elif 'previous=$previousIndex baseline=$baseline' not in reader:
    raise SystemExit('unexpected verticalShouldSuppressReportedIndex shape')

old_idle = '''    internal fun verticalOnScrollIdle() {
        if (pageTurnMode != TURN_MODE_VERTICAL || verticalShouldIgnoreScroll()) return
        persistVerticalDiagnosticState("vertical_idle", force = false)
    }
'''
new_idle = '''    internal fun verticalOnScrollIdle() {
        if (pageTurnMode != TURN_MODE_VERTICAL || verticalShouldIgnoreScroll()) return
        val visibleIndex = verticalLayoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (visibleIndex == 0 && currentPageIndex >= 4) {
            val restoreIndex = currentPageIndex
            pendingVerticalDiagnosticEvent =
                "vertical_idle_recover_zero book=$bookId visible=$visibleIndex restore=$restoreIndex stable=$lastStableSourceOffset"
            verticalProgrammaticScroll = true
            verticalLayoutManager?.scrollToPositionWithOffset(restoreIndex, 0)
            scheduleVerticalStateUnlockGuard()
            verticalRecyclerView?.post {
                verticalProgrammaticScroll = false
                if (!verticalWindowSuspended) cancelVerticalStateUnlockGuard()
                persistVerticalDiagnosticState("vertical_idle_recovered_zero", force = false)
            }
            return
        }
        persistVerticalDiagnosticState("vertical_idle", force = false)
    }
'''
if old_idle in reader:
    reader = reader.replace(old_idle, new_idle, 1)
elif 'vertical_idle_recover_zero' not in reader:
    raise SystemExit('unexpected verticalOnScrollIdle shape')
reader_path.write_text(reader, encoding='utf-8')

crash = crash_path.read_text(encoding='utf-8')
if 'MAX_EXIT_TRACE_CHARS' not in crash:
    marker = '    private const val MAX_JOURNAL_CHARS = 96_000\n'
    if marker not in crash:
        raise SystemExit('CrashLogStore const marker missing')
    crash = crash.replace(marker, marker + '    private const val MAX_EXIT_TRACE_CHARS = 160_000\n', 1)

old_state_journal = '''        val state = readReaderState(appContext)
        val journal = readJournal(appContext)
        val section = buildString {
'''
new_state_journal = '''        val state = readReaderState(appContext)
        val journal = readJournal(appContext)
        val systemTrace = readExitTrace(abnormal)
        val section = buildString {
'''
if old_state_journal in crash:
    crash = crash.replace(old_state_journal, new_state_journal, 1)
elif 'val systemTrace = readExitTrace(abnormal)' not in crash:
    raise SystemExit('capturePreviousProcessExit state/journal shape missing')

old_append = '''            if (journal.isNotBlank()) {
                appendLine()
                appendLine("异常前诊断流水：")
                append(journal.takeLast(MAX_JOURNAL_CHARS))
            }
        }
        writePendingSection(appContext, section, preservePrevious = true)
    }
'''
new_append = '''            if (journal.isNotBlank()) {
                appendLine()
                appendLine("异常前诊断流水：")
                append(journal.takeLast(MAX_JOURNAL_CHARS))
            }
            if (systemTrace.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("Android 系统退出 trace（截取）：")
                append(systemTrace)
            }
        }
        writePendingSection(appContext, section, preservePrevious = true)
    }

    private fun readExitTrace(info: ApplicationExitInfo): String = runCatching {
        val stream = info.traceInputStream ?: return@runCatching ""
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(8192)
            while (out.length < MAX_EXIT_TRACE_CHARS) {
                val remaining = MAX_EXIT_TRACE_CHARS - out.length
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) break
                out.append(buffer, 0, count)
            }
            out.toString()
        }
    }.getOrDefault("")
'''
if old_append in crash:
    crash = crash.replace(old_append, new_append, 1)
elif 'private fun readExitTrace(info: ApplicationExitInfo)' not in crash:
    raise SystemExit('capturePreviousProcessExit append shape missing')
crash_path.write_text(crash, encoding='utf-8')

print('v761 ANR/zero-reset patch applied/idempotent')
