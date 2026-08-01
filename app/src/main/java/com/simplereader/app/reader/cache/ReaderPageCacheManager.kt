package com.simplereader.app.reader.cache

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simplereader.app.ui.ReaderLayoutSignature
import java.util.concurrent.TimeUnit

object ReaderPageCacheManager {
    private const val KEY_BOOK_ID = "book_id"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_LINE_SPACING = "line_spacing"
    private const val KEY_HORIZONTAL_PADDING = "horizontal_padding"
    private const val KEY_TOP_PADDING = "top_padding"
    private const val KEY_BOTTOM_PADDING = "bottom_padding"
    private const val KEY_TITLE_SCALE = "title_scale"
    private const val KEY_VIEWPORT_WIDTH = "viewport_width"
    private const val KEY_VIEWPORT_HEIGHT = "viewport_height"

    const val PROGRESS_DONE = "done"
    const val PROGRESS_TOTAL = "total"
    const val PROGRESS_CHAPTER = "chapter"
    const val OUTPUT_MESSAGE = "message"

    fun bookTag(bookId: Long): String = "reader-page-cache-book-$bookId"

    private fun uniqueName(bookId: Long, signature: ReaderLayoutSignature): String =
        "${bookTag(bookId)}-${signature.stableKey().hashCode()}"

    fun enqueue(
        context: Context,
        bookId: Long,
        signature: ReaderLayoutSignature
    ) {
        val stableSignature = signature.copy(contentKey = 0L)
        val input = Data.Builder()
            .putLong(KEY_BOOK_ID, bookId)
            .putInt(KEY_WIDTH, stableSignature.widthPx)
            .putInt(KEY_HEIGHT, stableSignature.heightPx)
            .putInt(KEY_TEXT_SIZE, stableSignature.textSizePx)
            .putInt(KEY_LINE_SPACING, stableSignature.lineSpacingMultiplierX100)
            .putInt(KEY_HORIZONTAL_PADDING, stableSignature.horizontalPaddingPx)
            .putInt(KEY_TOP_PADDING, stableSignature.topPaddingPx)
            .putInt(KEY_BOTTOM_PADDING, stableSignature.bottomPaddingPx)
            .putInt(KEY_TITLE_SCALE, stableSignature.chapterTitleScaleX100)
            .putInt(KEY_VIEWPORT_WIDTH, stableSignature.viewportWidthPx)
            .putInt(KEY_VIEWPORT_HEIGHT, stableSignature.viewportHeightPx)
            .build()
        val request = OneTimeWorkRequestBuilder<ReaderPageCacheWorker>()
            .setInputData(input)
            .addTag(bookTag(bookId))
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                30,
                TimeUnit.SECONDS
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName(bookId, stableSignature),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun observe(
        context: Context,
        owner: LifecycleOwner,
        bookId: Long,
        observer: (WorkInfo?) -> Unit
    ) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTagLiveData(bookTag(bookId))
            .observe(owner) { infos ->
                observer(
                    infos.maxWithOrNull(
                        compareBy<WorkInfo> { statePriority(it.state) }
                            .thenBy { it.runAttemptCount }
                    )
                )
            }
    }

    private fun statePriority(state: WorkInfo.State): Int = when (state) {
        WorkInfo.State.RUNNING -> 6
        WorkInfo.State.ENQUEUED -> 5
        WorkInfo.State.BLOCKED -> 4
        WorkInfo.State.SUCCEEDED -> 3
        WorkInfo.State.FAILED -> 2
        WorkInfo.State.CANCELLED -> 1
    }

    internal fun signature(input: Data): ReaderLayoutSignature = ReaderLayoutSignature(
        widthPx = input.getInt(KEY_WIDTH, 1),
        heightPx = input.getInt(KEY_HEIGHT, 1),
        textSizePx = input.getInt(KEY_TEXT_SIZE, 1),
        lineSpacingMultiplierX100 = input.getInt(KEY_LINE_SPACING, 175),
        horizontalPaddingPx = input.getInt(KEY_HORIZONTAL_PADDING, 0),
        topPaddingPx = input.getInt(KEY_TOP_PADDING, 0),
        bottomPaddingPx = input.getInt(KEY_BOTTOM_PADDING, 0),
        chapterTitleScaleX100 = input.getInt(KEY_TITLE_SCALE, 130),
        contentKey = 0L,
        viewportWidthPx = input.getInt(KEY_VIEWPORT_WIDTH, input.getInt(KEY_WIDTH, 1)),
        viewportHeightPx = input.getInt(KEY_VIEWPORT_HEIGHT, input.getInt(KEY_HEIGHT, 1))
    )

    internal fun bookId(input: Data): Long = input.getLong(KEY_BOOK_ID, -1L)
}
