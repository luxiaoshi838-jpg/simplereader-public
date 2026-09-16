from pathlib import Path

reader_path = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
reader = reader_path.read_text(encoding='utf-8')
old_gesture = 'if (kotlin.math.abs(visibleIndex - start.pageIndex) <= 1) return'
new_gesture = 'if (kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA) return'
old_explicit = 'kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > 1'
new_explicit = 'kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA'
for old, new in ((old_gesture, new_gesture), (old_explicit, new_explicit)):
    if old not in reader and new not in reader:
        raise SystemExit(f'missing ReaderActivity anchor: {old}')
    reader = reader.replace(old, new)

constant_anchor = 'private const val VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L'
constant_line = 'private const val VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20'
if constant_line not in reader:
    if constant_anchor not in reader:
        raise SystemExit('missing rollback visible constant anchor')
    reader = reader.replace(constant_anchor, constant_line + '\n        ' + constant_anchor)
reader_path.write_text(reader, encoding='utf-8')

# Migrate the superseded v791 contract so the inherited full suite checks the current behavior.
v791_test_path = Path('app/src/test/java/com/simplereader/app/ui/ReaderV791VerticalRollbackContractTest.kt')
v791 = v791_test_path.read_text(encoding='utf-8')
v791 = v791.replace(
    'reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= 1")',
    'reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA")'
)
v791 = v791.replace(
    'reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > 1")',
    'reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA")'
)
if 'VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20' not in v791:
    v791 = v791.replace(
        'assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))',
        'assertTrue(reader.contains("VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20"))\n        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))'
    )
v791_test_path.write_text(v791, encoding='utf-8')

v792_test = '''package com.simplereader.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderV792RollbackThresholdContractTest {
    private val reader = File("src/main/java/com/simplereader/app/ui/ReaderActivity.kt").readText()

    @Test fun `rollback is offered only after more than twenty pages`() {
        assertTrue(reader.contains("VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20"))
        assertTrue(reader.contains("kotlin.math.abs(visibleIndex - start.pageIndex) <= VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
        assertTrue(reader.contains("kotlin.math.abs(currentPageIndex - rollbackOrigin.pageIndex) > VERTICAL_ROLLBACK_MIN_PAGE_DELTA"))
    }

    @Test fun `rollback placement and lifetime stay unchanged`() {
        assertTrue(reader.contains("snackbar.setAnchorView(readerControls)"))
        assertTrue(reader.contains("snackbar.setAction(\\\"↩︎ 回撤\\\")"))
        assertTrue(reader.contains("VERTICAL_ROLLBACK_VISIBLE_MS = 3_000L"))
    }
}
'''
Path('app/src/test/java/com/simplereader/app/ui/ReaderV792RollbackThresholdContractTest.kt').write_text(v792_test, encoding='utf-8')

maintenance = '''# V792 回撤触发阈值调整

## 用户反馈
V791 的“跨越超过 1 页即提示回撤”过于敏感，正常快速阅读也容易出现回撤浮层。

## 调整
- 回撤只在一次完整移动跨越 **超过 20 页** 时触发。
- 跨越 20 页及以内不提示。
- 手势大移动与目录/搜索/章节等显式跳转使用同一阈值。
- 3 秒自动消失、锚定阅读下栏上方、保存 sourceOffset + viewport offset、触摸即刹车、5 秒 stuck-settling 保险均保持 V791 行为不变。

## 实现
统一常量：`VERTICAL_ROLLBACK_MIN_PAGE_DELTA = 20`。
判断口径：`abs(targetPage - originPage) > 20` 才显示“↩︎ 回撤”。

## 验证
- 新增 `ReaderV792RollbackThresholdContractTest`。
- 迁移 V791 历史契约中已被 V792 明确取代的阈值断言。
- 运行 Debug/Release 定向测试、继承全量 baseline、Debug/Release 构建、APK 版本校验。
'''
Path('maintenance/V792_ROLLBACK_THRESHOLD.md').write_text(maintenance, encoding='utf-8')

main_log = Path('TXT_READER_RENDERING_MAINTENANCE_LOG.md')
log = main_log.read_text(encoding='utf-8')
entry = '''\n\n## v792 — 回撤阈值降敏\n- 用户反馈 v791 的回撤提示过于敏感。\n- 统一将手势大移动和显式跳转的回撤阈值改为：**跨越超过 20 页才触发**；20 页及以内不触发。\n- 3 秒自动消失、下栏上方 Snackbar、sourceOffset/viewportOffset 恢复、触摸刹车和 settling 保险保持不变。\n- 新增 `ReaderV792RollbackThresholdContractTest`，并迁移被新版本行为取代的 v791 阈值契约。\n'''
if '## v792 — 回撤阈值降敏' not in log:
    main_log.write_text(log.rstrip() + entry, encoding='utf-8')

print('v792 rollback threshold patch applied')