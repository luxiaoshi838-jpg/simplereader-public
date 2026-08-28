#!/usr/bin/env bash
set -euo pipefail

bad="$(git ls-files | awk 'tolower($0) ~ /\.apk$/ {print}')"
if [[ -n "$bad" ]]; then
  echo "::error::APK files are forbidden in the public repository. Remove these tracked files:"
  printf '%s\n' "$bad"
  exit 1
fi

echo "OK: no APK files are tracked in the public repository."
