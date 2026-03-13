package com.cctv.tvapp.model;

/**
 * 央视频道数据模型
 *
 * channelId  对应 liveHtml5.do 接口的 channel 参数值（去掉 "pa://" 前缀）
 *            例如 "cctv_p2p_hdcctv1"
 * name       用于 UI 显示的频道名称
 */
public class ChannelItem {

    /** 用于 liveHtml5.do 接口的频道标识（不含 pa:// 前缀） */
    private String channelId;

    /** UI 显示名称 */
    private String name;

    /** 解析成功后的 m3u8 直播流地址（运行时填入，初始为 null） */
    private String streamUrl;

    public ChannelItem(String channelId, String name) {
        this.channelId = channelId;
        this.name = name;
    }

    public String getChannelId() {
        return channelId;
    }

    /** 组装 liveHtml5.do 所需的 channel 参数值 */
    public String getApiChannelParam() {
        return "pa://" + channelId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
    }

    @Override
    public String toString() {
        return "ChannelItem{channelId='" + channelId + "', name='" + name + "'}";
    }
}
