from pathlib import Path

patch_path = Path(__file__).with_name("patch_reader_chrome_and_catalog_405.py")
code = patch_path.read_text(encoding="utf-8")
start_marker = 'reader = replace_once(\n    reader,\n    """    private fun isLikelyChapterTitle(line: String): Boolean {'
end_marker = 'reader = replace_once(\n    reader,\n    """    override fun onSingleTapUp(e: MotionEvent): Boolean {'
start = code.find(start_marker)
end = code.find(end_marker, start + 1)
if start < 0 or end < 0:
    raise SystemExit("cannot isolate obsolete local chapter helper patch")
code = code[:start] + code[end:]
exec(compile(code, str(patch_path), "exec"), {"__file__": str(patch_path), "__name__": "__main__"})

root = Path(__file__).resolve().parents[1]
parser_path = root / "app/src/main/java/com/simplereader/app/parser/TxtParser.kt"
parser = parser_path.read_text(encoding="utf-8")
parser = parser.replace(r"[\p{L}\p{N}]", r"[\\p{L}\\p{N}]")
parser_path.write_text(parser, encoding="utf-8")

ui_test_path = root / "app/src/test/java/com/simplereader/app/ui/ReaderChromeContractTest.kt"
ui_test = ui_test_path.read_text(encoding="utf-8")
ui_test = ui_test.replace(
    'assertTrue(text.contains("if (night) "☀" else "☾""))',
    'assertTrue(text.contains("if (night) \\"☀\\" else \\"☾\\""))'
)
ui_test = ui_test.replace(
    'assertTrue(xml.contains("android:text="☾""))',
    'assertTrue(xml.contains("android:text=\\"☾\\""))'
)
ui_test_path.write_text(ui_test, encoding="utf-8")
