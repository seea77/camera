#!/usr/bin/env bash
# 构建签名 release 包（R8 混淆 + 资源裁剪 + 独立签名）。
# 需要先在仓库根目录准备 keystore.properties（复制 keystore.properties.example 填写），
# 并把签名密钥 shotlab-release.keystore 放到仓库根目录（二者均被 .gitignore 忽略，不入库）。
set -e
cd "$(dirname "$0")"

if [ -z "$ANDROID_SDK_ROOT" ] && [ -f local.properties ]; then
  export ANDROID_SDK_ROOT=$(grep -E "^sdk.dir=" local.properties | cut -d= -f2)
fi

if [ ! -f keystore.properties ]; then
  echo "未找到 keystore.properties，无法打签名包。" >&2
  echo "请复制 keystore.properties.example 为 keystore.properties 并填写密钥信息。" >&2
  exit 1
fi

./gradlew clean assembleRelease

APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
mkdir -p dist
cp "$APK" "dist/ShotLab-release.apk"
echo "已输出：dist/ShotLab-release.apk"
