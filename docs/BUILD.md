# ASFAndroid 构建指南

本文档说明如何从源码构建 ASFAndroid（OpenList 安卓版风格的 ASF Android App）。

## 架构简述

ASF 是 .NET 程序，官方没有 Android 原生运行支持。本项目采用 **proot + 精简 Linux rootfs** 方案：

1. App 内置一个 **arm64 精简 Ubuntu rootfs**（glibc / libicu / OpenSSL / shell）。
2. App 内置 **Termux 的 proot**（Android 原生可执行文件，无需 root）。
3. 运行时 App 用 `proot` 在应用私有目录中模拟 Linux 用户态，并在其中执行 **ASF 官方 linux-arm64 自包含版本**。
4. ASF 的配置、日志、数据库写入应用私有目录（`filesDir/asf-data`）。
5. App 内置 **WebView** 访问 `http://127.0.0.1:1242` 的 ASF-ui 界面。

```
ASFAndroid App
 ├── assets/asf/rootfs/asf-rootfs-arm64.tar    ← 由 prepare-assets.sh 从 tar.gz 解压生成
 ├── jniLibs/arm64-v8a/libproot.so             ← proot 本体（重命名为 .so 以通过 AGP 打包）
 ├── jniLibs/arm64-v8a/libproot_loader.so        ← proot 的 ELF 加载器
 ├── jniLibs/arm64-v8a/libtalloc.so             ← proot 依赖（SONAME 已重写）
 ├── jniLibs/arm64-v8a/libandroid-shmem.so      ← proot 依赖
 └── Kotlin 源码：前台服务 + WebView + 配置/日志
```

## 环境要求（构建机）

- **Linux x86_64**（Debian/Ubuntu 系最佳，因为需要 `apt-get`）
- `apt-get`、`dpkg-deb`、`curl`、`unzip`、`patchelf`
- **JDK 17+**
- **Android SDK**（含 platform 35、build-tools）
- 不需要 .NET SDK（ASF 直接使用官方发布的二进制）

## 构建步骤

### 第 1 步：生成 rootfs 与 proot 资源

```bash
cd ASFAndroid
bash scripts/build-rootfs.sh
```

脚本会：

1. 用 arm64 的 Ubuntu apt 源下载并解压运行 ASF 所需的库（glibc、libicu74、libssl、zlib 等）到 `dist/rootfs/`；
2. 从 GitHub 下载 ASF 官方 `ASF-linux-arm64.zip`（可通过 `ASF_VERSION=6.3.10.4` 指定版本）并解压到 `dist/rootfs/asf/`；
3. 从 Termux 仓库下载原生 `proot` 及其依赖 `libandroid-shmem`、`libtalloc`，并用 `patchelf` 把 proot 的 RUNPATH 改为 `$ORIGIN`、把 `libtalloc.so.2` 的依赖/SONAME 改写为 `libtalloc.so`（AGP 只打包 `*.so`）；
4. 打包 `dist/asf-rootfs-arm64.tar.gz`（52MB 左右）。

> 可选环境变量：
> - `ASF_VERSION=latest` 或具体版本号（如 `6.3.10.4`）
> - `UBUNTU_SUITE=noble`（默认 24.04）
> - `OUT_DIR=/path/to/dist`

### 第 2 步：把产物放入 Android 工程

```bash
bash scripts/prepare-assets.sh
```

将 `dist/asf-rootfs-arm64.tar.gz` 解压为未压缩的 `asf-rootfs-arm64.tar` 并复制到
`android/app/src/main/assets/asf/rootfs/`，
将 `dist/proot-arm64/` 中的 `proot`、`loader`、`libtalloc.so.2.5.0`、`libandroid-shmem.so` 复制为
`libproot.so`、`libproot_loader.so`、`libtalloc.so`、`libandroid-shmem.so` 放入
`android/app/src/main/jniLibs/arm64-v8a/`。
（AAPT2 会自动解压 assets 里的 `.gz` 并改名为 `.tar`，因此 assets 中统一使用未压缩的 `.tar`；
AGP 打包 jniLibs 只保留 `*.so`，因此 proot/loader 需重命名为 `.so`。）

### 第 3 步：构建 APK

```bash
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-apk.sh
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

> 首次构建会自动生成 Gradle wrapper（需要系统已安装 Gradle，或直接使用仓库内的 `gradlew`）。

### 第 4 步：安装到手机

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

支持架构：`arm64-v8a`（绝大多数现代手机/电视盒子）。如需要 `x86_64` 模拟器支持，可参考 `build-rootfs.sh` 扩展。

## 使用说明

1. 打开 App，点击右上角开关启动 ASF。
   - 首次启动会解压 rootfs（约 100–200MB，需等待 1–3 分钟）。
   - 解压完成后自动拉起 ASF 进程。
2. WebView 会自动打开 ASF-ui：`http://127.0.0.1:1242`。
3. 默认配置：
   - `config/ASF.json`：`Headless=true`（无控制台交互）、`AutoUpdates=false`（避免 proot 环境下自更新出问题）
   - `config/IPC.config`：IPC 监听 `127.0.0.1:1242`
   - 可在「配置」页修改这些 JSON 文件。
4. 添加机器人：在 ASF-ui 中通过 ASF-ui 或直接编辑 `config/*.json` 添加 bot 账号配置。
5. 状态栏磁贴 / 开机自启：在 App 菜单中设置开机自启，并可在系统设置中允许自启动。

## 常见问题

### 构建时 `apt-get download` 报 GPG 错误
脚本已使用未签名源模式；如果网络受限可设置 `MIRROR` 镜像（默认使用阿里云 `https://mirrors.aliyun.com/ubuntu-ports`，可覆盖为清华/官方源）。

### 从 GitHub 下载 ASF 很慢或失败
脚本内置了断点续传、HTTP/1.1、多重试，并在直连失败时自动回退到 `gh-proxy.com` / `ghfast.top` 代理。
也可以手动指定版本号（如 `ASF_VERSION=6.3.10.3`）重试。

### 安装后启动提示 "无法解压 rootfs"
说明 APK 中没有打包 rootfs 资源。请确认已执行 `prepare-assets.sh` 后再构建 APK。

### ASF 进程被杀 / 无法连接 WebUI
查看「日志」页面的 `asf-console.log`；或使用 `adb logcat -s AsfService AsfProcess` 排查。

### 想升级 ASF
重新执行 `build-rootfs.sh`（指定新版 `ASF_VERSION`）→ `prepare-assets.sh` → 重新构建 APK 安装。
（ASF 也支持在运行中通过 `AutoUpdates` 自动更新，但更新后的文件写回 rootfs 目录，重启后保留。）

## 已知限制

- 仅支持 `arm64-v8a`。
- 首次启动解压较慢。
- proot 方案属于用户态模拟，性能略低于原生，对 ASF 这种低频网络应用完全够用。
- .NET 程序在 Android 上并非官方支持场景，本项目为社区实践。

## CI 参考

`.github/workflows/build-apk.yml` 提供了在 GitHub Actions 上自动构建 APK 的示例。