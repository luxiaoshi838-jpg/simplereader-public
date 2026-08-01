from pathlib import Path

path = Path("app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt")
text = path.read_text(encoding="utf-8")
updated = text.replace(
    "previousView.text = previous?.content.orEmpty()",
    "previousView.text = previous?.content ?: \"\"",
).replace(
    "nextView.text = next?.content.orEmpty()",
    "nextView.text = next?.content ?: \"\"",
)
if updated == text:
    print("v587 CharSequence fix already applied")
else:
    path.write_text(updated, encoding="utf-8")
    print("v587 CharSequence fix applied")
