from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
target = ROOT / "tools/apply-v791-vertical-scroll-rollback.py"
text = target.read_text(encoding="utf-8")
old = 'if "VERTICAL_ROLLBACK_VISIBLE_MS" not in reader:'
new = 'if "private const val VERTICAL_ROLLBACK_VISIBLE_MS" not in reader:'
if old not in text:
    raise SystemExit("v791 fixed wrapper: expected constant guard not found")
target.write_text(text.replace(old, new, 1), encoding="utf-8")
runpy.run_path(str(target), run_name="__main__")
