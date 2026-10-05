#!/usr/bin/env bash
#
# build-all.sh —— 一键完成：rootfs 构建 -> 资源放入工程 -> APK 构建
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "===== [1/3] 构建 rootfs + proot ====="
bash "$HERE/build-rootfs.sh"

echo
echo "===== [2/3] 准备 App 资源 ====="
bash "$HERE/prepare-assets.sh"

echo
echo "===== [3/3] 构建 APK ====="
bash "$HERE/build-apk.sh"

echo
echo "全部完成。"