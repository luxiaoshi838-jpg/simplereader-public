#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import os
import subprocess

B = Path('app/build.gradle.kts').read_text(encoding='utf-8')
R = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text(encoding='utf-8')
V = Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text(encoding='utf-8')
P = Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt').read_text(encoding='utf-8')
S = Path('app/src/main/java/com/simplereader/app/ui/ReaderSelectionActions.kt').read_text(encoding='utf-8')
version_code = os.environ.get('SIMPLE_READER_VERSION_CODE', '2098000770')
version_name = os.environ.get('SIMPLE_READER_VERSION_NAME', '770')

assert version_code in B and f'"{version_name}"' in B

# The switch must update current on-screen vertical TextViews synchronously.
for token in [
    'fun applyTextSelectionStateImmediately(recyclerView: RecyclerView)',
    'for (index in 0 until recyclerView.childCount)',
    'holder.textView.setTextIsSelectable(enabled)',
    'holder.textView.isLongClickable = enabled',
    'activity.bindSelectionActions(holder.textView, pages[position].startOffset)',
]:
    assert token in V, token

apply_block = R[R.index('    private fun applyTextSelectionSetting() {'):R.index('    private fun updateSettingsLabels() {')]
assert 'continuousTextView.setTextIsSelectable(textSelectionEnabled)' in apply_block
assert 'pagedReaderView.setTextSelectionEnabled(textSelectionEnabled)' in apply_block
assert 'verticalAdapter?.applyTextSelectionStateImmediately(recycler)' in apply_block
assert 'verticalAdapter?.refresh()' not in apply_block, 'selection toggle still waits for RecyclerView rebind'

immediate = V[V.index('    fun applyTextSelectionStateImmediately'):V.index('    fun release()', V.index('    fun applyTextSelectionStateImmediately'))]
assert 'notifyDataSetChanged()' not in immediate
assert 'post {' not in immediate and 'postDelayed' not in immediate

# Newly recycled rows must still inherit the setting normally.
for token in [
    'view.setTextIsSelectable(activity.isTextSelectionEnabled())',
    'view.isLongClickable = activity.isTextSelectionEnabled()',
    'activity.bindSelectionActions(view, pages[position].startOffset)',
]:
    assert token in V, token

# V769 touch routing and final selection menu are compatibility locks.
base_p = subprocess.check_output([
    'git','show','origin/source-v769:app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt'
], text=True)
base_s = subprocess.check_output([
    'git','show','origin/source-v769:app/src/main/java/com/simplereader/app/ui/ReaderSelectionActions.kt'
], text=True)
assert P == base_p, 'PagedReaderView touch routing changed from verified V769 baseline'
assert S == base_s, 'selected-text copy/translate/search actions changed from V769 baseline'
for label in ['复制','翻译','搜索']:
    assert f'"{label}"' in S
for forbidden in ['朗读选中','笔记','划线','摘录','分享']:
    assert f'"{forbidden}"' not in S

# Center tap / search / V761 zero-reset protections stay present.
for token in [
    'pagedReaderView.onCenterTap = { setReaderChromeVisible(!chromeVisible) }',
    'MENU_SEARCH -> { showContentSearch(); true }',
    'ReaderSearchSheet.show(',
    'zeroTeleport = index == 0 && baseline >= 4 && dy >= 0',
    'vertical_idle_recovered_zero',
]:
    assert token in R, token

print(f'v{version_name} instant selected-text activation gates: PASS')
PY
