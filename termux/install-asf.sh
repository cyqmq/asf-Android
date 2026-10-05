#!/usr/bin/env bash
#
# install-asf.sh —— 在 Termux 中一键安装并运行 ArchiSteamFarm（备用方案）
#
# 用法（在 Termux 中）：
#   curl -L -O https://raw.githubusercontent.com/<user>/ASFAndroid/main/termux/install-asf.sh
#   chmod +x install-asf.sh && bash install-asf.sh
#
# 原理：proot-distro 安装 Ubuntu，在其中运行 ASF 官方 linux-arm64 版本。
# 完成后 ASF 会自动启动，浏览器打开 http://127.0.0.1:1242 即可访问 ASF-ui。
#
set -euo pipefail

ASF_HOME="${ASF_HOME:-/root/asf}"
ASF_VERSION="${ASF_VERSION:-latest}"

echo "==> 检查 Termux 环境..."
if [ -z "${TERMUX_VERSION:-}" ]; then
  echo "错误：本脚本只能在 Termux 中运行。"
  echo "请从 F-Droid 安装 Termux: https://f-droid.org/packages/com.termux/"
  exit 1
fi

echo "==> 更新软件源并安装 proot-distro..."
pkg update -y
pkg install -y proot-distro curl unzip

echo "==> 安装 Ubuntu（首次较慢）..."
proot-distro install ubuntu || echo "Ubuntu 已存在，跳过安装。"

echo "==> 在 Ubuntu 中安装 ASF 依赖..."
proot-distro login ubuntu -- bash -c '
  set -e
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get upgrade -y
  apt-get install -y ca-certificates unzip libicu74 || apt-get install -y ca-certificates unzip libicu70
'

echo "==> 创建 ASF 目录..."
proot-distro login ubuntu -- bash -c "mkdir -p $ASF_HOME"

if [ "$ASF_VERSION" = "latest" ]; then
  ASF_VERSION="$(
    curl -fsSL "https://api.github.com/repos/JustArchiNET/ArchiSteamFarm/releases/latest" \
      | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p'
  )"
fi
ASF_URL="https://github.com/JustArchiNET/ArchiSteamFarm/releases/download/${ASF_VERSION}/ASF-linux-arm64.zip"

# 下载到宿主机再放进 Ubuntu，避免 proot 内网络问题
echo "==> 下载 ASF $ASF_VERSION (linux-arm64) ..."
curl -fL --retry 3 -o "$HOME/asf.zip" "$ASF_URL"
proot-distro login ubuntu -- bash -c "
  cd $ASF_HOME &&
  unzip -oq /data/data/com.termux/files/home/asf.zip -d $ASF_HOME &&
  chmod +x $ASF_HOME/ArchiSteamFarm
"
rm -f "$HOME/asf.zip"

echo "==> 写入 ASF 默认配置（IPC 端口 1242，Headless）..."
proot-distro login ubuntu -- bash -c "
  mkdir -p $ASF_HOME/config
  cat > $ASF_HOME/config/IPC.config <<'EOF'
{
  \"Kestrel\": {
    \"Endpoints\": {
      \"HTTP\": {
        \"Url\": \"http://127.0.0.1:1242\"
      }
    }
  }
}
EOF
  cat > $ASF_HOME/config/ASF.json <<'EOF'
{
  \"Headless\": true,
  \"AutoUpdates\": true,
  \"CurrentCulture\": \"en-US\"
}
EOF
"

echo "==> 创建启动脚本 asf.sh..."
cat > "$HOME/asf.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# 启动/停止 ASF：bash asf.sh [start|stop|status]
ASF_HOME="$ASF_HOME"
ACTION="\${1:-start}"
case "\$ACTION" in
  start)
    pkill -f "ArchiSteamFarm" 2>/dev/null || true
    nohup proot-distro login ubuntu -- bash -c "cd \$ASF_HOME && ./ArchiSteamFarm --path \$ASF_HOME" > /data/data/com.termux/files/home/asf.log 2>&1 &
    echo "ASF 启动中... 日志: ~/asf.log ｜ WebUI: http://127.0.0.1:1242"
    ;;
  stop)
    pkill -f "ArchiSteamFarm" 2>/dev/null && echo "ASF 已停止。" || echo "ASF 未在运行。"
    ;;
  status)
    if pgrep -f "ArchiSteamFarm" >/dev/null; then echo "ASF 运行中。"; else echo "ASF 未运行。"; fi
    ;;
  *)
    echo "用法: bash asf.sh [start|stop|status]"
    ;;
esac
EOF
chmod +x "$HOME/asf.sh"

echo
echo "==============================================================="
echo " 安装完成！"
echo "  - 启动:   bash asf.sh start"
echo "  - 停止:   bash asf.sh stop"
echo "  - 状态:   bash asf.sh status"
echo "  - WebUI:  http://127.0.0.1:1242  （在浏览器或 ASF-ui 中访问）"
echo "==============================================================="

# 自动启动
"$HOME/asf.sh" start