# SimpleReader Public

SimpleReader 是一个极简 Android 本地小说阅读器，主要面向 TXT、EPUB、CHM 等本地文件阅读场景。

## 公开库说明

这个仓库是公开源码与普通 CI 仓库，不保存、不恢复、不发布正式签名文件。

公开库生成的 APK 只用于编译验证或本地调试，不能视为正式发布包，也不保证能够覆盖安装正式版。

正式 APK、覆盖安装签名和发布流程保留在私有发布仓库中。

## 构建要求

- JDK 17
- Android SDK 35
- Gradle Wrapper

## 本地构建

Windows:

```powershell
.\gradlew.bat clean assembleDebug
```

Linux/macOS:

```bash
./gradlew clean assembleDebug
```

输出位置:

```text
app/build/outputs/apk/debug/app-debug.apk
```

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

## 安全边界

公开库 CI 不使用以下内容：

- 永久签名 keystore
- GitHub Actions Cache 中的正式签名文件
- 正式发布证书
- 可覆盖正式版的发布 APK

如需正式发布，请在私有发布仓库中执行受控构建。
