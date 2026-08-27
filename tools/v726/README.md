# SimpleReader v726 binary-baseline release

v726 uses the confirmed-working v722 APK as the only UI/feature baseline. It intentionally does **not** rebuild the application from the current public Gradle source because that source has known reader-page drift from the shipped v722 binary.

Allowed binary differences from v722:

- AndroidManifest versionCode/versionName -> 2098000726 / 726.
- `classes3.dex`: bounded operation-log bridge, legacy unbounded operation-log sink disabled, WorkInfo non-empty branch fix, current-title register fix, operation-log UI entry bridge.
- new `classes5.dex`: bounded (max 10) directory-cache task history list/detail UI.

Everything else is required to remain v722-identical. In particular the v722 ReaderActivity viewport/insets, reader settings, PageEngine/ReaderCacheProfile behavior and ShelfCacheWorker pagination pipeline are not rebuilt or replaced.

Release validation must compare the following critical methods byte-for-byte against v722:

- `ReaderActivity.applyReaderContentPadding()`
- `ReaderActivity.paginateAndDisplay()`
- `ReaderActivity.createLayoutSettings()`
- `ShelfCacheWorker.doWork()`
- `ShelfCacheWorker.reportProgressNow()`

The signed release must use the existing `simplereader-public-v1` signing identity. Signing secrets are never stored in this public repository.
