# V775 日夜模式单图标修复与验收

## 用户可见问题

V774 中，阅读页日夜按钮出现太阳与月亮同时存在的视觉结果；点击后看起来只是日月左右顺序变化，没有做到“当前日间只显示太阳、当前夜间只显示月亮”。

## 根因

V774 的模式映射本身没有反转：`MODE_DAY` 已对应 `ic_mode_day_a`，`MODE_NIGHT` 已对应 `ic_mode_night_a`。问题来自控件承载方式：按钮仍是 `TextView`，通过 compound drawable 放置模式图标，旧的 TextView drawable 状态可能和新状态并存，因此不能从控件结构上保证任何时刻只有一个模式图标。

## V775 固定修复

1. 书架页 `shelfNightButton` 与阅读页 `nightButton` 均改为 `ImageButton`。
2. 两处共用 `DayNightModeIcon`，参数改为 `ImageView`。
3. 每次刷新先 `setImageDrawable(null)`，再仅安装当前模式的一个 drawable；禁止再使用 `setCompoundDrawables`。
4. 当前模式映射锁定为：
   - `ReaderAppearance.MODE_DAY` → `ic_mode_day_a`（只显示太阳）
   - `ReaderAppearance.MODE_NIGHT` → `ic_mode_night_a`（只显示月亮）
5. 点击后继续使用同一个 `ReaderAppearance.toggleMode(...)` 切换状态，随后刷新唯一 image slot。
6. 删除书架/阅读页模式按钮上残留的 TextView-only 调用，包括 `setTextColor()` 和 `.text = ...`。
7. 新增 `tools/v775-single-mode-icon-gates.sh`，禁止上述旧路径重新出现。

## 继承修复

- V773 Android 35 启动安全书架滑块修复继续保留；禁止重新启用 RecyclerView 原生 scrollbar 绘制路径。
- V771 全书架目录生成与前台阅读同书交接修复继续保留。

## 验收

- 继承 52 项稳定性门禁：PASS。
- 选中文本/阅读触摸门禁：PASS。
- 即时选中门禁：PASS。
- 设置布局门禁：PASS。
- V775 单模式图标专项门禁：PASS，输出 `one image only; DAY=sun, NIGHT=moon`。
- V773 启动安全门禁：PASS。
- V771 书架/阅读目录交接回归：PASS。
- 完整单元测试基线：无新增失败（历史基线 10 项，unexpected=0）。
- Release 构建：PASS。
- Android 35 对混淆后的 Release 实际安装与冷启动：PASS；`LaunchState: COLD`，`MainActivity` 正常前台，进程保持存活，无 `FATAL EXCEPTION`。
- 包名：`com.simplereader.app`。
- `versionName=775`，`versionCode=2098000775`。
- 正式签名：SimpleReader Public V1。
- 正式证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`。
- APK Signature Scheme v2/v3：有效。
- 最终已签名 APK SHA-256：`9b4b57b38339d287b2d372ded2800372aaa41a4cc08a7c95dab5c2efffa95de9`。
