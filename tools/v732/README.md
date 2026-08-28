# v732 binary overlay

v732 is a production binary overlay based on the installable v731 APK. The shipped v722/v731 binary remains the UI/reader baseline; active `app/src` is not claimed to be a byte-for-byte reconstruction of v722.

## Authorized changes

### 1. Precise `全书架无目录书籍缓存` target selection

The worker still scans the full shelf, but before its checkpoint/processing loop the overlay calls `SimpleReaderBackupDecoder.kt.record(Object)` with the raw book list.

For `books_without_catalog`, the helper excludes a book **only when the shipped runtime itself proves the cache is actually reusable**:

1. current recognition/catalog matches current file name and size and has a visible catalog;
2. `ReaderDocumentLoader.load(..., false)` succeeds;
3. current `ReaderCacheProfile` settings are used to compute the real reader settings hash;
4. the same `PageCacheStore.CacheIdentity` fields used by the reader are reconstructed (book id/path, source size/mtime, current settings hash, text fingerprint, catalog rule version);
5. `PageCacheStore.loadPages(...)` succeeds and returns non-empty pages.

Therefore already-successful books are removed from the target list entirely: they do **not** count in target total, progress, success, failure, or skipped. If 5716 books are scanned and only 3 are not reusable, the worker total is 3.

Any probe uncertainty keeps the book in the target list rather than hiding a potentially uncached book.

### 2. Detailed, bounded operation log

Both `全书架目录缓存` and `全书架无目录书籍缓存` use the same operation-history UI, with exact mode labels. One user-triggered task maps to one log entry, bounded to 10 entries.

The detail view records scan count, target count, excluded reusable books, success/failure/skip counts, and checks classification conservation. If `success + failure + skipped != target`, the log explicitly shows `未归类` instead of silently reporting a clean completion.

Failures are written immediately against the pending book and include:

- book title;
- inferred failure stage (file read / catalog-pagination processing / page-cache write-verification);
- original `Throwable.toString()` reason (bounded in length).

Skips also retain book title and skip reason. The list page has no copy button; copy remains only in the clicked detail dialog.

### 3. Android 16 detail-dialog compatibility retained

The helper uses the real platform overload `LinearLayout.addView(View)`. It must never encode the nonexistent `LinearLayout.addView(View, LinearLayout.LayoutParams)` virtual signature which caused the v729 Android 16 crash.

## Production APK build order

1. patch v731 binary;
2. replace only authorized DEX/manifest content;
3. run official Android `zipalign`;
4. sign locally with official `apksigner` using v2 + v3;
5. verify with official `zipalign -c` and `apksigner verify --verbose --print-certs`.

Expected non-signature changes from v731 are exactly:

- `AndroidManifest.xml` — version 731 -> 732;
- `classes3.dex` — worker prefilter bridge and per-book failure/skip result bridge;
- `classes5.dex` — exact v732 helper/log implementation.

All other non-signature APK entries must remain byte-identical to v731. APKs, keystores, passwords, and signing material are never stored in the public repository.
