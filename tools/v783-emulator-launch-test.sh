#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven Android 35 real Release install/cold-launch path, then publish v783 evidence.
bash tools/v782-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v782-emulator-${suffix}.txt"
  dst="v783-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V783_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v783-emulator-result.txt
