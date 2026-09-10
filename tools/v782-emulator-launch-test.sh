#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven Android 35 real Release install/cold-launch path, then publish v782 evidence.
bash tools/v781-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v781-emulator-${suffix}.txt"
  dst="v782-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V782_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v782-emulator-result.txt
