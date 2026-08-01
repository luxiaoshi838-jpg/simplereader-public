# Uploaded Duokan APK reading architecture notes

Source inspected locally: the user-uploaded `duokan(1).apk`.

The APK method table and DEX bodies show the following relevant structure:

- `ReadingView` is a persistent `FrameLayout` host with one reading session.
- `PageAnchor` is the logical document position used across rendering modes.
- `PagesController` owns page preparation and animation state separately.
- `FlowPagesView` supports `SCROLL`, `OVERLAP`, and `FADE_IN` effects.
- Horizontal and vertical movement share `FlowPagesView`; orientation is changed through `PageLayout`.
- `CurlPageView` is loaded separately for simulation, while retaining the same session/page provider.
- The mode setter changes `FlipEffect` and `PageLayout`; it does not reload a chapter, clear page data, or replace the reading session.

SimpleReader v588 follows the same architectural boundary without copying Duokan assets or proprietary implementation:

- one persistent page host;
- one page anchor/window/cache;
- mode-independent content and page numbering;
- renderer-only mode switching;
- one cancelable animation state machine;
- adjacent-page prefetch that never rebinds the visible page.
