# ASFAndroid ｜ 把 ArchiSteamFarm 移植到 Android

> 将 [ArchiSteamFarm](https://github.com/JustArchiNET/ArchiSteamFarm)（ASF）移植到 Android 的完整工程。
> 参考了 OpenList/AList 安卓版（如 [AListLiteAndroid](https://github.com/LeoHaoVIP/AListLiteAndroid)）的"**服务端二进制内嵌进 App + WebView 即开即用**"方案。

## 为什么不能像 OpenList 那样直接内嵌

OpenList 是 Go 程序，可以轻松交叉编译成 Android 原生 ELF 放进 APK。
而 ASF 是 .NET 程序，.NET 官方 **没有** 为 Android 提供 `hostfxr`/`hostpolicy`（`Microsoft.AspNetCore.App.Runtime.android-arm64` 也不存在），
因此无法把一个 net10.0 的 ASF 控制台程序直接发布成 Android 原生可执行文件。

## 本项目的移植路线

```
┌─────────────────────────────────────────────────────────────┐
│ Android App (Kotlin)                                        │
│  ┌──────────────┐   WebView ──► http://127.0.0.1:1242     │
│  │  ASF-ui 界面  │        ▲                                    │
│  └──────────────┘        │                                 │
│  ┌────────────────────┐   │  IPC (Kestrel)                │
│  │ AsfService(前台)   │   │                                 │
│  │   │ 启动/停止      │   │                                 │
│  │   ▼               │   │                                 │
│  │ ProotRunner       │   │                                 │
│  │   │ execve         │   │                                 │
│  └───┼────────────────┘   │                                 │
│      └───────────────────────┐                              │
└──────────────────────────────┼──────────────────────────────┘
                               ▼
        proot (Android 原生，无需 root)
          │ --rootfs=App私有目录/linux
          ▼
   精简 arm64 Linux rootfs（glibc + libicu …）
          │ /asf/ArchiSteamFarm --path /asf-data
          ▼
   ASF 官方 linux-arm64 自包含版本
```

- **不需要 root**：使用 `proot` 在 App 私有目录里模拟 Linux 用户态，ASF 跑在 App 沙箱内。
- **不需要 Termux**：rootfs、ASF、proot 全部内嵌/首次启动时解压到 App 目录，即装即用。
- **ASF 官方二进制**：直接使用官方发布的 `ASF-linux-arm64.zip`（自包含 .NET 运行时）。
- **界面**：App 内置 WebView 打开 ASF-ui（`http://127.0.0.1:1242`），并支持配置编辑、日志查看。

## 仓库结构

```
├── android/                  # Android 原生应用工程（Kotlin + Gradle）
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/asfandroid/   # Kotlin 源码
│   │       ├── res/                  # 资源
│   │       └── assets/               # rootfs 归档（构建脚本生成）
│   └── ...
├── scripts/
│   ├── build-rootfs.sh      # 构建 arm64 rootfs（含 ASF + proot + glibc/ICU）
│   ├── prepare-assets.sh    # 把 rootfs 归档拷入 assets
│   └── build-apk.sh         # 构建 APK
├── termux/
│   └── install-asf.sh      # Termux 一键安装脚本（已验证的备用路线）
└── docs/
    └── BUILD.md             # 详细构建与打包说明
```

## 快速开始

### 方式一：Termux 一键安装（最快，已验证路线）

1. 安装 [Termux](https://f-droid.org/ru/packages/com.termux/)。
2. 执行：

```bash
curl -L -O https://raw.githubusercontent.com/<your-repo>/ASFAndroid/main/termux/install-asf.sh
chmod +x install-asf.sh && bash install-asf.sh
```

3. 完成后 ASF 会自动启动，用浏览器打开 `http://127.0.0.1:1242` 即可看到 ASF-ui。

### 方式二：构建原生 App（OpenList 安卓版风格）

需要一台 Linux 构建机（x86_64 即可，带 `apt`、`curl`、`unzip`、JDK 17+、Android SDK）。

```bash
# 1. 构建 arm64 rootfs（含 ASF 官方 linux-arm64 二进制、proot、glibc/ICU）
bash scripts/build-rootfs.sh

# 2. 准备 App 资源
bash scripts/prepare-assets.sh

# 3. 构建 APK
bash scripts/build-apk.sh
```

详细步骤见 [docs/BUILD.md](docs/BUILD.md)。

## 免责声明

- 本项目仅用于合法的 Steam 自动化场景，使用前请阅读并遵守 Steam 用户协议。
- 移植方案属于社区实践，.NET 在 Android 上的运行并非官方支持场景，请在真机上充分测试。
- 本项目与 ArchiSteamFarm 官方无关。

## 许可证

- ASF 使用 Apache-2.0 许可证。
- 本工程代码采用 Apache-2.0 许可证。