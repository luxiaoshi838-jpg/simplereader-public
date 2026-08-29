# v744

Purpose: fix the Android 16 `VerifyError` introduced by the v743 bookmark-button DEX patch while preserving the two approved UI changes.

Changes relative to v742:

- Shelf selection-mode top-right text: `取消` -> `×` (only the two selection-mode call sites).
- TXT and EPUB reader bookmark action text is cleared and the action view uses the existing repurposed white outline bookmark-plus vector resource.
- Critical verifier fix: after replacing the original orange `GradientDrawable` construction with `View.setBackgroundResource(0x7f08009c)`, the patch explicitly restores `const/16 v5, 40` before the later `dp(v5)` calls. v743 incorrectly NOPed this register initialization, causing Android 16 to reject `ReaderActivity.onCreateOptionsMenu()`.
- Version bumped to 744.

Validation performed on the final signed APK:

- `dexdump` confirms both `ReaderActivity.onCreateOptionsMenu()` and `ReadiumEpubActivity.onCreateOptionsMenu()` execute `const/16 v5, 40` before the `dp(v5)` calls.
- `zipalign -c -v 4`: successful.
- `apksigner verify --verbose --print-certs`: v2=true, v3=true, original certificate unchanged.
- Non-META diff versus v742: only `AndroidManifest.xml`, `classes3.dex`, and `res/G0.xml`; 1779 non-META entries unchanged.

No APK, keystore, password, or signing secret is stored in this repository.
