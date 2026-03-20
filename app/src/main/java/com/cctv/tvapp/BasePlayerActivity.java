package com.cctv.tvapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
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
 * 所有播放器 Activity 的公共基类。
 *
 * <p>封装了以下通用逻辑：
 * <ul>
 *   <li>频道名映射表与备用频道列表</li>
 *   <li>OkHttp 抓取 + 正则解析频道列表</li>
 *   <li>频道切换（防抖调度）</li>
 *   <li>侧边栏频道面板显隐 + 自动隐藏</li>
 *   <li>触屏手势（左划唤出、滑动换台）</li>
 *   <li>遥控器按键处理</li>
 *   <li>自动点击播放重试机制</li>
 *   <li>沉浸式全屏（hideSystemUi）</li>
 *   <li>Loading 遮罩</li>
 *   <li>MENU 键返回引擎选择界面</li>
 * </ul>
 *
 * <p>子类只需实现以下抽象方法即可：
 * <ul>
 *   <li>{@link #initWebEngine()} — 初始化具体 WebView 实现</li>
 *   <li>{@link #loadUrl(String)} — 加载指定 URL</li>
 *   <li>{@link #evaluateJavaScript(String)} — 执行 JS（注入自动播放脚本）</li>
 *   <li>{@link #getWebViewForTouch()} — 返回用于分发触摸事件的 View</li>
 *   <li>{@link #requestWebViewFocus()} — 隐藏面板后将焦点还给 WebView</li>
 *   <li>{@link #destroyWebEngine()} — 销毁引擎资源</li>
 * </ul>
 */
public abstract class BasePlayerActivity extends Activity {

    protected static final String BASE_TAG = "BasePlayer";

    // ---- 防抖延迟 ----
    protected static final long CHANNEL_SWITCH_DELAY_MS = 400;
    protected static final int MSG_LOAD_CHANNEL = 1;

    // ---- 频道面板自动隐藏 ----
    protected static final long AUTO_HIDE_PANEL_MS = 5000;
    protected static final int MSG_HIDE_PANEL = 2;

    // ---- 触屏左侧唤出面板的区域比例 ----
    protected static final float TOUCH_LEFT_ZONE_RATIO = 0.25f;

    // ---- 频道列表抓取地址 ----
    protected static final String INDEX_URL = "https://tv.cctv.com/live/index.shtml";

    // ---- User-Agent ----
    protected static final String PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    // =========================================================
    // 频道名映射表
    // =========================================================

    protected static final Map<String, String> CHANNEL_NAME_MAP = new HashMap<>();
    static {
        CHANNEL_NAME_MAP.put("cctv1",        "CCTV-1 综合");
        CHANNEL_NAME_MAP.put("cctv2",        "CCTV-2 财经");
        CHANNEL_NAME_MAP.put("cctv3",        "CCTV-3 综艺");
        CHANNEL_NAME_MAP.put("cctv4",        "CCTV-4 中文国际");
        CHANNEL_NAME_MAP.put("cctv4asia",    "CCTV-4 亚洲");
        CHANNEL_NAME_MAP.put("cctv4europe",  "CCTV-4 欧洲");
        CHANNEL_NAME_MAP.put("cctv4america", "CCTV-4 美洲");
        CHANNEL_NAME_MAP.put("cctv5",        "CCTV-5 体育");
        CHANNEL_NAME_MAP.put("cctv5plus",    "CCTV-5+ 体育赛事");
        CHANNEL_NAME_MAP.put("cctv6",        "CCTV-6 电影");
        CHANNEL_NAME_MAP.put("cctv7",        "CCTV-7 国防军事");
        CHANNEL_NAME_MAP.put("cctv8",        "CCTV-8 电视剧");
        CHANNEL_NAME_MAP.put("cctv9",        "CCTV-9 纪录");
        CHANNEL_NAME_MAP.put("cctvjilu",     "CCTV-9 纪录");
        CHANNEL_NAME_MAP.put("cctv10",       "CCTV-10 科教");
        CHANNEL_NAME_MAP.put("cctv11",       "CCTV-11 戏曲");
        CHANNEL_NAME_MAP.put("cctv12",       "CCTV-12 社会与法");
        CHANNEL_NAME_MAP.put("cctv13",       "CCTV-13 新闻");
        CHANNEL_NAME_MAP.put("cctv14",       "CCTV-14 少儿");
        CHANNEL_NAME_MAP.put("cctv15",       "CCTV-15 音乐");
        CHANNEL_NAME_MAP.put("cctv16",       "CCTV-16 奥林匹克");
        CHANNEL_NAME_MAP.put("cctv17",       "CCTV-17 农业农村");
    }

    // =========================================================
    // 备用频道列表
    // =========================================================

    protected static List<ChannelItem> buildFallbackChannels() {
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

    // =========================================================
    // 字段
    // =========================================================

    protected final List<ChannelItem> channels = new ArrayList<>();

    // ---- Views ----
    protected View loadingOverlay;
    protected TextView tvLoadingHint;
    protected View panelChannel;
    protected TextView tvCurrentChannel;
    protected RecyclerView rvChannelList;

    // ---- 状态 ----
    protected ChannelAdapter channelAdapter;
    protected int currentChannelIndex = 0;
    protected boolean isPanelVisible = false;
    protected boolean channelsReady = false;

    // ---- 手势 ----
    protected GestureDetector gestureDetector;

    // ---- OkHttp ----
    protected final OkHttpClient httpClient = new OkHttpClient();

    // ---- Handler ----
    protected final Handler uiHandler = new UiHandler(this);

    // ---- 自动点击 ----
    private int autoClickRetryCount = 0;
    private static final int MAX_AUTO_CLICK_RETRY = 5;

    // =========================================================
    // 抽象方法（子类实现）
    // =========================================================

    /**
     * 初始化具体的 WebView 引擎（GeckoView / WebView / XWalkView）。
     * 此方法在 {@link #setContentView} 和 {@link #initViews()} 之后调用。
     */
    protected abstract void initWebEngine();

    /**
     * 加载指定 URL（切换频道时调用）。
     */
    protected abstract void loadUrl(String url);

    /**
     * 执行 JavaScript（注入自动播放脚本时调用）。
     * @param js 以 "javascript:" 开头的完整脚本字符串
     */
    protected abstract void evaluateJavaScript(String js);

    /**
     * 返回 WebView 对应的 View，用于：
     * <ul>
     *   <li>绑定触摸手势监听</li>
     *   <li>获取宽高（自动点击定位中心）</li>
     * </ul>
     */
    protected abstract View getWebViewForTouch();

    /**
     * 隐藏频道面板后，将输入焦点归还给 WebView。
     */
    protected abstract void requestWebViewFocus();

    /**
     * 释放 WebView 引擎资源（在 {@link #onDestroy} 中调用）。
     */
    protected abstract void destroyWebEngine();

    // =========================================================
    // Handler（静态内部类，防止内存泄漏）
    // =========================================================

    private static class UiHandler extends Handler {
        private final WeakReference<BasePlayerActivity> ref;

        UiHandler(BasePlayerActivity a) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            BasePlayerActivity a = ref.get();
            if (a == null || a.isFinishing()) return;
            if (msg.what == MSG_LOAD_CHANNEL)  a.doLoadChannel(msg.arg1);
            else if (msg.what == MSG_HIDE_PANEL) a.hidePanelIfVisible();
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
        initViews();
        initWebEngine();          // 子类实现
        initChannelListView();
        initTouchGesture();
        showLoading(true, "正在获取频道列表...");
        startFetchChannels();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        destroyWebEngine();       // 子类实现
        super.onDestroy();
    }

    // =========================================================
    // Views 初始化
    // =========================================================

    private void initViews() {
        loadingOverlay   = findViewById(R.id.loading_overlay);
        tvLoadingHint    = findViewById(R.id.tv_loading_hint);
        panelChannel     = findViewById(R.id.panel_channel);
        tvCurrentChannel = findViewById(R.id.tv_current_channel);
        rvChannelList    = findViewById(R.id.rv_channel_list);
    }

    // =========================================================
    // 沉浸式全屏
    // =========================================================

    protected void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // =========================================================
    // 频道列表抓取
    // =========================================================

    private void startFetchChannels() {
        // 10 秒超时降级
        uiHandler.postDelayed(() -> {
            if (!channelsReady) {
                Log.w(BASE_TAG, "频道抓取超时，降级到备用列表");
                CrashReporter.reportEvent("fetch_timeout", "频道列表抓取超时（10s），已降级到内置备用列表");
                onFetchFailed("timeout");
            }
        }, 10_000);

        Request request = new Request.Builder()
                .url(INDEX_URL)
                .header("User-Agent", PC_USER_AGENT)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(BASE_TAG, "网络请求失败: " + e.getMessage(), e);
                CrashReporter.reportError("fetch_network_error", "频道列表网络请求失败", e);
                uiHandler.post(() -> onFetchFailed("network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        Log.w(BASE_TAG, "HTTP 响应异常: " + r.code());
                        CrashReporter.reportEvent("fetch_http_error", "频道列表 HTTP 异常: " + r.code());
                        uiHandler.post(() -> onFetchFailed("http " + r.code()));
                        return;
                    }
                    String html = r.body().string();
                    uiHandler.post(() -> parseAndHandleChannels(html));
                }
            }
        });
    }

    private void parseAndHandleChannels(String html) {
        if (channelsReady) return;

        Map<String, ChannelItem> seen = new LinkedHashMap<>();
        Pattern pattern = Pattern.compile("/live/(cctv[\\w]+)/?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        while (matcher.find()) {
            String slug = matcher.group(1).toLowerCase();
            if (seen.containsKey(slug)) continue;
            String url  = "https://tv.cctv.com/live/" + slug + "/";
            String name = CHANNEL_NAME_MAP.containsKey(slug)
                    ? CHANNEL_NAME_MAP.get(slug)
                    : slug.toUpperCase();
            seen.put(slug, new ChannelItem(url, name));
        }

        if (seen.isEmpty()) {
            Log.w(BASE_TAG, "未找到频道链接，降级到备用列表");
            CrashReporter.reportEvent("fetch_parse_empty",
                    "正则未匹配到任何频道链接，HTML长度: " + html.length());
            onFetchFailed("no live links");
            return;
        }

        List<ChannelItem> sorted = new ArrayList<>(seen.values());
        java.util.Collections.sort(sorted, (a, b) -> {
            int na = extractChannelNumber(a.getUrl().replaceAll(".*/(cctv[\\w]+)/?$", "$1"));
            int nb = extractChannelNumber(b.getUrl().replaceAll(".*/(cctv[\\w]+)/?$", "$1"));
            if (na != nb) return Integer.compare(na, nb);
            return a.getUrl().compareTo(b.getUrl());
        });

        channelsReady = true;
        channels.clear();
        channels.addAll(sorted);
        onChannelsReady();
    }

    private int extractChannelNumber(String slug) {
        Matcher m = Pattern.compile("cctv(\\d+)").matcher(slug);
        return m.find() ? Integer.parseInt(m.group(1)) : 999;
    }

    private void onFetchFailed(String reason) {
        if (channelsReady) return;
        channelsReady = true;
        Log.w(BASE_TAG, "频道抓取失败，使用备用列表：" + reason);
        channels.clear();
        channels.addAll(buildFallbackChannels());
        onChannelsReady();
    }

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
        uiHandler.sendMessageDelayed(uiHandler.obtainMessage(MSG_LOAD_CHANNEL, index, 0),
                CHANNEL_SWITCH_DELAY_MS);
    }

    protected void doLoadChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        currentChannelIndex = index;
        channelAdapter.setSelectedPosition(index);
        scrollChannelListTo(index);
        ChannelItem ch = channels.get(index);
        tvCurrentChannel.setText(ch.getName());
        showLoading(true, "正在加载 " + ch.getName() + "...");
        Log.i(BASE_TAG, "doLoadChannel: " + ch.getName() + " → " + ch.getUrl());
        loadUrl(ch.getUrl());     // 子类实现
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
        int ni = currentChannelIndex + offset;
        if (ni < 0) ni = channels.size() - 1;
        else if (ni >= channels.size()) ni = 0;
        currentChannelIndex = ni;
        channelAdapter.setSelectedPosition(ni);
        scrollChannelListTo(ni);
        if (isPanelVisible) {
            tvCurrentChannel.setText(channels.get(ni).getName());
            scheduleAutoHidePanel();
        } else {
            scheduleLoadChannel(ni);
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
        requestWebViewFocus();    // 子类实现
    }

    private void hidePanelIfVisible() {
        if (isPanelVisible) hidePanel();
    }

    private void scheduleAutoHidePanel() {
        uiHandler.removeMessages(MSG_HIDE_PANEL);
        uiHandler.sendEmptyMessageDelayed(MSG_HIDE_PANEL, AUTO_HIDE_PANEL_MS);
    }

    // =========================================================
    // 自动点击播放
    // =========================================================

    protected void scheduleAutoClick() {
        autoClickRetryCount = 0;
        doAutoClick();
    }

    private void doAutoClick() {
        if (autoClickRetryCount >= MAX_AUTO_CLICK_RETRY) return;
        autoClickRetryCount++;
        if (!isPanelVisible) simulateTouchOnCenter();
        if (autoClickRetryCount < MAX_AUTO_CLICK_RETRY) {
            uiHandler.postDelayed(this::doAutoClick, 2000);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void simulateTouchOnCenter() {
        View v = getWebViewForTouch();
        if (v == null) return;
        float cx = v.getWidth()  / 2f;
        float cy = v.getHeight() / 2f;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now,         MotionEvent.ACTION_DOWN, cx, cy, 0);
        MotionEvent up   = MotionEvent.obtain(now, now + 50,    MotionEvent.ACTION_UP,   cx, cy, 0);
        v.dispatchTouchEvent(down);
        v.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    // =========================================================
    // 触屏手势
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    private void initTouchGesture() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_MIN_DISTANCE = 80;
            private static final int SWIPE_MIN_VELOCITY = 100;

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX();
                if (isPanelVisible) {
                    if (x > panelChannel.getWidth()) { hidePanel(); return true; }
                    return false;
                } else {
                    View wv = getWebViewForTouch();
                    if (wv != null && x < wv.getWidth() * TOUCH_LEFT_ZONE_RATIO) {
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
                if (!isPanelVisible
                        && Math.abs(dy) > SWIPE_MIN_DISTANCE
                        && Math.abs(velocityY) > SWIPE_MIN_VELOCITY) {
                    switchChannelByOffset(dy < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });

        // 将手势分发绑定到 WebView（由子类在 initWebEngine 中完成 setOnTouchListener）
    }

    /**
     * 子类在 initWebEngine() 完成 WebView 创建后，
     * 调用此方法将触摸事件路由给手势识别器。
     */
    protected void bindTouchGestureToWebView(View webView) {
        webView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });
    }

    // =========================================================
    // JS 注入（自动播放脚本，三个引擎通用）
    // =========================================================

    /**
     * 注入自动播放脚本：
     * <ol>
     *   <li>强制 video 全屏（object-fit:contain）</li>
     *   <li>隐藏非播放器 DOM 元素</li>
     *   <li>模拟点击播放按钮 + video.play()</li>
     *   <li>MutationObserver 持续监听新 video 元素</li>
     *   <li>兜底轮询（每 1.5 秒，最多 20 秒）</li>
     * </ol>
     */
    protected void injectAutoPlayJs() {
        String css =
                "html,body{margin:0!important;padding:0!important;"
                + "width:100vw!important;height:100vh!important;"
                + "overflow:hidden!important;background:#000!important}"
                // translateZ(0) 强制 GPU 独立图层，修复系统 WebView 视频黑屏
                + "video{position:fixed!important;top:0!important;left:0!important;"
                + "width:100vw!important;height:100vh!important;"
                + "max-width:100vw!important;max-height:100vh!important;"
                + "object-fit:contain!important;"
                + "z-index:999999!important;background:#000!important;"
                + "transform:translateZ(0)!important;margin:0!important;"
                + "-webkit-transform:translateZ(0)!important;}";

        String js = "javascript:(function(){"
                + "if(!document.getElementById('_tvfs')){"
                + "var s=document.createElement('style');s.id='_tvfs';"
                + "s.textContent='" + css + "';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "function hideNonVideo(){"
                + "var v=document.querySelector('video');if(!v)return;"
                + "var top=v;"
                + "while(top.parentElement&&top.parentElement!==document.body){top=top.parentElement;}"
                + "var ch=document.body.children;"
                + "for(var k=0;k<ch.length;k++){if(ch[k]!==top)ch[k].style.cssText='display:none!important';}}"
                // simulateClick：兼容旧版 WebView（不支持 new Touch() 构造函数）
                + "function simulateClick(el){"
                + "if(!el)return;"
                + "var r=el.getBoundingClientRect();"
                + "var cx=r.left+r.width/2,cy=r.top+r.height/2;"
                + "try{"
                // 优先派发 MouseEvent（所有版本均支持）
                + "['mousedown','mouseup','click'].forEach(function(t){"
                + "el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:cx,clientY:cy}));});"
                // TouchEvent：new Touch() 构造函数仅 Chrome 新版支持，做特性检测
                + "if(typeof TouchEvent!=='undefined'&&typeof Touch!=='undefined'){"
                + "var mkTouch=function(t){"
                + "try{return new Touch({identifier:Date.now(),target:el,clientX:cx,clientY:cy});}"
                + "catch(e){return null;}};"
                + "var touch=mkTouch();"
                + "if(touch){['touchstart','touchend'].forEach(function(t){"
                + "try{el.dispatchEvent(new TouchEvent(t,{bubbles:true,cancelable:true,"
                + "touches:[touch],changedTouches:[touch]}));}catch(e){}});}}"
                + "}catch(e){}}"
                + "function tryPlay(){"
                + "var allV=document.querySelectorAll('video');"
                + "for(var m=0;m<allV.length;m++){allV[m].muted=false;allV[m].volume=1.0;}"
                + "var btns=['.play-btn','.vjs-big-play-button','.prism-big-play-btn',"
                + "'.prism-start-btn','.prism-play-btn','.m-player-start',"
                + "'#playBtn','.live-play-btn','.player-play','.icon-play',"
                + "'.btn-play','.J-play','.J_play','.play_button',"
                + "'[class*=play][class*=btn]','[class*=btn][class*=play]',"
                + "'[class*=play][class*=start]','[class*=start][class*=play]'];"
                + "for(var i=0;i<btns.length;i++){"
                + "var el=document.querySelector(btns[i]);"
                + "if(el&&el.offsetParent!==null&&el.offsetWidth>0){simulateClick(el);break;}}"
                + "var vv=document.querySelectorAll('video');"
                + "for(var j=0;j<vv.length;j++){var v=vv[j];if(v.paused){"
                + "v.muted=false;v.volume=1.0;var p=v.play();if(p&&p.catch)p.catch(function(){});}}"
                + "var vEl=document.querySelector('video');"
                + "if(vEl&&vEl.paused)simulateClick(vEl);}"
                + "function handleVideo(){hideNonVideo();tryPlay();}"
                + "handleVideo();"
                + "if(!window._tvObserver){"
                + "window._tvObserver=new MutationObserver(function(muts){"
                + "for(var i=0;i<muts.length;i++){for(var j=0;j<muts[i].addedNodes.length;j++){"
                + "var n=muts[i].addedNodes[j];"
                + "if(n.nodeName==='VIDEO'||(n.querySelector&&n.querySelector('video'))){"
                + "handleVideo();return;}}}});"
                + "window._tvObserver.observe(document.body||document.documentElement,"
                + "{childList:true,subtree:true});}"
                + "var _tvRetry=0;"
                + "function _tvPoll(){if(_tvRetry++>13)return;"
                + "var v=document.querySelector('video');"
                + "if(v){handleVideo();if(v.paused)setTimeout(_tvPoll,1500);"
                + "}else{setTimeout(_tvPoll,1500);}}"
                + "setTimeout(_tvPoll,1500);"
                + "})();";

        evaluateJavaScript(js);   // 子类实现
    }

    // =========================================================
    // 频道列表 RecyclerView
    // =========================================================

    private void initChannelListView() {
        channelAdapter = new ChannelAdapter(channels, (channel, position) -> {
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
                if (isPanelVisible) hidePanel();
                else finish();
                return true;

            case KeyEvent.KEYCODE_MENU:
                openEngineSelect();
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
                if (isPanelVisible) { doLoadChannel(currentChannelIndex); hidePanel(); return true; }
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

    /** 清除引擎偏好设置并返回引擎选择界面 */
    private void openEngineSelect() {
        getSharedPreferences(WebEngineType.PREFS_NAME, MODE_PRIVATE)
                .edit().remove(WebEngineType.PREFS_KEY).apply();
        Intent intent = new Intent(this, EngineSelectActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // =========================================================
    // UI 工具
    // =========================================================

    protected void showLoading(boolean show, @Nullable String hint) {
        if (loadingOverlay == null) return;
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && hint != null && tvLoadingHint != null) tvLoadingHint.setText(hint);
    }
}

