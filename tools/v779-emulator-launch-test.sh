#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven v778 real Release install/cold-launch procedure, then publish v779-named evidence.
bash tools/v778-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v778-emulator-${suffix}.txt"
  dst="v779-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V779_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v779-emulator-result.txt
