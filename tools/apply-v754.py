from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing patch anchor in {path}: {old[:120]!r}")
    p.write_text(s.replace(old, new, count))


# Version and catalog rule.
replace('app/build.gradle.kts', '"2098000753"', '"2098000754"')
replace('app/build.gradle.kts', '?: 2098000753', '?: 2098000754')
replace('app/build.gradle.kts', '?: "753"', '?: "754"')
replace('app/src/main/java/com/simplereader/app/parser/TxtParser.kt',
        'const val CATALOG_RULE_VERSION = 111', 'const val CATALOG_RULE_VERSION = 112')

d = 'app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt'
replace(d, 'const val RULE_VERSION = 111', 'const val RULE_VERSION = 112')
replace(d,
        '    private val wrapped = Regex("[（(]\\s*($NUM)\\s*[）)]")',
        '    private val wrappedLeadingUnit = Regex("^[（(]\\s*$NUM\\s*[）)]\\s*(?:单元|章|节|篇|部|卷|回|集)(?:\\s*[:：、.．—-]?\\s*.*)?$")\n'
        '    private val wrappedChineseSuffix = Regex("^[\\p{IsHan}]{1,20}[（(]\\s*$NUM\\s*[）)]$")\n'
        '    private val numericOnly = Regex("^\\s*$NUM\\s*$")')
replace(d,
        '        if (\'“\' in s || \'”\' in s || s.contains("http", ignoreCase = true)) return null\n',
        '        if (\'“\' in s || \'”\' in s || s.contains("http", ignoreCase = true)) return null\n'
        '        if (numericOnly.matches(s)) return null\n')
replace(d,
        "        for (m in diZhangHuaHui.findAll(s)) {\n            val unit = m.groupValues[1].firstOrNull() ?: continue\n            if (unit == '回' && m.range.first != 0) return s\n            if (!hasTerminator(s)) return s\n        }",
        "        for (m in diZhangHuaHui.findAll(s)) {\n            if (!hasTerminator(s)) return s\n        }")
replace(d,
        '        for (m in wrapped.findAll(s)) {\n            if (m.range.first == 0) {\n                if (!hasTerminator(s)) return s\n            } else if (!hasTerminatorIgnoringSeparatorAt(s, m.range.last + 1)) {\n                return s\n            }\n        }',
        '        if (wrappedLeadingUnit.matches(s) && !hasTerminator(s)) return s\n'
        '        if (wrappedChineseSuffix.matches(s) && !hasTerminator(s)) return s')

# Reader behavior.
r = 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
replace(r,
        '    private var suspendedAnchorViewportPx: Int? = null\n',
        '    private var suspendedAnchorViewportPx: Int? = null\n    private var lastStableSourceOffset: Int? = null\n')
replace(r,
        '        autoReading = true\n        autoReadAwaitingPageCommit = false\n        showAutoReadStopButton()',
        '        autoReading = true\n        autoReadAwaitingPageCommit = false\n        setReaderChromeVisible(false)\n        showAutoReadStopButton()')
replace(r,
        '            val listView = ListView(this@ReaderActivity)\n',
        '            val listView = ListView(this@ReaderActivity).apply {\n                isFastScrollEnabled = true\n                isFastScrollAlwaysVisible = true\n            }\n')
replace(r,
        '                    val labels = visible.mapIndexed { rowIndex, (chapterIndex, chapter) ->\n                        val page = paged.firstPageOfChapter(chapterIndex)\n                        val label = "${rowIndex + 1}.${chapter.title}\\n第 ${page + 1}/${paged.pages.size} 页"',
        '                    val labels = visible.map { (chapterIndex, chapter) ->\n                        val page = paged.firstPageOfChapter(chapterIndex)\n                        val label = "${chapter.title}\\n第 ${page + 1}/${paged.pages.size} 页"')
replace(r,
        '                val stableOffset = preserveOffset\n                    ?: progress?.startOffset',
        '                val stableOffset = preserveOffset\n                    ?: lastStableSourceOffset\n                    ?: progress?.startOffset')
replace(r,
        '                currentPageIndex = target.coerceIn(0, paged.pages.lastIndex)\n                paginationInProgress = false',
        '                currentPageIndex = target.coerceIn(0, paged.pages.lastIndex)\n                lastStableSourceOffset = paged.pages.getOrNull(currentPageIndex)?.startOffset\n                paginationInProgress = false')
replace(r,
        '                currentPageIndex = target\n                bindHorizontalPages()',
        '                currentPageIndex = target\n                lastStableSourceOffset = pages[target].startOffset\n                bindHorizontalPages()')
