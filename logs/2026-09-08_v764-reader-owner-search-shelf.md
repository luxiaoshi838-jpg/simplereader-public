# 简阅 V764：重复阅读器、书架虚拟化与阅读页搜索兼容记录

## 实机证据
V763 实机退出/内存日志显示：
- 同一本书在极短时间内连续出现多个 `reader_session_begin` / `paginate_success`，存在重叠 ReaderActivity 生命周期。
- 前台阅读位置曾从 `page=1571/1856, offset=366314` 被后续状态覆盖为 `page=0/1856, offset=0`，随后被 clean-finish 保存。
- 书架规模为约 5715 本、186 分组；MainActivity 即使 `visible=false` 并清理旧 GridLayout 后，进程基线仍偏高。
- 多个旧 ReaderSession 的 `post_reader_finish_*` 定时采样在数小时后集中执行，诊断时序失真。
- V763 后台缓存进程最终仍因 `normal_mem_pressure` 被系统回收。

## V764 修复
### 1. ReaderActivity 重复启动与写入所有权
- Manifest 中 ReaderActivity 使用 `launchMode=singleTop`。
- MainActivity 增加 `readerLaunchInFlight`；第一次 launch 后直到 MainActivity resume 前拒绝重复 launch，并附加 `FLAG_ACTIVITY_SINGLE_TOP`。
- 新增 `ReaderRuntimeState` generation/owner。
- 只有最新 ReaderActivity generation 可以写阅读进度、recovery state 和 clean-finish。
- 旧/隐藏实例在 `onPause` / `onDestroy` 写入会被拒绝并记录 `stale_reader_*_suppressed`。
- 异步数据库写入提交前再次检查 owner，避免旧 coroutine 延迟覆盖新实例位置。

### 2. 5715 本书架虚拟化
- `ScrollView + GridLayout` 全量 View 树改为 `RecyclerView + GridLayoutManager(3)`。
- 只绑定视口附近卡片；recycle 时清除 ImageView 引用。
- EPUB 封面解码并发限制为 3；书架离开前台时取消未完成封面任务、递增 generation、清 Bitmap cache 和 recycled pool。

### 3. 后台全书架分页与前台阅读隔离
- `ShelfCacheWorker` 在每本书边界以及进入 PageEngine 重分页前检查 `ReaderRuntimeState.isReaderForeground()`。
- 前台正在阅读（包括使用阅读页搜索）时，后台全书架分页让出重任务，避免与 ReaderActivity 争 CPU/内存。

### 4. 诊断定时任务去积压
- 新 ReaderSession 会使旧 finished-session 的延迟内存采样 generation 失效。
- 旧的 5 s / 30 s / 2 min / 10 min / 30 min 定时任务若已过期则跳过，不再数小时后集中补跑。
- 内存快照增加 version/versionCode、pid、processSession，便于跨升级归因。

## 阅读页搜索：硬兼容锁
本版明确不改搜索实现：
- `ReaderSearchSheet.kt` 与 `source-v763` 字节级一致。
- `ReaderActivity` 中从 `showContentSearch()` 到书签删除函数之前的搜索/查找/命中跳转/高亮代码块与 `source-v763` 字节级一致。
- V764 专用 gate 在 GitHub CI 中直接与 `origin/source-v763` 比较；任一搜索代码变化都会失败。
- 同时继承门禁继续验证：search jump、拖动清除搜索、高亮清除不触发布局重绑、高亮移除不得移动阅读位置。

## GitHub 构建过程
### 历史失败（均未交付）
- run #70 / `34176656137`：旧 Gate17 仍按非虚拟化时代的 `addBookCard` 函数名定位源码；业务补丁未判定失败，仅历史 gate 形状不兼容。
- run #71 / `34176715928`：Gate17 修正后，旧 Gate19 还有同样的 `addBookCard` 静态定位；继续只适配旧 gate。
- run #72 / `34176852144`：52 项门禁与 V764 专用门禁均 PASS，源码已写回；随后发现版本号补丁生成了缺失引号的 Gradle 字符串，单测未真正启动。该工具错误已直接修正，未将此 run 作为正式包。

### 最终正式构建
- branch: `source-v764`
- V764 业务源码提交: `afac78047b17870de922ff54913e273a6b8206ff`
- version-string 修正提交: `63e71a3e7036748c877e2ebfc8c507ffa733b6da`
- workflow: `Build 简阅 v764`
- run: `34176957994` / #73
- result: SUCCESS
- elapsed: about 5 min 1 sec
- inherited 52 stability gates: PASS
- V764 owner/search-lock/shelf-virtualization gates: PASS
- full unit suite against unchanged V758 historical-failure baseline: PASS
- Release build: PASS
- APK metadata verification: PASS
- artifact upload: PASS
- artifact: `SimpleReader-v764-73`, id `10037665526`
- artifact digest: `sha256:1b193ba446625e59a0ed27ed5f9afb127fd56211c2b47960d0c148fe0dd6d2cf`

## 正式签名
GitHub 负责源码门禁、单测和 Release 编译；正式签名仍在仓库外使用 Google Drive 中已验证的 SimpleReader Public V1 固定密钥，签名材料/密码未上传或提交 GitHub。
- package: `com.simplereader.app`
- versionCode: `2098000764`
- versionName: `764`
- minSdk: 26
- targetSdk: 35
- signer cert SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme V2: PASS
- APK Signature Scheme V3: PASS
- STORED ZIP entries: 479
- 4-byte alignment failures: 0
- final signed APK SHA-256: `c26d65cdcfd39b4b872c6519a274fb0836d8bd19a72d95137ae6d862df3cf6ce`

## 结论边界
V764 直接修复日志已经证实的重复 ReaderActivity 写入竞争、大书架全量 View、封面任务继续运行、旧诊断定时任务积压，并让全书架重分页避开前台阅读。阅读页搜索通过字节级兼容锁保留。是否已将目标设备上的长期 PSS/graphics 峰值降至理想水平，仍以安装 V764 后的新实机日志为准，不在构建阶段提前宣称。