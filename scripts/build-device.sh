#!/usr/bin/env bash
#
# build-device.sh — 构建/安装 RhodesLink 到连接的物理 iPhone
#
# 前置条件（需要人工一次性完成）：
#   1. iPhone 用数据线连接 Mac，并在手机上「信任此电脑」；
#   2. iPhone 设置 → 隐私与安全性 → 开发者模式 → 打开（iOS 16+ 必需，需重启）；
#   3. Xcode → Settings → Accounts 登录 Apple ID（免费个人团队即可）；
#   4. 首次构建时 Xcode 会要求为该设备注册开发证书（在 Xcode 里 Run 一次最简单）。
#
# 用法：
#   ./build-device.sh                 # 自动检测设备 + 自动签名构建 + 安装
#   DEVELOPMENT_TEAM=ABC123456 ./build-device.sh   # 显式指定 team
#
# 说明：免费 Apple ID 签名的 app 7 天过期，过期后重跑本脚本即可（重签重装）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # workspace 根
REPO="$ROOT/RhodesLink"
ENV_SH="$ROOT/ios-port/toolchain/env.sh"
PROJECT="$REPO/iosApp/RhodesLink.xcodeproj"
FRAMEWORK_DIR="$REPO/shared/build/bin/iosArm64/releaseFramework"

echo "== 1/5 检查设备 =="
DEVICE_JSON="$(xcrun devicectl list devices --json-output - 2>/dev/null || true)"
if ! echo "$DEVICE_JSON" | grep -q '"hardwareProperties"'; then
  echo "❌ 没有检测到物理 iPhone。请用数据线连接并信任此电脑。"
  echo "   （模拟器不属于实机测试）"
  exit 1
fi
echo "✅ 检测到物理设备（详见上方 devicectl 输出）"

echo "== 2/5 检查签名身份 =="
if ! security find-identity -v -p codesigning 2>/dev/null | grep -q "valid identities found" ; then
  echo "❌ 本机没有代码签名证书。"
  echo "   请在 Xcode → Settings → Accounts 登录 Apple ID（免费个人团队），"
  echo "   然后在 Xcode 里对本工程 Run 一次以生成证书与描述文件。"
  exit 1
fi
if [[ -z "${DEVELOPMENT_TEAM:-}" ]]; then
  # 优先从工程 pbxproj 读取（Xcode 配置签名时写入）；注意：证书 identity 括号里的是证书 ID，不是 Team ID
  TEAM="$(grep -m1 'DEVELOPMENT_TEAM = ' "$PROJECT/project.pbxproj" 2>/dev/null | sed 's/.*DEVELOPMENT_TEAM = \(.*\);.*/\1/')"
  if [[ -z "$TEAM" ]]; then
    echo "❌ 工程未配置 Team。请在 Xcode → Signing & Capabilities 选择 Team，或显式传入 DEVELOPMENT_TEAM=XXXXXX ./build-device.sh"
    exit 1
  fi
  export DEVELOPMENT_TEAM="$TEAM"
fi
echo "✅ 签名 team: $DEVELOPMENT_TEAM"

echo "== 3/5 检查 device framework =="
if [[ ! -d "$FRAMEWORK_DIR/Shared.framework" ]]; then
  echo "… 缺少 iosArm64 release framework，先构建"
  source "$ENV_SH"
  ( cd "$REPO" && ./gradlew --no-daemon :shared:linkReleaseFrameworkIosArm64 )
fi
echo "✅ Shared.framework(iosArm64) 就绪"

echo "== 4/5 xcodebuild（自动签名，首次会用 -allowProvisioningUpdates 注册设备）=="
cd "$REPO/iosApp"
xcodebuild build \
  -project "$PROJECT" \
  -scheme RhodesLink \
  -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$ROOT/ios-port/build-device/DerivedData" \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
  CODE_SIGN_STYLE=Automatic \
  | tail -25

APP_PATH="$ROOT/ios-port/build-device/DerivedData/Build/Products/Debug-iphoneos/RhodesLink.app"
[[ -d "$APP_PATH" ]] || { echo "❌ 构建产物未找到: $APP_PATH"; exit 1; }

echo "== 5/5 安装到设备 =="
DEVICE_UDID="$(xcrun devicectl list devices --json-output - 2>/dev/null | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    for dev in d.get("result",{}).get("devices",[]):
        if str(dev.get("hardwareProperties",{}).get("platform","")).lower()!="simulator":
            print(dev.get("identifier"))
            break
except Exception:
    pass
')"
if [[ -z "$DEVICE_UDID" ]]; then
  echo "⚠️ 无法解析物理设备 UDID，请手动安装："
  echo "   xcrun devicectl device install app --device <UDID> \"$APP_PATH\""
  exit 0
fi
xcrun devicectl device install app --device "$DEVICE_UDID" "$APP_PATH"
echo "✅ 已安装。首次启动需在 iPhone 设置 → 通用 → VPN与设备管理 中信任开发者证书。"
echo "   免费 Apple ID 签名 7 天过期，过期后重跑本脚本。"
