# v743 UI icon patch

Baseline: shipped v742 APK.

Authorized changes only:

1. Shelf selection mode: replace the `取消` text shown in the top-right `moreButton` with the existing `×` glyph. Only the two selection-mode assignments are patched; other Cancel buttons remain unchanged.
2. Reader bookmark action (TXT ReaderActivity and ReadiumEpubActivity): remove the old single-character label and orange filled circular `GradientDrawable`; use a transparent action view with a white outline bookmark plus icon.
3. Version: 742 -> 743.

Implementation details:

- `classes3.dex`:
  - MainActivity.enterShelfSelectionMode: string index `取消` -> existing `×`.
  - MainActivity.updateShelfSelectionButtons: same replacement.
  - ReaderActivity / ReadiumEpubActivity: action TextView text -> empty; old GradientDrawable construction replaced with `setBackgroundResource(0x7f08009c)`.
- `res/G0.xml` (`drawable/exo_ic_audiotrack`, unused by app code): repurposed as the white bookmark-plus vector. `resources.arsc` is unchanged.
- Vector paths on a 312-ish viewport:
  - bookmark: `M80,45L232,45L232,270L156,220L80,270Z`
  - plus vertical: `M156,100L156,170`
  - plus horizontal: `M120,135L192,135`
  - stroke white, width 18, transparent fill.

Final non-META diff versus v742 is exactly:

- `AndroidManifest.xml`
- `classes3.dex`
- `res/G0.xml`

No APK, keystore, password, or signing material belongs in the public repository.