replace(r,
        '        currentPageIndex = page.globalPageIndex\n        continuousWindowStartOffset = page.startOffset',
        '        currentPageIndex = page.globalPageIndex\n        lastStableSourceOffset = page.startOffset\n        continuousWindowStartOffset = page.startOffset')
replace(r,
        '        currentPageIndex = index\n        continuousWindowStartOffset = pages[index].startOffset',
        '        currentPageIndex = index\n        lastStableSourceOffset = pages[index].startOffset\n        continuousWindowStartOffset = pages[index].startOffset')
replace(r,
        '        currentPageIndex = index.coerceIn(0, pages.lastIndex)\n        if (pageTurnMode == TURN_MODE_VERTICAL)',
        '        currentPageIndex = index.coerceIn(0, pages.lastIndex)\n        lastStableSourceOffset = pages[currentPageIndex].startOffset\n        if (pageTurnMode == TURN_MODE_VERTICAL)')
replace(r,
        '        return paged.pages.getOrNull(currentPageIndex)?.startOffset ?: 0\n',
        '        return paged.pages.getOrNull(currentPageIndex)?.startOffset\n            ?: lastStableSourceOffset?.coerceIn(0, paged.text.length)\n            ?: 0\n')

old_focus = '''    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val rv = verticalRecyclerView
        if (!hasFocus) {
            verticalWindowSuspended = true
            stopAutoReading(false)
            rv?.stopScroll()
        } else if (rv != null) {
            verticalWindowSuspended = true
            rv.stopScroll()
            rv.postOnAnimation {
                rv.stopScroll()
                verticalWindowSuspended = false
            }
        } else {
            verticalWindowSuspended = false
        }
    }
'''
new_focus = '''    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val rv = verticalRecyclerView
        if (!hasFocus) {
            if (rv != null && pageTurnMode == TURN_MODE_VERTICAL) {
                val pages = readerBook?.pages.orEmpty()
                val index = verticalLayoutManager?.findFirstVisibleItemPosition() ?: -1
                if (index in pages.indices) {
                    suspendedAnchorOffset = pages[index].startOffset
                    suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0
                    lastStableSourceOffset = suspendedAnchorOffset
                }
            }
            verticalWindowSuspended = true
            stopAutoReading(false)
            rv?.stopScroll()
        } else if (rv != null && pageTurnMode == TURN_MODE_VERTICAL) {
            verticalWindowSuspended = true
            rv.stopScroll()
            val anchorOffset = suspendedAnchorOffset
            val viewportOffset = suspendedAnchorViewportPx ?: 0
            if (anchorOffset != null) {
                val pageIndex = readerBook?.pageForOffset(anchorOffset)?.globalPageIndex
                if (pageIndex != null) {
                    verticalProgrammaticScroll = true
                    currentPageIndex = pageIndex
                    lastStableSourceOffset = anchorOffset
                    verticalLayoutManager?.scrollToPositionWithOffset(pageIndex, viewportOffset)
                }
            }
            rv.postOnAnimation {
                rv.stopScroll()
                verticalProgrammaticScroll = false
                verticalWindowSuspended = false
                suspendedAnchorOffset = null
                suspendedAnchorViewportPx = null
            }
        } else {
            verticalWindowSuspended = false
            suspendedAnchorOffset = null
            suspendedAnchorViewportPx = null
        }
    }
'''
replace(r, old_focus, new_focus)

old_fallback = '''    private fun showContinuousFallback(reason: String) {
        val loaded = document ?: return showFatal(reason)
        pagedReaderView.visibility = View.GONE
        readerScrollView.visibility = View.VISIBLE
        continuousWindowStartOffset = 0
        continuousWindowEndOffset = loaded.text.length.coerceAtMost(CONTINUOUS_FALLBACK_CHARS)
        continuousTextView.text = loaded.text.substring(continuousWindowStartOffset, continuousWindowEndOffset)
        continuousTextView.textSize = readerTextSizeSp
        continuousTextView.setTextColor(activePalette().textColor)
        readerScrollView.background = activeBackgroundDrawable()
        progressLabel.text = "可读模式"
        Toast.makeText(this, "分页失败，已打开有限文本窗口：$reason", Toast.LENGTH_LONG).show()
    }
'''
new_fallback = '''    private fun showContinuousFallback(reason: String) {
        val loaded = document ?: return showFatal(reason)
        val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset()).coerceIn(0, loaded.text.length)
        var start = (anchor - CONTINUOUS_FALLBACK_CHARS / 2).coerceAtLeast(0)
        val end = (start + CONTINUOUS_FALLBACK_CHARS).coerceAtMost(loaded.text.length)
        start = (end - CONTINUOUS_FALLBACK_CHARS).coerceAtLeast(0)
        pagedReaderView.visibility = View.GONE
        readerScrollView.visibility = View.VISIBLE
        continuousWindowStartOffset = start
        continuousWindowEndOffset = end
        continuousTextView.text = loaded.text.substring(start, end)
        continuousTextView.textSize = readerTextSizeSp
        continuousTextView.setTextColor(activePalette().textColor)
        readerScrollView.background = activeBackgroundDrawable()
        progressLabel.text = "可读模式"
        continuousTextView.post {
            val layout = continuousTextView.layout ?: return@post
            val localOffset = (anchor - start).coerceIn(0, continuousTextView.text.length)
            val line = layout.getLineForOffset(localOffset)
            readerScrollView.scrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
        }
        Toast.makeText(this, "分页失败，已在当前位置打开有限文本窗口：$reason", Toast.LENGTH_LONG).show()
    }
'''
replace(r, old_fallback, new_fallback)

