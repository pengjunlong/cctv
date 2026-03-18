package com.cctv.tvapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cctv.tvapp.adapter.ChannelAdapter;
import com.cctv.tvapp.model.ChannelItem;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 主界面（GeckoView 全屏播放版）
 *
 * 使用 Mozilla GeckoView（Firefox 引擎）替代系统 WebView，
 * 解决 Android 6.0 自带 WebView 无法兼容 cctv.com 视频播放的问题。
 *
 * 布局结构：
 * ┌──────────────────────────────────────────────────┐
 * │           GeckoView（全屏，直接播放直播页）          │
 * │                                                  │
 * │  ┌────────────┐  ← 按 MENU/上下键 或 触摸左侧边缘   │
 * │  │ 频道列表    │     呼出频道列表浮层                │
 * │  │ (浮层)     │                                  │
 * │  └────────────┘                                  │
 * └──────────────────────────────────────────────────┘
 *
 * 频道列表来源：
 *   启动时用 OkHttp 请求 https://tv.cctv.com/live/index.shtml，
 *   用正则提取所有 /live/cctvXXX/ 格式链接，
 *   解析失败时自动回退到内置备用列表。
 *
 * TV 遥控器交互：
 * - MENU / CHANNEL+/-：呼出/隐藏频道列表
 * - 频道列表可见时：上/下键切换频道，确认键加载并隐藏列表
 * - 频道列表隐藏时：上/下键直接快速换台
 * - BACK 键：若列表可见则隐藏，否则退出
 * - 数字键 1-9：快速跳转对应频道
 *
 * 触屏交互（手机测试用）：
 * - 点击屏幕左侧 1/4 区域：呼出频道列表
 * - 点击频道列表中的某一行：切换到该频道并收起列表
 * - 点击频道列表以外区域：收起频道列表
 * - 上下滑动：切换上一个/下一个频道
 */
public class MainActivity extends Activity {

    private static final String TAG = "CctvFetch";

    // ---- 防抖延迟：快速连按上下键时只加载最终频道 ----
    private static final long CHANNEL_SWITCH_DELAY_MS = 400;
    private static final int MSG_LOAD_CHANNEL = 1;

    // ---- 频道列表自动隐藏延迟（选台后 N 秒无操作自动收起） ----
    private static final long AUTO_HIDE_PANEL_MS = 5000;
    private static final int MSG_HIDE_PANEL = 2;

    // ---- 触屏：点击屏幕左侧多少比例以内触发唤起频道列表 ----
    private static final float TOUCH_LEFT_ZONE_RATIO = 0.25f;

    // ---- 频道抓取入口 ----
    private static final String INDEX_URL = "https://tv.cctv.com/live/index.shtml";

    /**
     * 频道标识 → 中文名映射表
     */
    private static final Map<String, String> CHANNEL_NAME_MAP = new HashMap<>();
    static {
        CHANNEL_NAME_MAP.put("cctv1",       "CCTV-1 综合");
        CHANNEL_NAME_MAP.put("cctv2",       "CCTV-2 财经");
        CHANNEL_NAME_MAP.put("cctv3",       "CCTV-3 综艺");
        CHANNEL_NAME_MAP.put("cctv4",       "CCTV-4 中文国际");
        CHANNEL_NAME_MAP.put("cctv4asia",   "CCTV-4 亚洲");
        CHANNEL_NAME_MAP.put("cctv4europe", "CCTV-4 欧洲");
        CHANNEL_NAME_MAP.put("cctv4america","CCTV-4 美洲");
        CHANNEL_NAME_MAP.put("cctv5",       "CCTV-5 体育");
        CHANNEL_NAME_MAP.put("cctv5plus",   "CCTV-5+ 体育赛事");
        CHANNEL_NAME_MAP.put("cctv6",       "CCTV-6 电影");
        CHANNEL_NAME_MAP.put("cctv7",       "CCTV-7 国防军事");
        CHANNEL_NAME_MAP.put("cctv8",       "CCTV-8 电视剧");
        CHANNEL_NAME_MAP.put("cctv9",       "CCTV-9 纪录");
        CHANNEL_NAME_MAP.put("cctvjilu",    "CCTV-9 纪录");
        CHANNEL_NAME_MAP.put("cctv10",      "CCTV-10 科教");
        CHANNEL_NAME_MAP.put("cctv11",      "CCTV-11 戏曲");
        CHANNEL_NAME_MAP.put("cctv12",      "CCTV-12 社会与法");
        CHANNEL_NAME_MAP.put("cctv13",      "CCTV-13 新闻");
        CHANNEL_NAME_MAP.put("cctv14",      "CCTV-14 少儿");
        CHANNEL_NAME_MAP.put("cctv15",      "CCTV-15 音乐");
        CHANNEL_NAME_MAP.put("cctv16",      "CCTV-16 奥林匹克");
        CHANNEL_NAME_MAP.put("cctv17",      "CCTV-17 农业农村");
    }

