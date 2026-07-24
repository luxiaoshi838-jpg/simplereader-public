from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/java/com/simplereader/app/parser/TxtParser.kt"
text = path.read_text(encoding="utf-8")
old = '''        val preferred = preferredCharsetName
            ?.let { runCatching { Charset.forName(normalizeCharsetName(it)) }.getOrNull() }
        val locallyDetected = runCatching { CharsetDetector.detectCharset(bytes) }.getOrNull()
'''
new = '''        val preferred = preferredCharsetName
            ?.let { runCatching { Charset.forName(normalizeCharsetName(it)) }.getOrNull() }
        preferred?.let { charset ->
            decodeStrict(bytes, charset)?.let { decoded ->
                if (!looksMojibake(decoded)) return decoded
            }
        }
        val locallyDetected = runCatching { CharsetDetector.detectCharset(bytes) }.getOrNull()
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("preferred charset stability target missing")
path.write_text(text, encoding="utf-8")
print("preferred charset now wins after valid UTF-8 check")
