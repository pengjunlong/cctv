package com.cctv.tvapp.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

/**
 * ExoPlayer 封装管理器
 *
 * 职责：
 * 1. 创建并持有 ExoPlayer 实例
 * 2. 使用 OkHttp 作为数据源（统一 Headers 管理，如 Referer）
 * 3. 播放 HLS 直播流
 * 4. 处理播放错误，提供重试机制
 * 5. 生命周期管理（onResume/onPause/onDestroy）
 */
public class TvPlayerManager {

    private static final String TAG = "TvPlayerManager";

    /** 央视直播页 Referer（防盗链） */
    private static final String REFERER = "https://tv.cctv.com/";

    /** 桌面 UA */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public interface PlayerEventListener {
        void onPlaybackStarted();
        void onPlaybackError(String errorMsg);
        void onBuffering(boolean isBuffering);
    }

    private final ExoPlayer exoPlayer;
    private final OkHttpClient okHttpClient;
    private PlayerEventListener eventListener;
    private String currentUrl;

    public TvPlayerManager(Context context, OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;

        // 构建 ExoPlayer
        exoPlayer = new ExoPlayer.Builder(context).build();

        // 注册播放器事件监听
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        if (eventListener != null) eventListener.onBuffering(true);
                        break;
                    case Player.STATE_READY:
                        if (eventListener != null) {
                            eventListener.onBuffering(false);
                            eventListener.onPlaybackStarted();
                        }
                        break;
                    case Player.STATE_IDLE:
                    case Player.STATE_ENDED:
                        if (eventListener != null) eventListener.onBuffering(false);
                        break;
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage(), error);
                if (eventListener != null) {
                    eventListener.onPlaybackError(
                            "播放失败: " + (error.getMessage() != null
                                    ? error.getMessage() : error.getErrorCodeName()));
                }
            }
        });
    }

    /**
     * 绑定 PlayerView（UI 控件）
     */
    public void attachPlayerView(PlayerView playerView) {
        playerView.setPlayer(exoPlayer);
        // TV 遥控器模式：隐藏播放控制条（用遥控器直接操作）
        playerView.setUseController(false);
    }

    /**
     * 播放指定 m3u8 直播流
     *
     * @param m3u8Url HLS 直播流地址
     */
    public void playStream(String m3u8Url) {
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            Log.e(TAG, "无效的流地址");
            if (eventListener != null) {
                eventListener.onPlaybackError("无效的直播流地址");
            }
            return;
        }

        Log.d(TAG, "开始播放: " + m3u8Url);
        currentUrl = m3u8Url;

        // 使用 OkHttp 数据源，携带必要的 Headers（Referer、UA）
        OkHttpDataSource.Factory okHttpDataSourceFactory =
                new OkHttpDataSource.Factory(okHttpClient)
                        .setDefaultRequestProperties(buildRequestHeaders());

        // 统一使用 HlsMediaSource（m3u8 直播流）
        // 央视的直播流均为 HLS 格式，ExoPlayer 会自动处理自适应码率切换
        MediaSource mediaSource = new HlsMediaSource.Factory(okHttpDataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(m3u8Url)));

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    /**
     * 停止当前播放并释放资源准备播放新流
     */
    public void stopAndReset() {
        exoPlayer.stop();
        exoPlayer.clearMediaItems();
    }

    /**
     * 切换到新流（停止当前 → 播放新的）
     */
    public void switchStream(String newUrl) {
        stopAndReset();
        playStream(newUrl);
    }

    /** 恢复播放 */
    public void resume() {
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(true);
        }
    }

    /** 暂停播放 */
    public void pause() {
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);
        }
    }

    /** 释放播放器（onDestroy 时调用） */
    public void release() {
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }

    public void setEventListener(PlayerEventListener listener) {
        this.eventListener = listener;
    }

    public ExoPlayer getExoPlayer() {
        return exoPlayer;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public boolean isPlaying() {
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    /** 构建请求 Headers（Referer + User-Agent） */
    private java.util.Map<String, String> buildRequestHeaders() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", REFERER);
        headers.put("Origin", "https://tv.cctv.com");
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }

    /** 判断是否是 HLS 流 */
    private boolean isHlsStream(String url) {
        return url.contains("kcdnvip.com")
                || url.contains("cntv.cn")
                || url.contains("myalicdn.com")
                || url.contains("liveali")
                || url.contains("livetxcloud")
                || url.contains("chinanet");
    }
}

