from pathlib import Path

p = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = p.read_text(encoding='utf-8')

old_touch = '''                cancelVerticalRollbackBurstReset()
                rv?.stopScroll()
                cancelVerticalSettlingGuard()
'''
new_touch = '''                cancelVerticalRollbackBurstReset()
                rv?.stopScroll()
                // stopScroll() may synchronously emit IDLE and schedule a burst reset.
                // Cancel again so the new real gesture keeps the same short-burst origin.
                cancelVerticalRollbackBurstReset()
                cancelVerticalSettlingGuard()
'''
if old_touch not in s:
    raise SystemExit('missing v793 touch race anchor')
s = s.replace(old_touch, new_touch, 1)

old_jump = '''            ensureVerticalReader()
            verticalRecyclerView?.stopScroll()
            cancelVerticalSettlingGuard()
            verticalProgrammaticScroll = true
'''
new_jump = '''            ensureVerticalReader()
            verticalRecyclerView?.stopScroll()
            // Same synchronous-IDLE protection for rapid chapter/catalog/search jumps.
            cancelVerticalRollbackBurstReset()
            cancelVerticalSettlingGuard()
            verticalProgrammaticScroll = true
'''
if old_jump not in s:
    raise SystemExit('missing v793 jump race anchor')
s = s.replace(old_jump, new_jump, 1)
p.write_text(s, encoding='utf-8')

contract = r'''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV793BurstRaceContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `touch cancels any reset scheduled synchronously by stopScroll`() {
        val start = reader.indexOf("MotionEvent.ACTION_DOWN -> {", reader.indexOf("internal fun verticalHandleTouch"))
        val end = reader.indexOf("if (pageTurnMode == TURN_MODE_VERTICAL", start)
        val block = reader.substring(start, end)
        val stop = block.indexOf("rv?.stopScroll()")
        assertTrue(stop >= 0)
        assertTrue(block.indexOf("cancelVerticalRollbackBurstReset()", stop) > stop)
    }

    @Test fun `explicit jump also clears a reset scheduled by stopScroll`() {
        val start = reader.indexOf("private fun jumpToPage")
        val end = reader.indexOf("private fun jumpChapter", start)
        val block = reader.substring(start, end)
        val stop = block.indexOf("verticalRecyclerView?.stopScroll()")
        assertTrue(stop >= 0)
        assertTrue(block.indexOf("cancelVerticalRollbackBurstReset()", stop) > stop)
    }
}
'''
Path('app/src/test/java/com/simplereader/app/ui/ReaderV793BurstRaceContractTest.kt').write_text(contract, encoding='utf-8')

log_path = Path('TXT_READER_RENDERING_MAINTENANCE_LOG.md')
log = log_path.read_text(encoding='utf-8').rstrip()
entry = '''

### v793 final race guard
- `RecyclerView.stopScroll()` 可能同步触发 IDLE，并重新安排短时间 burst 的清空计时器。
- 因此在真实触摸与显式跳转的 `stopScroll()` 之后再次取消 burst reset，保证新操作不会在 1 秒中途丢失累计起点。
- 该修正不改变 v791 的 stopScroll/settling 保险，也不改变“短时间前后差距超过 20 页”规则。
'''
if '### v793 final race guard' not in log:
    log_path.write_text((log + entry).rstrip() + '\n', encoding='utf-8')
print('v793 burst race fix applied')
