#!/usr/bin/env bash
set -euo pipefail
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
L=app/src/main/res/layout/activity_reader.xml
M=app/src/main/AndroidManifest.xml
B=UI_BASELINE.md
! grep -q "supportActionBar" "$R"
! grep -q "paginateAndDisplay(stableOffset)" "$R"
grep -q 'android:id="@+id/readerTopBar"' "$L"
grep -q 'android:layout_gravity="top"' "$L"
grep -q 'android:id="@+id/readerControls"' "$L"
grep -q 'android:layout_gravity="bottom"' "$L"
grep -q 'android:theme="@style/Theme.SimpleReader.Reader"' "$M"
grep -q '阅读上下栏覆盖规则（永久）' "$B"
echo "reader chrome overlay policy: PASS"
