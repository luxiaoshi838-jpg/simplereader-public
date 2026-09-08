# 简阅 V766：后台回收静默与进程级诊断隔离

## 触发证据
用户上传的新退出记录显示：2026-09-08 13:03:39，Android `REASON_OTHER (13)`，`importance=400`，系统描述 `critical_mem_pressure`，PSS 125032 kB / RSS 135372 kB。该进程最后阅读状态停留在约一小时前。与此同时，退出报告附带的内存流水仍混有 2026-09-07 15:58～15:59 的旧进程数据，证明旧实现的进程诊断文件跨进程持续 append，会把前一天/前一进程的 300～395 MB 记录错误地附到当前 125 MB 回收事件上。

## V766 修复
### 1. 后台系统内存回收不再弹异常
- `ApplicationExitInfo.importance >= IMPORTANCE_CACHED (400)` 时，如果：
  - `REASON_OTHER` 且 description 含 `mem_pressure`（覆盖 `normal_mem_pressure`、`critical_mem_pressure` 等），或
  - `REASON_LOW_MEMORY`
  则归类为后台系统回收。
- 后台系统回收只写入 `system_exit_history.txt`，不写 `pending_crash_log.txt`，因此不弹“异常退出”。
- ANR、Java crash、native crash、signal、初始化失败、资源使用过量等真实异常仍维持可见异常路径。
- V765 的“升级前退出不得冒充新版本异常”规则继续保留。

### 2. 内存/事件流水按进程隔离
- App 新进程启动顺序固定为：
  1. `capturePreviousProcessExit()`：先读取上一进程的 Android 退出信息和上一进程日志；
  2. `startProcessSession()`：再轮转上一进程日志并清空当前 memory/event journal；
  3. `install()`；
  4. 当前进程写入 `process_start`。
- 每个进程写独立 `process_session_meta.json`，含 versionName/versionCode、pid、processSession、startedAt。
- 新进程不会把旧 pid/processSession 的内存流水继续 append 到当前进程主日志。
- 旧进程的 memory/event 流水保存到 `process_diagnostic_history.txt`，没有删除诊断证据。
- 真正异常报告加入“上一进程会话”字段，便于跨升级和跨进程直接归因。

### 3. 阅读功能边界
V766 是窄改动：相对 `source-v765`，`app/` 只允许修改：
- `app/build.gradle.kts`
- `App.kt`
- `CrashLogStore.kt`

`ReaderActivity.kt` 与 `ReaderSearchSheet.kt` 必须与 V765 完全一致；阅读页搜索、命中跳转/高亮、纵向滚动、页零保护、V764 书架 RecyclerView 虚拟化和 ReaderActivity ownership 均不改。

## GitHub 构建
- branch: `source-v766`
- verified source commit written by Actions: `eaf6d428051947a456f82060e38a96dcf74b0cd3`
- workflow: `Build 简阅 v766`
- run: `34196100693` / #82
- started: 2026-09-08T06:45:48Z
- completed: 2026-09-08T06:50:28Z
- elapsed: 4 min 40 sec
- result: SUCCESS
- inherited 52 stability gates: PASS
- V766 background-reclaim + process-session isolation + search-lock gates: PASS
- full unit suite against unchanged V758 historical-failure baseline: PASS
- Release build / APK metadata / artifact upload: PASS
- artifact: `SimpleReader-v766-82`, id `10044067955`
- artifact digest: `sha256:96bb03d707dab69fdbd9b7db8344ef2a09f0a00324ff6e1eb10b9d1509be7ebd`

## 正式签名
GitHub 完成源码门禁、单元测试和 Release 编译；正式签名继续在仓库外使用 Google Drive 中已验证的 SimpleReader Public V1 固定密钥。密钥与密码未上传 GitHub。
- package: `com.simplereader.app`
- versionCode: `2098000766`
- versionName: `766`
- minSdk: 26
- targetSdk: 35
- signer cert SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme V2: PASS
- APK Signature Scheme V3: PASS
- STORED ZIP entries: 479
- 4-byte alignment failures: 0
- final signed APK SHA-256: `8171abfe6ed3eef58183ee76a654b88c97a9fb0a2f245a7cbe66f224ab626526`
- final ZIP SHA-256: `c63e5e8886c357695ab6ee8aab46276230067dd10b5756d5155124632f139a6d`

## 结论边界
V766 解决的是“缓存后台进程被系统内存压力回收却弹异常”以及“退出报告混入旧进程内存流水”两个日志/分类问题；它不把 `critical_mem_pressure` 从诊断历史中抹掉，也不宣称系统以后不会回收缓存进程。若未来出现前台 ANR/Crash，仍会弹窗并携带当前进程独立流水。