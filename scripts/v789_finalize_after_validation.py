from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:160]!r}")
    write(path, text.replace(old, new, 1))


# The mode button always names the CURRENT layout, per the locked UI requirement.
replace_once(
    "app/src/main/res/layout/activity_main.xml",
    'android:id="@+id/editButton"\n            android:layout_width="52dp"\n            android:layout_height="match_parent"\n            android:gravity="center"\n            android:text="列表"',
    'android:id="@+id/editButton"\n            android:layout_width="52dp"\n            android:layout_height="match_parent"\n            android:gravity="center"\n            android:text="宫格"'
)

replace_once(
    "app/src/main/java/com/simplereader/app/ui/MainActivity.kt",
    '''        editButton.text = if (shelfListMode) "宫格" else "列表"
        editButton.contentDescription = if (shelfListMode) {
            "当前列表模式，点击切换为宫格"
        } else {
            "当前宫格模式，点击切换为列表"
        }''',
    '''        editButton.text = if (shelfListMode) "列表" else "宫格"
        editButton.contentDescription = if (shelfListMode) {
            "当前列表模式，点击切换为宫格"
        } else {
            "当前宫格模式，点击切换为列表"
        }'''
)

# Update the v788 contract so it locks current-mode labels rather than destination labels.
replace_once(
    "app/src/test/java/com/simplereader/app/ui/ShelfV788LayoutSwitchContractTest.kt",
    '''    @Test fun `mode button names the destination layout`() {
        assertTrue(layout.contains("android:text=\\\"列表\\\""))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \\\"宫格\\\" else \\\"列表\\\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }''',
    '''    @Test fun `mode button names the current layout`() {
        assertTrue(layout.contains("android:text=\\\"宫格\\\""))
        assertTrue(main.contains("editButton.text = if (shelfListMode) \\\"列表\\\" else \\\"宫格\\\""))
        assertTrue(main.contains("当前列表模式，点击切换为宫格"))
        assertTrue(main.contains("当前宫格模式，点击切换为列表"))
    }'''
)

# Old v725 contracts assumed clipboard-copy log UX and an older three-item export menu.
# The new requirement is file-based complete logs with separate crash/operation destinations.
replace_once(
    "app/src/test/java/com/simplereader/app/operation/V725FeatureContractTest.kt",
    '''        assertTrue(exportBlock.contains("arrayOf(\\\"导出\\\", \\\"同步\\\", \\\"日志\\\")"))
        assertTrue(exportBlock.contains("OperationLogDialogs.showLogHub(this)"))''',
    '''        assertTrue(exportBlock.contains("arrayOf(\\\"导出\\\", \\\"同步\\\", \\\"崩溃日志\\\", \\\"操作日志\\\", \\\"日志位置设置\\\")"))
        assertTrue(exportBlock.contains("showCrashHistoryDialog()"))
        assertTrue(exportBlock.contains("OperationLogDialogs.showOperationList(this)"))
        assertTrue(exportBlock.contains("showLogLocationSettings()"))'''
)

replace_once(
    "app/src/test/java/com/simplereader/app/operation/V725FeatureContractTest.kt",
    '''    @Test
    fun logListHasNoCopyButtonButDetailHasCopyAndDraggableSeekBar() {''',
    '''    @Test
    fun logListHasNoCopyButtonAndDetailSavesCompleteFileWithDraggableSeekBar() {'''
)

replace_once(
    "app/src/test/java/com/simplereader/app/operation/V725FeatureContractTest.kt",
    '''        assertTrue(detailBlock.contains("setPositiveButton(\\\"复制\\\")"))''',
    '''        assertTrue(detailBlock.contains("setPositiveButton(\\\"保存日志文件\\\")"))
        assertTrue(detailBlock.contains("DiagnosticLogFiles.exportOperationSnapshotNow(activity)"))
        assertFalse(detailBlock.contains("setPositiveButton(\\\"复制\\\")"))'''
)

# Crash-log contract now verifies durable file export rather than clipboard copy text.
replace_once(
    "app/src/test/java/com/simplereader/app/ui/CrashLogAndCoverContractTest.kt",
    '''        assertTrue(main.contains("日志已复制；历史记录仍保留"))
        assertFalse(main.contains("复制并清除"))''',
    '''        assertTrue(main.contains("setPositiveButton(\\\"保存日志文件\\\")"))
        assertTrue(main.contains("DiagnosticLogFiles.exportCrashSnapshotNow(this)"))
        assertFalse(main.contains("复制并清除"))'''
)

print("v789 final corrections applied")
