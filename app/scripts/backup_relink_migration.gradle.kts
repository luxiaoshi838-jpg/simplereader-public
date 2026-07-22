val applyBackupRelinkMigration = tasks.register("applyBackupRelinkMigration") {
    doLast {
        val mainFile = file("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        var source = mainFile.readText()

        if (!source.contains("private val relinkRestoredDataLauncher")) {
            val marker = "    override fun onCreate(savedInstanceState: Bundle?) {"
            check(source.contains(marker)) { "无法插入过往数据文件重关联选择器" }
            val launcher = """    private val relinkRestoredDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val permissionStatus = persistReadPermission(uri)
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null) {
                Toast.makeText(this, "无法读取所选文件夹", Toast.LENGTH_LONG).show()
            } else {
                relinkRestoredBooks(root, uri.toString(), permissionStatus)
            }
        }
    }

"""
            source = source.replace(marker, launcher + marker)
        }

        val oldSuccess = """            result.onSuccess { summary ->
                Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
            }.onFailure { error ->"""
        val newSuccess = """            result.onSuccess { summary ->
                showBackupRestoreCompleted(summary)
            }.onFailure { error ->"""
        if (source.contains(oldSuccess)) {
            source = source.replace(oldSuccess, newSuccess)
        } else {
            check(source.contains("showBackupRestoreCompleted(summary)")) {
                "无法连接过往数据恢复完成提示"
            }
        }

        // A content URI permission belongs to the installed app identity. After changing
        // signing identity and reinstalling, restored rows must be relinked before reading.
        source = source.replace(
            "row.stringOrNull(\"fileStatus\") ?: \"AVAILABLE\",",
            "\"MISSING\","
        )
        check(!source.contains("row.stringOrNull(\"fileStatus\") ?: \"AVAILABLE\",")) {
            "无法把恢复的旧书籍标记为待重新关联"
        }

        val oldImportOptions = """            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件", "导入过往数据")) { _, which ->
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
        val newImportOptions = """            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件", "导入过往数据", "重新关联过往书籍")) { _, which ->
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
                    3 -> relinkRestoredDataLauncher.launch(null)
                }
            }"""
        if (source.contains(oldImportOptions)) {
            source = source.replace(oldImportOptions, newImportOptions)
        } else {
            check(source.contains("重新关联过往书籍")) { "无法加入过往书籍重关联入口" }
        }

        if (!source.contains("private fun showBackupRestoreCompleted(")) {
            val marker = "    private fun showImportOptions() {"
            check(source.contains(marker)) { "无法插入过往数据重关联逻辑" }
            val helpers = """    private fun showBackupRestoreCompleted(summary: String) {
        AlertDialog.Builder(this)
            .setTitle("数据恢复完成")
            .setMessage(
                "${'$'}summary\n\n书架、分组、书签和阅读进度已经恢复。" +
                    "由于应用签名已更换，请重新选择原书所在的总文件夹，恢复文件读取权限。"
            )
            .setNegativeButton("稍后") { _, _ ->
                Toast.makeText(this, "之后可在“导入”中选择“重新关联过往书籍”", Toast.LENGTH_LONG).show()
            }
            .setPositiveButton("选择原书文件夹") { _, _ ->
                relinkRestoredDataLauncher.launch(null)
            }
            .show()
    }

    private fun relinkRestoredBooks(
        root: DocumentFile,
        sourceTreeUri: String,
        permissionStatus: PermissionStatus
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val candidates = mutableListOf<ImportCandidate>()
                    collectFolderCandidates(
                        folder = root,
                        sourceTreeUri = sourceTreeUri,
                        relativePath = root.name ?: "原书文件夹",
                        permissionStatus = permissionStatus,
                        candidates = candidates
                    )
                    val restoredBooks = bookRepository.getAllBooks().first().filter { book ->
                        book.fileStatus == "MISSING" || book.filePath.startsWith("backup://missing/")
                    }
                    val matches = com.simplereader.app.data.migration.BackupRelinkMatcher.match(
                        restoredBooks = restoredBooks,
                        candidates = candidates
                    )
                    matches.forEach { match ->
                        val candidate = match.candidate
                        bookRepository.update(
                            match.book.copy(
                                filePath = candidate.uri,
                                format = candidate.format,
                                fileName = candidate.displayName,
                                fileSize = candidate.size,
                                lastModified = candidate.lastModified,
                                sourceTreeUri = candidate.sourceTreeUri,
                                relativePath = candidate.relativeParentPath,
                                fileStatus = fileStatusFor(candidate.permissionStatus)
                            )
                        )
                    }
                    Triple(matches.size, restoredBooks.size - matches.size, candidates.size)
                }
            }
            result.onSuccess { (matched, remaining, scanned) ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("原书重新关联完成")
                    .setMessage(
                        "扫描到 ${'$'}scanned 个文件，成功关联 ${'$'}matched 本书。" +
                            (if (remaining > 0) {
                                "\n仍有 ${'$'}remaining 本未关联，可再次选择其他原书文件夹。"
                            } else {
                                "\n全部书籍均已恢复读取权限。"
                            })
                    )
                    .setNegativeButton("完成", null)
                    .setPositiveButton(if (remaining > 0) "继续选择文件夹" else "确定") { _, _ ->
                        if (remaining > 0) relinkRestoredDataLauncher.launch(null)
                    }
                    .show()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "重新关联失败：${'$'}{error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

"""
            source = source.replace(marker, helpers + marker)
        }

        mainFile.writeText(source)
    }
}

applyBackupRelinkMigration.configure {
    dependsOn("hideBookshelfFormatLabels")
    mustRunAfter("hideBookshelfFormatLabels")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(applyBackupRelinkMigration)
}
