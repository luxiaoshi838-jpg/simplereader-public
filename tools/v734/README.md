# v734 binary overlay

v734 fixes the v733 regression where the full-shelf cache path was routed through a reflective enqueue bridge and could remain at `0 / N` with a null current title.

## Full-shelf cache

The shipped v731 `ShelfCacheWorker.doWork()` is restored byte-for-byte. The `all_books` execution path inside `ShelfCacheWorker.Companion.enqueue()` is also retained byte-for-byte from v731.

Only the `books_without_catalog` branch is diverted to the dedicated D8-compiled `SimpleReaderBackupDecoder.NoCatalogWorker` through the existing `LogKt.logd(Context,String)` bridge. No coroutine method is binary-patched.

## No-catalog cache

The dedicated worker keeps the v733 behavior:

- scan the shelf first;
- exclude books only when the current catalog and current reader-identity page cache are genuinely reusable;
- excluded successful books do not enter target total and do not count as skipped;
- process only the remaining target books;
- record each target as success/failure/skip;
- failures include book title, failure stage, and raw exception reason;
- final log exposes unclassified count if classification does not balance.

## Delivery guards

Production order: modify -> zipalign -> apksigner.

Required checks:

- `zipalign -c -v 4`: Verification successful;
- APK Signature Scheme v2: true;
- APK Signature Scheme v3: true;
- release certificate unchanged;
- `ShelfCacheWorker.doWork()` code item identical to v731;
- all-books enqueue execution ranges identical to v731.

Expected non-signature differences from v731 are only `AndroidManifest.xml`, `classes3.dex`, and `classes5.dex`. APK and signing materials are not stored in the public repository.
