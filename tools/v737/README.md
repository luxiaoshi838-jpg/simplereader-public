# v737 binary overlay notes

Base APK: v736. `classes3.dex` is intentionally unchanged.

Changes are limited to `classes5.dex` helper code and version metadata:

- `NoCatalogWorker` prefilter no longer reimplements recognition validity with its own recognition.json regex checks.
- It calls the shipped app's `PageCacheStore.hasCurrentCatalog(context, bookId, currentFileName, currentFileSize)` as the authoritative check for: completed visible catalog, page-manifest existence, file-name match, and file-size match.
- If old database metadata does not prove the current cache, it resolves only current file metadata (name/length), without reading text or running recognition/pagination, then retries the shipped cache check.
- It additionally requires a page manifest for the same book with non-empty `pages` and exact `filePath` match, satisfying the user-required location condition.
- Prefilter diagnostics are persisted into the operation log: catalog/name/size mismatch; current file metadata unavailable; page/location mismatch; unexpected probe error.
- Books satisfying all five conditions are removed from the target list before the processing loop.

Packaging: official Android build-tools 37.0.0 `zipalign` -> `apksigner`; v2/v3 signatures verified. No APK, keystore, password, or signing material is committed publicly.
