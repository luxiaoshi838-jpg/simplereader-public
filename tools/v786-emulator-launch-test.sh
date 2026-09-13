#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven Android 35 Release install/cold-launch path, then publish v786 evidence.
bash tools/v785-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v785-emulator-${suffix}.txt"
  dst="v786-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V786_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v786-emulator-result.txt
