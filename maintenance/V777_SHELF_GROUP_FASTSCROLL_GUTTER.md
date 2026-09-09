# v777 书架/分组滑块独立右侧通道

## 用户反馈
书架和分组的快速滚动滑块虽然右侧仍有空白，却贴着分组/书籍内容，容易在操作内容时误触滑块。

## 根因
v776 的 `ShelfFastScroller` 使用 `width - paddingRight - edgeInset` 计算滑块横坐标。主书架本身已有右侧 padding，因此滑块被 padding 再次向左推到内容边缘；分组 RecyclerView 又没有为滑块提供一致的内部右侧通道。旧触摸热区还会在滑块左侧额外扩张，进一步增加误触风险。

## v777 修复
- 主书架 RecyclerView：`paddingEnd` 统一为 28dp。
- 分组 RecyclerView：右侧 padding 统一为 28dp。
- `ShelfFastScroller` 的滑块与轨道改为锚定 RecyclerView 外侧右边缘：`width - edgeInset`，不再扣除 `paddingRight` 后向内容区偏移。
- 滑块宽度保持 10dp，右边缘 inset 为 4dp，因此滑块位于专门的 28dp 右侧通道中。
- 横向触摸扩展缩小为 4dp，并以 `contentRight = width - paddingRight` 为硬边界；滑块触摸热区绝不向左进入书籍/分组内容区。
- 纵向触摸余量继续保留，避免滑块本身变得难抓。
- 原有行为保持：只有实际滚动后滑块才显示/可抓，停止约 1.2 秒后失活。
- 阅读页未修改。

## 验证
GitHub Actions `Build 简阅 v777`，run `34338599265`，run number `136`：全部通过。

通过项目：
- 继承 52 项稳定性门禁
- 选择/触摸共存门禁
- 即时选择门禁
- 设置布局门禁
- v777 书架/分组滑块通道专项门禁
- 阅读页相对 v776 零差异检查
- 完整单测既有失败基线检查
- Release 构建
- Android 35 Release 实际安装与冷启动
- APK 包信息检查

## 正式签名
- versionName: 777
- versionCode: 2098000777
- package: com.simplereader.app
- 签名证书: SimpleReader Public V1
- 证书 SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- 最终 APK SHA-256: `9064070e4277838673bdfcbcc0badc29cd2c77bba5df2af6c12b7e7bacd56125`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- ZIP 完整性: PASS
