#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp tools/v770-selection-touch-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v769-selection-touch-gates.sh').read_text(encoding='utf-8')
source = source.replace('2098000769', '2098000770')
source = source.replace("'\"769\"'", "'\"770\"'")
source = source.replace('v769', 'v770').replace('V769', 'V770')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
