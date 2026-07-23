package com.simplereader.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.model.ShelfBookItem
import com.simplereader.app.data.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupBooksActivity : AppCompatActivity() {
    private lateinit var bookRepository: BookRepository
    private lateinit var adapter: GroupBookAdapter
    private lateinit var rootView: LinearLayout
    private var groupId: Long = 0L
    private var groupName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty().ifBlank { "分组" }
        if (groupId <= 0L) {
            Toast.makeText(this, "分组不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bookRepository = BookRepository(SimpleReaderDatabase.getDatabase(this).bookDao())
        adapter = GroupBookAdapter(
            onOpen = { openBook(it.id) },
            onLongPress = { showBookActions(it) }
        )

        setContentView(createContentView())
        lifecycleScope.launch {
            bookRepository.getShelfBooksByGroup(groupId).collectLatest { books ->
                adapter.submit(books)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyShelfAppearance()
    }

    private fun createContentView(): View {
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: 0
        return LinearLayout(this).apply {
            this@GroupBooksActivity.rootView = this
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderAppearance.palette(this@GroupBooksActivity).backgroundColor)
            setPadding(dp(16), statusBarHeight + dp(12), dp(16), dp(8))

            addView(LinearLayout(this@GroupBooksActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                )

                addView(TextView(this@GroupBooksActivity).apply {
                    text = "‹"
                    textSize = 34f
                    gravity = Gravity.CENTER
                    setTextColor(ReaderAppearance.shelfTextColor(this@GroupBooksActivity))
                    layoutParams = LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener { finish() }
                })

                addView(TextView(this@GroupBooksActivity).apply {
                    text = groupName
                    textSize = 27f
                    setTextColor(ReaderAppearance.shelfTextColor(this@GroupBooksActivity))
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
            })

            addView(RecyclerView(this@GroupBooksActivity).apply {
                layoutManager = GridLayoutManager(this@GroupBooksActivity, 3)
                adapter = this@GroupBooksActivity.adapter
                clipToPadding = false
                setPadding(0, dp(8), 0, dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            })
        }
    }

    private fun applyShelfAppearance() {
        val palette = ReaderAppearance.palette(this)
        rootView.setBackgroundColor(palette.backgroundColor)
        window.decorView.setBackgroundColor(palette.backgroundColor)
        tintTextViews(rootView)
        adapter.notifyDataSetChanged()
    }

    private fun tintTextViews(view: View) {
        when (view) {
            is TextView -> view.setTextColor(ReaderAppearance.shelfTextColor(this))
            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    tintTextViews(view.getChildAt(index))
                }
            }
        }
    }

    private fun openBook(bookId: Long) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bookId))
    }

    private fun showBookActions(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf("打开", "移出分组", "删除书架")) { _, which ->
                when (which) {
                    0 -> openBook(book.id)
                    1 -> updateBookGroup(book, null)
                    2 -> confirmDeleteBook(book)
                }
            }
            .show()
    }

    private fun updateBookGroup(book: ShelfBookItem, groupId: Long?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val entity = bookRepository.getBook(book.id) ?: return@withContext
                bookRepository.update(entity.copy(groupId = groupId))
            }
            Toast.makeText(this@GroupBooksActivity, "已更新书籍分组", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteBook(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle("删除书架")
            .setMessage("从书架删除《${book.title}》，不会删除本地文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val entity = bookRepository.getBook(book.id) ?: return@withContext
                        bookRepository.delete(entity)
                    }
                    Toast.makeText(this@GroupBooksActivity, "已从书架删除", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private class GroupBookAdapter(
        private val onOpen: (ShelfBookItem) -> Unit,
        private val onLongPress: (ShelfBookItem) -> Unit
    ) : RecyclerView.Adapter<GroupBookAdapter.BookViewHolder>() {
        private val books = mutableListOf<ShelfBookItem>()

        fun submit(items: List<ShelfBookItem>) {
            books.clear()
            books.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            return BookViewHolder(BookCardView(parent))
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            holder.bind(books[position], onOpen, onLongPress)
        }

        override fun getItemCount(): Int = books.size

        class BookViewHolder(private val view: BookCardView) : RecyclerView.ViewHolder(view) {
            fun bind(
                book: ShelfBookItem,
                onOpen: (ShelfBookItem) -> Unit,
                onLongPress: (ShelfBookItem) -> Unit
            ) {
                view.bind(book)
                view.setOnClickListener { onOpen(book) }
                view.setOnLongClickListener {
                    onLongPress(book)
                    true
                }
            }
        }
    }

    private class BookCardView(parent: ViewGroup) : LinearLayout(parent.context) {
        private val cover = TextView(context)
        private val title = TextView(context)
        private val progress = TextView(context)

        init {
            orientation = VERTICAL
            setPadding(dp(3), 0, dp(3), dp(18))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )

            cover.apply {
                gravity = Gravity.CENTER
                maxLines = 5
                textSize = 11f
                setTextColor(Color.rgb(232, 238, 244))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.rgb(74, 126, 172), Color.rgb(47, 94, 136))
                ).apply {
                    cornerRadius = dp(5).toFloat()
                }
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(148))
            }
            addView(cover)

            title.apply {
                textSize = 15f
                maxLines = 2
                setTextColor(Color.rgb(30, 30, 30))
            }
            addView(title)

            progress.apply {
                textSize = 12f
                setTextColor(Color.rgb(130, 126, 118))
            }
            addView(progress)
        }

        fun bind(book: ShelfBookItem) {
            cover.text = book.title.take(22)
            title.text = book.title
            title.setTextColor(ReaderAppearance.shelfTextColor(context))
            progress.text = "已读 ${book.progressPercent()}%"
            progress.setTextColor(ReaderAppearance.shelfSecondaryTextColor(context))
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
    }
}
