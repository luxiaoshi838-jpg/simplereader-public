#!/usr/bin/env python3
"""Strict v726-vs-v722 APK parity gate.

Usage:
  python3 tools/v726/verify_apk_baseline.py <v722.apk> <v726.apk>

This verifies the *actual* accepted v722 SHA-256 first, then requires every
non-signing APK entry outside the explicit v726 allow-list to be byte-identical
and to retain the same ZIP compression method.
"""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASELINE = json.loads((HERE / "v722-baseline.json").read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def ignored(name: str) -> bool:
    return any(name.startswith(prefix) for prefix in BASELINE["ignoredSigningPrefixes"])


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: verify_apk_baseline.py <v722.apk> <v726.apk>")

    base = Path(sys.argv[1])
    candidate = Path(sys.argv[2])
    if not base.is_file() or not candidate.is_file():
        fail("both APK paths must exist")

    actual_base = sha256_file(base)
    expected_base = BASELINE["apkSha256"]
    if actual_base != expected_base:
        fail(f"wrong v722 baseline: {actual_base}; expected {expected_base}")

    allowed_changed = set(BASELINE["allowedChangedEntries"])
    allowed_added = set(BASELINE["allowedAddedEntries"])

    with zipfile.ZipFile(base) as zb, zipfile.ZipFile(candidate) as zc:
        base_info = {i.filename: i for i in zb.infolist() if not ignored(i.filename)}
        cand_info = {i.filename: i for i in zc.infolist() if not ignored(i.filename)}

        base_names = set(base_info)
        cand_names = set(cand_info)
        missing = sorted(base_names - cand_names)
        added = sorted(cand_names - base_names)
        unauthorized_added = [n for n in added if n not in allowed_added]
        if missing:
            fail("candidate removed v722 entries: " + ", ".join(missing))
        if unauthorized_added:
            fail("candidate added unauthorized entries: " + ", ".join(unauthorized_added))
        if set(added) != allowed_added:
            fail(f"expected added entries {sorted(allowed_added)}, got {added}")

        changed = []
        compression_changed = []
        unchanged_count = 0
        for name in sorted(base_names & cand_names):
            binfo = base_info[name]
            cinfo = cand_info[name]
            bdata = zb.read(name)
            cdata = zc.read(name)
            if sha256_bytes(bdata) != sha256_bytes(cdata):
                changed.append(name)
            else:
                unchanged_count += 1
                if binfo.compress_type != cinfo.compress_type:
                    compression_changed.append(name)

        unauthorized_changed = [n for n in changed if n not in allowed_changed]
        if unauthorized_changed:
            fail("candidate changed unauthorized entries: " + ", ".join(unauthorized_changed))
        if set(changed) != allowed_changed:
            fail(f"expected changed entries {sorted(allowed_changed)}, got {changed}")
        if compression_changed:
            fail("ZIP compression changed for otherwise-identical entries: " + ", ".join(compression_changed))

    result = {
        "baselineSha256": actual_base,
        "candidateSha256": sha256_file(candidate),
        "unchangedNonSigningEntries": unchanged_count,
        "changedEntries": sorted(allowed_changed),
        "addedEntries": sorted(allowed_added),
        "removedEntries": [],
        "unauthorizedDifferences": [],
        "status": "PASS",
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
