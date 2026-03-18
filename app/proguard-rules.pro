# Add project specific ProGuard rules here.

# ---- GeckoView：保留所有 Mozilla 类，防止 R8 裁剪导致运行时崩溃 ----
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.**

# ---- OkHttp：保留必要类 ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- ACRA：崩溃上报框架 ----
# 保留 ACRA 核心类（R8 可能裁剪反射用到的类）
-keep class org.acra.** { *; }
-keep interface org.acra.** { *; }
-keep enum org.acra.** { *; }
-dontwarn org.acra.**
# 保留自定义 Sender（通过 ServiceLoader 加载，需要完整类名）
-keep class com.cctv.tvapp.GitHubIssueSender { *; }
-keep class com.cctv.tvapp.GitHubIssueSender$Config { *; }
-keep class com.cctv.tvapp.GitHubIssueSenderFactory { *; }
# ServiceLoader 注册文件中的类名不能混淆
-keepnames class com.cctv.tvapp.GitHubIssueSenderFactory

# ---- 保留 R8 处理崩溃堆栈可读性 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 允许 R8 警告（不要因警告失败）----
-ignorewarnings
