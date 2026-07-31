from pathlib import Path

path = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = path.read_text(encoding="utf-8")
old = '        return "第 ${page} 页 · $progress\\n$preview"\n'
new = '        return "$pageLabel · $progress\\n$preview"\n'
if old in text:
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
elif new not in text:
    raise SystemExit("v589 bookmark page-label target not found")
