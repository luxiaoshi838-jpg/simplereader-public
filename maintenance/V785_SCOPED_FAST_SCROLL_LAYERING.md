# v785 — only fast-scroll handle is topmost

## User-reported regression

In v784, scrolling shelf/group content could visually cover the toolbar/action area (for example 数据导出 / 导入 / 编辑). The v784 fix disabled ancestor clipping so the 28×52dp fast-scroll pill would not be cut in half, but that also allowed ordinary RecyclerView content to bleed outside its intended list region.

## v785 fix

The z-order contract is now scoped:

- Only the custom fast-scroll handle remains topmost relative to book/group cells, via `ShelfFastScroller.onDrawOver()`.
- The shelf RecyclerView itself is not raised (`bringToFront`/`translationZ` are not used).
- Ordinary book/group rows remain clipped to the list region and cannot paint over the top toolbar/action rows.
- Main shelf and group shelf roots use normal clipping again.
- The list itself spans the physical screen width, while book/group content keeps its normal 16dp horizontal inset. This keeps the handle fully visible at the physical right edge without restoring a dedicated scrollbar gutter.
- The user-approved handle geometry remains 28dp × 52dp with three grip lines and the 1dp right-edge track.
- Reader code remains unrelated to shelf fast-scroll.
- Android 35 platform-scrollbar safety remains unchanged; no system scrollbar APIs are touched.

## Validation

GitHub Actions release workflow:

- Run: #165
- Run ID: `34465017754`
- Result: PASS
- v785 scoped fast-scroll layering gate: PASS
- inherited v783/v784 fast-scroll behavior gates: PASS
- inherited 52 stability gates: PASS
- full unit baseline: PASS
- Release build: PASS
- Android 35 real install + cold launch: PASS
- `V785_ANDROID35_RELEASE_COLD_LAUNCH_PASS`

Package/version:

- package: `com.simplereader.app`
- versionName: `785`
- versionCode: `2098000785`
- minSdk: 26
- targetSdk: 35

Official locally signed release:

- signing identity: SimpleReader Public V1
- certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature v2: true
- APK Signature v3: true
- signed APK SHA-256: `4683a42d2b50e1f927ecb98ee1b7c12ea2622bdbb78400c8928c5b612fffe784`
