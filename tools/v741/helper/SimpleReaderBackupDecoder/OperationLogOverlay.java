package SimpleReaderBackupDecoder;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Map;

/** Window-free operation-log UI attached directly to MainActivity's decor view. */
public final class OperationLogOverlay {
    private static final String PREF = "operation_history_v726";
    private static final int LIMIT = 10;
    private static View current;
    private OperationLogOverlay() {}

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            removeCurrent(decor);
            final SharedPreferences sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            final LinearLayout root = base(activity);
            current = root;
            TextView header = text(activity, "操作日志", 20f, true);
            root.addView(header);
            TextView close = text(activity, "关闭", 16f, true);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { removeCurrent(decor); }
            });
            root.addView(close);

            int count = Math.min(LIMIT, Math.max(0, safeInt(sp, "count", 0)));
            if (count <= 0) {
                root.addView(text(activity, "暂无操作日志", 16f, false));
            } else {
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    String title = safeString(sp, "title_" + i, "目录缓存");
                    TextView row = text(activity, title, 16f, false);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showDetail(activity, decor, sp, index); }
                    });
                    root.addView(row);
                }
            }
            decor.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable error) {
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                removeCurrent(decor);
                LinearLayout root = base(activity); current = root;
                root.addView(text(activity, "操作日志打开失败", 20f, true));
                root.addView(text(activity, rootMessage(error), 14f, false));
                final ViewGroup d = decor;
                TextView close = text(activity, "关闭", 16f, true);
                close.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ removeCurrent(d); }});
                root.addView(close);
                decor.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable ignored) {}
        }
    }

    private static void showDetail(final Activity activity, final ViewGroup decor, final SharedPreferences sp, final int index) {
        removeCurrent(decor);
        final LinearLayout root = base(activity); current = root;
        final String title = safeString(sp, "title_" + index, "操作日志");
        final String body = safeString(sp, "body_" + index, "暂无结果");
        root.addView(text(activity, title, 19f, true));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        TextView bodyView = text(activity, body, 14f, false);
        bodyView.setTextIsSelectable(true);
        scroll.addView(bodyView);
        root.addView(scroll);
        TextView copy = text(activity, "复制", 16f, true);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Object service = activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (service instanceof ClipboardManager) {
                        ((ClipboardManager) service).setPrimaryClip(ClipData.newPlainText("操作日志", body));
                    }
                } catch (Throwable ignored) {}
            }
        });
        root.addView(copy);
        TextView back = text(activity, "返回日志列表", 16f, true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { show(activity); }
        });
        root.addView(back);
        decor.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static LinearLayout base(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff7f7f7);
        root.setPadding(dp(activity, 18), dp(activity, 38), dp(activity, 18), dp(activity, 22));
        return root;
    }

    private static TextView text(Activity activity, String value, float size, boolean action) {
        TextView v = new TextView(activity);
        v.setText(value == null ? "" : value);
        v.setTextSize(size);
        v.setTextColor(action ? 0xff1f4f78 : 0xff202020);
        v.setPadding(dp(activity, 10), dp(activity, 12), dp(activity, 10), dp(activity, 12));
        return v;
    }

    private static void removeCurrent(ViewGroup decor) {
        if (current != null) {
            try { decor.removeView(current); } catch (Throwable ignored) {}
            current = null;
        }
    }

    private static int safeInt(SharedPreferences sp, String key, int fallback) {
        try {
            Map<String, ?> all = sp.getAll(); Object v = all == null ? null : all.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static String safeString(SharedPreferences sp, String key, String fallback) {
        try {
            Map<String, ?> all = sp.getAll(); Object v = all == null ? null : all.get(key);
            return v == null ? fallback : String.valueOf(v);
        } catch (Throwable ignored) { return fallback; }
    }

    private static int dp(Context context, int value) {
        try { return Math.max(1, (int)(value * context.getResources().getDisplayMetrics().density + 0.5f)); }
        catch (Throwable ignored) { return value; }
    }

    private static String rootMessage(Throwable error) {
        Throwable x = error;
        int guard = 0;
        while (x.getCause() != null && x.getCause() != x && guard++ < 12) x = x.getCause();
        String m = x.getMessage();
        return x.getClass().getName() + (m == null || m.length() == 0 ? "" : ": " + m);
    }
}
