# SimpleReader Public

SimpleReader 是一个极简 Android 本地小说阅读器，主要面向 TXT、EPUB、CHM 等本地文件阅读场景。

## 当前开发基线

本公开仓库是后续唯一开发仓库，保留当前有效代码，不复制原私有仓库的 Git 历史，也不提交任何签名私钥。

当前任务包括：

- 书架与分组封面不再显示 TXT、EPUB、CHM 格式标识；
- 兼容旧版 `SimpleReaderBackup` schemaVersion 1；
- 恢复书籍、分组、书签和阅读进度；
- 换签重装后重新选择原书总文件夹，并安全重新关联文件；
- 无法唯一匹配的同名文件会跳过，不会猜测绑定。

## 构建要求

- JDK 17
- Android SDK 35
- Gradle Wrapper

## 普通编译与预览

Pull Request 和普通开发分支只运行无永久签名的编译、单元测试和 Debug APK 预览：

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 只用于功能预览，不能覆盖正式 v2 版本。

## 正式 v2 APK

正式发布工作流只允许可信 `main` 分支或手动触发，并从 GitHub Actions Secrets 读取永久签名：

```text
SIMPLEREADER_SIGNING_KEY_BASE64
SIMPLEREADER_SIGNING_PASSWORD
```

仓库不保存 keystore、密码或 Base64 私钥。工作流在上传 APK 前强制验证：

- 包名 `com.simplereader.app`
- minSdk 26
- V1 和 V2 签名
- 证书 SHA-256 `6c2baa3cc6f51a3d7ec608d9350fe8f6610b66f54dc46bd6da8e1af959b8cbe5`
- zipalign
- Release 单元测试

## 一次性恢复旧数据与原书

选择“导入备份并自动关联”后，应用会先恢复书架、分组、书签和阅读进度；如果备份中的原 URI 仍可读取，会直接恢复阅读。签名更换或重装导致 Android 授权失效时，只需紧接着选择一次原书总文件夹，应用会扫描并自动关联所有匹配书籍，不会进入普通导入流程，也不会逐本重新选择。

## 从旧签名迁移

v2 签名与旧版签名不同，第一次不能覆盖安装。固定迁移流程：

1. 在旧版执行“数据导出”，保存 JSON；
2. 确认备份文件可读取；
3. 卸载旧版；
4. 安装正式 v2 APK；
5. 选择“导入过往数据”；
6. 按提示重新选择原书总文件夹。

旧版备份没有包含字号、背景色、翻页方式等 SharedPreferences，这些设置需要重新选择。

## 功能范围

- 本地书架
- 文件夹/文件导入
- 分组管理
- TXT/EPUB/CHM 阅读
- 阅读进度
- 书签
- 目录识别
- 搜索定位
- 阅读设置
- 旧版数据备份恢复与原书重关联

详细签名规则见 [`SIGNING_POLICY.md`](SIGNING_POLICY.md)。
