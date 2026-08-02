#!/usr/bin/env bash
set -euo pipefail

fail() { echo "UI POLICY FAILURE: $*" >&2; exit 1; }

# Forbidden historical implementations must not exist in the active source tree.
test ! -e app/src/main/java/com/simplereader/app/ui/ReaderPanels.kt || fail "legacy ReaderPanels.kt is forbidden"
test ! -e .github/v14-continuous-build-status.md || fail "v14 status file is forbidden"
if find .github/workflows -maxdepth 1 -type f -iname '*v14*' | grep -q .; then
  fail "v14 workflow files are forbidden"
fi

grep -R --line-number --fixed-strings 'drawText("TXT"' app/src/main/java && fail "TXT cover text is forbidden" || true
grep -R --line-number --fixed-strings 'txtPaint' app/src/main/java && fail "TXT cover paint is forbidden" || true
grep -R --line-number --fixed-strings 'verticalCount' app/src/main/java/com/simplereader/app/ui/PaperCoverDrawable.kt && fail "cross-hatch cover is forbidden" || true
grep -R --line-number --fixed-strings 'lineCount' app/src/main/java/com/simplereader/app/ui/PaperCoverDrawable.kt && fail "regular lined cover is forbidden" || true
grep -R --line-number --fixed-strings 'onAddBookmark' app/src/main/java/com/simplereader/app/ui/ReaderCatalogSheet.kt && fail "bookmark creation must not be inside catalog/bookmark sheet" || true
grep -R --line-number --fixed-strings '添加书签' app/src/main/java/com/simplereader/app/ui/ReaderCatalogSheet.kt && fail "bookmark creation label must not appear in catalog/bookmark sheet" || true

grep -q 'backgroundFactory' app/src/main/java/com/simplereader/app/ui/ReaderPageAdapter.kt || fail "reader page adapter must accept a layered background factory"
grep -q 'ReaderSurfaceDrawable' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt || fail "reader pages must use layered surface drawable"
grep -q 'contentPaddingBottomPx = dp(26)' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt || fail "page paginator must not inherit the old 118dp bottom gap"
! grep -q 'paddingBottom="118dp"' app/src/main/res/layout/activity_reader.xml || fail "118dp reader bottom gap is forbidden"

grep -q 'TURN_MODE_SIMULATE' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt || fail "simulation mode missing"
grep -q 'rotationY' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt || fail "simulation mode must have a distinct perspective animation"
grep -q 'TURN_MODE_HORIZONTAL' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt || fail "horizontal mode missing"
grep -q 'translationX' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt || fail "overlap/horizontal modes must not collapse into one behavior"

# Exact hashes lock the finished UI files. Update only after explicit owner approval.
if [[ -f ui-lock.sha256 ]]; then
  sha256sum -c ui-lock.sha256
fi
