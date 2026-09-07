# 简阅 V762 内存归因诊断记录

## 触发证据
V761 的 Android 系统退出记录显示一次 `REASON_OTHER (13)` / `normal_mem_pressure`：退出时 PSS 约 185934 kB，RSS 约 147056 kB；阅读器此前已 `reader_clean_finish`。这条记录只能给出系统回收瞬间的总 PSS/RSS，不能回答 186 MB 主要来自 Java heap、native、graphics、code、private-other 还是 system，也不能回答 ReaderActivity 结束后内存是否真正下降。

因此 V762 不先修改分页、RecyclerView、滚动、页面缓存或恢复逻辑；先增强内存归因日志，取得证据后再决定下一版的实际内存修复。

## V762 诊断内容
- 使用 `Debug.MemoryInfo` 记录 totalPss、Java heap、native heap、graphics、code、stack、private-other、system、swap。
- 同时记录 Runtime Java used/committed/max、native allocated、系统可用内存/threshold/lowMemory。
- ReaderActivity 只传递便宜的结构计数：textChars、pages、chapters、currentPage、RecyclerView child/item 数。
- 采样时机：process start、reader session begin、paginate success、onPause、onDestroy、reader_clean_finish，以及退出阅读器后 5 s / 30 s / 2 min / 10 min / 30 min；另记录 Application.onTrimMemory/onLowMemory。
- 内存采样全部在 crash-log 后台 ScheduledExecutor 上执行；RecyclerView `onScrolled` 与 `verticalOnPageVisible` 禁止调用内存采样或 Debug.MemoryInfo。
- Android 下一次报告进程退出时，文本日志加入“进程退出前内存诊断流水”。

## 稳定性边界
V761 的 ANR trace、页零回跳保护、V758/V760 的轻量文本布局和有界缓存均保留。本版是诊断版，不声称已经解决 PSS 约 186 MB 的根因。

## GitHub 构建
- branch: `source-v762`
- verified source commit written by Actions: `446b5a4e176266daf5a1856a50d3d68712c037b4`
- Actions run: `34096056664` / #67
- started: 2026-09-07T07:33:36Z
- completed: 2026-09-07T07:38:29Z
- result: SUCCESS
- elapsed: 4 min 53 sec
- inherited 52 stability gates: PASS
- V762 memory-attribution + no-hotpath gate: PASS
- full unit suite against unchanged V758 historical failure baseline: PASS
- Release build / APK metadata / artifact upload: PASS

## 正式签名
正式 APK 使用 Google Drive“签名文件”中的 SimpleReader Public V1 固定密钥在仓库外签名；密钥和密码未提交 GitHub。
- package: `com.simplereader.app`
- versionCode: `2098000762`
- versionName: `762`
- minSdk: 26
- targetSdk: 35
- certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- stored ZIP entries: 479
- 4-byte alignment failures: 0
- signed APK SHA-256: `a5bf9eedd57575ec5796d1a393e5526f887a7e7a7d8f92087dbe8ec1f4d5c799`
