#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v752-24-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
M=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
G=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
B=app/src/main/java/com/simplereader/app/ui/BookFileActions.kt

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

# 28: one selected book: rename + move + delete.
for F in "$M" "$G"; do
  grep -Fq 'listOf("重命名", "移动到其他分组", "删除")' "$F" || fail 28 "single-book action set wrong in $F"
done
pass 28 'single selected book supports rename, move, delete'

# 29: multiple selected books: move + delete only.
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

# 31: deleting group(s) offers two shelf-level choices.
grep -Fq 'setNeutralButton("仅删除分组")' "$M" || fail 31 'group-only deletion choice missing'
grep -Fq 'setPositiveButton("分组及书籍一起删除")' "$M" || fail 31 'group+books shelf deletion choice missing'
grep -Fq 'executeDeleteGroups(groupsToDelete, affectedBooks, removeBooksFromShelf = false' "$M" || fail 31 'group-only execution branch missing'
grep -Fq 'executeDeleteGroups(groupsToDelete, affectedBooks, removeBooksFromShelf = true' "$M" || fail 31 'group+books execution branch missing'
pass 31 'group deletion offers return-books vs remove-books-from-shelf choices'

# 32: group deletion may remove Book rows from shelf, but must NEVER delete local files.
python3 - "$M" <<'PY'
from pathlib import Path
import sys
t=Path(sys.argv[1]).read_text()
s=t.index('    private fun executeDeleteGroups(')
e=t.index('\n    private fun showBookActions(',s)
b=t[s:e]
assert 'database.bookDao().clearGroup(group.id)' in b
assert 'database.bookDao().deleteById(book.id)' in b
assert 'database.bookmarkDao().deleteByBookId(book.id)' in b
assert 'database.readProgressDao().deleteByBookId(book.id)' in b
assert 'BookFileActions.deleteBookFile' not in b
assert 'deleteLocalFile' not in b and 'deleteLocalFiles' not in b
PY
pass 32 'group deletion never deletes local book files'

# 33: single-book rename in both shelf paths renames local file BEFORE updating shelf DB.
for F in "$M" "$G"; do
  grep -Fq 'BookFileActions.renameBookFile' "$F" || fail 33 "local-file rename missing in $F"
  python3 - "$F" <<'PY'
from pathlib import Path
import sys
t=Path(sys.argv[1]).read_text()
s=t.index('    private fun showRenameBookDialog(')
# stop at next function
rest=t[s+1:]
pos=rest.find('\n    private fun ')
e=len(t) if pos < 0 else s+1+pos
b=t[s:e]
a=b.index('BookFileActions.renameBookFile')
u=b.index('bookRepository.update(renamed)')
assert a < u, 'DB update occurs before local-file rename'
PY
done
pass 33 'single-book rename changes local file first, then shelf metadata'

# 34: local rename protects conflicts for both File and SAF/content paths.
grep -Fq 'check(!target.exists() || target.absolutePath == file.absolutePath) { "同名文件已存在" }' "$B" || fail 34 'filesystem conflict guard missing'
grep -Fq 'folder.listFiles().any' "$B" || fail 34 'SAF sibling conflict scan missing'
grep -Fq 'candidate.name?.equals(newFileName, ignoreCase = true)' "$B" || fail 34 'SAF case-insensitive conflict comparison missing'
grep -Fq 'check(!conflict) { "同名文件已存在，请换一个书名" }' "$B" || fail 34 'SAF conflict rejection missing'
grep -Fq 'document.renameTo(newFileName)' "$B" || fail 34 'SAF local-file rename missing'
grep -Fq 'file.renameTo(target)' "$B" || fail 34 'filesystem local-file rename missing'
pass 34 'single-book rename protects local filename conflicts on File + SAF paths'

printf 'ALL_34_GATES_PASS\n'
