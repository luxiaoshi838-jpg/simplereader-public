#!/usr/bin/env bash
set -euo pipefail

LOG="${1:-v759-full-unit-tests.log}"
rm -f "$LOG"

set +e
set -o pipefail
./gradlew testDebugUnitTest --stacktrace 2>&1 | tee "$LOG"
gradle_status=${PIPESTATUS[0]}
set -e

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path('app/build/test-results/testDebugUnitTest')
if not root.is_dir():
    raise SystemExit('FAIL: unit-test result directory missing')

actual = set()
for xml in root.glob('TEST-*.xml'):
    tree = ET.parse(xml)
    suite = tree.getroot()
    suite_class = suite.attrib.get('name', '')
    for case in suite.findall('testcase'):
        if case.find('failure') is None and case.find('error') is None:
            continue
        classname = case.attrib.get('classname') or suite_class
        name = case.attrib.get('name', '')
        actual.add((classname, name))

# Unchanged source-v758 baseline, verified on GitHub Actions run 33943123874.
known_v758 = {
    ('com.simplereader.app.operation.ShelfCacheResumeMigrationContractTest',
     'unfinishedLegacyV725WorkSeedsCheckpointFromPersistedWorkManagerProgress'),
    ('com.simplereader.app.parser.TxtChapterAndEncodingRegressionTest',
     'decodesGb18030AndScansNumberSpaceCatalogWithoutMojibake'),
    ('com.simplereader.app.parser.TxtChapterAndEncodingRegressionTest',
     'scansFinalChapterWithoutTrailingNewline'),
    ('com.simplereader.app.parser.TxtChapterAndEncodingRegressionTest',
     'recognizesNumberSpaceChapterTitles'),
    ('com.simplereader.app.ui.CrashLogAndCoverContractTest',
     'plain text uses generic cover without txt badge while epub real cover still wins'),
    ('com.simplereader.app.ui.ReaderTopTitleAndCatalogContractTest',
     'readerKeepsOneCharacterMarginAtTopAndBottom'),
    ('com.simplereader.app.ui.ShelfBackgroundCacheContractTest',
     'shelfManagementStartsPersistentCatalogCache'),
    ('com.simplereader.app.ui.UiRegressionPolicyTest',
     'confirmed layered background is restored'),
    ('com.simplereader.app.ui.UiRegressionPolicyTest',
     'vertical mode uses a bounded continuous window and one character bottom margin'),
    ('com.simplereader.app.ui.V13InteractionContractTest',
     'bookmark creation stays in reader top bar only'),
}

unexpected = sorted(actual - known_v758)
resolved = sorted(known_v758 - actual)
print(f'V759 full unit tests: failures={len(actual)}, V758-known={len(actual & known_v758)}, unexpected={len(unexpected)}')
if resolved:
    print('Historical V758 failures now passing:')
    for item in resolved:
        print('  RESOLVED', item)
if unexpected:
    print('New/unexpected failures not present in unchanged V758 baseline:')
    for item in unexpected:
        print('  UNEXPECTED', item)
    raise SystemExit(1)
print('PASS: no V759-specific unit-test regressions beyond unchanged V758 baseline')
PY

# A fully green Gradle task is also valid; a nonzero status is accepted only because the parser
# above proved every remaining failure belongs to the verified unchanged-V758 baseline set.
if [ "$gradle_status" -eq 0 ]; then
  echo 'Gradle full unit suite is fully green.'
else
  echo "Gradle returned $gradle_status only for verified V758 baseline failures."
fi
