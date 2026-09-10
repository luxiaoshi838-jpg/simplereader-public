# V779 字号重分页回归修复

## 问题

V778 中出现阅读页字号修改回归：

- 修改字号后当前页面没有立即按新字号重新分页；
- 连续修改字号会连续启动分页任务；
- 退出阅读页或新一轮分页取消旧任务时，正常的协程取消可能被 `catch (Throwable)` 当成真正分页失败；
- 随后可能进入 fallback / fatal UI，在 Activity 已销毁或无有效 window token 时触发 `BadTokenException`；
- 退出重进后才使用已保存的新字号重新分页，造成“修改字号页面不变、重进才变化”的表现。

该问题属于历史字号重分页保护的回归。历史门禁已有 `font-size debounce/anchor/rollback protection` 契约，本版恢复并强化该契约。

## V779 修复

### 1. 恢复字号调整 burst 的单一稳定回滚基线

- 继续使用 `FONT_CHANGE_DEBOUNCE_MS = 320L`；
- 使用 `currentVisibleSourceOffset()` 保存当前真实原文字符锚点；
- 一次连续字号调整 burst 只建立一个 `FontRollback` 基线；
- 连续点击只保留最后一次延迟请求；
- 用 `fontChangeRequestId` 让过期字号请求失效；
- 新字号真正分页时仍以原文字符位置作为 `preserveOffset`。

### 2. 新字号请求开始前取消旧分页

如果上一轮字号分页已经开始，新的字号操作会立即取消旧 `paginationJob`，避免多轮全书分页并发运行。取消属于正常控制流，不得进入失败恢复 UI。

### 3. `CancellationException` 与真正失败严格分离

`loadBook()` 与 `paginateAndDisplay()` 均新增显式：

```kotlin
catch (cancelled: CancellationException) {
    ...
    throw cancelled
}
```

正常取消不会再：

- rollback；
- `showContinuousFallback()`；
- `showFatal()`；
- 弹 Dialog / Toast 伪装为分页故障。

### 4. 生命周期 / owner 防护

真正异常进入失败 UI 前必须满足：

- Activity 未 `isFinishing`；
- Activity 未 `isDestroyed`；
- 当前 Reader 仍拥有本次 reader session。

这用于阻止旧 Activity、过期 reader generation 在退出/重进过程中弹出 fatal Dialog。

### 5. rollback 恢复原文位置

真正字号分页失败时，除了恢复旧字号、旧 `ReaderBook`、旧 layout settings、旧页码，还恢复 `FontRollback.sourceOffset` 到 `lastStableSourceOffset`，保证位置保护与历史 v754 契约一致。

## 防回归门禁

新增：

- `tools/apply-v779-font-repagination-regression.py`
- `tools/v779-font-repagination-gates.sh`
- `tools/v779-emulator-launch-test.sh`

专项门禁检查：

- 320 ms 防抖仍存在；
- `currentVisibleSourceOffset()` 字符锚点仍存在；
- 单 burst 稳定 rollback；
- `fontChangeRequestId` 过期请求淘汰；
- 字号变化会取消旧分页；
- `CancellationException` 必须在 `Throwable` 之前单独处理；
- 取消路径不得进入 rollback / fallback / fatal UI；
- 真正失败 UI 必须有 Activity 生命周期和 reader owner 防护；
- v773 Android 35 启动安全与 v771 shelf/reader handoff 回归继续执行。

## GitHub Actions 验收

- workflow: `Build 简阅 v779`
- run number: 141
- run id: `34372978576`
- result: `success`
- 开始：2026-09-09 15:51:18 UTC
- 完成：2026-09-09 15:58:22 UTC
- v779 字号重分页专项门禁：PASS
- 继承 52 项稳定性门禁：PASS
- selection/touch coexistence：PASS
- instant selection：PASS
- compact settings layout：PASS
- v773 startup-safe fast-scroll + v771 handoff：PASS
- 完整单测基线：PASS
- Release 构建：PASS
- Android 35 实际 Release 安装 + 冷启动：PASS
- CI artifact：`SimpleReader-v779-141`

## 版本与正式签名

- package: `com.simplereader.app`
- versionCode: `2098000779`
- versionName: `779`
- minSdk: 26
- target/compileSdk: 35
- 正式证书：`SimpleReader Public V1`
- 正式证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme v2：true
- APK Signature Scheme v3：true
- 最终已签名 APK SHA-256：`d994e797314183fb8292b51c570baffcca38e58e331dd02d20a8929b1b7aeb47`

## 后续固定规则

以后任何阅读页异步任务，只要“取消”属于正常控制流，都不得被宽泛的 `catch (Throwable)` 转换成错误 UI。对于字号、翻页模式、背景等会触发重新分页的操作，必须继续保持“字符位置锚点 + 最终请求生效 + 可回滚 + 生命周期安全”四项约束。