# Move auto-stop button inside guarded viewport.
x = Path('app/src/main/res/layout/activity_reader.xml')
s = x.read_text()
stop = '''    <TextView
        android:id="@+id/autoReadStopButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_gravity="top|end"
        android:layout_marginTop="8dp"
        android:layout_marginEnd="58dp"
        android:background="@android:drawable/btn_default_small"
        android:gravity="center"
        android:text="停"
        android:textColor="#FFFFFF"
        android:textSize="16sp"
        android:visibility="gone" />

'''
if stop not in s:
    raise SystemExit('stop button XML anchor missing')
s = s.replace(stop, '', 1)
anchor = '''        <View
            android:id="@+id/readerBottomHaze"
            android:layout_width="match_parent"
            android:layout_height="28dp"
            android:layout_gravity="bottom"
            android:visibility="gone" />
    </FrameLayout>
'''
insert = '''        <View
            android:id="@+id/readerBottomHaze"
            android:layout_width="match_parent"
            android:layout_height="28dp"
            android:layout_gravity="bottom"
            android:visibility="gone" />

        <TextView
            android:id="@+id/autoReadStopButton"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_gravity="top|end"
            android:layout_marginTop="8dp"
            android:layout_marginEnd="10dp"
            android:background="@android:drawable/btn_default_small"
            android:gravity="center"
            android:text="停"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:visibility="gone" />
    </FrameLayout>
'''
if anchor not in s:
    raise SystemExit('readerViewport XML anchor missing')
x.write_text(s.replace(anchor, insert, 1))

# Rule112 executable regression test.
Path('app/src/test/java/com/simplereader/app/parser/TxtCatalogRule112Test.kt').write_text(r'''package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxtCatalogRule112Test {
    @Test fun keepsRequiredTitles() {
        assertEquals("大道之上（一）", TxtParser.extractStructuredChapterTitle("大道之上（一）"))
        assertEquals("大道之上（1）", TxtParser.extractStructuredChapterTitle("大道之上（1）"))
        assertEquals("一章 重逢", TxtParser.extractStructuredChapterTitle("一章 重逢"))
        assertEquals("12、归来", TxtParser.extractStructuredChapterTitle("12、归来"))
        assertEquals("（十二）篇 初遇", TxtParser.extractStructuredChapterTitle("（十二）篇 初遇"))
    }

    @Test fun rejectsFalseCatalogLines() {
        assertNull(TxtParser.extractStructuredChapterTitle("Road（1）"))
        assertNull(TxtParser.extractStructuredChapterTitle("大道 之上（一）"))
        assertNull(TxtParser.extractStructuredChapterTitle("这是正文（2026）吗"))
        assertNull(TxtParser.extractStructuredChapterTitle("2026"))
        assertNull(TxtParser.extractStructuredChapterTitle("这是正文。第12回 继续说"))
        assertNull(TxtParser.extractStructuredChapterTitle("第12章。这是正文"))
    }
}
''')

