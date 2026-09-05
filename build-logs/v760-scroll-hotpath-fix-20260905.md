# 简阅 V760 滚动卡顿修复与正式构建记录（2026-09-05）

## 用户问题
V759 真机再次出现明显滑动卡顿。审计确认 V758 的轻量滚动优化并未被直接删除，但 V759 为异常退出诊断新增的状态记录重新靠近 RecyclerView 滚动热路径，构成性能回归。

## V759 → V760 修复
- 版本：`760`
- versionCode：`2098000760`
- package：`com.simplereader.app`
- 分支：`source-v760`
- 实际源代码持久化提交：`055b059afaaec704a539f426b9ba558d34821812`

### 滚动热路径隔离
1. `verticalOnPageVisible()` 中删除 `CrashLogStore.recordReaderPosition(... vertical_page ...)`；滚动跨页时只更新内存中的页号/sourceOffset、进度UI和原有低频进度 checkpoint。
2. `verticalShouldSuppressReportedIndex()` 不再直接调用 `CrashLogStore.recordEvent()`；发现疑似 row-0 瞬移时只写入内存字符串 `pendingVerticalDiagnosticEvent`。
3. 新增 `verticalOnScrollIdle()`；只有 RecyclerView 进入 `SCROLL_STATE_IDLE` 后，才统一调用 `persistVerticalDiagnosticState()` 保存诊断位置并写入待处理异常事件。
4. `onPause()` 对竖向阅读做一次强制诊断位置落盘，保证异常诊断能力仍保留。
5. 保留 V758 的性能基线：`BREAK_STRATEGY_SIMPLE`、`HYPHENATION_FREQUENCY_NONE`、32页渲染 LRU、RecyclerView item cache=12、prefetch=8。
6. V759 的 Java/Kotlin crash、ApplicationExitInfo、ANR/native/signal/low-memory 等异常退出日志和 recovery offset 机制全部保留，没有为换取流畅度删除诊断功能。

## 新增禁止回归门禁
`tools/v760-scroll-hotpath-gates.sh` 明确要求：
- `VerticalScrollListener.onScrolled()` 中禁止出现 `CrashLogStore`、`recordReaderPosition`、`recordEvent`、`saveProgress`；
- `verticalOnPageVisible()` 中禁止出现 CrashLogStore 持久化调用；
- reset guard 中禁止直接记录磁盘日志；
- 必须只在 `SCROLL_STATE_IDLE` 后调用 `verticalOnScrollIdle()`；
- 必须继续保留 V758 的断行、连字符、LRU、item cache、prefetch 配置。

## GitHub Actions 正式验证
正式 run：`33953926765`，run number `64`，结论：SUCCESS。
时间：2026-09-05T07:56:57Z → 2026-09-05T08:01:59Z，约 5分02秒。

通过步骤：
- V760 inherited 52 stability gates：PASS
- V760 scroll hot-path isolation gates：PASS
- V760 crash/system-exit/recovery gates：PASS
- full unit suite against unchanged V758 failure baseline：PASS（不允许新增 V760 特有失败）
- Release assemble：PASS
- package/version/minSdk/体积校验：PASS
- artifact upload：PASS

超过5分钟后已检查运行状态：全量单测已完成，任务明确推进至 Release 构建，不是死锁；随后正常完成。

GitHub artifact：`SimpleReader-v760-64`，artifact id `9965787822`。
GitHub unsigned APK SHA-256：`6ad85f3e3cdbf46e24a83c91271711febdf13d290b5fdaeb4531e1282e83285f`。

## 正式签名
使用 Google Drive `签名文件/简阅签名文件.zip` 内固定 `SimpleReader Public V1` keystore。签名材料和密码未提交 GitHub。

固定证书 SHA-256：
`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

最终 APK：
- V2：true
- V3：true
- 签名前后 AndroidManifest.xml SHA-256 完全一致
- 4字节对齐：479 个 stored entries，0 个不合格
- 最终 APK SHA-256：`53eb364fe83ba8f52d085ebf9b57492b1d42ad8804a249f0b788b49c5c4c06f4`

覆盖升级条件满足：同包名、同 Public V1 证书、`2098000760 > 2098000759`。

## 本轮失败/错误记录
1. 初始尝试新增 `.github/workflows/v760-apply-hotpath-fix.yml`，GitHub Actions 在进入 job 前即判定工作流无效；未修改业务源码。
2. 第二个临时 apply workflow 同样在 job 前失败；随后两份无效 workflow 都从 `source-v760` 删除。
3. 改为复用已验证的 `android-release-v2.yml` 正式构建链，在该链中先应用补丁、跑三组门禁，再把已验证源码提交回 `source-v760`，最终 run 64 全部通过。
4. 本地签名第一次使用中文 `/tmp` 输出文件名时，Java/apksigner 所在环境把文件名转换为问号字符，导致后续 `cp` 找不到原中文路径。未影响 APK 内容或密钥；改用 ASCII 临时输出名后重新签名并完整验证成功。

## 后续规则
- 后续新增任何诊断、统计或恢复逻辑，都不得进入 `onScrolled()` / `verticalOnPageVisible()` 热路径。
- 如果 V760 真机仍出现卡顿，应优先读取 V760 保留的异常日志并做 frame/drop 或具体渲染页定位，禁止通过撤回已有修复或扩大无界缓存试错。
