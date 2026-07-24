# v14 final validation

STATUS: READY FOR FINAL BUILD

- TXT continuous-reading regression: passed without mojibake, replacement characters, gaps, overlaps, or premature end-of-book.
- EPUB real sample: 56 spine items, embedded cover extracted, 3,170,000+ readable characters, no empty chapters or replacement characters.
- CHM real sample: 231 MB archive produced zero readable chapters, so new CHM import and old CHM opening are deliberately disabled.
- EPUB structured cache is persistent, exportable, and restorable. TXT remains direct streaming from the original file and is not duplicated into a full-book cache.
- Debug tests, release tests, debug build, and release build must each finish within five minutes.

This commit triggers the final v14 build from the verified branch source.