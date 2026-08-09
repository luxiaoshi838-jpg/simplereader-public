from pathlib import Path
import re

reader = Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt')
s = reader.read_text(encoding='utf-8')

if 'import android.view.Window\n' not in s:
    s = s.replace('import android.view.View\n', 'import android.view.View\nimport android.view.Window\n', 1)

old = '        WindowCompat.setDecorFitsSystemWindows(window, false)\n        setContentView(R.layout.activity_reader)'
new = '        WindowCompat.setDecorFitsSystemWindows(window, false)\n        supportRequestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY)\n        setContentView(R.layout.activity_reader)'
if old in s:
    s = s.replace(old, new, 1)
elif 'supportRequestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY)' not in s:
    raise SystemExit('missing onCreate insertion point')

if 'private var readerInsetsApplied = false' not in s:
    marker = '    private var navigationBarInsetPx = 0\n'
    if marker not in s:
        raise SystemExit('missing inset field marker')
    s = s.replace(marker, marker + '    private var readerInsetsApplied = false\n', 1)

old = '        readerControls.visibility = if (visible) View.VISIBLE else View.GONE'
new = '        readerControls.visibility = if (visible) View.VISIBLE else View.INVISIBLE'
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit('missing readerControls visibility line')

old_block = '''    private fun bindReaderInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(readerRoot) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (statusTop != statusBarInsetPx || navigationBottom != navigationBarInsetPx) {
                statusBarInsetPx = statusTop
                navigationBarInsetPx = navigationBottom
                applyReaderContentPadding()
                val stableOffset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset
                if (document != null && readerBook != null) paginateAndDisplay(stableOffset)
            }
            insets
        }
        ViewCompat.requestApplyInsets(readerRoot)
    }
'''
new_block = '''    private fun bindReaderInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(readerRoot) { _, insets ->
            if (!readerInsetsApplied) {
                readerInsetsApplied = true
                statusBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                navigationBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                applyReaderContentPadding()
            }
            insets
        }
        ViewCompat.requestApplyInsets(readerRoot)
    }
'''
if old_block in s:
    s = s.replace(old_block, new_block, 1)
elif new_block not in s:
    raise SystemExit('missing bindReaderInsets block')
reader.write_text(s, encoding='utf-8')

layout = Path('app/src/main/res/layout/activity_reader.xml')
x = layout.read_text(encoding='utf-8')
pattern = re.compile(r'(<LinearLayout\s+android:id="@\+id/readerControls".*?android:visibility=")gone(".*?>)', re.S)
x2, count = pattern.subn(r'\1invisible\2', x, count=1)
if count == 0 and 'android:id="@+id/readerControls"' in x and 'android:visibility="invisible"' not in x:
    raise SystemExit('readerControls layout visibility not patched')
layout.write_text(x2, encoding='utf-8')

guard = Path('READER_FORBIDDEN_REGRESSIONS.md')
g = guard.read_text(encoding='utf-8')
rule = '''
### 上下栏不得改变阅读区几何尺寸
- 顶部 ActionBar 必须使用 overlay 模式；显示/隐藏不得压缩或扩张正文区域。
- `readerControls` 隐藏必须使用 `INVISIBLE`，不得用 `GONE ↔ VISIBLE` 触发布局尺寸变化。
- WindowInsets 只允许首次建立状态栏/导航栏固定安全边界；后续上下栏显示/隐藏不得再次修改正文 padding/margin/height，不得 `requestLayout()`，不得重新分页或补偿 scrollY。
- 禁止恢复任何“上下栏出现时缩短阅读页、消失时再放大阅读页”的实现。
'''
if '### 上下栏不得改变阅读区几何尺寸' not in g:
    guard.write_text(g.rstrip() + '\n' + rule, encoding='utf-8')
