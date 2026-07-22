from pathlib import Path

root = Path('.')
main_path = root / 'app/src/main/java/com/simplereader/app/ui/MainActivity.kt'
group_path = root / 'app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt'
settings_path = root / 'settings.gradle.kts'
feature_script_path = root / 'app/scripts/feature_scroll_chm_export.gradle.kts'
group_snippet_path = root / 'app/scripts/group_books_dialog_snippet.kt.txt'
backup_patch_path = root / 'app/scripts/backup_relink_migration.gradle.kts'
test_path = root / 'app/src/test/java/com/simplereader/app/ui/StableWorkflowContractTest.kt'
activity_test_path = root / 'app/src/test/java/com/simplereader/app/ui/GroupBooksActivityTest.kt'
ci_path = root / '.github/workflows/android-pr-validation.yml'
readme_path = root / 'README.md'

main = main_path.read_text(encoding='utf-8')

needle = '    private var shelfSearchQuery = ""\n'
replacement = needle + '    private var pendingBackupRestoreSummary: String? = null\n'
if replacement not in main:
    if needle not in main:
        raise SystemExit('missing shelfSearchQuery insertion point')
    main = main.replace(needle, replacement, 1)

old_launcher = '''    private val relinkRestoredDataLauncher = registerForActivityResult(
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
'''
new_launcher = '''    private val relinkRestoredDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingBackupRestoreSummary?.let { summary ->
                AlertDialog.Builder(this)
                    .setTitle("数据已恢复，原书尚未关联")
                    .setMessage(
                        "$summary\\n\\nAndroid 要求重新授权原书目录。" +
                            "之后可在“导入与恢复”中选择“补充关联未找到的书籍”。"
                    )
                    .setPositiveButton("确定", null)
                    .show()
            }
        } else {
            val permissionStatus = persistReadPermission(uri)
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null) {
                Toast.makeText(this, "无法读取所选文件夹", Toast.LENGTH_LONG).show()
            } else {
                relinkRestoredBooks(root, uri.toString(), permissionStatus)
            }
        }
    }
'''
if old_launcher in main:
    main = main.replace(old_launcher, new_launcher, 1)
elif new_launcher not in main:
    raise SystemExit('relink launcher shape changed unexpectedly')

old_import = '''    private fun importReaderData(sourceUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(sourceUri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("无法读取所选备份文件")
                    restoreReaderBackup(org.json.JSONObject(text))
                }
            }
            result.onSuccess { summary ->
                showBackupRestoreCompleted(summary)
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "导入过往数据失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
'''
new_import = '''    private fun importReaderData(sourceUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(sourceUri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("无法读取所选备份文件")
                    val summary = restoreReaderBackup(org.json.JSONObject(text))
                    val missingCount = bookRepository.getAllBooks().first().count { book ->
                        book.fileStatus == "MISSING" || book.filePath.startsWith("backup://missing/")
                    }
                    summary to missingCount
                }
            }
            result.onSuccess { (summary, missingCount) ->
                if (missingCount == 0) {
                    pendingBackupRestoreSummary = null
                    Toast.makeText(
                        this@MainActivity,
                        "$summary\\n原书文件已自动关联，可直接阅读。",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    pendingBackupRestoreSummary = summary
                    Toast.makeText(
                        this@MainActivity,
                        "数据已恢复，请选择一次原书总文件夹，系统将自动关联全部书籍。",
                        Toast.LENGTH_LONG
                    ).show()
                    relinkRestoredDataLauncher.launch(null)
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "导入过往数据失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
'''
if old_import in main:
    main = main.replace(old_import, new_import, 1)
elif new_import not in main:
    raise SystemExit('backup import function shape changed unexpectedly')

old_insert = '''                if (resolvedBookId == null) {
                    db.execSQL(
'''
new_insert = '''                if (resolvedBookId == null) {
                    val restoredFileStatus = if (canReadRestoredBookUri(filePath)) {
                        "AVAILABLE"
                    } else {
                        "MISSING"
                    }
                    db.execSQL(
'''
if old_insert in main:
    main = main.replace(old_insert, new_insert, 1)
elif 'val restoredFileStatus = if (canReadRestoredBookUri(filePath))' not in main:
    raise SystemExit('backup book insert point changed unexpectedly')

main = main.replace(
    '''                            "MISSING",
                            row.stringOrNull("txtCharset")
''',
    '''                            restoredFileStatus,
                            row.stringOrNull("txtCharset")
''',
    1,
)

