# v776 书架日间太阳图标调整与发布记录

日期：2026-09-09

## 用户要求

- 仅修改书架页的日间模式太阳图标。
- 书架日间模式太阳沿用已确认的 A 方案几何形状，但由黑色实心改为“黑边 + 白底/白色中心”。
- 阅读页不要修改，继续保持 v775 的日夜图标和交互实现。
- 日夜状态语义保持不变：当前为日间模式时显示太阳；当前为夜间模式时显示月亮，禁止反转。

## 实现

版本：
- `versionName=776`
- `versionCode=2098000776`
- package：`com.simplereader.app`

分支：`source-v776`

书架页新增独立实现，避免修改阅读页共用资源：
- `app/src/main/java/com/simplereader/app/ui/ShelfDayNightModeIcon.kt`
- `app/src/main/res/drawable/ic_shelf_mode_day_outline.xml`

书架日间太阳：
- 保留 v775 A 方案的圆形太阳与 8 条圆头光芒几何结构；
- 中心改为白色填充；
- 外圈和光芒固定为黑色；
- 日间图标不再被主题 tint 覆盖，以保证黑边白底视觉稳定；
- 夜间模式继续使用 v775 的 `ic_mode_night_a`。

`MainActivity` 改用书架专用 `ShelfDayNightModeIcon`。阅读页继续使用 v775 的 `DayNightModeIcon`，未切换到书架专用实现。

## 阅读页零修改保护

新增 `tools/v776-shelf-outline-day-gates.sh`，相对 `origin/source-v775` 强制检查以下阅读页文件/资源无差异：
- `ReaderActivity.kt`
- `activity_reader.xml`
- `DayNightModeIcon.kt`
- `ic_mode_day_a.xml`
- `ic_mode_night_a.xml`

该门禁已通过，因此 v776 本次图标修改范围仅限书架页。

## 回归与发布验证

GitHub Actions：
- workflow：`Build 简阅 v776`
- run ID：`34336043034`
- run number：`134`
- artifact：`SimpleReader-v776-134`
- artifact ID：`10097965491`

结果：
- 继承 52 项稳定性门禁：PASS
- 选择/触摸共存门禁：PASS
- 即时选中文本门禁：PASS
- 设置布局门禁：PASS
- v776 书架黑边白底太阳 + 阅读页零修改专项门禁：PASS
- 源码格式检查：PASS
- 完整单元测试相对既有 V758 基线：无新增失败
- Release 构建：PASS
- Android 35 对混淆 Release 实际安装 + 冷启动：PASS
- APK 包名/版本/minSdk 校验：PASS

同时保留：
- v773 Android 35 书架启动崩溃修复；
- v775 单图标槽结构；
- v771 书架目录生成与前台打开书籍的任务交接修复。

## 正式签名

正式签名身份：`SimpleReader Public V1`

证书 SHA-256：
`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

APK Signature Scheme：
- v2：PASS
- v3：PASS

最终已签名 APK SHA-256：
`e0f20fddacfd773d05b3a263b1143aa2667c12007eed6beedd1517c099591a16`

APK ZIP 完整性：PASS。
