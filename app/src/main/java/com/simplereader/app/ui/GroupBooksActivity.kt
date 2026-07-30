package com.simplereader.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.simplereader.app.data.cache.StructuredBookCache
import com.simplereader.app.data.db.SimpleReaderDatabase
import com.simplereader.app.data.model.ShelfBookItem
import com.simplereader.app.data.repository.BookRepository
import com.simplereader.app.parser.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupBooksActivity : AppCompatActivity() {
    private lateinit var database: SimpleReaderDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var adapter: GroupBookAdapter
    private lateinit var rootView: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var deleteButton: TextView
    private lateinit var cancelButton: TextView
    private var groupId: Long = 0L
    private var groupName: String = ""
    private var selectionMode = false
    private val selectedBookIds = linkedSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty().ifBlank { "\u5206\u7ec4" }
        if (groupId <= 0L) {
            Toast.makeText(this, "\u5206\u7ec4\u4e0d\u5b58\u5728", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        database = SimpleReaderDatabase.getDatabase(this)
        bookRepository = BookRepository(database.bookDao())
        adapter = GroupBookAdapter(
            isSelectionMode = { selectionMode },
            isSelected = { selectedBookIds.contains(it) },
            onOpen = { book -> if (selectionMode) toggleSelection(book.id) else openBook(book.id) },
            onLongPress = { book -> enterSelectionMode(); toggleSelection(book.id) }
        )

        setContentView(createContentView())
        lifecycleScope.launch {
            bookRepository.getShelfBooksByGroup(groupId).collectLatest { books ->
                selectedBookIds.retainAll(books.map { it.id }.toSet())
                adapter.submit(books)
                updateSelectionChrome()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyShelfAppearance()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectionMode) exitSelectionMode() else super.onBackPressed()
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))

                addView(TextView(this@GroupBooksActivity).apply {
                    text = "<"
                    textSize = 30f
                    gravity = Gravity.CENTER
                    setTextColor(ReaderAppearance.shelfTextColor(this@GroupBooksActivity))
                    layoutParams = LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener { if (selectionMode) exitSelectionMode() else finish() }
                })

                titleView = TextView(this@GroupBooksActivity).apply {
                    text = groupName
                    textSize = 27f
                    setTextColor(ReaderAppearance.shelfTextColor(this@GroupBooksActivity))
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                }
                addView(titleView)

                deleteButton = TextView(this@GroupBooksActivity).apply {
                    text = "\u5220\u9664"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    setTextColor(Color.rgb(235, 96, 48))
                    layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener { confirmDeleteSelection() }
                }
                addView(deleteButton)

                cancelButton = TextView(this@GroupBooksActivity).apply {
                    text = "\u53d6\u6d88"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    setTextColor(ReaderAppearance.shelfTextColor(this@GroupBooksActivity))
                    layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener { exitSelectionMode() }
                }
                addView(cancelButton)
            })

            addView(RecyclerView(this@GroupBooksActivity).apply {
                layoutManager = GridLayoutManager(this@GroupBooksActivity, 3)
                adapter = this@GroupBooksActivity.adapter
                clipToPadding = false
                setPadding(0, dp(8), 0, dp(18))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
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
            is ViewGroup -> for (index in 0 until view.childCount) tintTextViews(view.getChildAt(index))
        }
        deleteButton.setTextColor(Color.rgb(235, 96, 48))
    }

    private fun enterSelectionMode() {
        if (!selectionMode) {
            selectionMode = true
            updateSelectionChrome()
            adapter.notifyDataSetChanged()
        }
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedBookIds.clear()
        updateSelectionChrome()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(bookId: Long) {
        if (!selectionMode) enterSelectionMode()
        if (!selectedBookIds.add(bookId)) selectedBookIds.remove(bookId)
        updateSelectionChrome()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionChrome() {
        if (!::titleView.isInitialized) return
        val count = selectedBookIds.size
        titleView.text = if (selectionMode) "\u5df2\u9009\u62e9 $count" else groupName
        deleteButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
        cancelButton.visibility = if (selectionMode) View.VISIBLE else View.GONE
    }

    private fun openBook(bookId: Long) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", bookId))
    }

    private fun showBookActions(book: ShelfBookItem) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf("\u6253\u5f00", "\u91cd\u547d\u540d", "\u79fb\u51fa\u5206\u7ec4", "\u53ea\u5220\u9664\u4e66\u67b6", "\u5220\u9664\u4e66\u67b6\u53ca\u672c\u5730\u6587\u4ef6")) { _, which ->
                when (which) {
                    0 -> openBook(book.id)
                    1 -> showRenameBookDialog(book)
                    2 -> updateBookGroup(book, null)
                    3 -> confirmDeleteBook(book)
                    4 -> confirmDeleteBook(book, deleteLocalFile = true)
                }
            }
            .show()
    }

    private fun showRenameBookDialog(book: ShelfBookItem) {
        val input = EditText(this).apply {
            hint = "\u4e66\u7c4d\u540d"
            setText(book.title)
            selectAll()
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("\u91cd\u547d\u540d\u4e66\u7c4d")
            .setView(input)
            .setNegativeButton("\u53d6\u6d88", null)
            .setPositiveButton("\u4fdd\u5b58", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newTitle = input.text.toString().trim()
                if (newTitle.isBlank()) {
                    input.error = "\u4e66\u540d\u4e0d\u80fd\u4e3a\u7a7a"
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val entity = bookRepository.getBook(book.id) ?: error("\u4e66\u7c4d\u4e0d\u5b58\u5728")
                            val renamed = BookFileActions.renameBookFile(this@GroupBooksActivity, entity, newTitle)
                            bookRepository.update(renamed)
                            renamed.title
                        }
                    }
                    result.onSuccess { title ->
                        Toast.makeText(this@GroupBooksActivity, "\u5df2\u91cd\u547d\u540d\uff1a$title", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }.onFailure { error ->
                        Toast.makeText(this@GroupBooksActivity, "\u91cd\u547d\u540d\u5931\u8d25\uff1a${error.message ?: "\u672a\u77e5\u9519\u8bef"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateBookGroup(book: ShelfBookItem, targetGroupId: Long?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val entity = bookRepository.getBook(book.id) ?: return@withContext
                bookRepository.update(entity.copy(groupId = targetGroupId))
            }
            Toast.makeText(this@GroupBooksActivity, "\u5df2\u66f4\u65b0", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSelection() {
        if (selectedBookIds.isEmpty()) {
            Toast.makeText(this, "\u8bf7\u5148\u9009\u62e9\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("\u6279\u91cf\u5220\u9664\u4e66\u7c4d")
            .setMessage("\u5df2\u9009\u62e9 ${selectedBookIds.size} \u672c\u4e66\u3002")
            .setNegativeButton("\u53d6\u6d88", null)
            .setNeutralButton("\u53ea\u5220\u9664\u4e66\u67b6") { _, _ -> deleteSelectedBooks(deleteLocalFiles = false) }
            .setPositiveButton("\u4e66\u67b6+\u672c\u5730\u6587\u4ef6") { _, _ -> deleteSelectedBooks(deleteLocalFiles = true) }
            .show()
    }

    private fun confirmDeleteBook(book: ShelfBookItem, deleteLocalFile: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle(if (deleteLocalFile) "\u5220\u9664\u4e66\u67b6\u53ca\u672c\u5730\u6587\u4ef6" else "\u53ea\u5220\u9664\u4e66\u67b6")
            .setMessage(if (deleteLocalFile) "\u5c06\u5220\u9664\u300a${book.title}\u300b\u7684\u4e66\u67b6\u8bb0\u5f55\u548c\u672c\u5730\u6587\u4ef6\uff0c\u6b64\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\u3002" else "\u53ea\u4ece\u4e66\u67b6\u79fb\u9664\u300a${book.title}\u300b\uff0c\u4e0d\u5220\u9664\u539f\u6587\u4ef6\u3002")
            .setNegativeButton("\u53d6\u6d88", null)
            .setPositiveButton("\u5220\u9664") { _, _ -> deleteBooks(listOf(book.id), deleteLocalFile) }
            .show()
    }

    private fun deleteSelectedBooks(deleteLocalFiles: Boolean) {
        deleteBooks(selectedBookIds.toList(), deleteLocalFiles) { exitSelectionMode() }
    }

    private fun deleteBooks(bookIds: List<Long>, deleteLocalFiles: Boolean, afterDelete: () -> Unit = {}) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var localDeleted = 0
                    var localFailed = 0
                    database.withTransaction {
                        bookIds.forEach { id ->
                            val entity = database.bookDao().getBook(id)
                            if (deleteLocalFiles && entity != null) {
                                if (BookFileActions.deleteBookFile(this@GroupBooksActivity, entity)) localDeleted++ else localFailed++
                            }
                            database.bookmarkDao().deleteByBookId(id)
                            database.readProgressDao().deleteByBookId(id)
                            database.bookDao().deleteById(id)
                        }
                    }
                    localDeleted to localFailed
                }
            }
            result.onSuccess { (localDeleted, localFailed) ->
                val message = if (deleteLocalFiles) "\u5df2\u5220\u9664\u4e66\u67b6\u8bb0\u5f55\uff0c\u672c\u5730\u6587\u4ef6\u6210\u529f $localDeleted \u4e2a\uff0c\u5931\u8d25 $localFailed \u4e2a" else "\u5df2\u4ece\u4e66\u67b6\u5220\u9664"
                Toast.makeText(this@GroupBooksActivity, message, Toast.LENGTH_LONG).show()
                afterDelete()
            }.onFailure { error ->
                Toast.makeText(this@GroupBooksActivity, "\u5220\u9664\u5931\u8d25\uff1a${error.message ?: "\u672a\u77e5\u9519\u8bef"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private class GroupBookAdapter(
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (Long) -> Boolean,
        private val onOpen: (ShelfBookItem) -> Unit,
        private val onLongPress: (ShelfBookItem) -> Unit
    ) : RecyclerView.Adapter<GroupBookAdapter.BookViewHolder>() {
        private val books = mutableListOf<ShelfBookItem>()

        fun submit(items: List<ShelfBookItem>) {
            books.clear()
            books.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder =
            BookViewHolder(BookCardView(parent))

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = books[position]
            holder.bind(book, isSelectionMode(), isSelected(book.id), onOpen, onLongPress)
        }

        override fun getItemCount(): Int = books.size

        class BookViewHolder(private val view: BookCardView) : RecyclerView.ViewHolder(view) {
            fun bind(
                book: ShelfBookItem,
                selectionMode: Boolean,
                selected: Boolean,
                onOpen: (ShelfBookItem) -> Unit,
                onLongPress: (ShelfBookItem) -> Unit
            ) {
                view.bind(book, selectionMode, selected)
                view.setOnClickListener { onOpen(book) }
                view.setOnLongClickListener {
                    onLongPress(book)
                    true
                }
            }
        }
    }

    private class BookCardView(parent: ViewGroup) : FrameLayout(parent.context) {
        private val content = LinearLayout(context)
        private val coverFallback = TextView(context)
        private val coverImage = ImageView(context)
        private val title = TextView(context)
        private val progress = TextView(context)
        private val selectionMark = TextView(context)
        private var boundBookId: Long = -1L

        init {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            content.orientation = LinearLayout.VERTICAL
            content.setPadding(dp(3), 0, dp(3), dp(18))
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            val coverFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148))
            }
            coverFallback.apply {
                gravity = Gravity.CENTER
                maxLines = 5
                textSize = 11f
                setTextColor(Color.rgb(232, 238, 244))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = PaperCoverDrawable(radiusPx = dp(5).toFloat())
            }
            coverImage.apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            coverFrame.addView(coverFallback, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            coverFrame.addView(coverImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            content.addView(coverFrame)

            title.apply {
                textSize = 15f
                maxLines = 2
                setTextColor(Color.rgb(30, 30, 30))
            }
            content.addView(title)

            progress.apply {
                textSize = 12f
                setTextColor(Color.rgb(130, 126, 118))
            }
            content.addView(progress)

            selectionMark.apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                visibility = View.GONE
            }
            addView(selectionMark, LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(120)
                rightMargin = dp(6)
            })
        }

        fun bind(book: ShelfBookItem, selectionMode: Boolean, selected: Boolean) {
            boundBookId = book.id
            coverFallback.text = book.title.take(22)
            coverFallback.background = PaperCoverDrawable(radiusPx = dp(5).toFloat(), seed = book.id.toInt())
            coverFallback.visibility = View.VISIBLE
            coverImage.visibility = View.GONE
            coverImage.setImageDrawable(null)
            coverImage.contentDescription = "\u5c01\u9762 ${book.title}"
            title.text = book.title
            title.setTextColor(ReaderAppearance.shelfTextColor(context))
            progress.text = "\u5df2\u8bfb ${book.progressPercent()}%"
            progress.setTextColor(ReaderAppearance.shelfSecondaryTextColor(context))
            updateSelection(selectionMode, selected)

            if (!book.format.equals("EPUB", ignoreCase = true)) return
            val activity = context as? AppCompatActivity ?: return
            activity.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        StructuredBookCache.coverFile(context, book.id)
                            ?.takeIf { it.isFile }
                            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                            ?: context.contentResolver.openInputStream(Uri.parse(book.filePath))?.use { input ->
                                EpubParser.readCoverImage(input)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            }
                    }.getOrNull()
                }
                if (boundBookId == book.id && bitmap != null) {
                    coverImage.setImageBitmap(bitmap)
                    coverImage.visibility = View.VISIBLE
                    coverFallback.visibility = View.GONE
                }
            }
        }

        private fun updateSelection(selectionMode: Boolean, selected: Boolean) {
            selectionMark.visibility = if (selectionMode) View.VISIBLE else View.GONE
            selectionMark.text = if (selected) "\u2713" else ""
            selectionMark.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(2), if (selected) Color.rgb(239, 100, 45) else Color.rgb(150, 145, 136))
                setColor(if (selected) Color.rgb(239, 100, 45) else Color.WHITE)
            }
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
    }
}
