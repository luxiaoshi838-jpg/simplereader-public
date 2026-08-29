# v745 UI icon verifier-safe patch

Baseline: shipped v742 APK.

Authorized UI changes only:

1. Shelf selection mode: top-right `取消` -> existing `×` glyph.
2. TXT ReaderActivity and ReadiumEpubActivity bookmark action: old single-character label removed; old orange filled circle replaced by transparent white outline bookmark-plus vector.
3. Version 742 -> 745.

Critical verifier fix compared with v743/v744:

- Do not overwrite the whole GradientDrawable block.
- Preserve ReaderActivity `const/4 v2, 1` at code unit 0x0050 because v2 is the method's boolean return register.
- Preserve ReadiumEpubActivity `const/4 v4, 1` from code unit 0x0021 because v4 is the method's boolean return register.
- Preserve `const/16 v5, 40` at code unit 0x0058 because the following layout-size code still consumes v5.
- Replace only the obsolete shape/color calls with `View.setBackgroundResource(0x7f08009c)` using a non-return temporary register; all unused remaining units become NOPs.

The vector resource remains transparent fill with white bookmark outline and centered white plus sign.

Final non-META diff versus v742:

- AndroidManifest.xml
- classes3.dex
- res/G0.xml

No APK, keystore, password or signing material belongs in the public repository.
