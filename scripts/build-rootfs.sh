#!/usr/bin/env bash
#
# build-rootfs.sh
# 构建 ASFAndroid 所需的 arm64 Linux rootfs：
#   - 精简 Ubuntu arm64 用户态（glibc / libicu / openssl / shell 工具等）
#   - ASF 官方 linux-arm64 自包含版本
#   - Termux 原生 proot 可执行文件及其依赖（libandroid-shmem / libtalloc）
#
# 产物：
#   dist/asf-rootfs-arm64.tar.gz        -> 运行时解压到 App 私有目录的 rootfs
#   dist/proot-arm64/*                  -> 需要放进 jniLibs 的原生 proot 二进制
#
# 依赖（构建机）：
#   apt-get / dpkg-deb / curl / unzip / patchelf
#   支持 amd64 即可（通过 apt 直接下载 arm64 的 .deb，无需 root / qemu）
#
set -euo pipefail

UBUNTU_SUITE="${UBUNTU_SUITE:-noble}"          # Ubuntu 24.04
# 默认使用国内镜像（速度快且稳定）；可覆盖为官方源或其他镜像：
#   MIRROR=http://ports.ubuntu.com/ubuntu-ports
#   MIRROR=https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
MIRROR="${MIRROR:-https://mirrors.aliyun.com/ubuntu-ports}"
ASF_VERSION="${ASF_VERSION:-latest}"             # 例如 6.3.10.4；latest 表示 GitHub 最新版
OUT_DIR="${OUT_DIR:-$(pwd)/dist}"
ROOTFS_DIR="${OUT_DIR}/rootfs"
TARBALL="${OUT_DIR}/asf-rootfs-arm64.tar.gz"
PROOT_DIR="${OUT_DIR}/proot-arm64"

APT_LISTS="${OUT_DIR}/.apt-lists"
APT_CACHE="${OUT_DIR}/.apt-cache"
SOURCES_LIST="${OUT_DIR}/.apt-sources.list"

mkdir -p "$ROOTFS_DIR" "$PROOT_DIR" "$APT_LISTS/partial" "$APT_CACHE/partial"

# ---------------------------------------------------------------------------
# 0. 检查依赖工具
# ---------------------------------------------------------------------------
for tool in apt-get dpkg-deb curl unzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖工具: $tool"; exit 1; }
done
if ! command -v patchelf >/dev/null 2>&1; then
  echo "未找到 patchelf，尝试安装..."
  if [ "$(id -u)" = "0" ]; then
    apt-get update -y >/dev/null && apt-get install -y patchelf
  else
    sudo apt-get update -y && sudo apt-get install -y patchelf
  fi
fi

# ---------------------------------------------------------------------------
# 1. 准备 arm64 apt 源并下载基础 .deb 包
# ---------------------------------------------------------------------------
cat > "$SOURCES_LIST" <<EOF
deb [arch=arm64] $MIRROR $UBUNTU_SUITE main
EOF

apt_download() {
  apt-get \
    -o Dir::Etc::sourcelist="$SOURCES_LIST" \
    -o Dir::Etc::sourceparts=- \
    -o Dir::State::Lists="$APT_LISTS" \
    -o Dir::Cache="$APT_CACHE" \
    -o APT::Architecture=arm64 \
    -o APT::Get::Download-Only=true \
    -o APT::Get::AllowUnauthenticated=true \
    -o Acquire::AllowInsecureRepositories=true \
    -o Acquire::Retries=3 \
    download "$@"
}

echo "[1/5] 更新 arm64 软件源..."
for attempt in 1 2 3; do
  if apt-get \
    -o Dir::Etc::sourcelist="$SOURCES_LIST" \
    -o Dir::Etc::sourceparts=- \
    -o Dir::State::Lists="$APT_LISTS" \
    -o Dir::Cache="$APT_CACHE" \
    -o APT::Architecture=arm64 \
    -o APT::Get::AllowUnauthenticated=true \
    -o Acquire::AllowInsecureRepositories=true \
    -o Acquire::Retries=5 \
    update >/dev/null 2>&1; then
    break
  fi
  echo "  apt update 第 $attempt 次失败，重试..."
  sleep 3
done