old_dialog_marker = '    private fun showBackupRestoreCompleted(summary: String) {'
helper_marker = '    private fun canReadRestoredBookUri(filePath: String): Boolean {'
relink_marker = '    private fun relinkRestoredBooks('
if old_dialog_marker in main:
    start = main.index(old_dialog_marker)
    end = main.index(relink_marker, start)
    helper = '''    private fun canReadRestoredBookUri(filePath: String): Boolean {
        val uri = runCatching { Uri.parse(filePath) }.getOrNull() ?: return false
        if (uri.scheme != "content" && uri.scheme != "file") return false
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

'''
    main = main[:start] + helper + main[end:]
elif helper_marker not in main:
    raise SystemExit('restore completion function shape changed unexpectedly')

old_success = '''            result.onSuccess { (matched, remaining, scanned) ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("原书重新关联完成")
                    .setMessage(
                        "扫描到 $scanned 个文件，成功关联 $matched 本书。" +
                            (if (remaining > 0) {
                                "\\n仍有 $remaining 本未关联，可再次选择其他原书文件夹。"
                            } else {
                                "\\n全部书籍均已恢复读取权限。"
                            })
                    )
                    .setNegativeButton("完成", null)
                    .setPositiveButton(if (remaining > 0) "继续选择文件夹" else "确定") { _, _ ->
                        if (remaining > 0) relinkRestoredDataLauncher.launch(null)
                    }
                    .show()
'''
new_success = '''            result.onSuccess { (matched, remaining, scanned) ->
                val restoreSummary = pendingBackupRestoreSummary
                pendingBackupRestoreSummary = null
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("数据恢复与原书关联完成")
                    .setMessage(
                        buildString {
                            if (!restoreSummary.isNullOrBlank()) {
                                append(restoreSummary)
                                append("\\n\\n")
                            }
                            append("扫描到 $scanned 个文件，自动关联 $matched 本书。")
                            if (remaining > 0) {
                                append("\\n仍有 $remaining 本未找到；书架、分组、书签和进度均已保留。")
                                append("可稍后选择其他总目录补充关联，不需要重新导入备份。")
                            } else {
                                append("\\n全部书籍均已恢复读取权限，可直接打开阅读。")
                            }
                        }
                    )
                    .setPositiveButton("确定", null)
                    .show()
'''
if old_success in main:
    main = main.replace(old_success, new_success, 1)
elif new_success not in main:
    raise SystemExit('relink success block shape changed unexpectedly')

main = main.replace(
    '.setTitle("导入本地书籍")\n            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件", "导入过往数据", "重新关联过往书籍"))',
    '.setTitle("导入与恢复")\n            .setItems(arrayOf("选择按文件夹导入", "选择单个或多个文件", "导入备份并自动关联", "补充关联未找到的书籍"))',
    1,
)

old_group = '''    private fun showGroupBooksV2(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        startActivity(
            Intent(this, GroupBooksActivity::class.java)
                .putExtra(GroupBooksActivity.EXTRA_GROUP_ID, group.id)
                .putExtra(
                    GroupBooksActivity.EXTRA_GROUP_NAME,
                    group.displayName.ifBlank { group.name }
                )
        )
    }
'''
new_group = '''    private fun showGroupBooksV2(group: BookGroup, groupBooks: List<ShelfBookItem>) {
        val intent = Intent(this, GroupBooksActivity::class.java)
            .putExtra(GroupBooksActivity.EXTRA_GROUP_ID, group.id)
            .putExtra(
                GroupBooksActivity.EXTRA_GROUP_NAME,
                group.displayName.ifBlank { group.name }
            )
        runCatching { startActivity(intent) }
            .onFailure { error ->
                Toast.makeText(
                    this,
                    "打开分组失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
'''
if old_group in main:
    main = main.replace(old_group, new_group, 1)
elif new_group not in main:
    raise SystemExit('group routing method shape changed unexpectedly')

if 'showBackupRestoreCompleted' in main:
    raise SystemExit('old two-step backup dialog still present')
if '继续选择文件夹' in main:
    raise SystemExit('repeated relink loop still present')
if '导入备份并自动关联' not in main:
    raise SystemExit('new guided backup label missing')
main_path.write_text(main, encoding='utf-8')

group = group_path.read_text(encoding='utf-8')
group = group.replace('if (groupId <= 0L) {', 'if (groupId < 0L) {', 1)
group_path.write_text(group, encoding='utf-8')

settings = settings_path.read_text(encoding='utf-8')
settings = settings.replace('        apply(from = file("scripts/backup_relink_migration.gradle.kts"))\n', '')
settings_path.write_text(settings, encoding='utf-8')

feature = feature_script_path.read_text(encoding='utf-8')
feature = feature.replace(
    'check(mainSource.contains("导入过往数据")) { "无法扩展导入入口" }',
    'check(mainSource.contains("导入过往数据") || mainSource.contains("导入备份并自动关联")) { "无法扩展导入入口" }',
)
feature_script_path.write_text(feature, encoding='utf-8')

