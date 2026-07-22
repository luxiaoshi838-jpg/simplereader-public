# Third-party implementation references

This project adopts established architectural patterns from Mihon
(https://github.com/mihonapp/mihon), licensed under Apache License 2.0:

- validate/decode a backup completely before restoring;
- restore categories before library entries and remap database identifiers;
- merge restored entries using stable identifiers;
- rescan the user-selected local library as the source of current file locations.

The existing `SimpleReaderBackup` JSON schema version 1 remains unchanged.
