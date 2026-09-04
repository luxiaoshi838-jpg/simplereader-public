#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# V758 intentionally inherits every V757 gate. Only two assertions need version-aware adaptation:
# 1) version 757 -> 758;
# 2) V757's `if (changed)` checkpoint shape becomes an equivalent same-page early-return shape.
# Keep the generated script under tools/ so the inherited suite resolves its repository root exactly
# like the original v757 script.
TMP="$(mktemp tools/v758-52-generated.XXXXXX.sh)"
trap 'rm -f "$TMP"' EXIT
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys

source = Path('tools/v757-52-gates.sh').read_text(encoding='utf-8')
source = source.replace("2098000757", "2098000758")
source = source.replace("'\"757\"'", "'\"758\"'")
source = source.replace("v757/rule113 versions missing", "v758/rule113 versions missing")
source = source.replace("v757 version + catalog rule113", "v758 version + catalog rule113")
old = "'if (changed) scheduleProgressCheckpoint(pages[index].startOffset)'"
new = "'if (index !in pages.indices || currentPageIndex == index) return' 'scheduleProgressCheckpoint(pages[index].startOffset)'"
if old not in source:
    raise SystemExit('V758 gate adaptation failed: V757 checkpoint assertion not found')
source = source.replace(old, new, 1)
Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
