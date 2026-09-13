# v786 异常日志历史、全书架缓存让行与阅读历史排版修复

## 用户反馈

v785 实机使用中确认“全书架无目录书籍缓存”执行期间存在异常退出风险；同时任务结束后仍反复出现异常日志弹窗，并且阅读历史页面书籍排列过密，不符合正常书架三列排版。

本次将两个问题拆开处理：

1. 真正的新异常退出必须与“同一份旧 pending 日志反复展示”区分；
2. 全书架无目录缓存的大型分页任务不得与前台阅读器长期并发占用内存。

## 1. 最近 20 条异常退出历史

`CrashLogStore` 新增持久化异常历史目录 `crash_history_v786`，最多保存最近 20 条不同的异常记录。

- 新 pending 异常启动后被消费进历史，并立即删除 one-shot pending 文件；
- 同一份异常内容以 SHA-256 指纹去重，不会因为用户没有复制而在每次启动时重新当作“新异常”弹出；
- 第 21 条开始自动淘汰最旧记录；
- 复制日志不会删除历史；
- 书架“更多”菜单新增“异常日志（最近20条）”，可查看并复制历史记录；
- 旧版本遗留 pending 日志在升级轮转时也会先进入历史记录。

因此从 v786 起，如果任务完成后再次出现“新异常”弹窗，它代表新的异常记录，而不是 v785 那种未复制 pending 日志的重复展示。

## 2. 全书架无目录书籍缓存与前台阅读互斥

原实现只在进入每一本书之前等待前台阅读器空闲；一旦 `PageEngine.paginate()` 已开始，用户随后进入阅读器时，后台整书分页不会立即让行，可能造成两套大型文档/页表同时存在。

v786 调整为：

- 每一本重任务开始前再次检查 `ReaderRuntimeState.isReaderForeground()`；
- 正在进行的 `PageEngine.paginate()` 使用既有 `shouldCancel` 协作取消点持续检查前台阅读状态；
- 前台阅读器出现时，当前缓存书籍停止本轮重分页，Worker 释放 book claim，并通过 `Result.retry()` 保留 checkpoint，不把当前书错误计入完成；
- 用户退出阅读后，WorkManager 按 checkpoint 继续；
- TXT 分页不再无意义创建 `ReaderImageRepository`；EPUB 创建的图片仓库在每本书结束时显式 `clear()`；
- `VirtualMachineError`（包含 `OutOfMemoryError`）、`LinkageError`、`ThreadDeath` 不再被 `runCatching` 当成普通“单本失败”吞掉并继续处理；
- 任务真正完成时记录 `shelf_cache_complete` 内存快照，便于区分“任务仍在运行”和“任务已结束后的新异常”。

已有 v771 的 Reader/Worker 同一本书所有权 handoff 保持不变。

## 3. 阅读历史三列间距

历史页继续使用正常书架的三列卡片尺寸，但把原来会在 `onBindViewHolder()` 中丢失的 3dp 左右、18dp 底部间距移到稳定的 ViewHolder 容器上。因此阅读历史、普通书籍和分组卡片使用一致的正常书架视觉间距。

## 4. 回归保护

新增：

- `tools/apply-v786-crash-cache-history.py`
- `tools/v786-crash-cache-history-gates.sh`
- `tools/v786-emulator-launch-test.sh`

同时把 `CrashLogAndCoverContractTest` 从旧的“复制并清除”契约更新为“新异常只展示一次 + 最近20条长期保存”的 v786 契约。

继承的 v785 门禁曾写死版本号 785，已改为纯行为门禁，避免后续版本误失败；v785 的实际滑块层级行为没有改变。

## 5. 最终验收

正式发布验证：GitHub Actions run #169，run ID `34757829201`。

全部通过：

- 52 项历史稳定性门禁；
- v771 handoff / v773 fast-scroll 安全门禁；
- v781、v782 阅读缓存/第一页回归；
- v783-v785 滑块/层级回归；
- v786 20 条异常历史、缓存前台让行、阅读历史排版专项门禁；
- `PageEngineCancellationTest`、`CompatiblePageCacheOpenContractTest`、`PageEngineFirstPagePreviewTest`；
- 完整单测基线；
- Release 构建；
- Android 35 Release 实际安装与冷启动，结果 `V786_ANDROID35_RELEASE_COLD_LAUNCH_PASS`；
- APK 包名/版本校验。

版本：

- package: `com.simplereader.app`
- versionCode: `2098000786`
- versionName: `786`

正式签名：`SimpleReader Public V1`

证书 SHA-256：

`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

最终正式签名 APK SHA-256：

`d5f42996fd50b34011aa88bc89d8e5c905ce3dd3fd2616425895084e72b74e9b`

签名方案：v2=true，v3=true；APK ZIP 完整性检查通过。
