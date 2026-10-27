#!/bin/sh
# 打包 KernelSU 模块：带上管理 App（先在 ../HyperAdCleanApp 执行 ./gradlew assembleRelease），排除开发用 mock.js
cd "$(dirname "$0")"
APK=../HyperAdCleanApp/app/build/outputs/apk/release/app-release.apk
if [ -f "$APK" ]; then
  mkdir -p app
  cp -f "$APK" app/HyperAdClean.apk
  grep -o '"versionCode": *[0-9]*' ../HyperAdCleanApp/app/build/outputs/apk/release/output-metadata.json | grep -o '[0-9]*$' > app/versionCode
fi
VER=$(grep '^version=' module.prop | cut -d= -f2)
OUT=../HyperAdClean-$VER.zip
rm -f "$OUT"
# 不打包 webroot：KernelSU 看到 webroot 才显示 WebUI 入口，设置统一走澎湃小工具
zip -r -X "$OUT" module.prop customize.sh post-fs-data.sh service.sh uninstall.sh adclean.sh hosts app devmodel battery -x '*.DS_Store'
echo "$OUT"
