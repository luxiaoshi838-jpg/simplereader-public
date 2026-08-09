#!/usr/bin/env bash
set -euo pipefail

fail() { echo "REJECTED READER IMPLEMENTATION: $*" >&2; exit 1; }

reader='app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
backgrounds='app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt'
picker='app/src/main/java/com/simplereader/app/ui/ReaderBackgroundPicker.kt'
covers='app/src/main/java/com/simplereader/app/ui/BookCoverAssets.kt'
layout='app/src/main/res/layout/activity_reader.xml'
assets='app/src/main/res/drawable-nodpi'

# 1) Reader chrome may overlay, but may never change reader geometry.
grep -Fq 'supportRequestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY)' "$reader" || fail 'ActionBar overlay missing'
grep -Fq 'readerControls.visibility = if (visible) View.VISIBLE else View.INVISIBLE' "$reader" || fail 'bottom chrome must use INVISIBLE, not GONE'
grep -A14 'private fun bindReaderInsets' "$reader" | grep -Fq 'if (!readerInsetsApplied)' || fail 'WindowInsets must be applied only once'
if grep -A14 'private fun bindReaderInsets' "$reader" | grep -Eq 'paginateAndDisplay|requestLayout|scrollTo|scrollY|layoutParams'; then
  fail 'WindowInsets still repaginates, relayouts, or compensates scroll position'
fi
grep -A14 'android:id="@+id/readerControls"' "$layout" | grep -Fq 'android:visibility="invisible"' || fail 'readerControls XML must start INVISIBLE'

# 2) No-cover / blank-cover implementation is forbidden.
grep -Fq 'R.drawable.book_cover_default_txt' "$covers" || fail 'confirmed TXT/default cover is not wired'
grep -Fq 'R.drawable.book_cover_default_epub' "$covers" || fail 'confirmed EPUB default cover is not wired'
if grep -Fq 'book_cover_default_generic' "$covers"; then fail 'generic/blank fallback cover implementation remains'; fi

# 3) Unsafe full-screen body is forbidden. Fixed system-bar + one-character guards must remain.
grep -Fq 'statusBarInsetPx + oneCharacterPx' "$reader" || fail 'fixed top safe bound missing'
grep -Fq 'navigationBarInsetPx + oneCharacterPx' "$reader" || fail 'fixed bottom safe bound missing'

# 4) Old layered/tiled/generated background implementation is forbidden everywhere in active source.
if grep -R -n -E 'LayeredReaderBackgroundDrawable|TILED_BITMAP|BitmapShader|TileMode\.REPEAT|自由搭配|纯色决定底色|纹理与质感.*叠加' app/src/main/java app/src/main/res; then
  fail 'old layered/tiled background implementation remains'
fi
if grep -R -n -E 'solid_ivory|solid_eye|solid_white|texture_duokan_green|material_duokan_|material_none|texture_none' "$backgrounds" "$picker" "$reader"; then
  fail 'old background model identifiers remain in active implementation'
fi
grep -Fq 'FullPageReaderBackgroundDrawable' "$backgrounds" || fail 'v625 full-page background drawable missing'
grep -Fq 'val category: Category' "$backgrounds" || fail 'single background category selection missing'
grep -Fq 'val optionId: String' "$backgrounds" || fail 'single background option selection missing'

# Old bad binary resources must not remain in the active tree.
for bad in \
  book_cover_default_generic.webp \
  reader_material_duokan_dark.webp \
  reader_material_duokan_green.webp \
  reader_material_duokan_grey.webp \
  reader_material_duokan_paper.webp \
  reader_material_duokan_warm.webp \
  reader_material_duokan_white.webp \
  reader_texture_duokan_blue.webp \
  reader_texture_duokan_green.webp \
  reader_texture_duokan_white.webp \
  reader_texture_duokan_yellow.webp; do
  test ! -e "$assets/$bad" || fail "obsolete binary resource remains: $bad"
done

# Only exact assets extracted from the verified real v625 APK are accepted.
check_asset() {
  local name="$1" expected="$2"
  local path="$assets/$name"
  test -f "$path" || fail "confirmed v625 asset missing: $name"
  local actual
  actual="$(sha256sum "$path" | awk '{print $1}')"
  test "$actual" = "$expected" || fail "confirmed v625 asset SHA mismatch: $name"
}

check_asset book_cover_default_txt.webp a53aa236ac378ff97fb4c2ea1782f90ebf871717d0e04f80949b1eee2c3ff83f
check_asset reader_material_vine_black.webp 54af0ee85a3df52ff48c824db44c24643aa40060c09d9f1ae4b9e0a12f898222
check_asset reader_material_vine_blue.webp f427defab27645f4527e78be36b63dac2baffe6181792ee60facaa98a3bd924a
check_asset reader_material_vine_green.webp 9f76af5fb50e3088305257df050ace78ca94bf532716f8adbfe05ea88f3266a1
check_asset reader_material_vine_white.webp d10bff62231a5a8768731b4000ab3cca089963947bc1a7a1f2d7cee0713ad8d9
check_asset reader_material_vine_yellow.webp 14205d25ad6dd958509d3cbe038e28286ede18bfe13316fd43685cd042a5cbdb
check_asset reader_scene_duokan_black.webp 914726d53dad8205ac24b09855f30db2eec8fe15c107b5b2d7c6519e5cbc0fba
check_asset reader_scene_duokan_blue.webp d118ae7b9a46110562f646d032f6a3642d9f809513947f212d83f43adf227887
check_asset reader_scene_duokan_green.webp 81ab407df318a3c01cc7d387d5dafee349e665ba83d8c42d15e269bb11771467
check_asset reader_scene_duokan_white.webp 30c6714f86fe4ead7161cd43d36fddcb11d4431e2d6925092d83b522586428ad
check_asset reader_scene_duokan_yellow.webp 474fad8c878bc9c9c31cefed608a4de578de034a9d97870d31475157be30fde8
check_asset reader_texture_theme_blue.webp f792c87f60afa58c6d18d0e09ed04e4d174e5e730308e4df44c885f00fa0700f
check_asset reader_texture_theme_green.webp adda2b567943920c752295ad343e59bfe46bd3548a5743254d03196c33a7f6a4
check_asset reader_texture_theme_white.webp 39c05763505eab09893d85d92d535978852fd2ef3069222e9af75059c052c82a
check_asset reader_texture_theme_yellow.webp 3defab0dc3d4313255b37a9808e3f8e9ef95f10d382a824241c887aedbe672d9

echo 'Rejected reader implementations: NONE in active source. Exact v625 asset gate: PASS.'
