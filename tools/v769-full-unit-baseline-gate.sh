#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp tools/v769-full-unit-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v767-full-unit-baseline-gate.sh').read_text(encoding='utf-8')
source = source.replace('V767', 'V769').replace('v767', 'v769')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP" "${1:-v769-full-unit-tests.log}"
