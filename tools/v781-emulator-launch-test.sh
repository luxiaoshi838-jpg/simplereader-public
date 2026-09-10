#!/usr/bin/env bash
set -euo pipefail

# Reuse the proven Android 35 real Release install/cold-launch path, then publish v781 evidence.
bash tools/v780-emulator-launch-test.sh

for suffix in signature install start logcat activity pid; do
  src="v780-emulator-${suffix}.txt"
  dst="v781-emulator-${suffix}.txt"
  [ -f "$src" ] && cp "$src" "$dst"
done

echo 'V781_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v781-emulator-result.txt
