#!/usr/bin/env bash
set -euo pipefail

bash tools/v755-42-gates.sh

BUILD="app/build.gradle.kts"
ADAPTER="app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt"
READER="app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"

fail() { echo "FAIL gate $1: $2" >&2; exit 1; }
pass() { echo "PASS gate $1: $2"; }

# Gate 43: v756 identity must be the source default as well as the release workflow override.
grep -Fq '"2098000756"' "$BUILD" || fail 43 "versionCode 2098000756 missing"
grep -Fq '"756"' "$BUILD" || fail 43 "versionName 756 missing"
pass 43 "v756 identity"

# Gate 44: a user drag must clear search state and then refresh only RecyclerView rendering.
python3 - "$ADAPTER" <<'PY'
from pathlib import Path
import re, sys
s = Path(sys.argv[1]).read_text(encoding='utf-8')
m = re.search(r'override fun onScrollStateChanged\(.*?\n    \}', s, re.S)
if not m:
    raise SystemExit('onScrollStateChanged missing')
b = m.group(0)
required = [
    'RecyclerView.SCROLL_STATE_DRAGGING',
    'activity.verticalOnUserDrag()',
    'recyclerView.post',
    'clearTransientSearchHighlight(recyclerView)'
]
for token in required:
    if token not in b:
        raise SystemExit(f'missing drag highlight contract: {token}')
if b.index('activity.verticalOnUserDrag()') > b.index('clearTransientSearchHighlight(recyclerView)'):
    raise SystemExit('search state must clear before adapter cache rebind')
PY
pass 44 "drag clears transient search highlight"

# Gate 45: clearing highlight may rebind visible rows, but it must never move/restore reader position.
python3 - "$ADAPTER" <<'PY'
from pathlib import Path
import re, sys
s = Path(sys.argv[1]).read_text(encoding='utf-8')
m = re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView\) \{(.*?)\n    \}', s, re.S)
if not m:
    raise SystemExit('clearTransientSearchHighlight missing')
b = m.group(1)
for token in ['rendered.evictAll()', 'findFirstVisibleItemPosition()', 'findLastVisibleItemPosition()', 'notifyItemRangeChanged']:
    if token not in b:
        raise SystemExit(f'missing visible-only rebind contract: {token}')
for forbidden in [
    'scrollToPosition', 'scrollToPositionWithOffset', 'smoothScroll', 'scrollBy(',
    'jumpToPage', 'currentPageIndex =', 'suspendedAnchorOffset', 'lastStableSourceOffset'
]:
    if forbidden in b:
        raise SystemExit(f'highlight clear must not mutate reader position: {forbidden}')
PY
pass 45 "highlight clear cannot move reader position"

# Gate 46: Android 15/16 dataSync foreground timeout handling must use a WorkManager generation
# that contains the upstream SystemForegroundService stopSelf timeout fix (2.10.0+); pin current stable.
grep -Fq 'androidx.work:work-runtime-ktx:2.11.2' "$BUILD" || fail 46 "WorkManager 2.11.2 required"
# Keep v632/v755 protections explicitly visible in this newest gate as well.
grep -Fq 'private var verticalRecyclerView: RecyclerView?' "$READER" || fail 46 "RecyclerView reader missing"
grep -Fq 'private var verticalLayoutManager: LinearLayoutManager?' "$READER" || fail 46 "LinearLayoutManager reader missing"
grep -Fq 'suspendedAnchorOffset = targetPage.startOffset' "$READER" || fail 46 "v755 explicit-navigation anchor replacement missing"
pass 46 "Android 15/16 WorkManager + reader rollback protections"

echo "PASS v756 46/46 gates"
