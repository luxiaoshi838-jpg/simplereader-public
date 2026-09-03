#!/usr/bin/env bash
set -euo pipefail
fail() { echo "UI POLICY FAILURE: $*" >&2; exit 1; }

reader=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
engine=app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt
layout=app/src/main/res/layout/activity_reader.xml
profile=app/src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt

# Rejected replacement implementations must be absent.
for path in \
  app/src/main/java/com/simplereader/app/ui/ReaderPanels.kt \
  app/src/main/java/com/simplereader/app/ui/ReaderCatalogSheet.kt \
  app/src/main/java/com/simplereader/app/ui/ReaderSurfaceDrawable.kt \
  app/src/main/java/com/simplereader/app/ui/ReaderPageAdapter.kt \
  app/src/main/java/com/simplereader/app/ui/VerticalPageFlowView.kt; do
  test ! -e "$path" || fail "rejected implementation remains: $path"
done

# Cover format text/cross hatch remain forbidden.
grep -R --line-number --fixed-strings 'drawText("TXT"' app/src/main/java && fail "TXT cover text is forbidden" || true
grep -R --line-number --fixed-strings 'txtPaint' app/src/main/java && fail "TXT cover paint is forbidden" || true

test -e app/src/main/res/drawable-nodpi/book_cover_default_txt.webp || fail "confirmed TXT/default cover missing"
grep -q 'android:name=".App"' app/src/main/AndroidManifest.xml || fail "App crash logger is not registered"

# Exact confirmed reader structures.
grep -q 'showCatalogBookmarkPanelV600' "$reader" || fail "v600 catalog/bookmark panel missing"
grep -q 'window.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)' "$reader" || fail "v600 side panel placement missing"
grep -q 'ReaderBackgroundPicker.show' "$reader" || fail "v625 single-background picker missing"
grep -q 'ReaderBackgrounds.Selection' "$reader" || fail "v625 single-background selection missing"
grep -q 'PagedReaderView.TurnMode.OVERLAP' "$reader" || fail "overlap mode missing"
grep -q 'PagedReaderView.TurnMode.SIMULATE' "$reader" || fail "simulation mode missing"
grep -q 'PagedReaderView.TurnMode.SLIDE' "$reader" || fail "slide mode missing"

# v632+ vertical architecture is locked: RecyclerView + LinearLayoutManager + ReaderPage adapter.
# The old NestedScrollView/TextView renderer may remain only as pagination-failure fallback.
grep -q 'private var verticalRecyclerView: RecyclerView?' "$reader" || fail "v632 RecyclerView continuous reader missing"
grep -q 'private var verticalLayoutManager: LinearLayoutManager?' "$reader" || fail "v632 LinearLayoutManager missing"
grep -q 'private var verticalAdapter: VerticalPageAdapter?' "$reader" || fail "v632 ReaderPage adapter missing"
grep -q 'verticalAdapter?.setPages(paged.pages)' "$reader" || fail "v632 ReaderPage virtualized list missing"
grep -q 'readerScrollView.visibility = View.VISIBLE' "$reader" || fail "continuous fallback reader missing"
! grep -q 'PagerSnapHelper' "$reader" || fail "vertical/page snap helper is forbidden"

# v722/current layout uses zero internal body padding. The guarded viewport itself starts one
# character below the status bar and ends three characters above the navigation bar.
grep -q 'CONTENT_BOTTOM_PADDING_DP = 0' "$profile" || fail "reader body bottom padding must remain zero"
grep -q 'bottomPaddingPx = settings.contentPaddingBottomPx' "$reader" || fail "horizontal renderer must honor zero internal bottom padding"
! grep -q 'paddingBottom="118dp"' "$layout" || fail "118dp page gap is forbidden"
grep -q 'android:paddingBottom="0dp"' "$layout" || fail "reader XML bottom padding must defer to runtime navigation-bar insets"
grep -q 'val bottomGuardPx = navigationBarInsetPx + oneCharacterPx \* 3' "$reader" || fail "reader lower viewport guard must remain navigation bar plus three characters"
! grep -q '#33FFFFFF' "$layout" || fail "progress indicator background block is forbidden"
! grep -A14 '@+id/readerProgressLabel' "$layout" | grep -q 'android:background=' || fail "progress indicator must be plain text without a box"

grep -q 'styledWholeText' "$engine" || fail "continuous styling missing"
grep -q 'renderContinuousWindow' "$reader" || fail "bounded fallback continuous window missing"
grep -q 'CONTINUOUS_PAGES_AFTER' "$reader" || fail "bounded fallback window size missing"
! grep -q 'TxtParser.isLikelyChapterTitle' "$engine" || fail "non-chapter title guessing is forbidden"

test -e app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt || fail "confirmed v625 background source missing"
test -e app/src/main/java/com/simplereader/app/ui/ReaderBackgroundPicker.kt || fail "confirmed v625 background picker missing"
test -e app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt || fail "confirmed horizontal renderer missing"

grep -q 'android:paddingTop="0dp"' "$layout" || fail "reader XML padding must defer to runtime system-bar insets"
grep -q 'WindowCompat.setDecorFitsSystemWindows(window, false)' "$reader" || fail "reader root must receive real system-bar insets"
grep -q 'val topGuardPx = statusBarInsetPx + oneCharacterPx' "$reader" || fail "reader upper limit must be notification-bar bottom plus one character"
! sed -n '1,8p' "$layout" | grep -q 'android:paddingTop=' || fail "reader root layout must keep the v612 top boundary"
grep -q 'TITLE_SIZE_DELTA_SP = 2f' "$profile" || fail "chapter title must be exactly 2sp larger"
grep -q 'updateCurrentChapterTitle' "$reader" || fail "reader chrome must use current chapter title"
grep -q '目录　${book?.title.orEmpty()}' "$reader" || fail "catalog must show book title beside 目录"
test -e app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt || fail "shelf catalog cache worker missing"
grep -q 'ExistingWorkPolicy.KEEP' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt || fail "background cache must remain unique and persistent"
grep -q 'hasCurrentCatalog' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt || fail "no-catalog mode must skip books with a current catalog"
grep -q 'forceCatalogRefresh = true' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt || fail "selected books must be re-recognized"
grep -q 'CATALOG_RULE_VERSION' app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt || fail "catalog rule version must be recorded"
grep -q 'MANIFEST_PREFIX' app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt || fail "layout-specific page manifests missing"
grep -q 'arrayOf("书架目录缓存", "批量管理分组", "同步书架")' app/src/main/java/com/simplereader/app/ui/MainActivity.kt || fail "shelf catalog cache entry missing from shelf management"
grep -q 'arrayOf("全书架目录缓存", "全书架无目录书籍缓存")' app/src/main/java/com/simplereader/app/ui/MainActivity.kt || fail "shelf catalog cache options missing"
grep -q 'MODE_BOOKS_WITHOUT_CATALOG' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt || fail "no-catalog shelf cache mode missing"
