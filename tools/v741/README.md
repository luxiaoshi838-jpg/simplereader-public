# v741 操作日志覆盖层修复

基线：v740 成品 APK。

## 问题
v739/v740 的操作日志入口仍通过 `showBookActionsLegacy -> showGroupActions -> 反射 kt.show(Activity)` 打开 Android AlertDialog。用户实机上点击“操作日志”后父菜单关闭，但新日志窗口没有稳定显示，表现为直接回到书架且无反应。

## v741 修复
- 不再使用 AlertDialog/新 Window 显示操作日志。
- `kt.show(Activity)` 改为调用 `OperationLogOverlay.show(Activity)`。
- `OperationLogOverlay` 直接通过 `activity.getWindow().getDecorView().addView(...)` 把操作日志覆盖层加到当前书架 Activity。
- 列表页无复制按钮。
- 点击日志进入详情覆盖层；详情页才有“复制”和“返回日志列表”。
- 关闭覆盖层后原书架仍在下面，不创建新窗口，不依赖 Window token/theme。
- 兼容旧 `operation_history_v726` 字段类型，通过 `SharedPreferences.getAll()` 做容错读取。

## 二进制范围
相对 v740，仅 `AndroidManifest.xml`（版本号）和 `classes5.dex` 变化；`classes3.dex` 字节完全一致。因此单线缓存、书架、阅读和分页入口不变。

APK、keystore、密码不上传公开仓库。