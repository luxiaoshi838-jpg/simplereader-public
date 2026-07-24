from pathlib import Path

path = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = path.read_text(encoding="utf-8")

current = '''    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val addItem = menu.add(Menu.NONE, MENU_ADD_BOOKMARK, Menu.NONE, "添加书签")
        addItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        addItem.actionView = TextView(this).apply {
            text = "添"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            contentDescription = "添加书签"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(239, 122, 40))
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { addBookmark() }
        }
        return true
    }
'''

previous = '''    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, MENU_PANEL, Menu.NONE, "目录/书签")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_ADD_BOOKMARK, Menu.NONE, "添加书签")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_BOOKMARKS, Menu.NONE, "书签列表")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_TOC, Menu.NONE, "目录")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }
'''

if previous in text:
    print("Previous reader search menu is already restored")
elif current in text:
    path.write_text(text.replace(current, previous, 1), encoding="utf-8")
    print("Restored previous reader search menu exactly")
else:
    raise SystemExit("Reader menu block does not match current or previous implementation")
