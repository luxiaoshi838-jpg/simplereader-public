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
- `classes3.dex`: bounded operation-log bridge, legacy unbounded operation-log sink disabled, shelf-cache WorkInfo/status fixes and operation-log UI bridge.
- new `classes5.dex`: bounded (max 10) directory-cache task history list/detail UI.
- `META-INF/*`: expected signing metadata differences after re-signing.

No other APK entry may be added, removed or content-changed.

## Directory cache contract

“目录缓存/识别目录” is a real background WorkManager job, not an Activity-owned task. An unfinished job may continue after leaving the shelf, entering the reader, locking the screen, switching apps, process recreation, and relaunch. Relaunch reconnects the progress UI; it does not cancel valid pending work.

A book is successful only when the cache pipeline has completed the behavior inherited from v722: catalog recognition, full pagination, persistent page-cache save and later page-cache reuse by the reader. A cached book must not be treated as successful if opening it still requires a new pagination pass.

The v722 `ShelfCacheWorker` and reader pagination/cache-identity methods therefore remain byte-identical in v726. They are not replaced with the v725 source-built implementations.

## Reader baseline contract

The v722 reader is retained unchanged, including its `readerViewport` behavior and reader settings. Its accepted bounds are:

- upper reading bound: status-bar bottom + one current-font character height;
- lower reading bound: navigation-bar top - three current-font character heights;
- body TextView top/bottom padding: zero.

All reader pages/settings/features not expressly authorized for v726 remain v722 behavior.

## Required binary validation

`verify_apk_baseline.py` must be run against the exact v722 APK and the candidate v726 APK. It rejects a wrong baseline hash and rejects any unapproved entry difference.

The following critical methods must additionally be byte-identical to v722:

- `ReaderActivity.applyReaderContentPadding()`
- `ReaderActivity.paginateAndDisplay()`
- `ReaderActivity.createLayoutSettings()`
- `ShelfCacheWorker.doWork()`
- `ShelfCacheWorker.reportProgressNow()`

The signed release must use the existing `simplereader-public-v1` signing identity. Signing secrets are never stored in this public repository.
