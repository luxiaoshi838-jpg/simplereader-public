package SimpleReaderBackupDecoder;

import android.content.Context;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function3;

/** Dedicated v733 worker for "full shelf books without reusable catalog/page cache". */
public final class NoCatalogWorker extends Worker {
    public NoCatalogWorker(Context context, WorkerParameters params) { super(context, params); }

    @Override public ListenableWorker.Result doWork() {
        Context context = getApplicationContext();
        try {
            KeepAliveService.start(context);
            List<Object> allBooks = loadBooksFromDatabase(context);
            int scanTotal = allBooks.size();
            ArrayList<Object> targets = new ArrayList<Object>();
            int excluded = 0;
            for (Object book : allBooks) {
                if (book != null && kt.hasReusableCurrentCache(context, book)) excluded++;
                else targets.add(book);
            }
            final int total = targets.size();
            kt.noCatalogScan(context, scanTotal, total, excluded);
            if (total == 0) return ListenableWorker.Result.success();

            int completed = 0, failed = 0, skipped = 0;
            for (int i = 0; i < total; i++) {
                Object book = targets.get(i);
                String title = bookTitle(book);
                kt.bookStart(context, i + 1, total, title);
                publishProgress(i + 1, total, title, completed, skipped, failed);

                // Race-safe: if another operation produced a valid current cache after the scan,
                // this target is explicitly classified as skipped rather than silently disappearing.
                if (book != null && kt.hasReusableCurrentCache(context, book)) {
                    skipped++;
                    kt.bookSkip(context, "扫描后已存在可复用目录与分页缓存");
                    publishProgress(i + 1, total, title, completed, skipped, failed);
                    continue;
                }

                String stage = "准备处理";
                try {
                    stage = processBook(context, book);
                    completed++;
                    kt.bookSuccess(context);
                } catch (StageFailure sf) {
                    failed++;
                    kt.bookFailure(context, sf.stage, unwrap(sf.getCause() == null ? sf : sf.getCause()));
                } catch (Throwable t) {
                    failed++;
                    kt.bookFailure(context, stage, unwrap(t));
                }
                publishProgress(i + 1, total, title, completed, skipped, failed);
            }
            kt.finishNoCatalog(context, total);
            return ListenableWorker.Result.success();
        } catch (Throwable top) {
            Throwable real = unwrap(top);
            try {
                kt.noCatalogScan(context, 0, 1, 0);
                kt.bookStart(context, 1, 1, "（任务初始化）");
                kt.bookFailure(context, "任务初始化", real);
                kt.finishNoCatalog(context, 1);
            } catch (Throwable ignored) {}
            return ListenableWorker.Result.failure();
        }
    }

