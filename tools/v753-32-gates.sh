#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v752-24-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
M=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
G=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt

# 25: group-book selection chrome uses 操作 + ×, never delete-only.
grep -Fq 'actionButton.text = "操作"' "$G" || fail 25 'group-book primary selection action is not 操作'
grep -Fq 'cancelButton.text = "×"' "$G" || fail 25 'group-book cancel is not ×'
! grep -Fq 'setOnClickListener { confirmDeleteSelection() }' "$G" || fail 25 'group-book top action still hardwired to delete'
pass 25 'group-book selection chrome restored to 操作 + ×'

# 26: main shelf single group => 操作, multi-group => 删除; single-group chain includes rename.
grep -Fq 'groupCount == 1 && bookCount == 0 -> "操作"' "$M" || fail 26 'single group does not show 操作'
grep -Fq 'groupCount >= 2 && bookCount == 0 -> "删除"' "$M" || fail 26 'multi-group does not show 删除'
grep -Fq 'showGroupActions(group, groupBooks)' "$M" || fail 26 'single-group operation menu missing'
grep -Fq 'showRenameGroupDialog(group)' "$M" || fail 26 'single-group rename chain missing'
pass 26 'single group uses 操作 with rename; multi-group uses 删除'

# 27: main shelf selection exits with ×.
grep -Fq 'moreButton.text = "×"' "$M" || fail 27 'main shelf selection cancel is not ×'
grep -Fq 'moreButton.contentDescription = "退出选择"' "$M" || fail 27 'main shelf × semantics missing'
pass 27 'main shelf selection uses × cancel'

# 28: one selected book: rename + move + delete; no extra batch-only actions.
for F in "$M" "$G"; do
  grep -Fq 'listOf("重命名", "移动到其他分组", "删除")' "$F" || fail 28 "single-book action set wrong in $F"
done
pass 28 'single selected book supports rename, move, delete'

# 29: multiple selected books: move + delete only; no rename.
for F in "$M" "$G"; do
  grep -Fq 'listOf("移动到其他分组", "删除")' "$F" || fail 29 "multi-book action set wrong in $F"
done
pass 29 'multi-book selection supports move + delete only'

# 30: selected-book deletion always offers shelf-only vs shelf+local-file.
for F in "$M" "$G"; do
  grep -Fq 'setNeutralButton("只删除书架")' "$F" || fail 30 "shelf-only delete option missing in $F"
  grep -Fq 'setPositiveButton("删除书籍及本地")' "$F" || fail 30 "shelf+local delete option missing in $F"
done
pass 30 'book deletion distinguishes shelf-only vs book+local-file'

# 31: group deletion only removes group structure and returns books to shelf; no Book/local deletion in group functions.
python3 - "$M" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); t=p.read_text()
def block(name,next_name):
 s=t.index(f'    private fun {name}(')
 e=t.index(f'\n    private fun {next_name}(',s)
 return t[s:e]
for name,next_name in [('confirmDeleteGroup','showBookActions'),('confirmDeleteSelectedGroups','confirmDeleteShelfSelection')]:
 b=block(name,next_name)
 assert 'clearGroup' in b and 'deleteById(group.id)' in b, name
 assert 'BookFileActions.deleteBookFile' not in b, name
 assert 'database.bookDao().deleteById(book.id)' not in b, name
 assert '全部删除' not in b, name
PY
pass 31 'single/multi group deletion is shelf-group-only; group books return to shelf'

# 32: mixed deletion also never deletes group-contained book rows or local files through groupIds.
python3 - "$M" <<'PY'
from pathlib import Path
import sys
t=Path(sys.argv[1]).read_text()
s=t.index('    private fun deleteShelfSelection(')
e=t.index('\n    private fun confirmDeleteBook(',s)
b=t[s:e]
gs=b.index('groupIds.forEach')
ge=b.index('                    }\n                    localDeleted',gs)
g=b[gs:ge]
assert 'clearGroup(groupId)' in g
assert 'bookGroupDao().deleteById(groupId)' in g
assert 'bookDao().deleteById' not in g
assert 'BookFileActions.deleteBookFile' not in g
PY
pass 32 'group deletion from shelf never deletes group books or local files'

printf 'ALL_32_GATES_PASS\n'
