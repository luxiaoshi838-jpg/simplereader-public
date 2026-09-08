#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import subprocess

build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
app = Path('app/src/main/java/com/simplereader/app/App.kt').read_text(encoding='utf-8')
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
search_sheet = Path('app/src/main/java/com/simplereader/app/ui/ReaderSearchSheet.kt').read_text(encoding='utf-8')

assert '2098000766' in build and '?: "766"' in build

# Previous exit must be captured before current process rotates its journal.
assert 'CrashLogStore.capturePreviousProcessExit(this)\n        CrashLogStore.startProcessSession(this)\n        CrashLogStore.install(this)' in app

# Cached/background system memory reclamation is silent, not a user-visible crash popup.
for token in [
    'ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED',
    'info.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED',
    'description.orEmpty().lowercase().contains("mem_pressure")',
    'ApplicationExitInfo.REASON_LOW_MEMORY -> true',
    '后台系统回收（静默，不弹窗）',
    'archiveSystemExitSilently',
    'system_exit_history.txt',
]:
    assert token in crash, token

# Real failures remain actionable.
for token in [
    'ApplicationExitInfo.REASON_ANR',
    'ApplicationExitInfo.REASON_CRASH',
    'ApplicationExitInfo.REASON_CRASH_NATIVE',
    'ApplicationExitInfo.REASON_SIGNALED',
    'ApplicationExitInfo.REASON_INITIALIZATION_FAILURE',
    'ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE',
]:
    assert token in crash, token

# Each process gets a fresh memory/event stream after previous-process capture.
for token in [
    'fun startProcessSession(context: Context)',
    'process_session_meta.json',
    'process_diagnostic_history.txt',
    'memoryJournalFile(appContext).delete()',
    'journalFile(appContext).delete()',
    'readProcessSessionMeta',
    'processSessionId',
    '上一进程会话：',
]:
    assert token in crash, token

# Upgrade suppression from V765 must remain.
for token in [
    'PREF_LAST_SEEN_VERSION_CODE',
    'pendingPredatesCurrentInstall',
    'archivePendingBeforeUpgrade',
    'info.timestamp in 1 until packageLastUpdateTime',
]:
    assert token in crash, token

# Narrow V766 change: reading UI/search, shelf virtualization and worker behavior must remain V765-identical.
changed = subprocess.check_output([
    'git', 'diff', '--name-only', 'origin/source-v765', '--', 'app/'
], text=True).splitlines()
allowed = {
    'app/build.gradle.kts',
    'app/src/main/java/com/simplereader/app/App.kt',
    'app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt',
}
assert set(changed) <= allowed, f'unexpected V766 app changes: {changed}'

base_sheet = subprocess.check_output([
    'git', 'show', 'origin/source-v765:app/src/main/java/com/simplereader/app/ui/ReaderSearchSheet.kt'
], text=True)
assert search_sheet == base_sheet, 'ReaderSearchSheet changed from V765'
base_reader = subprocess.check_output([
    'git', 'show', 'origin/source-v765:app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
], text=True)
assert reader == base_reader, 'ReaderActivity changed from V765; reading/search path must stay untouched'

print('v766 background-reclaim + process-session isolation + search lock gates: PASS')
PY
