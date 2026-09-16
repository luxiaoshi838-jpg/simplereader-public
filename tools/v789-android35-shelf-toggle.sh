#!/usr/bin/env bash
set -euo pipefail

./gradlew assembleDebug --stacktrace --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.simplereader.app >/dev/null
adb logcat -c
adb shell monkey -p com.simplereader.app -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2

dump_ui() {
  adb shell uiautomator dump /sdcard/window.xml >/dev/null
  adb pull /sdcard/window.xml /tmp/window.xml >/dev/null
}

center_for_text() {
  python3 - "$1" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
wanted = sys.argv[1]
root = ET.parse('/tmp/window.xml').getroot()
for node in root.iter('node'):
    if node.attrib.get('text') == wanted:
        match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds', ''))
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            raise SystemExit(0)
raise SystemExit(f'UI text not found: {wanted}')
PY
}

assert_alive() {
  test -n "$(adb shell pidof com.simplereader.app | tr -d '\r')"
  if adb logcat -d AndroidRuntime:E '*:S' | grep -q 'FATAL EXCEPTION'; then
    adb logcat -d AndroidRuntime:E '*:S'
    exit 1
  fi
}

current="宫格"
dump_ui
grep -Fq 'text="宫格"' /tmp/window.xml
assert_alive

for _ in $(seq 1 12); do
  coords="$(center_for_text "$current")"
  x="${coords%% *}"
  y="${coords##* }"
  adb shell input tap "$x" "$y"
  sleep 1
  dump_ui
  if [[ "$current" == "宫格" ]]; then
    current="列表"
  else
    current="宫格"
  fi
  grep -Fq "text=\"$current\"" /tmp/window.xml
  assert_alive
done

echo "Android 35 shelf toggle passed: 12 transitions, app process alive, no fatal exception."