    /** Returns the final stage name when processing succeeds. */
    private String processBook(final Context context, final Object book) throws Exception {
        if (book == null) throw new StageFailure("读取书架记录", new IllegalStateException("书籍记录为空"));
        Class<?> bookClass = Class.forName("com.simplereader.app.data.entity.Book");
        Method getId = bookClass.getMethod("getId");
        Method getFilePath = bookClass.getMethod("getFilePath");
        Method getFileName = bookClass.getMethod("getFileName");
        Method getFileSize = bookClass.getMethod("getFileSize");
        final long bookId = ((Number)getId.invoke(book)).longValue();
        final String filePath = String.valueOf(getFilePath.invoke(book));
        String currentFileName = String.valueOf(getFileName.invoke(book));
        Long currentFileSize = (Long)getFileSize.invoke(book);

        Object loader; Class<?> loaderClass;
        try {
            loaderClass = Class.forName("com.simplereader.app.reader.ReaderDocumentLoader");
            loader = loaderClass.getField("INSTANCE").get(null);
        } catch (Throwable t) { throw new StageFailure("文档加载器初始化", unwrap(t)); }

        try {
            Method resolve = loaderClass.getMethod("resolveDocument", Context.class, bookClass);
            Object source = resolve.invoke(loader, context, book);
            if (source != null) {
                Method name = source.getClass().getMethod("getName");
                Method length = source.getClass().getMethod("length");
                Object n = name.invoke(source);
                if (n instanceof String && !((String)n).trim().isEmpty()) currentFileName = (String)n;
                Object len = length.invoke(source);
                if (len instanceof Number && ((Number)len).longValue() >= 0L) currentFileSize = ((Number)len).longValue();
            }
        } catch (Throwable t) { throw new StageFailure("文件定位/读取", unwrap(t)); }

        Object pageStore; Class<?> pageClass;
        try {
            pageClass = Class.forName("com.simplereader.app.reader.page.PageCacheStore");
            pageStore = pageClass.getField("INSTANCE").get(null);
            pageClass.getMethod("clearDerivedCatalogAndPages", Context.class, long.class)
                    .invoke(pageStore, context, bookId);
        } catch (Throwable t) { throw new StageFailure("清理旧目录/分页缓存", unwrap(t)); }

        Object document;
        try {
            document = loaderClass.getMethod("load", Context.class, bookClass, boolean.class)
                    .invoke(loader, context, book, Boolean.TRUE);
            if (document == null) throw new IllegalStateException("文档加载结果为空");
        } catch (Throwable t) { throw new StageFailure("目录识别/文档加载", unwrap(t)); }

        Class<?> readerDocClass = document.getClass();
        final String text;
        final List<?> chapters;
        final long sourceSize;
        final long sourceModified;
        try {
            text = (String)readerDocClass.getMethod("getText").invoke(document);
            chapters = (List<?>)readerDocClass.getMethod("getChapters").invoke(document);
            sourceSize = ((Number)readerDocClass.getMethod("getSourceSize").invoke(document)).longValue();
            sourceModified = ((Number)readerDocClass.getMethod("getSourceModified").invoke(document)).longValue();
            if (text == null) throw new IllegalStateException("文档文本为空");
            if (chapters == null) throw new IllegalStateException("章节列表为空");
        } catch (Throwable t) { throw new StageFailure("目录识别结果读取", unwrap(t)); }

        Object settings; String settingsHash;
        try {
            Class<?> profileClass = Class.forName("com.simplereader.app.reader.page.ReaderCacheProfile");
            Object profile = profileClass.getField("INSTANCE").get(null);
            float textSize = ((Number)profileClass.getMethod("currentTextSizeSp", Context.class).invoke(profile, context)).floatValue();
            settings = profileClass.getMethod("createSettings", Context.class, float.class,
                    Integer.class,Integer.class,Integer.class,Integer.class,Integer.class,Integer.class)
                    .invoke(profile, context, textSize, null,null,null,null,null,null);
            settingsHash = (String)settings.getClass().getMethod("stableHash").invoke(settings);
        } catch (Throwable t) { throw new StageFailure("分页参数构建", unwrap(t)); }

        final Object identity;
        try {
            String fingerprint = (String)pageClass.getMethod("textFingerprint", String.class).invoke(pageStore, text);
            int catalogRuleVersion = 111;
            try {
                Class<?> parser = Class.forName("com.simplereader.app.parser.TxtParser");
                catalogRuleVersion = parser.getField("CATALOG_RULE_VERSION").getInt(null);
            } catch (Throwable ignored) { catalogRuleVersion = 111; }
            Class<?> identityClass = Class.forName("com.simplereader.app.reader.page.PageCacheStore$CacheIdentity");
            Constructor<?> ctor = identityClass.getConstructor(long.class,String.class,long.class,long.class,String.class,String.class,int.class);
            identity = ctor.newInstance(bookId, filePath, sourceSize, sourceModified, settingsHash, fingerprint, catalogRuleVersion);
        } catch (Throwable t) { throw new StageFailure("分页缓存身份构建", unwrap(t)); }

        final Object paged;
        try {
            Class<?> repoClass = Class.forName("com.simplereader.app.reader.ReaderImageRepository");
            final Object imageRepo = repoClass.getConstructor(Context.class, long.class).newInstance(context, bookId);
            final Method span = repoClass.getMethod("span", String.class, int.class, int.class);
            Function0<Boolean> cancelCheck = new Function0<Boolean>() {
                @Override public Boolean invoke() { return Boolean.TRUE; }
            };
            Function3<Object,Object,Object,Object> imageFactory = new Function3<Object,Object,Object,Object>() {
                @Override public Object invoke(Object href, Object width, Object height) {
                    try { return span.invoke(imageRepo, String.valueOf(href), ((Number)width).intValue(), ((Number)height).intValue()); }
                    catch (Throwable ignored) { return null; }
                }
            };
            Class<?> engineClass = Class.forName("com.simplereader.app.reader.page.PageEngine");
            Object engine = engineClass.getField("INSTANCE").get(null);
            Class<?> settingsClass = Class.forName("com.simplereader.app.reader.page.ReaderLayoutSettings");
            Class<?> typefaceClass = Class.forName("android.graphics.Typeface");
            Object typeface = typefaceClass.getField("DEFAULT").get(null);
            Class<?> f0 = Class.forName("kotlin.jvm.functions.Function0");
            Class<?> f3 = Class.forName("kotlin.jvm.functions.Function3");
            Method paginate = engineClass.getMethod("paginate", String.class, List.class, settingsClass, typefaceClass, f0, f3);
            paged = paginate.invoke(engine, text, chapters, settings, typeface, cancelCheck, imageFactory);
            if (paged == null) throw new IllegalStateException("分页结果为空");
            Object pages = paged.getClass().getMethod("getPages").invoke(paged);
            if (!(pages instanceof List) || ((List<?>)pages).isEmpty()) throw new IllegalStateException("分页结果为空");
        } catch (Throwable t) { throw new StageFailure("分页", unwrap(t)); }

        try {
            Class<?> identityClass = identity.getClass();
            Class<?> readerBookClass = paged.getClass();
            pageClass.getMethod("savePages", Context.class, identityClass, readerBookClass)
                    .invoke(pageStore, context, identity, paged);
            Object verified = pageClass.getMethod("loadPages", Context.class, identityClass, String.class)
                    .invoke(pageStore, context, identity, text);
            if (verified == null) throw new IllegalStateException("分页缓存写入后无法重新读取");
            List<?> vp = (List<?>)verified.getClass().getMethod("getPages").invoke(verified);
            List<?> pp = (List<?>)paged.getClass().getMethod("getPages").invoke(paged);
            List<?> vc = (List<?>)verified.getClass().getMethod("getChapters").invoke(verified);
            List<?> pc = (List<?>)paged.getClass().getMethod("getChapters").invoke(paged);
            if (vp.size() != pp.size()) throw new IllegalStateException("分页缓存页数校验失败");
            if (vc.size() != pc.size()) throw new IllegalStateException("分页缓存章节校验失败");

            int visible = 0;
            for (Object ch : vc) {
                try { if (Boolean.TRUE.equals(ch.getClass().getMethod("getCatalogVisible").invoke(ch))) visible++; }
                catch (Throwable ignored) {}
            }
            pageClass.getMethod("markRecognitionComplete", Context.class,long.class,String.class,Long.class,int.class,int.class)
                    .invoke(pageStore, context, bookId, currentFileName,
                            currentFileSize != null ? currentFileSize : Long.valueOf(sourceSize), visible, vp.size());
        } catch (Throwable t) { throw new StageFailure("分页缓存写入/校验", unwrap(t)); }

        return "完成";
    }

