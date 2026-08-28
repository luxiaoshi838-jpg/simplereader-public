# v739

v739 is based directly on v738 and keeps the v738 single-run shelf-cache cancellation logic unchanged.

## Fix

Android 16 crash log showed `MainActivity.showGroupActions()` invoking `SimpleReaderBackupDecoder.kt.show(Activity)` through reflection. If the helper UI throws, `Method.invoke()` wraps the real exception in `InvocationTargetException`, which previously escaped to the main thread and crashed the app.

v739 changes only the operation-log UI boundary:

- `kt.show(Activity)` now wraps the entire UI implementation in `catch (Throwable)`.
- The previous UI implementation is moved to `showUnsafe(Activity)`.
- The root reflection target exception is persisted to SharedPreferences keys `operation_log_ui_error` and `operation_log_ui_error_at` for later diagnosis.
- No exception from the operation-log helper is allowed to propagate back through `MainActivity.showGroupActions()`.

## Binary scope

Relative to v738, the production APK changes only:

- `AndroidManifest.xml` (version 738 -> 739)
- `classes5.dex` (operation-log helper)

`classes3.dex`, reader, pagination engine, cache worker routing and v738 single-run cancellation logic are unchanged.

APK binaries, signing keystores and passwords are intentionally not stored in the public repository.
