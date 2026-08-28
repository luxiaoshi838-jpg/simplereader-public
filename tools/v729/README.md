# v729 startup-crash fix

v729 fixes the v728 production APK startup crash without changing the v722 reader/UI baseline.

## Root cause

The v728 binary overlay added `SimpleReaderBackupDecoder.KeepAliveService`, which acquires a `PowerManager.PARTIAL_WAKE_LOCK`, but the **actual generated v728 APK manifest** did not contain `android.permission.WAKE_LOCK`. When an unfinished shelf-cache job resumed during app startup, the service could reach `WakeLock.acquire()` and throw `SecurityException`, killing the app process.

The source-tree manifest already contained the permission, so this was specifically a production binary-overlay/build mismatch rather than a source intent problem.

## v729 fixes

- Actual production APK manifest contains `android.permission.WAKE_LOCK`.
- Keep-alive service remains a `dataSync` foreground service.
- Wake-lock acquisition is wrapped so any permission/vendor/device failure degrades keep-alive behavior instead of crashing SimpleReader.
- v727 pagination identity fix (`catalogRuleVersion=111`, v722 viewport identity) is preserved.
- WorkManager checkpoint/resume remains the owner of shelf-cache progress.

## Production verification

Before signing, inspect the **actual APK** (not only source files) and require all of the following:

- versionCode `2098000729`
- versionName `729`
- `android.permission.WAKE_LOCK`
- `SimpleReaderBackupDecoder.KeepAliveService`
- `foregroundServiceType=dataSync`

After signing, require APK Signature Scheme v2/v3 verification with the existing SimpleReader signer certificate.

Relative to the canonical shipped v722 APK, non-signature differences remain restricted to `AndroidManifest.xml`, authorized `classes3.dex`, and added `classes5.dex`. APKs, keystores and passwords are never committed to the public repository.
