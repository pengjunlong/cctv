package com.cctv.tvapp;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.acra.config.CoreConfiguration;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderFactory;

/**
 * ACRA ReportSenderFactory：创建 {@link GitHubIssueSender} 实例。
 *
 * <p>通过 {@code @AutoService} 注解自动生成
 * {@code META-INF/services/org.acra.sender.ReportSenderFactory} 注册文件，
 * ACRA 在初始化时通过 ServiceLoader 自动发现并加载本 Factory。
 *
 * <p>配置方式：在 {@code BuildConfig} 中定义以下字段（推荐通过 {@code build.gradle} 注入）：
 * <pre>
 *   BuildConfig.GITHUB_OWNER  = "pengjunlong"
 *   BuildConfig.GITHUB_REPO   = "cctv"
 *   BuildConfig.GITHUB_TOKEN  = "ghp_xxxx"  // PAT，Issues: Write 权限
 * </pre>
 *
 * <p>必须有无参 public 构造函数（ServiceLoader 要求）。
 */
@AutoService(ReportSenderFactory.class)
public class GitHubIssueSenderFactory implements ReportSenderFactory {

    /**
     * GitHub 仓库 owner（用户名或组织名）
     * 在 MyApplication 中通过 GitHubIssueSender.Config 传入，
     * 这里通过静态配置持有。
     */
    private static volatile GitHubIssueSender.Config sConfig;

    /** 由 MyApplication 在 ACRA.init 之前设置配置 */
    public static void setConfig(GitHubIssueSender.Config config) {
        sConfig = config;
    }

    public GitHubIssueSenderFactory() {
        // 无参构造函数，ServiceLoader 要求
    }

    @NonNull
    @Override
    public ReportSender create(@NonNull Context context,
                               @NonNull CoreConfiguration config) {
        GitHubIssueSender.Config c = sConfig;
        if (c == null) {
            throw new IllegalStateException(
                    "GitHubIssueSenderFactory: 请在 ACRA.init() 之前调用 setConfig()");
        }
        return new GitHubIssueSender(c.owner, c.repo, c.token);
    }

    @Override
    public boolean enabled(@NonNull CoreConfiguration coreConfig) {
        return sConfig != null && !sConfig.token.isEmpty();
    }
}

