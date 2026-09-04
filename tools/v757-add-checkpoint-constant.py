from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = p.read_text(encoding='utf-8')
needle = '        private const val VERTICAL_STATE_UNLOCK_GUARD_MS = 900L\n'
insert = needle + '        private const val PROGRESS_CHECKPOINT_DELAY_MS = 600L\n'
if 'private const val PROGRESS_CHECKPOINT_DELAY_MS = 600L' in s:
    raise SystemExit('constant already present')
if needle not in s:
    raise SystemExit('insertion point missing')
s = s.replace(needle, insert, 1)
p.write_text(s, encoding='utf-8')
print('v757 checkpoint delay constant added')
