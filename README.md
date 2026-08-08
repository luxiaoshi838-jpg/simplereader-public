# SimpleReader Public

SimpleReader 是一个极简 Android 本地小说阅读器，主要面向 TXT、EPUB 两种本地文件阅读场景。

## 当前开发基线

本公开仓库是后续开发仓库，保留当前有效代码，不提交任何签名私钥。

当前源码版本：

- 版本：v630
- versionCode：`2098000630`
- versionName：`630`
- 包名：`com.simplereader.app`
- 正式证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

v615 取消带 TXT 标识的默认封面，普通文本使用用户提供的通用封面；补充“（xxxx）第一章”等目录规则。分页缓存按版式哈希分别持久化，后台缓存不再覆盖实际阅读缓存；规则升级只从已有 UTF-8 缓存重建一次。大文件上下阅读改为有限页窗口，跳转后直接显示目标窗口并在滚动接近边界时平滑换窗，避免整本巨型 TextView 导致空白、卡死和闪退。阅读纹理直接使用上传包中的 1080×2340 完整纹理图；正文上下边界均保留一个字符高度。应用类已在清单注册，未捕获异常会在下次进入书架弹出可复制日志。

当前任务包括：

- 本地书架与分组管理；
- 兼容旧版 `SimpleReaderBackup` schemaVersion 1；
- 恢复书籍、分组、书签、阅读进度与本地目录/分页缓存；
- 换签或重装后重新选择原书总文件夹，并安全重新关联文件；
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

Debug APK 只用于功能预览，不能覆盖正式版本。

## 正式 APK

当前正式 APK 使用本地固定 keystore 签名：

```text
E:\脚本\小说阅读\签名文件\simplereader-public-v1.keystore
alias: simplereader-public-v1
```

仓库不保存 keystore、密码或 Base64 私钥。正式交付前必须验证：

- 包名 `com.simplereader.app`
- versionCode 高于上一正式版
- 证书 SHA-256 `315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK 文件 SHA-256
- 签名方案

## 一次性恢复旧数据与原书

选择“导入备份并自动关联”后，应用会先恢复书架、分组、书签和阅读进度；如果备份中的原 URI 仍可读取，会直接恢复阅读。签名更换或重装导致 Android 授权失效时，只需紧接着选择一次原书总文件夹，应用会扫描并自动关联所有匹配书籍，不会进入普通导入流程，也不会逐本重新选择。

旧版备份没有包含字号、背景色、翻页方式等 SharedPreferences，这些设置需要重新选择。

## 功能范围

- 本地书架
- 文件夹/文件导入
- 分组管理
- TXT/EPUB 阅读
- 阅读进度
- 书签
- 目录识别
- 搜索定位
- 阅读设置
- 旧版数据备份恢复与原书重关联

详细签名规则见 [`SIGNING_POLICY.md`](SIGNING_POLICY.md)。
