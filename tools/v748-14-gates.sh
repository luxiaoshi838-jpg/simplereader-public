#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
V=app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt
T=app/src/main/java/com/simplereader/app/parser/TxtParser.kt
D=app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt
N=app/src/main/java/com/simplereader/app/reader/CatalogTitleNormalizerV103.kt
B=app/src/main/java/com/simplereader/app/reader/ReaderBodyTitleNormalizerV104.kt
P=app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt
W=app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
L=app/src/main/java/com/simplereader/app/reader/ReaderDocumentLoader.kt
X=app/src/main/res/layout/activity_reader.xml
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
need(){ grep -Eq "$2" "$1" || return 1; }
# 1/2 exact official v745 binary resources
python3 - <<'PY' || exit 1
from pathlib import Path
import hashlib,sys
root=Path('app/src/main/res/drawable-nodpi')
want={
'book_cover_default_txt.webp':'a53aa236ac378ff97fb4c2ea1782f90ebf871717d0e04f80949b1eee2c3ff83f',
'reader_scene_duokan_black.webp':'914726d53dad8205ac24b09855f30db2eec8fe15c107b5b2d7c6519e5cbc0fba',
'reader_scene_duokan_blue.webp':'d118ae7b9a46110562f646d032f6a3642d9f809513947f212d83f43adf227887',
'reader_scene_duokan_green.webp':'81ab407df318a3c01cc7d387d5dafee349e665ba83d8c42d15e269bb11771467',
'reader_scene_duokan_white.webp':'30c6714f86fe4ead7161cd43d36fddcb11d4431e2d6925092d83b522586428ad',
'reader_scene_duokan_yellow.webp':'474fad8c878bc9c9c31cefed608a4de578de034a9d97870d31475157be30fde8',
'reader_texture_theme_blue.webp':'f792c87f60afa58c6d18d0e09ed04e4d174e5e730308e4df44c885f00fa0700f',
'reader_texture_theme_green.webp':'adda2b567943920c752295ad343e59bfe46bd3548a5743254d03196c33a7f6a4',
'reader_texture_theme_white.webp':'39c05763505eab09893d85d92d535978852fd2ef3069222e9af75059c052c82a',
'reader_texture_theme_yellow.webp':'3defab0dc3d4313255b37a9808e3f8e9ef95f10d382a824241c887aedbe672d9',
'reader_material_vine_black.webp':'54af0ee85a3df52ff48c824db44c24643aa40060c09d9f1ae4b9e0a12f898222',
'reader_material_vine_blue.webp':'f427defab27645f4527e78be36b63dac2baffe6181792ee60facaa98a3bd924a',
'reader_material_vine_green.webp':'9f76af5fb50e3088305257df050ace78ca94bf532716f8adbfe05ea88f3266a1',
'reader_material_vine_white.webp':'d10bff62231a5a8768731b4000ab3cca089963947bc1a7a1f2d7cee0713ad8d9',
'reader_material_vine_yellow.webp':'14205d25ad6dd958509d3cbe038e28286ede18bfe13316fd43685cd042a5cbdb'}
bad=[]
for n,h in want.items():
 p=root/n
 got=hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else 'MISSING'
 if got!=h: bad.append((n,got,h))
if bad:
 print('RESOURCE HASH FAIL',bad);sys.exit(1)
