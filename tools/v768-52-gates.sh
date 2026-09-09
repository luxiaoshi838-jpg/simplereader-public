#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp tools/v768-52-generated.XXXXXX.sh)"
LEGACY_GATE17="tools/v749-17-gates.sh"
LEGACY_GATE19="tools/v750-19-gates.sh"
BACKUP17="$(mktemp tools/v749-17-gates.v768-backup.XXXXXX.sh)"
BACKUP19="$(mktemp tools/v750-19-gates.v768-backup.XXXXXX.sh)"
cp "$LEGACY_GATE17" "$BACKUP17"
cp "$LEGACY_GATE19" "$BACKUP19"
trap 'cp "$BACKUP17" "$LEGACY_GATE17"; cp "$BACKUP19" "$LEGACY_GATE19"; rm -f "$BACKUP17" "$BACKUP19" "$TMP"' EXIT

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
source = Path('tools/v759-52-gates.sh').read_text(encoding='utf-8')
source = source.replace('2098000759', '2098000768')
source = source.replace("'\\\"759\\\"'", "'\\\"768\\\"'")
source = source.replace('v759', 'v768')
source = source.replace('CATALOG_RULE_VERSION = 113', 'CATALOG_RULE_VERSION = 115')
source = source.replace('RULE_VERSION = 113', 'RULE_VERSION = 115')
source = source.replace('rule113 versions missing', 'rule115 versions missing')
source = source.replace('catalog rule113', 'catalog rule115')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
