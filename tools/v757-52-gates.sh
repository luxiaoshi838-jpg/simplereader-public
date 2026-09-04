#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash tools/v753-34-gates.sh

pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
D=app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt
T=app/src/main/java/com/simplereader/app/parser/TxtParser.kt
X=app/src/main/res/layout/activity_reader.xml
B=app/build.gradle.kts
A=app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt
APP=app/src/main/java/com/simplereader/app/App.kt
TEST=app/src/test/java/com/simplereader/app/parser/TxtCatalogRule112Test.kt

# 35 version/rule.
grep -Fq '2098000757' "$B" && grep -Fq '"757"' "$B" && grep -Fq 'CATALOG_RULE_VERSION = 113' "$T" && grep -Fq 'RULE_VERSION = 113' "$D" || fail 35 'v757/rule113 versions missing'
pass 35 'v757 version + catalog rule113'

python3 - "$D" "$T" "$TEST" <<'PY'
from pathlib import Path
import sys
direct=Path(sys.argv[1]).read_text(encoding='utf-8')
parser=Path(sys.argv[2]).read_text(encoding='utf-8')
test=Path(sys.argv[3]).read_text(encoding='utf-8')
for token in ['explicitNumberedTitle','numberLeadingUnit','hasValidStructuralTail','numericOnly.matches(s)']:
    assert token in direct, token
for token in ['hasGluedPrefixedStructuralText','startsWithNumeralToken','if (startsWithNumeralToken(normalized)) return null']:
    assert token in parser, token
for token in ['3节课','3节 课','第3节课','12章鱼','一条','3 天','12 公里']:
    assert token in test, token
PY
pass 36 'rule113 structural boundaries retained'

grep -Fq 'isFastScrollEnabled = true' "$R" && grep -Fq 'isFastScrollAlwaysVisible = true' "$R" || fail 37 'catalog fast scroll missing'
! grep -Fq '${rowIndex + 1}.${chapter.title}' "$R" || fail 37 'catalog numbering returned'
grep -Fq 'jumpToPage(paged.firstPageOfChapter(chapterIndex), false)' "$R" || fail 37 'catalog click navigation missing'
pass 37 'catalog behavior retained'

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root=ET.parse('app/src/main/res/layout/activity_reader.xml').getroot(); ns='{http://schemas.android.com/apk/res/android}'
parent={c:p for p in root.iter() for c in p}
stop=next(e for e in root.iter() if e.get(ns+'id')=='@+id/autoReadStopButton')
viewport=next(e for e in root.iter() if e.get(ns+'id')=='@+id/readerViewport')
assert parent[stop] is viewport
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
s=r.index('    private fun startAutoReading()'); e=r.index('    private fun scheduleAutomaticPageTurn()',s); b=r[s:e]
assert 'setReaderChromeVisible(false)' in b and b.index('setReaderChromeVisible(false)') < b.index('showAutoReadStopButton()')
PY
pass 38 'auto-reading chrome/stop control retained'

for token in 'suspendedAnchorOffset = anchorOffset' 'suspendedAnchorViewportPx = if (anchorPageIndex == index)' 'scrollToPositionWithOffset(pageIndex, viewportOffset)'; do
  grep -Fq "$token" "$R" || fail 39 "focus visual anchor missing: $token"
done
pass 39 'focus visual anchor retained without stable promotion'

grep -Fq 'val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset())' "$R" || fail 40 'fallback stable anchor missing'
python3 - <<'PY'
from pathlib import Path
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(); s=r.index('    private fun showContinuousFallback('); e=r.index('    private fun sourceOffsetAtScroll(',s); b=r[s:e]
assert 'continuousWindowStartOffset = 0' not in b
assert 'loaded.text.substring(start, end)' in b
PY
pass 40 'fallback cannot reset to page 1'

grep -Fq 'onHit = { hit -> jumpToPage(hit.globalPageIndex, false, hit) }' "$R" || fail 41 'search navigation missing'
grep -Fq 'suspendedAnchorOffset = targetPage.startOffset' "$R" || fail 41 'explicit search anchor missing'
pass 41 'search jump retained'

for token in 'RecyclerView(this)' 'LinearLayoutManager(this, RecyclerView.VERTICAL, false)' 'verticalAdapter?.setPages(paged.pages)' 'scrollToPositionWithOffset(pageIndex, viewportOffset)'; do
  grep -Fq "$token" "$R" || fail 42 "RecyclerView anti-rollback architecture missing: $token"
done
pass 42 'RecyclerView virtualized reader retained'

python3 - "$A" <<'PY'
from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'override fun onScrollStateChanged\(.*?\n    \}',s,re.S); assert m
b=m.group(0)
for token in ['RecyclerView.SCROLL_STATE_DRAGGING','val hitPage = activity.verticalOnUserDrag()','clearTransientSearchHighlight(recyclerView, hitPage)']:
    assert token in b, token
