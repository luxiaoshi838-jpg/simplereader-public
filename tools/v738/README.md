# v738 — 单线书架缓存执行修复

## 根因
v733-v737 的 `NoCatalogWorker` 使用同一个 WorkManager unique work 并以 `ExistingWorkPolicy.REPLACE` 入队，但 Worker 自身没有检查 `ListenableWorker.isStopped()`，分页时传给 `PageEngine` 的取消回调也始终返回 `true`。因此旧 WorkRequest 被替换/取消后，旧线程仍可能继续扫描、识别和分页；新 WorkRequest 同时启动，形成两条缓存执行线竞争 CPU/IO，表现为两个不同进度同时前进、页面明显卡顿。

## v738 修复
- `kt.enqueue()` 在构建新 WorkRequest 前立即把 `active_work_id` 改成 pending token，使旧 `NoCatalogWorker` 立刻失效。
- WorkRequest 构建后把真实 UUID 写入 `active_work_id`。
- `NoCatalogWorker` 同时检查：
  - 当前 WorkRequest UUID 是否仍等于 `active_work_id`；
  - WorkManager 是否已将该 Worker 标记为 stopped。
- 扫描期间每 32 本检查一次取消状态。
- 每本正式处理前、文件定位后、清缓存后、文档加载后、分页参数后、分页结果写入前后均检查取消状态。
- `PageEngine.paginate()` 的 `Function0<Boolean>` 改为返回当前 Worker 是否仍有效；旧任务在长书分页过程中也能被 `PageEngine.ensureNotCancelled()` 中止。
- `NoCatalogWorker.doWork()` 增加 JVM 单实例执行锁；即使数据库里短暂存在多个 WorkSpec，也只允许一个实例真正进入扫描/分页，获得锁后过期 Work ID 会立即退出。
- 被替代/取消的旧任务不写“失败”日志，避免把正常替换误报为处理失败。

## 二进制范围
v738 以 v737 APK 为生产基线：
- `classes3.dex` 完全不变；
- 仅 `classes5.dex` 更新上述并发/取消逻辑；
- `AndroidManifest.xml` 只升版本 737 -> 738；
- 其余 1780 个非 META-INF 条目与 v737 字节一致。

生产 APK 仍只在本地签名，不上传公开仓库。