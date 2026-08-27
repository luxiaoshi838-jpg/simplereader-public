package com.simplereader.app.operation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
 * Installs operation-log UI and the live shelf-cache status without changing unrelated shelf UI.
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
                    // onActivityCreated is invoked before MainActivity finishes setContentView().
                    // Both hooks therefore have to be attached from the posted layout callback.
                    main.window.decorView.post {
                        installExportMenu(main)
                        installShelfStatus(main)
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    // Defensive retry for OEMs where the first decorView post can run before
                    // the activity content hierarchy is fully attached.
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
        val stats = activity.findViewById<TextView>(R.id.readingStatsTextView) ?: return
        statusInstalled[activity] = true

        val lifecycleOwner = activity as LifecycleOwner
        val handler = Handler(Looper.getMainLooper())
        var desiredText: String? = null
        var active = false
        var lastWorkId: String? = null

        val originalTextSize = stats.textSize / activity.resources.displayMetrics.scaledDensity
        val originalMaxLines = stats.maxLines
        val originalEllipsize = stats.ellipsize

        val keepVisible = object : Runnable {
            override fun run() {
                if (!active || activity.isFinishing || activity.isDestroyed) return
                desiredText?.let { desired ->
                    // MainActivity.updateUI() still writes "累计导入 xxxx 本". While the current
                    // cache task is alive, the task status owns this box and must win that race.
                    if (stats.text?.toString() != desired) stats.text = desired
                }
                handler.postDelayed(this, 200L)
            }
        }

        WorkManager.getInstance(activity.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ShelfCacheWorker.UNIQUE_WORK_NAME)
            .observe(lifecycleOwner) { infos ->
                val running = infos.lastOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
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
                        "正在准备全书架目录缓存…\n当前：读取书架信息"
                    }

                    stats.maxLines = 2
                    stats.ellipsize = TextUtils.TruncateAt.END
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

                stats.maxLines = 2
                stats.text = "目录缓存完成：$total / $total（100.0%）\n成功 $completed｜失败 $failed｜跳过 $skipped"
                handler.postDelayed({
                    if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                    stats.textSize = originalTextSize
                    stats.maxLines = originalMaxLines
                    stats.ellipsize = originalEllipsize
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
