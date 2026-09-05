#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# V759 inherits the complete V757 52-gate contract. Only assertions whose source shape changed
# while preserving/strengthening the same behavior are adapted: release version, V758 same-page
# checkpoint shape, and V759's expanded fallback anchor that inserts a persisted recovery offset
# between the in-memory stable offset and the visible-position fallback.
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

old_checkpoint = "'if (changed) scheduleProgressCheckpoint(pages[index].startOffset)'"
new_checkpoint = "'if (index !in pages.indices || currentPageIndex == index) return' 'scheduleProgressCheckpoint(pages[index].startOffset)'"
if old_checkpoint not in source:
    raise SystemExit('V759 gate adaptation failed: V757 checkpoint assertion not found')
source = source.replace(old_checkpoint, new_checkpoint, 1)

old_anchor = "grep -Fq 'val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset())' \"$R\" || fail 40 'fallback stable anchor missing'"
new_anchor = "grep -Fq 'val anchor = (lastStableSourceOffset' \"$R\" && grep -Fq 'CrashLogStore.recoveryOffset(this, bookId)' \"$R\" && grep -Fq '?: currentVisibleSourceOffset()).coerceIn' \"$R\" || fail 40 'fallback stable/recovery anchor missing'"
if old_anchor not in source:
    raise SystemExit('V759 gate adaptation failed: V757 fallback anchor assertion not found')
source = source.replace(old_anchor, new_anchor, 1)

Path(sys.argv[1]).write_text(source, encoding='utf-8')
PY
bash "$TMP"
