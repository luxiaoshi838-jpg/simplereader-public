# 简阅 V763 内存释放记录

## V762 实机证据
用户在 V762 上的 Android 系统退出记录为 `REASON_OTHER (13)` / `normal_mem_pressure`，系统回收时 PSS 约 207701 kB。V762 新增的内存分量流水显示：

- process_start：totalPss ≈ 21.5 MB。
- reader_session_begin（分页前）：totalPss ≈ 241.8 MB，其中 java ≈ 19.1 MB、native ≈ 82.4 MB、graphics ≈ 112.3 MB。
- paginate_success：totalPss ≈ 338.4 MB，其中 java ≈ 66.2 MB、native ≈ 92.4 MB、graphics ≈ 150.3 MB。
- reader_onDestroy / reader_clean_finish：totalPss ≈ 300.6 MB。
- clean_finish +5 s：totalPss ≈ 287.7 MB，其中 java ≈ 66.9 MB、native ≈ 111.9 MB、graphics ≈ 72.2 MB。
- 数秒后再次打开 ReaderActivity：totalPss ≈ 383.9 MB，graphics ≈ 166.4 MB。

这证明高 PSS 并非仅由分页后的大书正文造成：大量 graphics/native 内存在分页前已存在，且 ReaderActivity 销毁后没有及时回落，再次打开阅读器会继续叠加图形资源。

## 源码审计定位
V762 源码存在以下会放大内存的结构：

1. MainActivity 书架使用非虚拟化 GridLayout；所有卡片/ImageView 同时常驻，打开 ReaderActivity 时 MainActivity 仍保留完整书架 View 树。
2. EPUB 书架封面此前直接按原尺寸解码；12 MiB LruCache 只限制缓存映射，不限制已经被 ImageView 强引用的 Bitmap。
3. ReaderBackgroundBitmapCache 是无界静态 `mutableMapOf<Int, Bitmap>()`，所有使用过的全尺寸阅读背景永久保留。
4. 背景选择器小预览也走全尺寸背景解码路径。
5. ReaderActivity 同时在 `readerRoot` 与 `android.R.id.content` 挂载全屏阅读背景，存在重复全屏图形层。
6. ReaderActivity.onDestroy 原先没有主动清空 RecyclerView adapter/recycled pool、rendered page cache、PagedReaderView 文本/背景/回调、ReaderImageRepository、ReaderBook/ReaderDocument 等强引用。

## V763 正式修改
V763 不修改 V758/V760/V761 已验证的纵向滚动、页零回跳和 ANR 日志路径，只处理内存释放：

- MainActivity：打开 ReaderActivity 前立即 `shelfGrid.removeAllViews()`、`coverBitmapCache.evictAll()`、清空默认封面缓存；MainActivity 不可见时禁止数据 Flow 重新构建书架；onResume 再按现有数据重建。
- EPUB 书架封面：按约 384×512 上限使用 power-of-two `inSampleSize` 解码；Activity 隐藏后异步封面结果不再写回 UI/cache。
- ReaderBackgrounds：无界全尺寸 Bitmap Map 改为“单个当前全尺寸 Bitmap + 4 MiB LruCache 的采样预览”；纯色预览不解码 Bitmap；支持 `onTrimMemory` / `onLowMemory` 清理。
- ReaderActivity：保留 `readerRoot` 阅读背景，移除 `android.R.id.content` 的第二个重复全屏背景。
- ReaderActivity.onDestroy：显式释放 RecyclerView listener/touch/adapter/recycled pool、32页 rendered cache、PagedReaderView 页面文本/背景/回调、continuous TextView、ReaderImageRepository、ReaderBook、ReaderDocument、Book、layout settings、search refs 和阅读背景缓存。
- V762 内存诊断完整保留，用于同一设备实测 V763 的 PSS/java/native/graphics 是否真正下降。

## 门禁
新增 `tools/v763-memory-release-gates.sh`，硬性要求：

