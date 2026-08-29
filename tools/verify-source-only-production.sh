#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if grep -RInE 'classes[0-9]*\.dex|patch_classes|patch_v7[0-9][0-9]|dex offset|register patch' app/src/main --include='*.kt' --include='*.java' --include='*.xml'; then
  echo 'ERROR: production source references binary patch implementation' >&2
  exit 1
fi
[ "$(grep -c 'moreButton.text = "×"' app/src/main/java/com/simplereader/app/ui/MainActivity.kt)" -eq 2 ]
grep -q 'R.drawable.ic_bookmark_add' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'R.drawable.ic_bookmark_add' app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt
grep -q 'getBookmarkByPage' app/src/main/java/com/simplereader/app/data/dao/BookmarkDao.kt
grep -q 'withTransaction' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'withTransaction' app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt
grep -Fq 'navigationBarInsetPx + oneCharacterPx * 3' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'ExistingWorkPolicy.REPLACE' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
grep -q 'coroutineContext.ensureActive()' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
grep -q 'shouldContinue = { activeContext.isActive }' app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt
grep -q 'fun showLogHub(activity: AppCompatActivity) = showCrashLogList(activity)' app/src/main/java/com/simplereader/app/operation/OperationLogDialogs.kt
grep -q 'return emptyList()' app/src/main/java/com/simplereader/app/operation/OperationLogStore.kt
grep -q 'showAutoReadDialog' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'scheduleAutomaticPageTurn' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'startAutomaticVerticalScroll' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'MIN_AUTO_READ_CPM = 200' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'MAX_AUTO_READ_CPM = 2000' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q 'AUTO_READ_STEP_CPM = 50' app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
grep -q '@+id/autoReadButton' app/src/main/res/layout/activity_reader.xml
grep -q '@+id/autoReadStopButton' app/src/main/res/layout/activity_reader.xml
grep -q '?: "2098000747"' app/build.gradle.kts
grep -q '?: "747"' app/build.gradle.kts
echo 'Source-only V747 production guard passed.'
