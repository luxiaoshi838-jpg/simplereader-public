#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# V761 still inherits the complete V757 52-gate contract through the already-validated V759
# adaptations. Generate directly from V759 instead of nesting the V760 wrapper; otherwise the
# inner generator keeps asserting V759 at Gate 35 even though the outer wrapper says V761.
TMP="$(mktemp tools/v761-52-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v759-52-gates.sh').read_text(encoding='utf-8')
source = source.replace('2098000759', '2098000761')
source = source.replace("'\\\"759\\\"'", "'\\\"761\\\"'")
source = source.replace('v759', 'v761')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