assert 'recyclerView.post' not in b
PY
pass 43 'drag clears search without deferred adapter rebind'

python3 - "$A" <<'PY'
from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView, position: Int\) \{(.*?)\n    \}',s,re.S); assert m
b=m.group(1)
for token in ['rendered.remove(position)','findViewHolderForAdapterPosition(position)','getSpans','removeSpan','invalidate()']:
    assert token in b, token
for forbidden in ['evictAll()','notifyItemRangeChanged','notifyDataSetChanged','notifyItemChanged','recyclerView.post']:
    assert forbidden not in b, forbidden
PY
pass 44 'search highlight clear is target-only and layout-free'

python3 - "$A" <<'PY'
from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView, position: Int\) \{(.*?)\n    \}',s,re.S); b=m.group(1)
for forbidden in ['scrollToPosition','scrollToPositionWithOffset','smoothScroll','scrollBy(','jumpToPage','currentPageIndex =','lastStableSourceOffset','suspendedAnchorOffset']:
    assert forbidden not in b, forbidden
PY
pass 45 'highlight removal cannot move reader position'

grep -Fq 'androidx.work:work-runtime-ktx:2.10.5' "$B" || fail 46 'WorkManager 2.10.5 missing'
pass 46 'Android foreground timeout fix retained'

for token in 'private fun stableProgressSourceOffset(): Int?' 'if (verticalWindowSuspended || verticalProgrammaticScroll)' 'return stable ?: suspended ?: visible ?: current' 'private fun progressSnapshotForOffset(sourceOffset: Int): ReadProgress?' '(application as App).applicationScope.launch'; do
  grep -Fq "$token" "$R" || fail 47 "stable progress protection missing: $token"
done
grep -Fq 'val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)' "$APP" || fail 47 'application scope missing'
pass 47 'stable progress survives Activity destruction'

for token in 'private fun scheduleVerticalStateUnlockGuard()' 'mainHandler.postDelayed(it, VERTICAL_STATE_UNLOCK_GUARD_MS)' 'releaseVerticalStateLock(clearAnchor = true)' 'hasWindowFocus() && (verticalWindowSuspended || verticalProgrammaticScroll)' 'private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L'; do
  grep -Fq "$token" "$R" || fail 48 "unlock failsafe missing: $token"
done
pass 48 'vertical state locks retain timed/touch failsafe'

python3 - "$R" <<'PY'
from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'override fun onWindowFocusChanged\(hasFocus: Boolean\) \{(.*?)\n    \}',s,re.S); assert m
b=m.group(1)
assert 'lastStableSourceOffset =' not in b
assert 'val anchorOffset = lastStableSourceOffset' in b
assert 'suspendedAnchorOffset = anchorOffset' in b
PY
pass 49 'focus restore cannot poison committed stable source anchor'

for token in 'private var progressCheckpointRunnable: Runnable? = null' 'if (changed) scheduleProgressCheckpoint(pages[index].startOffset)' 'private fun scheduleProgressCheckpoint(sourceOffset: Int)' 'mainHandler.postDelayed(it, PROGRESS_CHECKPOINT_DELAY_MS)' 'private const val PROGRESS_CHECKPOINT_DELAY_MS = 600L'; do
  grep -Fq "$token" "$R" || fail 50 "progress checkpoint missing: $token"
done
pass 50 'vertical reading checkpoints committed page progress'

python3 - "$R" <<'PY'
from pathlib import Path
import re,sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'private fun progressSnapshotForOffset\(sourceOffset: Int\): ReadProgress\? \{(.*?)\n    \}',s,re.S); assert m
b=m.group(1)
for forbidden in ['currentPageIndex =','lastStableSourceOffset =','scrollToPosition','jumpToPage']:
    assert forbidden not in b, forbidden
m2=re.search(r'private fun saveProgress\(\) \{(.*?)\n    \}',s,re.S); assert m2
assert 'progressSnapshotForOffset(sourceOffset)' in m2.group(1)
assert '(application as App).applicationScope.launch' in m2.group(1)
PY
pass 51 'persistence snapshots cannot mutate live reading position'

python3 - "$A" "$R" <<'PY'
from pathlib import Path
import sys
a=Path(sys.argv[1]).read_text(encoding='utf-8'); r=Path(sys.argv[2]).read_text(encoding='utf-8')
assert 'rendered.evictAll()\n        val manager = recyclerView.layoutManager' not in a
assert 'notifyItemRangeChanged(first, last - first + 1)' not in a
assert 'lastStableSourceOffset = suspendedAnchorOffset' not in r
assert 'lastStableSourceOffset = anchorOffset\n                    verticalLayoutManager?.scrollToPositionWithOffset' not in r
PY
pass 52 'v756 freeze/old-chapter regression paths are absent'

printf 'ALL_52_GATES_PASS\n'