    /** 内置备用频道列表（网络抓取失败时使用） */
    private static List<ChannelItem> buildFallbackChannels() {
        List<ChannelItem> list = new ArrayList<>();
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv1/",     "CCTV-1 综合"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv2/",     "CCTV-2 财经"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv3/",     "CCTV-3 综艺"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv4/",     "CCTV-4 中文国际"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv5/",     "CCTV-5 体育"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv5plus/", "CCTV-5+ 体育赛事"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv6/",     "CCTV-6 电影"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv7/",     "CCTV-7 国防军事"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv8/",     "CCTV-8 电视剧"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctvjilu/",  "CCTV-9 纪录"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv10/",    "CCTV-10 科教"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv11/",    "CCTV-11 戏曲"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv12/",    "CCTV-12 社会与法"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv13/",    "CCTV-13 新闻"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv14/",    "CCTV-14 少儿"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv15/",    "CCTV-15 音乐"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv16/",    "CCTV-16 奥林匹克"));
        list.add(new ChannelItem("https://tv.cctv.com/live/cctv17/",    "CCTV-17 农业农村"));
        return list;
    }

    // ---- 运行时频道列表 ----
    private final List<ChannelItem> channels = new ArrayList<>();

    // ---- GeckoView 相关 ----
    /** 全局单例 Runtime（整个 App 生命周期内只创建一次） */
    private static GeckoRuntime sRuntime;
    private GeckoView geckoView;
    private GeckoSession geckoSession;

    // ---- 普通 Views ----
    private View loadingOverlay;
    private TextView tvLoadingHint;
    private View panelChannel;
    private TextView tvCurrentChannel;
    private RecyclerView rvChannelList;

    // ---- 状态 ----
    private ChannelAdapter channelAdapter;
    private int currentChannelIndex = 0;
    private boolean isPanelVisible = false;
    private boolean channelsReady = false;

    // ---- 触屏手势识别 ----
    private GestureDetector gestureDetector;

    // ---- OkHttp（频道列表抓取） ----
    private final OkHttpClient httpClient = new OkHttpClient();

    // ---- Handler（WeakReference 防止泄漏） ----
    private final Handler uiHandler = new UiHandler(this);

    private static class UiHandler extends Handler {
        private final WeakReference<MainActivity> ref;

        UiHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            MainActivity a = ref.get();
            if (a == null || a.isFinishing()) return;
            switch (msg.what) {
                case MSG_LOAD_CHANNEL:
                    a.doLoadChannel(msg.arg1);
                    break;
                case MSG_HIDE_PANEL:
                    a.hidePanelIfVisible();
                    break;
            }
        }
    }

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initGeckoRuntime();
        initViews();
        initGeckoSession();
        initChannelListView();
        initTouchGesture();

        showLoading(true, "正在获取频道列表...");
        startFetchChannels();
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

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
        super.onDestroy();
    }

    // =========================================================
    // GeckoRuntime 初始化（全局单例）
    // =========================================================

    private void initGeckoRuntime() {
        if (sRuntime == null) {
            GeckoRuntimeSettings runtimeSettings = new GeckoRuntimeSettings.Builder()
                    // 允许不安全（HTTP）内容在 HTTPS 页面中加载（混合内容）
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                    // 开启 JS（runtime 级别）
                    .javaScriptEnabled(true)
                    .useMaxScreenDepth(false)
                    .build();
            sRuntime = GeckoRuntime.create(getApplicationContext(), runtimeSettings);
        }
    }

    // =========================================================
    // 初始化 Views
    // =========================================================

    private void initViews() {
        geckoView        = findViewById(R.id.web_view);
        loadingOverlay   = findViewById(R.id.loading_overlay);
        tvLoadingHint    = findViewById(R.id.tv_loading_hint);
        panelChannel     = findViewById(R.id.panel_channel);
        tvCurrentChannel = findViewById(R.id.tv_current_channel);
        rvChannelList    = findViewById(R.id.rv_channel_list);
    }

    // =========================================================
    // GeckoSession 初始化（播放用）
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private void initGeckoSession() {
        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .usePrivateMode(false)
                .userAgentOverride(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Safari/537.36")
                .build();

        geckoSession = new GeckoSession(sessionSettings);

        // ---- 页面加载进度监听 ----
        geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
                showLoading(true, "正在加载...");
            }

            @Override
            public void onPageStop(@NonNull GeckoSession session, boolean success) {
                showLoading(false, null);
                if (success) {
                    injectAutoPlayJs(session);
                    // 延迟 2 秒后开始自动点击播放：用真实 MotionEvent 触发播放
                    scheduleAutoClick();
                }
            }
        });

        // ---- 内容/全屏事件监听 ----
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(@NonNull GeckoSession session, boolean fullScreen) {
                if (fullScreen) {
                    hideSystemUi();
                }
            }
        });

        // ---- 挂载到 GeckoView ----
        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);

        // 确保初始时系统 UI 已隐藏
        hideSystemUi();
    }

    /** 隐藏状态栏、导航栏，进入沉浸式全屏 */
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /**
     * 向 GeckoView 中心派发一次真实的 down→up 触摸事件。
     *
     * 浏览器只允许在真实用户手势（User Activation）的上下文中调用 video.play()，
     * JS 注入的 javascript: URI 不具备 User Activation。
     * 通过 Java 层的 dispatchTouchEvent 派发真实 MotionEvent，
     * GeckoView 会将其识别为用户手势并允许 play()。
     *
     * 触摸位置选在 GeckoView 中心，正好覆盖 cctv.com 播放器的播放按钮区域。
     */
    @SuppressLint("ClickableViewAccessibility")
    private void simulateTouchOnCenter() {
        if (geckoView == null) return;
        float cx = geckoView.getWidth()  / 2f;
        float cy = geckoView.getHeight() / 2f;
        long now = android.os.SystemClock.uptimeMillis();

        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, cx, cy, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50,
                MotionEvent.ACTION_UP, cx, cy, 0);

        android.util.Log.i(TAG, String.format("simulateTouchOnCenter (%.0f, %.0f)", cx, cy));
        geckoView.dispatchTouchEvent(down);
        geckoView.dispatchTouchEvent(up);

        down.recycle();
        up.recycle();
    }

    /**
     * 定时自动点击播放按钮（多次重试）
     *
     * cctv.com 播放器加载时机不确定（onPageStop 时可能还没初始化），
     * 每隔 2 秒点一次中心位置，最多尝试 5 次（共 10 秒）。
     * 这样即使首次点击时播放器还没加载完，后续重试也能触发。
     */
    private int autoClickRetryCount = 0;
    private static final int MAX_AUTO_CLICK_RETRY = 5;

    private void scheduleAutoClick() {
        autoClickRetryCount = 0;
        doAutoClick();
    }

    private void doAutoClick() {
        if (autoClickRetryCount >= MAX_AUTO_CLICK_RETRY) {
            android.util.Log.w(TAG, "自动点击播放已达最大重试次数: " + MAX_AUTO_CLICK_RETRY);
            return;
        }
        autoClickRetryCount++;
        android.util.Log.i(TAG, "自动点击播放 (第 " + autoClickRetryCount + " 次)");

        // 只在频道列表隐藏时点击（避免误触列表）
        if (!isPanelVisible) {
            simulateTouchOnCenter();
        }

        // 2 秒后再试一次（兜底，确保播放器加载完成）
        if (autoClickRetryCount < MAX_AUTO_CLICK_RETRY) {
            uiHandler.postDelayed(this::doAutoClick, 2000);
        }
    }

    // =========================================================
    // 触屏手势（手机测试用）
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private void initTouchGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {

            // 上下滑动切换频道的最小距离 / 速度阈值
            private static final int SWIPE_MIN_DISTANCE = 80;
            private static final int SWIPE_MIN_VELOCITY = 100;

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX();
                int screenWidth = geckoView.getWidth();

                if (isPanelVisible) {
                    // 频道列表可见时：点击列表以外区域则收起
                    // 列表宽度约 220dp，转 px
                    float panelWidthPx = panelChannel.getWidth();
                    if (x > panelWidthPx) {
                        hidePanel();
                        return true;
                    }
                    // 点击列表内部让 RecyclerView 自己处理
                    return false;
                } else {
                    // 频道列表不可见时：点击左侧 1/4 区域唤起列表
                    if (x < screenWidth * TOUCH_LEFT_ZONE_RATIO) {
                        showPanel();
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (e1 == null) return false;
                float dy = e2.getY() - e1.getY();
                float absVY = Math.abs(velocityY);

                // 仅在频道列表隐藏时响应上下滑切台（避免与列表滚动冲突）
                if (!isPanelVisible && Math.abs(dy) > SWIPE_MIN_DISTANCE && absVY > SWIPE_MIN_VELOCITY) {
                    if (dy < 0) {
                        // 上滑 → 下一个频道
                        switchChannelByOffset(1);
                    } else {
                        // 下滑 → 上一个频道
                        switchChannelByOffset(-1);
                    }
                    return true;
                }
                return false;
            }
        });

        // 将手势分发给 GeckoView（GeckoView 本身会消费触摸事件，需要拦截分发）
        geckoView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            // 返回 false 让 GeckoView 继续处理（保证页面内部可以正常点击/滚动）
            return false;
        });
    }

    // =========================================================
    // 频道列表抓取（OkHttp + 正则解析，不依赖 WebView）
    // =========================================================

    /**
     * 用 OkHttp 请求 index.shtml，通过正则提取 /live/cctvXXX/ 链接。
     * 10 秒超时降级到内置备用列表。
     */
    private void startFetchChannels() {
        android.util.Log.i(TAG, "开始抓取频道列表，URL: " + INDEX_URL);

        // 10 秒超时降级
        uiHandler.postDelayed(() -> {
            if (!channelsReady) {
                android.util.Log.w(TAG, "抓取超时（10s），降级到备用列表");
                CrashReporter.reportEvent("fetch_timeout",
                        "频道列表抓取超时（10s），已降级到内置备用列表");
                onFetchFailed("timeout");
            }
        }, 10_000);

        Request request = new Request.Builder()
                .url(INDEX_URL)
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Safari/537.36")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                android.util.Log.e(TAG, "网络请求失败: " + e.getMessage(), e);
                CrashReporter.reportError("fetch_network_error",
                        "频道列表网络请求失败", e);
                uiHandler.post(() -> onFetchFailed("network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response r = response) {
                    int code = r.code();
                    android.util.Log.i(TAG, "HTTP 响应: " + code);
                    if (!r.isSuccessful() || r.body() == null) {
                        android.util.Log.w(TAG, "HTTP 响应异常，降级: http " + code);
                        CrashReporter.reportEvent("fetch_http_error",
                                "频道列表 HTTP 异常: " + code);
                        uiHandler.post(() -> onFetchFailed("http " + code));
                        return;
                    }
                    String html = r.body().string();
                    android.util.Log.d(TAG, "HTML 长度: " + html.length() + " 字节");
                    uiHandler.post(() -> parseAndHandleChannels(html));
                }
            }
        });
    }

    /**
     * 用正则从 HTML 中提取所有 /live/cctvXXX/ 链接，去重后按频道号数字排序
     */
    private void parseAndHandleChannels(String html) {
        if (channelsReady) return;

        android.util.Log.i(TAG, "开始解析频道列表（HTML 长度: " + html.length() + "）");

        Map<String, ChannelItem> seen = new LinkedHashMap<>();
        Pattern pattern = Pattern.compile("/live/(cctv[\\w]+)/?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        int matchCount = 0;

        while (matcher.find()) {
            matchCount++;
            String slug = matcher.group(1).toLowerCase();
            if (seen.containsKey(slug)) {
                android.util.Log.d(TAG, "跳过重复 slug: " + slug);
                continue;
            }

            String url  = "https://tv.cctv.com/live/" + slug + "/";
            String name = CHANNEL_NAME_MAP.get(slug);
            if (name == null) {
                name = slug.toUpperCase();
                android.util.Log.d(TAG, "未知频道（无映射）: " + slug + " → " + name);
            }
            seen.put(slug, new ChannelItem(url, name));
            android.util.Log.d(TAG, "新增频道: " + slug + " → " + name);
        }

        android.util.Log.i(TAG, "正则命中 " + matchCount + " 次，去重后得到 " + seen.size() + " 个频道");

        if (seen.isEmpty()) {
            android.util.Log.w(TAG, "未找到任何频道链接，降级到备用列表");
            CrashReporter.reportEvent("fetch_parse_empty",
                    "正则未匹配到任何频道链接，HTML长度: " + html.length());
            onFetchFailed("no live links found in html");
            return;
        }

        // 按频道号数字顺序排序
        List<ChannelItem> sorted = new ArrayList<>(seen.values());
        java.util.Collections.sort(sorted, (a, b) -> {
            String slugA = a.getUrl().replaceAll(".*/(cctv[\\w]+)/?$", "$1");
            String slugB = b.getUrl().replaceAll(".*/(cctv[\\w]+)/?$", "$1");
            int numA = extractChannelNumber(slugA);
            int numB = extractChannelNumber(slugB);
            if (numA != numB) return Integer.compare(numA, numB);
            return slugA.compareTo(slugB);
        });

        android.util.Log.i(TAG, "=== 最终频道列表（共 " + sorted.size() + " 个，按频道号排序）===");
        for (int i = 0; i < sorted.size(); i++) {
            ChannelItem ch = sorted.get(i);
            android.util.Log.i(TAG, String.format("  [%2d] %s  →  %s", i + 1, ch.getName(), ch.getUrl()));
        }

        channelsReady = true;
        channels.clear();
        channels.addAll(sorted);
        onChannelsReady();
    }

    /**
     * 从 slug 中提取频道号数字（用于排序）
     * cctv1 → 1, cctv5plus → 5, cctv4asia → 4, cctvjilu → 999
     */
    private int extractChannelNumber(String slug) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("cctv(\\d+)");
        java.util.regex.Matcher m = p.matcher(slug);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 999;
    }

    /** 网络抓取失败 → 降级到内置备用列表 */
    private void onFetchFailed(String reason) {
        if (channelsReady) return;
        channelsReady = true;
        android.util.Log.w(TAG, "频道抓取失败，使用备用列表：" + reason);
        channels.clear();
        channels.addAll(buildFallbackChannels());
        onChannelsReady();
    }

    /** 频道列表就绪（不论来源）：更新 Adapter 并加载第一个频道 */
    private void onChannelsReady() {
        channelAdapter.notifyDataSetChanged();
        showLoading(false, null);
        doLoadChannel(0);
    }

    // =========================================================
    // 频道切换
    // =========================================================

    private void scheduleLoadChannel(int index) {
        uiHandler.removeMessages(MSG_LOAD_CHANNEL);
        Message msg = uiHandler.obtainMessage(MSG_LOAD_CHANNEL, index, 0);
        uiHandler.sendMessageDelayed(msg, CHANNEL_SWITCH_DELAY_MS);
    }

    private void doLoadChannel(int index) {
        if (index < 0 || index >= channels.size()) return;

        currentChannelIndex = index;
        channelAdapter.setSelectedPosition(index);
        scrollChannelListTo(index);

        ChannelItem channel = channels.get(index);
        tvCurrentChannel.setText(channel.getName());

        showLoading(true, "正在加载 " + channel.getName() + "...");
        android.util.Log.i(TAG, String.format("doLoadChannel %s  →  %s", channel.getName(), channel.getUrl()));
        geckoSession.loadUri(channel.getUrl());

        scheduleAutoHidePanel();
    }

    private void scrollChannelListTo(int index) {
        rvChannelList.scrollToPosition(index);
        rvChannelList.post(() -> {
            RecyclerView.ViewHolder vh = rvChannelList.findViewHolderForAdapterPosition(index);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private void switchChannelByOffset(int offset) {
        if (channels.isEmpty()) return;
        int newIndex = currentChannelIndex + offset;
        if (newIndex < 0) newIndex = channels.size() - 1;
        else if (newIndex >= channels.size()) newIndex = 0;

        currentChannelIndex = newIndex;
        channelAdapter.setSelectedPosition(newIndex);
        scrollChannelListTo(newIndex);

        if (isPanelVisible) {
            tvCurrentChannel.setText(channels.get(newIndex).getName());
            scheduleAutoHidePanel();
        } else {
            scheduleLoadChannel(newIndex);
        }
    }

    // =========================================================
    // 侧边栏显隐
    // =========================================================

    private void showPanel() {
        if (channels.isEmpty()) return;
        isPanelVisible = true;
        panelChannel.setVisibility(View.VISIBLE);
        rvChannelList.requestFocus();
        scrollChannelListTo(currentChannelIndex);
        scheduleAutoHidePanel();
    }

    private void hidePanel() {
        isPanelVisible = false;
        panelChannel.setVisibility(View.GONE);
        uiHandler.removeMessages(MSG_HIDE_PANEL);
        geckoView.requestFocus();
    }

    private void hidePanelIfVisible() {
        if (isPanelVisible) hidePanel();
    }

    private void togglePanel() {
        if (isPanelVisible) hidePanel();
        else showPanel();
    }

    private void scheduleAutoHidePanel() {
        uiHandler.removeMessages(MSG_HIDE_PANEL);
        uiHandler.sendEmptyMessageDelayed(MSG_HIDE_PANEL, AUTO_HIDE_PANEL_MS);
    }

    // =========================================================
    // JS 注入
    // =========================================================

    /**
     * 页面加载完成后注入，实现：
     * 1. 强制 video 元素固定在全屏、保持原比例（object-fit:contain，有黑边）
     * 2. 隐藏页面所有非播放器元素（导航栏、广告、侧边栏等）
     * 3. 通过 MutationObserver 持续监听 DOM：video 一旦出现立即触发播放
     * 4. 对已有 video 立即尝试播放；找不到先点播放按钮
     */
    private void injectAutoPlayJs(GeckoSession session) {
        // CSS：直接对 video 元素本身强制全屏 + contain 保持比例
        // 不依赖父容器结构，规避各播放器 SDK 的不同 DOM 层级
        String css =
                // 全局重置
                "html,body{"
                + "margin:0!important;padding:0!important;"
                + "width:100vw!important;height:100vh!important;"
                + "overflow:hidden!important;background:#000!important}"
                // 直接对 video 强制全屏 + contain（保留比例，黑边填充）
                + "video{"
                + "position:fixed!important;top:0!important;left:0!important;"
                + "width:100vw!important;height:100vh!important;"
                + "max-width:100vw!important;max-height:100vh!important;"
                + "object-fit:contain!important;"  // 保持比例，不裁剪，有黑边
                + "z-index:999999!important;background:#000!important;"
                + "transform:none!important;margin:0!important;}";

        String js = "javascript:(function(){"

                // ── Step 1: 注入 CSS（幂等，防止重复注入） ──
                + "if(!document.getElementById('_tvfs')){"
                + "var s=document.createElement('style');s.id='_tvfs';"
                + "s.textContent='" + css + "';"
                + "(document.head||document.documentElement).appendChild(s);}"

                // ── Step 2: 隐藏所有非 video 的 body 直接子元素 ──
                + "function hideNonVideo(){"
                + "var v=document.querySelector('video');"
                + "if(!v)return;"
                // 找到 video 最顶层的 body 直接子节点
                + "var top=v;"
                + "while(top.parentElement&&top.parentElement!==document.body)"
                + "{top=top.parentElement;}"
                + "var ch=document.body.children;"
                + "for(var k=0;k<ch.length;k++){"
                + "if(ch[k]!==top)ch[k].style.cssText='display:none!important';}}"

                // ── Step 3: 模拟点击指定元素（同时派发 touch+click 事件）──
                + "function simulateClick(el){"
                + "if(!el)return;"
                + "var r=el.getBoundingClientRect();"
                + "var cx=r.left+r.width/2,cy=r.top+r.height/2;"
                + "['touchstart','touchend','mousedown','mouseup','click'].forEach(function(t){"
                + "var ev;"
                + "if(t.startsWith('touch')){"
                + "var touch=new Touch({identifier:1,target:el,clientX:cx,clientY:cy});"
                + "ev=new TouchEvent(t,{bubbles:true,cancelable:true,touches:[touch],changedTouches:[touch]});"
                + "}else{"
                + "ev=new MouseEvent(t,{bubbles:true,cancelable:true,clientX:cx,clientY:cy});}"
                + "el.dispatchEvent(ev);});}"

                // ── Step 4: 尝试触发播放：解除静音 + 找按钮 + video.play() ──
                + "function tryPlay(){"
                // 解除所有 video 的静音（GeckoView 自动播放时默认 muted）
                + "var allV=document.querySelectorAll('video');"
                + "for(var m=0;m<allV.length;m++){"
                + "allV[m].muted=false;"
                + "allV[m].volume=1.0;}"
                // 策略 A：遍历所有可见元素，找"播放"相关的按钮并点击
                + "var btns=['.play-btn','.vjs-big-play-button','.prism-big-play-btn',"
                + "'.prism-start-btn','.prism-play-btn','.m-player-start',"
                + "'#playBtn','.live-play-btn','.player-play','.icon-play',"
                + "'.btn-play','.J-play','.J_play','.play_button',"
                + "'[class*=play][class*=btn]','[class*=btn][class*=play]',"
                + "'[class*=play][class*=start]','[class*=start][class*=play]'];"
                + "for(var i=0;i<btns.length;i++){"
                + "var el=document.querySelector(btns[i]);"
                + "if(el&&el.offsetParent!==null&&el.offsetWidth>0){"
                + "simulateClick(el);break;}}"
                // 策略 B：直接 video.play()
                + "var vv=document.querySelectorAll('video');"
                + "for(var j=0;j<vv.length;j++){"
                + "var v=vv[j];"
                + "if(v.paused){"
                + "v.muted=false;v.volume=1.0;"
                + "var p=v.play();"
                + "if(p&&p.catch)p.catch(function(){});}}"
                // 策略 C：点击 video 元素中心（触发播放器内置点击播放逻辑）
                + "var vEl=document.querySelector('video');"
                + "if(vEl&&vEl.paused)simulateClick(vEl);"
                + "}"

                // ── Step 5: 核心入口：处理当前已有的 video ──
                + "function handleVideo(){"
                + "hideNonVideo();"
                + "tryPlay();}"

                // ── Step 6: 立即执行一次 ──
                + "handleVideo();"

                // ── Step 7: MutationObserver 持续监听，video 插入 DOM 时立刻处理 ──
                // cctv.com 播放器是异步加载的，onPageStop 时 video 可能还未存在
                + "if(!window._tvObserver){"
                + "window._tvObserver=new MutationObserver(function(muts){"
                + "for(var i=0;i<muts.length;i++){"
                + "for(var j=0;j<muts[i].addedNodes.length;j++){"
                + "var n=muts[i].addedNodes[j];"
                + "if(n.nodeName==='VIDEO'||"
                + "(n.querySelector&&n.querySelector('video'))){"
                + "handleVideo();return;}}}"
                + "});"
                + "window._tvObserver.observe(document.body||document.documentElement,"
                + "{childList:true,subtree:true});}"

                // ── Step 8: 兜底轮询（应对不触发 MutationObserver 的播放器） ──
                // 每 1.5 秒重试一次，最多 20 秒（约 13 次）
                + "var _tvRetry=0;"
                + "function _tvPoll(){"
                + "if(_tvRetry++>13)return;"
                + "var v=document.querySelector('video');"
                + "if(v){"
                + "handleVideo();"
                // video 已出现但还在缓冲，继续轮询直到开始播放
                + "if(v.paused)setTimeout(_tvPoll,1500);"
                + "}else{setTimeout(_tvPoll,1500);}}"
                + "setTimeout(_tvPoll,1500);"

                + "})();";

        session.loadUri(js);
    }

    // =========================================================
    // 频道列表 RecyclerView 初始化
    // =========================================================

    private void initChannelListView() {
        channelAdapter = new ChannelAdapter(channels, (channel, position) -> {
            // 触屏点击某个频道条目：切换频道并收起列表
            doLoadChannel(position);
            hidePanel();
        });
        rvChannelList.setLayoutManager(new LinearLayoutManager(this));
        rvChannelList.setAdapter(channelAdapter);
    }

    // =========================================================
    // 遥控器按键
    // =========================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {

            case KeyEvent.KEYCODE_BACK:
                if (isPanelVisible) { hidePanel(); }
                else { finish(); }
                return true;

            case KeyEvent.KEYCODE_MENU:
                togglePanel();
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (!isPanelVisible) showPanel();
                switchChannelByOffset(-1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (!isPanelVisible) showPanel();
                switchChannelByOffset(1);
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (!isPanelVisible) showPanel();
                switchChannelByOffset(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!isPanelVisible) showPanel();
                switchChannelByOffset(1);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (isPanelVisible) {
                    doLoadChannel(currentChannelIndex);
                    hidePanel();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!isPanelVisible) { showPanel(); return true; }
                break;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isPanelVisible) { hidePanel(); return true; }
                break;

            case KeyEvent.KEYCODE_1: jumpToChannel(0); return true;
            case KeyEvent.KEYCODE_2: jumpToChannel(1); return true;
            case KeyEvent.KEYCODE_3: jumpToChannel(2); return true;
            case KeyEvent.KEYCODE_4: jumpToChannel(3); return true;
            case KeyEvent.KEYCODE_5: jumpToChannel(4); return true;
            case KeyEvent.KEYCODE_6: jumpToChannel(5); return true;
            case KeyEvent.KEYCODE_7: jumpToChannel(6); return true;
            case KeyEvent.KEYCODE_8: jumpToChannel(7); return true;
            case KeyEvent.KEYCODE_9: jumpToChannel(8); return true;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void jumpToChannel(int index) {
        if (!channelsReady || index >= channels.size()) return;
        doLoadChannel(index);
        if (isPanelVisible) hidePanel();
    }

    // =========================================================
    // UI 工具
    // =========================================================

    private void showLoading(boolean show, @Nullable String hint) {
        if (loadingOverlay == null) return;
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && hint != null && tvLoadingHint != null) {
            tvLoadingHint.setText(hint);
        }
    }
}
