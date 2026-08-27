package com.simplereader.app.operation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simplereader.app.R
import com.simplereader.app.worker.ShelfCacheWorker
import java.util.Locale
import java.util.WeakHashMap

/**
 * Installs operation-log UI and owns the shelf status box while catalog caching is active.
 */
object OperationLogUiInstaller {
    @Volatile private var installed = false
    private val statusInstalled = WeakHashMap<AppCompatActivity, Boolean>()

    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity.javaClass.name != "com.simplereader.app.ui.MainActivity") return
                    val main = activity as? AppCompatActivity ?: return
                    main.window.decorView.post {
                        installExportMenu(main)
                        installShelfStatus(main)
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    if (activity.javaClass.name != "com.simplereader.app.ui.MainActivity") return
                    val main = activity as? AppCompatActivity ?: return
                    main.window.decorView.post { installShelfStatus(main) }
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    (activity as? AppCompatActivity)?.let { statusInstalled.remove(it) }
                }
            })
            installed = true
        }
    }

    private fun installExportMenu(activity: AppCompatActivity) {
        val button = activity.findViewById<TextView>(R.id.exportButton) ?: return
        button.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("数据导出")
                .setItems(arrayOf("导出", "同步", "日志")) { _, which ->
                    when (which) {
                        0 -> invokeNoArg(activity, "launchDataExport")
                        1 -> invokeNoArg(activity, "syncDataExport")
                        2 -> OperationLogDialogs.showLogHub(activity)
                    }
                }
                .show()
        }
    }

    private fun installShelfStatus(activity: AppCompatActivity) {
        if (statusInstalled[activity] == true) return
        val statusBox = activity.findViewById<TextView>(R.id.readingStatsTextView) ?: return
        statusInstalled[activity] = true

        val lifecycleOwner = activity as LifecycleOwner
        val handler = Handler(Looper.getMainLooper())
        var desiredText: String? = null
        var cacheOwnsStatusBox = false
        var lastWorkId: String? = null

        val keepStatusVisible = object : Runnable {
            override fun run() {
                if (!cacheOwnsStatusBox || activity.isFinishing || activity.isDestroyed) return
                desiredText?.let { desired ->
                    if (statusBox.text?.toString() != desired) statusBox.text = desired
                }
                handler.postDelayed(this, 200L)
            }
        }

        WorkManager.getInstance(activity.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ShelfCacheWorker.UNIQUE_WORK_NAME)
            .observe(lifecycleOwner) { infos ->
                val running = infos.lastOrNull {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }

                if (running != null) {
                    val progress = running.progress
                    val current = progress.getInt(ShelfCacheWorker.KEY_CURRENT, 0)
                    val total = progress.getInt(ShelfCacheWorker.KEY_TOTAL, 0)
                    val title = progress.getString(ShelfCacheWorker.KEY_TITLE).orEmpty()
                    val completed = progress.getInt(ShelfCacheWorker.KEY_COMPLETED, 0)
                    val failed = progress.getInt(ShelfCacheWorker.KEY_FAILED, 0)
                    val skipped = progress.getInt(ShelfCacheWorker.KEY_SKIPPED, 0)

                    desiredText = if (total > 0) {
                        val percent = current * 100.0 / total
                        val shownTitle = title.ifBlank { "准备中" }
                        buildString {
                            append("$current / $total（")
                            append(String.format(Locale.getDefault(), "%.1f", percent))
                            append("%）")
                            append("\n当前：《")
                            append(shownTitle)
                            append("》　成功 ")
                            append(completed)
                            append("｜失败 ")
                            append(failed)
                            append("｜跳过 ")
                            append(skipped)
                        }
                    } else {
                        "0 / 0（0.0%）\n当前：《准备中》　成功 0｜失败 0｜跳过 0"
                    }

                    statusBox.text = desiredText
                    if (!cacheOwnsStatusBox) {
                        cacheOwnsStatusBox = true
                        handler.removeCallbacks(keepStatusVisible)
                        handler.post(keepStatusVisible)
                    }
                    lastWorkId = running.id.toString()
                    return@observe
                }

                if (!cacheOwnsStatusBox) return@observe
                cacheOwnsStatusBox = false
                handler.removeCallbacks(keepStatusVisible)

                val finished = infos.lastOrNull { it.id.toString() == lastWorkId }
                    ?: infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
                    ?: infos.lastOrNull()
                val output = finished?.outputData
                val total = output?.getInt(ShelfCacheWorker.KEY_TOTAL, 0) ?: 0
                val completed = output?.getInt(ShelfCacheWorker.KEY_COMPLETED, 0) ?: 0
                val failed = output?.getInt(ShelfCacheWorker.KEY_FAILED, 0) ?: 0
                val skipped = output?.getInt(ShelfCacheWorker.KEY_SKIPPED, 0) ?: 0

                statusBox.text = "目录缓存完成：$total / $total（100.0%）\n成功 $completed｜失败 $failed｜跳过 $skipped"
                handler.postDelayed({
                    if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                    invokeNoArg(activity, "updateUI")
                }, 3000L)
            }
    }

    private fun invokeNoArg(activity: AppCompatActivity, methodName: String) {
        runCatching {
            activity.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(activity)
        }
    }
}
