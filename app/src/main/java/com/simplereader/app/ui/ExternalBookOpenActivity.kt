package com.simplereader.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.simplereader.app.data.db.SimpleReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExternalBookOpenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val uri = incomingUri(intent)
        if (uri == null) {
            Toast.makeText(this, "没有收到可打开的书籍文件", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val database = SimpleReaderDatabase.getDatabase(this)
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { ExternalBookStore.prepare(this@ExternalBookOpenActivity, database, uri) }
            }
            prepared.onSuccess { result ->
                startActivity(
                    Intent(this@ExternalBookOpenActivity, ReaderActivity::class.java)
                        .putExtra("bookId", result.bookId)
                        .putExtra(ExternalBookStore.EXTRA_EXTERNAL_PENDING, result.pendingDecision)
                )
                finish()
            }.onFailure { error ->
                Toast.makeText(
                    this@ExternalBookOpenActivity,
                    error.message ?: "无法打开外部书籍",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun incomingUri(source: Intent): Uri? {
        source.data?.let { return it }
        source.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }
        return source.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
