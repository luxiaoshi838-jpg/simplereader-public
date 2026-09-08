#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp tools/v765-full-unit-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v760-full-unit-baseline-gate.sh').read_text(encoding='utf-8')
source = source.replace('v760-full-unit-tests.log', 'v765-full-unit-tests.log')
source = source.replace('V760', 'V765').replace('v760', 'v765')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP" "${1:-v765-full-unit-tests.log}"
