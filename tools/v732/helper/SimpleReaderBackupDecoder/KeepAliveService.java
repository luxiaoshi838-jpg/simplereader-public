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
public final class KeepAliveService extends Service {
    private static final String CHANNEL_ID="simple_reader_shelf_cache";
    private static final int NOTIFICATION_ID=61313;
    private static final String UNIQUE_WORK="simple_reader_cache_all_shelf_books";
    private static final String WAKE_TAG="SimpleReader:ShelfCache";
    private volatile boolean destroyed=false; private PowerManager.WakeLock wakeLock; private Thread watchdog; private long startedAt;
    public static void start(Context context){ if(context==null)return; Context app=context.getApplicationContext(); if(app==null)app=context; Intent i=new Intent(app,KeepAliveService.class); try{ if(Build.VERSION.SDK_INT>=26)app.startForegroundService(i); else app.startService(i);}catch(Throwable ignored){} }
    public static void stop(Context context){ if(context==null)return; Context app=context.getApplicationContext(); if(app==null)app=context; try{app.stopService(new Intent(app,KeepAliveService.class));}catch(Throwable ignored){} }
    @Override public void onCreate(){ super.onCreate(); startedAt=System.currentTimeMillis(); createChannel(); startAsForeground(); acquireWakeLock(); startWatchdog(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId){ startAsForeground(); acquireWakeLock(); return START_STICKY; }
    private void createChannel(){ try{ if(Build.VERSION.SDK_INT<26)return; Object s=getSystemService(Context.NOTIFICATION_SERVICE); if(s instanceof NotificationManager){ NotificationChannel c=new NotificationChannel(CHANNEL_ID,"书架目录缓存",2); c.setDescription("持续执行目录识别与完整分页缓存"); ((NotificationManager)s).createNotificationChannel(c); }}catch(Throwable ignored){} }
    private void startAsForeground(){ try{ Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this); Notification n=b.setSmallIcon(0x01080081).setContentTitle("简阅：书架目录缓存").setContentText("正在后台持续执行目录识别与完整分页").setOngoing(true).build(); if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,1); else startForeground(NOTIFICATION_ID,n);}catch(Throwable ignored){} }
    private void acquireWakeLock(){ try{ if(wakeLock!=null&&wakeLock.isHeld())return; Object s=getSystemService(Context.POWER_SERVICE); if(s instanceof PowerManager){ wakeLock=((PowerManager)s).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,WAKE_TAG); wakeLock.setReferenceCounted(false); wakeLock.acquire(6L*60L*60L*1000L); }}catch(Throwable ignored){ wakeLock=null; } }
    private void startWatchdog(){ if(watchdog!=null)return; watchdog=new Thread(new Runnable(){ public void run(){ while(!destroyed){ try{Thread.sleep(10000L);}catch(InterruptedException ignored){} if(destroyed)break; if(System.currentTimeMillis()-startedAt<15000L)continue; Boolean u=hasUnfinishedWork(); if(Boolean.FALSE.equals(u)){stopSelf();break;} } }},"SimpleReaderShelfKeepAlive"); watchdog.setDaemon(true); watchdog.start(); }
    private Boolean hasUnfinishedWork(){ try{ Class<?> c=Class.forName("androidx.work.WorkManager"); Object wm=c.getMethod("getInstance",Context.class).invoke(null,this); Object f=c.getMethod("getWorkInfosForUniqueWork",String.class).invoke(wm,UNIQUE_WORK); if(!(f instanceof Future))return null; Object v=((Future<?>)f).get(10L,TimeUnit.SECONDS); if(!(v instanceof List))return null; for(Object info:(List<?>)v){ if(info==null)continue; Object state=info.getClass().getMethod("getState").invoke(info); if(state==null)continue; Object fin=state.getClass().getMethod("isFinished").invoke(state); if(fin instanceof Boolean&&!((Boolean)fin))return Boolean.TRUE; } return Boolean.FALSE; }catch(Throwable ignored){return null;} }
    @Override public void onDestroy(){ destroyed=true; Thread t=watchdog; if(t!=null)t.interrupt(); watchdog=null; if(wakeLock!=null){try{if(wakeLock.isHeld())wakeLock.release();}catch(Throwable ignored){} wakeLock=null;} try{if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_DETACH); else stopForeground(false);}catch(Throwable ignored){} super.onDestroy(); }
    @Override public IBinder onBind(Intent intent){return null;}
}
