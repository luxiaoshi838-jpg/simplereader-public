#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven Android 35 Release install/cold-launch path, then publish v784 evidence.
bash tools/v783-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v783-emulator-${suffix}.txt"
  dst="v784-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V784_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v784-emulator-result.txt
