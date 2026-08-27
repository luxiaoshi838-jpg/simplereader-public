package SimpleReaderBackupDecoder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v726 bounded operation history helper; compiled as the additional classes5.dex. */
public final class kt {
    private static final String PREF = "operation_history_v726";
    private static final int LIMIT = 10;
    private static final Pattern PROGRESS = Pattern.compile("(?:^|\\n)\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern BOOK_PROGRESS = Pattern.compile("（\\s*(\\d+)\\s*/\\s*(\\d+)\\s*）\\s*$");
    private kt() {}

    private static void purgeLegacy(Context context) {
        if (context == null) return;
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info == null || info.dataDir == null) return;
            File dir = new File(info.dataDir, "shared_prefs");
            new File(dir, "operation.xml").delete();
            new File(dir, "operation.xml.bak").delete();
        } catch (Throwable ignored) {}
    }

    public static void record(Context context, String message) {
        if (context == null || message == null) return;
        boolean workerStart = "__worker_started__".equals(message);
        Matcher p = PROGRESS.matcher(message);
        Matcher b = BOOK_PROGRESS.matcher(message);
        boolean progress = p.find();
        boolean bookProgress = b.find();
        boolean status = message.contains("成功") && message.contains("失败") && message.contains("跳过");
        boolean initial = message.contains("生成目录");
        if (!workerStart && !progress && !bookProgress && !status && !initial) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        purgeLegacy(app);
        SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int current = -1, total = -1;
        if (progress) { current = safeInt(p.group(1)); total = safeInt(p.group(2)); }
        else if (bookProgress) { current = safeInt(b.group(1)); total = safeInt(b.group(2)); }
        int count = Math.min(LIMIT, Math.max(0, sp.getInt("count", 0)));
        int lastCurrent = sp.getInt("last_current", -1);
        int lastTotal = sp.getInt("last_total", -1);
        boolean active = sp.getInt("active", 0) == 1;
        boolean newTask = workerStart || !active || count == 0 ||
                (current >= 0 && lastCurrent >= 0 && current < lastCurrent) ||
                (total > 0 && lastTotal > 0 && total != lastTotal);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = sp.edit();
        if (newTask) {
            int move = Math.min(count, LIMIT - 1);
            for (int i = move; i >= 1; i--) {
                e.putString("title_" + i, sp.getString("title_" + (i - 1), "目录缓存"));
                e.putString("body_" + i, sp.getString("body_" + (i - 1), ""));
            }
            count = Math.min(LIMIT, count + 1);
            e.putInt("count", count);
            e.putString("title_0", "目录缓存 · " + stamp(now));
            e.putInt("active", 1);
        }
        String shown = workerStart ? "准备目录识别与分页缓存" : message;
        String body = "操作：书架目录缓存（目录识别 + 完整分页缓存）\n" +
                "更新时间：" + fullStamp(now) + "\n\n" + shown;
        e.putString("body_0", body);
        if (current >= 0) e.putInt("last_current", current);
        if (total >= 0) e.putInt("last_total", total);
        if (total > 0 && current >= total && status) e.putInt("active", 0);
        e.putLong("last_update", now);
        e.apply();
    }

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        purgeLegacy(activity);
        final SharedPreferences sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int count = Math.min(LIMIT, Math.max(0, sp.getInt("count", 0)));
        if (count <= 0) {
            new AlertDialog.Builder(activity).setTitle("操作日志").setMessage("暂无操作日志")
                    .setNegativeButton("关闭", null).show();
            return;
        }
        final CharSequence[] titles = new CharSequence[count];
        for (int i = 0; i < count; i++) titles[i] = sp.getString("title_" + i, "目录缓存");
        new AlertDialog.Builder(activity).setTitle("操作日志")
                .setItems(titles, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showDetail(activity, sp, which); }
                }).setNegativeButton("关闭", null).show();
    }

    private static void showDetail(final Activity activity, final SharedPreferences sp, int index) {
        final String title = sp.getString("title_" + index, "操作日志");
        final String body = sp.getString("body_" + index, "暂无结果");
        final TextView text = new TextView(activity);
        text.setText(body); text.setTextSize(14f); text.setTextIsSelectable(true);
        int pad = dp(activity, 14); text.setPadding(pad, dp(activity, 10), pad, dp(activity, 16));
        final ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.addView(text);
        final SeekBar seek = new SeekBar(activity); seek.setMax(1000); seek.setProgress(0);
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(activity, 420), 1f));
        root.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        final boolean[] dragging = new boolean[] { false };
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int range = Math.max(0, text.getHeight() - scroll.getHeight());
                scroll.scrollTo(0, (int)(((long)range * progress) / 1000L));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging[0] = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { dragging[0] = false; }
        });
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int oldx, int oldy) {
                if (dragging[0]) return;
                int range = Math.max(0, text.getHeight() - scroll.getHeight());
                int progress = range <= 0 ? 0 : (int)(((long)sy * 1000L) / range);
                seek.setProgress(Math.max(0, Math.min(1000, progress)));
            }
        });
        new AlertDialog.Builder(activity).setTitle(title).setView(root)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Object service = activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (service instanceof ClipboardManager) {
                            ((ClipboardManager)service).setPrimaryClip(ClipData.newPlainText("操作日志", body));
                        }
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private static int safeInt(String value) { try { return Integer.parseInt(value); } catch (Throwable ignored) { return 0; } }
    private static String stamp(long now) { return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(now)); }
    private static String fullStamp(long now) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(now)); }
    private static int dp(Context context, int value) {
        float d = context.getResources().getDisplayMetrics().density;
        return Math.max(1, (int)(value * d + 0.5f));
    }
}
