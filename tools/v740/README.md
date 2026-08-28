# v740 operation-log UI compatibility fix

Base: v739 production APK.

Changes are intentionally limited to the operation-log helper in `classes5.dex` plus version metadata.

## Problem fixed
v739 wrapped `kt.show(Activity)` in a catch-all to stop an `InvocationTargetException` from crashing the main thread. If older `operation_history_v726` SharedPreferences contained a key whose stored type no longer matched `getInt` / `getString`, the UI threw (typically `ClassCastException`) and v739 silently swallowed it. Result: tapping Operation Log returned to the shelf with no visible response.

## v740 behavior
- `count`, `title_*`, and `body_*` are read through tolerant helpers that try compatible stored types.
- A malformed historical item cannot prevent the whole log list from opening.
- The operation-log list still has no Copy button.
- Copy remains only in the clicked detail dialog.
- If the list UI still fails, v740 shows `操作日志打开失败` with the root exception instead of silently doing nothing.
- If only a detail fails, v740 shows `日志详情打开失败` with the root exception.
- v738/v739 single-worker cache cancellation behavior is retained unchanged.

## Binary scope
Relative to v739, only `AndroidManifest.xml` and `classes5.dex` change. `classes3.dex` is byte-identical.

Production packaging order remains: unsigned overlay -> `zipalign` -> `apksigner` v2/v3 -> verify signature + zip alignment. APKs and signing secrets are never committed to the public repository.
