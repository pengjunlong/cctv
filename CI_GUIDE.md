# GitHub Actions CI/CD 使用指南

## 自动构建配置

本项目已配置 GitHub Actions，可自动编译 APK 并发布 Release。

### 触发条件

| 事件 | 分支/标签 | 说明 |
|-----|---------|------|
| `push` | `main` / `master` | 编译 Debug + Release APK，上传为 Artifacts |
| `push` | `v*` (tag) | 编译 + **自动创建 GitHub Release** |
| `pull_request` | `main` / `master` | PR 构建验证 |
| `workflow_dispatch` | 手动触发 | 支持手动运行 |

### 版本号规则

- **Tag 构建**（如 `v1.2.3`）：
  - `versionName` = `1.2.3`
  - `versionCode` = `10203` (计算公式: `major * 10000 + minor * 100 + patch`)

- **普通 push/PR**：
  - `versionName` = `dev-<短 commit hash>`
  - `versionCode` = GitHub Actions 运行编号

## 发布新版本

### 步骤 1：打标签并推送

```bash
git tag v1.0.0
git push origin v1.0.0
```

### 步骤 2：等待自动构建

- GitHub Actions 会自动触发构建
- 构建完成后会在 [Releases 页面](../../releases) 自动创建新版本
- 附带 APK 文件供下载

### 步骤 3：查看产物

- **Artifacts（所有 push）**: [Actions 页面](../../actions) → 点击具体运行 → 下载 Artifacts
- **Release（仅 tag）**: [Releases 页面](../../releases) → 下载 APK

## 构建产物说明

| 文件名 | 说明 |
|-------|------|
| `CctvTvApp-{version}-debug.apk` | Debug 版本（含调试符号，体积较大） |
| `CctvTvApp-{version}-release-unsigned.apk` | Release 版本（未签名，体积小） |

> **注意**: Release APK 为 **unsigned**（未签名），可直接安装到开发者模式的设备。
> 若需上架应用市场，需使用 keystore 签名（可通过 GitHub Secrets 配置）。

## 添加签名（可选）

如需生成**已签名的 Release APK**：

### 1. 生成 keystore

```bash
# 需要 JDK 17+ 环境
keytool -genkey -v -keystore my-release-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias my-key-alias
```

### 2. 将 keystore 转为 Base64 存入 GitHub Secrets

```bash
base64 -i my-release-key.jks | pbcopy
```

在仓库 **Settings → Secrets and variables → Actions** 添加：

- `KEYSTORE_BASE64`: 上面复制的 Base64 字符串
- `KEYSTORE_PASSWORD`: keystore 密码
- `KEY_ALIAS`: 密钥别名（如 `my-key-alias`）
- `KEY_PASSWORD`: 密钥密码

### 3. 修改 `app/build.gradle`

```gradle
android {
    signingConfigs {
        release {
            storeFile file("${System.getenv('RUNNER_TEMP') ?: System.getProperty('java.io.tmpdir')}/release.jks")
            storePassword System.getenv("KEYSTORE_PASSWORD")
            keyAlias System.getenv("KEY_ALIAS")
            keyPassword System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

### 4. 修改 `.github/workflows/android-build.yml`

在 `Build Release APK` 步骤前添加：

```yaml
- name: Decode Keystore
  run: |
    echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > $RUNNER_TEMP/release.jks
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}

- name: Build Release APK (signed)
  run: ./gradlew assembleRelease --stacktrace
  env:
    VERSION_NAME: ${{ env.VERSION_NAME }}
    VERSION_CODE: ${{ env.VERSION_CODE }}
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
```

此后生成的 `app-release.apk` 将自动签名（文件名不再含 `-unsigned`）。

## 本地测试 CI 脚本

使用 [act](https://github.com/nektos/act) 在本地模拟 GitHub Actions：

```bash
# 安装 act
brew install act

# 测试 push 到 main
act push -j build

# 测试 tag 发布（需要先创建本地 tag）
git tag v0.0.1
act push --eventpath <(echo '{"ref":"refs/tags/v0.0.1"}') -j release
```

## 常见问题

**Q: 构建失败提示 `ANDROID_HOME not set`？**
A: 不会发生，GitHub Actions runner 已预装 Android SDK。

**Q: 如何查看详细构建日志？**
A: 访问 [Actions 页面](../../actions)，点击具体的运行记录，展开各步骤查看。

**Q: APK 安装时提示"应用未签名"？**
A: Release APK 默认未签名，允许"未知来源"安装即可。或参照上文配置签名。

---

**配置文件位置**: `.github/workflows/android-build.yml`
**Gradle 版本注入**: `app/build.gradle` → `defaultConfig.versionCode/versionName`

