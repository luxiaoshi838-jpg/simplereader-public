#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- old ---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_version() -> None:
    path = ROOT / "app/build.gradle.kts"
    text = path.read_text(encoding="utf-8")
    text = text.replace('"2098000771"', '"2098000772"')
    text = text.replace('?: 2098000771', '?: 2098000772')
    text = text.replace('?: "771"', '?: "772"')
    path.write_text(text, encoding="utf-8")


def patch_main_layout() -> None:
    path = ROOT / "app/src/main/res/layout/activity_main.xml"
    replace_once(
        path,
        '''        <TextView\n            android:id="@+id/searchButton"\n''',
        '''        <TextView\n            android:id="@+id/shelfNightButton"\n            android:layout_width="44dp"\n            android:layout_height="44dp"\n            android:background="@android:color/transparent"\n            android:contentDescription="日间夜间模式"\n            android:gravity="center"\n            android:text="☾"\n            android:textColor="#24211C"\n            android:textSize="28sp" />\n\n        <TextView\n            android:id="@+id/searchButton"\n'''
    )
    replace_once(
        path,
        '<androidx.recyclerview.widget.RecyclerView\n        android:id="@+id/shelfGrid"',
        '<com.simplereader.app.ui.FastScrollRecyclerView\n        android:id="@+id/shelfGrid"'
    )
    replace_once(
        path,
        'android:scrollbars="vertical" />',
        'android:scrollbars="none" />'
    )


def patch_main_activity() -> None:
    path = ROOT / "app/src/main/java/com/simplereader/app/ui/MainActivity.kt"
    replace_once(
        path,
        '''        findViewById<TextView>(R.id.searchButton).apply {\n            text = "⌕"\n            setOnClickListener { showShelfSearch() }\n        }\n''',
        '''        findViewById<TextView>(R.id.shelfNightButton).apply {\n            text = "☾"\n            contentDescription = "日间夜间模式"\n            setOnClickListener {\n                ReaderAppearance.toggleMode(this@MainActivity)\n                applyShelfAppearance()\n                shelfAdapter.notifyDataSetChanged()\n            }\n        }\n        findViewById<TextView>(R.id.searchButton).apply {\n            text = "⌕"\n            setOnClickListener { showShelfSearch() }\n        }\n'''
    )
    replace_once(
        path,
        '''        findViewById<TextView>(R.id.readingStatsTextView).setTextColor(secondaryText)\n        findViewById<TextView>(R.id.searchButton).setTextColor(primaryText)\n''',
        '''        findViewById<TextView>(R.id.readingStatsTextView).setTextColor(secondaryText)\n        findViewById<TextView>(R.id.shelfNightButton).setTextColor(primaryText)\n        findViewById<TextView>(R.id.searchButton).setTextColor(primaryText)\n'''
    )


def patch_group_activity() -> None:
    path = ROOT / "app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt"
    replace_once(
        path,
        '            addView(RecyclerView(this@GroupBooksActivity).apply {\n',
        '            addView(FastScrollRecyclerView(this@GroupBooksActivity).apply {\n'
    )


def main() -> None:
    patch_version()
    patch_main_layout()
    patch_main_activity()
    patch_group_activity()
    print("v772 shelf fast-scroll + shelf day/night patch applied")


if __name__ == "__main__":
    main()