# 验证软件源可用性（注意：不能用 grep -q，否则 apt-cache 会因 SIGPIPE 被误判失败）
if [ "$(apt-cache \
  -o Dir::Etc::sourcelist="$SOURCES_LIST" \
  -o Dir::State::Lists="$APT_LISTS" \
  -o APT::Architecture=arm64 \
  policy libc6 2>/dev/null | grep -c Candidate)" = "0" ]; then
  echo "错误：apt 软件源不可用（无法获取 libc6 的 arm64 包）。请检查网络后重试。"
  exit 1
fi

# 运行 ASF 自包含 .NET 所需的运行库 + 基础 shell 工具
PACKAGES=(
  libc6 libgcc-s1 libstdc++6 libicu74 zlib1g libssl3t64
  liblzma5 libzstd1 libsqlite3-0 libpcre2-8-0 libcap2 libffi8
  libgomp1 libattr1 libacl1 libbz2-1.0 liblz4-1 libgcrypt20 libgpg-error0
  libexpat1 libselinux1 libsepol2 libmount1 libblkid1 libuuid1
  libunistring5 libidn2-0 libsystemd0 libpam0g libaudit1 libapparmor1
  libncursesw6 libtinfo6 libreadline8t64
  dash bash coreutils tar gzip sed grep findutils procps ca-certificates
)

echo "[2/5] 下载 arm64 基础包..."
pushd "$ROOTFS_DIR" >/dev/null
# 先筛选出源中存在的包，再一次性批量下载（比逐个 apt-get download 快很多）
AVAILABLE_PACKAGES=()
for pkg in "${PACKAGES[@]}"; do
  if [ "$(apt-cache \
    -o Dir::Etc::sourcelist="$SOURCES_LIST" \
    -o Dir::State::Lists="$APT_LISTS" \
    -o APT::Architecture=arm64 \
    policy "$pkg" 2>/dev/null | grep -c Candidate)" != "0" ]; then
    AVAILABLE_PACKAGES+=("$pkg")
  else
    echo "  跳过（源中不存在）: $pkg"
  fi
done

if [ "${#AVAILABLE_PACKAGES[@]}" -gt 0 ]; then
  apt_download "${AVAILABLE_PACKAGES[@]}"
fi