    private void publishProgress(int current, int total, String title, int completed, int skipped, int failed) {
        try {
            Class<?> dbc = Class.forName("androidx.work.Data$Builder");
            Object b = dbc.getConstructor().newInstance();
            Method putInt = dbc.getMethod("putInt", String.class, int.class);
            Method putString = dbc.getMethod("putString", String.class, String.class);
            putInt.invoke(b, "current", current); putInt.invoke(b, "total", total);
            putInt.invoke(b, "completed", completed); putInt.invoke(b, "skipped", skipped); putInt.invoke(b, "failed", failed);
            putString.invoke(b, "title", title == null ? "" : title);
            Object data = dbc.getMethod("build").invoke(b);
            Class<?> dataClass = Class.forName("androidx.work.Data");
            Method set = findPublicMethod(getClass(), "setProgressAsync", dataClass);
            if (set != null) set.invoke(this, data);
        } catch (Throwable ignored) {}
    }

    private static Method findPublicMethod(Class<?> c, String name, Class<?> arg) {
        try { return c.getMethod(name, arg); } catch (Throwable ignored) {}
        Class<?> x = c;
        while (x != null) {
            try { Method m = x.getDeclaredMethod(name, arg); m.setAccessible(true); return m; } catch (Throwable ignored) {}
            x = x.getSuperclass();
        }
        return null;
    }

    private static String bookTitle(Object book) {
        if (book == null) return "（未知书籍）";
        try { Object v = book.getClass().getMethod("getTitle").invoke(book); if (v != null) return String.valueOf(v); } catch (Throwable ignored) {}
        return "（未知书籍）";
    }

