package com.cctv.tvapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.xwalk.core.XWalkInitializer;
import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

/**
 * Crosswalk（XWalkView）播放器
 *
 * <p>使用 Crosswalk 内置的 XWalkView（Chromium 53 内核），
 * 独立于系统 WebView，不受系统 WebView 版本限制。
 *
 * <p><b>注意：</b>Crosswalk 项目已于 2019 年停止维护，
 * 最新版 23.53.589.4 对应 Chromium 53，仅支持 Android ≤ 7.0（API 25）。
 * 在 Android 8.0+ 设备上，XWalkView 初始化可能失败，
 * 此时 App 会弹出提示并自动降级到系统 WebView。
 *
 * <p>公共逻辑（频道抓取、遥控器、手势等）由 {@link BasePlayerActivity} 提供。
 *
 * <p>可通过 MENU 键返回引擎选择界面切换引擎。
 */
public class CrosswalkPlayerActivity extends BasePlayerActivity
        implements XWalkInitializer.XWalkInitListener {

    private static final String TAG = "XWalkPlayer";

    private XWalkView xWalkView;
    private XWalkInitializer xWalkInitializer;

    // XWalk 初始化完成前缓存的待加载 URL
    private String pendingUrl;

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // XWalkInitializer 必须在 super.onCreate（其中会调用 initWebEngine）之前创建
        xWalkInitializer = new XWalkInitializer(this, this);
        // 触发异步初始化；完成后回调 onXWalkInitCompleted()
        xWalkInitializer.initAsync();

        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (xWalkView != null) xWalkView.resumeTimers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (xWalkView != null) xWalkView.pauseTimers();
    }

    // =========================================================
    // XWalkInitializer 回调
    // =========================================================

    /**
     * XWalk 引擎初始化成功，现在可以安全创建 XWalkView。
     * 如果此时已有待加载的 URL（由 loadUrl 缓存），立即加载。
     */
    @Override
    public void onXWalkInitCompleted() {
        Log.i(TAG, "XWalk 引擎初始化完成");
        try {
            setupXWalkView();
            if (pendingUrl != null) {
                xWalkView.load(pendingUrl, null);
                pendingUrl = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "XWalkView 创建失败", t);
            fallbackToSystemWebView("Crosswalk 引擎初始化失败（" + t.getMessage() + "），已切换到系统 WebView");
        }
    }

    /** XWalk 引擎初始化失败（设备不兼容等），降级到系统 WebView */
    @Override
    public void onXWalkInitFailed() {
        Log.e(TAG, "XWalk 引擎初始化失败，降级到系统 WebView");
        fallbackToSystemWebView("Crosswalk 引擎不支持本设备，已自动切换到系统 WebView");
    }

    /** XWalk 引擎正在后台初始化中（正常异步过程，无需处理） */
    @Override
    public void onXWalkInitStarted() {
        Log.d(TAG, "XWalk 引擎开始初始化...");
    }

    /** 用户取消了初始化（如取消下载运行时），降级到系统 WebView */
    @Override
    public void onXWalkInitCancelled() {
        Log.w(TAG, "XWalk 引擎初始化被取消，降级到系统 WebView");
        fallbackToSystemWebView("Crosswalk 初始化被取消，已自动切换到系统 WebView");
    }

    // =========================================================
    // BasePlayerActivity 抽象方法实现
    // =========================================================

    /**
     * initWebEngine 在 super.onCreate 中被调用，此时 XWalk 尚未初始化完成，
     * 仅做占位，实际 XWalkView 创建在 onXWalkInitCompleted() 中完成。
     */
    @Override
    protected void initWebEngine() {
        // XWalkInitializer.initAsync() 已在 onCreate() 触发，此处无需操作
        Log.d(TAG, "initWebEngine: 等待 XWalk 异步初始化完成...");
    }

    @Override
    protected void loadUrl(String url) {
        if (xWalkView != null) {
            xWalkView.load(url, null);
        } else {
            // XWalk 尚未初始化完成，缓存 URL，等 onXWalkInitCompleted 时加载
            Log.d(TAG, "XWalk 未就绪，缓存 URL: " + url);
            pendingUrl = url;
        }
    }

    @Override
    protected void evaluateJavaScript(String js) {
        if (xWalkView != null) xWalkView.load(js, null);
    }

    @Override
    protected View getWebViewForTouch() {
        return xWalkView;
    }

    @Override
    protected void requestWebViewFocus() {
        if (xWalkView != null) xWalkView.requestFocus();
    }

    @Override
    protected void destroyWebEngine() {
        if (xWalkView != null) {
            xWalkView.onDestroy();
            xWalkView = null;
        }
    }

    // =========================================================
    // XWalkView 实际创建（在 XWalk 初始化完成后调用）
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private void setupXWalkView() {
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, false);
        XWalkPreferences.setValue(XWalkPreferences.ALLOW_UNIVERSAL_ACCESS_FROM_FILE, false);

        xWalkView = new XWalkView(this, this);
        xWalkView.setId(R.id.web_view);
        xWalkView.setFocusable(true);
        xWalkView.setFocusableInTouchMode(true);

        // 替换布局中的占位 View
        FrameLayout rootFrame = (FrameLayout) ((ViewGroup)
                getWindow().getDecorView().findViewById(android.R.id.content)).getChildAt(0);
        View placeholder = rootFrame.getChildAt(0);
        ViewGroup.LayoutParams lp = placeholder.getLayoutParams();
        rootFrame.removeView(placeholder);
        rootFrame.addView(xWalkView, 0, lp);

        xWalkView.setUserAgentString(PC_USER_AGENT);

        xWalkView.setResourceClient(new XWalkResourceClient(xWalkView) {
            @Override
            public void onLoadStarted(XWalkView view, String url) {
                super.onLoadStarted(view, url);
                showLoading(true, "正在加载...");
            }

            @Override
            public void onLoadFinished(XWalkView view, String url) {
                super.onLoadFinished(view, url);
                showLoading(false, null);
                injectAutoPlayJs();
                scheduleAutoClick();
            }
        });

        xWalkView.setUIClient(new XWalkUIClient(xWalkView) {
            @Override
            public void onFullscreenToggled(XWalkView view, boolean enterFullscreen) {
                if (enterFullscreen) hideSystemUi();
            }
        });

        bindTouchGestureToWebView(xWalkView);
        hideSystemUi();
        Log.i(TAG, "XWalkView 创建成功");
    }

    // =========================================================
    // Crosswalk 不可用时降级到系统 WebView
    // =========================================================

    private void fallbackToSystemWebView(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.w(TAG, "降级到系统 WebView: " + message);

            Intent intent = new Intent(CrosswalkPlayerActivity.this,
                    SystemWebViewPlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}

