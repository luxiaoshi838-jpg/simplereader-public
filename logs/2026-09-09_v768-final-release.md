# 简阅 V768 最终正式版记录

## 最终功能口径
- TXT目录 Rule115：`第xx章 / 节 / 回 / 卷 / 篇` 均允许整段标题末尾的标点不影响目录识别；正文中间标点不因此放宽，并保留章鱼/节课/回家等误识别保护。
- 阅读设置继续保留 V767 紧凑布局：字号区域缩小左移；“音量键翻页”和“选中文本”位于同一行；开启橘黄、关闭默认灰色，不显示“开/关”。
- “选中文本”开启后最终只保留：**复制、翻译、搜索**。
- 已移除朗读选中、笔记、划线、摘录、分享等选中文本动作。
- 阅读页原有全文搜索功能保持不变；V764-V766 的阅读器单实例、书架虚拟化、异常退出分类和进程日志隔离继续保留。

## GitHub 正式构建
- branch: `source-v768`
- source commit: `afb60b17bb2a9afb9474d2aa23388902d8d46bc9`
- workflow: `Build 简阅 v768`
- run: `34307801520` / #98
- result: SUCCESS
- inherited 52 stability gates: PASS
- Rule115 + selected-text final-action gate: PASS (`copy+translate+search`)
- compact settings-row layout gate: PASS
- full unit suite: 102 tests, 10 failures, all 10 exactly match the unchanged V758 accepted historical baseline; unexpected failures=0
- Release assemble: PASS
- APK metadata verification: PASS
- artifact upload: PASS
- artifact: `SimpleReader-v768-98`
- artifact id: `10087346521`
- artifact digest: `sha256:e58733954d0dec5944d58154a42f2603fc28bea052ab42210b296963bf7a4da2`

## 正式签名
正式签名在仓库外完成，私钥和密码没有上传 GitHub。
- package: `com.simplereader.app`
- versionCode: `2098000768`
- versionName: `768`
- minSdk: 26
- targetSdk: 35
- signer: `SimpleReader Public V1`
- signer certificate SHA-256: `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme V2: PASS
- APK Signature Scheme V3: PASS
- STORED ZIP entries: 479
- 4-byte alignment failures: 0
- final signed APK SHA-256: `25c347edaa7d5d35f86bc5144fa4f67320aff203836382cf2722a6ca81c5846e`
- final package ZIP SHA-256: `edb2a9f3777dd7625899b913246f9a73cb17584438dd9545aa1c0da3d0ba2305`
