from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/com/simplereader/app/reader/page/PageCacheStore.kt"
text = path.read_text(encoding="utf-8")
inserted = '''    fun clearBook(context: Context, bookId: Long) {
        bookDir(context, bookId).deleteRecursively()
    }

    fun textFingerprint(text: String): String {'''
replacement = '''    fun textFingerprint(text: String): String {'''
if inserted not in text:
    raise SystemExit("v789 post-fix: inserted duplicate clearBook block not found")
text = text.replace(inserted, replacement, 1)
path.write_text(text, encoding="utf-8")
print("v789 post-fix applied")
