# v735

v735 is based directly on the verified v733 APK lineage. The v734 all-books enqueue change is intentionally **not** part of v735.

## Authorized changes

Only the `books_without_catalog` worker helper is changed. `ShelfCacheWorker.doWork()` and the v733 `classes3.dex` are left byte-identical.

A shelf book is excluded from **全书架无目录书籍缓存** only when all five user-defined conditions are proven:

1. A completed visible catalog exists (`recognition.json`: `completed=true`, `chapterCount>0`).
2. Real page information exists (a `pages.json` or `pages-*.json` manifest with a non-empty `pages` array).
3. The cached file name matches the current shelf record.
4. The cached file size matches the current shelf record.
5. The page manifest `filePath` matches the current shelf record's file location/path.

If any one of these conditions is missing or inconsistent, the book remains in the target set and is processed again. The prefilter deliberately does **not** use catalog rule version, reader settings hash, lastModified, or reflective reader-load checks.

WorkManager progress is now published through a direct `setProgressAsync(Data)` call using the existing keys `current`, `total`, `title`, `completed`, `skipped`, and `failed`, and outputData uses the same summary keys. This avoids the previous reflective progress update silently leaving the status title empty/null.

Failure logging remains: each failed target records book title, failure stage, and the original exception reason.

## Production overlay

The production helper change is captured in `NoCatalogWorker_v733_to_v735.patch`. APKs, keystores, passwords, and signing material are not stored in this public repository.

Validated production APK properties locally:

- package: `com.simplereader.app`
- versionCode/versionName: `2098000735` / `735`
- Android official `zipalign`: successful
- Android official `apksigner`: v2=true, v3=true
- signer certificate SHA-256 unchanged from the v722/v731 lineage
- compared with v733 excluding `META-INF`: only `AndroidManifest.xml` and `classes5.dex` changed; 1780 entries remain byte-identical, including `classes3.dex`.
