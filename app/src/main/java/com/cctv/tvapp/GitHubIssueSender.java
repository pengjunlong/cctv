package com.cctv.tvapp;

import android.content.Context;
import android.util.Log;

import org.acra.ReportField;
import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ACRA 自定义 Sender：将崩溃报告通过 GitHub Issues REST API 自动创建 Issue。
 *
 * <p>使用方式：在 {@link GitHubIssueSenderFactory} 中传入配置，无需直接实例化。
 *
 * <p>所需 GitHub PAT 权限：{@code repo} → {@code issues: write}（fine-grained PAT 也可以）。
 *
 * <p>API：{@code POST https://api.github.com/repos/{owner}/{repo}/issues}
 */
public class GitHubIssueSender implements ReportSender {

    private static final String TAG = "GitHubIssueSender";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** GitHub 仓库 owner，如 "pengjunlong" */
    private final String owner;
    /** GitHub 仓库名，如 "cctv" */
    private final String repo;
    /**
     * GitHub Personal Access Token（PAT）。
     * 建议使用 fine-grained PAT，只授予目标 repo 的 Issues: Write 权限。
     * 格式：ghp_xxxx 或 github_pat_xxxx
     */
    private final String token;

    private final OkHttpClient client;

    public GitHubIssueSender(String owner, String repo, String token) {
        this.owner  = owner;
        this.repo   = repo;
        this.token  = token;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void send(Context context, CrashReportData report) throws ReportSenderException {
        try {
            String title = buildTitle(report);
            String body  = buildBody(report);
            String json  = buildJson(title, body);

            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/issues";
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .post(RequestBody.create(json.getBytes(StandardCharsets.UTF_8), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                if (code == 201) {
                    // 201 Created：issue 创建成功
                    String responseBody = response.body() != null ? response.body().string() : "";
                    // 提取 issue html_url 打印日志
                    String issueUrl = extractHtmlUrl(responseBody);
                    Log.i(TAG, "GitHub Issue 创建成功: " + issueUrl);
                    android.widget.Toast.makeText(context, "GitHub Issue 创建成功: " + issueUrl, android.widget.Toast.LENGTH_LONG).show();
                } else {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    Log.w(TAG, "GitHub Issue 创建失败: HTTP " + code + " " + responseBody);
                    android.widget.Toast.makeText(context, "GitHub Issue 创建失败: HTTP " + code + " " + responseBody, android.widget.Toast.LENGTH_LONG).show();
                    // 抛出 ReportSenderException 通知 ACRA 可以重试
                    throw new ReportSenderException("GitHub API 返回 " + code + ": " + responseBody);
                }
            }
        } catch (ReportSenderException e) {
            throw e;
        } catch (IOException e) {
            Log.w(TAG, "GitHub Issue 网络错误: " + e.getMessage());
            throw new ReportSenderException("网络错误: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.w(TAG, "GitHub Issue 发送异常: " + e.getMessage());
            throw new ReportSenderException("发送异常: " + e.getMessage(), e);
        }
    }

    // =========================================================
    // Issue 内容构建
    // =========================================================

    private String buildTitle(CrashReportData report) {
        String exception = safeGet(report, ReportField.STACK_TRACE);
        // 取堆栈第一行作为标题
        String firstLine = exception.split("\n")[0].trim();
        if (firstLine.length() > 100) firstLine = firstLine.substring(0, 100) + "...";
        String version = safeGet(report, ReportField.APP_VERSION_NAME);
        return "[Crash] " + firstLine + " (v" + version + ")";
    }

    private String buildBody(CrashReportData report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 崩溃报告\n\n");

        appendRow(sb, "应用版本",   safeGet(report, ReportField.APP_VERSION_NAME)
                + " (" + safeGet(report, ReportField.APP_VERSION_CODE) + ")");
        appendRow(sb, "Android版本", safeGet(report, ReportField.ANDROID_VERSION));
        appendRow(sb, "设备型号",   safeGet(report, ReportField.PHONE_MODEL));
        appendRow(sb, "品牌",       safeGet(report, ReportField.BRAND));
        appendRow(sb, "发生时间",   safeGet(report, ReportField.USER_CRASH_DATE));
        appendRow(sb, "Report ID", safeGet(report, ReportField.REPORT_ID));

        sb.append("\n## 堆栈信息\n\n```\n");
        sb.append(safeGet(report, ReportField.STACK_TRACE));
        sb.append("\n```\n");

        // 附加 logcat（如果有）
        String logcat = safeGet(report, ReportField.LOGCAT);
        if (!logcat.isEmpty() && !logcat.equals("N/A")) {
            sb.append("\n<details>\n<summary>Logcat</summary>\n\n```\n");
            // 限制 logcat 最多 3000 字符，避免 issue body 过大
            if (logcat.length() > 3000) logcat = logcat.substring(0, 3000) + "\n...(truncated)";
            sb.append(logcat);
            sb.append("\n```\n</details>\n");
        }

        sb.append("\n---\n*由 cctv ACRA 自动创建*");
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, String label, String value) {
        sb.append("**").append(label).append("**: ").append(value).append("  \n");
    }

    private String safeGet(CrashReportData report, ReportField field) {
        try {
            Object val = report.get(field.name());
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // =========================================================
    // JSON 构建（手写避免引入额外依赖）
    // =========================================================

    private String buildJson(String title, String body) {
        return "{\"title\":" + jsonString(title)
                + ",\"body\":" + jsonString(body)
                + ",\"labels\":[\"bug\",\"crash\"]}";
    }

    /** 将字符串转为 JSON 字符串（含双引号，转义特殊字符） */
    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /** 从 GitHub API 响应 JSON 中简单提取 html_url */
    private static String extractHtmlUrl(String json) {
        int idx = json.indexOf("\"html_url\"");
        if (idx < 0) return "(unknown)";
        int start = json.indexOf("\"", idx + 10) + 1;
        int end   = json.indexOf("\"", start);
        return (start > 0 && end > start) ? json.substring(start, end) : "(unknown)";
    }

    // =========================================================
    // 配置类
    // =========================================================

    /**
     * GitHub Issues Sender 配置。
     *
     * <p>在 {@link MyApplication#onCreate()} 中通过
     * {@link GitHubIssueSenderFactory#setConfig(Config)} 传入。
     */
    public static class Config {
        /** GitHub 用户名或组织名，如 "pengjunlong" */
        public final String owner;
        /** GitHub 仓库名，如 "cctv" */
        public final String repo;
        /**
         * GitHub Personal Access Token（PAT）。
         * 推荐使用 fine-grained PAT，只授予目标 repo 的 Issues: Write 权限。
         * 格式：ghp_xxxx 或 github_pat_xxxx
         * 获取：GitHub → Settings → Developer settings → Personal access tokens
         */
        public final String token;

        public Config(String owner, String repo, String token) {
            this.owner = owner;
            this.repo  = repo;
            this.token = token;
        }
    }
}

