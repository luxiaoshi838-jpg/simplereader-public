# v728 binary overlay

v728 keeps the shipped v722 APK as the binary/UI baseline and carries forward the authorized v727 fixes. The new change addresses shelf catalog+pagination work pausing after the user switches to another app.

## Actual APK change

The shipped v727 `ShelfCacheWorker` already calls `CoroutineWorker.setForeground()` and the manifest already contains `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and WorkManager's `SystemForegroundService`. The remaining weakness is that the long-running work is still owned only by WorkManager/JobScheduler.

v728 therefore adds a second, independent foreground owner for the same user-triggered task:

- `SimpleReaderBackupDecoder.KeepAliveService` is declared as a non-exported `dataSync` foreground service in the binary manifest.
- Worker start (`__worker_started__`) starts the keep-alive service through the existing bounded operation-log bridge.
- The service holds a non-reference-counted `PARTIAL_WAKE_LOCK`, bounded to 6 hours, while the unique shelf-cache WorkRequest is unfinished.
- A watchdog checks `simple_reader_cache_all_shelf_books`; once no unfinished WorkInfo remains it stops itself and releases the wake lock.
- Normal task completion also stops the keep-alive service immediately.
- The existing WorkManager checkpoint remains the recovery mechanism after process/device interruption.

This is deliberately additive: reader UI, reader settings, page-turn logic, catalog rules, page-cache identity, book/shelf behavior and all other v722 assets stay unchanged.

## APK allowlist

Relative to the canonical v722 APK, non-signature differences remain restricted to:

- `AndroidManifest.xml`
- `classes3.dex`
- new/replaced `classes5.dex`

The production APK, keystore and passwords are not stored in the public repository.

## Android 16 note

Android 16 applies JobScheduler runtime quota to WorkManager long-running workers even when WorkManager runs a foreground service. The keep-alive service prevents the app process/CPU from being casually suspended during an ordinary app switch, while WorkManager remains responsible for checkpoint/recovery. If a device actually stops the JobScheduler work because its Android 16 job quota is exhausted, the checkpoint remains intact and the task can resume when scheduling is allowed again.
