#!/usr/bin/env bash
#
# build-device.sh — 构建 / 安装 KnockKnock 到已连接的物理 iPhone
# （本仓库布局适配版：仓库根 = rhodeslink_ios/，Xcode 工程在 ios/，KMP framework 在 shared/build/bin/）
#
# 前置条件：
#   1. iPhone 用数据线连接 Mac 并在手机上「信任此电脑」；
#   2. iPhone 设置 → 隐私与安全性 → 开发者模式 → 打开；
#   3. Xcode → Settings → Accounts 登录 Apple ID（免费个人团队即可），工程内已有 CODE_SIGN_STYLE=Automatic；
#   4. 本机已装 JDK21（默认 ~/jdks/jdk-21.0.13.1+2/Contents/Home）。
#
# 用法：
#   ./scripts/build-device.sh                       # 构建 + 安装 + 启动
#   DEVELOPMENT_TEAM=XXXXXXX ./scripts/build-device.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/ios/KnockKnock.xcodeproj"
FRAMEWORK_DIR="$ROOT/shared/build/bin/iosArm64/releaseFramework"
DERIVED="$ROOT/.build-device"
JDK_HOME="${JDK_HOME:-$HOME/jdks/jdk-21.0.13.1+2/Contents/Home}"
GRADLE_BIN="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.10.2-all/ezxzryhqdbf6stsda8vkgso9x/gradle-8.10.2/bin/gradle}"

echo "== 1/5 检查物理设备 =="
DEVICE_JSON="$(xcrun devicectl list devices --json-output - 2>/dev/null || true)"
if ! echo "$DEVICE_JSON" | grep -q '"hardwareProperties"'; then
  echo "❌ 没有检测到物理 iPhone（模拟器不属于真机）。"
  exit 1
fi
DEVICE_UDID="$(echo "$DEVICE_JSON" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    for dev in d.get("result",{}).get("devices",[]):
        if str(dev.get("hardwareProperties",{}).get("platform","")).lower()!="simulator":
            print(dev.get("identifier")); break
except Exception:
    pass
')"
echo "✅ 设备: ${DEVICE_UDID:-unknown}"

echo "== 2/5 检查签名 =="
if ! security find-identity -v -p codesigning 2>/dev/null | grep -q "valid identities found"; then
  echo "❌ 没有代码签名证书。请在 Xcode 登录 Apple ID 并 Run 一次以生成证书。"
  exit 1
fi
if [[ -z "${DEVELOPMENT_TEAM:-}" ]]; then
  TEAM="$(grep -m1 'DEVELOPMENT_TEAM = ' "$PROJECT/project.pbxproj" 2>/dev/null | sed 's/.*DEVELOPMENT_TEAM = \(.*\);.*/\1/' || true)"
  [[ -n "$TEAM" ]] && export DEVELOPMENT_TEAM="$TEAM"
fi
echo "✅ team: ${DEVELOPMENT_TEAM:-<工程内配置>}"

echo "== 3/5 检查 Shared.framework（iosArm64 release） =="
if [[ ! -d "$FRAMEWORK_DIR/Shared.framework" ]]; then
  echo "… 缺少 framework，使用 Gradle 构建（约 1–3 分钟）"
  ( cd "$ROOT" && JAVA_HOME="$JDK_HOME" "$GRADLE_BIN" --no-daemon --console=plain :shared:linkReleaseFrameworkIosArm64 )
fi
echo "✅ framework: $FRAMEWORK_DIR/Shared.framework"

echo "== 4/5 xcodebuild =="
cd "$ROOT/ios"
xcodebuild build \
  -project "$PROJECT" \
  -scheme KnockKnock \
  -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$DERIVED" \
  -allowProvisioningUpdates \
  ${DEVELOPMENT_TEAM:+DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM"} \
  | tail -20

APP_PATH="$DERIVED/Build/Products/Debug-iphoneos/KnockKnock.app"
[[ -d "$APP_PATH" ]] || { echo "❌ 构建产物未找到: $APP_PATH"; exit 1; }

echo "== 5/5 安装并启动 =="
if [[ -n "${DEVICE_UDID:-}" ]]; then
  xcrun devicectl device install app --device "$DEVICE_UDID" "$APP_PATH"
  xcrun devicectl device process launch --device "$DEVICE_UDID" com.xsbcjx.knockknock || true
else
  echo "⚠️ 无法解析设备 UDID，请手动安装："
  echo "   xcrun devicectl device install app --device <UDID> \"$APP_PATH\""
fi
echo "✅ 完成"