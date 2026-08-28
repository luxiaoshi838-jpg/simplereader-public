package SimpleReaderBackupDecoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Keeps the user-triggered shelf cache process alive while WorkManager owns checkpoint/recovery. */
public final class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "simple_reader_shelf_cache";
    private static final int NOTIFICATION_ID = 61313;
    private static final String UNIQUE_WORK = "simple_reader_cache_all_shelf_books";
    private static final String WAKE_TAG = "SimpleReader:ShelfCache";
    private volatile boolean destroyed = false;
    private PowerManager.WakeLock wakeLock;
    private Thread watchdog;
    private long startedAt;

    public static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        Intent intent = new Intent(app, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
        else app.startService(intent);
    }

    public static void stop(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        app.stopService(new Intent(app, KeepAliveService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        startedAt = System.currentTimeMillis();
        createChannel();
        startAsForeground();
        acquireWakeLock();
        startWatchdog();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        acquireWakeLock();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        Object service = getSystemService(Context.NOTIFICATION_SERVICE);
        if (service instanceof NotificationManager) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "书架目录缓存", 2);
            channel.setDescription("持续执行目录识别与完整分页缓存");
            ((NotificationManager) service).createNotificationChannel(channel);
        }
    }

    private void startAsForeground() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = builder
                .setSmallIcon(0x01080081)
                .setContentTitle("简阅：全书架目录缓存")
                .setContentText("正在后台持续执行目录识别与完整分页")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, 1);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        Object service = getSystemService(Context.POWER_SERVICE);
        if (service instanceof PowerManager) {
            wakeLock = ((PowerManager) service).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG);
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        }
    }

    private void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new Thread(new Runnable() {
            @Override public void run() {
                while (!destroyed) {
                    try { Thread.sleep(10000L); } catch (InterruptedException ignored) {}
                    if (destroyed) break;
                    if (System.currentTimeMillis() - startedAt < 15000L) continue;
                    Boolean unfinished = hasUnfinishedWork();
                    if (Boolean.FALSE.equals(unfinished)) {
                        stopSelf();
                        break;
                    }
                }
            }
        }, "SimpleReaderShelfKeepAlive");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private Boolean hasUnfinishedWork() {
        try {
            Class<?> wmClass = Class.forName("androidx.work.WorkManager");
            Method getInstance = wmClass.getMethod("getInstance", Context.class);
            Object wm = getInstance.invoke(null, this);
            Method query = wmClass.getMethod("getWorkInfosForUniqueWork", String.class);
            Object futureObj = query.invoke(wm, UNIQUE_WORK);
            if (!(futureObj instanceof Future)) return null;
            Object value = ((Future<?>) futureObj).get(10L, TimeUnit.SECONDS);
            if (!(value instanceof List)) return null;
            for (Object info : (List<?>) value) {
                if (info == null) continue;
                Object state = info.getClass().getMethod("getState").invoke(info);
                if (state == null) continue;
                Object finished = state.getClass().getMethod("isFinished").invoke(state);
                if (finished instanceof Boolean && !((Boolean) finished)) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        Thread t = watchdog;
        if (t != null) t.interrupt();
        watchdog = null;
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
            wakeLock = null;
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
