# V781 跨字号缓存即时开书修复

## 问题

v780 已解决“当前书修改字号后立即看到字号变化，并在后台重建精确完整页表”，但打开另一本文本时仍会无条件进入 `paginateAndDisplay(null)`。

分页缓存身份包含 `ReaderLayoutSettings.stableHash()`。因此只要用户修改字号，另一本文本即使已经拥有完整分页缓存，旧缓存也会因为 settingsHash 不同而被判定为 miss，随后前台重新执行整本 `PageEngine.paginate()`，表现为再次出现“分页中…”。

这不是旧书后台任务串到新书；v780 的 generation/bookId/epoch 隔离仍然有效。根因是新书自己的当前字号 cache miss 被当成了阻塞式首次分页。

## v781 修复

1. `PageCacheStore.loadCompatiblePages()` 新增“同源完整页表”读取路径。它只允许跨 `readerSettingsHash` 复用；`bookId`、文本 fingerprint、catalog rule version 仍必须匹配。旧布局页表不会被写成当前布局缓存。
2. `ReaderActivity.showCachedBookImmediately()` 打开书时先找当前字号精确缓存；没有时再找同源旧字号完整页表。
3. 找到旧字号页表后立即显示阅读页，并使用当前字号/当前排版样式渲染。临时层复用的是完整旧页表，不创建“当前页+附近页”的 partial ReaderBook，因此不重新引入历史上的窗口边界卡死、不能翻页和跳章节问题。
4. 精确当前字号完整页表仍在后台生成，但 `backgroundOpen=true` 时不显示“分页中…”，也不把 `paginationInProgress` 当成前台交互锁。
5. 后台精确页表提交前继续受 pagination epoch、reader generation、bookId 和 owner 校验；切换书籍后旧任务不能覆盖新书。
6. 后台精确分页失败时，如果兼容页表已经在显示，则保留现有可读页面，只记录 `open_cache_refresh:failed_keep_preview`，不进入 fatal/fallback UI。
7. 当前 typography hash 与临时完整页表 hash 不一致时，阅读进度继续以稳定 `sourceOffset` 为真值，避免后台原子替换时回退到旧页表页首。
8. v780 的字号即时预览、协作取消、CancellationException 控制流以及 v771/v773/v777/v778 等历史修复全部保留。

## 边界

真正从未生成过任何完整页表的书没有可复用导航骨架，仍需要一次初始完整分页；本次修复针对的是“修改全局字号后，已存在其它布局页表的书被迫再次阻塞式全书分页”的回归场景。书架后台缓存完成后的书，以及以前打开/识别过的书，均可走跨字号即时打开路径。

## 自动化验收

- 分支：`source-v781`
- 运行时持久化提交：`3bfedd9a9d84c702e7c8446e190ac83ee6abb5b5`
- GitHub Actions：run #150，run id `34431578183`
- 52 项历史稳定性门禁：PASS
- 选择/触摸、设置布局、v771/v773 接管与启动门禁：PASS
- v781 cache-first cross-font opening gates：PASS
- `PageEngineCancellationTest`：PASS
- `CompatiblePageCacheOpenContractTest`：PASS
- 完整单测基线：PASS
- Release 构建：PASS
- Android 35 实际 Release 安装 / 冷启动：PASS
- Android 35 结果：`V781_ANDROID35_RELEASE_COLD_LAUNCH_PASS`

## 发布产物

- `versionName=781`
- `versionCode=2098000781`
- GitHub unsigned APK SHA-256：`e58ac38c58537d5e462f26da0ce1b889f7e52d2de78b2e74e89da72ddd222eb0`
- 正式签名 APK SHA-256：`d53e8d326b6dc747362ddef07a022e2c354189e9b83ec7b638e1a38445256580`
- 正式签名 APK 大小：`5818047` bytes
- 签名：APK Signature Scheme v2=true，v3=true，v1=false，v4=false
- 证书：`CN=SimpleReader Public V1`
- 证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- ZIP integrity：PASS
