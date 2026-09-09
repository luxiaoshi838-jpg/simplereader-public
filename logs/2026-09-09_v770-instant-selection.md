# 简阅 V770：选中文本开启后即时生效

## 实机问题
V769 已经恢复了“选中文本”与阅读页触摸共存：中心点击可以开关上下栏，长按最终也可以正常选中文字。但实机继续发现：点击“选中文本”后按钮已经处于开启状态，当前页面并不能马上长按选中，需要等待一段时间后才开始正常工作。

这不是正常长按时间（V769 的触摸长按阈值没有修改），而是开关状态从“已开启”到当前可见文字 View 真正进入 selectable 状态之间存在延迟。

## 根因与修复
V769 的纵向阅读在 `applyTextSelectionSetting()` 中通过 `verticalAdapter.refresh()` / `notifyDataSetChanged()` 请求 RecyclerView 重新绑定。当前可见 TextView 的 `setTextIsSelectable(true)`、`isLongClickable=true` 和复制/翻译/搜索回调实际要等到后续 `onBindViewHolder()` 执行后才生效，因此按钮视觉状态和实际文本选择能力存在时间差。

V770 改为：
- 点击“选中文本”后直接遍历当前 RecyclerView 已 attached 的可见 holder；
- 同步执行 `setTextIsSelectable(enabled)`；
- 同步执行 `isLongClickable = enabled`；
- 同步绑定/清除当前页面的选中文本动作回调；
- 不再为了这个开关调用 `notifyDataSetChanged()`；
- 以后滚入屏幕的新 holder 仍由原 `onBindViewHolder()` 按当前设置正常继承。

因此 V770 修复的是“开关开启后的等待时间”，没有缩短或修改正常长按判定阈值。

## 兼容锁
- `PagedReaderView.kt` 与已实机确认可用的 V769 触摸路由保持字节级不变。
- `ReaderSelectionActions.kt` 与 V769 保持字节级不变。
- 选中文本动作仍严格只有：复制、翻译、搜索。
- 不恢复朗读选中、笔记、划线、摘录、分享。
- 中心点击开关上下栏保留。
- 阅读页全文搜索保留。
- Rule115、V761 页0保护、纵向滚动性能保护、V764-V766 内存/日志修复均保留。

## 构建过程
第一次 V770 build #101 / run `34310732542` 在 52 项继承门禁 Gate35 停止，原因是新门禁生成脚本错误地把 V769 内部已适配后的版本文本再次替换，属于 CI gate 适配错误，不是业务源码失败。已修正 gate 后重新正式构建。

最终正式构建：
- branch: `source-v770`
- verified source commit: `45065322deef4221a9ca525418b775d3ed32179f`
- workflow: `Build 简阅 v770`
- run: `34310833285` / #102
- result: SUCCESS
- inherited 52 stability gates: PASS
- inherited V769 selection/touch coexistence gates: PASS
- V770 instant selection activation gates: PASS
- compact settings-row layout gate: PASS
- full unit suite against unchanged V758 historical-failure baseline: PASS
- Release build: PASS
- APK metadata verification: PASS
- artifact upload: PASS
- artifact: `SimpleReader-v770-102`, id `10088390617`
- artifact digest: `sha256:3694ab74753b0426e5294839db67ac76baa952eef489c3c643ab278e823c7c33`

## 正式签名
正式签名继续在仓库外使用 SimpleReader Public V1 固定签名材料，未上传任何私钥或密码到 GitHub。
- package: `com.simplereader.app`
- versionCode: `2098000770`
- versionName: `770`
- signer cert SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme V2: PASS
- APK Signature Scheme V3: PASS
- STORED ZIP entries: 479
- 4-byte alignment failures: 0
- final signed APK SHA-256: `417c03fc1e383887084911b357cd3eb24f143a1c7f005f00f7b2e3e037201071`
- final package ZIP SHA-256: `1e8396df6873deb7bff3a54c3b03b8a12a9d51ce56c3c1e66d31185d47c6eafa`

## 实机验证目标
安装 V770 后测试：打开阅读设置 → 点亮“选中文本” → 立即关闭设置栏 → 不等待，马上长按当前屏幕文字。预期应立即进入系统文本选择，并显示复制/翻译/搜索。CI 能确认已经去掉 RecyclerView 延迟重绑路径，但最终触屏时序仍以实机验证为准。