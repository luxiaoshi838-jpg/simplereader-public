# 简阅永久签名与发布规则

## 当前永久签名

- 当前最新产出：v609
- 版本号：`2098000609`
- 版本名：`609`
- APK SHA-256：`641517f30f501b793002713dce1c238216269d9695dde71cb1b9e457fa71d38a`
- 签名名称：简阅 Public V1 本地签名
- 证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- 包名：`com.simplereader.app`
- 密钥别名：`simplereader-public-v1`
- 本地 keystore：`E:\脚本\小说阅读\签名文件\simplereader-public-v1.keystore`
- 签名方案：V2、V3

## 密钥保存规则

1. `simplereader-public-v1.keystore` 由项目所有者在本地永久保存。
2. keystore、密码和 Base64 内容不得提交到 GitHub 仓库。
3. 当前正式 APK 以本地固定 keystore 签名，GitHub workflow 不得使用旧的 `6c2baa...` v2 指纹作为正式产出基线。
4. 普通 PR 可以构建无正式签名的 APK 用于编译和功能预览，但不得作为正式发布包。
5. 正式 APK 交付前必须验证证书 SHA-256、包名、versionCode、versionName 和签名方案；任一不符立即失败。
6. PR 验证构建不得覆盖源码中的 versionCode，否则验证产物不能用于覆盖安装判断。

## 覆盖安装规则

后续正式 APK 必须满足：

1. 包名继续为 `com.simplereader.app`。
2. 证书继续为 `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`。
3. `versionCode` 必须大于当前最新正式版 `2098000609`。
4. `versionName` 必须保留清晰序列号，便于确认新版本能覆盖旧版本。
5. APK 输出放在 `E:\脚本\小说阅读\apk-output`，旧版归档到 `E:\脚本\小说阅读\apk-output\旧版`。
6. 版本号比较必须使用 Android 的整数 `versionCode`，不能只比较文件名或 versionName。

## UI 发布规则

1. `UI_BASELINE.md` 中列出的禁止实现不得恢复。
2. `tools/verify-ui-policy.sh` 和 `ui-lock.sha256` 必须通过后才能生成正式 APK。
3. 分页、缓存、搜索、目录和书签定位修改不得顺带修改锁定 UI；确需修改时必须由项目所有者明确提出。
4. 旧 v13/v14、规则纹理封面、格式文字封面及旧目录面板不得作为恢复源。

## 备份兼容

新版必须兼容读取 `SimpleReaderBackup` schemaVersion 1，并且不得因缺少字号、背景色、翻页方式等 SharedPreferences 字段拒绝导入。

旧版 schemaVersion 1 JSON 不包含原书文件本体。恢复时应允许用户选择包含原书的总文件夹，并按备份中的相对路径、文件名和文件大小自动重新关联。无法唯一匹配的原书必须跳过，禁止猜测绑定。

## 不可修改规则

1. 禁止运行时或工作流自动生成替代正式证书。
2. 禁止把签名材料放入 Actions Cache，尤其禁止在公开仓库中使用缓存保存私钥。
3. Release APK 必须启用 R8 与资源压缩。
4. APK 超过 16 MiB 时警告，超过 50 MiB 时失败。
5. 任何未通过固定指纹验证的 APK 都不得交付。
6. 更换签名必须再次建立新迁移基线并明确告知无法覆盖安装，禁止静默换签。
