package com.simplereader.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Extra foreground owner for the user-triggered full-shelf catalog + pagination pass.
 * WorkManager still owns checkpoint/recovery; this service keeps the process foreground and
 * holds a bounded partial wake lock while that unique WorkRequest is unfinished.
 */
class ShelfCacheKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var destroyed = false
    private var watchdog: Thread? = null
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        createChannel()
        startAsForeground()
        acquireWakeLock()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        watchdog?.interrupt()
        watchdog = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else stopForeground(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "书架目录缓存", NotificationManager.IMPORTANCE_LOW).apply {
                description = "持续执行目录识别与完整分页缓存"
            }
        )
    }

    private fun startAsForeground() {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("简阅：全书架目录缓存")
            .setContentText("正在后台持续执行目录识别与完整分页")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(TimeUnit.HOURS.toMillis(6))
            }
    }

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread({
            while (!destroyed) {
                try { Thread.sleep(10_000L) } catch (_: InterruptedException) { }
                if (destroyed) break
                if (System.currentTimeMillis() - startedAt < 15_000L) continue
                val unfinished = runCatching {
                    WorkManager.getInstance(this)
                        .getWorkInfosForUniqueWork(ShelfCacheWorker.UNIQUE_WORK_NAME)
                        .get(10, TimeUnit.SECONDS)
                        .any { !it.state.isFinished }
                }.getOrNull()
                if (unfinished == false) {
                    stopSelf()
                    break
                }
            }
        }, "SimpleReaderShelfKeepAlive").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val CHANNEL_ID = "simple_reader_shelf_cache"
        private const val NOTIFICATION_ID = 61313
        private const val WAKE_TAG = "SimpleReader:ShelfCache"

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ShelfCacheKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, ShelfCacheKeepAliveService::class.java))
        }
    }
}
