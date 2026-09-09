#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp tools/v769-52-generated.XXXXXX.sh)"
LEGACY_GATE17="tools/v749-17-gates.sh"
LEGACY_GATE18="tools/v749-18-gates.sh"
LEGACY_GATE19="tools/v750-19-gates.sh"
BACKUP17="$(mktemp tools/v749-17-gates.v769-backup.XXXXXX.sh)"
BACKUP18="$(mktemp tools/v749-18-gates.v769-backup.XXXXXX.sh)"
BACKUP19="$(mktemp tools/v750-19-gates.v769-backup.XXXXXX.sh)"
cp "$LEGACY_GATE17" "$BACKUP17"
cp "$LEGACY_GATE18" "$BACKUP18"
cp "$LEGACY_GATE19" "$BACKUP19"
trap 'cp "$BACKUP17" "$LEGACY_GATE17"; cp "$BACKUP18" "$LEGACY_GATE18"; cp "$BACKUP19" "$LEGACY_GATE19"; rm -f "$BACKUP17" "$BACKUP18" "$BACKUP19" "$TMP"' EXIT

python3 - "$LEGACY_GATE17" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = '''grep -Fq 'findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()' "$R" || fail 15 'window content background missing' '''.strip()
new = '''grep -Fq 'findViewById<View>(android.R.id.content).background = null' "$R" || fail 15 'window content duplicate background was not removed' '''.strip()
if old in s:
    s = s.replace(old, new, 1)
s = s.replace("start=s.index('private fun addBookCard(')", "start=s.index('private fun buildBookCard(')")
s = s.replace("duplicate title remains below cover in addBookCard", "duplicate title remains below cover in buildBookCard")
p.write_text(s, encoding='utf-8')
PY

python3 - "$LEGACY_GATE18" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = '''# v745 applies V104 only at horizontal PagedReaderView snapshot bind/updateAdjacent.\nif p.count('normalizeSnapshot') < 5:\n    raise SystemExit('FAIL 18 horizontal snapshot V104 placement incomplete')'''
new = '''# V769 still applies V104 only in horizontal PagedReaderView.  The selected-text decorator is\n# inserted immediately before V104 in one helper, so bind/updateAdjacent call that helper instead\n# of spelling normalizeSnapshot five times.\nrequired_horizontal = [\n    'private fun renderSnapshot(snapshot: ReaderPageSnapshot): CharSequence',\n    'ReaderBodyTitleNormalizerV104.normalizeSnapshot(snapshot.copy(content = decorated))',\n    'previousView.text = previous?.let(::renderSnapshot) ?: ""',\n    'currentView.text = renderSnapshot(current)',\n    'nextView.text = next?.let(::renderSnapshot) ?: ""',\n]\nfor token in required_horizontal:\n    if token not in p:\n        raise SystemExit('FAIL 18 horizontal snapshot V104 helper placement incomplete: '+token)\nif p.count('previous?.let(::renderSnapshot)') < 2 or p.count('next?.let(::renderSnapshot)') < 2:\n    raise SystemExit('FAIL 18 horizontal adjacent-page V104 helper placement incomplete')'''
if old not in s:
    raise SystemExit('V769 gate18 adaptation failed: historical V104 assertion not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
PY

python3 - "$LEGACY_GATE19" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
s = s.replace("a=m.index('private fun addBookCard(')", "a=m.index('private fun buildBookCard(')")
p.write_text(s, encoding='utf-8')
PY

python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v757-52-gates.sh').read_text(encoding='utf-8')
source = source.replace('2098000757', '2098000769')
source = source.replace("'\"757\"'", "'\"769\"'")
source = source.replace('v757/rule113 versions missing', 'v769/rule115 versions missing')
source = source.replace('v757 version + catalog rule113', 'v769 version + catalog rule115')
source = source.replace('CATALOG_RULE_VERSION = 113', 'CATALOG_RULE_VERSION = 115')
source = source.replace('RULE_VERSION = 113', 'RULE_VERSION = 115')
old_checkpoint = "'if (changed) scheduleProgressCheckpoint(pages[index].startOffset)'"
new_checkpoint = "'if (index !in pages.indices || currentPageIndex == index) return' 'scheduleProgressCheckpoint(pages[index].startOffset)'"
if old_checkpoint not in source:
    raise SystemExit('V769 gate adaptation failed: V757 checkpoint assertion not found')
source = source.replace(old_checkpoint, new_checkpoint, 1)
old_anchor = "grep -Fq 'val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset())' \"$R\" || fail 40 'fallback stable anchor missing'"
new_anchor = "grep -Fq 'val anchor = (lastStableSourceOffset' \"$R\" && grep -Fq 'CrashLogStore.recoveryOffset(this, bookId)' \"$R\" && grep -Fq '?: currentVisibleSourceOffset()).coerceIn' \"$R\" || fail 40 'fallback stable/recovery anchor missing'"
if old_anchor not in source:
    raise SystemExit('V769 gate adaptation failed: V757 fallback anchor assertion not found')
source = source.replace(old_anchor, new_anchor, 1)
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
