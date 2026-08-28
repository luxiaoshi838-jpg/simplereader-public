#!/usr/bin/env bash
set -euo pipefail
fail() { echo "V722 CACHE OVERLAY FAILURE: $*" >&2; exit 1; }

worker='app/src/main/java/com/simplereader/app/worker/ShelfCacheWorker.kt'
checkpoint='app/src/main/java/com/simplereader/app/worker/ShelfCacheCheckpointStore.kt'
profile='app/src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt'
store='app/src/main/java/com/simplereader/app/operation/OperationLogStore.kt'
dialogs='app/src/main/java/com/simplereader/app/operation/OperationLogDialogs.kt'
main='app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
manifest='app/src/main/AndroidManifest.xml'

# Canonical parsed v722 baseline must remain present, and public Git history must remain APK-free.
test -f baseline/v722/baseline.json || fail 'parsed v722 baseline is missing'
test -f baseline/v722/critical-reader-methods.json || fail 'critical v722 method baseline is missing'
bash tools/verify-no-apk.sh

# v722 cache identity: body TextView contributes no top/bottom internal padding.
grep -Fq 'const val CONTENT_TOP_PADDING_DP = 0' "$profile" || fail 'v722 top content padding must be 0'
grep -Fq 'const val CONTENT_BOTTOM_PADDING_DP = 0' "$profile" || fail 'v722 bottom content padding must be 0'

# Full shelf catalog means recognition + full pagination + persisted reusable pages.
grep -Fq 'PageEngine.paginate(' "$worker" || fail 'full pagination is missing from shelf cache'
grep -Fq 'PageCacheStore.savePages(applicationContext, identity, paged)' "$worker" || fail 'page cache persistence is missing'
grep -Fq 'PageCacheStore.loadPages(applicationContext, identity, document.text)' "$worker" || fail 'persisted page-cache verification is missing'
grep -Fq 'require(verified != null)' "$worker" || fail 'written page cache is not verified'
grep -Fq 'require(verified.pages.size == paged.pages.size)' "$worker" || fail 'written page count is not verified'
grep -Fq 'PageCacheStore.markRecognitionComplete(' "$worker" || fail 'recognition completion marker is missing'

# No-catalog mode may skip only when both catalog and reusable page cache are current.
grep -Fq 'PageCacheStore.hasCurrentCatalog(' "$worker" || fail 'current catalog check is missing'
grep -Fq 'cached != null && cached.pages.isNotEmpty()' "$worker" || fail 'reusable page cache must be required before skipping'

# Work must survive leaving the app and be owned by WorkManager foreground execution.
grep -Fq ': CoroutineWorker(' "$worker" || fail 'shelf cache is not a CoroutineWorker'
grep -Fq 'setForeground(' "$worker" || fail 'foreground execution is missing'
grep -Fq 'enqueueUniqueWork(' "$worker" || fail 'unique WorkManager execution is missing'
grep -Fq 'ExistingWorkPolicy.KEEP' "$worker" || fail 'active shelf task must not be duplicated'
grep -Fq 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' "$manifest" || fail 'data-sync foreground permission missing'
grep -Fq 'android:foregroundServiceType="dataSync"' "$manifest" || fail 'data-sync foreground service type missing'

# Same WorkRequest resumes from a durable checkpoint instead of starting the shelf over.
test -f "$checkpoint" || fail 'durable checkpoint store missing'
grep -Fq 'val nextIndex: Int' "$checkpoint" || fail 'checkpoint nextIndex missing'
grep -Fq 'val bookIds: List<Long>' "$checkpoint" || fail 'checkpoint book snapshot missing'
grep -Fq 'ShelfCacheCheckpointStore.load(applicationContext, workId)' "$worker" || fail 'worker does not load its checkpoint'
grep -Fq 'for (index in resumeIndex until total)' "$worker" || fail 'worker does not resume at saved index'
grep -Fq 'ShelfCacheCheckpointStore.save(applicationContext, workId, checkpoint)' "$worker" || fail 'worker does not persist progress checkpoints'

# v725 status box + operation-log behavior stays intact.
grep -Fq 'ShelfCacheUiController.attach(this, readingStatsTextView) { updateUI() }' "$main" || fail 'v725 status box controller missing'
grep -Fq 'ShelfCacheUiController.showPreparing(this, readingStatsTextView)' "$main" || fail 'v725 preparing status missing'
grep -Fq 'const val MAX_ENTRIES = 10' "$store" || fail 'operation log must be capped at 10 entries'
grep -Fq 'if (current.any { it.id == workId }) return' "$store" || fail 'same resumed task would create/reset another log entry'

list_block="$(sed -n '/fun showOperationList/,/private fun showOperationDetail/p' "$dialogs")"
detail_block="$(sed -n '/private fun showOperationDetail/,/private fun showPendingCrashLog/p' "$dialogs")"
[[ "$list_block" != *'setPositiveButton("复制")'* ]] || fail 'operation-log list page must not have copy button'
[[ "$list_block" == *'showOperationDetail(activity, it)'* ]] || fail 'operation-log list must be clickable'
[[ "$detail_block" == *'setPositiveButton("复制")'* ]] || fail 'log detail page must have copy button'

echo 'v722 cache overlay contract: PASS'
