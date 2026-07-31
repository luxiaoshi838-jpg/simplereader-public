from pathlib import Path

reader = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = reader.read_text(encoding="utf-8-sig")

if "private const val PREF_BACKGROUND_STYLE" not in text:
    old = """        private const val PREF_TURN_MODE = \"turn_mode\"\n        private const val PREF_VOLUME_KEY = \"volume_key\"\n        private const val TURN_MODE_OVERLAP = \"overlap\"\n"""
    new = """        private const val PREF_TURN_MODE = \"turn_mode\"\n        private const val PREF_VOLUME_KEY = \"volume_key\"\n        private const val PREF_BACKGROUND_STYLE = \"background_style_v582\"\n        private const val PREF_CHROME_ACTIVATION = \"chrome_activation_v582\"\n        private const val CHROME_ACTIVATION_CENTER = \"center_tap\"\n        private const val CHROME_ACTIVATION_LONG_PRESS = \"long_press\"\n        private const val TURN_MODE_OVERLAP = \"overlap\"\n"""
    if text.count(old) != 1:
        raise SystemExit("无法定位 v582 偏好常量插入点")
    text = text.replace(old, new, 1)

for marker in [
    "private const val PREF_BACKGROUND_STYLE",
    "private const val PREF_CHROME_ACTIVATION",
    "private const val CHROME_ACTIVATION_CENTER",
    "private const val CHROME_ACTIVATION_LONG_PRESS",
]:
    if marker not in text:
        raise SystemExit(f"ReaderActivity 仍缺少：{marker}")
reader.write_text(text, encoding="utf-8")

backgrounds = Path("app/src/main/java/com/simplereader/app/ui/ReaderBackgrounds.kt")
background_text = backgrounds.read_text(encoding="utf-8-sig")
background_text = background_text.replace(
    "x + length, y + (index % 3 - 1), detailPaint",
    "x + length, y.toFloat() + (index % 3 - 1).toFloat(), detailPaint",
)
if "y.toFloat() + (index % 3 - 1).toFloat()" not in background_text:
    raise SystemExit("ReaderBackgrounds 浮点坐标修复未生效")
backgrounds.write_text(background_text, encoding="utf-8")

print("v582 compile fixes applied")