    private static List<Object> loadBooksFromDatabase(Context context) throws Exception {
        Object db = null, cursor = null;
        try {
            File path = (File)context.getClass().getMethod("getDatabasePath", String.class).invoke(context, "simple_reader_db");
            Class<?> sqlite = Class.forName("android.database.sqlite.SQLiteDatabase");
            Class<?> factory = Class.forName("android.database.sqlite.SQLiteDatabase$CursorFactory");
            int readOnly = sqlite.getField("OPEN_READONLY").getInt(null);
            db = sqlite.getMethod("openDatabase", String.class, factory, int.class).invoke(null, path.getAbsolutePath(), null, readOnly);
            cursor = sqlite.getMethod("rawQuery", String.class, String[].class).invoke(db,
                    "SELECT id,title,author,filePath,format,groupId,lastReadTime,addTime,cover,fileName,fileSize,lastModified,sourceTreeUri,relativePath,fileStatus,txtCharset FROM books ORDER BY id", null);
            Class<?> cc = Class.forName("android.database.Cursor");
            Method move = cc.getMethod("moveToNext"); Method getLong = cc.getMethod("getLong", int.class);
            Method getString = cc.getMethod("getString", int.class); Method isNull = cc.getMethod("isNull", int.class);
            Class<?> bookClass = Class.forName("com.simplereader.app.data.entity.Book");
            Constructor<?> ctor = bookClass.getConstructor(long.class,String.class,String.class,String.class,String.class,Long.class,
                    long.class,long.class,String.class,String.class,Long.class,Long.class,String.class,String.class,String.class,String.class);
            ArrayList<Object> out = new ArrayList<Object>();
            while (Boolean.TRUE.equals(move.invoke(cursor))) {
                long id = ((Number)getLong.invoke(cursor,0)).longValue();
                String title = str(getString.invoke(cursor,1), ""); String author = str(getString.invoke(cursor,2), "");
                String filePath = str(getString.invoke(cursor,3), ""); String format = str(getString.invoke(cursor,4), "TXT");
                Long groupId = nullableLong(cursor, isNull, getLong, 5);
                long lastRead = ((Number)getLong.invoke(cursor,6)).longValue(); long addTime = ((Number)getLong.invoke(cursor,7)).longValue();
                String cover = nullableString(cursor,isNull,getString,8); String fileName = str(getString.invoke(cursor,9), title);
                Long fileSize = nullableLong(cursor,isNull,getLong,10); Long lastModified = nullableLong(cursor,isNull,getLong,11);
                String tree = nullableString(cursor,isNull,getString,12); String rel = nullableString(cursor,isNull,getString,13);
                String status = str(getString.invoke(cursor,14), "AVAILABLE"); String charset = nullableString(cursor,isNull,getString,15);
                out.add(ctor.newInstance(id,title,author,filePath,format,groupId,lastRead,addTime,cover,fileName,fileSize,lastModified,tree,rel,status,charset));
            }
            return out;
        } finally {
            if (cursor != null) try { cursor.getClass().getMethod("close").invoke(cursor); } catch (Throwable ignored) {}
            if (db != null) try { db.getClass().getMethod("close").invoke(db); } catch (Throwable ignored) {}
        }
    }

    private static Long nullableLong(Object cursor, Method isNull, Method getLong, int idx) throws Exception {
        if (Boolean.TRUE.equals(isNull.invoke(cursor, idx))) return null;
        return Long.valueOf(((Number)getLong.invoke(cursor,idx)).longValue());
    }
    private static String nullableString(Object cursor, Method isNull, Method getString, int idx) throws Exception {
        if (Boolean.TRUE.equals(isNull.invoke(cursor, idx))) return null;
        Object v=getString.invoke(cursor,idx); return v==null?null:String.valueOf(v);
    }
    private static String str(Object v, String d) { return v == null ? d : String.valueOf(v); }

    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while (x instanceof InvocationTargetException && ((InvocationTargetException)x).getTargetException()!=null) x=((InvocationTargetException)x).getTargetException();
        return x;
    }
    private static final class StageFailure extends Exception {
        final String stage;
        StageFailure(String stage, Throwable cause) { super(cause); this.stage=stage; }
    }
}
