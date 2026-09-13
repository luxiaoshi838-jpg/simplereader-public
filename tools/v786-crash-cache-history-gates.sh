#!/usr/bin/env bash
set -euo pipefail

fail() { echo "V786_GATE_FAIL: $*" >&2; exit 1; }
pass() { echo "V786_GATE_PASS: $*"; }

CRASH='app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt'
MAIN='app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
WORKER='app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt'
BUILD='app/build.gradle.kts'

for f in "$CRASH" "$MAIN" "$WORKER" "$BUILD"; do [ -f "$f" ] || fail "missing $f"; done

grep -Fq 'CRASH_HISTORY_LIMIT = 20' "$CRASH" || fail 'crash history is not capped at 20'
grep -Fq 'consumePendingIntoHistory' "$CRASH" || fail 'pending crash is not consumed into durable history'
grep -Fq 'pending.delete()' "$CRASH" || fail 'consumed pending crash is not removed'
grep -Fq 'crash_history_v786' "$CRASH" || fail 'durable crash history directory missing'
grep -Fq 'MessageDigest.getInstance("SHA-256")' "$CRASH" || fail 'crash-history dedup fingerprint missing'
grep -Fq 'drop(CRASH_HISTORY_LIMIT)' "$CRASH" || fail 'old crash history is not trimmed'
pass '20-entry deduplicated crash history contract'

grep -Fq 'CrashLogStore.consumePendingIntoHistory(this)' "$MAIN" || fail 'MainActivity still re-presents raw pending log'
grep -Fq '异常日志（最近20条）' "$MAIN" || fail 'crash history browser entry missing'
if grep -Fq '复制并清除' "$MAIN"; then fail 'old copy-and-delete crash behavior remains'; fi
grep -Fq '历史记录仍保留' "$MAIN" || fail 'copy must retain history'
pass 'one-shot presentation + retained history UI contract'

grep -Fq 'holder.container.setPadding(dp(3), 0, dp(3), dp(18))' "$MAIN" || fail 'normal three-column card spacing not restored'
pass 'reading-history/normal shelf card spacing contract'

grep -Fq 'ReaderRuntimeState.isReaderForeground()' "$WORKER" || fail 'cache worker does not observe foreground reader'
grep -Fq 'shouldCancel = {' "$WORKER" || fail 'in-flight pagination lacks cooperative cancellation'
grep -Fq 'pausedForReader.set(true)' "$WORKER" || fail 'reader foreground yield flag missing'
grep -Fq 'return Result.retry()' "$WORKER" || fail 'reader foreground does not preserve checkpoint via retry'
grep -Fq 'failure is VirtualMachineError' "$WORKER" || fail 'fatal VM errors can still be swallowed as ordinary book failures'
grep -Fq 'images?.clear()' "$WORKER" || fail 'per-book image repository is not explicitly released'
grep -Fq 'shelf_cache_complete' "$WORKER" || fail 'cache completion memory snapshot missing'
pass 'full-shelf cache yields to foreground reader and releases per-book heavy resources'

grep -Fq '2098000786' "$BUILD" || fail 'versionCode 786 missing'
grep -Fq '"786"' "$BUILD" || fail 'versionName 786 missing'
pass 'v786 version'

echo 'V786_CRASH_CACHE_HISTORY_GATES_PASS'
