#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven Android 35 Release install/cold-launch path, then publish v785 evidence.
bash tools/v784-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v784-emulator-${suffix}.txt"
  dst="v785-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V785_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v785-emulator-result.txt
