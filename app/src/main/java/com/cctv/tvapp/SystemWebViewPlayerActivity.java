package com.cctv.tvapp;

import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 系统 WebView 播放器（Android 原生 WebView）
 *
 * <p>使用 Android 系统内置的 WebView 组件（基于 Chromium），
 * 体积最小，启动最快，但兼容性依赖系统 WebView 版本。
 *
 * <p>公共逻辑（频道抓取、遥控器、手势等）由 {@link BasePlayerActivity} 提供。
 *
 * <p>可通过 MENU 键返回引擎选择界面切换引擎。
 */
public class SystemWebViewPlayerActivity extends BasePlayerActivity {

    private WebView webView;

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    // =========================================================
    // BasePlayerActivity 抽象方法实现
    // =========================================================

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    protected void initWebEngine() {
        // 从布局中找到 GeckoView 占位符，替换为系统 WebView
        FrameLayout rootFrame = (FrameLayout) ((ViewGroup)
                getWindow().getDecorView().findViewById(android.R.id.content)).getChildAt(0);
        View geckoPlaceholder = rootFrame.getChildAt(0);
        ViewGroup.LayoutParams lp = geckoPlaceholder.getLayoutParams();
        rootFrame.removeView(geckoPlaceholder);

        webView = new WebView(this);
        webView.setId(R.id.web_view);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        rootFrame.addView(webView, 0, lp);

        // WebView 配置
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(PC_USER_AGENT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);

        // 页面加载事件
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                showLoading(true, "正在加载...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                showLoading(false, null);
                injectAutoPlayJs();
                scheduleAutoClick();
            }
        });

        // 全屏视频支持
        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;
            private CustomViewCallback customViewCallback;

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
                decorView.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
                decorView.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                hideSystemUi();
            }
        });

        bindTouchGestureToWebView(webView);
        hideSystemUi();
    }

    @Override
    protected void loadUrl(String url) {
        if (webView != null) webView.loadUrl(url);
    }

    @Override
    protected void evaluateJavaScript(String js) {
        if (webView != null) webView.loadUrl(js);
    }

    @Override
    protected View getWebViewForTouch() {
        return webView;
    }

    @Override
    protected void requestWebViewFocus() {
        if (webView != null) webView.requestFocus();
    }

    @Override
    protected void destroyWebEngine() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }
}

