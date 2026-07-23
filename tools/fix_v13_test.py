from pathlib import Path

path = Path("app/src/test/java/com/simplereader/app/ui/ReaderAppearanceTest.kt")
text = path.read_text(encoding="utf-8")
old_imports = '''import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAppearanceTest {
'''
new_imports = '''import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderAppearanceTest {
'''
if text.count(old_imports) != 1:
    raise SystemExit("ReaderAppearanceTest import block not found exactly once")
path.write_text(text.replace(old_imports, new_imports, 1), encoding="utf-8")
print("ReaderAppearanceTest now runs with Robolectric")
