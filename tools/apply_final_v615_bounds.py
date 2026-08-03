from pathlib import Path


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)


reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = reader.read_text()

if "import androidx.core.view.WindowCompat" not in text:
    text = must_replace(
        text,
        "import androidx.appcompat.app.AppCompatActivity\n",
        "import androidx.appcompat.app.AppCompatActivity\n"
        "import androidx.core.view.ViewCompat\n"
        "import androidx.core.view.WindowCompat\n"
        "import androidx.core.view.WindowInsetsCompat\n",
        "AppCompatActivity import",
    )

if "private lateinit var readerRoot: View" not in text:
    text = must_replace(
        text,
        "    private lateinit var database: SimpleReaderDatabase\n"
        "    private lateinit var readerScrollView: NestedScrollView",
        "    private lateinit var database: SimpleReaderDatabase\n"
        "    private lateinit var readerRoot: View\n"
        "    private lateinit var readerScrollView: NestedScrollView",
        "readerRoot field",
    )

if "private var statusBarInsetPx = 0" not in text:
    text = must_replace(
        text,
        "    private var continuousWindowShiftPosted = false\n"
        "    private var backgroundColorId: String",
        "    private var continuousWindowShiftPosted = false\n"
        "    private var statusBarInsetPx = 0\n"
        "    private var navigationBarInsetPx = 0\n"
        "    private var backgroundColorId: String",
        "system inset fields",
    )

if "WindowCompat.setDecorFitsSystemWindows(window, false)" not in text:
    text = must_replace(
        text,
        "        AppTheme.apply(this)\n"
        "        super.onCreate(savedInstanceState)\n"
        "        setContentView(R.layout.activity_reader)",
        "        AppTheme.apply(this)\n"
        "        super.onCreate(savedInstanceState)\n"
        "        // Root spans the screen; text starts below the notification bar plus one character.\n"
        "        WindowCompat.setDecorFitsSystemWindows(window, false)\n"
        "        setContentView(R.layout.activity_reader)",
        "edge-to-edge setup",
    )

if "readerRoot = findViewById(R.id.readerRoot)" not in text:
    text = must_replace(
        text,
        "        database = SimpleReaderDatabase.getDatabase(this)\n"
        "        readerScrollView = findViewById(R.id.readerScrollView)",
        "        database = SimpleReaderDatabase.getDatabase(this)\n"
        "        readerRoot = findViewById(R.id.readerRoot)\n"
        "        readerScrollView = findViewById(R.id.readerScrollView)",
        "readerRoot binding",
    )

if "        bindReaderInsets()\n" not in text:
    text = must_replace(
        text,
        "        loadPreferences()\n"
        "        bindPagedReader()",
        "        loadPreferences()\n"
        "        bindReaderInsets()\n"
        "        applyReaderContentPadding()\n"
        "        bindPagedReader()",
        "reader inset initialization",
    )

if "private fun bindReaderInsets()" not in text:
    marker = "    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()"
    methods = '''    private fun bindReaderInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(readerRoot) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (statusTop != statusBarInsetPx || navigationBottom != navigationBarInsetPx) {
                statusBarInsetPx = statusTop
                navigationBarInsetPx = navigationBottom
                applyReaderContentPadding()
                val stableOffset = readerBook?.pages?.getOrNull(currentPageIndex)?.startOffset
                if (document != null && readerBook != null) paginateAndDisplay(stableOffset)
            }
            insets
        }
        ViewCompat.requestApplyInsets(readerRoot)
    }

    private fun applyReaderContentPadding() {
        val oneCharacterPx = (readerTextSizeSp * resources.displayMetrics.scaledDensity + 0.5f)
            .toInt()
            .coerceAtLeast(1)
        // Upper limit = notification/status-bar bottom + one complete text character.
        // This is intentionally not measured from the physical top edge of the screen.
        val topPaddingPx = statusBarInsetPx + oneCharacterPx
        val bottomPaddingPx = navigationBarInsetPx + oneCharacterPx
        continuousTextView.setPadding(
            continuousTextView.paddingLeft,
            topPaddingPx,
            continuousTextView.paddingRight,
            bottomPaddingPx
        )
    }

'''
    if marker not in text:
        raise SystemExit("dp marker not found")
    text = text.replace(marker, methods + marker, 1)

