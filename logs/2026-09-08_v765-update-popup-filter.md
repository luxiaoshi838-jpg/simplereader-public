# 简阅 V765：覆盖更新后历史异常误报修复

## 实机证据
用户在覆盖更新后首次打开简阅即弹出“异常进程退出记录”。2026-09-08 上传的日志显示最新被弹出的系统记录为：
- `REASON_OTHER (13)`
- description=`normal_mem_pressure`
- importance=400
- PSS=77924 kB / RSS=134040 kB
- ReaderState: `active=false`, `lastEvent=reader_clean_finish`

同一份 V764 实机内存流水显示大书 `book=26491` 在 `paginate_success` 时 PSS 约 278 MB，而正常退出阅读器、释放书架后降到约 104 MB，5 秒后约 99 MB。之后系统在后台缓存状态按 normal_mem_pressure 回收到约 78 MB。这个退出本身不应作为“当前版本刚启动就异常”的用户告警。

## 根因
V764 `CrashLogStore` 有两个独立误报路径：
1. `ApplicationExitInfo.REASON_OTHER` 被无条件列为 actionable，因此 `normal_mem_pressure` 即使发生在 reader_clean_finish 后也会弹窗。
2. `pending_crash_log.txt` 跨 APK 覆盖更新保留；新版本第一次启动会继续展示上一安装版本遗留的待处理日志，看起来像“更新后立即报错”。

## V765 修复
- versionCode `2098000765` / versionName `765`。
- `capturePreviousProcessExit()` 读取当前 APK `lastUpdateTime`；任何退出时间早于当前 APK 安装时间的历史 `ApplicationExitInfo` 不再作为当前版本新异常弹窗。
- 增加当前版本记录 `PREF_LAST_SEEN_VERSION_CODE`。
- 对 V764 -> V765 首次升级的 bootstrap：V764 尚未保存版本 prefs，因此同时比较 `pending_crash_log.txt.lastModified()` 与当前 APK `lastUpdateTime`；旧 pending 文件会被识别为升级前历史记录。
- 升级前 pending 日志不会直接丢弃：先内部归档到 `pending_crash_log_history.txt`，然后从当前待弹窗文件移除。
- `REASON_OTHER + normal_mem_pressure` 且 ReaderState 已 `active=false`、事件为 `reader_clean_finish` 时不弹异常。
- ANR、Java crash、native crash、signal、LOW_MEMORY、初始化失败、资源使用过量、依赖进程死亡等仍保持 actionable。

## 回归边界
V765 是窄范围诊断修复。CI 强制检查 `app/` 相对 V764 只允许以下两个文件发生变化：
- `app/build.gradle.kts`
- `app/src/main/java/com/simplereader/app/crash/CrashLogStore.kt`

因此 ReaderActivity、ReaderSearchSheet、V764 重复实例所有权、书架 RecyclerView 虚拟化、后台 worker 避让和滚动路径均不改。52 项继承门禁中搜索 jump、拖动清除搜索、高亮清理/位置稳定等继续 PASS。

## GitHub 构建过程
早期失败均为构建脚本/历史门禁适配问题，未交付任何 APK：
- run `34180986001`：补丁脚本使用了错误的旧 prefs key 锚点。
- run `34181077425`：V764 专用 gate 硬编码版本 764，与 V765 版本升级冲突；改由 V765 窄差异门禁保护 V764 业务逻辑。
- run `34181246079`：前一次 CI 已把第一阶段 V765 源码写回分支，补丁脚本不是幂等；已修为可重复执行并补上首个升级 bootstrap。

最终正式 GitHub Actions：
- workflow: `Build 简阅 v765`
- run: `34181347194` / #81
- head SHA: `de5400aa55537fa3d63bfbe4f39e960543e1141d`
- verified app source persisted commit: `4cd950845fa2dabd28dfe4734aacb8de5f0d1869`
- result: SUCCESS
- run time: about 3 min 51 sec
- inherited 52 stability gates: PASS
- V765 update/benign-exit + narrow-diff + search compatibility gates: PASS
- full unit suite against unchanged V758 historical-failure baseline: PASS
- Release build/APK metadata/artifact upload: PASS
- artifact: `SimpleReader-v765-81`, id `10039075282`
- artifact digest: `sha256:7de28e8c474b68f651cda9a4b68bdb446225acfa8e7bfacc86e2aabdd424598e`

## 正式签名
GitHub 负责源码门禁、单元测试和 Release 编译。最终 APK 在仓库外受保护运行环境中使用 Google Drive“签名文件”内已经核验的 SimpleReader Public V1 固定密钥签名；密钥/密码未提交或上传到 GitHub。
- package: `com.simplereader.app`
- versionCode: `2098000765`
- versionName: `765`
- minSdk: 26
- targetSdk: 35
- signer alias: `simplereader-public-v1`
- signer certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme V1: false
- V2: true
- V3: true
- V4: false
- signers: 1
- STORED entries: 479
- 4-byte alignment failures: 0
- final signed APK SHA-256: `bac0f0dc4a5633b0ad691296872a400e60a1239c8665de6738d87aa22f886b8f`
- final package ZIP SHA-256: `7e4865e02d4e9ea191083668a48d25cc453e9784c0cb59141b1ac402b9e2185a`
