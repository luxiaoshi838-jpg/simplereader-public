#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import subprocess

build = Path('app/build.gradle.kts').read_text(encoding='utf-8')
crash = Path('app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt').read_text(encoding='utf-8')
reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
search_sheet = Path('app/src/main/java/com/simplereader/app/ui/ReaderSearchSheet.kt').read_text(encoding='utf-8')

assert '2098000765' in build and '?: "765"' in build
for token in [
    'PREF_LAST_SEEN_VERSION_CODE',
    'HISTORY_FILE_NAME',
    'packageInfo?.lastUpdateTime',
    'archivePendingBeforeUpgrade',
    'previousVersionCode != currentVersionCode',
    'info.timestamp in 1 until packageLastUpdateTime',
    'description.contains("normal_mem_pressure")',
    'state?.active == false',
    'state.event.startsWith("reader_clean_finish")',
]:
    assert token in crash, token

# A clean background reclaim must be filtered contextually, while ANR/crash/native crash remain actionable.
for token in [
    'ApplicationExitInfo.REASON_ANR',
    'ApplicationExitInfo.REASON_CRASH',
    'ApplicationExitInfo.REASON_CRASH_NATIVE',
    'ApplicationExitInfo.REASON_LOW_MEMORY',
    'ApplicationExitInfo.REASON_SIGNALED',
]:
    assert token in crash, token

# Update must archive old pending popups rather than deleting diagnostic evidence.
assert 'pending_crash_log_history.txt' in crash
assert '升级前历史异常记录' in crash
assert 'pending.delete()' in crash

# V764 reading-page search is a compatibility lock: no changes allowed in this targeted fix.
base_sheet = subprocess.check_output([
    'git', 'show', 'origin/source-v764:app/src/main/java/com/simplereader/app/ui/ReaderSearchSheet.kt'
], text=True)
assert search_sheet == base_sheet, 'ReaderSearchSheet changed from V764'

def block(text: str, start: str, end: str) -> str:
    a = text.index(start); b = text.index(end, a); return text[a:b]
base_reader = subprocess.check_output([
    'git', 'show', 'origin/source-v764:app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
], text=True)
assert block(reader, '    private fun showContentSearch() {', '    private fun confirmDeleteBookmark(') == block(base_reader, '    private fun showContentSearch() {', '    private fun confirmDeleteBookmark('), 'ReaderActivity search block changed from V764'

print('v765 exit-popup filtering + search compatibility gates: PASS')
PY
