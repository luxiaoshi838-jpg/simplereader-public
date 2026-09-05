#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# V759 inherits the complete V757 52-gate contract. The only semantic adaptations are the
# release version and V758's already-validated same-page early-return checkpoint shape.
TMP="$(mktemp tools/v759-52-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
source = Path('tools/v757-52-gates.sh').read_text(encoding='utf-8')
source = source.replace("2098000757", "2098000759")
source = source.replace("'\"757\"'", "'\"759\"'")
source = source.replace("v757/rule113 versions missing", "v759/rule113 versions missing")
source = source.replace("v757 version + catalog rule113", "v759 version + catalog rule113")
old = "'if (changed) scheduleProgressCheckpoint(pages[index].startOffset)'"
new = "'if (index !in pages.indices || currentPageIndex == index) return' 'scheduleProgressCheckpoint(pages[index].startOffset)'"
if old not in source:
    raise SystemExit('V759 gate adaptation failed: V757 checkpoint assertion not found')
source = source.replace(old, new, 1)
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
