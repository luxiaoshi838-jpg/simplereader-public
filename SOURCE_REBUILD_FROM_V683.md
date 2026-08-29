# SimpleReader source rebuild from V683

## Source-of-truth policy

V683 is the last explicit pre-700 public version with a complete ReaderActivity source lineage. Current `app/src/main` already contains many V683→current source migrations and remains the working source tree.

From this point forward, production behavior must be implemented in normal Kotlin/Java/XML/Room source. Production releases must not depend on:

- hard-coded DEX offsets;
- manual Dalvik register edits;
- direct mutation of `classes*.dex`;
- injected helper DEX files used only to bypass verifier/source issues;
- binary APK overlays as the implementation of an application feature.

Historical `tools/v726`…`tools/v745` files remain only as migration/history references. They are not production build inputs.

## Behavior parity to preserve

The source rebuild must preserve the accepted v722/current behavior except explicitly authorized changes, including:

- reader settings, auto reading, page-turn modes, reader backgrounds and day/night mode;
- reader safe bounds: status-bar bottom + one current-font character at top; navigation-bar top - three current-font characters at bottom; body TextView top/bottom padding 0;
- catalog recognition and page-cache identity/reuse;
- full-shelf background catalog + pagination cache and durable checkpoint recovery;
- one active shelf-cache execution at a time, with cooperative cancellation of superseded work;
- foreground keep-alive + bounded WakeLock behavior;
- crash-log access from the `日志` menu;
- existing shelf/import/group/search/progress behavior.

## Explicitly retained current UI changes

- Shelf selection mode top-right exit control is `×` rather than `取消`.
- TXT and EPUB add-bookmark control uses a transparent background with a white outline bookmark and centered white `+`.
- For the same book and the same resolved page, only one bookmark may exist; a second add attempt must not create another row.
- `数据导出 → 日志` opens the crash-log list directly. The historical operation-log UI is removed from the user path.

## Historical behavior that must NOT be reintroduced

- v732 coroutine register patch (verifier failure).
- discarded v734 routing change.
- abandoned v735–v737 no-catalog fast-prefilter experiments.
- v739–v741 operation-log UI experiments.
- v743/v744/v745 ReaderActivity DEX/register patches.

## Release gate

A source release is valid only when:

1. Gradle builds it from `app/src/main` without applying a DEX/APK binary patch.
2. Source tests cover the retained behavior above.
3. The release is zipaligned and v2/v3 signed with the existing signing identity.
4. The public repository contains source/tests/docs only; no APK, keystore, passwords or other secrets.
