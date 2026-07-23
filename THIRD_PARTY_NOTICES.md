# Third-party implementation references

This project adopts established architectural patterns from Mihon
(https://github.com/mihonapp/mihon), licensed under Apache License 2.0:

- validate/decode a backup completely before restoring;
- restore categories before library entries and remap database identifiers;
- merge restored entries using stable identifiers;
- rescan the user-selected local library as the source of current file locations.

The existing `SimpleReaderBackup` JSON schema version 1 remains unchanged.

## Structured document parsers

- `documentnode/epub4j` core, Apache License 2.0: EPUB 2/3 container, OPF manifest and spine parsing on Android.
- `chimenchen/jchmlib` v0.5.4, Apache License 2.0: CHM decompression, detected encoding, topics tree and title mapping.

The application keeps its own database and `SimpleReaderBackup` schema version 1; these parser changes do not alter backup fields.
