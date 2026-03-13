package com.cctv.tvapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cctv.tvapp.adapter.ChannelAdapter;
import com.cctv.tvapp.model.ChannelItem;
import com.cctv.tvapp.player.StreamParser;
import com.cctv.tvapp.player.TvPlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 主界面（ExoPlayer 版）
 *
 * 布局结构：
 * ┌───────────────────────────────────────────────┐
 * │  频道列表(左侧)  │  ExoPlayer播放区(右侧)   │
 * │  RecyclerView   │  PlayerView              │
 * │  (通过上下键    │  (直接播放 HLS m3u8)     │
 * │   切换频道)     │                           │
 * └───────────────────────────────────────────────┘
 *
 * 核心流程：
 * 1. 用户选择频道 → StreamParser 调用 liveHtml5.do 解析 m3u8
 * 2. 解析成功 → TvPlayerManager 播放 m3u8 流
 * 3. 频道切换：停止当前播放 → 解析新频道 → 播放新流
 *
 * 遥控器交互：
 * - 上/下键：切换频道列表焦点
 * - 左键：焦点移到频道列表
 * - 右键/确认键：焦点移到播放器区域
 * - Back键：退出应用
 * - 数字键0-9：快速跳转频道
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    // ---- 央视频道列表 ----
    private static final List<ChannelItem> CHANNELS = new ArrayList<>();
    static {
        // channelId 对应 liveHtml5.do 的 channel=pa://{channelId}
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv1", "CCTV-1 综合"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv2", "CCTV-2 财经"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv3", "CCTV-3 综艺"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv4", "CCTV-4 中文国际"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv5", "CCTV-5 体育"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv5plus", "CCTV-5+ 体育赛事"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv6", "CCTV-6 电影"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv7", "CCTV-7 国防军事"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv8", "CCTV-8 电视剧"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctvjilu", "CCTV-9 纪录"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv10", "CCTV-10 科教"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv11", "CCTV-11 戏曲"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv12", "CCTV-12 社会与法"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv13", "CCTV-13 新闻"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv14", "CCTV-14 少儿"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv15", "CCTV-15 音乐"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv16", "CCTV-16 奥林匹克"));
        CHANNELS.add(new ChannelItem("cctv_p2p_hdcctv17", "CCTV-17 农业农村"));
    }

    // ---- Views ----
    private PlayerView playerView;
    private RecyclerView rvChannelList;
    private View loadingOverlay;
    private TextView tvCurrentChannel;

    // ---- 逻辑 ----
    private TvPlayerManager playerManager;
    private StreamParser streamParser;
    private OkHttpClient okHttpClient;

    private ChannelAdapter channelAdapter;
    private int currentChannelIndex = 0;

    /** 频道切换防抖：500ms 内多次上下键只解析最后一次 */
    private final Handler channelSwitchHandler = new Handler(Looper.getMainLooper());
    private Runnable channelSwitchRunnable;
    private static final long CHANNEL_SWITCH_DELAY_MS = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        initViews();
        initOkHttpClient();
        initStreamParser();
        initPlayerManager();
        initChannelList();

        // 默认加载第一个频道
        loadChannel(currentChannelIndex);
    }

    private void initViews() {
        playerView = findViewById(R.id.player_view);
        rvChannelList = findViewById(R.id.rv_channel_list);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvCurrentChannel = findViewById(R.id.tv_current_channel);
    }

    private void initOkHttpClient() {
        // 配置 OkHttp：连接超时、读超时
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private void initStreamParser() {
        streamParser = new StreamParser(okHttpClient);
    }

    private void initPlayerManager() {
        playerManager = new TvPlayerManager(this, okHttpClient);
        playerManager.attachPlayerView(playerView);

        // 监听播放事件
        playerManager.setEventListener(new TvPlayerManager.PlayerEventListener() {
            @Override
            public void onPlaybackStarted() {
                showLoading(false);
                Toast.makeText(MainActivity.this, "播放开始", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPlaybackError(String errorMsg) {
                showLoading(false);
                Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onBuffering(boolean isBuffering) {
                showLoading(isBuffering);
            }
        });
    }

    private void initChannelList() {
        channelAdapter = new ChannelAdapter(CHANNELS, (channel, position) -> {
            currentChannelIndex = position;
            scheduleLoadChannel(position);
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        rvChannelList.setLayoutManager(layoutManager);
        rvChannelList.setAdapter(channelAdapter);

        // 让 RecyclerView 默认获取焦点
        rvChannelList.requestFocus();
    }

    // ===================== 频道切换 =====================

    /**
     * 防抖加载频道：快速连续上下键时，只加载最终停留的频道
     */
    private void scheduleLoadChannel(int index) {
        if (channelSwitchRunnable != null) {
            channelSwitchHandler.removeCallbacks(channelSwitchRunnable);
        }
        channelSwitchRunnable = () -> loadChannel(index);
        channelSwitchHandler.postDelayed(channelSwitchRunnable, CHANNEL_SWITCH_DELAY_MS);
    }

    /**
     * 加载指定频道：解析 m3u8 → 播放
     */
    private void loadChannel(int index) {
        if (index < 0 || index >= CHANNELS.size()) return;

        currentChannelIndex = index;
        channelAdapter.setSelectedPosition(index);
        scrollChannelListTo(index);

        ChannelItem channel = CHANNELS.get(index);
        tvCurrentChannel.setText(channel.getName());

        // 如果已缓存 m3u8 URL，直接播放
        if (channel.getStreamUrl() != null) {
            playerManager.switchStream(channel.getStreamUrl());
            return;
        }

        // 否则解析 m3u8
        showLoading(true);
        String refererKey = extractRefererKey(channel.getChannelId());
        streamParser.parseChannel(channel.getChannelId(), refererKey,
                new StreamParser.ParseCallback() {
                    @Override
                    public void onSuccess(String streamUrl, boolean isAudioOnly) {
                        showLoading(false);
                        // 缓存解析结果（音频流不缓存，避免误以为有视频）
                        if (!isAudioOnly) {
                            channel.setStreamUrl(streamUrl);
                        }
                        playerManager.switchStream(streamUrl);
                        if (isAudioOnly) {
                            Toast.makeText(MainActivity.this,
                                    channel.getName() + " 当前仅支持音频播放（无画面）",
                                    Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailed(String reason) {
                        showLoading(false);
                        Toast.makeText(MainActivity.this,
                                "解析失败：" + reason, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * 从 channelId 提取用于 Referer 的频道关键字
     * 例如：cctv_p2p_hdcctv1 → cctv1
     */
    private String extractRefererKey(String channelId) {
        // cctv_p2p_hdcctv{N} → cctv{N}
        if (channelId.startsWith("cctv_p2p_hd")) {
            return channelId.substring("cctv_p2p_hd".length());
        }
        return "cctv1"; // 默认
    }

    // ===================== 遥控器按键处理 =====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // 返回键：退出应用
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;

            // 上键：切换到上一个频道
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!rvChannelList.hasFocus()) {
                    rvChannelList.requestFocus();
                }
                switchChannelByOffset(-1);
                return true;

            // 下键：切换到下一个频道
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!rvChannelList.hasFocus()) {
                    rvChannelList.requestFocus();
                }
                switchChannelByOffset(1);
                return true;

            // 左键：将焦点移到频道列表
            case KeyEvent.KEYCODE_DPAD_LEFT:
                rvChannelList.requestFocus();
                return true;

            // 右键/确认：焦点移到播放器区域
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (rvChannelList.hasFocus()) {
                    // 当前焦点在频道列表：确认键切换频道
                    loadChannel(channelAdapter.getSelectedPosition());
                    playerView.requestFocus();
                    return true;
                }
                break;

            // 数字键0-9：快速切换频道
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int numIndex = keyCode - KeyEvent.KEYCODE_0;
                if (numIndex < CHANNELS.size()) {
                    loadChannel(numIndex);
                    scrollChannelListTo(numIndex);
                }
                return true;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 通过偏移量切换频道（+1下一个，-1上一个）
     */
    private void switchChannelByOffset(int offset) {
        int newIndex = currentChannelIndex + offset;
        // 循环切换
        if (newIndex < 0) {
            newIndex = CHANNELS.size() - 1;
        } else if (newIndex >= CHANNELS.size()) {
            newIndex = 0;
        }
        currentChannelIndex = newIndex;
        channelAdapter.setSelectedPosition(newIndex);
        scrollChannelListTo(newIndex);
        scheduleLoadChannel(newIndex);
    }

    /**
     * 滚动频道列表到指定位置
     */
    private void scrollChannelListTo(int index) {
        rvChannelList.smoothScrollToPosition(index);
        // 让对应条目获取焦点
        RecyclerView.ViewHolder vh = rvChannelList.findViewHolderForAdapterPosition(index);
        if (vh != null) {
            vh.itemView.requestFocus();
        }
    }

    // ===================== 加载状态 =====================

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // ===================== 生命周期 =====================

    @Override
    protected void onResume() {
        super.onResume();
        if (playerManager != null) {
            playerManager.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (playerManager != null) {
            playerManager.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        channelSwitchHandler.removeCallbacksAndMessages(null);
        if (playerManager != null) {
            playerManager.release();
        }
    }
}