group_snippet_path.write_text(new_group.rstrip() + '\n', encoding='utf-8')

if backup_patch_path.exists():
    backup_patch_path.unlink()

test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text('''package com.simplereader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StableWorkflowContractTest {
    @Test
    fun `group card always opens dedicated group activity`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val method = source.substringAfter("private fun showGroupBooksV2(")
            .substringBefore("private fun showBookActionsV2(")
        assertTrue(method.contains("GroupBooksActivity::class.java"))
        assertTrue(method.contains("startActivity(intent)"))
        assertFalse(method.contains("AlertDialog.Builder"))
    }

    @Test
    fun `backup import immediately starts one guided relink`() {
        val source = projectFile("src/main/java/com/simplereader/app/ui/MainActivity.kt").readText()
        val method = source.substringAfter("private fun importReaderData(")
            .substringBefore("private suspend fun restoreReaderBackup(")
        assertTrue(method.contains("relinkRestoredDataLauncher.launch(null)"))
        assertFalse(source.contains("showBackupRestoreCompleted"))
        assertFalse(source.contains("继续选择文件夹"))
        assertTrue(source.contains("导入备份并自动关联"))
    }

    @Test
    fun `legacy backup relink build patch is disabled`() {
        val settings = projectFile("../settings.gradle.kts", "settings.gradle.kts").readText()
        assertFalse(settings.contains("backup_relink_migration.gradle.kts"))
    }

    private fun projectFile(vararg candidates: String): File {
        val cwd = File(System.getProperty("user.dir"))
        return candidates.asSequence()
            .map { File(cwd, it) }
            .firstOrNull { it.exists() }
            ?: error("Project file not found: ${candidates.joinToString()}")
    }
}
''', encoding='utf-8')

activity_test_path.write_text('''package com.simplereader.app.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupBooksActivityTest {
    @Test
    fun `restored group screen stays open even for legacy zero id`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            GroupBooksActivity::class.java
        )
            .putExtra(GroupBooksActivity.EXTRA_GROUP_ID, 0L)
            .putExtra(GroupBooksActivity.EXTRA_GROUP_NAME, "恢复分组")
        val controller = Robolectric.buildActivity(GroupBooksActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
}
''', encoding='utf-8')

ci_path.write_text('''name: Android PR validation

on:
  workflow_dispatch:
  pull_request:
  push:
    branches-ignore:
      - main

permissions:
  contents: read

concurrency:
  group: android-pr-validation-${{ github.ref }}
  cancel-in-progress: true

jobs:
  validate:
    name: Full compile, tests and immutable-source check
    runs-on: ubuntu-latest
    timeout-minutes: 35

    steps:
      - name: Checkout source
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Make Gradle wrapper executable
        run: chmod +x ./gradlew

      - name: Generate test version
        shell: bash
        run: |
          echo "SIMPLE_READER_VERSION_CODE=$((2026300000 + 10#$GITHUB_RUN_NUMBER))" >> "$GITHUB_ENV"
          echo "SIMPLE_READER_VERSION_NAME=2026.07.22.complete.${GITHUB_RUN_NUMBER}" >> "$GITHUB_ENV"

      - name: Run debug and release tests and builds
        run: ./gradlew clean testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease --stacktrace --console=plain

      - name: Verify build does not rewrite committed source
        shell: bash
        run: |
          set -euo pipefail
          git diff --exit-code -- \
            app/src/main \
            app/scripts \
            app/build.gradle.kts \
            settings.gradle.kts

      - name: Upload preview and unsigned release APKs
        uses: actions/upload-artifact@v4
        with:
          name: SimpleReader-complete-fix-${{ github.run_number }}
          path: |
            app/build/outputs/apk/debug/app-debug.apk
            app/build/outputs/apk/release/*.apk
          if-no-files-found: error
          retention-days: 7
''', encoding='utf-8')

readme = readme_path.read_text(encoding='utf-8')
marker = '## 从旧签名迁移\n'
note = '''## 一次性恢复旧数据与原书

选择“导入备份并自动关联”后，应用会先恢复书架、分组、书签和阅读进度；如果备份中的原 URI 仍可读取，会直接恢复阅读。签名更换或重装导致 Android 授权失效时，只需紧接着选择一次原书总文件夹，应用会扫描并自动关联所有匹配书籍，不会进入普通导入流程，也不会逐本重新选择。

'''
if note not in readme and marker in readme:
    readme = readme.replace(marker, note + marker, 1)
readme_path.write_text(readme, encoding='utf-8')

print('stable source integration prepared')
