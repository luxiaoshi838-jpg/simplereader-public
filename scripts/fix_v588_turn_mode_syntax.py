from pathlib import Path

path = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = path.read_text(encoding="utf-8")
old = '''        pagedReaderView.setTurnMode(pagedTurnMode())
        currentPagedPage?.let(::updatePagedProgressLabel)
            ?: if (openSucceeded && currentContent.isNotBlank()) {
                refreshPagedReader(pagedAnchorFromCurrentPosition())
            }
'''
new = '''        pagedReaderView.setTurnMode(pagedTurnMode())
        val visiblePage = currentPagedPage
        if (visiblePage != null) {
            updatePagedProgressLabel(visiblePage)
        } else if (openSucceeded && currentContent.isNotBlank()) {
            refreshPagedReader(pagedAnchorFromCurrentPosition())
        }
'''
if old in text:
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("Applied v588 turn-mode syntax fix")
elif new in text:
    print("v588 turn-mode syntax fix already applied")
else:
    raise SystemExit("Expected v588 turn-mode block not found")
