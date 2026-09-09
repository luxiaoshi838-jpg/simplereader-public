#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re

B = Path('app/build.gradle.kts').read_text(encoding='utf-8')
R = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
P = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt').read_text(encoding='utf-8')
S = Path('app/src/main/java/com/simplereader/app/ui/ReaderSelectionActions.kt').read_text(encoding='utf-8')
O = Path('app/src/main/java/com/simplereader/app/ui/ReaderTouchObservingViews.kt').read_text(encoding='utf-8')
X = Path('app/src/main/res/layout/activity_reader.xml').read_text(encoding='utf-8')
T = Path('app/src/main/java/com/simplereader/app/parser/TxtParser.kt').read_text(encoding='utf-8')
D = Path('app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt').read_text(encoding='utf-8')

assert '2098000769' in B and '"769"' in B
assert 'CATALOG_RULE_VERSION = 115' in T
assert 'RULE_VERSION = 115' in D
assert '(?:章|节|回|卷|篇)' in D

# Final selected-text action set remains exactly copy / translate / search.
for label in ['复制', '翻译', '搜索']:
    assert f'"{label}"' in S, label
for forbidden in ['朗读选中', '笔记', '划线', '摘录', '分享']:
    assert f'"{forbidden}"' not in S, forbidden
for token in ['ACTION_COPY', 'ACTION_TRANSLATE', 'ACTION_SEARCH', 'menu.clear()', 'setCustomSelectionActionModeCallback']:
    assert token in S, token

# Horizontal selectable TextView must no longer steal reader gestures.
for token in [
    'override fun dispatchTouchEvent(event: MotionEvent): Boolean',
    'if (textSelectionEnabled) handleReaderTouchEvent(event)',
    'if (textSelectionEnabled) return true',
    'private fun handleReaderTouchEvent(event: MotionEvent): Boolean',
    'selection may now be dragging a word/handle',
]:
    assert token in P, token
move = P[P.index('MotionEvent.ACTION_MOVE ->'):P.index('MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->')]
assert 'if (longPressTriggered)' in move
assert 'longPressTriggered = false' not in move, 'long press must stay navigation-suppressed until UP/CANCEL'
assert 'else -> onCenterTap?.invoke()' in P, 'center tap lost'
assert 'event.x < width * 0.33f -> turn(-1)' in P
assert 'event.x > width * 0.67f -> turn(1)' in P

# Vertical and fallback scroll containers observe dispatch before selectable children consume it.
for token in [
    'class ReaderTouchObservingRecyclerView',
    'class ReaderTouchObservingNestedScrollView',
    'touchObserver?.invoke(event)',
    'return super.dispatchTouchEvent(event)',
]:
    assert token in O, token
assert '<com.simplereader.app.ui.ReaderTouchObservingNestedScrollView' in X
assert '</com.simplereader.app.ui.ReaderTouchObservingNestedScrollView>' in X
for token in [
    'val recycler = ReaderTouchObservingRecyclerView(this).apply',
    'recycler.touchObserver = { event -> verticalHandleTouch(event) }',
    'private fun handleContinuousReaderTouch(event: MotionEvent)',
    'observingScroll.touchObserver = { event -> handleContinuousReaderTouch(event) }',
]:
    assert token in R, token
assert 'recycler.setOnTouchListener(VerticalTouchListener(this))' not in R

# Existing center-tap behavior itself must remain unchanged.
assert 'pagedReaderView.onCenterTap = { setReaderChromeVisible(!chromeVisible) }' in R
assert 'setReaderChromeVisible(!chromeVisible)' in R

# Search and V761 zero-reset protection remain present.
for token in [
    'MENU_SEARCH -> { showContentSearch(); true }',
    'ReaderSearchSheet.show(',
    'verticalShouldSuppressReportedIndex(lastReportedIndex, index, dy)',
    'zeroTeleport = index == 0 && baseline >= 4 && dy >= 0',
    'vertical_idle_recovered_zero',
]:
    assert token in R, token

print('v769 selection + reader touch coexistence gates: PASS')
PY
