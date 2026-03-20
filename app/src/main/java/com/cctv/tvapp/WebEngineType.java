package com.cctv.tvapp;

/**
 * 播放引擎类型枚举
 *
 * <ul>
 *   <li>{@link #GECKO}   — Mozilla GeckoView（Firefox 引擎），打包进 APK，不依赖系统 WebView，兼容性最强</li>
 *   <li>{@link #CROSSWALK} — Crosswalk XWalkView（Chromium 内核），独立于系统 WebView，支持 Android ≤ 7.0</li>
 *   <li>{@link #SYSTEM}  — 系统 WebView（Android 原生），体积最小，兼容性取决于系统版本</li>
 * </ul>
 *
 * <p>用户在 {@link EngineSelectActivity} 中选择引擎后，通过 Intent 携带此枚举的 name() 字符串，
 * 再由对应的 Activity 读取并使用。选择结果同时持久化到 SharedPreferences，下次启动时记住上次选择。
 */
public enum WebEngineType {

    /**
     * Mozilla GeckoView（Firefox 引擎）
     * <ul>
     *   <li>优势：不依赖系统 WebView，内置最新 Firefox 引擎，对 HLS/视频兼容性最好</li>
     *   <li>劣势：AAR 体积较大（~50MB），首次冷启动稍慢</li>
     * </ul>
     */
    GECKO("GeckoView（Firefox 引擎）", "推荐 · Android 8+ · 最佳兼容性"),

    /**
     * Crosswalk XWalkView（Chromium 内核）
     * <ul>
     *   <li>优势：独立于系统 WebView，提供固定版本的 Chromium 引擎</li>
     *   <li>劣势：项目已于 2019 年停止维护，仅支持 Android ≤ 7.0</li>
     *   <li>在 Android 8+ 上会自动路由到 GeckoView</li>
     * </ul>
     */
    CROSSWALK("Crosswalk（Chromium 引擎）", "推荐 · Android 7 及以下 · Chromium 53"),

    /**
     * 系统 WebView
     * <ul>
     *   <li>优势：无额外体积，启动最快</li>
     *   <li>劣势：依赖系统 WebView 版本，旧版本可能无法播放</li>
     * </ul>
     */
    SYSTEM("系统 WebView", "轻量 · 依赖系统版本");

    /** 显示名称（用于界面展示） */
    public final String displayName;

    /** 副标题/描述（用于界面展示） */
    public final String description;

    WebEngineType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /** Intent / SharedPreferences 的 Key */
    public static final String EXTRA_KEY = "web_engine_type";

    /**
     * Intent Extra Key：标记本次启动是由版本自动路由触发（非用户直接选择）
     * <p>播放器收到此 Extra 时可显示提示：如"已自动切换为 GeckoView（原选 Crosswalk）"
     */
    public static final String EXTRA_AUTO_ROUTED = "web_engine_auto_routed";

    /** SharedPreferences 文件名 */
    public static final String PREFS_NAME = "engine_prefs";

    /** SharedPreferences 存储的 Key */
    public static final String PREFS_KEY = "selected_engine";
}

