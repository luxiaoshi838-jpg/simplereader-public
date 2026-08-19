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
