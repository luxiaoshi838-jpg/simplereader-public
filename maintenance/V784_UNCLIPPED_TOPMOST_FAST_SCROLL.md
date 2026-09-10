# v784 右缘滑块完整浮层修复

## 用户现象

v783 中书架页和分组页的 28dp × 52dp 右缘浮动滑块在屏幕最右侧显示时被隐藏边界裁掉，实际只能看到左侧一部分。允许保留视觉边框，但滑块本体必须完整可见，并位于书籍/分组内容的最上层。

## 根因

v783 为了取消专门的 28dp 滑块通道，将书架和分组 RecyclerView 通过 `marginEnd = -16dp` 延伸到父容器的物理右边缘，同时保留正常内容的 16dp 右内边距。父级 LinearLayout 默认会裁剪超出 padding 内容区域的子 View 绘制，因此 RecyclerView 的 `onDrawOver()` 虽然已经位于所有书籍/分组 item 之上，但滑块进入父容器右侧 padding 区域的部分仍会被祖先裁剪，造成“只显示左半边”的现象。

## v784 修复

- 主书架根容器加入 `android:clipChildren="false"` 与 `android:clipToPadding="false"`。
- 分组页程序化根 LinearLayout 同样设置 `clipChildren = false`、`clipToPadding = false`。
- 滑块继续使用 `RecyclerView.ItemDecoration.onDrawOver()` 绘制，因此处于书籍/分组 item 的绘制最上层。
- 保持用户确认的约 28dp × 52dp 竖向胶囊比例、三条横向抓手线、阴影/边框与 1dp 最右侧轨道。
- 不恢复 28dp 专用滑块空列；书架与分组继续使用 v783 已回收后的完整内容宽度。
- 不触碰 Android 系统 scrollbar API，保留 v773 Android 35 启动安全约束。
- 阅读页不接入 ShelfFastScroller，本次修改仅限书架和分组页。

## 回归门禁

新增：
- `tools/apply-v784-unclipped-topmost-fastscroll.py`
- `tools/v784-unclipped-topmost-fastscroll-gates.sh`
- `tools/v784-emulator-launch-test.sh`

同时将历史 v783 门禁改为行为校验，避免后续版本被历史 versionName/versionCode 锁死。

## 正式验收

- 分支：`source-v784`
- versionName：784
- versionCode：2098000784
- GitHub Actions：Build 简阅 v784 run #162 / `34461301270`
- v784 unclipped/topmost fast-scroll gate：PASS
- 52 项历史稳定性门禁：PASS
- v781/v782 阅读打开与分页回归门禁：PASS
- 完整单测基线：PASS
- Release build：PASS
- Android 35 实际安装 + Release 冷启动：PASS
- APK 校验与 artifact 上传：PASS

## 正式签名

- 签名身份：SimpleReader Public V1
- 证书 SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme v2：true
- APK Signature Scheme v3：true
- 最终签名 APK SHA-256：`49a33b9f7e96d5248a95778eff3bd0e2e4c7d4e5f835786c723c07c17cedb4b7`
- 最终 APK 大小：5,957,510 bytes
- ZIP 完整性：PASS
