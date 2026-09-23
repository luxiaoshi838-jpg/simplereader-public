# V796 全书架目录缓存后台连续运行修复

## 实机反馈

全书架目录缓存点击后最初可以正常出现书籍总数与当前进度，但运行一段时间后会退回：

`0 / 0（0.0%）`
`当前：《准备中》　成功 0｜失败 0｜跳过 0`

重新进入应用后仍可能保持该状态，表现为任务没有正常持续后台执行。

## 根因

问题与 V795 的 Rule 117 目录识别无关，回归点位于 V786 的“前台阅读让行”改动。

V785 的 `ShelfCacheWorker` 没有 `Result.retry()`。V786 新增了两条正常阅读路径上的 `Result.retry()`：

1. Worker 已取得当前书后发现 Reader 进入前台；
2. `PageEngine.paginate()` 进行中发现 Reader 进入前台。

这会把唯一 WorkRequest 从 RUNNING 送回 WorkManager retry/backoff 队列。进入 ENQUEUED/BLOCKED 后，WorkManager 的实时 progress 可能为空，而旧 UI 对空 progress 直接按 0 读取，因此把真实 checkpoint 错显示成 0/0、准备中、成功 0。

同时，V728 已经实现的 `ShelfCacheKeepAliveService` 在生产 `enqueue()` 路径中从未真正调用，所以“保持进程前台 + bounded wake lock”的设计实际没有生效。

## V796 修复

1. 正常前台阅读不再触发 `Result.retry()`。
2. 保留 `awaitForegroundReaderIdle()`：Worker 在进入下一本书前等待 Reader 空闲，继续保证阅读优先。
3. 已经取得所有权的当前书在同一次 WorkManager RUNNING 生命周期内完成；`PageEngine` 的 `shouldCancel` 只响应 Worker 自身真正被取消/停止。
4. `ShelfCacheWorker.enqueue()` 正式启动已有的 `ShelfCacheKeepAliveService`。
5. keep-alive 服务使用独立通知 ID 61314，避免与 WorkManager foreground worker 的 61313 冲突。
6. `ShelfCacheUiController` 在 WorkManager 临时没有 progress 时读取 `ShelfCacheCheckpointStore`：有真实 checkpoint 就显示真实 current/total 与成功/失败/跳过计数，不再退成 0/0。
7. 规则 117、目录识别逻辑、Reader/Worker 同书所有权 handoff 均不修改。

## 回归边界

- 不恢复同书双写。
- 不取消 checkpoint。
- 不把当前书失败误计为完成。
- 不修改 Rule 117。
- 不允许 `ShelfCacheWorker.kt` 再出现正常路径 `Result.retry()`。
- 任务重新进入应用时，只要 checkpoint 已建立，UI 必须显示真实 total。

## 版本

- versionName: 796
- versionCode: 2098000796
- branch: source-v796


## 构建与签名验收

GitHub Actions 仅负责构建未签名 APK，不保存、不读取签名密钥。

- workflow run: 35809176451
- branch head: f61d491e54bb61d0e259d63af107473e76dbb1bb
- package: com.simplereader.app
- versionName: 796
- versionCode: 2098000796
- 专项测试：通过
- 完整单测相对既有基线：通过
- Debug / Release 构建：通过
- 未签名 APK 上传：通过

正式 APK 在 GitHub 外使用 Google Drive 中既有的 SimpleReader Public V1 密钥签名。

- certificate SHA-256: 315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648
- APK SHA-256: cdd27c55e641f30471072e608ceef609a13b1e1843fbaf7c742abec251d1bc01
- v2 signing: true
- v3 signing: true

公开分支未包含 .keystore、.jks 或密码文件。
