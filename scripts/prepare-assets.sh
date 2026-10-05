#!/usr/bin/env bash
#
# prepare-assets.sh
# 把 build-rootfs.sh 生成的产物放入 Android 工程：
#   - dist/asf-rootfs-arm64.tar.gz      -> app/src/main/assets/asf/rootfs/
#   - dist/proot-arm64/{proot, loader, loader32, lib*.so}
#                                       -> app/src/main/jniLibs/arm64-v8a/
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$HERE/.." && pwd)"
OUT_DIR="${OUT_DIR:-$(pwd)/dist}"

ASSETS_DIR="$PROJECT_ROOT/android/app/src/main/assets/asf/rootfs"
JNILIBS_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$ASSETS_DIR" "$JNILIBS_DIR"

# rootfs 压缩包
ROOTFS_TARBALL="$OUT_DIR/asf-rootfs-arm64.tar.gz"
if [ ! -f "$ROOTFS_TARBALL" ]; then
  echo "错误: 未找到 $ROOTFS_TARBALL，请先运行 scripts/build-rootfs.sh"
  exit 1
fi
# 注意：AAPT2 打包 assets 时会自动解压 .gz 并改名为 .tar，
# 因此这里直接解压为未压缩的 .tar 放入 assets，避免运行时资源名不匹配。
gzip -dc "$ROOTFS_TARBALL" > "$ASSETS_DIR/asf-rootfs-arm64.tar"
echo "已复制 rootfs(未压缩 tar) -> $ASSETS_DIR/asf-rootfs-arm64.tar"

# proot 原生二进制
PROOT_SRC="$OUT_DIR/proot-arm64"
if [ ! -d "$PROOT_SRC" ]; then
  echo "错误: 未找到 $PROOT_SRC，请先运行 scripts/build-rootfs.sh"
  exit 1
fi

# AGP 打包 jniLibs 时只保留 *.so，因此把 proot/loader 等重命名为 .so 放入 jniLibs：
#   proot            -> libproot.so
#   loader           -> libproot_loader.so
#   loader32         -> libproot_loader32.so
#   libtalloc.so.2.5.0 -> libtalloc.so（SONAME 已在 build-rootfs.sh 中改为 libtalloc.so）
#   libandroid-shmem.so -> libandroid-shmem.so
cp -f "$PROOT_SRC/bin/proot" "$JNILIBS_DIR/libproot.so"
if [ -f "$PROOT_SRC/libexec/proot/loader" ]; then
  cp -f "$PROOT_SRC/libexec/proot/loader" "$JNILIBS_DIR/libproot_loader.so"
fi
if [ -f "$PROOT_SRC/libexec/proot/loader32" ]; then
  cp -f "$PROOT_SRC/libexec/proot/loader32" "$JNILIBS_DIR/libproot_loader32.so"
fi
if [ -f "$PROOT_SRC/lib/libtalloc.so.2.5.0" ]; then
  cp -f "$PROOT_SRC/lib/libtalloc.so.2.5.0" "$JNILIBS_DIR/libtalloc.so"
fi
if [ -f "$PROOT_SRC/lib/libandroid-shmem.so" ]; then
  cp -f "$PROOT_SRC/lib/libandroid-shmem.so" "$JNILIBS_DIR/libandroid-shmem.so"
fi

# 清理不需要进入 APK 的垃圾文件
rm -f "$JNILIBS_DIR"/*.gz
rm -rf "$JNILIBS_DIR/share"

echo "已复制 proot -> $JNILIBS_DIR/"
ls -la "$ASSETS_DIR" "$JNILIBS_DIR"