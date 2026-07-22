plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val applyRuntimeUiPatches = tasks.register("applyRuntimeUiPatches") {
    doLast {
        val sourceFile = file("src/main/java/com/simplereader/app/ui/MainActivity.kt")
        var source = sourceFile.readText()

        if (!source.contains("private fun showGroupManagement(")) {
            val oldActions = """            .setItems(arrayOf("打开分组", "重命名分组", "删除分组", "导入本地书籍")) { _, which ->
                when (which) {
                    0 -> showGroupBooksV2(group, groupBooks)
                    1 -> showRenameGroupDialog(group)
                    2 -> confirmDeleteGroup(group, groupBooks)
                    3 -> showImportOptions()
                }
            }"""
            val newActions = """            .setItems(arrayOf("打开分组", "管理分组", "重命名分组", "删除分组")) { _, which ->
                when (which) {
                    0 -> showGroupBooksV2(group, groupBooks)
                    1 -> showGroupManagement(group, groupBooks)
                    2 -> showRenameGroupDialog(group)
                    3 -> confirmDeleteGroup(group, groupBooks)
                }
            }"""
            check(source.contains(oldActions)) { "未找到分组操作代码，无法加入分组管理" }
            source = source.replace(oldActions, newActions)

            val renameMarker = "    private fun showRenameGroupDialog(group: BookGroup) {"
            val managementSnippet = file("scripts/group_management_snippet.kt.txt").readText().trimEnd()
            check(source.contains(renameMarker)) { "未找到分组重命名代码，无法插入分组管理" }
            source = source.replace(renameMarker, "$managementSnippet\n\n$renameMarker")
        }

        if (!source.contains("private fun showBatchGroupManagement(")) {
            val oldMoreButton = """        findViewById<TextView>(R.id.moreButton).apply {
            text = "⋮"
            setOnClickListener { showImportOptions() }
        }"""
            val newMoreButton = """        findViewById<TextView>(R.id.moreButton).apply {
            text = "⋮"
            contentDescription = "批量管理分组"
            setOnClickListener { showBatchGroupManagement() }
        }"""
            check(source.contains(oldMoreButton)) { "未找到右上角更多按钮，无法恢复批量分组管理" }
            source = source.replace(oldMoreButton, newMoreButton)

            val renameMarker = "    private fun showRenameGroupDialog(group: BookGroup) {"
            val batchSnippet = file("scripts/batch_group_management_snippet.kt.txt").readText().trimEnd()
            check(source.contains(renameMarker)) { "未找到分组重命名代码，无法插入批量管理" }
            source = source.replace(renameMarker, "$batchSnippet\n\n$renameMarker")
        }

        val renameStart = "    private fun showRenameGroupDialog(group: BookGroup) {"
        val renameEnd = "    private fun confirmDeleteGroup("
        val renameStartIndex = source.indexOf(renameStart)
        val renameEndIndex = source.indexOf(renameEnd, renameStartIndex + renameStart.length)
        check(renameStartIndex >= 0 && renameEndIndex > renameStartIndex) {
            "未找到分组重命名代码，无法应用持久化修复"
        }
        val renameSnippet = file("scripts/rename_group_dialog_snippet.kt.txt").readText().trimEnd()
        source = source.substring(0, renameStartIndex) +
            renameSnippet + "\n\n" +
            source.substring(renameEndIndex)

        val deleteGroupStart = "    private fun confirmDeleteGroup("
        val deleteGroupEnd = "    private fun showBookActions("
        val deleteGroupStartIndex = source.indexOf(deleteGroupStart)
        val deleteGroupEndIndex = source.indexOf(
            deleteGroupEnd,
            deleteGroupStartIndex + deleteGroupStart.length
        )
        check(deleteGroupStartIndex >= 0 && deleteGroupEndIndex > deleteGroupStartIndex) {
            "未找到删除分组代码，无法恢复两种处理方式"
        }
        val deleteGroupSnippet = file("scripts/delete_group_dialog_snippet.kt.txt").readText().trimEnd()
        source = source.substring(0, deleteGroupStartIndex) +
            deleteGroupSnippet + "\n\n" +
            source.substring(deleteGroupEndIndex)

        val oldBookActions = """            .setItems(arrayOf("打开", "导入分组", "删除书架", "导入本地书籍")) { _, which ->
                when (which) {
                    0 -> openBook(book.id)
                    1 -> showMoveBookToGroup(book)
                    2 -> confirmDeleteBook(book)
                    3 -> showImportOptions()
                }
            }"""
        val newBookActions = """            .setItems(arrayOf("打开", "移入分组", "删除书架")) { _, which ->
                when (which) {
                    0 -> openBook(book.id)
                    1 -> showMoveBookToGroup(book)
                    2 -> confirmDeleteBook(book)
                }
            }"""
        if (source.contains(oldBookActions)) {
            source = source.replace(oldBookActions, newBookActions)
        }

        val groupBooksStart = "    private fun showGroupBooksV2("
        val groupBooksEnd = "    private fun showBookActionsV2("
        val groupBooksStartIndex = source.indexOf(groupBooksStart)
        val groupBooksEndIndex = source.indexOf(
            groupBooksEnd,
            groupBooksStartIndex + groupBooksStart.length
        )
        check(groupBooksStartIndex >= 0 && groupBooksEndIndex > groupBooksStartIndex) {
            "未找到分组书籍页面，无法应用分类页面替换"
        }
        val groupBooksSnippet = file("scripts/group_books_dialog_snippet.kt.txt").readText().trimEnd()
        source = source.substring(0, groupBooksStartIndex) +
            groupBooksSnippet + "\n\n" +
            source.substring(groupBooksEndIndex)

        val shelfCardStart = "    private fun createShelfCard(): LinearLayout {"
        val shelfCardEnd = "    private fun addEmptyText("
        val shelfCardStartIndex = source.indexOf(shelfCardStart)
        val shelfCardEndIndex = source.indexOf(
            shelfCardEnd,
            shelfCardStartIndex + shelfCardStart.length
        )
        check(shelfCardStartIndex >= 0 && shelfCardEndIndex > shelfCardStartIndex) {
            "未找到书架卡片布局，无法应用三列自适应修复"
        }
        val shelfCardSnippet = file("scripts/shelf_card_snippet.kt.txt").readText().trimEnd()
        source = source.substring(0, shelfCardStartIndex) +
            shelfCardSnippet + "\n\n" +
            source.substring(shelfCardEndIndex)

        val oldScrollView = ".setView(ScrollView(this).apply { addView(grid) })"
        if (source.contains(oldScrollView)) {
            val newScrollView = """.setView(ScrollView(this).apply {
                  isVerticalScrollBarEnabled = true
                  isScrollbarFadingEnabled = false
                  scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
                  addView(grid)
              })"""
            source = source.replace(oldScrollView, newScrollView)
        }

        val folderModeStart = "    private fun showFolderImportMode("
        val folderModeEnd = "    private fun showFolderGroupingPreview("
        val startIndex = source.indexOf(folderModeStart)
        val endIndex = source.indexOf(folderModeEnd, startIndex + folderModeStart.length)
        check(startIndex >= 0 && endIndex > startIndex) {
            "未找到文件夹导入层级对话框，无法应用修复"
        }
        val folderModeSnippet = file("scripts/folder_import_mode_snippet.kt.txt").readText().trimEnd()
        source = source.substring(0, startIndex) +
            folderModeSnippet + "\n\n" +
            source.substring(endIndex)

        sourceFile.writeText(source)
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(applyRuntimeUiPatches)
}

android {
    namespace = "com.simplereader.app"
    compileSdk = 35

    val generatedVersionCode = (System.getenv("SIMPLE_READER_VERSION_CODE") ?: "2026202001")
        .toIntOrNull()
        ?: 2026202001
    val generatedVersionName = System.getenv("SIMPLE_READER_VERSION_NAME") ?: "2026.07.21.1"

    defaultConfig {
        applicationId = "com.simplereader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = generatedVersionCode
        versionName = generatedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            // Keep the permanent certificate, but emit both signature schemes.
            // Some Xiaomi/Meizu/OEM installers fail their pre-parser on V2-only APKs.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")

    implementation("com.google.code.gson:gson:2.10.1")

    // Public, established readers: Mozilla charset detection and pure-Java JChm.
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("com.github.chimenchen:jchmlib:v0.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

apply(from = "scripts/feature_scroll_chm_export.gradle.kts")