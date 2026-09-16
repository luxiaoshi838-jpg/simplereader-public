from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
target = ROOT / "tools/apply-v791-vertical-scroll-rollback.py"
text = target.read_text(encoding="utf-8")
text = text.replace(
    'if "VERTICAL_ROLLBACK_VISIBLE_MS" not in reader:',
    'if "private const val VERTICAL_ROLLBACK_VISIBLE_MS" not in reader:',
    1,
)
old = '''                if (pageTurnMode == TURN_MODE_VERTICAL && verticalGestureStartLocation == null) {
                    verticalGestureStartLocation = captureVerticalLocation()
                }
                // A new real touch must always brake the previous ViewFlinger before RecyclerView or
                // a selectable child TextView gets the event. This closes the stale-settling path.
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
'''
new = '''                // A new real touch must always brake the previous ViewFlinger before RecyclerView or
                // a selectable child TextView gets the event. Capture this gesture's origin only
                // after stopScroll(): stopScroll may synchronously emit IDLE and must not consume
                // the origin belonging to the new gesture.
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
                if (pageTurnMode == TURN_MODE_VERTICAL && verticalGestureStartLocation == null) {
                    verticalGestureStartLocation = captureVerticalLocation()
                }
'''
if old not in text:
    raise SystemExit("v791 final wrapper: ACTION_DOWN ordering anchor missing")
text = text.replace(old, new, 1)
target.write_text(text, encoding="utf-8")
runpy.run_path(str(target), run_name="__main__")
