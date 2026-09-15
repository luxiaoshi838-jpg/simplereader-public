# V788 Shelf Layout Switch Crash Fix

- Baseline: final validated v787 commit `c6576ed83729987ac567d330a971c129df216082`.
- User reproduction: v787 crashes immediately after tapping the shelf list/grid control.
- The supplied Android exit record identifies a Java crash but does not contain the Java stack trace, so v788 does not claim a single proven exception class.
- v788 no longer replaces RecyclerView LayoutManager while the shelf is live. Both modes keep one 3-column GridLayoutManager; grid items span 1 column, list-mode items span all 3 columns and bind the existing full-width list cards.
- Switching is serialized, stops active scrolling, posts the transition to the RecyclerView event queue, invalidates span caches, clears recycled holders, rebinds items, and requests layout.
- Button semantics: current grid = 宫格; current list = 列表. Long-press selection continues to temporarily show 操作.
- Version: 788 / 2098000788.
