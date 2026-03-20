package com.cctv.tvapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/**
 * GeckoView 播放器（Mozilla Firefox 引擎）
 *
 * <p>使用 Mozilla GeckoView（Firefox 引擎）替代系统 WebView，
 * 解决 Android 6.0 自带 WebView 无法兼容 cctv.com 视频播放的问题。
 *
 * <p>布局结构：
 * <pre>
 * ┌──────────────────────────────────────────────────┐
 * │           GeckoView（全屏，直接播放直播页）          │
 * │                                                  │
 * │  ┌────────────┐  ← 按 MENU/上下键 或 触摸左侧边缘   │
 * │  │ 频道列表    │     呼出频道列表浮层                │
 * │  │ (浮层)     │                                  │
 * │  └────────────┘                                  │
 * └──────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>引擎选择：App 启动时由 {@link EngineSelectActivity} 弹出引擎选择，
 * 用户选择 GeckoView 后进入本 Activity。
 * 在播放过程中按 MENU 键可返回引擎选择界面切换引擎。
 *
 * <p>公共逻辑（频道抓取、遥控器、手势等）由 {@link BasePlayerActivity} 提供。
 */
public class GeckoPlayerActivity extends BasePlayerActivity {

    private static final String TAG = "GeckoPlayer";

    /** 全局单例 Runtime（整个 App 生命周期内只创建一次） */
    private static GeckoRuntime sRuntime;
    private GeckoView geckoView;
    private GeckoSession geckoSession;

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 兜底版本检测：GeckoView 148 要求 Android 8.0（API 26）及以上
        // 正常情况下 EngineSelectActivity 会在路由时拦截，这里作为最后一道保障
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "GeckoView 148 不支持 Android " + Build.VERSION.RELEASE
                    + "（< 8.0），自动切换到 Crosswalk");
            Toast.makeText(this,
                    "GeckoView 需要 Android 8+，本机（Android "
                            + Build.VERSION.RELEASE + "）自动切换到 Crosswalk",
                    Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, CrosswalkPlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(WebEngineType.EXTRA_KEY, WebEngineType.CROSSWALK.name());
            intent.putExtra(WebEngineType.EXTRA_AUTO_ROUTED, true);
            startActivity(intent);
            finish();
            return;
        }
        initGeckoRuntime();   // Runtime 必须在 super.onCreate → initWebEngine 之前初始化
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (geckoSession != null) geckoSession.setActive(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (geckoSession != null) geckoSession.setActive(false);
    }

    // =========================================================
    // BasePlayerActivity 抽象方法实现
    // =========================================================

    @Override
    protected void initWebEngine() {
        initGeckoSession();
    }

    @Override
    protected void loadUrl(String url) {
        if (geckoSession != null) geckoSession.loadUri(url);
    }

    @Override
    protected void evaluateJavaScript(String js) {
        if (geckoSession != null) geckoSession.loadUri(js);
    }

    @Override
    protected View getWebViewForTouch() {
        return geckoView;
    }

    @Override
    protected void requestWebViewFocus() {
        if (geckoView != null) geckoView.requestFocus();
    }

    @Override
    protected void destroyWebEngine() {
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
    }

    // =========================================================
    // GeckoRuntime 初始化（全局单例）
    // =========================================================

    private void initGeckoRuntime() {
        if (sRuntime == null) {
            GeckoRuntimeSettings runtimeSettings = new GeckoRuntimeSettings.Builder()
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                    .javaScriptEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(getApplicationContext(), runtimeSettings);
        }
    }

    // =========================================================
    // GeckoSession 初始化
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private void initGeckoSession() {
        geckoView = findViewById(R.id.web_view);

        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .usePrivateMode(false)
                .userAgentOverride(PC_USER_AGENT)
                .build();

        geckoSession = new GeckoSession(sessionSettings);

        // 页面加载进度监听
        geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
                showLoading(true, "正在加载...");
            }

            @Override
            public void onPageStop(@NonNull GeckoSession session, boolean success) {
                showLoading(false, null);
                if (success) {
                    injectAutoPlayJs();
                    scheduleAutoClick();
                }
            }
        });

        // 全屏事件监听
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(@NonNull GeckoSession session, boolean fullScreen) {
                if (fullScreen) hideSystemUi();
            }
        });

        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);

        bindTouchGestureToWebView(geckoView);
        hideSystemUi();
    }
}
