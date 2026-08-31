#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v752-24-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }

# 25: selecting books inside a group restores an operation menu; selection chrome is not delete-only.
G=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
grep -Fq 'actionButton.text = "操作"' "$G" || fail 25 'group-book selection primary action is not 操作'
grep -Fq 'cancelButton.text = "×"' "$G" || fail 25 'group-book selection cancel is not ×'
grep -Fq 'private fun showSelectionActions()' "$G" || fail 25 'group-book operation menu missing'
for label in '全选' '取消全选' '移入分组' '移出分组' '删除书架'; do grep -Fq "add(\"$label\")" "$G" || fail 25 "group-book operation missing $label"; done
! grep -Fq 'setOnClickListener { confirmDeleteSelection() }' "$G" || fail 25 'group-book top action still hardwired to delete'
pass 25 'group-book selection restores operations and × cancel instead of delete-only mode'

# 26: main shelf group-selection state is asymmetric by count: one group => 操作, two+ groups => 删除.
M=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
grep -Fq 'groupCount >= 2 && bookCount == 0 -> "删除"' "$M" || fail 26 'multi-group selection does not show 删除'
grep -Fq 'groupCount == 1 && bookCount == 0 -> "操作"' "$M" || fail 26 'single-group selection does not show 操作'
grep -Fq 'groupCount == 1 && bookCount == 0 -> {' "$M" || fail 26 'single-group action branch missing'
grep -Fq 'showGroupActions(group, groupBooks)' "$M" || fail 26 'single-group operation does not open group actions'
grep -Fq 'showRenameGroupDialog(group)' "$M" || fail 26 'group operation chain lacks rename'
grep -Fq 'groupCount >= 2 && bookCount == 0 -> confirmDeleteSelectedGroups()' "$M" || fail 26 'multi-group delete branch missing'
pass 26 'single group uses 操作/rename-capable flow; multi-group selection alone uses 删除'

# 27: shelf selection exits with ×, not 取/取消 text.
grep -Fq 'moreButton.text = "×"' "$M" || fail 27 'main shelf selection cancel is not ×'
grep -Fq 'moreButton.contentDescription = "退出选择"' "$M" || fail 27 '× cancel semantics missing'
! grep -Fq 'moreButton.text = "\\u53d6\\u6d88"' "$M" || fail 27 'old 取消 selection text returned'
pass 27 'main shelf selection uses × cancel'

# 28: selected books regain batch operations rather than top-level delete-only behavior.
grep -Fq 'private fun showShelfBookSelectionActions()' "$M" || fail 28 'shelf book batch action menu missing'
for label in '全选书籍' '取消全选' '移入分组' '移出分组' '删除书架'; do grep -Fq "add(\"$label\")" "$M" || fail 28 "shelf book operation missing $label"; done
grep -Fq 'handleShelfSelectionPrimaryAction()' "$M" || fail 28 'selection top button still bypasses operation dispatcher'
! sed -n '205,218p' "$M" | grep -Fq 'confirmDeleteShelfSelection()' || fail 28 'editButton remains hardwired to delete'
grep -Fq 'setNeutralButton("回归书架", null)' "$M" || fail 28 'multi-group deletion lacks return-to-shelf choice'
grep -Fq 'setPositiveButton("全部删除", null)' "$M" || fail 28 'multi-group deletion lacks explicit full-delete choice'
pass 28 'shelf book operations restored and multi-group delete preserves group/book handling choices'

printf 'ALL_28_GATES_PASS\n'
