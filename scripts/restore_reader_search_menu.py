from pathlib import Path

path = Path("app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt")
text = path.read_text(encoding="utf-8")

current_menu = '''    override fun onCreateOptionsMenu(menu: Menu): Boolean {
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

restored_menu = '''    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
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

if restored_menu not in text:
    if current_menu not in text:
        raise SystemExit("Reader menu block does not match the current implementation")
    text = text.replace(current_menu, restored_menu, 1)

text = text.replace("            var downX = 0f\n", "", 1)

swipe_listener = '''            listView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> downX = event.x
                    MotionEvent.ACTION_UP -> {
                        val delta = event.x - downX
                        if (delta > 120) {
                            showingCatalog = true
                            render()
                        } else if (delta < -120) {
                            showingCatalog = false
                            render()
                        }
                    }
                }
                false
            }
'''
if swipe_listener in text:
    text = text.replace(swipe_listener, "", 1)

path.write_text(text, encoding="utf-8")
print("Restored previous search entry and disabled catalog/bookmark swipe switching")
