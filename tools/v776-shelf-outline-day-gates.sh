#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/apply-v776-shelf-outline-day.py

GRADLE=app/build.gradle.kts
MAIN=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
SHELF_BINDER=app/src/main/java/com/simplereader/app/ui/ShelfDayNightModeIcon.kt
SHELF_DAY=app/src/main/res/drawable/ic_shelf_mode_day_outline.xml
READER=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
SHARED_BINDER=app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt
READER_XML=app/src/main/res/layout/activity_reader.xml
SHARED_DAY=app/src/main/res/drawable/ic_mode_day_a.xml
SHARED_NIGHT=app/src/main/res/drawable/ic_mode_night_a.xml

grep -Fq '2098000776' "$GRADLE"
grep -Fq 'SIMPLE_READER_VERSION_NAME") ?: "776"' "$GRADLE"

# Shelf-only implementation.
grep -Fq 'ShelfDayNightModeIcon.apply(' "$MAIN"
if grep -Eq '(^|[^A-Za-z0-9_])DayNightModeIcon\.apply\(' "$MAIN"; then
  echo 'FAIL: MainActivity still uses the shared reader icon binder'
  exit 1
fi
grep -Fq 'R.drawable.ic_shelf_mode_day_outline else R.drawable.ic_mode_night_a' "$SHELF_BINDER"
grep -Fq 'if (!isDay && drawable != null)' "$SHELF_BINDER"
grep -Fq 'DrawableCompat.setTint(drawable, tintColor)' "$SHELF_BINDER"
grep -Fq 'button.setImageDrawable(null)' "$SHELF_BINDER"
grep -Fq 'button.setImageDrawable(drawable)' "$SHELF_BINDER"

# Approved shelf DAY geometry: white center + fixed black border/rays.
grep -Fq 'android:fillColor="#FFFFFFFF"' "$SHELF_DAY"
grep -Fq 'android:strokeColor="#FF000000"' "$SHELF_DAY"
grep -Fq 'android:pathData="M12,7.25A4.75,4.75' "$SHELF_DAY"
grep -Fq 'M12,2.25L12,4.25' "$SHELF_DAY"

# Hard lock from the user's correction: Reader page is NOT modified in v776.
# This covers the reader Activity, its layout, the shared binder and both shared A icon resources.
git diff --quiet origin/source-v775 -- "$READER" "$READER_XML" "$SHARED_BINDER" "$SHARED_DAY" "$SHARED_NIGHT" || {
  echo 'FAIL: v776 modified reader-page day/night implementation; only shelf may change'
  git diff -- origin/source-v775 -- "$READER" "$READER_XML" "$SHARED_BINDER" "$SHARED_DAY" "$SHARED_NIGHT"
  exit 1
}

# Preserve exact state semantics: DAY shows sun; NIGHT shows moon.
grep -Fq 'val isDay = mode == ReaderAppearance.MODE_DAY' "$SHELF_BINDER"
grep -Fq '当前日间模式，点击切换夜间模式' "$SHELF_BINDER"
grep -Fq '当前夜间模式，点击切换日间模式' "$SHELF_BINDER"
grep -Fq 'ReaderAppearance.toggleMode(this@MainActivity)' "$MAIN"

# Preserve the v773 Android-35 startup crash fix and v771 handoff regression.
bash tools/v773-startup-fastscroll-gates.sh

echo 'v776 shelf-only outline-day icon gates: PASS (reader unchanged)'
