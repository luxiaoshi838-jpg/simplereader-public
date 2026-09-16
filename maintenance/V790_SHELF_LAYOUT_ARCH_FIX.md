# v790 书架列表/宫格崩溃修复

## 实机根因

v787、v788、v789 在 Android 16/API 36 的实机日志均指向同一异常：`java.lang.NullPointerException: Layout parameters cannot be null`，调用链为 `wrapSelectableShelfCard -> buildBookListCard -> ShelfAdapter.onBindViewHolder`。根因不是 `GridLayoutManager` 或 `LinearLayoutManager` 本身，而是 `wrapSelectableShelfCard()` 读取尚未挂到父容器的 `card.layoutParams`（允许为 null），随后把它赋给新 `FrameLayout`。Android 16 在 `View.setLayoutParams(null)` 处直接崩溃。

## v790 修复

1. 删除 `originalParams` 的读取与恢复。内部 card 显式获得 `FrameLayout.LayoutParams`；外层 wrapper 的参数继续只由 `ShelfAdapter.onBindViewHolder()` 在加入真实父容器前设置。
2. 列表/宫格切换不再对正在显示的 RecyclerView 热替换层级。保存新模式、停止滚动、清空 recycled pool 后调用 `Activity.recreate()`，由新 Activity 一次性创建匹配模式的 `LinearLayoutManager` 或 `GridLayoutManager`。
3. 新增 `ShelfV790LayoutSafetyContractTest`，硬性禁止恢复 nullable layoutParams，并禁止切换函数再次调用 `applyShelfLayoutMode()` 做热切换。
4. 同步 v788 历史契约：旧测试不再要求已经被实机证明不可靠的 `post + applyShelfLayoutMode()` 热切换。

## 成熟实现参考

参考 `LegadoTeam/legado` 的书架实现：列表/宫格分别创建匹配的 Adapter/LayoutManager；书架布局配置改变时清理对应 recycled pool 并触发界面 recreate，而不是在活跃 RecyclerView 上复用不同模式的 item 层级。

## 验收要求

- Debug/Release 定向测试通过。
- 全量单测相对历史 known-failure baseline 不新增失败。
- Debug/Release APK 均可构建。
- Android 35 自动验收必须预置真实书籍/分组后执行连续模式切换；空书架按钮点击不再视为充分验证。
