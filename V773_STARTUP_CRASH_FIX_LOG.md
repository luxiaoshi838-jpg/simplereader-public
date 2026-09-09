# SimpleReader V773 启动闪退修复与发布验收日志

日期：2026-09-09

## 用户反馈

V772 安装后点击图标立即闪退，无法进入软件。该问题出现在新增书架/分组快速滑块与书架日夜模式按钮之后。

## 真实 Android 35 崩溃定位

通过混淆 Release APK 在 Android 35 x86_64 模拟器进行真实 `adb install` + `am start -W` 冷启动测试，首次完整 logcat 定位到：

- `FATAL EXCEPTION: main`
- `Process: com.simplereader.app`
- `java.lang.NullPointerException`
- 崩溃入口：`android.view.View.onDrawScrollBars()`
- 具体原因：系统 `ScrollBarDrawable` 为 null，但新快速滑块附着逻辑调用了 `isScrollbarFadingEnabled = false`。在 Android 35 中，当平台滚动条本身已关闭时，这个调用仍可能创建滚动条缓存，却没有创建对应 `ScrollBarDrawable`，导致 RecyclerView 首次绘制系统滚动条路径时空指针崩溃。

## V773 修复

1. 主书架继续使用标准 `androidx.recyclerview.widget.RecyclerView`，不在 XML 中实例化自定义 RecyclerView。
2. 主书架 XML 固定 `android:scrollbars="none"`。
3. `ShelfFastScroller` 只作为 `RecyclerView.ItemDecoration + OnItemTouchListener + OnScrollListener` 附着。
4. `ShelfFastScroller.attach()` 不再调用以下任何系统滚动条状态 API：
   - `isScrollbarFadingEnabled`
   - `isVerticalScrollBarEnabled`
   - `isHorizontalScrollBarEnabled`
5. 书架与分组内继续共用同一 `ShelfFastScroller`。
6. 滑块规则保持用户要求：只有真实列表滚动后滑块才出现并可抓；只能按住当前可见滑块本体拖动；停止滚动约 1.2 秒后失效；平时不可抓。
7. 书架日/夜按钮继续位于搜索按钮左侧，并复用阅读页 `ReaderAppearance.toggleMode()`。
8. V771 全书架目录生成与 Reader 同书交接逻辑完整保留。

## 新增/强化门禁

- `tools/v773-startup-fastscroll-gates.sh`：禁止再次在 ShelfFastScroller 中调用系统滚动条 fading / enable API；锁定主书架 `android:scrollbars="none"`。
- `MainActivityStartupSmokeTest`：真实创建 MainActivity 并确认书架为标准 RecyclerView。
- `tools/v773-emulator-launch-test.sh`：对混淆 Release APK 临时测试签名后在 Android 35 实际安装、冷启动，检查：
  - 安装成功；
  - `MainActivity` 启动成功；
  - 应用进程保持存活；
  - `MainActivity` 处于 resumed/前台；
  - logcat 无 `FATAL EXCEPTION`；
  - 无 package startup crash marker。
- 真启动失败时必须保留并上传 pid / activity / logcat / start / install 等诊断文件。

## 最终 CI 验收

GitHub Actions：`Build 简阅 v773`，run number **122**，run id **34327020666**。

通过项目：

- 继承 52 项稳定性门禁：PASS
- 选中文本/触摸共存门禁：PASS
- 即时选中门禁：PASS
- 设置布局门禁：PASS
- V773 冷启动 + 快速滑块 + 日夜模式专项门禁：PASS
- V771 书架/阅读器同书交接回归：PASS
- 完整单元测试基线：PASS
- Release 混淆构建：PASS
- Android 35 真实 Release 安装/冷启动：PASS
- APK package/version 校验：PASS
- artifact 上传：PASS

Android 35 真启动结果文件：`V773_ANDROID35_RELEASE_COLD_LAUNCH_PASS`。

版本：

- package：`com.simplereader.app`
- versionName：`773`
- versionCode：`2098000773`
- compileSdk：35
- targetSdk：35
- minSdk：26

## 正式签名

正式 APK 继续使用原 `SimpleReader Public V1` 证书签名，没有创建或替换证书。

证书 SHA-256：

`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`

签名校验：

- APK Signature Scheme v2：true
- APK Signature Scheme v3：true
- signers：1
- ZIP integrity：PASS

最终已签名 APK SHA-256：

`3dc0743ca2f9d5703cc0531c240798f6b75fe84ce445650c24ba70945cf98aa6`

最终 APK 大小：5,945,054 bytes。
