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
grep -q '?: "2098000746"' app/build.gradle.kts
grep -q '?: "746"' app/build.gradle.kts
echo 'Source-only V746 production guard passed.'
