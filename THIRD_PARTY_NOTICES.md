# Third-party implementation references

This project adopts established architectural patterns from Mihon
(https://github.com/mihonapp/mihon), licensed under Apache License 2.0:

- validate/decode a backup completely before restoring;
- restore categories before library entries and remap database identifiers;
- merge restored entries using stable identifiers;
- rescan the user-selected local library as the source of current file locations.

The existing `SimpleReaderBackup` JSON schema version 1 remains unchanged.


## EPUB and CHM readers

- `documentnode/epub4j` 4.2.3 (Apache License 2.0) is used to parse EPUB 2/3 container, OPF manifest and spine reading order.
- `chimenchen/jchmlib` 0.5.4 (Apache License 2.0) is used to read CHM archives, topics trees and detected archive encodings.

## User-provided cover and reading-background assets

The v614 TXT/EPUB fallback covers and reader texture/material bitmaps were supplied by the user in
`多看阅读_默认封面_阅读页纹理_相关代码.zip`. The supplied package identifies the source as
Duokan Reader (`com.duokan.reader`) 8.4.20 / versionCode 804200000 and records the extraction as
Apktool 3.0.2 plus JADX 1.5.5. Only the requested TXT/EPUB fallback covers, tileable reader textures,
and material bitmaps are included. Ownership and redistribution rights remain with their respective
rights holders; this notice does not assert an open-source license for those image assets.

