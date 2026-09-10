#!/usr/bin/env python3
"""Idempotent v780 entry point.

A persisted v780 tree may already use the compatibility-preserving PageEngine signature
(typeface, shouldCancel, imageSpanProvider). In that state the original transformation script
must not try to match the old v779 signature again. Fresh v779-style trees still get the full
runtime transformation followed by the trailing-lambda compatibility adjustment.
"""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
reader = root / "app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt"
engine = root / "app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt"
gradle = root / "app/build.gradle.kts"

r = reader.read_text(encoding="utf-8")
e = engine.read_text(encoding="utf-8")
g = gradle.read_text(encoding="utf-8")

runtime_markers = (
    "private var paginationEpoch: Long = 0L",
    "private fun applyTransientFontPreview(anchorOffset: Int, requestId: Long)",
    "private fun paginateAndDisplay(preserveOffset: Int?, fontRequestId: Long? = null)",
    "stale pagination result suppressed",
    "font_preview:applied",
)
compatible_engine_markers = (
    "shouldCancel: (() -> Boolean)? = null,\n        imageSpanProvider: ImageSpanProvider? = null",
    "private fun throwIfPaginationCancelled",
    "PageEngine pagination cancelled",
)
version_markers = (
    'SIMPLE_READER_VERSION_CODE") ?: "2098000780"',
    'SIMPLE_READER_VERSION_NAME") ?: "780"',
)

already_applied = (
    all(m in r for m in runtime_markers)
    and all(m in e for m in compatible_engine_markers)
    and all(m in g for m in version_markers)
)

if already_applied:
    print("v780 already applied: live font preview + compatible cancellable PageEngine")
    sys.exit(0)

subprocess.run([sys.executable, str(root / "tools/apply-v780-live-font-preview.py")], check=True)
subprocess.run([sys.executable, str(root / "tools/apply-v780-pagination-api-compat.py")], check=True)
print("v780 applied through idempotent wrapper")
