#!/usr/bin/env bash
set -euo pipefail

PACKAGE='com.simplereader.app'
ACTIVITY='com.simplereader.app/.ui.MainActivity'
RELEASE_APK="$(find app/build/outputs/apk/release -maxdepth 1 -type f \( -name 'app-release-unsigned.apk' -o -name 'app-release.apk' \) | head -n 1)"
if [ -z "$RELEASE_APK" ] || [ ! -s "$RELEASE_APK" ]; then
  echo 'FAIL: release APK not found'
  exit 1
fi

BUILD_TOOLS="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
TEST_DIR="${RUNNER_TEMP:-/tmp}/simplereader-v776-launch"
mkdir -p "$TEST_DIR"
TEST_KEYSTORE="$TEST_DIR/launch-test.jks"
TEST_APK="$TEST_DIR/SimpleReader_v776_release_launch_test.apk"

if [ ! -f "$TEST_KEYSTORE" ]; then
  keytool -genkeypair -noprompt \
    -keystore "$TEST_KEYSTORE" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname 'CN=SimpleReader CI Launch Test,O=SimpleReader,C=CN' >/dev/null 2>&1
fi

"$APKSIGNER" sign \
  --ks "$TEST_KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$TEST_APK" \
  "$RELEASE_APK"
"$APKSIGNER" verify --verbose "$TEST_APK" | tee v776-emulator-signature.txt

adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb install "$TEST_APK" | tee v776-emulator-install.txt
grep -Fq 'Success' v776-emulator-install.txt

adb logcat -c
adb shell am force-stop "$PACKAGE" || true
set +e
adb shell am start -W -n "$ACTIVITY" > v776-emulator-start.txt 2>&1
start_code=$?
set -e
cat v776-emulator-start.txt
if [ "$start_code" -ne 0 ]; then
  echo "FAIL: am start exit=$start_code"
  adb logcat -d -v time > v776-emulator-logcat.txt || true
  exit 1
fi

sleep 5
adb logcat -d -v time > v776-emulator-logcat.txt || true
adb shell dumpsys activity activities > v776-emulator-activity.txt || true

set +e
pid_output="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
pid_code=$?
set -e
printf '%s\n' "$pid_output" | tee v776-emulator-pid.txt

if [ "$pid_code" -ne 0 ] || [ -z "$pid_output" ]; then
  echo "FAIL: app process disappeared after cold launch (pidof_exit=$pid_code)"
  grep -nE -A100 -B20 'FATAL EXCEPTION|AndroidRuntime|Process: com\.simplereader\.app|Unable to start activity|InflateException|com\.simplereader\.app' v776-emulator-logcat.txt | tail -n 300 || true
  exit 1
fi

if ! grep -Eq 'mResumedActivity.*com\.simplereader\.app|topResumedActivity.*com\.simplereader\.app|ResumedActivity.*com\.simplereader\.app' v776-emulator-activity.txt; then
  echo 'FAIL: MainActivity is not resumed after launch'
  grep -nE 'mResumedActivity|topResumedActivity|ResumedActivity|simplereader' v776-emulator-activity.txt | tail -n 100 || true
  exit 1
fi

if grep -q 'FATAL EXCEPTION' v776-emulator-logcat.txt; then
  echo 'FAIL: FATAL EXCEPTION observed after cold launch'
  grep -n -A100 -B20 'FATAL EXCEPTION' v776-emulator-logcat.txt | tail -n 300 || true
  exit 1
fi

if grep -Eq 'Process: com\.simplereader\.app.*PID:|Unable to start activity.*com\.simplereader\.app|InflateException.*simplereader' v776-emulator-logcat.txt; then
  echo 'FAIL: package startup crash marker observed'
  grep -nE -A100 -B20 'Process: com\.simplereader\.app|Unable to start activity|InflateException' v776-emulator-logcat.txt | tail -n 300 || true
  exit 1
fi

echo 'V776_ANDROID35_RELEASE_COLD_LAUNCH_PASS' | tee v776-emulator-result.txt
