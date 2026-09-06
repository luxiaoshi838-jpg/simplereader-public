#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path

crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')
build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
assert '2098000761' in build and '?: "761"' in build

for token in [
    'ApplicationExitInfo',
    'getHistoricalProcessExitReasons',
    'MAX_EXIT_TRACE_CHARS = 160_000',
    'val systemTrace = readExitTrace(abnormal)',
    'Android 系统退出 trace（截取）：',
    'private fun readExitTrace(info: ApplicationExitInfo)',
    'info.traceInputStream',
    'bufferedReader(Charsets.UTF_8)',
    'while (out.length < MAX_EXIT_TRACE_CHARS)',
]:
    assert token in crash, token

# Existing Java/system exit and recovery diagnostics must remain.
for token in [
    'REASON_ANR',
    'REASON_LOW_MEMORY',
    'REASON_CRASH_NATIVE',
    'reader_recovery_state.json',
    'reader_diagnostic_journal.txt',
    'fun recoveryOffset(',
    'fun recordReaderPosition(',
    'Executors.newSingleThreadExecutor',
]:
    assert token in crash, token

print('v761 crash/ANR trace gates: PASS')
PY
