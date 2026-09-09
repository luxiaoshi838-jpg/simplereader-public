#!/usr/bin/env bash
set -euo pipefail
TMP="$(mktemp tools/v770-selection-touch-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import os
import sys
source = Path('tools/v769-selection-touch-gates.sh').read_text(encoding='utf-8')
version_code = os.environ.get('SIMPLE_READER_VERSION_CODE', '2098000770')
version_name = os.environ.get('SIMPLE_READER_VERSION_NAME', '770')
source = source.replace('2098000769', version_code)
source = source.replace("'\"769\"'", f"'\"{version_name}\"'")
source = source.replace('v769', f'v{version_name}').replace('V769', f'V{version_name}')
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
