from pathlib import Path

path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
text = path.read_text(encoding='utf-8')
old = 'Regex("\\s+")'
new = 'Regex("\\\\s+")'
if old not in text:
    raise SystemExit('illegal regex escape not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('fixed Kotlin regex escape')
