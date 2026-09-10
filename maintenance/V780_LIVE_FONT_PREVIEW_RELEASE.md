# v780 字号即时预览与安全后台分页发布记录

## 目标

修复阅读页调整字号时长篇小说等待明显、连续改字号容易出现长时间无响应的问题，同时禁止回退到曾经出现过的“只重建当前页附近几页并替换全局 ReaderBook”方案。

## 实现原则

- 点击字号后，基于当前已经完整加载的 ReaderBook 立即刷新显示字号，不等待重新分页完成。
- 当前完整页表继续作为唯一导航真值；即时预览阶段不替换 readerBook，不制造局部页号。
- 后台重新生成新字号对应的完整页表，完成后按用户此刻最新 sourceOffset 原子切换。
- 后台提交使用 paginationEpoch、readerGeneration、bookId、fontRequestId 多重校验；切书、再次改字号、Reader 会话失效后，旧结果禁止提交。
- PageEngine 增加协作取消检查，旧分页任务能够主动停止，不继续把整部长篇小说计算完。
- 保留历史 stableOffset 恢复契约，同时字号后台提交时由最新 live sourceOffset 优先，避免用户后台分页期间继续阅读后位置倒退。

## 兼容性修正

构建过程中发现并处理：

1. PageEngine 新取消参数一度破坏历史尾随 lambda 调用方式；已恢复 ImageSpanProvider 的历史调用兼容。
2. v780 补丁入口改为幂等识别，已经应用的新 API 状态不会被重复补丁误判为失败。
3. 完整历史单测基线发现 stableTextOffsetWinsOverDeviceSpecificPageNumber 契约变化；已恢复原 stableOffset 入口形式，并继续保留 live sourceOffset 的字号提交优先级。

## 验收

正式发布构建：GitHub Actions `Build 简阅 v780` run #149，run id `34429196666`，head `fb648e905d715f532e5a345c9067e843763f3be1`。

以下均通过：

- 52 项继承稳定性门禁。
- 选择/触摸共存门禁。
- 即时选中文字门禁。
- v767 设置布局门禁。
- v771 handoff 与 v773 启动安全 fast-scroll 门禁。
- v780 字号即时预览 / 后台结果隔离专项门禁。
- PageEngine cooperative cancellation 单元测试。
- git diff --check。
- 完整单测相对 V758 已知失败基线：无新增失败。
- Release 构建。
- Android 35 Release 实际安装与冷启动：PASS；MainActivity cold launch 成功。
- APK 结构与包信息校验。

CI artifact：`SimpleReader-v780-149`，artifact id `10133960467`。

CI APK SHA-256：`c4eddd402f92e64d2e1e50cbbe21f60dcad89669ef9d636c99824aa514cfdd6c`。

本地使用既有 SimpleReader Public V1 官方 keystore 重新签名后：

- 文件：`SimpleReader_v780_signed.apk`
- 大小：5,949,318 bytes
- SHA-256：`6cb219e4a76ff7092b3b93d8657bf53756ee55d9707aed9629290efda43120cd`
- v2：true
- v3：true
- v1：false
- v4：false
- signers：1
- 证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- 与 v779 官方签名证书一致，可覆盖升级。

## 保留项

v771 handoff、v773 启动安全 fast-scroll、v775 日夜单图标结构、v776 仅书架太阳描边、v777 书架/分组 28dp 滑块通道、v778 轨道与滑块水平中心线完全重合均保持不变。