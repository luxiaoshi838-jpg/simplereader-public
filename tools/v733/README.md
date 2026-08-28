# v733 binary overlay

v733 fixes the Android 16 `VerifyError` introduced by v732. The v732 failure came from manually inserting instructions into the Kotlin coroutine state machine for `ShelfCacheWorker.doWork()`; ART rejected the resulting register type merge.

## Binary strategy

- Start from the installable v731 APK.
- Keep `ShelfCacheWorker.doWork()` byte-for-byte identical to v731. Its method-code SHA-256 is `f09accdc3003d2e2f0cbf5b3f851f721c8699c09816ce57bd2a48189b0cdc6b6`.
- Patch only the ordinary non-coroutine `ShelfCacheWorker.Companion.enqueue(Context,String)` method. It reflectively calls `SimpleReaderBackupDecoder.kt.enqueue(Context,String)` in `classes5.dex`.
- `all_books` continues to enqueue the shipped v731 `ShelfCacheWorker`.
- `books_without_catalog` enqueues the D8-compiled `SimpleReaderBackupDecoder.NoCatalogWorker`. No hand-written coroutine bytecode is used.

## No-catalog behavior

`NoCatalogWorker` scans the shelf first and excludes a book only when the shipped reader APIs confirm both a current visible catalog and a page cache that actually reloads under the current reader settings/identity. Already-successful books therefore do not enter the target total and are not counted as skipped.

Every target book is closed exactly once as success, failure, or skip. Failure detail records book title, processing stage, and the original exception text. The operation log reports scanned shelf count, excluded reusable count, target total, per-outcome totals, and any unclassified difference.

## Packaging gate

Production order is: modify -> `zipalign` -> `apksigner` -> verify. Delivery requires official `zipalign -c -v 4` success and `apksigner verify --verbose --print-certs` with v2/v3 true and the existing certificate SHA-256 `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`.

Public repository policy remains unchanged: no APK, keystore, password, or signing material is stored here.
