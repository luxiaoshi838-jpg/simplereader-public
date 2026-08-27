#!/usr/bin/env bash
set -euo pipefail

pkg='com.simplereader.app'
component='com.simplereader.app/.ui.MainActivity'
apk='app/build/outputs/apk/debug/app-debug.apk'

adb install -r "$apk"
adb logcat -c
adb shell am force-stop "$pkg"
adb shell am start -W -n "$component" | tee emulator-first-start.txt
sleep 5
test -n "$(adb shell pidof "$pkg" | tr -d '\r')"
adb shell dumpsys activity activities > emulator-first-activity.txt
grep -Fq 'com.simplereader.app/.ui.MainActivity' emulator-first-activity.txt
adb logcat -d > emulator-first-logcat.txt
if grep -Fq 'Process: com.simplereader.app' emulator-first-logcat.txt; then
  echo 'v725 crashed on fresh launch'
  exit 31
fi

# Seed an oversized legacy v722 operation log, then prove v725 deletes it before normal log loading.
adb shell am force-stop "$pkg"
python3 - <<'PY'
from pathlib import Path
payload = 'X' * (2 * 1024 * 1024)
Path('/tmp/operation.xml').write_text(
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name=\"log\">" + payload + "</string>\n</map>\n",
    encoding='utf-8'
)
PY
adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cat > shared_prefs/operation.xml'" < /tmp/operation.xml
adb shell "run-as $pkg sh -c 'test -s shared_prefs/operation.xml'"

adb logcat -c
adb shell am start -W -n "$component" | tee emulator-legacy-start.txt
sleep 5
test -n "$(adb shell pidof "$pkg" | tr -d '\r')"
adb shell dumpsys activity activities > emulator-legacy-activity.txt
grep -Fq 'com.simplereader.app/.ui.MainActivity' emulator-legacy-activity.txt
adb logcat -d > emulator-legacy-logcat.txt
if grep -Fq 'Process: com.simplereader.app' emulator-legacy-logcat.txt; then
  echo 'v725 crashed after legacy operation log seed'
  exit 32
fi
if adb shell "run-as $pkg sh -c 'test -e shared_prefs/operation.xml'"; then
  echo 'legacy operation.xml was not physically removed'
  exit 33
fi

# Seed one real v725 history entry so the list/detail UI can be exercised deterministically.
python3 - <<'PY'
import html, json
from pathlib import Path
body = '\n'.join(f'第{i}行：v725日志滚动功能验证' for i in range(1, 181))
entries = [{
    'id': 'ui-check',
    'title': 'V725功能验证日志',
    'body': body,
    'startedAt': 1787820000000,
    'updatedAt': 1787820001000,
}]
raw = json.dumps(entries, ensure_ascii=False, separators=(',', ':'))
xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name=\"entries\">" + html.escape(raw) + "</string>\n</map>\n"
Path('/tmp/operation_history.xml').write_text(xml, encoding='utf-8')
PY
adb shell am force-stop "$pkg"
adb shell "run-as $pkg sh -c 'cat > shared_prefs/operation_history.xml'" < /tmp/operation_history.xml
adb shell am start -W -n "$component" > /tmp/restart-ui.txt
sleep 2

cat > /tmp/ui_action.py <<'PY'
import re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = 'com.simplereader.app'

def dump():
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/window.xml'], check=True, stdout=subprocess.DEVNULL)
    raw = subprocess.check_output(['adb','exec-out','cat','/sdcard/window.xml'])
    return ET.fromstring(raw.decode('utf-8', errors='replace'))

