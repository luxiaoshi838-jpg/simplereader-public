#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp tools/v760-52-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v759-52-gates.sh').read_text(encoding='utf-8')
source = source.replace('2098000759', '2098000760')
source = source.replace("'\\\"759\\\"'", "'\\\"760\\\"'")
source = source.replace('v759', 'v760')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
