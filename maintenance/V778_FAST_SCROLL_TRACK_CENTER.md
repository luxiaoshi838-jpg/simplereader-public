# SimpleReader v778 维护记录：滑块轨道与滑块同中心线

## 用户反馈
书架页和分组页的滑块对应竖向轨道（虚线）需要严格位于滑块本体中间，不能左右偏移。

## 根因
v777 已将滑块放入右侧 28dp 独立通道，但轨道绘制仍以滑块右边缘 `right` 为基准：轨道范围为 `right - trackWidth .. right`。由于滑块范围为 `right - thumbWidth .. right`，两者中心并不相同，导致轨道视觉上偏向滑块右侧。

## v778 修复
`ShelfFastScroller.kt` 改为先计算唯一水平中心：

```kotlin
val right = parent.width - edgeInset
val thumbCenterX = right - thumbWidth / 2f
```

轨道左右边界对称围绕该中心：

```kotlin
thumbCenterX - trackWidth / 2f
thumbCenterX + trackWidth / 2f
```

滑块仍保持：

```kotlin
thumbRect.set(right - thumbWidth, top, right, top + geometry.thumbHeight)
```

因此轨道中心与滑块中心严格相等，而不是依赖肉眼调整偏移量。

## 修改范围
- 主书架与分组页共用 `ShelfFastScroller`，因此两处同步修正。
- 保留 v777 的 28dp 独立右侧滑块通道。
- 保留横向触摸热区只能位于独立通道内的防误触规则。
- 保留“发生实际滚动后滑块才出现并可抓取”的行为。
- 保留停止约 1200ms 后失活。
- 阅读页未修改。
- 保留 v773 Android 35 启动安全修复与 v771 书架缓存/阅读器交接修复。

## 专项门禁
新增：
- `tools/apply-v778-center-fastscroll-track.py`
- `tools/v778-fastscroll-track-center-gates.sh`
- `tools/v778-emulator-launch-test.sh`

专项门禁检查轨道与滑块共享同一个 `thumbCenterX`，同时继续检查 v777 独立通道和防误触约束。

第一轮 Release run 138 被继承的 v777 门禁中写死的历史版本号 777 拦截；v778 本身的中心线逻辑已通过。随后将继承门禁改为仅验证 v777 行为约束，版本由当前 v778 门禁验证。

## 最终验收
GitHub Actions Release run：`34340714648`（run number 139）

通过：
- 52 项稳定性门禁
- 选择/触摸共存门禁
- 即时选中门禁
- 设置布局门禁
- v778 滑块轨道中心线专项门禁
- 完整单元测试既有基线门禁
- Minified Release 构建
- Android 35 实际安装 + MainActivity 冷启动
- APK 包名、版本与 minSdk 校验

Android 35 结果：`V778_ANDROID35_RELEASE_COLD_LAUNCH_PASS`

## 正式发布信息
- package: `com.simplereader.app`
- versionName: `778`
- versionCode: `2098000778`
- 正式签名：SimpleReader Public V1
- 正式证书 SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- 最终签名 APK SHA-256: `b6dfa205b3a3e7b5e19927c6c77fccccf919c87ed89d178afad7bd2a44d30f42`
- APK ZIP 完整性：PASS
