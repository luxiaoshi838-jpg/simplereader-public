# v742 日志入口改为闪退/崩溃日志

本版以已验证的 v741 成品为二进制基线，只修改两处：

1. AndroidManifest.xml：版本号升到 742 / 2098000742。
2. classes3.dex：`MainActivity.showDataExportOptions$lambda$172()` 中第 3 项“日志”不再进入历史操作日志链 `showBookActionsLegacy(null)`，而是直接调用 MainActivity 已有的 `showCrashLogList()`。

因此点击“数据导出 → 日志”后，直接显示应用现有的“闪退/崩溃日志”目录列表；操作日志覆盖层、反射 helper 不再参与该入口。

本版不修改 classes5.dex，不修改单线缓存、阅读页、分页引擎、书架布局或其他功能。

成品验收：相对 v741，非签名条目仅 `AndroidManifest.xml` 与 `classes3.dex` 改变，其余 1780 个条目字节不变；官方 zipalign 与 APK Signature Scheme v2/v3 验证通过。
