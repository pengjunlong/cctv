package com.cctv.tvapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃/错误信息展示页。
 *
 * <p>当应用崩溃时，{@link MyApplication} 的 UncaughtExceptionHandler 会
 * 启动此 Activity，在屏幕上展示完整的异常堆栈、设备信息和时间。
 *
 * <p>也可以在代码的 catch 块中主动调用：
 * <pre>
 *   startActivity(CrashDisplayActivity.buildIntent(this, e));
 * </pre>
 *
 * <p>遥控器操作：
 * <ul>
 *   <li>BACK 键：关闭本页（退出应用）</li>
 *   <li>DPAD / OK：可聚焦到"复制"和"关闭"按钮</li>
 * </ul>
 */
public class CrashDisplayActivity extends Activity {

    /** Intent extra key：崩溃信息（完整文本，已序列化为字符串）*/
    private static final String EXTRA_CRASH_TEXT = "crash_text";

    /**
     * 构建启动 Intent。
     *
     * @param ctx       Context
     * @param throwable 异常（可为 null）
     */
    public static Intent buildIntent(Context ctx, Throwable throwable) {
        Intent intent = new Intent(ctx, CrashDisplayActivity.class);
        intent.putExtra(EXTRA_CRASH_TEXT, buildCrashText(ctx, throwable));
        return intent;
    }

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏、常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        String crashText = getIntent().getStringExtra(EXTRA_CRASH_TEXT);
        if (crashText == null) crashText = "（未捕获到错误信息）";

        setContentView(buildLayout(crashText));
    }

    // =========================================================
    // 布局（纯代码，不依赖 XML，避免崩溃时 inflate 失败）
    // =========================================================

    private View buildLayout(String crashText) {
        float density = getResources().getDisplayMetrics().density;
        int dp8  = (int) (8  * density);
        int dp12 = (int) (12 * density);
        int dp16 = (int) (16 * density);
        int dp48 = (int) (48 * density);

        // 根容器
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1A1A2E"));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 标题栏 ──
        TextView title = new TextView(this);
        title.setText("❌  应用发生错误");
        title.setTextColor(Color.parseColor("#FF6B6B"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp16, dp16, dp16, dp8);
        root.addView(title);

        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#FF6B6B"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (1 * density));
        divLp.setMargins(dp16, 0, dp16, dp8);
        root.addView(divider, divLp);

        // ── 滚动内容 ──
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, scrollLp);

        TextView tvCrash = new TextView(this);
        tvCrash.setText(crashText);
        tvCrash.setTextColor(Color.parseColor("#E0E0E0"));
        tvCrash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvCrash.setTypeface(Typeface.MONOSPACE);
        tvCrash.setTextIsSelectable(true);
        tvCrash.setPadding(dp16, dp12, dp16, dp12);
        tvCrash.setLineSpacing(0, 1.3f);
        scrollView.addView(tvCrash);

        // ── 底部按钮区 ──
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(dp16, dp8, dp16, dp16);
        root.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // "复制"按钮
        Button btnCopy = makeButton("📋  复制", Color.parseColor("#0F3460"));
        final String textToCopy = crashText;
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("crash", textToCopy));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp48);
        copyLp.setMargins(0, 0, dp12, 0);
        btnRow.addView(btnCopy, copyLp);

        // "关闭"按钮
        Button btnClose = makeButton("✖  关闭", Color.parseColor("#E94560"));
        btnClose.setOnClickListener(v -> finish());
        btnRow.addView(btnClose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp48));

        return root;
    }

    private Button makeButton(String text, int bgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setBackgroundColor(bgColor);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        int dp8 = (int) (8 * getResources().getDisplayMetrics().density);
        btn.setPadding(dp8 * 2, dp8, dp8 * 2, dp8);
        return btn;
    }

    // =========================================================
    // 工具
    // =========================================================

    private static String buildCrashText(Context ctx, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()))
          .append("\n");
        // 设备信息
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(" (Android ").append(Build.VERSION.RELEASE)
          .append(", API ").append(Build.VERSION.SDK_INT).append(")\n");
        // 版本
        if (ctx != null) {
            try {
                android.content.pm.PackageInfo pi =
                        ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                sb.append("版本: ").append(pi.versionName)
                  .append(" (").append(pi.versionCode).append(")\n");
            } catch (Exception ignored) {}
        }
        sb.append("\n");
        // 异常堆栈
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        } else {
            sb.append("（无异常信息）");
        }
        return sb.toString();
    }
}

