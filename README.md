# SimpleReader Public

SimpleReader 是一个极简 Android 本地小说阅读器，主要面向 TXT、EPUB、CHM 等本地文件阅读场景。

## 当前开发基线

本公开仓库是后续开发仓库，保留当前有效代码，不提交任何签名私钥。

当前最新正式产出：

- 版本：v610
- versionCode：`2098000610`
- versionName：`610`
- APK SHA-256：`dd33f10d252ae44bb46199a23a98e292a24da32d81be473513cadf6364386ff8`
- 包名：`com.simplereader.app`
- 正式证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- 正式 APK：`SimpleReader_v610_confirmed_reader_ui_signed.apk`

v610 恢复已经确认的阅读实现：目录/书签使用 v600 侧边面板；背景使用颜色、纹理、质感三层独立组合；覆盖、仿真、平移使用不同的横向翻页实现。上下模式不再使用分页容器，整本正文在单一连续阅读区域中滚动，只在真实章节之间保留章节间距。横向分页不再给每页预留底部空白，只有章节最后一页会因正文不足自然留白；章节标题只按真实章节起点处理。

当前任务包括：

- 本地书架与分组管理；
- 兼容旧版 `SimpleReaderBackup` schemaVersion 1；
- 恢复书籍、分组、书签和阅读进度；
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
- TXT/EPUB/CHM 阅读
- 阅读进度
- 书签
- 目录识别
- 搜索定位
- 阅读设置
- 旧版数据备份恢复与原书重关联

详细签名规则见 [`SIGNING_POLICY.md`](SIGNING_POLICY.md)。
