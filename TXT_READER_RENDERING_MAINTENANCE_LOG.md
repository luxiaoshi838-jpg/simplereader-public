# TXT 阅读正文渲染维护日志

## 2026-08-19 — V683

### 问题
目录标题已经正常清理空白，但正文章节标题前仍出现大块空白。

### 真正根因
不是 TXT 原文空格，也不是目录标题清洗失败。

`PageEngine.styledWholeText()` 会给每个识别出的真实章节标题应用 `ChapterTopSpacingSpan`，其额外顶部高度原先为：

`textSizePx × lineSpacingMultiplier × 1.8`

当 TXT 目录规则扩大、更多标题被正确识别后，这个额外顶部留白也会在更多章节出现，因此视觉上像“章节名前莫名其妙有很多空格”。

### V683 固定修法
- 保留章节标题的 `RelativeSizeSpan`（字号放大）。
- 保留章节标题的 `StyleSpan(Typeface.BOLD)`（加粗）。
- 将 `ChapterTopSpacingSpan` 的额外高度乘数从 `1.8f` 改为 `0f`，等价于取消额外章节顶部留白。
- 不修改原 TXT。
- 不修改章节 `startOffset/endOffset`。
- 不修改目录识别规则。
- 不修改分页缓存格式。
- 不修改阅读进度逻辑。

### 后续排查规则
如果以后再次出现“目录正常但正文章节前大块空白”：
1. 先检查 `PageEngine.styledWholeText()` 是否重新引入 `ChapterTopSpacingSpan` 或其他 `LineHeightSpan`。
2. 不要先改目录正则、目录版本或缓存版本。
3. 区分两类问题：
   - 字符空白：由 `CatalogTitleNormalizerV103` / `ReaderBodyTitleNormalizerV104` 处理。
   - 视觉顶部留白：由 `ChapterTopSpacingSpan` / `LineHeightSpan` 处理。
4. 发布前确认章节标题仍然放大/加粗，但标题前不再增加额外行高。

## 2026-09-04 — V757

### 问题
V756 仍可能出现：
- 阅读过程中突然卡死，页面不能继续操作；
- 强制退出或返回书架后再次进入，阅读位置退回到前面章节；
- 不是固定回第一页，而是恢复到较早的“稳定”章节位置。

### 根因
1. V756 为“搜索命中高亮在手动滑动后消失”新增的 RecyclerView 处理，在开始拖动时执行了全量渲染缓存清空并重新绑定当前可见页。复杂章节/长文本下，这会迫使 `PageEngine.styledText()` 在主线程重新生成页面内容，存在卡住 RecyclerView 手势/布局的风险。
2. 卡顿或失焦发生时，焦点恢复链中的 `suspendedAnchorOffset` 可能覆盖 `lastStableSourceOffset`，导致“稳定锚点”被较早位置污染。
3. 仅在退出时保存进度仍不足以覆盖异常退出场景，需要在真实滚动稳定后做低频 checkpoint。

### V757 修法
- 保留 V632 起的 `RecyclerView + LinearLayoutManager + ReaderPage` 虚拟化连续阅读架构，禁止退回旧 `NestedScrollView + 单 TextView` 主路径。
- 搜索高亮清除不再使用 `rendered.evictAll() + notifyItemRangeChanged()` 的全缓存/重绑定方式。
- 只清除命中页对应的缓存，并直接移除当前可见 TextView 上的搜索高亮 span；清除高亮不得触发滚动、跳页或 RecyclerView 重新布局。
- `lastStableSourceOffset` 只由真实用户滚动、显式跳转或明确程序化导航更新；失焦时保存的 suspended anchor 不再反向覆盖稳定锚点。
- 真实垂直滚动更新位置后，加入延迟约 600 ms 的低频进度 checkpoint，降低异常退出后退回前面章节的风险。
- 保留 V756 的 900 ms 锁状态兜底释放，不允许 `verticalWindowSuspended / verticalProgrammaticScroll` 永久锁死阅读器。
- 保留搜索跳转、rule113 目录规则、Android 16 WorkManager 修复。

### 禁止回归
- 禁止在用户拖动开始时调用 `rendered.evictAll()` 后再 `notifyItemRangeChanged()` 清搜索高亮。
- 禁止高亮清除函数调用 `scrollToPosition`、`scrollToPositionWithOffset`、`smoothScroll`、`scrollBy` 或 `jumpToPage`。
- 禁止失焦事件把 `suspendedAnchorOffset` 写回并覆盖 `lastStableSourceOffset`。
- 禁止退出时仅依赖陈旧 `currentPageIndex` 保存进度。
- 禁止为修复卡死问题撤销 RecyclerView 虚拟化连续阅读架构。

### GitHub 记录
- 分支：`source-v757`
- V757 相对 `source-v756` 的修改已全部提交到 GitHub 公开仓库。
- V757 新增 `tools/v757-52-gates.sh`，用于锁定卡死修复、稳定进度锚点、高亮清除和原有防回弹架构。
