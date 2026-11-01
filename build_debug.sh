#!/usr/bin/env bash
# 构建 debug 包（不混淆、不签名，便于真机调试）。需要先在本机配置好 Android SDK。
set -e
cd "$(dirname "$0")"

# 若未设置 ANDROID_SDK_ROOT，尝试从 local.properties 读取（该文件不入库，仅本地用）
if [ -z "$ANDROID_SDK_ROOT" ] && [ -f local.properties ]; then
  export ANDROID_SDK_ROOT=$(grep -E "^sdk.dir=" local.properties | cut -d= -f2)
fi

./gradlew clean assembleDebug

APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
mkdir -p dist
cp "$APK" "dist/ShotLab-debug.apk"
echo "已输出：dist/ShotLab-debug.apk"
