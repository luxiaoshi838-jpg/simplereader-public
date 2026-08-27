#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt')
s = p.read_text(encoding='utf-8')
original = s

def once(old: str, new: str, label: str):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {count}')
    s = s.replace(old, new, 1)

once(
    'import com.simplereader.app.crash.CrashLogStore\n',
    'import com.simplereader.app.crash.CrashLogStore\nimport com.simplereader.app.operation.OperationLogDialogs\nimport com.simplereader.app.operation.ShelfCacheUiController\n',
    'operation imports'
)

once(
    '        readingStatsTextView = findViewById(R.id.readingStatsTextView)\n        shelfTabTextView = findViewById(R.id.shelfTabTextView)\n',
    '        readingStatsTextView = findViewById(R.id.readingStatsTextView)\n        ShelfCacheUiController.attach(this, readingStatsTextView) { updateUI() }\n        shelfTabTextView = findViewById(R.id.shelfTabTextView)\n',
    'status controller attach'
)

once(
    '            readingStatsTextView.text = "${group?.displayName?.ifBlank { group.name } ?: "分组"} · ${groupBooks.size} 本"\n',
    '            if (!ShelfCacheUiController.isLocked(this)) {\n                readingStatsTextView.text = "${group?.displayName?.ifBlank { group.name } ?: "分组"} · ${groupBooks.size} 本"\n            }\n',
    'group idle status ownership'
)

once(
    '        readingStatsTextView.text = "累计导入 ${books.size} 本"\n',
    '        if (!ShelfCacheUiController.isLocked(this)) {\n            readingStatsTextView.text = "累计导入 ${books.size} 本"\n        }\n',
    'shelf idle status ownership'
)

once(
    '    private fun startShelfCache(mode: String) {\n        ShelfCacheWorker.enqueue(this, mode)\n',
    '    private fun startShelfCache(mode: String) {\n        ShelfCacheUiController.showPreparing(this, readingStatsTextView)\n        ShelfCacheWorker.enqueue(this, mode)\n',
    'preparing status'
)

once(
    '''    private fun showDataExportOptions() {\n        AlertDialog.Builder(this)\n            .setTitle("数据导出")\n            .setItems(arrayOf("导出", "同步")) { _, which ->\n                when (which) {\n                    0 -> launchDataExport()\n                    1 -> syncDataExport()\n                }\n            }\n            .show()\n    }\n''',
    '''    private fun showDataExportOptions() {\n        AlertDialog.Builder(this)\n            .setTitle("数据导出")\n            .setItems(arrayOf("导出", "同步", "日志")) { _, which ->\n                when (which) {\n                    0 -> launchDataExport()\n                    1 -> syncDataExport()\n                    2 -> OperationLogDialogs.showLogHub(this)\n                }\n            }\n            .show()\n    }\n''',
    'log entry'
)

if s == original:
    raise SystemExit('no changes applied')
p.write_text(s, encoding='utf-8')
print('v725 MainActivity patch applied')
