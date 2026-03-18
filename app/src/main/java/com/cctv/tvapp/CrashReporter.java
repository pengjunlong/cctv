package com.cctv.tvapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.acra.ACRA;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地日志持久化工具（配合 ACRA 使用）。
 *
 * <p>功能：
 * <ul>
 *   <li>将事件/异常写入本地文件（Downloads/CctvTvApp/crash_logs/）供离线分析。</li>
 *   <li>对于 error/crash，同时通过 {@code ACRA.getErrorReporter().handleSilentException()}
 *       触发 ACRA 上报到 GitHub Issues。</li>
 * </ul>
 *
 * <p>注意：全局崩溃捕获由 ACRA 的 UncaughtExceptionHandler 负责，
 * 本类不再安装独立的 UncaughtExceptionHandler，避免冲突。
 *
 * <p>日志文件位置：
 * <ul>
 *   <li>Android 10+：{@code Downloads/CctvTvApp/crash_logs/log_yyyyMMdd.txt}（文件管理器可见）</li>
 *   <li>Android 6~9：同上（需 WRITE_EXTERNAL_STORAGE 权限）</li>
 *   <li>降级：{@code /data/data/com.cctv.tvapp/files/crash_logs/}（需 adb）</li>
 * </ul>
 *
 * <p>初始化：
 * <pre>
 *   CrashReporter.init(this);  // 在 Application.onCreate 中
 * </pre>
 *
 * <p>主动上报：
 * <pre>
 *   CrashReporter.reportEvent("channel_load_fail", "CCTV-1 加载超时");
 *   CrashReporter.reportError("fetch_failed", exception);
 * </pre>
 */
public class CrashReporter {

    private static final String TAG = "CrashReporter";

    private static final String LOG_SUB_DIR   = "CctvTvApp/crash_logs";
    private static final int    MAX_LOG_LINES = 2000;
    private static final int    MAX_LOG_FILES = 10;

    private static volatile CrashReporter sInstance;

    private final Context          appContext;
    private final ExecutorService  executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat sdf;
    private final String           deviceInfo;
    private final String           appVersion;

    // =========================================================
    // 初始化
    // =========================================================

    private CrashReporter(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.sdf        = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        this.sdf.setTimeZone(TimeZone.getDefault());
        this.deviceInfo = buildDeviceInfo();
        this.appVersion = getAppVersion(ctx);

        cleanupOldLogs();

        // 检查上次是否有 native crash（libxul.so 等），如果有则写入日志
        checkLastNativeCrash();

        Log.i(TAG, "CrashReporter initialized. version=" + appVersion + " device=" + deviceInfo);
    }

    /** 初始化，在 Application.onCreate 中调用（ACRA.init 之后）。 */
    public static void init(Context ctx) {
        if (sInstance == null) {
            synchronized (CrashReporter.class) {
                if (sInstance == null) {
                    sInstance = new CrashReporter(ctx);
                }
            }
        }
    }

    // =========================================================
    // 公开 API
    // =========================================================

    /**
     * 上报普通事件（非崩溃）：仅写本地日志，不触发 ACRA。
     */
    public static void reportEvent(String event, String message) {
        if (sInstance == null) return;
        sInstance.doReport("event", event, message, null, false);
    }

    /**
     * 上报异常（带堆栈）：写本地日志 + 触发 ACRA 创建 GitHub Issue。
     */
    public static void reportError(String event, Throwable t) {
        if (sInstance == null) return;
        sInstance.doReport("error", event, t != null ? t.getMessage() : "null", t, true);
    }

    /**
     * 上报异常（带描述和堆栈）：写本地日志 + 触发 ACRA 创建 GitHub Issue。
     */
    public static void reportError(String event, String message, Throwable t) {
        if (sInstance == null) return;
        sInstance.doReport("error", event, message, t, true);
    }

