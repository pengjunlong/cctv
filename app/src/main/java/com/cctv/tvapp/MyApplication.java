package com.cctv.tvapp;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.acra.ACRA;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.data.StringFormat;

/**
 * 全局 Application。
 *
 * <p>启动时初始化两套上报机制：
 * <ol>
 *   <li><b>ACRA</b>：全局崩溃捕获，通过自定义 {@link GitHubIssueSender}
 *       将崩溃报告自动创建为 GitHub Issues。</li>
 *   <li><b>CrashReporter</b>：本地日志持久化，写入 Downloads/cctv/crash_logs/
 *       供离线分析。</li>
 * </ol>
 *
 * <p>GitHub PAT 配置方式：
 * <ol>
 *   <li>在 GitHub 仓库 → Settings → Secrets and variables → Actions 中</li>
 *   <li>新建 Repository secret，名称 {@code CRASH_REPORT_TOKEN}，值填入你的 fine-grained PAT</li>
 *   <li>PAT 只需 Issues: Write 权限，CI 构建时会自动通过环境变量注入 BuildConfig</li>
 * </ol>
 */
public class MyApplication extends android.app.Application {

    private static final String TAG = "cctvApp";

    // =========================================================
    // GitHub Issues 配置
    // =========================================================

    /** GitHub 仓库 owner */
    private static final String GITHUB_OWNER = "pengjunlong";
    /** GitHub 仓库名 */
    private static final String GITHUB_REPO  = "cctv";
    /**
     * GitHub PAT，由 CI 通过环境变量注入 BuildConfig.CRASH_REPORT_TOKEN。
     * 本地调试时为空字符串，崩溃上报功能不可用但不影响应用运行。
     */
    private static final String GITHUB_TOKEN = BuildConfig.CRASH_REPORT_TOKEN;

    // =========================================================
    // ACRA 必须在 attachBaseContext 中初始化（早于 onCreate）
    // =========================================================

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // 1. 先配置 GitHubIssueSender（在 ACRA.init 之前）
        GitHubIssueSenderFactory.setConfig(
                new GitHubIssueSender.Config(GITHUB_OWNER, GITHUB_REPO, GITHUB_TOKEN));

        // 2. 初始化 ACRA（silent 模式：崩溃后无弹窗，直接上报）
        ACRA.init(this, new CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig.class)
                .withReportFormat(StringFormat.JSON)
                // 收集 logcat（最近 100 行）
                .withLogcatArguments("-t", "100", "-v", "time")
        );

        Log.i(TAG, "ACRA initialized. reporter=GitHub Issues " + GITHUB_OWNER + "/" + GITHUB_REPO);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 3. 初始化本地日志（写入 Downloads/cctv/crash_logs/）
        CrashReporter.init(this);

        // 4. 安装全局崩溃捕获：崩溃时启动 CrashDisplayActivity 展示完整错误栈
        //    在 ACRA 的 handler 之后再包一层，先让 ACRA 上报，再显示错误页
        Thread.UncaughtExceptionHandler acraHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Intent intent = CrashDisplayActivity.buildIntent(
                        getApplicationContext(), throwable);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
                // 万一 startActivity 也失败，不要再次崩溃
            }
            // 让 ACRA 继续处理（上报 GitHub Issue）
            if (acraHandler != null) {
                acraHandler.uncaughtException(thread, throwable);
            }
        });

        Log.i(TAG, "Application started.");
    }
}
