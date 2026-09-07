#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp tools/v763-52-generated.XXXXXX.sh)"
LEGACY_GATE="tools/v749-17-gates.sh"
LEGACY_BACKUP="$(mktemp tools/v749-17-gates.v763-backup.XXXXXX.sh)"
cp "$LEGACY_GATE" "$LEGACY_BACKUP"
trap 'cp "$LEGACY_BACKUP" "$LEGACY_GATE"; rm -f "$LEGACY_BACKUP" "$TMP"' EXIT

# Gate 15 historically required the same full-page background on both readerRoot and
# android.R.id.content. V763 intentionally removes only that duplicate content-layer bitmap while
# retaining the root background and all system-bar/legacy-selection behavior. Adapt only this
# source-shape assertion during the inherited suite; the historical gate file is restored on exit.
python3 - "$LEGACY_GATE" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = '''grep -Fq 'findViewById<View>(android.R.id.content).background = activeBackgroundDrawable()' "$R" || fail 15 'window content background missing' '''.strip()
new = '''grep -Fq 'findViewById<View>(android.R.id.content).background = null' "$R" || fail 15 'window content duplicate background was not removed' '''.strip()
if old not in s:
    raise SystemExit('V763 gate adaptation failed: legacy duplicate-background assertion not found')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY

python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v759-52-gates.sh').read_text(encoding='utf-8')
source = source.replace('2098000759', '2098000763')
source = source.replace("'\\\"759\\\"'", "'\\\"763\\\"'")
source = source.replace('v759', 'v763')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
