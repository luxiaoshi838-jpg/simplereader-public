# SimpleReader v726 binary-baseline release

v726 uses the actual shipped v722 APK as the only UI/feature baseline. The exact accepted baseline is:

- package: `com.simplereader.app`
- versionCode/versionName: `2098000722` / `722`
- APK SHA-256: `a1403b4eeda62ac04c4d9303e32d5a12ff3c3f1a96222c450eaf71bdf489580d`
- signer certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

A same-named v722 APK with any other SHA-256 is not an accepted baseline.

## Release policy

v726 intentionally does **not** rebuild unrelated application pages from the current Gradle source, because that source has known behavioral drift from the shipped v722 binary. Except for expressly authorized v726 changes, all APK content must remain v722-identical.

Allowed binary differences from v722 are limited to:

- `AndroidManifest.xml`: versionCode/versionName -> `2098000726` / `726`.
- `classes3.dex`: bounded operation-log bridge, legacy unbounded operation-log sink disabled, shelf-cache WorkInfo/status fixes, operation-log UI bridge, and the authorized background pagination-profile fix described below.
- new `classes5.dex`: bounded (max 10) directory-cache task history list/detail UI.
- `META-INF/*`: expected signing metadata differences after re-signing.

No other APK entry may be added, removed or content-changed.

## Directory cache contract

“目录缓存/识别目录” is a real background WorkManager job, not an Activity-owned task. An unfinished job may continue after leaving the shelf, entering the reader, locking the screen, switching apps, process recreation, and relaunch. Relaunch reconnects the progress UI; it does not cancel valid pending work.

A book is successful only when the cache pipeline has completed catalog recognition, full pagination, persistent page-cache save and later page-cache reuse by the reader. A cached book must not be treated as successful if opening it still requires a new pagination pass.

### v726 pagination reuse correction

The shipped v722 reader applies its vertical reading guards to `readerViewport`, while the body TextView itself has top/bottom padding `0`. Therefore `ReaderActivity.createLayoutSettings()` produces a page-cache identity with internal content top/bottom padding `0`.

The shipped v722 background worker, however, reaches `ReaderCacheProfile.createSettings()` through defaults that used `24dp` for the internal top/bottom content padding. That makes the worker's `settingsHash` differ from the visible reader even after the worker has already paginated and saved pages.

v726 keeps the v722 reader/UI unchanged and changes only that background/default pagination-profile literal from `24dp` to `0`, so the background-generated page cache can use the same cache identity as the v722 reader. The deterministic DEX change is implemented in `tools/v726/patch_v726.py`.

## Reader baseline contract

The v722 reader is retained unchanged, including its `readerViewport`, auto-reading and reader settings. Its accepted bounds are:

- upper reading bound: status-bar bottom + one current-font character height;
- lower reading bound: navigation-bar top - three current-font character heights;
- body TextView top/bottom padding: zero.

All reader pages/settings/features not expressly authorized for v726 remain v722 behavior.

## Required binary validation

`verify_apk_baseline.py` must be run against the exact v722 APK and the candidate v726 APK. It rejects a wrong baseline hash and rejects any unapproved entry difference.

The following reader methods remain byte-identical to v722:

- `ReaderActivity.applyReaderContentPadding()`
- `ReaderActivity.paginateAndDisplay()`
- `ReaderActivity.createLayoutSettings()`

The background worker execution architecture remains the v722 WorkManager/CoroutineWorker path. `classes3.dex` is allowed to differ only for the explicitly documented v726 patches, including the `ReaderCacheProfile` default-padding correction.

The signed release must use the existing `simplereader-public-v1` signing identity. Signing secrets are never stored in this public repository.
