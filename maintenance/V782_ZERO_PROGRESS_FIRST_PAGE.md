# v782 零进度 / 无完整页表即时第一页修复

## 问题

v781 已能在当前字号分页缓存缺失时复用同一本书的其他字号完整页表，因此“改字号后打开已有分页缓存的另一本文本”不再必须阻塞等待整本分页。

仍遗漏一个明确场景：阅读进度为 0 且该书从未生成过任何完整 page manifest。此时 `showCachedBookImmediately()` 返回 null，`loadBook()` 会回到阻塞式 `paginateAndDisplay(..., backgroundOpen=false)`，因此用户看到“分页中…”而不是正常第一页。

## v782 行为

打开书籍的优先级变为：

1. 当前排版参数的完整页表缓存：直接打开。
2. 同一源文本的其他排版参数完整页表：按 v781 立即打开，并静默生成精确页表。
3. 没有任何完整页表，但阅读进度为空或为 0：调用 `PageEngine.layoutFirstPage()` 只排版第一页，立即绑定到 `PagedReaderView`；同时后台静默生成完整页表。
4. 非零进度且不存在可恢复完整页表：保留原恢复/完整分页路径，避免用无法证明正确的单页锚点覆盖既有阅读位置。

## 安全边界

零进度即时第一页是纯显示快照：

- `readerBook` 在完整页表提交前保持 null；
- 不构造局部 `ReaderBook`；
- 不伪造全局 `pages[]`、`globalPageIndex` 或章节页表；
- `previous` / `next` 均为空，因此不会把单页预览误当成可无限导航的局部分页窗口；
- 完整分页完成后按 source offset=0 原子接管正常全局导航；
- 后台精确分页失败时保留已经显示的第一页，不弹出阻塞式分页错误；
- v780 的 generation / bookId / paginationEpoch / CancellationException 隔离继续生效。

由于早期 16K 预览窗口与正式 `paginateChapter()` 的首个布局窗口可能产生细微分页边界差异，最终实现让 `layoutFirstPage()` 直接复用正式分页器的 `chooseWindowEnd()`，因此即时第一页与正式完整分页使用相同的有界布局窗口和相同 StaticLayout 参数，测试要求首屏 start/end offset 完全一致。

## 防回归

新增：

- `PageEngineFirstPagePreviewTest`
- `tools/v782-zero-progress-first-page-gates.sh`
- `tools/apply-v782-zero-progress-first-page.py`
- `tools/apply-v782-first-page-authoritative-window.py`
- `tools/v782-emulator-launch-test.sh`

同时把继承的 v781 gate / patch 调整为行为契约，不再把后续版本扩展后的 `loadBook()` 精确文本形状误判为回归。

## 发布验收

最终成功发布流水线：GitHub Actions run #156，run id `34435150053`，artifact `SimpleReader-v782-156`（id `10135981019`）。

通过项目：

- 52 项历史稳定性门禁：PASS
- selection/touch 与 instant-selection 门禁：PASS
- v771/v773 启动、ShelfCacheHandoff 与 fast-scroll 门禁：PASS
- v781 跨字号完整缓存即时开书门禁：PASS
- v782 零进度 / 无页表即时第一页门禁：PASS
- PageEngineCancellationTest：PASS
- CompatiblePageCacheOpenContractTest：PASS
- PageEngineFirstPagePreviewTest：PASS
- 完整历史单测基线：PASS
- Release 构建：PASS
- Android 35 实际 Release 安装 / 冷启动：PASS，`V782_ANDROID35_RELEASE_COLD_LAUNCH_PASS`
- APK package：`com.simplereader.app`
- versionCode：`2098000782`
- versionName：`782`

正式签名 APK 使用既有 **SimpleReader Public V1** 证书：

- APK SHA-256：`de27ab267ba2a2aa43fb3d9768a2a3ce6d7b31c3dfa847485fd90dad6a91b24f`
- APK 大小：`5822143` bytes
- v1：false
- v2：true
- v3：true
- v4：false
- 证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- ZIP/APK container integrity：PASS
