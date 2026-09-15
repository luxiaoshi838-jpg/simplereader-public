# v787 书架排序、外形与宫格/列表修复

## 用户要求

1. 顶层书籍与分组同权排序：不再固定“分组在前、书籍在后”；按最近活动时间统一排序。
2. 主书架宫格封面比例对齐分组内书籍：主页书籍与分组封面高度从 112dp 调整为 148dp。
3. “书架管理”去掉“异常日志（最近20条）”入口；崩溃历史存储和新崩溃弹窗仍保留，数据导出日志能力不动。
4. 顶部原“编辑”按钮改为“列表/宫格”切换：默认宫格；点击切列表；再次点击回宫格；选择模式仍由长按书籍/分组进入，并继续让该位置显示“操作/删除”。

## 实现

- 顶层分组的排序时间 = 该分组中最近活动书籍的 activityTime。
- 顶层未分组书籍的排序时间 = 书籍 activityTime。两者放入同一个 topLevelItems 后一次排序。
- activityTime 继续沿用既有定义 max(lastReadTime, addTime)，没有另造排序时间口径。
- 宫格继续三列；列表使用 LinearLayoutManager，并使用 72x104dp 纵向缩略图 + 右侧文字。
- 宫格/列表偏好写入 SharedPreferences，首次安装/没有偏好时保持默认宫格。
- 长按选择逻辑没有改入口；退出选择模式后按钮恢复当前布局对应的“列表/宫格”。

## 回归保护

- 新增 ShelfV787ContractTest。
- 更新 CrashLogAndCoverContractTest：历史日志仍保留，但不再要求书架管理菜单存在重复入口。
- 正式构建仍必须满足 SIGNING_POLICY.md：包名 com.simplereader.app、固定 Public V1 证书指纹、R8/资源压缩及版本号校验。
- 2026-09-15：补丁已自动应用到 `source-v787`；由用户身份提交本记录更新，以触发完整 Android PR validation 对实际 v787 源码进行验证。

## 2026-09-15 最终验收

- v787 专项 Debug/Release 契约测试：通过。
- 完整单测：与仓库既有 V758/V759 的 10 条已知失败基线完全一致，无新增失败；基线门通过。
- Debug 构建：通过。
- Release 构建：通过。
- GitHub 发布构建 run：`34961091573`，用于生成 unsigned Release APK，并校验包名、版本号、APK 大小及提供公开 Android `apksigner/aapt2` 工具。
- 正式密钥未上传 GitHub；从 Google Drive `签名文件/简阅签名文件.zip` 读取固定 Public V1 keystore，在本地完成签名。
- 正式签名方案：APK Signature Scheme V2、V3 均验证通过。
- 正式证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`。
- 包名：`com.simplereader.app`。
- versionCode：`2098000787`。
- versionName：`787`。
- 正式 APK 大小：`5961606` bytes。
- 正式 APK SHA-256：`855384224749d1becc88433ccc7da3152a9e1c57f9bac2d32c3a07e5647a152d`。
- 正式 APK 对应功能源码构建提交：`afc92f4d19241f051e2d731e0dcb2ae793c8bf82`；本提交仅补写验收日志，不改变 APK 功能源码。
