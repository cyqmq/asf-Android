#!/usr/bin/env bash
#
# build-apk.sh
# 使用 Gradle 构建 Android APK。
# 需要：
#   - JDK 17+
#   - Android SDK（通过 ANDROID_HOME 或 local.properties 指定）
#   - 已先执行 build-rootfs.sh 与 prepare-assets.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$HERE/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"

# Android SDK 定位
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/android-sdk" "/usr/lib/android-sdk" "/opt/android-sdk" "/usr/local/lib/android/sdk"; do
    if [ -d "$candidate" ]; then
      ANDROID_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "错误: 未找到 Android SDK，请设置 ANDROID_HOME 环境变量。"
  exit 1
fi
echo "使用 Android SDK: $ANDROID_HOME"
echo "sdk.dir=$ANDROID_HOME" > "$ANDROID_DIR/local.properties"

# 检查 gradle wrapper jar；若缺失则用系统 gradle 生成
if [ ! -f "$ANDROID_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  if command -v gradle >/dev/null 2>&1; then
    echo "未找到 gradle wrapper jar，使用系统 gradle 生成 wrapper..."
    (cd "$ANDROID_DIR" && gradle wrapper --gradle-version 8.9)
  else
    echo "错误: 缺少 gradle wrapper jar，且系统未安装 gradle。"
    echo "请先安装 Gradle 并运行: cd android && gradle wrapper --gradle-version 8.9"
    exit 1
  fi
fi

echo "开始构建 APK..."
cd "$ANDROID_DIR"
./gradlew assembleDebug "$@"

APK="$(find "$ANDROID_DIR/app/build/outputs/apk" -name "*.apk" | head -1)"
if [ -n "$APK" ]; then
  echo
  echo "构建成功: $APK"
else
  echo "构建失败，未生成 APK。"
  exit 1
fi