# 解压所有 .deb（先校验完整性，损坏的包重新下载一次）
for deb in ./*.deb; do
  [ -e "$deb" ] || continue
  if ! dpkg-deb --info "$deb" >/dev/null 2>&1; then
    echo "  包损坏，重新下载: $(basename "$deb")"
    pkg_name="${deb%.deb}"
    rm -f "$deb"
    apt_download "$pkg_name" || { echo "  重新下载失败，跳过: $pkg_name"; continue; }
    deb="./${pkg_name}.deb"
    dpkg-deb --info "$deb" >/dev/null 2>&1 || { echo "  包仍损坏，跳过: $pkg_name"; continue; }
  fi
  echo "  解压: $(basename "$deb")"
  dpkg-deb -x "$deb" .
done
rm -f ./*.deb
popd >/dev/null

# ---------------------------------------------------------------------------
# 2. 下载并解压 ASF linux-arm64
# ---------------------------------------------------------------------------
echo "[3/5] 下载 ASF ($ASF_VERSION) linux-arm64..."
if [ "$ASF_VERSION" = "latest" ]; then
  # 优先用 GitHub API；若 API 限流，则从 releases 页面解析最新标签
  ASF_VERSION="$(curl -fsSL --retry 3 "https://api.github.com/repos/JustArchiNET/ArchiSteamFarm/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')"
  if [ -z "$ASF_VERSION" ]; then
    ASF_VERSION="$(
      curl -fsSL --retry 3 -A "Mozilla/5.0" \
        "https://github.com/JustArchiNET/ArchiSteamFarm/releases/latest" \
        | grep -oE 'releases/tag/[0-9][^"?#]*' | head -1 | cut -d/ -f3
    )"
  fi
fi
[ -n "$ASF_VERSION" ] || { echo "无法获取 ASF 版本号，请设置 ASF_VERSION=具体版本号 后重试"; exit 1; }

ASF_URL="https://github.com/JustArchiNET/ArchiSteamFarm/releases/download/${ASF_VERSION}/ASF-linux-arm64.zip"
ASF_ZIP="${OUT_DIR}/ASF-linux-arm64.zip"
# 断点续传 + HTTP/1.1 + 多重试，应对 GitHub 下载不稳定
download_from() {
  local url="$1"
  # 带超时与最低速度限制，避免网络挂起导致无限等待
  curl -fL --http1.1 --retry 5 --retry-all-errors --retry-delay 3 -C - \
    --connect-timeout 30 --max-time 600 --speed-time 30 --speed-limit 1024 \
    -o "$ASF_ZIP" "$url"
}
echo "  下载 ASF 压缩包（直连 GitHub）..."
ASF_DOWNLOAD_OK=0
for asf_attempt in 1 2; do
  if download_from "$ASF_URL"; then
    ASF_DOWNLOAD_OK=1
    break
  fi
  echo "  直连失败（第 $asf_attempt 次），尝试 GitHub 代理..."
  rm -f "$ASF_ZIP"
  sleep 3
  for proxy in "https://gh-proxy.com/" "https://ghfast.top/"; do
    echo "  尝试代理: $proxy"
    if download_from "${proxy}${ASF_URL}"; then
      ASF_DOWNLOAD_OK=1
      break 2
    fi
    rm -f "$ASF_ZIP"
  done
  if [ "$asf_attempt" -eq 1 ]; then
    echo "  首次下载序列失败，等待 10 秒后整体重试..."
    sleep 10
  fi
done
if [ "$ASF_DOWNLOAD_OK" -ne 1 ]; then
  echo "错误：ASF 下载失败（直连与代理均不可用）。可手动下载并设置 ASF_VERSION 后重试。"
  exit 1
fi
echo "  解压 ASF 到 rootfs/asf ..."
mkdir -p "${ROOTFS_DIR}/asf"
unzip -oq "$ASF_ZIP" -d "${ROOTFS_DIR}/asf"
rm -f "$ASF_ZIP"
# 确保主程序可执行（unzip 可能不保留 zip 中的可执行位）
chmod +x "${ROOTFS_DIR}/asf/ArchiSteamFarm"
[ -x "${ROOTFS_DIR}/asf/ArchiSteamFarm" ] || { echo "ASF 解压后未找到 ArchiSteamFarm 可执行文件"; exit 1; }

# ---------------------------------------------------------------------------
# 3. 下载 Termux 原生 proot 及其依赖
# ---------------------------------------------------------------------------
echo "[4/5] 下载 proot (Android arm64)..."
TERMUX_BASE="https://packages.termux.dev/apt/termux-main/pool/main"
curl -fL --retry 3 --connect-timeout 20 --max-time 300 -o "${OUT_DIR}/proot.deb" \
  "${TERMUX_BASE}/p/proot/proot_5.1.107.96_aarch64.deb"
curl -fL --retry 3 --connect-timeout 20 --max-time 300 -o "${OUT_DIR}/libandroid-shmem.deb" \
  "${TERMUX_BASE}/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
curl -fL --retry 3 --connect-timeout 20 --max-time 300 -o "${OUT_DIR}/libtalloc.deb" \
  "${TERMUX_BASE}/libt/libtalloc/libtalloc_2.5.0_aarch64.deb"

# 解压 proot 到独立目录（最终放入 APK jniLibs）
declare -A PROOT_MAP=(
  ["${OUT_DIR}/proot.deb"]="$PROOT_DIR"
  ["${OUT_DIR}/libandroid-shmem.deb"]="$PROOT_DIR"
  ["${OUT_DIR}/libtalloc.deb"]="$PROOT_DIR"
)
for deb in "${!PROOT_MAP[@]}"; do
  dpkg-deb -x "$deb" "${PROOT_MAP[$deb]}"
done
rm -f "${OUT_DIR}/proot.deb" "${OUT_DIR}/libandroid-shmem.deb" "${OUT_DIR}/libtalloc.deb"

# Termux 包解压后位于 data/data/com.termux/files/usr/ 下，规整到 PROOT_DIR 根
if [ -d "$PROOT_DIR/data/data/com.termux/files/usr" ]; then
  cp -a "$PROOT_DIR/data/data/com.termux/files/usr/." "$PROOT_DIR/"
  rm -rf "$PROOT_DIR/data"
fi

# proot 的 RUNPATH 指向 Termux 目录，改为 $ORIGIN 以便在 jniLibs 目录中直接找到依赖
PROOT_BIN="$PROOT_DIR/bin/proot"
if [ -f "$PROOT_BIN" ]; then
  patchelf --set-rpath '$ORIGIN' "$PROOT_BIN"
  # AGP 打包 jniLibs 时只保留 *.so，把 NEEDED 从 libtalloc.so.2 改为 libtalloc.so
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$PROOT_BIN"
  chmod 755 "$PROOT_BIN"
fi

# libtalloc：把真实库的 SONAME 改为 libtalloc.so，并删除版本化符号链接
TALLOC_REAL="$PROOT_DIR/lib/libtalloc.so.2.5.0"
if [ -f "$TALLOC_REAL" ]; then
  patchelf --set-soname libtalloc.so "$TALLOC_REAL"
  rm -f "$PROOT_DIR/lib/libtalloc.so" "$PROOT_DIR/lib/libtalloc.so.2"
fi

# ---------------------------------------------------------------------------
# 4. 整理 rootfs：符号链接 / 目录 / 权限
# ---------------------------------------------------------------------------
echo "[5/5] 整理 rootfs 目录结构..."
# Ubuntu (noble) 采用 usrmerge：核心包解压后位于 usr/ 下，
# 需要把 /bin /lib 等链接到 /usr 对应目录，使 /lib/ld-linux-aarch64.so.1 可解析。
[ -e "$ROOTFS_DIR/bin" ] || ln -s usr/bin "$ROOTFS_DIR/bin"
[ -e "$ROOTFS_DIR/sbin" ] || ln -s usr/sbin "$ROOTFS_DIR/sbin"
[ -e "$ROOTFS_DIR/lib" ] || ln -s usr/lib "$ROOTFS_DIR/lib"
[ -e "$ROOTFS_DIR/lib64" ] || ln -s usr/lib64 "$ROOTFS_DIR/lib64"
# /bin/sh -> dash
if [ -x "$ROOTFS_DIR/usr/bin/dash" ] && [ ! -e "$ROOTFS_DIR/usr/bin/sh" ]; then
  ln -sf dash "$ROOTFS_DIR/usr/bin/sh"
fi
# /etc 基础文件：passwd/group/resolv.conf/hosts
mkdir -p "$ROOTFS_DIR/etc" "$ROOTFS_DIR/tmp" "$ROOTFS_DIR/etc/ssl/certs"
cat > "$ROOTFS_DIR/etc/passwd" <<'PASSWD'
root:x:0:0:root:/root:/bin/sh
daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
PASSWD
cat > "$ROOTFS_DIR/etc/group" <<'GROUP'
root:x:0:
daemon:x:1:
nogroup:x:65534:
GROUP
cat > "$ROOTFS_DIR/etc/resolv.conf" <<'RESOLV'
nameserver 223.5.5.5
nameserver 8.8.8.8
RESOLV
cat > "$ROOTFS_DIR/etc/hosts" <<'HOSTS'
127.0.0.1 localhost
::1 localhost ip6-localhost
HOSTS
chmod 1777 "$ROOTFS_DIR/tmp"

# CA 证书（.NET 在 Linux 上使用 OpenSSL，需要系统证书链做 HTTPS 校验）
echo "  下载 Mozilla CA 证书..."
if ! curl -fL --http1.1 --retry 5 --retry-all-errors --retry-delay 3 \
  --connect-timeout 20 --max-time 300 \
  -o "$ROOTFS_DIR/etc/ssl/certs/ca-certificates.crt" \
  "https://curl.se/ca/cacert.pem"; then
  echo "  警告：CA 证书下载失败，ASF 可能无法连接 Steam（可手动放入 $ROOTFS_DIR/etc/ssl/certs/ca-certificates.crt）"
fi

# 常见空目录
for d in dev proc sys var/run var/log; do
  mkdir -p "$ROOTFS_DIR/$d"
done

# 打包 rootfs（--dereference 把符号链接替换为真实文件/硬链接，
# 避免 Android 设备上 SELinux 禁止创建符号链接导致 rootfs 不可用）
echo "打包 $TARBALL ..."
tar --dereference -C "$ROOTFS_DIR" -czf "$TARBALL" .

echo
echo "构建完成："
echo "  rootfs 压缩包: $TARBALL"
echo "  proot 原生文件: $PROOT_DIR/"
du -sh "$TARBALL" "$PROOT_DIR" 2>/dev/null || true