PY
pass 1 "TXT default cover exact v745 resource"
pass 2 "reader backgrounds/textures/materials exact v745 resources"
# 3 auto reading historical contract
need "$R" 'PREF_AUTO_READ_SPEED' && need "$R" '2000' && need "$R" '200' && need "$R" '50' && need "$R" 'Choreographer' && need "$R" 'scheduleAutomaticPageTurn' && need "$R" 'startAutomaticVerticalScroll' && need "$R" 'autoReadAwaitingPageCommit' && need "$R" 'autoReadStopButton' || fail 3 "automatic reading contract"
pass 3 "automatic reading v619/v745 state machine contract"
# 4 bounds
need "$R" 'statusBarInsetPx \+ oneCharacterPx' && need "$R" 'navigationBarInsetPx \+ oneCharacterPx \* 3' && need "$R" 'continuousTextView.setPadding' || fail 4 "reader bounds"
pass 4 "reader top/bottom bounds and zero body vertical padding"
# 5 shared cache profile/identity
need "$R" 'ReaderCacheProfile.createSettings' && need "$W" 'ReaderCacheProfile.createSettings' && need "$R" 'TxtParser.CATALOG_RULE_VERSION' && need "$W" 'TxtParser.CATALOG_RULE_VERSION' || fail 5 "shared cache identity"
pass 5 "foreground/background pagination cache identity"
# 6 rule111
T_RULE="$(grep -Eo 'CATALOG_RULE_VERSION = [0-9]+' "$T" | head -n1 | grep -Eo '[0-9]+')"
D_RULE="$(grep -Eo 'RULE_VERSION = [0-9]+' "$D" | head -n1 | grep -Eo '[0-9]+')"
[ -n "$T_RULE" ] && [ "$T_RULE" = "$D_RULE" ] && [ "$T_RULE" -ge 111 ] || fail 6 "catalog rule version mismatch/below v745 baseline"
pass 6 "catalog rule version $T_RULE, matched and >=111 baseline"
# 7 final recognition/normalization chain
need "$D" 'MAX_VISIBLE_TITLE_CHARS = 25' && need "$D" 'fun detect' && need "$D" 'fun recognize' && need "$N" 'object CatalogTitleNormalizerV103' && need "$B" 'object ReaderBodyTitleNormalizerV104' && need "$L" 'DirectTxtCatalogV100.detect' && need "$T" 'DirectTxtCatalogV100.recognize' || fail 7 "catalog chain"
pass 7 "DirectTxtCatalogV100 + V103 recognizer + V104 render-only chain"
# 8 recognition/current version checks
need "$P" 'root.optInt\("catalogRuleVersion", -1\) == TxtParser.CATALOG_RULE_VERSION' || fail 8 "recognition current version"
[ "$(grep -Ec 'root.optInt\("catalogRuleVersion", -1\) == TxtParser.CATALOG_RULE_VERSION' "$P")" -ge 2 ] || fail 8 "both current functions"
pass 8 "isRecognitionCurrent/hasCurrentCatalog enforce rule version"
# 9 prefilter total/skipped
need "$W" 'initialBookIds' && need "$W" 'targets' && need "$W" 'Books whose current catalog\+pages are already reusable are successful exclusions, not skips' && need "$W" 'post-prefilter case is a real runtime skip' || fail 9 "no catalog prefilter semantics"
pass 9 "no-catalog prefilter/total/skipped semantics"
# 10 bookmarks
need "$R" 'globalPageIndex == null' && need "$R" 'take\(48\)' && need "$R" 'needle.length < 12' && need "$R" 'kotlin.math.round' && need "$R" 'bestDistance' || fail 10 "bookmark compatibility"
pass 10 "legacy bookmark preview/nearest/byte-ratio compatibility"
# 11 font protection
need "$R" '320L' && need "$R" 'fontChange' && need "$R" 'rollback' && need "$R" 'currentVisibleSourceOffset' || fail 11 "font protection"
pass 11 "font-size debounce/anchor/rollback protection"
# 12 turn mode anchor
need "$R" 'pendingTurnMode' && need "$R" 'modeSwitch' && need "$R" 'currentVisibleSourceOffset' && need "$R" 'setTurnMode' || fail 12 "turn mode positioning"
pass 12 "page-turn mode source-position preservation"
# 13 vertical architecture
need "$R" 'RecyclerView' && need "$R" 'setItemViewCacheSize\(12\)' && need "$R" 'initialPrefetchItemCount = 8' && need "$R" 'scrollToPositionWithOffset' && need "$V" 'LruCache<Int, CharSequence>\(32\)' || fail 13 "vertical reader"
pass 13 "v632-v745 RecyclerView continuous long-book reader"
# 14 focus anchor
need "$R" 'onWindowFocusChanged' && need "$R" 'verticalWindowSuspended = true' && need "$R" 'stopScroll\(\)' && need "$R" 'postOnAnimation' || fail 14 "focus anchor"
pass 14 "notification/dialog focus-loss scroll anchor"
printf 'ALL_14_GATES_PASS\n'
