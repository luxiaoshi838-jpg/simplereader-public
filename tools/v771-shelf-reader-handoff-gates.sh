#!/usr/bin/env bash
set -euo pipefail

reader="app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
worker="app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt"
handoff="app/src/main/java/com/simplereader/app/runtime/ShelfCacheHandoff.kt"
build="app/build.gradle.kts"

require_fixed() {
  local needle="$1" file="$2"
  grep -Fq "$needle" "$file" || {
    echo "missing: $needle in $file" >&2
    exit 1
  }
}

# This is a behavioral regression gate inherited from v771. Do not pin it to the historical
# v771 package version: later releases must retain the handoff behavior while incrementing version.
require_fixed 'val generatedVersionCode = (System.getenv("SIMPLE_READER_VERSION_CODE")' "$build"
require_fixed 'val generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME")' "$build"
require_fixed 'awaitShelfCacheReaderClaim()' "$reader"
require_fixed 'releaseShelfCacheReaderClaim(markCompleted = true)' "$reader"
require_fixed 'shelf_handoff:foreground_complete' "$reader"
require_fixed 'ShelfCacheHandoff.beginWork(id.toString())' "$worker"
require_fixed 'doWorkWithShelfHandoff()' "$worker"
require_fixed 'finally {' "$worker"
require_fixed 'ShelfCacheHandoff.endWork(id.toString())' "$worker"
require_fixed 'awaitWorkerBookClaim(book.id)' "$worker"
require_fixed 'ShelfCacheHandoff.consumeForegroundCompleted(book.id)' "$worker"
require_fixed 'currentTitle = "${book.title}（阅读器已完成）"' "$worker"
require_fixed 'ShelfCacheHandoff.releaseWorker(book.id)' "$worker"
require_fixed 'enum class ReaderClaimResult' "$handoff"
require_fixed 'WAIT_FOR_WORKER' "$handoff"

if grep -Fq 'override fun onStopped()' "$worker"; then
  echo "CoroutineWorker.onStopped must not be overridden" >&2
  exit 1
fi

# Once a worker owns the current book it must not wait for the foreground reader again, otherwise
# ReaderActivity waiting for that same worker would deadlock. The only call in processing code is
# the one at the start of each book; the other textual occurrence is the helper declaration itself.
count="$(grep -Fc 'awaitForegroundReaderIdle()' "$worker")"
if [ "$count" -ne 2 ]; then
  echo "unexpected awaitForegroundReaderIdle() occurrence count: $count" >&2
  exit 1
fi

./gradlew testDebugUnitTest --tests com.simplereader.app.runtime.ShelfCacheHandoffTest

echo "v771 shelf/reader handoff gates passed"
