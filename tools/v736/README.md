# v736 — 无目录缓存先快速筛选、再处理

基线：v735 成品二进制。v734 的误改不进入本版；classes3.dex 保持 v735/v733 不变。

## 用户固定判定
“全书架无目录书籍缓存”中，一本书只有同时满足以下 5 项，才在预筛选阶段直接排除，不进入正式处理总数：
1. 有目录：recognition.json 存在，completed=true，chapterCount>0；
2. 有分页信息：至少一个 pages.json 或 pages-*.json 属于本书且 pages 数组非空；
3. 文件名一致；
4. 文件大小一致；
5. 文件位置一致：分页 manifest 的 filePath 与当前书架 filePath 一致。

不额外使用 catalogRuleVersion、readerSettingsHash、lastModified 等条件扩大目标范围。

## 两阶段执行
阶段 1：快速元数据预筛选。
- 只读书架数据库字段以及每本 reader_page_cache/<bookId>/ 下的小型 recognition/page manifest JSON；
- 不读取正文；
- 不运行目录识别；
- 不运行 PageEngine.paginate；
- 正常情况下文件名/大小直接用书架记录快速比较；只有记录不足或与缓存不一致时，才调用 ReaderDocumentLoader.resolveDocument() 补取当前真实文件 name/length，该调用只查文件属性，不加载正文。
- 筛选阶段状态为“正在筛选无目录书籍”，不把 1/全书架、2/全书架 当作正式处理进度。

阶段 2：只处理筛选后的目标书。
- 扫描完成后才确定 targetTotal；
- 正式进度从 0/targetTotal 开始；
- 符合五项条件的已成功书不会进入处理循环；
- 每个目标最终归类为成功/失败/跳过；失败日志保留书名、失败阶段和原始异常原因。

## 交付约束
- v736 仅修改 AndroidManifest.xml 版本号和 classes5.dex 的 NoCatalogWorker；classes3.dex 与 v735 完全一致。
- APK 必须先 zipalign，再使用 Android 官方 apksigner 完成 v2/v3 签名；最后反向执行 zipalign -c 与 apksigner verify。
- 公开库不上传 APK、keystore 或密码。