    /**
     * 在前台 Activity 中弹出 AlertDialog，展示完整错误信息和堆栈。
     *
     * <p>适合在 catch 块中主动调用，方便在设备上直接看到错误原因。
     * 必须在主线程调用，或者传入 Activity 后自动 post 到主线程。
     *
     * @param activity 当前前台 Activity（用于弹窗上下文）
     * @param t        异常（可为 null，此时只展示 message）
     * @param message  附加说明（可为 null）
     */
    public static void showCrashDialog(Activity activity, String message, Throwable t) {
        if (activity == null || activity.isFinishing()) return;
        final String text = buildCrashText(message, t);
        Runnable show = () -> {
            if (activity.isFinishing()) return;
            // 构建可滚动 TextView
            ScrollView sv = new ScrollView(activity);
            TextView tv = new TextView(activity);
            tv.setText(text);
            tv.setTextIsSelectable(true);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            int pad = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 12,
                    activity.getResources().getDisplayMetrics());
            tv.setPadding(pad, pad, pad, pad);
            sv.addView(tv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(activity)
                    .setTitle("❌ 发生错误")
                    .setView(sv)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("复制", (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("crash", text));
                        }
                    })
                    .setCancelable(true)
                    .show();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            new Handler(Looper.getMainLooper()).post(show);
        }
    }

    /** 组装对话框正文：时间 + 设备信息 + message + 完整堆栈 */
    private static String buildCrashText(String message, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
          .append("\n");
        if (sInstance != null) {
            sb.append("版本: ").append(sInstance.appVersion).append("\n");
            sb.append("设备: ").append(sInstance.deviceInfo).append("\n");
        }
        if (message != null && !message.isEmpty()) {
            sb.append("说明: ").append(message).append("\n");
        }
        if (t != null) {
            sb.append("\n").append(getStackTrace(t));
        }
        return sb.toString();
    }

    // =========================================================
    // 内部上报
    // =========================================================

    private void doReport(String level, String event, String message,
                          Throwable t, boolean notifyAcra) {
        String stack   = t != null ? getStackTrace(t) : null;
        String logLine = "[" + level.toUpperCase() + "] " + event + ": " + message;
        if (stack != null) logLine += "\n" + stack;

        if ("error".equals(level)) Log.e(TAG, logLine);
        else Log.i(TAG, logLine);

        // 写本地文件（异步）
        final String finalMessage = message;
        final String finalStack   = stack;
        executor.execute(() -> writeToFile(level, event, finalMessage, finalStack));

        // 通知 ACRA 发往 GitHub Issues（同步，已在子线程）
        if (notifyAcra && t != null) {
            try {
                ACRA.getErrorReporter().handleSilentException(t);
            } catch (Exception ignored) {
                // ACRA 未初始化时忽略
            }
        }
    }

    // =========================================================
    // 本地文件写入
    // =========================================================

    private String getTodayFileName() {
        String date = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        return "log_" + date + ".txt";
    }

    /**
     * 写入日志，存储策略：
     * <ol>
     *   <li>Android 10+：MediaStore.Downloads（公共 Downloads，文件管理器可见）</li>
     *   <li>Android 6~9：Environment.getExternalStoragePublicDirectory</li>
     *   <li>降级：内部私有目录</li>
     * </ol>
     */
    private void writeToFile(String level, String event, String message, String stack) {
        String logContent = buildLogEntry(level, event, message, stack);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(logContent);
        } else {
            writeViaFile(logContent);
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private void writeViaMediaStore(String logContent) {
        String fileName     = getTodayFileName();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + LOG_SUB_DIR;

        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            Uri existingUri = null;
            String[] projection = {MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            String[] selArgs = {fileName, relativePath + "/"};
            try (android.database.Cursor cursor = appContext.getContentResolver().query(
                    collection, projection, selection, selArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                    existingUri = Uri.withAppendedPath(collection, String.valueOf(id));
                }
            }

            Uri fileUri;
            if (existingUri != null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                appContext.getContentResolver().update(existingUri, cv, null, null);
                fileUri = existingUri;
            } else {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME,  fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE,     "text/plain");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                cv.put(MediaStore.Downloads.IS_PENDING,    0);
                fileUri = appContext.getContentResolver().insert(collection, cv);
            }

            if (fileUri == null) {
                writeViaFileFallback(logContent);
                return;
            }

            try (OutputStream os = appContext.getContentResolver().openOutputStream(fileUri, "wa")) {
                if (os != null) {
                    os.write(logContent.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Log.d(TAG, "MediaStore log written: " + fileUri);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "writeViaMediaStore failed: " + e.getMessage());
            writeViaFileFallback(logContent);
        }
    }

    private void writeViaFile(String logContent) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            writeViaFileFallback(logContent);
            return;
        }
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                LOG_SUB_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            writeViaFileFallback(logContent);
            return;
        }
        writeToFileImpl(new File(dir, getTodayFileName()), logContent);
    }

    private void writeViaFileFallback(String logContent) {
        File dir = new File(appContext.getFilesDir(), "crash_logs");
        if (!dir.exists()) dir.mkdirs();
        writeToFileImpl(new File(dir, getTodayFileName()), logContent);
    }

    private void writeToFileImpl(File logFile, String logContent) {
        try {
            if (logFile.exists() && countLines(logFile) > MAX_LOG_LINES) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(logContent);
            fw.flush();
            fw.close();
            Log.d(TAG, "File log written: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "writeToFileImpl failed: " + e.getMessage());
        }
    }

    private String buildLogEntry(String level, String event, String message, String stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("────────────────────────────────────────\n");
        sb.append("TIME   : ").append(sdf.format(new Date())).append("\n");
        sb.append("LEVEL  : ").append(level.toUpperCase()).append("\n");
        sb.append("EVENT  : ").append(event).append("\n");
        sb.append("MSG    : ").append(message).append("\n");
        sb.append("APP    : ").append(appVersion).append("\n");
        sb.append("DEVICE : ").append(deviceInfo).append("\n");
        if (stack != null) {
            sb.append("STACK  :\n").append(stack).append("\n");
        }
        return sb.toString();
    }

    private int countLines(File file) {
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            while (br.readLine() != null) count++;
        } catch (IOException ignored) {}
        return count;
    }

    private void cleanupOldLogs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        executor.execute(() -> {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    LOG_SUB_DIR);
            if (!dir.exists()) dir = new File(appContext.getFilesDir(), "crash_logs");
            File[] files = dir.listFiles((d, n) -> n.startsWith("log_") && n.endsWith(".txt"));
            if (files == null || files.length <= MAX_LOG_FILES) return;
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            int toDelete = files.length - MAX_LOG_FILES;
            for (int i = 0; i < toDelete; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
                Log.d(TAG, "已清理旧日志: " + files[i].getName());
            }
        });
    }

    // =========================================================
    // Native Crash 检测（libxul.so 等信号崩溃）
    // =========================================================

    /** 标记文件：用于记录上次启动是否发生过 native crash，避免重复记录 */
    private static final String NATIVE_CRASH_MARKER = "native_crash_checked";

    /**
     * 检查上次应用退出是否为 native crash。
     *
     * <p>原理：读取 logcat 中 DEBUG tag 的 tombstone 信息，
     * 如果发现包含本应用包名且带有信号（SIGSEGV/SIGABRT 等），
     * 则认为发生了 native crash，将堆栈写入本地日志。
     */
    private void checkLastNativeCrash() {
        executor.execute(() -> {
            try {
                // 标记文件：避免重复记录同一次崩溃
                File marker = new File(appContext.getFilesDir(), NATIVE_CRASH_MARKER);
                long now = System.currentTimeMillis();

                // 读取最近的 logcat（DEBUG tag 包含 tombstone 信息）
                Process proc = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-d", "-t", "500", "-v", "time", "DEBUG:*", "*:S"});
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder crashInfo = new StringBuilder();
                String line;
                boolean inTombstone = false;
                boolean foundCrash = false;

                while ((line = br.readLine()) != null) {
                    // Tombstone 开始标记（包含本应用包名）
                    if (line.contains("Build fingerprint:") && line.contains(appContext.getPackageName())) {
                        inTombstone = true;
                        crashInfo.setLength(0);
                    }
                    if (inTombstone) {
                        crashInfo.append(line).append("\n");
                        // 检测到信号崩溃
                        if (line.contains("signal 11") || line.contains("signal 6")
                                || line.contains("SIGSEGV") || line.contains("SIGABRT")
                                || line.contains("Fatal signal")) {
                            foundCrash = true;
                        }
                        // Tombstone 结束（通常以 >>> 包裹进程名）
                        if (line.contains(">>>") && line.contains("<<<")) {
                            break;
                        }
                    }
                }
                br.close();
                proc.destroy();

                if (foundCrash && crashInfo.length() > 0) {
                    // 写入日志文件
                    writeToFile("native_crash", "tombstone", crashInfo.toString(), null);
                    Log.e(TAG, "检测到上次 Native 崩溃，已写入日志:\n" + crashInfo);

                    // 标记已处理
                    writeMarker(marker, String.valueOf(now));
                }
            } catch (Exception e) {
                Log.e(TAG, "检查 Native 崩溃失败: " + e.getMessage());
            }
        });
    }

    private void writeMarker(File f, String content) {
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(content);
        } catch (Exception ignored) {}
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private static String getStackTrace(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String buildDeviceInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")";
    }

    private static String getAppVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + "(" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}