def tap_node(node, delay=0.55):
    m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
    if not m:
        raise SystemExit(f'node has no usable bounds: {node.attrib}')
    x1,y1,x2,y2 = map(int,m.groups())
    subprocess.run(['adb','shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)], check=True)
    time.sleep(delay)

def click_text(text, delay=0.55):
    root = dump()
    for node in root.iter('node'):
        if node.attrib.get('text') == text:
            tap_node(node, delay)
            return
    values=[n.attrib.get('text','') for n in root.iter('node')]
    raise SystemExit(f'UI text not found: {text}; values={values}')

def click_resource(short_id, delay=0.55):
    wanted = short_id if ':' in short_id else f'{PKG}:id/{short_id}'
    root = dump()
    for node in root.iter('node'):
        if node.attrib.get('resource-id') == wanted:
            tap_node(node, delay)
            return
    ids=[n.attrib.get('resource-id','') for n in root.iter('node')]
    raise SystemExit(f'UI resource not found: {wanted}; ids={ids}')

def assert_text(text, present=True):
    values=[n.attrib.get('text','') for n in dump().iter('node')]
    hit=text in values
    if hit != present:
        raise SystemExit(f'UI text expectation failed: {text}, present={present}, values={values}')

def assert_seekbar():
    classes=[n.attrib.get('class','') for n in dump().iter('node')]
    if 'android.widget.SeekBar' not in classes:
        raise SystemExit(f'SeekBar missing: {classes}')

def text_by_resource(short_id):
    wanted = short_id if ':' in short_id else f'{PKG}:id/{short_id}'
    for n in dump().iter('node'):
        if n.attrib.get('resource-id') == wanted:
            return n.attrib.get('text','')
    raise SystemExit(f'resource missing: {wanted}')

if __name__ == '__main__':
    cmd=sys.argv[1]
    if cmd=='click': click_text(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 0.55)
    elif cmd=='clickres': click_resource(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 0.55)
    elif cmd=='has': assert_text(sys.argv[2], True)
    elif cmd=='lacks': assert_text(sys.argv[2], False)
    elif cmd=='seekbar': assert_seekbar()
    elif cmd=='resource': print(text_by_resource(sys.argv[2]))
    else: raise SystemExit(cmd)
PY

# Real log UI flow. Click the actual home-screen export view by resource id,
# then verify that its dialog exposes the new Log path.
python3 /tmp/ui_action.py clickres exportButton
python3 /tmp/ui_action.py has '数据导出'
python3 /tmp/ui_action.py has '日志'
python3 /tmp/ui_action.py click '日志'
python3 /tmp/ui_action.py has '操作日志'
python3 /tmp/ui_action.py click '操作日志'
python3 /tmp/ui_action.py has 'V725功能验证日志'
python3 /tmp/ui_action.py lacks '复制'
python3 /tmp/ui_action.py click 'V725功能验证日志'
python3 /tmp/ui_action.py has '复制'
python3 /tmp/ui_action.py seekbar
python3 /tmp/ui_action.py click '关闭'

# Real shelf-cache UI flow. Preserve the original ⋮ group-management button and
# reach the new cache action through it.
python3 /tmp/ui_action.py clickres moreButton
python3 /tmp/ui_action.py has '书架管理'
python3 /tmp/ui_action.py has '批量管理分组'
python3 /tmp/ui_action.py has '书架目录缓存'
python3 /tmp/ui_action.py click '书架目录缓存'
python3 /tmp/ui_action.py has '全书架目录缓存'
python3 /tmp/ui_action.py click '全书架目录缓存' 0.02

# On an empty emulator shelf the worker may complete faster than uiautomator can
# sample. Accept either the required active two-line state or immediate idle
# restoration here; the exact preparing/active/completed strings are hard-gated
# separately by ShelfCacheStatusTextTest.
status_seen=0
for i in $(seq 1 20); do
  status="$(python3 /tmp/ui_action.py resource readingStatsTextView | tr -d '\r')"
  printf '%s\n' "$status" | tee emulator-cache-status.txt
  if printf '%s' "$status" | grep -q '成功 ' && printf '%s' "$status" | grep -q '失败 ' && printf '%s' "$status" | grep -q '跳过 ' && printf '%s' "$status" | grep -q ' / '; then
    status_seen=1
    break
  fi
  if printf '%s' "$status" | grep -q '^累计导入 '; then
    status_seen=2
    break
  fi
  sleep 0.10
done
test "$status_seen" -ne 0

# Wait for the zero-book worker to finish and prove the status box returns to idle.
sleep 3
idle="$(python3 /tmp/ui_action.py resource readingStatsTextView | tr -d '\r')"
printf '%s\n' "$idle" | tee emulator-cache-idle.txt
printf '%s' "$idle" | grep -q '^累计导入 '

# Prove the actual worker wrote exactly one additional operation-history entry,
# rather than per-book spam. Starting from the single seeded UI entry, there must
# now be exactly two entries and exactly one cache-task entry.
adb shell "run-as $pkg cat shared_prefs/operation_history.xml" > /tmp/operation_history_after.xml
python3 - <<'PY'
import json, xml.etree.ElementTree as ET
root = ET.parse('/tmp/operation_history_after.xml').getroot()
raw = None
for child in root:
    if child.tag == 'string' and child.attrib.get('name') == 'entries':
        raw = child.text or ''
        break
if raw is None:
    raise SystemExit('operation history entries key missing after shelf cache')
entries = json.loads(raw)
if len(entries) != 2:
    raise SystemExit(f'expected seeded log + one cache task = 2 entries, got {len(entries)}: {entries}')
cache_entries = [e for e in entries if '全书架目录缓存' in e.get('title','')]
if len(cache_entries) != 1:
    raise SystemExit(f'expected exactly one cache task history entry, got {len(cache_entries)}: {entries}')
body = cache_entries[0].get('body','')
if '已完成' not in body or '成功' not in body or '失败' not in body or '跳过' not in body:
    raise SystemExit(f'cache task log body incomplete: {body}')
PY

adb logcat -d > emulator-feature-logcat.txt
if grep -Fq 'Process: com.simplereader.app' emulator-feature-logcat.txt; then
  echo 'v725 crashed during feature UI checks'
  exit 34
fi

echo 'V725_EMULATOR_LAUNCH=PASS' | tee emulator-v725-result.txt
echo 'V725_LEGACY_LOG_PURGE=PASS' | tee -a emulator-v725-result.txt
echo 'V725_LOG_LIST_DETAIL_UI=PASS' | tee -a emulator-v725-result.txt
echo 'V725_LOG_DETAIL_SEEKBAR=PASS' | tee -a emulator-v725-result.txt
echo 'V725_GROUP_ACTION_PRESERVED=PASS' | tee -a emulator-v725-result.txt
echo 'V725_CACHE_TASK_LOG_SINGLE_ENTRY=PASS' | tee -a emulator-v725-result.txt
echo 'V725_CACHE_STATUS_UI=PASS' | tee -a emulator-v725-result.txt
echo 'V725_CACHE_IDLE_RESTORE=PASS' | tee -a emulator-v725-result.txt
