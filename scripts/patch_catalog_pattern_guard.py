from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/java/com/simplereader/app/parser/TxtParser.kt"
text = path.read_text(encoding="utf-8")
old = '            Regex("^\\\\s*[0-9]{1,5}\\\\s+\\\\S.{0,100}$"),\n'
new = '            Regex("^\\\\s*[0-9]{1,5}\\\\s+(?![年月日点时分秒个次米])\\\\S.{0,100}$"),\n'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("number-space chapter guard target missing")
path.write_text(text, encoding="utf-8")
print("number-space chapter guard applied")
