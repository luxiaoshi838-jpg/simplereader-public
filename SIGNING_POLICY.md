# 简阅永久签名与发布规则

## 当前永久签名

- 签名名称：简阅 Permanent Signing v2
- 证书 SHA-256：`6c2baa3cc6f51a3d7ec608d9350fe8f6610b66f54dc46bd6da8e1af959b8cbe5`
- 固定日期：2026-07-22
- 包名：`com.simplereader.app`
- 密钥别名：`simplereader-v2`
- 签名方案：V1 + V2

## 密钥保存规则

1. `SimpleReader_Permanent_v2.p12` 由项目所有者离线永久保存。
2. keystore、密码和 Base64 内容不得提交到 GitHub 仓库。
3. GitHub Actions 仅通过以下加密 Secrets 读取签名：
   - `SIMPLEREADER_SIGNING_KEY_BASE64`
   - `SIMPLEREADER_SIGNING_PASSWORD`
4. 正式签名工作流不得通过 `pull_request` 触发，只允许可信 `main` 分支或手动触发。
5. 普通 PR 可以构建无正式签名的 APK 用于编译和功能预览，但不得作为正式发布包。
6. 正式 APK 上传前必须验证证书 SHA-256、包名、minSdk、zipalign、V1 和 V2；任一不符立即失败。

## 从旧签名迁移

旧签名 v1：`51a02e78615c6c45d525d57f679b5dcb6fb33003493240523691d4dd806aec0e`。

v2 与 v1 不同，因此 v2 APK 不能覆盖安装 v1。迁移步骤固定为：

1. 在旧版执行“数据导出”，保存 `SimpleReaderBackup` JSON。
2. 确认备份文件可读取后卸载旧版。
3. 安装 v2 正式 APK。
4. 选择“导入过往数据”，恢复书籍、分组、书签和阅读进度。
5. 按提示重新选择原书总文件夹，恢复 Android 文件读取授权和书籍 URI。

旧版 schemaVersion 1 JSON 不包含原书文件本体，也不包含字号、背景色、翻页方式等 SharedPreferences。新版必须兼容读取 schemaVersion 1，并且不得因缺少设置字段拒绝导入。无法唯一匹配的原书必须跳过，禁止猜测绑定。

## 不可修改规则

1. v2 发布后，后续所有正式 APK 必须继续使用 v2 证书。
2. 禁止运行时或工作流自动生成替代证书。
3. 禁止把签名材料放入 Actions Cache，尤其禁止在公开仓库中使用缓存保存私钥。
4. Release APK 必须启用 R8 与资源压缩。
5. APK 超过 16 MiB 时警告，超过 50 MiB 时失败。
6. 任何未通过固定指纹验证的 APK 都不得交付。
7. 更换签名必须再次建立新迁移基线并明确告知无法覆盖安装，禁止静默换签。
