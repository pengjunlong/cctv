package com.cctv.tvapp.player;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 央视直播流地址解析器（完整两步解析版）
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  解析流程                                                        │
 * │                                                                  │
 * │  Step 1: liveHtml5.do                                           │
 * │    → 获取 client_sid、hls_cdn_info（CDN 类型）                   │
 * │    → 若 hls_url 中有直接可用 URL 则跳到播放                      │
 * │                                                                  │
 * │  Step 2: vdnxbk.live.cntv.cn/api/v3/vdn/live                  │
 * │    → 携带 auth-key Header（MD5 签名认证）                        │
 * │    → 响应可能是明文 JSON 或 AES-256-CBC 加密 Base64              │
 * │    → 解密后得到真实 CDN m3u8 地址                               │
 * │                                                                  │
 * │  Fallback:                                                       │
 * │    → 备用 CDN 模板 URL（金山云/阿里云）                         │
 * │    → hls6 音频流（最终保底，isAudioOnly=true）                  │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 逆向来源：https://js.player.cntv.cn/creator/liveplayer.js
 */
public class StreamParser {

    private static final String TAG = "StreamParser";

    // ==================== API 端点 ====================

    /** Step1：获取频道基础信息（含 client_sid） */
    private static final String API_LIVE_HTML5 =
            "https://vdn.live.cntv.cn/api2/liveHtml5.do";

    /** Step2：获取加密后的真实流地址 */
    private static final String API_VDNXBK =
            "https://vdnxbk.live.cntv.cn/api/v3/vdn/live";

    // ==================== 签名算法常量 ====================
    // 来源：liveplayer.js 逆向分析

    /**
     * PC 端签名盐值（setH5Str salt for PC/HTML5）
     * 用于生成 auth-key Header
     */
    private static final String SIGN_SALT_PC = "7G17927AY79W7A979H79W7G179P7AA7AG";

    /**
     * 移动端签名盐值（Mobile client）
     */
    private static final String SIGN_SALT_MOBILE = "47899B86370B879139C08EA3B5E88267";

    // ==================== AES 解密常量 ====================
    // 来源：liveplayer.js CryptoJS.AES.decrypt 调用处

    /**
     * AES-256-CBC 解密密钥（PC 端，32 字节）
     * Base64 原文：0hdiziKsev1LRe24oGTMPwfg9f+kcCWQ56sxi+jMAKE=
     */
    private static final byte[] AES_KEY = hexToBytes(
            "d21762ce22ac7afd4b45edb8a064cc3f07e0f5ffa4702590e7ab318be8cc00a1");

    /**
     * AES-256-CBC IV（16 字节）
     * Base64 原文：I6JulGGNroT8GVjO56ss6A==
     */
    private static final byte[] AES_IV = hexToBytes(
            "23a26e94618dae84fc1958cee7ab2ce8");

    // ==================== 其他常量 ====================

    private static final String REFERER_BASE = "https://tv.cctv.com/live/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 备用金山云 CDN 模板（720p） */
    private static final String FALLBACK_KS =
            "http://ldncctvwbcdks.v.kcdnvip.com/ldncctvwbcd/cdrmldcctv%s_1/index.m3u8?BR=td";

    /** 备用阿里云 CDN 模板（720p） */
    private static final String FALLBACK_ALI =
            "http://ldncctvwbcdali.v.myalicdn.com/ldncctvwbcdali/cdrmldcctv%s_1/index.m3u8?BR=td";

    // ==================== 回调接口 ====================

    public interface ParseCallback {
        /**
         * 解析成功
         *
         * @param streamUrl   可播放的 m3u8/flv 地址
         * @param isAudioOnly 是否仅为音频流（无视频画面）
         */
        void onSuccess(String streamUrl, boolean isAudioOnly);

        /** 所有 fallback 均失败 */
        void onFailed(String reason);
    }

