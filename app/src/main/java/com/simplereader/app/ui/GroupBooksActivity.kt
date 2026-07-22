package com.simplereader.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.model.ShelfBookItem
import com.simplereader.app.data.repository.BookGroupRepository
import com.simplereader.app.data.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated category screen following the same stable pattern used by mature
 * open-source Android readers: one lifecycle-owned screen, a virtualized grid,
 * and database Flow collection only while the screen is visible.
 */
class GroupBooksActivity : AppCompatActivity() {

    private lateinit var database: SimpleReaderDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var bookGroupRepository: BookGroupRepository
    private lateinit var adapter: GroupBooksAdapter
    private lateinit var titleView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView

    private var groupId: Long = -1L
    private var groupName: String = "分类"

    private val readerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Room Flow is re-collected on STARTED and immediately supplies the
        // latest progress after returning from ReaderActivity.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1L)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty().ifBlank { "分类" }
        if (groupId < 0L) {
            Toast.makeText(this, "分类信息无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        database = SimpleReaderDatabase.getDatabase(this)
        bookRepository = BookRepository(database.bookDao())
        bookGroupRepository = BookGroupRepository(database.bookGroupDao())

        adapter = GroupBooksAdapter(
            onClick = ::openBook,
            onLongClick = ::showBookActions
        )
        setContentView(createContentView())
        observeGroupBooks()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 246, 240))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(239, 235, 225))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 36f
            gravity = Gravity.CENTER
            contentDescription = "返回书架"
            setTextColor(Color.rgb(55, 50, 43))
            setPadding(dp(8), 0, dp(12), 0)
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            text = groupName
            textSize = 20f
            maxLines = 1
            setTextColor(Color.rgb(40, 36, 31))
        }
        countView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(115, 108, 96))
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(52), dp(52)))
        toolbar.addView(
            titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        toolbar.addView(
            countView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52))
        )

        val content = android.widget.FrameLayout(this)
        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@GroupBooksActivity, 3)
            adapter = this@GroupBooksActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            setPadding(dp(6), dp(8), dp(6), dp(20))
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            recycledViewPool.setMaxRecycledViews(0, 24)
            setItemViewCacheSize(12)
        }
        emptyView = TextView(this).apply {
            text = "该分类暂无书籍"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(115, 108, 96))
            visibility = View.GONE
        }
        content.addView(
            recyclerView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        content.addView(
            emptyView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return root
    }

    private fun observeGroupBooks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bookRepository.getShelfBooks().collectLatest { allBooks ->
                    val groupBooks = allBooks
                        .asSequence()
                        .filter { it.groupId == groupId }
                        .sortedByDescending(::activityTime)
                        .toList()
                    adapter.submitList(groupBooks)
                    countView.text = "${groupBooks.size} 本"
                    emptyView.visibility = if (groupBooks.isEmpty()) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (groupBooks.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun openBook(book: ShelfBookItem) {
        readerLauncher.launch(
            Intent(this, ReaderActivity::class.java).putExtra("bookId", book.id)
        )
    }

    private fun showBookActions(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf("打开", "移入其他分类", "移出书架")) { _, which ->
                when (which) {
                    0 -> openBook(book)
                    1 -> showMoveBookDialog(book)
                    2 -> confirmDeleteBook(book)
                }
            }
            .show()
    }

    private fun showMoveBookDialog(book: ShelfBookItem) {
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                bookGroupRepository.getAllGroups().first()
            }
            val targets = groups.filter { it.id != groupId }
            val labels = (listOf("未分类") + targets.map { it.displayName.ifBlank { it.name } })
                .toTypedArray()
            AlertDialog.Builder(this@GroupBooksActivity)
                .setTitle("移动《${book.title}》")
                .setItems(labels) { _, which ->
                    lifecycleScope.launch {
                        val targetGroupId = if (which == 0) null else targets[which - 1].id
                        val error = withContext(Dispatchers.IO) {
                            runCatching {
                                database.bookDao().getBook(book.id)?.let { entity ->
                                    bookRepository.update(entity.copy(groupId = targetGroupId))
                                }
                            }.exceptionOrNull()
                        }
                        if (error != null) {
                            Toast.makeText(
                                this@GroupBooksActivity,
                                "移动失败：${error.message ?: error.javaClass.simpleName}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmDeleteBook(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle("移出书架")
            .setMessage("只删除书架记录，不会删除原文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移出") { _, _ ->
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching { database.bookDao().deleteById(book.id) }.exceptionOrNull()
                    }
                    if (error != null) {
                        Toast.makeText(
                            this@GroupBooksActivity,
                            "移出失败：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun activityTime(book: ShelfBookItem): Long = maxOf(book.lastReadTime, book.addTime)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
    }
}
