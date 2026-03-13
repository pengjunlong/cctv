package com.cctv.tvapp.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

/**
 * ExoPlayer 封装管理器
 *
 * 职责：
 * 1. 创建并持有 ExoPlayer 实例
 * 2. 使用 OkHttp 作为数据源（统一 Headers 管理，如 Referer、UA）
 * 3. 播放 HLS 直播流（支持自适应码率）
 * 4. 细化播放错误类型，便于上层决定是否重试
 * 5. 生命周期管理（onResume/onPause/onDestroy）
 */
@UnstableApi
public class TvPlayerManager {

    private static final String TAG = "TvPlayerManager";

    /** 央视直播页 Referer（防盗链） */
    private static final String REFERER = "https://tv.cctv.com/";

    /** 桌面 UA（与 StreamParser 一致） */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 播放错误类型（便于上层决定重试策略）
     */
    public enum ErrorType {
        /** 网络连接问题（可重试） */
        NETWORK,
        /** 流格式不支持（不可重试） */
        FORMAT_UNSUPPORTED,
        /** 流地址无效（需重新解析） */
        INVALID_URL,
        /** 其他未知错误 */
        UNKNOWN
    }

    public interface PlayerEventListener {
        void onPlaybackStarted();
        /** @param errorType 错误类型，便于上层决定重试策略 */
        void onPlaybackError(String errorMsg, ErrorType errorType);
        void onBuffering(boolean isBuffering);
    }

    private final ExoPlayer exoPlayer;
    private final OkHttpClient okHttpClient;
    private PlayerEventListener eventListener;
    private String currentUrl;

    public TvPlayerManager(Context context, OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;

        // 构建 ExoPlayer（使用 Application Context 避免泄漏）
        exoPlayer = new ExoPlayer.Builder(context.getApplicationContext()).build();

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
                Log.e(TAG, "播放错误 [" + error.getErrorCodeName() + "]: " + error.getMessage(), error);
                if (eventListener != null) {
                    ErrorType errorType = classifyError(error);
                    String userMsg = buildUserFriendlyMessage(error, errorType);
                    eventListener.onPlaybackError(userMsg, errorType);
                }
            }
        });
    }

    /**
     * 绑定 PlayerView（UI 控件）
     */
    public void attachPlayerView(PlayerView playerView) {
        playerView.setPlayer(exoPlayer);
        // TV 遥控器模式：隐藏播放控制条
        playerView.setUseController(false);
    }

    /**
     * 播放指定 m3u8 直播流
     *
     * @param m3u8Url HLS 直播流地址
     */
    @UnstableApi
    public void playStream(String m3u8Url) {
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            Log.e(TAG, "无效的流地址");
            if (eventListener != null) {
                eventListener.onPlaybackError("无效的直播流地址", ErrorType.INVALID_URL);
            }
            return;
        }

        Log.d(TAG, "开始播放: " + m3u8Url);
        currentUrl = m3u8Url;

        // 使用 OkHttp 数据源，携带必要的 Headers（Referer、UA）
        OkHttpDataSource.Factory dataSourceFactory =
                new OkHttpDataSource.Factory(okHttpClient)
                        .setDefaultRequestProperties(buildRequestHeaders());

        // 统一使用 HlsMediaSource（央视直播流均为 HLS 格式）
        HlsMediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(m3u8Url)));

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    /**
     * 停止当前播放，清除媒体源
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
        exoPlayer.setPlayWhenReady(true);
    }

    /** 暂停播放 */
    public void pause() {
        exoPlayer.setPlayWhenReady(false);
    }

    /** 释放播放器（onDestroy 时调用） */
    public void release() {
        exoPlayer.release();
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
        return exoPlayer.isPlaying();
    }

    // ==================== 私有方法 ====================

    /** 构建请求 Headers */
    private java.util.Map<String, String> buildRequestHeaders() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", REFERER);
        headers.put("Origin", "https://tv.cctv.com");
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }

    /**
     * 根据 ExoPlayer 错误码分类错误类型
     *
     * 完整错误码列表：
     * https://developer.android.com/reference/androidx/media3/common/PlaybackException
     */
    private ErrorType classifyError(PlaybackException error) {
        int errorCode = error.errorCode;
        if (errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                && errorCode <= PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            return ErrorType.NETWORK;
        }
        if (errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                && errorCode <= PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return ErrorType.FORMAT_UNSUPPORTED;
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return ErrorType.INVALID_URL;
        }
        return ErrorType.UNKNOWN;
    }

    /** 生成用户友好的错误提示 */
    private String buildUserFriendlyMessage(PlaybackException error, ErrorType errorType) {
        switch (errorType) {
            case NETWORK:
                return "网络连接失败，正在重试…";
            case FORMAT_UNSUPPORTED:
                return "视频格式不支持：" + error.getErrorCodeName();
            case INVALID_URL:
                return "直播流地址失效，请稍后重试";
            default:
                return "播放异常：" + error.getErrorCodeName();
        }
    }
}