    // ==================== 成员 ====================

    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    public StreamParser(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ==================== 公开入口 ====================

    /**
     * 异步解析指定频道的直播流地址（两步解析）
     *
     * @param channelId  频道 ID，如 "cctv_p2p_hdcctv6"
     * @param refererKey Referer 中的频道关键字，如 "cctv6"
     * @param callback   结果回调（主线程执行）
     */
    public void parseChannel(String channelId, String refererKey, ParseCallback callback) {
        Log.d(TAG, "[Step1] 开始解析频道: " + channelId);
        fetchLiveHtml5(channelId, refererKey, callback);
    }

    // ==================== Step 1：liveHtml5.do ====================

    private void fetchLiveHtml5(String channelId, String refererKey, ParseCallback callback) {
        String url = API_LIVE_HTML5
                + "?channel=pa://" + channelId
                + "&client=html5"
                + "&timed=" + System.currentTimeMillis();

        Request request = buildRequest(url, REFERER_BASE + refererKey + "/");

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[Step1] 网络失败: " + e.getMessage());
                tryFallback(channelId, "Step1 网络失败: " + e.getMessage(), callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP " + response.code());
                    }
                    String body = response.body().string();
                    Log.d(TAG, "[Step1] 响应: " + body.substring(0, Math.min(500, body.length())));

                    JsonObject root = parseJsonpToObject(body);
                    if (root == null) {
                        tryFallback(channelId, "Step1 JSONP 解析失败", callback);
                        return;
                    }

                    // 检查播放状态
                    if (isPlayBlocked(root)) {
                        String tip = root.has("tip_msg") ? root.get("tip_msg").getAsString() : "版权限制";
                        Log.w(TAG, "[Step1] 频道不可播放: " + tip);
                        // 仍然尝试 Step2，某些情况下 vdnxbk 可以绕过限制
                    }

                    // 先尝试直接从 hls_url/flv_url 中取（可能某些频道能直接返回）
                    String directUrl = extractDirectVideoUrl(root);
                    if (directUrl != null) {
                        Log.d(TAG, "[Step1] 直接获取到流地址: " + directUrl);
                        mainHandler.post(() -> callback.onSuccess(directUrl, false));
                        return;
                    }

                    // 进入 Step2：用 vdnxbk 获取真实流地址
                    String hlsCdnCode = "";
                    if (root.has("hls_cdn_info")) {
                        JsonObject cdnInfo = root.getAsJsonObject("hls_cdn_info");
                        if (cdnInfo.has("cdn_code")) {
                            hlsCdnCode = cdnInfo.get("cdn_code").getAsString();
                        }
                    }
                    Log.d(TAG, "[Step1] hls_cdn_code=" + hlsCdnCode + "，进入 Step2");
                    fetchVdnxbk(channelId, refererKey, callback);

                } catch (Exception e) {
                    Log.e(TAG, "[Step1] 异常: " + e.getMessage(), e);
                    tryFallback(channelId, "Step1 异常: " + e.getMessage(), callback);
                } finally {
                    response.close();
                }
            }
        });
    }

    // ==================== Step 2：vdnxbk + auth-key ====================

    private void fetchVdnxbk(String channelId, String refererKey, ParseCallback callback) {
        // 构造 auth-key 签名
        long timestamp = System.currentTimeMillis() / 1000L;  // 秒级时间戳
        int rand = 100 + random.nextInt(900);                  // 100-999 随机数
        String authKey = buildAuthKey(channelId, timestamp, rand, false);

        String url = API_VDNXBK
                + "?channel=pa://" + channelId
                + "&client=html5"
                + "&timed=" + (timestamp * 1000L);

        Log.d(TAG, "[Step2] vdnxbk URL: " + url);
        Log.d(TAG, "[Step2] auth-key: " + authKey);

        Request request = new Request.Builder()
                .url(url)
                .header("Referer", REFERER_BASE + refererKey + "/")
                .header("Origin", "https://tv.cctv.com")
                .header("User-Agent", USER_AGENT)
                .header("auth-key", authKey)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[Step2] 网络失败: " + e.getMessage());
                tryFallback(channelId, "Step2 网络失败", callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body().string();
                    Log.d(TAG, "[Step2] HTTP " + response.code()
                            + " 响应: " + body.substring(0, Math.min(500, body.length())));

                    if (!response.isSuccessful()) {
                        Log.w(TAG, "[Step2] HTTP 失败，尝试 fallback");
                        tryFallback(channelId, "Step2 HTTP " + response.code(), callback);
                        return;
                    }

                    // 响应可能是明文 JSON 或 AES-CBC 加密的 Base64 字符串
                    String streamUrl = parseVdnxbkResponse(body);
                    if (streamUrl != null) {
                        Log.d(TAG, "[Step2] 解析成功: " + streamUrl);
                        mainHandler.post(() -> callback.onSuccess(streamUrl, false));
                    } else {
                        Log.w(TAG, "[Step2] 无可用流地址，尝试 fallback");
                        tryFallback(channelId, "Step2 无可用流地址", callback);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[Step2] 异常: " + e.getMessage(), e);
                    tryFallback(channelId, "Step2 异常: " + e.getMessage(), callback);
                } finally {
                    response.close();
                }
            }
        });
    }

    // ==================== 签名生成 ====================

    /**
     * 生成 auth-key Header 值
     *
     * 格式：{timestamp}-{random}-{MD5(channel + timestamp + random + salt)}
     *
     * 来源：liveplayer.js setH5Str() 函数逆向
     *
     * @param channelId 频道 ID（如 "cctv_p2p_hdcctv6"）
     * @param timestamp 秒级时间戳
     * @param rand      100~999 随机数
     * @param mobile    true=移动端盐值，false=PC 端盐值
     */
    private String buildAuthKey(String channelId, long timestamp, int rand, boolean mobile) {
        String salt = mobile ? SIGN_SALT_MOBILE : SIGN_SALT_PC;
        // 签名原文：channel + timestamp + random + salt
        String signSource = channelId + timestamp + rand + salt;
        String sign = md5Lower(signSource);
        return timestamp + "-" + rand + "-" + sign;
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 vdnxbk 响应：
     *   - 若是 JSON 对象，直接提取 url 字段
     *   - 若是 Base64 字符串，先 AES-CBC 解密再提取
     */
    private String parseVdnxbkResponse(String body) {
        body = body.trim();

        // 情况1：明文 JSON
        if (body.startsWith("{")) {
            return extractUrlFromJson(body);
        }

        // 情况2：JSONP 包裹
        if (body.contains("getHtml5VideoData(") || body.startsWith("var ")) {
            JsonObject root = parseJsonpToObject(body);
            if (root != null) {
                return extractUrlFromJsonObject(root);
            }
        }

        // 情况3：AES-CBC 加密的 Base64 字符串
        // 特征：纯 Base64 字符（A-Z a-z 0-9 +/= 不含 { } 空格）
        if (looksLikeBase64(body)) {
            Log.d(TAG, "[Step2] 检测到加密响应，尝试 AES 解密");
            String decrypted = aesDecrypt(body);
            if (decrypted != null) {
                Log.d(TAG, "[Step2] AES 解密成功: " + decrypted.substring(0, Math.min(200, decrypted.length())));
                return extractUrlFromJson(decrypted);
            }
        }

        return null;
    }

    /**
     * 从 JSON 字符串中提取流地址
     */
    private String extractUrlFromJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return extractUrlFromJsonObject(obj);
        } catch (Exception e) {
            Log.w(TAG, "extractUrlFromJson 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从解析后的 JsonObject 中提取可播放的视频流 URL
     * vdnxbk 响应结构：
     * {
     *   "ack": "yes",
     *   "hls_url": { "hls1":"...", "hls2":"...", ... },
     *   "flv_url": { "flv1":"...", ... },
     *   "url": "...",           // 部分版本直接返回单个 url
     *   "urls": [...],          // 部分版本返回数组
     * }
     */
    private String extractUrlFromJsonObject(JsonObject root) {
        // 检查 play 字段
        if (isPlayBlocked(root)) return null;

        // 直接 url 字段
        if (root.has("url")) {
            String u = safeGetString(root, "url");
            if (isVideoUrl(u)) return u;
        }

        // hls_url 对象
        if (root.has("hls_url")) {
            JsonObject hls = root.getAsJsonObject("hls_url");
            for (String key : new String[]{"hls1", "hls2", "hls3", "hls4"}) {
                String u = safeGetString(hls, key);
                if (isVideoUrl(u)) return u;
            }
        }

        // flv_url 对象
        if (root.has("flv_url")) {
            JsonObject flv = root.getAsJsonObject("flv_url");
            for (String key : new String[]{"flv1", "flv2", "flv3", "flv4"}) {
                String u = safeGetString(flv, key);
                if (isVideoUrl(u)) return u;
            }
        }

        // urls 数组
        if (root.has("urls")) {
            JsonArray urls = root.getAsJsonArray("urls");
            for (JsonElement el : urls) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String u = safeGetString(item, "url");
                if (isVideoUrl(u)) return u;
            }
        }

        return null;
    }

    /**
     * 从 liveHtml5.do 的 JSON 中直接提取可播放视频 URL（非混淆）
     * 如果所有 hls/flv URL 都是混淆串（yangshi?...），返回 null
     */
    private String extractDirectVideoUrl(JsonObject root) {
        // hls_url 中取视频流（跳过 audio/、.png、.json）
        if (root.has("hls_url")) {
            JsonObject hls = root.getAsJsonObject("hls_url");
            for (String key : new String[]{"hls1", "hls2", "hls3", "hls4"}) {
                String u = safeGetString(hls, key);
                if (isVideoUrl(u)) return u;
            }
        }
        // flv_url
        if (root.has("flv_url")) {
            JsonObject flv = root.getAsJsonObject("flv_url");
            for (String key : new String[]{"flv1", "flv2", "flv3", "flv4"}) {
                String u = safeGetString(flv, key);
                if (isVideoUrl(u)) return u;
            }
        }
        return null;
    }

    // ==================== Fallback ====================

    /**
     * 多级 fallback 策略：
     *   1. 金山云 CDN 模板
     *   2. 阿里云 CDN 模板
     *   3. hls6 音频流（isAudioOnly=true）
     */
    private void tryFallback(String channelId, String reason, ParseCallback callback) {
        Log.d(TAG, "[Fallback] 原因: " + reason + "，频道: " + channelId);

        // Fallback 1/2：静态 CDN 模板（需要网络验证，这里直接给出地址）
        String cdnKey = extractCdnKey(channelId);
        if (cdnKey != null) {
            // 先尝试金山云
            String ksUrl = String.format(FALLBACK_KS, cdnKey);
            Log.d(TAG, "[Fallback] 尝试金山云: " + ksUrl);
            verifyStreamUrl(ksUrl, channelId, callback);
            return;
        }

        // 无法构造 CDN URL，尝试音频流 fallback
        tryAudioFallback(channelId, reason, callback);
    }

    /**
     * 验证 CDN URL 是否可访问，若不可访问则降级到阿里云或音频流
     */
    private void verifyStreamUrl(String url, String channelId, ParseCallback callback) {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .head() // 只请求 HEAD，不下载 body，节省流量
                .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[Fallback] 金山云不可达，尝试阿里云");
                String cdnKey = extractCdnKey(channelId);
                if (cdnKey != null) {
                    String aliUrl = String.format(FALLBACK_ALI, cdnKey);
                    mainHandler.post(() -> callback.onSuccess(aliUrl, false));
                } else {
                    tryAudioFallback(channelId, "所有 CDN 不可达", callback);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
                int code = response.code();
                Log.d(TAG, "[Fallback] 金山云响应: " + code);
                if (code == 200 || code == 206) {
                    // 金山云可用
                    mainHandler.post(() -> callback.onSuccess(url, false));
                } else {
                    // 金山云不可用，尝试阿里云
                    String cdnKey = extractCdnKey(channelId);
                    if (cdnKey != null) {
                        String aliUrl = String.format(FALLBACK_ALI, cdnKey);
                        Log.d(TAG, "[Fallback] 尝试阿里云: " + aliUrl);
                        mainHandler.post(() -> callback.onSuccess(aliUrl, false));
                    } else {
                        tryAudioFallback(channelId, "CDN 返回 " + code, callback);
                    }
                }
            }
        });
    }

    /**
     * 最终 fallback：音频流（有声音无画面）
     * URL 格式：hls6 = https://piccpndali.v.myalicdn.com/audio/{channelShort}_2.m3u8
     */
    private void tryAudioFallback(String channelId, String reason, ParseCallback callback) {
        String shortName = extractShortName(channelId); // cctv_p2p_hdcctv6 → cctv6
        if (shortName != null) {
            String audioUrl = "https://piccpndali.v.myalicdn.com/audio/" + shortName + "_2.m3u8";
            Log.d(TAG, "[Fallback] 音频流: " + audioUrl);
            mainHandler.post(() -> callback.onSuccess(audioUrl, true));
        } else {
            mainHandler.post(() -> callback.onFailed("无可用直播流: " + reason));
        }
    }

    // ==================== AES 解密 ====================

    /**
     * AES-256-CBC 解密
     *
     * @param base64Cipher Base64 编码的密文
     * @return 解密后的明文字符串，失败返回 null
     */
    private String aesDecrypt(String base64Cipher) {
        try {
            byte[] cipherBytes = android.util.Base64.decode(
                    base64Cipher.trim(), android.util.Base64.DEFAULT);
            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(AES_IV);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decrypted = cipher.doFinal(cipherBytes);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "AES 解密失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 工具方法 ====================

    /** 解析 JSONP 或直接 JSON 字符串为 JsonObject */
    private JsonObject parseJsonpToObject(String body) {
        try {
            String json = null;

            // 格式A：var html5VideoData = '...'; getHtml5VideoData(...);
            int idx = body.indexOf("var html5VideoData = '");
            if (idx >= 0) {
                int start = idx + "var html5VideoData = '".length();
                int end = body.lastIndexOf("';");
                if (end > start) {
                    json = body.substring(start, end);
                }
            }

            // 格式B：getHtml5VideoData({...})
            if (json == null) {
                int funcIdx = body.indexOf("getHtml5VideoData(");
                if (funcIdx >= 0) {
                    int braceStart = body.indexOf("{", funcIdx);
                    int braceEnd = body.lastIndexOf("}");
                    if (braceStart >= 0 && braceEnd > braceStart) {
                        json = body.substring(braceStart, braceEnd + 1);
                    }
                }
            }

            // 格式C：直接 JSON
            if (json == null && body.trim().startsWith("{")) {
                json = body.trim();
            }

            if (json == null) return null;
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            Log.w(TAG, "parseJsonpToObject 失败: " + e.getMessage());
            return null;
        }
    }

    /** 构建带通用 Header 的 OkHttp Request */
    private Request buildRequest(String url, String referer) {
        return new Request.Builder()
                .url(url)
                .header("Referer", referer)
                .header("Origin", "https://tv.cctv.com")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
    }

    /**
     * 判断 URL 是否是可播放的视频流（非混淆、非图片、非音频目录、非 JSON）
     * 混淆串特征：不以 http 开头，如 "yangshi?group&drm=0&zbzx"
     */
    private boolean isVideoUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        // 排除图片
        if (url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg")) return false;
        // 排除配置 JSON
        if (url.endsWith(".json")) return false;
        // 排除音频目录
        if (url.contains("/audio/")) return false;
        // 必须看起来像流地址
        boolean hasStreamExt = url.contains(".m3u8") || url.contains(".flv")
                || url.contains(".ts") || url.contains(".mp4");
        boolean isKnownCdn = url.contains("kcdnvip.com") || url.contains("myalicdn.com")
                || url.contains("cntv.cn") || url.contains("chinanetcenter.com")
                || url.contains("cdnvip") || url.contains("txcloud") || url.contains("txlivecloud");
        return hasStreamExt || isKnownCdn;
    }

    /** 判断字符串是否 play=0（版权限制不可播放） */
    private boolean isPlayBlocked(JsonObject root) {
        if (!root.has("play")) return false;
        try {
            return "0".equals(root.get("play").getAsString());
        } catch (Exception e) {
            return false;
        }
    }

    /** 安全获取 JsonObject 中的 String 值，失败返回 null */
    private String safeGetString(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 channelId 提取 CDN 路径中的频道号
     * cctv_p2p_hdcctv6    → "6"
     * cctv_p2p_hdcctv5plus → "5plus"
     * cctv_p2p_hdcctvjilu  → "9"（纪录频道对应 9）
     */
    private String extractCdnKey(String channelId) {
        String prefix = "cctv_p2p_hdcctv";
        if (!channelId.startsWith(prefix)) return null;
        String suffix = channelId.substring(prefix.length());
        if ("jilu".equals(suffix)) return "9";
        if ("5plus".equalsIgnoreCase(suffix)) return "5plus";
        try {
            Integer.parseInt(suffix); // 验证是数字
            return suffix;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 channelId 提取短名称，用于音频流 URL
     * cctv_p2p_hdcctv6   → "cctv6"
     * cctv_p2p_hdcctvjilu → "cctvjilu"
     */
    private String extractShortName(String channelId) {
        String prefix = "cctv_p2p_hd";
        if (!channelId.startsWith(prefix)) return null;
        return channelId.substring(prefix.length()); // "cctv6"、"cctvjilu" 等
    }

    /** 判断字符串是否像 Base64（用于识别加密响应） */
    private boolean looksLikeBase64(String s) {
        if (s == null || s.length() < 20) return false;
        // Base64 字符集：A-Z a-z 0-9 + / =，且不包含空白以外的特殊字符
        return s.matches("[A-Za-z0-9+/=\\s]+") && !s.contains("{") && !s.contains("<");
    }

    /** MD5 哈希（小写十六进制） */
    private String md5Lower(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /** Hex 字符串转 byte 数组 */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}

