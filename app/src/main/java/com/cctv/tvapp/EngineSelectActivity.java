package com.cctv.tvapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 播放引擎选择界面
 *
 * <p>App 每次启动时显示此界面，让用户选择使用哪种浏览器引擎播放央视直播：
 * <ul>
 *   <li>{@link WebEngineType#GECKO}      → 启动 {@link GeckoPlayerActivity}（GeckoView 引擎）</li>
 *   <li>{@link WebEngineType#CROSSWALK}  → 启动 {@link CrosswalkPlayerActivity}（XWalkView 引擎）</li>
 *   <li>{@link WebEngineType#SYSTEM}     → 启动 {@link SystemWebViewPlayerActivity}（系统 WebView）</li>
 * </ul>
 *
 * <p><b>版本自动路由规则：</b>
 * <ul>
 *   <li>GeckoView 148 要求 Android 8.0（API 26）及以上</li>
 *   <li>Crosswalk（Chromium 53）仅支持 Android ≤ 7.0（API 25）</li>
 *   <li>用户选 Gecko 但系统低于 Android 8 → 自动使用 Crosswalk</li>
 *   <li>用户选 Crosswalk 但系统高于 Android 7 → 自动使用 Gecko</li>
 * </ul>
 *
 * <p>用户选择后会自动记住，下次启动时直接进入对应播放器，不再显示此界面。
 * 用户可通过播放器中的 MENU 键重新进入此界面切换引擎。
 *
 * <p>遥控器操作：
 * <ul>
 *   <li>左/右键：在三张卡片间移动焦点</li>
 *   <li>确认/OK 键：选择当前焦点选项</li>
 *   <li>数字键 1/2/3：直接选择对应引擎</li>
 * </ul>
 */
public class EngineSelectActivity extends Activity {

    private static final String TAG = "EngineSelect";

    /**
     * GeckoView 148 要求的最低 Android 版本：Android 8.0（API 26）
     */
    private static final int GECKO_MIN_SDK = Build.VERSION_CODES.O;  // API 26

    /**
     * Crosswalk（Chromium 53）支持的最高 Android 版本：Android 7.1（API 25）
     */
    private static final int CROSSWALK_MAX_SDK = Build.VERSION_CODES.N_MR1;  // API 25

    // 三个选项按钮
    private View btnGecko;
    private View btnCrosswalk;
    private View btnSystem;

    // 选中指示器（小圆点）
    private View indicatorGecko;
    private View indicatorCrosswalk;
    private View indicatorSystem;

    // 当前焦点索引（0=Gecko, 1=Crosswalk, 2=System）
    private int focusIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_engine_select);
        hideSystemUi();
        initViews();
        // 根据系统版本将焦点默认在推荐引擎上
        updateFocus(isGeckoSupported() ? 0 : 1);
    }

    /** 隐藏状态栏/导航栏，沉浸式全屏 */
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void initViews() {
        btnGecko        = findViewById(R.id.btn_gecko);
        btnCrosswalk    = findViewById(R.id.btn_crosswalk);
        btnSystem       = findViewById(R.id.btn_system);
        indicatorGecko     = findViewById(R.id.indicator_gecko);
        indicatorCrosswalk = findViewById(R.id.indicator_crosswalk);
        indicatorSystem    = findViewById(R.id.indicator_system);

        // 触屏点击事件
        btnGecko.setOnClickListener(v -> selectEngine(WebEngineType.GECKO));
        btnCrosswalk.setOnClickListener(v -> selectEngine(WebEngineType.CROSSWALK));
        btnSystem.setOnClickListener(v -> selectEngine(WebEngineType.SYSTEM));

        // 焦点变化时更新焦点索引
        btnGecko.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) focusIndex = 0; });
        btnCrosswalk.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) focusIndex = 1; });
        btnSystem.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) focusIndex = 2; });

        // 根据系统版本更新按钮副标题，提示兼容性
        updateCompatHints();
        updateRememberHint(false);
    }

    /**
     * 根据当前系统版本，更新 Gecko 和 Crosswalk 按钮的副标题文字，
     * 清晰告知用户哪个引擎在本设备上原生支持、哪个会自动兜底切换。
     */
    private void updateCompatHints() {
        // Gecko 副标题：仅在不支持时显示警告
        TextView tvGeckoSub = findViewById(R.id.tv_gecko_subtitle);
        if (tvGeckoSub != null) {
            if (!isGeckoSupported()) {
                tvGeckoSub.setText("⚠ Android " + Build.VERSION.RELEASE
                        + " < 8.0，将自动使用 Crosswalk");
                tvGeckoSub.setVisibility(View.VISIBLE);
            } else {
                tvGeckoSub.setVisibility(View.GONE);
            }
        }

        // Crosswalk 副标题：仅在不支持时显示警告
        TextView tvXwalkSub = findViewById(R.id.tv_crosswalk_subtitle);
        if (tvXwalkSub != null) {
            if (!isCrosswalkSupported()) {
                tvXwalkSub.setText("⚠ Android " + Build.VERSION.RELEASE
                        + " ≥ 8.0，将自动使用 GeckoView");
                tvXwalkSub.setVisibility(View.VISIBLE);
            } else {
                tvXwalkSub.setVisibility(View.GONE);
            }
        }
    }

    /** 更新底部提示文字 */
    private void updateRememberHint(boolean hasRemembered) {
        TextView hint = findViewById(R.id.tv_remember_hint);
        if (hint == null) return;
        hint.setText("每次启动都会显示此界面");
    }

    /**
     * 更新焦点到指定索引
     * @param index 0=Gecko, 1=Crosswalk, 2=System
     */
    private void updateFocus(int index) {
        focusIndex = index;
        switch (index) {
            case 0: btnGecko.requestFocus();     break;
            case 1: btnCrosswalk.requestFocus(); break;
            case 2: btnSystem.requestFocus();    break;
        }
    }

    /**
     * 选择引擎并记住，启动对应播放器
     * @param type 引擎类型
     */
    private void selectEngine(WebEngineType type) {
        Log.i(TAG, "用户选择引擎: " + type.name());

        // 显示选中指示器
        indicatorGecko.setVisibility(type == WebEngineType.GECKO ? View.VISIBLE : View.GONE);
        indicatorCrosswalk.setVisibility(type == WebEngineType.CROSSWALK ? View.VISIBLE : View.GONE);
        indicatorSystem.setVisibility(type == WebEngineType.SYSTEM ? View.VISIBLE : View.GONE);

        // 启动对应播放器（含版本自动路由）
        launchPlayer(type);
        finish();
    }

    /**
     * 根据引擎类型启动对应的播放 Activity。
     *
     * <p><b>版本自动路由：</b>
     * <ul>
     *   <li>选 {@link WebEngineType#GECKO}：若系统低于 Android 8（API 26），
     *       GeckoView 148 不支持，自动路由到 {@link CrosswalkPlayerActivity}</li>
     *   <li>选 {@link WebEngineType#CROSSWALK}：若系统高于 Android 7（API 25），
     *       Crosswalk 已停维护不稳定，自动路由到 {@link GeckoPlayerActivity}</li>
     * </ul>
     *
     * @param type 用户选择的引擎类型
     */
    private void launchPlayer(WebEngineType type) {
        Intent intent;
        switch (type) {
            case GECKO:
                if (!isGeckoSupported()) {
                    // Android < 8：GeckoView 148 不支持此系统，自动降级到 Crosswalk
                    Log.w(TAG, "Android " + Build.VERSION.RELEASE
                            + " < 8.0，GeckoView 148 不支持，自动切换到 Crosswalk");
                    intent = new Intent(this, CrosswalkPlayerActivity.class);
                    intent.putExtra(WebEngineType.EXTRA_KEY, WebEngineType.CROSSWALK.name());
                    intent.putExtra(WebEngineType.EXTRA_AUTO_ROUTED, true);
                } else {
                    intent = new Intent(this, GeckoPlayerActivity.class);
                    intent.putExtra(WebEngineType.EXTRA_KEY, type.name());
                }
                break;

            case CROSSWALK:
                if (!isCrosswalkSupported()) {
                    // Android >= 8：Crosswalk 已停维护，对新系统支持差，自动升级到 Gecko
                    Log.w(TAG, "Android " + Build.VERSION.RELEASE
                            + " >= 8.0，Crosswalk 不适用，自动切换到 GeckoView");
                    intent = new Intent(this, GeckoPlayerActivity.class);
                    intent.putExtra(WebEngineType.EXTRA_KEY, WebEngineType.GECKO.name());
                    intent.putExtra(WebEngineType.EXTRA_AUTO_ROUTED, true);
                } else {
                    intent = new Intent(this, CrosswalkPlayerActivity.class);
                    intent.putExtra(WebEngineType.EXTRA_KEY, type.name());
                }
                break;

            case SYSTEM:
            default:
                intent = new Intent(this, SystemWebViewPlayerActivity.class);
                intent.putExtra(WebEngineType.EXTRA_KEY, type.name());
                break;
        }
        startActivity(intent);
    }

    // =========================================================
    // 版本兼容判断
    // =========================================================

    /** GeckoView 148 是否支持当前系统（需要 Android 8.0 / API 26+） */
    private static boolean isGeckoSupported() {
        return Build.VERSION.SDK_INT >= GECKO_MIN_SDK;
    }

    /** Crosswalk 是否适用于当前系统（Android 7.1 / API 25 及以下） */
    private static boolean isCrosswalkSupported() {
        return Build.VERSION.SDK_INT <= CROSSWALK_MAX_SDK;
    }

    // =========================================================
    // SharedPreferences 持久化
    // =========================================================

    /** 保存引擎选择到 SharedPreferences */
    private void saveEngine(WebEngineType type) {
        getSharedPreferences(WebEngineType.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(WebEngineType.PREFS_KEY, type.name())
                .apply();
    }

    /** 清除已保存的引擎选择 */
    private void clearSavedEngine() {
        getSharedPreferences(WebEngineType.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(WebEngineType.PREFS_KEY)
                .apply();
        Log.i(TAG, "已清除引擎选择偏好设置");
    }

    /**
     * 读取已保存的引擎选择
     * @return 已保存的引擎类型，若无记录则返回 null
     */
    private WebEngineType getSavedEngine() {
        SharedPreferences prefs = getSharedPreferences(WebEngineType.PREFS_NAME, MODE_PRIVATE);
        String name = prefs.getString(WebEngineType.PREFS_KEY, null);
        if (name == null) return null;
        try {
            return WebEngineType.valueOf(name);
        } catch (IllegalArgumentException e) {
            // 枚举值可能已不存在（版本升级），清除无效记录
            clearSavedEngine();
            return null;
        }
    }

    // =========================================================
    // 遥控器按键处理
    // =========================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                updateFocus(Math.max(0, focusIndex - 1));
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                updateFocus(Math.min(2, focusIndex + 1));
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 选中当前焦点选项
                switch (focusIndex) {
                    case 0: selectEngine(WebEngineType.GECKO);     break;
                    case 1: selectEngine(WebEngineType.CROSSWALK); break;
                    case 2: selectEngine(WebEngineType.SYSTEM);    break;
                }
                return true;

            // 数字键快速选择
            case KeyEvent.KEYCODE_1:
                selectEngine(WebEngineType.GECKO);
                return true;
            case KeyEvent.KEYCODE_2:
                selectEngine(WebEngineType.CROSSWALK);
                return true;
            case KeyEvent.KEYCODE_3:
                selectEngine(WebEngineType.SYSTEM);
                return true;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}

