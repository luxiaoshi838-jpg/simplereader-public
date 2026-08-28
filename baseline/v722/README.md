# SimpleReader v722 parsed baseline

This directory is the canonical **text-only parsed baseline** for the confirmed shipped SimpleReader v722 release.

The APK itself is deliberately **not stored in this public repository**. `.apk` files are forbidden in Git history. The canonical local input is identified only by cryptographic hashes.

## Canonical identity

- package: `com.simplereader.app`
- versionName: `722`
- versionCode: `2098000722`
- canonical APK SHA-256: `a1403b4eeda62ac04c4d9303e32d5a12ff3c3f1a96222c450eaf71bdf489580d`
- signer certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

## Scope of the v722 lock

Except for explicitly authorized changes, **all pages, features, behaviors and resources must remain v722-equivalent**. This is not limited to reader top/bottom bounds. It includes, among other things:

- automatic reading, automatic vertical scrolling and auto-read speed;
- horizontal/vertical page turning and page-turn mode;
- reader settings, fonts, backgrounds, day/night appearance and saved preferences;
- reader viewport, system-bar bounds and content padding;
- catalog/chapter display and chapter navigation;
- pagination, page-cache identity, cache persistence and cache reuse;
- shelf, grouping, import, search, progress, bookmarks and all other existing v722 UI/interaction behavior.

Current authorized product changes are restricted to:

1. the shelf upper-left status box behavior;
2. the operation-log behavior/UI.

The accepted unfinished-work behavior may reconnect/resume a valid pending directory-cache task after relaunch.

Directory cache success means **catalog recognition + full pagination + persistent reusable page cache**. Merely recognizing a catalog is not success; opening a successfully cached book must not require another pagination pass for the same reader layout/cache identity.

## Parsed verification baseline

`baseline.json` records the DEX identities, counts, and Merkle roots covering the full parsed package inventory:

- 1,784 APK entries;
- 623 `com.simplereader.app` classes;
- 4,078 `com.simplereader.app` methods.

`critical-reader-methods.json` records direct v722 bytecode hashes for high-risk behavior such as auto-reading, turning, settings, appearance, pagination and shelf-cache execution.

`tools/parse-v722-baseline.py` reproduces the full entry/class/method inventory and Merkle roots from a local canonical v722 APK without copying the APK into the repository.

Any future change that is not on the authorized list must be treated as a regression until proven otherwise against this baseline.
