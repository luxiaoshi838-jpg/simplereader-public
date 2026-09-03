#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash tools/v756-46-gates.sh

R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
APP=app/src/main/java/com/simplereader/app/App.kt
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }

# Gate 47: pause/exit persistence is source-anchor based and survives Activity destruction.
for token in \
  'private fun stableProgressSourceOffset(): Int?' \
  'if (verticalWindowSuspended) return suspended ?: visible ?: stable ?: current' \
  'visible == 0 && stable != null && stable > 0 -> stable' \
  'val sourceOffset = stableProgressSourceOffset() ?: return' \
  'val page = paged.pageForOffset(sourceOffset.coerceIn(0, paged.text.length))' \
  '(application as App).applicationScope.launch'; do
  grep -Fq "$token" "$R" || fail 47 "stable progress persistence missing: $token"
done
grep -Fq 'val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)' "$APP" || fail 47 'application-lifetime progress scope missing'
pass 47 'stable source progress cannot be overwritten by stale page-0 state on exit'

# Gate 48: focus/programmatic suspension has both normal next-frame release and a timed/user-touch failsafe.
for token in \
  'private fun scheduleVerticalStateUnlockGuard()' \
  'mainHandler.postDelayed(it, VERTICAL_STATE_UNLOCK_GUARD_MS)' \
  'private fun releaseVerticalStateLock(clearAnchor: Boolean)' \
  'rv.postOnAnimation {' \
  'releaseVerticalStateLock(clearAnchor = true)' \
  'hasWindowFocus() && (verticalWindowSuspended || verticalProgrammaticScroll)' \
  'private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L'; do
  grep -Fq "$token" "$R" || fail 48 "vertical unlock failsafe missing: $token"
done
pass 48 'RecyclerView focus/programmatic locks cannot remain permanently stuck'

printf 'ALL_48_GATES_PASS\n'
