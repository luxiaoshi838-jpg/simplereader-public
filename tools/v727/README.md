# v727 binary overlay

v727 keeps the shipped v722 APK as the binary/UI baseline and fixes one remaining shelf-cache pagination reuse bug found in v726.

The defect was an identity mismatch, not a missing pagination pass: the visible v722 `ReaderActivity` constructs `PageCacheStore.CacheIdentity` with catalog rule version **111**, while the background `ShelfCacheWorker` still used stale version **15**. `PageCacheStore.loadPages()` requires exact `catalogRuleVersion` and `readerSettingsHash` matches, so a worker-generated cache could be saved and reloaded by the worker but still be rejected by the reader.

v727 therefore applies all authorized v726 overlay changes plus:

- `ShelfCacheWorker` cache identity rule version: `15 -> 111`, matching shipped v722 `ReaderActivity`.
- First-run fallback viewport height: `screen - status bar - navigation bar - 4 * current-font-px`, matching the v722 reader bounds (1 character above, 3 below). An exact remembered viewport still wins.
- Content internal top/bottom padding stays `0/0`.
- Worker success still requires immediate page-cache write/read verification with the same identity.

Production APKs, signing keys and passwords are deliberately not stored in the public repository. Build/sign from the canonical local v722 APK, apply this overlay, verify the APK-entry allowlist, then sign locally with the existing release key.

Expected non-signature APK differences from v722 are restricted to:

- `AndroidManifest.xml` (version only)
- `classes3.dex` (authorized overlay)
- new `classes5.dex` (bounded operation-log helper inherited from v726)

No reader UI/resource asset is changed by this fix.
