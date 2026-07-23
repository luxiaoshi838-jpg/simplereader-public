# v14 real-file regression scope

Validated against the user-provided samples:

- `阿里布达年代祭.epub`: contains an OPF-declared `cover.jpg`, a cover title page, 56 spine items, and clean UTF-8 chapter markup.
- `绯梦之都完本.chm`: contains extensive GBK/GB18030 Chinese chapter metadata and content; it is not a one-chapter or nearly empty archive.

Required fixes:

1. Render actual EPUB cover images with a text-cover fallback.
2. Decode CHM entry bytes from declared/detected charset with Chinese fallbacks instead of trusting one String conversion.
3. Preserve adjacent structured chapters so vertical scrolling and page turns cross chapter boundaries in both directions.
4. Fix structured previous-page position and whole-book progress seeking.
5. Keep package name, database schema, backup schema, and signing identity unchanged.
