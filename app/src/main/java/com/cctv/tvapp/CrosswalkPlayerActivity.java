package com.cctv.tvapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

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
public class CrosswalkPlayerActivity extends BasePlayerActivity {

    private static final String TAG = "XWalkPlayer";

    private XWalkView xWalkView;

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
        } catch (Throwable t) {
            // XWalkView 初始化异常（如 Android 版本不兼容），降级到系统 WebView
            Log.e(TAG, "XWalkView 初始化失败，降级到系统 WebView", t);
            fallbackToSystemWebView("Crosswalk 引擎初始化失败（" + t.getMessage() + "），已切换到系统 WebView");
        }
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
    // BasePlayerActivity 抽象方法实现
    // =========================================================

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initWebEngine() {
        // 关闭远程调试（生产版本）
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, false);
        XWalkPreferences.setValue(XWalkPreferences.ALLOW_UNIVERSAL_ACCESS_FROM_FILE, false);

        // 创建 XWalkView（构造时可能抛出异常，由 onCreate 中的 try-catch 捕获）
        xWalkView = new XWalkView(this, this);
        xWalkView.setId(R.id.web_view);
        xWalkView.setFocusable(true);
        xWalkView.setFocusableInTouchMode(true);

        // 替换布局中的 GeckoView 占位符
        FrameLayout rootFrame = (FrameLayout) ((ViewGroup)
                getWindow().getDecorView().findViewById(android.R.id.content)).getChildAt(0);
        View geckoPlaceholder = rootFrame.getChildAt(0);
        ViewGroup.LayoutParams lp = geckoPlaceholder.getLayoutParams();
        rootFrame.removeView(geckoPlaceholder);
        rootFrame.addView(xWalkView, 0, lp);

        xWalkView.setUserAgentString(PC_USER_AGENT);

        // 页面加载事件
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

        // 全屏视频支持
        xWalkView.setUIClient(new XWalkUIClient(xWalkView) {
            @Override
            public void onFullscreenToggled(XWalkView view, boolean enterFullscreen) {
                if (enterFullscreen) hideSystemUi();
            }
        });

        bindTouchGestureToWebView(xWalkView);
        hideSystemUi();
        Log.i(TAG, "XWalkView 初始化成功");
    }

    @Override
    protected void loadUrl(String url) {
        if (xWalkView != null) xWalkView.load(url, null);
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
    // Crosswalk 不可用时降级到系统 WebView
    // =========================================================

    private void fallbackToSystemWebView(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.w(TAG, "降级到系统 WebView: " + message);

            // 保存降级后的选择，下次直接进入系统 WebView
            getSharedPreferences(WebEngineType.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(WebEngineType.PREFS_KEY, WebEngineType.SYSTEM.name())
                    .apply();

            Intent intent = new Intent(CrosswalkPlayerActivity.this,
                    SystemWebViewPlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}

