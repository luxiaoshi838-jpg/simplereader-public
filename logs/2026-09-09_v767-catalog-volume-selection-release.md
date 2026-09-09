# 简阅 V767：目录尾标点、设置栏布局与正式构建签名

## 用户确认范围
本版只修改以下三类内容：
1. TXT目录：仅“第N章/第中文数字章”允许**整行末尾**标点不影响识别；第N节/回/卷/篇等不继承该例外。
2. 阅读设置：音量键翻页不显示“开/关”，开启橘黄、关闭默认灰色。
3. 仅恢复“选中文本”开关，不恢复翻译、朗读选中、笔记、划线、摘录、分享等其他历史功能。

追加布局要求：字号 A-/数值/A+ 区域缩小字体并左移；“选中文本”按钮放在“音量键翻页”右侧，同一行显示。

## 目录 Rule114
- `TxtParser.CATALOG_RULE_VERSION = 114`
- `DirectTxtCatalogV100.RULE_VERSION = 114`
- 新增仅匹配 `第 + 数字 + 章` 的专用规则。
- 仅剥离整行末尾连续 Unicode 标点/空白后重新判断；中间标点仍受原 Rule113 约束。
- `第1章鱼很好吃。` 仍不会因为该例外成为目录。
- 其他结构单位继续使用原 Rule113 边界规则。
- 新增 `DirectTxtCatalogV114ContractTest` 覆盖上述正反例。

## 阅读设置
第一行顺序固定为：
`字号 | A- | 当前字号 | A+ | 音量键翻页 | 选中文本`

尺寸：
- 字号标签：42dp / 14sp
- A-/A+：50×34dp / 15sp
- 当前字号：38dp / 14sp
- 音量键翻页：34dp 高 / 13sp
- 选中文本：34dp 高 / 13sp，位于音量键翻页右侧

开关状态：
- 开启背景：`#EF7A28`
- 关闭背景：`#4A4842`
- 按钮文字始终只显示 `音量键翻页` / `选中文本`，不显示“开/关”。

## 选中文本范围
只接回 Android TextView 原生文本选择能力：
- continuous TextView
- horizontal current-page TextView
- vertical RecyclerView page TextView

未恢复任何旧选择功能组合。专用 CI gate 明确禁止相关旧动作字符串/入口重新进入。

## 防回归
- 阅读页搜索代码块与 source-v766 字节级一致。
- V766 `App.kt` 与 `CrashLogStore.kt` 字节级一致，退出分类/进程日志隔离不变。
- 纵向阅读 32 项 CharSequence 缓存、SIMPLE break、禁用 hyphenation、页0保护等继续保留。

## GitHub Actions
正式成功运行：
- workflow: `Build 简阅 v767`
- run id: `34303867783`
- run number: `87`
- result: SUCCESS
- source persisted commit: `c46d03f8a27fa6006b2435b8dfb6c6e514e015c2`

通过：
- inherited 52 stability gates
- V767 catalog/volume/text-selection-only gates
- compact settings-row layout gate
- `git diff --check`
- full unit suite against unchanged V758 historical-failure baseline
- release build
- package/version/minSdk verification
- artifact upload

Artifact：
- id: `10086023692`
- name: `SimpleReader-v767-87`
- digest: `sha256:005ad894b92135fae97933e7020e73af1e56c51f309a79574a0c9700466801ed`

## 正式签名
GitHub 构建产物为 unsigned APK；正式签名仍在仓库外使用既有 SimpleReader Public V1 密钥完成，密钥和密码未上传 GitHub。

- package: `com.simplereader.app`
- versionCode: `2098000767`
- versionName: `767`
- minSdk: 26
- targetSdk: 35
- signer cert SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- STORED entries: 479
- 4-byte alignment failures: 0
- signed manifest identical to unsigned: PASS
- final signed APK SHA-256: `1e6bb3d3dc54d45f1bbb326f853820ef9b536c6793bf110de6c2c73dd3b13be9`
- final package ZIP SHA-256: `dfdfd0aafd85710103b9d475488419c240e638b44ec9431ebbc1f683eed5e2e0`
