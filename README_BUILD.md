# SimpleReader 构建说明

## 环境要求

- Windows 64 位
- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35.x
- Gradle Wrapper：`gradle-8.2.1`
- Android Gradle Plugin：`8.1.2`
- Kotlin：`1.9.10`

## 构建 Debug APK

在项目根目录执行：

```powershell
.\gradlew.bat clean assembleDebug
```

成功后 APK 输出路径：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 构建 Release APK

```powershell
.\gradlew.bat assembleRelease
```

当前项目尚未配置正式签名，Release 包仅用于本地验证。

## 安装到手机

手机开启 USB 调试后执行：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

红米 K80 可直接使用同一 Debug APK 安装测试。项目 `minSdk = 26`，理论支持 Android 8.0 及以上系统。

## 最近一次构建记录

构建日志会放在：

```text
build-logs\
```

APK 路径：

```text
app\build\outputs\apk\debug\app-debug.apk
```

如果构建失败，请优先检查 JDK 17、Android SDK 35、网络依赖下载和 Gradle Wrapper 是否完整。
