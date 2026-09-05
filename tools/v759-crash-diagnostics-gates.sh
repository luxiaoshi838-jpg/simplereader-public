#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

app = Path('app/src/main/java/com/simplereader/app/App.kt').read_text(encoding='utf-8')
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
adapter = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
main = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text(encoding='utf-8')
build = Path('app/build.gradle.kts').read_text(encoding='utf-8')

assert '2098000759' in build and '?: "759"' in build

# Java/Kotlin uncaught exceptions + Android 11+ process-death reasons.
assert 'CrashLogStore.capturePreviousProcessExit(this)' in app
assert app.index('CrashLogStore.capturePreviousProcessExit(this)') < app.index('CrashLogStore.install(this)')
for token in [
    'ApplicationExitInfo',
    'getHistoricalProcessExitReasons',
    'REASON_ANR',
    'REASON_LOW_MEMORY',
    'REASON_CRASH_NATIVE',
    'REASON_SIGNALED',
    'REASON_EXCESSIVE_RESOURCE_USAGE',
    'pending_crash_log.txt',
    'reader_recovery_state.json',
    'reader_diagnostic_journal.txt',
    'Executors.newSingleThreadExecutor',
    'STATE_FLUSH_MIN_INTERVAL_MS',
    'fun recoveryOffset(',
    'fun recordReaderPosition(',
    'emergencyReserve = null',
]:
    assert token in crash, token

# Routine reader diagnostics must be queued off the UI thread; no synchronous commit in hot state path.
position = re.search(r'fun recordReaderPosition\(.*?\n    \}', crash, re.S)
assert position
assert 'enqueueStateWrite' in position.group(0)
assert '.commit()' not in crash

# Reader starts a recovery session, restores it before stale DB/page-0 fallbacks, and updates it.
for token in [
    'CrashLogStore.beginReaderSession(this, bookId, pageTurnMode)',
    'CrashLogStore.recoveryOffset(this@ReaderActivity, bookId)',
    'CrashLogStore.recordReaderPosition(',
    'verticalShouldSuppressReportedIndex',
    'vertical_suppressed_position_reset',
]:
    assert token in reader, token

# A transient RecyclerView reset may not be committed as a genuine position jump.
listener = re.search(r'class VerticalScrollListener\(.*?\n\}', adapter, re.S)
assert listener
body = listener.group(0)
assert 'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)' in body
assert 'return' in body

# The user must see the captured log after reopening the app.
assert 'showPendingCrashLogIfNeeded()' in main
assert '异常退出/闪退/崩溃日志' in main

# Recovery/session logging is not allowed to replace the validated virtualized reader architecture.
for token in ['RecyclerView(this)', 'LinearLayoutManager(this, RecyclerView.VERTICAL, false)', 'scrollToPositionWithOffset']:
    assert token in reader, token

print('v759 crash/system-exit/recovery gates: PASS')
PY

echo 'v759 diagnostic gates passed'