# Gates 35-40 extend all 34 inherited v753 gates.
Path('tools/v754-40-gates.sh').write_text(r'''#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v753-34-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
D=app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt
T=app/src/main/java/com/simplereader/app/parser/TxtParser.kt
X=app/src/main/res/layout/activity_reader.xml
B=app/build.gradle.kts

grep -Fq '2098000754' "$B" && grep -Fq '"754"' "$B" && grep -Fq 'CATALOG_RULE_VERSION = 112' "$T" && grep -Fq 'RULE_VERSION = 112' "$D" || fail 35 'v754/rule112 versions missing'
pass 35 'v754 version + catalog rule112'

grep -Fq 'wrappedChineseSuffix' "$D" && grep -Fq 'wrappedLeadingUnit' "$D" && grep -Fq 'numericOnly.matches(s)' "$D" || fail 36 'rule112 false-positive guards missing'
! grep -Fq "unit == '回' && m.range.first != 0" "$D" || fail 36 'embedded 回 terminator bypass returned'
pass 36 'rule112 parenthetical/numeric/terminator guards'

grep -Fq 'isFastScrollEnabled = true' "$R" && grep -Fq 'isFastScrollAlwaysVisible = true' "$R" || fail 37 'catalog-only fast scroll missing'
! grep -Fq '${rowIndex + 1}.${chapter.title}' "$R" || fail 37 'artificial catalog row numbering returned'
grep -Fq 'jumpToPage(paged.firstPageOfChapter(chapterIndex), false)' "$R" || fail 37 'catalog click navigation missing'
pass 37 'catalog scroll only browses catalog; click navigates body; no artificial numbering'

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root=ET.parse('app/src/main/res/layout/activity_reader.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
parent={c:p for p in root.iter() for c in p}
stop=next(e for e in root.iter() if e.get(ns+'id')=='@+id/autoReadStopButton')
viewport=next(e for e in root.iter() if e.get(ns+'id')=='@+id/readerViewport')
assert parent[stop] is viewport
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
s=r.index('    private fun startAutoReading()')
e=r.index('    private fun scheduleAutomaticPageTurn()',s)
b=r[s:e]
assert 'setReaderChromeVisible(false)' in b
assert b.index('setReaderChromeVisible(false)') < b.index('showAutoReadStopButton()')
PY
pass 38 'auto-reading hides chrome; stop control is inside guarded viewport'

for token in 'suspendedAnchorOffset = pages[index].startOffset' 'suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0' 'scrollToPositionWithOffset(pageIndex, viewportOffset)' 'lastStableSourceOffset = anchorOffset'; do
  grep -Fq "$token" "$R" || fail 39 "focus anchor behavior missing: $token"
done
pass 39 'focus loss captures/restores source + pixel anchor'

grep -Fq '?: lastStableSourceOffset' "$R" || fail 40 'stable source fallback missing'
grep -Fq 'val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset())' "$R" || fail 40 'fallback not anchored'
python3 - <<'PY'
from pathlib import Path
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
s=r.index('    private fun showContinuousFallback(')
e=r.index('    private fun sourceOffsetAtScroll(',s)
b=r[s:e]
assert 'continuousWindowStartOffset = 0' not in b
assert 'loaded.text.substring(start, end)' in b
PY
pass 40 'pagination/fallback cannot hard-reset active reading to page 1'
printf 'ALL_40_GATES_PASS\n'
''')
Path('tools/v754-40-gates.sh').chmod(0o755)

# Final reusable v754 build workflow.
Path('.github/workflows/android-release-v2.yml').write_text(r'''name: Build 简阅 v754

on:
  workflow_dispatch:

permissions:
  contents: read

env:
  SIMPLE_READER_VERSION_CODE: "2098000754"
  SIMPLE_READER_VERSION_NAME: "754"
  APK_PATH: "app/build/outputs/apk/release/SimpleReader_v754_github_unsigned.apk"
  MAX_APK_BYTES: "52428800"

jobs:
  build-apk:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - run: chmod +x ./gradlew tools/v754-40-gates.sh
      - name: Run v754 40 gates
        run: bash tools/v754-40-gates.sh
      - name: Test and build v754
        shell: bash
        run: |
          set -o pipefail
          ./gradlew testDebugUnitTest assembleRelease --stacktrace 2>&1 | tee gradle-build.log
      - name: Verify APK
        shell: bash
        run: |
          set -euo pipefail
          src="app/build/outputs/apk/release/app-release-unsigned.apk"
          [ -f "$src" ] || src="app/build/outputs/apk/release/app-release.apk"
          cp "$src" "$APK_PATH"
          build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
          "${build_tools}/aapt2" dump badging "$APK_PATH" | tee apk-package-info.txt
          grep -Fq "versionCode='2098000754'" apk-package-info.txt
          grep -Fq "versionName='754'" apk-package-info.txt
          grep -Fq "minSdkVersion:'26'" apk-package-info.txt
          test "$(stat -c %s "$APK_PATH")" -le "$MAX_APK_BYTES"
          sha256sum "$APK_PATH" | tee apk-sha256.txt
      - uses: actions/upload-artifact@v4
        with:
          name: SimpleReader-v754-${{ github.run_number }}
          path: |
            ${{ env.APK_PATH }}
            apk-sha256.txt
            apk-package-info.txt
            gradle-build.log
          if-no-files-found: error
          retention-days: 7
''')

# Do not retain the one-shot helper in the formal v754 tree.
Path('tools/apply-v754.py').unlink()
