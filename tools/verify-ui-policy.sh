#!/usr/bin/env bash
set -euo pipefail
fail() { echo "UI POLICY FAILURE: $*" >&2; exit 1; }

reader=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
engine=app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt
layout=app/src/main/res/layout/activity_reader.xml

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

# Exact confirmed reader structures.
grep -q 'showCatalogBookmarkPanelV600' "$reader" || fail "v600 catalog/bookmark panel missing"
grep -q 'window.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)' "$reader" || fail "v600 side panel placement missing"
grep -q 'ReaderBackgroundPicker.show' "$reader" || fail "layered background picker missing"
grep -q 'ReaderBackgrounds.Selection' "$reader" || fail "colour-texture-material selection missing"
grep -q 'PagedReaderView.TurnMode.OVERLAP' "$reader" || fail "overlap mode missing"
grep -q 'PagedReaderView.TurnMode.SIMULATE' "$reader" || fail "simulation mode missing"
grep -q 'PagedReaderView.TurnMode.SLIDE' "$reader" || fail "slide mode missing"
grep -q 'readerScrollView.visibility = View.VISIBLE' "$reader" || fail "continuous reader missing"
! grep -q 'PagerSnapHelper' "$reader" || fail "vertical/page snap helper is forbidden"
! grep -q 'RecyclerView' "$reader" || fail "reader page containers are forbidden in ReaderActivity"

grep -q 'contentPaddingBottomPx = 0' "$reader" || fail "per-page bottom reservation must be zero"
grep -q 'bottomPaddingPx = 0' "$reader" || fail "horizontal renderer bottom reservation must be zero"
! grep -q 'paddingBottom="118dp"' "$layout" || fail "118dp page gap is forbidden"
grep -q 'android:paddingBottom="0dp"' "$layout" || fail "continuous document must not add per-page bottom space"
! grep -q '#33FFFFFF' "$layout" || fail "progress indicator background block is forbidden"
! grep -A14 '@+id/readerProgressLabel' "$layout" | grep -q 'android:background=' || fail "progress indicator must be plain text without a box"

grep -q 'styledWholeText' "$engine" || fail "whole-book continuous styling missing"
! grep -q 'TxtParser.isLikelyChapterTitle' "$engine" || fail "non-chapter title guessing is forbidden"

test -e app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt || fail "confirmed layered background source missing"
test -e app/src/main/java/com/simplereader/app/ui/ReaderBackgroundPicker.kt || fail "confirmed background picker missing"
test -e app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt || fail "confirmed horizontal renderer missing"