size_line = "        readerTextSizeSp = (readerTextSizeSp + delta).coerceIn(12f, 36f)\n"
if size_line in text and size_line + "        applyReaderContentPadding()\n" not in text:
    text = text.replace(size_line, size_line + "        applyReaderContentPadding()\n", 1)

settings_call = '''            contentPaddingLeftPx = continuousTextView.paddingLeft,
            contentPaddingTopPx = continuousTextView.paddingTop,
            contentPaddingRightPx = continuousTextView.paddingRight
        )'''
settings_new = '''            contentPaddingLeftPx = continuousTextView.paddingLeft,
            contentPaddingTopPx = continuousTextView.paddingTop,
            contentPaddingRightPx = continuousTextView.paddingRight,
            contentPaddingBottomPx = continuousTextView.paddingBottom
        )'''
if settings_call in text:
    text = text.replace(settings_call, settings_new, 1)
elif "contentPaddingBottomPx = continuousTextView.paddingBottom" not in text:
    raise SystemExit("Reader layout settings call not found")
reader.write_text(text)

profile = Path("app/src/main/java/com/simplereader/app/reader/page/ReaderCacheProfile.kt")
ptext = profile.read_text()
old_signature = '''        contentPaddingLeftPx: Int? = null,
        contentPaddingTopPx: Int? = null,
        contentPaddingRightPx: Int? = null
    ): ReaderLayoutSettings {'''
new_signature = '''        contentPaddingLeftPx: Int? = null,
        contentPaddingTopPx: Int? = null,
        contentPaddingRightPx: Int? = null,
        contentPaddingBottomPx: Int? = null
    ): ReaderLayoutSettings {'''
if old_signature in ptext:
    ptext = ptext.replace(old_signature, new_signature, 1)
elif "contentPaddingBottomPx: Int? = null" not in ptext:
    raise SystemExit("ReaderCacheProfile signature not found")
ptext = ptext.replace(
    "            contentPaddingBottomPx = dp(context, CONTENT_BOTTOM_PADDING_DP),",
    "            contentPaddingBottomPx = contentPaddingBottomPx ?: dp(context, CONTENT_BOTTOM_PADDING_DP),",
    1,
)
profile.write_text(ptext)

layout = Path("app/src/main/res/layout/activity_reader.xml")
xml = layout.read_text()
if 'android:id="@+id/readerRoot"' not in xml:
    xml = must_replace(
        xml,
        '<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"\n',
        '<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:id="@+id/readerRoot"\n',
        "reader root XML",
    )
xml = xml.replace('android:paddingTop="24dp"', 'android:paddingTop="0dp"', 1)
xml = xml.replace('android:paddingBottom="24dp"', 'android:paddingBottom="0dp"', 1)
layout.write_text(xml)

policy = Path("tools/verify-ui-policy.sh")
sh = policy.read_text()
old_policy = 'grep -q \'android:paddingTop="24dp"\' "$layout" || fail "reader text top guard must be one character"'
new_policy = (
    'grep -q \'android:paddingTop="0dp"\' "$layout" || fail "reader XML padding must defer to runtime system-bar insets"\n'
    'grep -q \'WindowCompat.setDecorFitsSystemWindows(window, false)\' "$reader" || fail "reader root must receive real system-bar insets"\n'
    'grep -q \'statusBarInsetPx + oneCharacterPx\' "$reader" || fail "reader upper limit must be notification-bar bottom plus one character"\n'
    'grep -q \'navigationBarInsetPx + oneCharacterPx\' "$reader" || fail "reader lower limit must leave one character above navigation bar"'
)
if old_policy in sh:
    sh = sh.replace(old_policy, new_policy, 1)
elif "statusBarInsetPx + oneCharacterPx" not in sh:
    raise SystemExit("UI policy top-padding rule not found")
policy.write_text(sh)

baseline = Path("UI_BASELINE.md")
btext = baseline.read_text()
btext = btext.replace(
    "阅读区顶部：正文整体必须比系统顶部再下移一个字符高度（24dp），不得贴住通知栏。",
    "阅读区顶部：先避开通知栏/状态栏，再从通知栏下缘额外留出当前正文字号的一个完整字符高度；禁止从屏幕物理最高处直接计算。",
)
baseline.write_text(btext)
