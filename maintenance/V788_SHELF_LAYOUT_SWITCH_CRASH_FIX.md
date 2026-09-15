# V788 Shelf Layout Switch Crash Fix

- Baseline: final validated v787 commit `c6576ed83729987ac567d330a971c129df216082`.
- User reproduction: v787 crashes immediately after tapping the shelf list/grid control.
- The supplied Android exit record identifies a Java crash but does not contain the Java stack trace, so v788 does not claim a single proven exception class.
- v788 no longer replaces RecyclerView LayoutManager while the shelf is live. Both modes keep one 3-column GridLayoutManager; grid items span 1 column, list-mode items span all 3 columns and bind the existing full-width list cards.
- Switching is serialized, stops active scrolling, posts the transition to the RecyclerView event queue, invalidates span caches, clears recycled holders, rebinds items, and requests layout.
- Button semantics: current grid = 宫格; current list = 列表. Long-press selection continues to temporarily show 操作.
- Version: 788 / 2098000788.

## Final release verification — 2026-09-15

- GitHub Actions validation run `34964644861`: PASS. v787/v788 shelf regression tests, inherited known-failure baseline check, Debug build, Release build, package/version verification and unsigned artifact export all passed.
- Final APK was signed locally from the Google Drive `签名文件/简阅签名文件.zip` source. No private key, password or signed APK was committed to this public repository.
- Package: `com.simplereader.app`.
- Version: `versionName=788`, `versionCode=2098000788`.
- APK Signature Scheme v2: PASS.
- APK Signature Scheme v3: PASS.
- Signer certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`.
- v787 ↔ v788 signer identity: PASS; the same SimpleReader Public V1 certificate is used for upgrade compatibility.
- ZIP/APK compressed-data integrity: PASS.
- Final signed APK SHA-256: `fad57edf34dbb082d390f3a9957b359e7ad6284be9aaa5c31bda220978f6ca36`.
- Release guard: an unsigned APK is a build intermediate only and must never be presented to users as an installable/update package. User-facing releases must use the fixed SimpleReader Public V1 signer and pass package/version/signature verification first.
