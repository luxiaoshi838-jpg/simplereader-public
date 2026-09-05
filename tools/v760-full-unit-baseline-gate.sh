#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp tools/v760-full-unit-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v759-full-unit-baseline-gate.sh').read_text(encoding='utf-8')
source = source.replace('v759-full-unit-tests.log', 'v760-full-unit-tests.log')
source = source.replace('V759', 'V760').replace('v759', 'v760')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP" "${1:-v760-full-unit-tests.log}"
