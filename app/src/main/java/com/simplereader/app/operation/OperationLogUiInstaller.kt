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

/**
 * Installs the current log/status behavior without forcing unrelated MainActivity UI changes.
 */
object OperationLogUiInstaller {
    @Volatile private var installed = false

    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity.javaClass.name != "com.simplereader.app.ui.MainActivity") return
                    val main = activity as? AppCompatActivity ?: return
                    installExportMenu(main)
                    installShelfStatus(main)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            installed = true
        }
    }

    private fun installExportMenu(activity: AppCompatActivity) {
        activity.window.decorView.post {
            val button = activity.findViewById<TextView>(R.id.exportButton) ?: return@post
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
    }

    private fun installShelfStatus(activity: AppCompatActivity) {
        val stats = activity.findViewById<TextView>(R.id.readingStatsTextView) ?: return
        val lifecycleOwner = activity as LifecycleOwner
        val handler = Handler(Looper.getMainLooper())
        var desiredText: String? = null
        var active = false
        var lastWorkId: String? = null

        val keepVisible = object : Runnable {
            override fun run() {
                if (!active) return
                desiredText?.let { desired ->
                    if (stats.text?.toString() != desired) stats.text = desired
                }
                handler.postDelayed(this, 250L)
            }
        }

        WorkManager.getInstance(activity.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ShelfCacheWorker.UNIQUE_WORK_NAME)
            .observe(lifecycleOwner) { infos ->
                val running = infos.lastOrNull { !it.state.isFinished }
                if (running != null) {
                    val progress = running.progress
                    val current = progress.getInt(ShelfCacheWorker.KEY_CURRENT, 0)
                    val total = progress.getInt(ShelfCacheWorker.KEY_TOTAL, 0)
                    val title = progress.getString(ShelfCacheWorker.KEY_TITLE).orEmpty()
                    val completed = progress.getInt(ShelfCacheWorker.KEY_COMPLETED, 0)
                    val failed = progress.getInt(ShelfCacheWorker.KEY_FAILED, 0)
                    val skipped = progress.getInt(ShelfCacheWorker.KEY_SKIPPED, 0)
                    val percent = if (total > 0) current * 100.0 / total else 0.0
                    desiredText = buildString {
                        append("$current / $total（")
                        append(String.format(Locale.getDefault(), "%.1f", percent))
                        append("%）")
                        if (title.isNotBlank()) append("\n当前：$title")
                        append("，成功$completed 本，失败$failed 本，跳过$skipped 本")
                    }
                    stats.maxLines = 2
                    stats.textSize = 12f
                    stats.text = desiredText
                    if (!active) {
                        active = true
                        handler.removeCallbacks(keepVisible)
                        handler.post(keepVisible)
                    }
                    lastWorkId = running.id.toString()
                    return@observe
                }

                if (!active) return@observe
                active = false
                handler.removeCallbacks(keepVisible)
                val finished = infos.lastOrNull { it.id.toString() == lastWorkId }
                    ?: infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
                    ?: infos.lastOrNull()
                val output = finished?.outputData
                val total = output?.getInt(ShelfCacheWorker.KEY_TOTAL, 0) ?: 0
                val completed = output?.getInt(ShelfCacheWorker.KEY_COMPLETED, 0) ?: 0
                val failed = output?.getInt(ShelfCacheWorker.KEY_FAILED, 0) ?: 0
                val skipped = output?.getInt(ShelfCacheWorker.KEY_SKIPPED, 0) ?: 0
                stats.text = "目录缓存完成：$total 本，成功$completed 本，失败$failed 本，跳过$skipped 本"
                stats.maxLines = 2
                stats.textSize = 12f
                handler.postDelayed({ invokeNoArg(activity, "updateUI") }, 3000L)
            }
    }

    private fun invokeNoArg(activity: AppCompatActivity, methodName: String) {
        runCatching {
            activity.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(activity)
        }
    }
}
