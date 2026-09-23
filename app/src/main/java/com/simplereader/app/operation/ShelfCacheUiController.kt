package com.simplereader.app.operation

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simplereader.app.worker.ShelfCacheCheckpointStore
import com.simplereader.app.worker.ShelfCacheWorker
import java.util.Locale
import java.util.WeakHashMap

/** Owns the shelf status box only while the user-triggered catalog cache is active. */
object ShelfCacheUiController {
    private data class State(
        var locked: Boolean = false,
        var lastWorkId: String? = null,
        var attached: Boolean = false,
        var recoveryRequestedWorkId: String? = null
    )

    private val states = WeakHashMap<AppCompatActivity, State>()
    private val handler = Handler(Looper.getMainLooper())

    fun isLocked(activity: AppCompatActivity): Boolean = states[activity]?.locked == true

    fun attach(activity: AppCompatActivity, statusBox: TextView, restoreIdle: () -> Unit) {
        val state = states.getOrPut(activity) { State() }
        if (state.attached) return
        state.attached = true

        Thread {
            OperationLogStore.purgeLegacyV722StoreBeforeLoad(activity.applicationContext)
        }.start()

        WorkManager.getInstance(activity.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ShelfCacheWorker.UNIQUE_WORK_NAME)
            .observe(activity as LifecycleOwner) { infos ->
                val running = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (running != null) {
                    state.locked = true
                    state.lastWorkId = running.id.toString()
                    val stalledLegacy =
                        (running.state == WorkInfo.State.ENQUEUED || running.state == WorkInfo.State.BLOCKED) &&
                            running.runAttemptCount > 0
                    if (stalledLegacy && state.recoveryRequestedWorkId != running.id.toString()) {
                        state.recoveryRequestedWorkId = running.id.toString()
                        ShelfCacheWorker.resumeStalledLegacyWork(
                            activity.applicationContext,
                            running.id
                        )
                    } else if (running.state == WorkInfo.State.RUNNING) {
                        state.recoveryRequestedWorkId = null
                    }
                    val progress = running.progress
                    val progressTotal = progress.getInt(ShelfCacheWorker.KEY_TOTAL, 0)
                    statusBox.text = if (progressTotal > 0) {
                        ShelfCacheStatusText.active(
                            current = progress.getInt(ShelfCacheWorker.KEY_CURRENT, 0),
                            total = progressTotal,
                            title = progress.getString(ShelfCacheWorker.KEY_TITLE).orEmpty(),
                            completed = progress.getInt(ShelfCacheWorker.KEY_COMPLETED, 0),
                            failed = progress.getInt(ShelfCacheWorker.KEY_FAILED, 0),
                            skipped = progress.getInt(ShelfCacheWorker.KEY_SKIPPED, 0)
                        )
                    } else {
                        // ENQUEUED/BLOCKED work can temporarily expose empty WorkManager progress
                        // after process recreation. Never turn a real durable checkpoint into 0/0.
                        val checkpoint = ShelfCacheCheckpointStore.load(
                            activity.applicationContext,
                            running.id.toString()
                        )
                        if (checkpoint != null && checkpoint.total > 0) {
                            ShelfCacheStatusText.active(
                                current = checkpoint.nextIndex,
                                total = checkpoint.total,
                                title = when {
                                    running.state == WorkInfo.State.RUNNING -> "后台运行中"
                                    stalledLegacy -> "正在自动恢复"
                                    else -> "等待调度"
                                },
                                completed = checkpoint.completed,
                                failed = checkpoint.failed,
                                skipped = checkpoint.skipped
                            )
                        } else {
                            ShelfCacheStatusText.preparing()
                        }
                    }
                    return@observe
                }

                if (!state.locked) return@observe
                val finished = infos.firstOrNull { it.id.toString() == state.lastWorkId }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                    ?: infos.firstOrNull()
                val output = finished?.outputData
                val total = output?.getInt(ShelfCacheWorker.KEY_TOTAL, 0) ?: 0
                val completed = output?.getInt(ShelfCacheWorker.KEY_COMPLETED, 0) ?: 0
                val failed = output?.getInt(ShelfCacheWorker.KEY_FAILED, 0) ?: 0
                val skipped = output?.getInt(ShelfCacheWorker.KEY_SKIPPED, 0) ?: 0
                statusBox.text = ShelfCacheStatusText.completed(total, completed, failed, skipped)
                handler.postDelayed({
                    if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                    state.locked = false
                    state.lastWorkId = null
                    state.recoveryRequestedWorkId = null
                    restoreIdle()
                }, 3000L)
            }
    }

    fun showPreparing(activity: AppCompatActivity, statusBox: TextView) {
        val state = states.getOrPut(activity) { State() }
        state.locked = true
        statusBox.text = ShelfCacheStatusText.preparing()
    }
}

object ShelfCacheStatusText {
    fun preparing(): String = "0 / 0（0.0%）\n当前：《准备中》　成功 0｜失败 0｜跳过 0"

    fun active(
        current: Int,
        total: Int,
        title: String,
        completed: Int,
        failed: Int,
        skipped: Int
    ): String {
        if (total <= 0) return preparing()
        val safeCurrent = current.coerceIn(0, total)
        val percent = safeCurrent * 100.0 / total
        val shownTitle = title.ifBlank { "准备中" }
        return buildString {
            append("$safeCurrent / $total（")
            append(String.format(Locale.getDefault(), "%.1f", percent))
            append("%）")
            append("\n当前：《")
            append(shownTitle)
            append("》　成功 ")
            append(completed.coerceAtLeast(0))
            append("｜失败 ")
            append(failed.coerceAtLeast(0))
            append("｜跳过 ")
            append(skipped.coerceAtLeast(0))
        }
    }

    fun completed(total: Int, completed: Int, failed: Int, skipped: Int): String {
        val safeTotal = total.coerceAtLeast(0)
        return "$safeTotal / $safeTotal（100.0%）\n当前：《已完成》　成功 ${completed.coerceAtLeast(0)}｜失败 ${failed.coerceAtLeast(0)}｜跳过 ${skipped.coerceAtLeast(0)}"
    }
}
