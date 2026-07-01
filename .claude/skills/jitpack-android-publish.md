---
name: jitpack-android-publish
description: 将 Android Library 发布到 JitPack 的完整流程、配置、踩坑点与验证命令
---

# JitPack Android Library 发布指南

适用于把本项目的 Android Library 模块发布到 JitPack，供外部通过 `implementation 'com.github.<user>:<repo>:<version>'` 引用。

## 前置条件

1. 项目是 Git 仓库，并已 push 到 GitHub。
2. **仓库必须是 Public**。JitPack 默认只支持 Public 仓库；若使用 Private 仓库，需在 JitPack 设置中添加 GitHub Access Token，且构建列表不会自动刷新。
3. library 模块使用 `com.android.library` 插件，不是 `com.android.application`。
4. 仓库根目录有 `jitpack.yml` 指定 JDK 17：
   ```yaml
   jdk:
     - openjdk17
   ```
   如果项目 Gradle Wrapper 版本较高，建议同时指定 Gradle 版本：
   ```yaml
   jdk:
     - openjdk17
   before_install:
     - wget https://services.gradle.org/distributions/gradle-8.13-bin.zip
     - unzip gradle-8.13-bin.zip
     - export GRADLE_HOME=$PWD/gradle-8.13
     - export PATH=$GRADLE_HOME/bin:$PATH
   ```
5. library 模块的 `build.gradle` 在 `defaultConfig` 中配置 `archivesBaseName`：
   ```gradle
   android {
       namespace 'com.xxx.yyy'
       compileSdk 34
       defaultConfig {
           minSdk 21
           targetSdk 34
           archivesBaseName = 'YourArtifactName'
       }
       // ...
   }
   ```
   > AGP 8.0+ 中，`archivesBaseName` 放在 `android {}` 顶级已被废弃，必须放在 `defaultConfig {}` 内。
   > 不要手写 `maven-publish` 自定义 `publishing.publications`，否则容易发布成 POM-only 而缺少 AAR。

## 发布步骤

### 1. 提交代码

```bash
cd G:\workspace\WebSocketScan
git add .
git commit -m "feat: xxxx"
git push origin main
```

### 2. 打 tag 并 push

JitPack 使用 git tag 作为版本号。Git tag 可以带 `v` 前缀（如 `v1.0.0`），但 `implementation` 坐标里推荐写纯数字版本号（`1.0.0`），更符合 Maven 规范。

```bash
git tag v1.0.0
git push origin v1.0.0
```

> 一旦 tag push 到 GitHub，不要 force-push 同名 tag 去覆盖新 commit——JitPack 对已经构建过的 version 可能不会自动重新构建。要发修复版请直接打新 tag（如 `v1.0.1`）。

### 3. 在 JitPack 上触发构建

1. 打开 `https://jitpack.io/#<github用户名>/<仓库名>`
2. 用 GitHub 账号登录并授权。
3. 在版本列表里找到刚 push 的 tag，点击 **Get it**。
4. 等待 1-5 分钟，状态变绿（✅）即成功。

如果某版本构建失败，列表右侧可能出现 **Refresh** / **Re-build** 按钮，点击即可重新构建，无需打新 tag。

### 4. 验证产物

替换用户名、仓库名、版本号后执行。JitPack 链接可能有 CDN 重定向，建议加 `-L` 跟随：

```bash
curl -s -o /dev/null -w "%{http_code}\n" -L "https://jitpack.io/com/github/<user>/<repo>/<version>/<artifact>-<version>.aar"
curl -s -o /dev/null -w "%{http_code}\n" -L "https://jitpack.io/com/github/<user>/<repo>/<version>/<artifact>-<version>.pom"
```

例如本仓库 1.0.7：

```bash
curl -s -o /dev/null -w "%{http_code}\n" -L "https://jitpack.io/com/github/jingyao6/WebSocketScan/1.0.7/WebSocketScan-1.0.7.aar"
curl -s -o /dev/null -w "%{http_code}\n" -L "https://jitpack.io/com/github/jingyao6/WebSocketScan/1.0.7/WebSocketScan-1.0.7.pom"
```

返回 `200` 表示产物存在。

### 5. 使用方引用

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.jingyao6:WebSocketScan:1.0.7'
}
```

> 推荐坐标里写 `1.0.7`（纯数字）。写 `v1.0.7` 也能用，但 Maven 规范更习惯纯数字。

## 常见错误与解决

### 1. `Java home supplied is invalid`

**原因**：`gradle.properties` 里写了 Windows 路径的 `org.gradle.java.home`，被 JitPack Linux 沙箱读到。

**解决**：删掉项目 `gradle.properties` 里的 `org.gradle.java.home`，改到本机用户级配置 `%USERPROFILE%\.gradle\gradle.properties`。

### 2. `Could not find com.github.xxx:Repo:1.0.0`

**原因**：library 模块用了手写 `maven-publish`，把 app module 发布成了 POM-only，没有 AAR。

**解决**：删掉自定义 `publishing` 块，在 `defaultConfig {}` 内改用 `archivesBaseName = 'YourArtifactName'`，让 JitPack 默认发布 AAR。

### 3. `Manifest merger failed : Attribute application@theme value=... is also present at [...]`

**原因**：library 的 `AndroidManifest.xml` 里给 `<application>` 加了 `android:theme` / `android:label` / `android:icon` 等属性。

**解决**：library 的 manifest 只保留空 `<application />`：
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application />
</manifest>
```

### 4. JitPack 列表里只有旧 version，新 tag 没出现

**原因**：JitPack webhook 有延迟，或缓存未刷新。

**解决**：等 2-5 分钟再刷新页面；或者直接访问 `https://jitpack.io/com/github/<user>/<repo>/<version>/build.log` 看是否已经生成；若该版本构建失败，可点击 **Refresh** / **Re-build** 重新构建。

## 版本号同步清单

每次发新版，确保以下文件同步：

- `gradle/libs.versions.toml` 里的 `websocketscan` 版本号
- `README.md` 引用示例
- `USAGE.md` 引用示例
- `demo/build.gradle` 注释里的验证版本号（如适用）
- GitHub Release（可选，用于 Release Notes）

## 快速检查命令

```bash
# 看所有 version 状态
curl -s "https://jitpack.io/api/builds/com.github.jingyao6/WebSocketScan"

# 看某个 version 详细状态
curl -s "https://jitpack.io/api/builds/com.github.jingyao6/WebSocketScan/1.0.7"

# 看 build log
curl -s -L "https://jitpack.io/com/github/jingyao6/WebSocketScan/1.0.7/build.log"
```

## 禁忌

- 不要 force-push 已经构建过的 tag 来覆盖 commit。
- 不要在 library manifest 里声明 theme/label/icon。
- 不要把 `org.gradle.java.home` 这种本机路径写进项目 `gradle.properties` 后提交到 git。
- 不要在 library 的 `build.gradle` 里手写 `maven-publish` 自定义坐标，除非你很清楚 AGP 8.x 的发布机制。
- 不要把 `archivesBaseName` 放在 `android {}` 顶级，AGP 8.0+ 会报警告。
