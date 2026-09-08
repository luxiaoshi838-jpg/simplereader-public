#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp tools/v766-full-unit-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v760-full-unit-baseline-gate.sh').read_text(encoding='utf-8')
source = source.replace('v760-full-unit-tests.log', 'v766-full-unit-tests.log')
source = source.replace('V760', 'V766').replace('v760', 'v766')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP" "${1:-v766-full-unit-tests.log}"