- 书架在打开阅读器前释放；隐藏时不能被 Flow 重新构建。
- EPUB 封面必须采样解码。
- 阅读背景不能恢复无界 `mutableMapOf<Int, Bitmap>()`。
- ReaderActivity 销毁必须清空主要强引用和 RecyclerView/PagedReaderView 图形资源。
- V762 内存归因字段继续存在。
- `onScrolled()` / `verticalOnPageVisible()` 禁止进入任何释放、内存采样、CrashLogStore 热路径。
- V758 的 SIMPLE break strategy、hyphenation none、32页 cache、RecyclerView cache/prefetch 继续保留。

## GitHub 构建过程
### 第一次 run #68
- run id：`34119278674`
- Gate 1–14 PASS。
- Gate 15 失败：旧 V745 门禁硬编码要求 `android.R.id.content` 也挂载全屏背景，而 V763 正是有意移除这一重复层。
- 这是旧门禁 source-shape 与 V763 内存修复冲突，不是编译或产品逻辑失败。

因此只适配这一条历史门禁：V763 继承门禁仍要求 readerRoot 背景、系统栏、legacy preference migration 全部存在，但改为要求 `android.R.id.content.background = null`。历史 gate 文件在运行时备份并恢复。

### 正式成功 run #69
- run id：`34119471263`
- 开始：2026-09-07T11:59:43Z
- 完成：2026-09-07T12:04:23Z
- 总耗时：约 4 分 40 秒
- 结论：SUCCESS
- V763 inherited 52 gates：PASS
- V763 memory-release + scroll-isolation gates：PASS
- `git diff --check`：PASS
- V758 historical full-unit failure baseline：PASS（无 V763 新增失败）
- Release build：PASS
- APK package/version/minSdk validation：PASS
- artifact upload：PASS

验证后的正式源码由 Actions 固化到：
- branch：`source-v763`
- source commit：`6891a61c0deacf7d44d0207f6ffc100071f2b5b7`
- message：`v763：释放书架与阅读器图形内存并限制背景缓存 [skip ci]`

GitHub artifact：
- `SimpleReader-v763-69`
- artifact id：`10017737371`
- artifact ZIP digest：`sha256:092999590fe700d525d49edcec1cccf8ff544e9d5cb977d65e1595dbc107f4bf`
- unsigned APK SHA-256：`a6369bc2727c92b9a12eee1568ed3e786eac11b91d5383f9d8eb084136d57717`

## 正式签名与最终校验
使用 Google Drive“签名文件”中的 SimpleReader Public V1 固定密钥在仓库外签名；密钥与密码未提交 GitHub，也未写入日志。

- package：`com.simplereader.app`
- versionCode：`2098000763`
- versionName：`763`
- compileSdk / targetSdk：35
- minSdk：26
- application label：简阅
- certificate SHA-256：`315d7bbf06b2a0a16ea7efd7a5c7cd8e6371ab9b0f40ae380cc416e1472c8648`
- APK Signature Scheme v2：PASS
- APK Signature Scheme v3：PASS
- signers：1
- ZIP STORED entries：479
- 4-byte alignment failures：0
- unsigned/signed AndroidManifest.xml：byte-for-byte identical
- signed manifest SHA-256：`b1382a87eb68e5b4966f3b3b4e51de3259e7dcb7c8e72cbc12a9b16c4fb2dd89`
- final signed APK SHA-256：`03dfebb3686c234370ca685cdbcb754b17ec0be1f20770efb93c9e4d722ac991`

## 实机验证要求
V763 的实际内存降幅不能由 CI 静态门禁替代。安装后优先比较 V762/V763 同一设备、同一本大书的以下日志节点：

1. `shelf_release_before_open_reader`
2. `reader_session_begin`
3. `paginate_success`
4. `reader_onDestroy_after_release`
5. `post_reader_finish_5_seconds`

重点看 totalPss、javaPss、nativePss、graphicsPss，尤其确认：进入阅读器前不再出现 20 MB→240 MB 的书架/graphics 叠加；退出阅读器后 graphics/native 明显下降；连续开关阅读器不再阶梯式累积。