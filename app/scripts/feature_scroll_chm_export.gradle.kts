tasks.named("applyRuntimeUiPatches").configure {
    doLast {
        val mainFile = file("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        var mainSource = mainFile.readText()

        if (!mainSource.contains("private val exportDataLauncher")) {
            val marker = "    override fun onCreate(savedInstanceState: Bundle?) {"
            check(mainSource.contains(marker)) { "无法插入数据备份选择器" }
            val launchers = """    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            rememberBackupLocation(uri)
            exportReaderData(uri)
        }
    }

    private val importDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            importReaderData(uri)
        }
    }
"""
            mainSource = mainSource.replace(marker, launchers + "\n" + marker)
        }

        if (!mainSource.contains("findViewById<TextView>(R.id.exportButton)")) {
            val marker = "        findViewById<TextView>(R.id.importButton).apply {"
            check(mainSource.contains(marker)) { "无法连接数据导出按钮" }
            val exportButton = """        findViewById<TextView>(R.id.exportButton).apply {
            text = "数据导出"
            setOnClickListener { showDataExportOptions() }
        }
"""
            mainSource = mainSource.replace(marker, exportButton + "\n" + marker)
        }

        if (!mainSource.contains("private fun showDataExportOptions(")) {
            val marker = "    private fun showImportOptions() {"
            check(mainSource.contains(marker)) { "无法插入数据导出与恢复逻辑" }
            val snippet = file("scripts/data_export_snippet.kt.txt").readText().trimEnd()
            mainSource = mainSource.replace(marker, snippet + "\n\n" + marker)
        }

        val oldImportOptions = """            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件")) { _, which ->
                when (which) {
                    0 -> openFolderLauncher.launch(null)
                    1 -> openFilesLauncher.launch(
                        arrayOf("text/plain", "application/epub+zip", "application/octet-stream")
                    )
                }
            }"""
        val newImportOptions = """            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件", "导入过往数据")) { _, which ->
                when (which) {
                    0 -> openFolderLauncher.launch(null)
                    1 -> openFilesLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "application/epub+zip",
                            "application/vnd.ms-htmlhelp",
                            "application/x-chm",
                            "application/octet-stream"
                        )
                    )
                    2 -> importDataLauncher.launch(
                        arrayOf("application/json", "text/json", "application/octet-stream")
                    )
                }
            }"""
        if (mainSource.contains(oldImportOptions)) {
            mainSource = mainSource.replace(oldImportOptions, newImportOptions)
        } else {
            check(
                mainSource.contains("导入过往数据") ||
                    mainSource.contains("导入备份并自动关联")
            ) { "无法扩展导入入口" }
        }

        mainSource = mainSource.replace(
            "未发现可导入的 TXT 或 EPUB 文件",
            "未发现可导入的 TXT、EPUB 或 CHM 文件"
        )
        mainSource = mainSource.replace(
            "name.endsWith(\".epub\", ignoreCase = true) -> \"EPUB\"\n            else -> null",
            "name.endsWith(\".epub\", ignoreCase = true) -> \"EPUB\"\n            name.endsWith(\".chm\", ignoreCase = true) -> \"CHM\"\n            else -> null"
        )
        mainSource = mainSource.replace(
            ".removeSuffixIgnoreCase(\".epub\"),",
            ".removeSuffixIgnoreCase(\".epub\")\n                                .removeSuffixIgnoreCase(\".chm\"),"
        )
        mainSource = mainSource
            .replace("ScrollView(this).apply", "FastScrollView(this).apply")
            .replace("FastFastScrollView", "FastScrollView")
            .replace("\"${'$'}action完成：", "\"${'$'}{action}完成：")

        val dedupSnippet = file("scripts/normal_import_dedup_snippet.kt.txt").readText().trimEnd()
        val importFunctionMarker = "    private suspend fun importCandidate("
        val buildFunction = dedupSnippet.substringBefore(importFunctionMarker).trimEnd()
        val importFunction = dedupSnippet.substring(dedupSnippet.indexOf(importFunctionMarker)).trimEnd()

        val buildStartMarker = "    private suspend fun buildCandidate("
        val importCandidatesMarker = "    private suspend fun importCandidates("
        val buildStart = mainSource.indexOf(buildStartMarker)
        val importCandidatesStart = mainSource.indexOf(importCandidatesMarker, buildStart + buildStartMarker.length)
        check(buildStart >= 0 && importCandidatesStart > buildStart) { "无法替换普通导入预查重" }
        mainSource = mainSource.substring(0, buildStart) +
            buildFunction + "\n\n" +
            mainSource.substring(importCandidatesStart)

        val importStart = mainSource.indexOf(importFunctionMarker)
        val resolveGroupMarker = "    private suspend fun resolveGroupId("
        val resolveGroupStart = mainSource.indexOf(resolveGroupMarker, importStart + importFunctionMarker.length)
        check(importStart >= 0 && resolveGroupStart > importStart) { "无法替换写入前二次查重" }
        mainSource = mainSource.substring(0, importStart) +
            importFunction + "\n\n" +
            mainSource.substring(resolveGroupStart)

        if (!mainSource.contains("private const val BACKUP_SCHEMA_VERSION")) {
            val marker = "        private const val FOLDER_IMPORT_LIMIT = 10_000"
            check(mainSource.contains(marker)) { "无法写入备份格式常量" }
            val constants = """        private const val BACKUP_SCHEMA_VERSION = 1
        private const val BACKUP_PREFS = "simple_reader_backup"
        private const val BACKUP_URI_KEY = "backup_uri"
"""
            mainSource = mainSource.replace(marker, constants + marker)
        }
        mainFile.writeText(mainSource)

        val readerFile = file("src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
        var readerSource = readerFile.readText()

        val loadBookStartMarker = "    private fun loadBook() {"
        val loadBookContentMarker = "    private fun loadBookContent("
        val loadBookStart = readerSource.indexOf(loadBookStartMarker)
        val loadBookContentStart = readerSource.indexOf(
            loadBookContentMarker,
            loadBookStart + loadBookStartMarker.length
        )
        check(loadBookStart >= 0 && loadBookContentStart > loadBookStart) {
            "无法替换阅读页文件访问保护逻辑"
        }
        val readerGuard = file("scripts/reader_open_guard_snippet.kt.txt").readText().trimEnd()
        readerSource = readerSource.substring(0, loadBookStart) +
            readerGuard + "\n\n" +
            readerSource.substring(loadBookContentStart)

        // CHM stays isolated from ReaderActivity bytecode. Loading a TXT or EPUB
        // never initializes the optional native CHM engine.
        readerSource = readerSource.replace(
            "import com.simplereader.app.parser.ChmParser\n",
            ""
        )

        val chmMarker = "                            else -> LoadedContent(\"\")"
        check(readerSource.contains(chmMarker)) { "无法接入隔离式 CHM 阅读解析器" }
        val chmBranch = """                            "CHM" -> LoadedContent(
                                text = readChmTextIsolated(input)
                            )
"""
        readerSource = readerSource.replace(chmMarker, chmBranch + chmMarker)

        if (!readerSource.contains("private fun readChmTextIsolated(")) {
            val setupMarker = "    private fun setupUI() {"
            check(readerSource.contains(setupMarker)) { "无法插入隔离式 CHM 调用" }
            val helper = """    private fun readChmTextIsolated(input: java.io.InputStream): String {
        return try {
            val parserClass = Class.forName("com.simplereader.app.parser.ChmParser")
            val parserInstance = parserClass.getField("INSTANCE").get(null)
            val method = parserClass.getMethod("readText", java.io.InputStream::class.java)
            method.invoke(parserInstance, input) as? String
                ?: error("CHM 解析器未返回文本")
        } catch (error: Throwable) {
            val cause = if (error is java.lang.reflect.InvocationTargetException) {
                error.targetException ?: error
            } else {
                error
            }
            throw IllegalStateException(
                "CHM 解析组件不可用：${'$'}{cause.message ?: cause.javaClass.simpleName}",
                cause
            )
        }
    }

"""
            readerSource = readerSource.replace(setupMarker, helper + setupMarker)
        }

        readerSource = readerSource.replace(
            "            } catch (e: Exception) {\n                showError(\"打开书籍失败：${'$'}{e.message ?: \"未知错误\"}\")",
            "            } catch (e: Throwable) {\n                showError(\"打开书籍失败：${'$'}{e.message ?: e.javaClass.simpleName}\")"
        )

        val normalStartup = """        bookId = intent.getLongExtra("bookId", 0L)
        setupUI()
        loadBook()"""
        val testedStartup = """        bookId = intent.getLongExtra("bookId", 0L)
        setupUI()
        val diagnosticText = intent.getStringExtra("readerDiagnosticText")
        if (diagnosticText != null) {
            val parsed = TxtParser.readText(
                java.io.ByteArrayInputStream(diagnosticText.toByteArray(Charsets.UTF_8)),
                Charsets.UTF_8.name()
            )
            currentContent = parsed.text
            currentPosition = 0
            txtStreamingMode = false
            progressLoaded = true
            contentLoaded = true
            openSucceeded = true
            displayContent()
        } else {
            loadBook()
        }"""
        if (readerSource.contains(normalStartup)) {
            readerSource = readerSource.replace(normalStartup, testedStartup)
        } else {
            check(readerSource.contains(testedStartup)) { "无法接入阅读页真实启动测试入口" }
        }

        readerFile.writeText(readerSource)
    }
}

configurations.configureEach {
    exclude(group = "org.apache.tika")
}

dependencies.add(
    "implementation",
    "com.sorrowblue.sevenzipjbinding:7-Zip-JBinding-4Android:16.02-2.4"
)
