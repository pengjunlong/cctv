package com.cctv.tvapp.model;

/**
 * 频道数据模型
 *
 * url  直播页地址，WebView 直接加载
 *      例如 "https://tv.cctv.com/live/cctv1/"
 * name 用于 UI 显示的频道名称
 */
public class ChannelItem {

    /** 直播页 URL（WebView 直接加载） */
    private final String url;

    /** UI 显示名称 */
    private final String name;

    public ChannelItem(String url, String name) {
        this.url = url;
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "ChannelItem{url='" + url + "', name='" + name + "'}";
    }
}
