package com.cctv.tvapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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
 * <p>用户选择后会自动记住，下次启动时直接进入对应播放器，不再显示此界面。
 * 用户可通过播放器中的 MENU 键重新进入此界面切换引擎。
 *
 * <p>遥控器操作：
 * <ul>
 *   <li>上/下键：在三个选项间移动焦点</li>
 *   <li>确认/OK 键：选择当前焦点选项</li>
 *   <li>数字键 1/2/3：直接选择对应引擎</li>
 *   <li>BACK 键：退出 App</li>
 * </ul>
 */
public class EngineSelectActivity extends Activity {

    private static final String TAG = "EngineSelect";

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

        // 检查是否有记住的选择，有则直接跳转
        WebEngineType saved = getSavedEngine();
        if (saved != null) {
            android.util.Log.i(TAG, "记住的引擎选择: " + saved.name() + "，直接进入播放器");
            launchPlayer(saved);
            finish();
            return;
        }

        setContentView(R.layout.activity_engine_select);
        hideSystemUi();
        initViews();
        updateFocus(0);
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

        // 重置已记住的偏好设置
        View btnReset = findViewById(R.id.btn_reset_prefs);
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> {
                clearSavedEngine();
                updateRememberHint(false);
            });
        }

        // 焦点变化时更新焦点索引
        btnGecko.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) focusIndex = 0; });
        btnCrosswalk.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) focusIndex = 1; });
        btnSystem.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) focusIndex = 2; });

        updateRememberHint(false);
    }

    /** 更新底部「记住选择」提示文字 */
    private void updateRememberHint(boolean hasRemembered) {
        TextView hint = findViewById(R.id.tv_remember_hint);
        if (hint == null) return;
        if (hasRemembered) {
            hint.setText("✓ 已记住选择");
        } else {
            hint.setText("选择后将自动记住（下次直接进入）");
        }
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
        android.util.Log.i(TAG, "用户选择引擎: " + type.name());

        // 显示选中指示器
        indicatorGecko.setVisibility(type == WebEngineType.GECKO ? View.VISIBLE : View.GONE);
        indicatorCrosswalk.setVisibility(type == WebEngineType.CROSSWALK ? View.VISIBLE : View.GONE);
        indicatorSystem.setVisibility(type == WebEngineType.SYSTEM ? View.VISIBLE : View.GONE);

        // 持久化选择
        saveEngine(type);
        updateRememberHint(true);

        // 启动对应播放器
        launchPlayer(type);
        finish();
    }

    /**
     * 根据引擎类型启动对应的播放 Activity
     * @param type 引擎类型
     */
    private void launchPlayer(WebEngineType type) {
        Intent intent;
        switch (type) {
            case CROSSWALK:
                intent = new Intent(this, CrosswalkPlayerActivity.class);
                break;
            case SYSTEM:
                intent = new Intent(this, SystemWebViewPlayerActivity.class);
                break;
            case GECKO:
            default:
                intent = new Intent(this, GeckoPlayerActivity.class);
                break;
        }
        // 传递引擎类型，供播放器显示当前使用的引擎
        intent.putExtra(WebEngineType.EXTRA_KEY, type.name());
        startActivity(intent);
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
        android.util.Log.i(TAG, "已清除引擎选择偏好设置");
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

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                updateFocus(Math.max(0, focusIndex - 1));
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
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

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}

