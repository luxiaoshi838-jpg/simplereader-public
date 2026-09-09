# V774 日间/夜间图标维护日志

## 需求与固定语义

- 书架页与阅读页统一使用 A 方案日夜图标，不再使用 Unicode `☀/☾` 字符作为主切换标识。
- 图标表示**当前模式状态**，不是下一步操作：
  - `ReaderAppearance.MODE_DAY` → 显示太阳。
  - `ReaderAppearance.MODE_NIGHT` → 显示月亮。
- 该映射禁止反转。
- 点击书架页或阅读页的模式按钮后，调用同一套 `ReaderAppearance` 状态切换，并立即刷新图标。
- 阅读页通过其他背景入口切换到日间或夜间模式时，也由 `applyReaderAppearance()` 同步刷新当前图标。

## A 方案图标

- `ic_mode_day_a.xml`：实心圆太阳中心 + 8 条短圆角光芒。
- `ic_mode_night_a.xml`：实心弯月 + 小星点。
- `DayNightModeIcon.kt`：书架页和阅读页共用绑定器；唯一模式判断为 `ReaderAppearance.currentMode()`。

## 主要实现位置

- `app/src/main/java/com/simplereader/app/ui/DayNightModeIcon.kt`
- `app/src/main/res/drawable/ic_mode_day_a.xml`
- `app/src/main/res/drawable/ic_mode_night_a.xml`
- `app/src/main/java/com/simplereader/app/ui/MainActivity.kt`
- `app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt`
- `tools/v774-day-night-icon-gates.sh`

## 回归保护

- v774 专项门禁明确检查 `MODE_DAY -> ic_mode_day_a`、否则 `ic_mode_night_a`，防止日月逻辑以后被写反。
- 继续继承 v773 的 Android 35 系统滚动条启动崩溃修复与门禁。
- 继续继承 v771 的全书架目录生成/前台阅读同书交接回归。

## V774 验收结果

- `versionName=774`，`versionCode=2098000774`。
- GitHub Actions `Build 简阅 v774` run 124。
- 52 项稳定性门禁：PASS。
- 选中文本/阅读触摸、即时选中、设置布局：PASS。
- v774 日夜图标语义与启动专项门禁：PASS。
- 完整单元测试基线：PASS。
- Release 构建：PASS。
- Android 35 对同一混淆 Release 产物进行临时测试签名后的实际安装与冷启动：PASS。
- 正式签名使用 `SimpleReader Public V1`。
- 正式证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`。
- 最终已签名 APK SHA-256：`86fe3f6118cbd3363e148c7a72547eee55f0ec0bfdf2dc4d88c114b25f0c2167`。
