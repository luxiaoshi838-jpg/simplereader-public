# V791 竖向阅读 fling 保险与大跳转回撤

## 范围
本版只修改 TXT/通用 `ReaderActivity` 的竖向 RecyclerView 阅读链，不改书架、不改分页算法、不改 PageEngine offset 定义。

## 问题 1：偶发持续滑动
V790 的 `verticalHandleTouch()` 能停止自动阅读、解除陈旧程序化状态锁，但真实 `ACTION_DOWN` 没有无条件调用 RecyclerView `stopScroll()`；同时 `VerticalScrollListener` 对 `SCROLL_STATE_SETTLING` 没有极端兜底。V791 增加“触摸即刹车”和 5 秒 stuck-settling 保险，并过滤音量键长按重复。

## 问题 2：短时间大位置移动后的回撤
参考 KOReader location history 与公开 `kojump.koplugin`：只把跨越超过 1 页视为 jump；保存移动前 location。Android UI 使用 Material Snackbar 的 anchor 模式，浮在阅读下栏 `readerControls` 上方。显示时间由主线程固定为 3000 ms。

保存位置不是单纯页号，而是：
- `sourceOffset`
- `pageIndex`
- 第一可见页相对视口的 `top` 像素

点击“↩︎ 回撤”后，重新用 `sourceOffset` 定位当前分页中的目标页，再用保存的 top 恢复视口位置，因此比只保存 adapter position 更耐字号/分页状态变化。

## 公开实现参考
- KOReader: `frontend/apps/reader/modules/readerlink.lua` — previous-location stack / back-forward navigation.
- KOReader plugin: `dani84bs/kojump.koplugin/main.lua` — `abs(page_num-current_page) > 1` 才记录 jump，并保存 source page。
- Material Components Android: Snackbar / anchorView transient bottom bar pattern.

## 验证门
- `ReaderV791VerticalRollbackContractTest` Debug + Release。
- V759 全量单测基线门：只允许既有 10 项历史失败，不允许 V791 新增失败。
- Debug + Release 构建。
- APK 包名、versionCode=2098000791、versionName=791 校验。
