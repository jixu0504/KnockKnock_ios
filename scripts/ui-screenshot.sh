#!/usr/bin/env bash
#
# ui-screenshot.sh — 模拟器构建 / 安装 / 启动 / 截图（敲敲 UI 改版验收用）
#
# 用法：
#   scripts/ui-screenshot.sh <输出.png> [--dark|--light] [--launch-arg <arg>] [--device "iPhone 17"] [--wait N] [--no-build]
#
# 说明：
#   - derivedData 默认 .build-sim，可用环境变量 DERIVED_DIR 覆盖（队友各自独立编译时建议设置）；
#   - 主题通过 `xcrun simctl ui <udid> appearance dark|light` 切换；
#   - --launch-arg 可传 `--knock-gallery`、`--selftest` 等自检/演示参数（可重复）；
#   - 截图前等待 N 秒让 UI 稳定（默认 2 秒）；
#   - 内置串行锁（mkdir 自旋），多个智能体并发截图会自动排队，不会互相踩模拟器。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/ios/KnockKnock.xcodeproj"
DERIVED="${DERIVED_DIR:-$ROOT/.build-sim}"
BUNDLE_ID="com.xsbcjx.knockknock"
OUT="${1:-$ROOT/screenshots/ui-shot.png}"
shift || true

APPEARANCE="dark"
DEVICE_FILTER="iPhone 17"
LAUNCH_ARGS=()
DO_BUILD=1
WAIT_SECONDS=2

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dark|--light) APPEARANCE="${1#--}"; shift ;;
    --launch-arg) LAUNCH_ARGS+=("$2"); shift 2 ;;
    --device) DEVICE_FILTER="$2"; shift 2 ;;
    --wait) WAIT_SECONDS="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    --derived) DERIVED="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

mkdir -p "$(dirname "$OUT")"

# ── 串行锁（macOS 无 flock，用 mkdir 自旋；最长等 6 分钟）──
LOCKDIR=/tmp/knock-ui-screenshot.lock
acquired=0
for _ in $(seq 1 180); do
  if mkdir "$LOCKDIR" 2>/dev/null; then acquired=1; break; fi
  sleep 2
done
if [[ "$acquired" != "1" ]]; then
  echo "❌ 等待截图锁超时（$LOCKDIR 被占用）"; exit 1
fi
cleanup() { rmdir "$LOCKDIR" 2>/dev/null || true; }
trap cleanup EXIT

echo "== 1/4 构建（模拟器） =="
if [[ "$DO_BUILD" == "1" ]]; then
  xcodebuild build \
    -project "$PROJECT" \
    -scheme KnockKnock \
    -configuration Debug \
    -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$DERIVED" \
    | tail -5
fi
APP_PATH="$DERIVED/Build/Products/Debug-iphonesimulator/KnockKnock.app"
if [[ ! -d "$APP_PATH" ]]; then
  echo "❌ 构建产物不存在: $APP_PATH"
  exit 1
fi

echo "== 2/4 解析/启动模拟器 =="
UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
data = json.load(sys.stdin)["devices"]
want = sys.argv[1]
for _runtime, devices in data.items():
    for dev in devices:
        if want in dev.get("name", ""):
            print(dev["udid"])
            raise SystemExit
' "$DEVICE_FILTER")"

if [[ -z "${UDID:-}" ]]; then
  echo "❌ 没找到包含 '$DEVICE_FILTER' 的模拟器（先确认 runtime 已安装）"
  exit 1
fi
xcrun simctl boot "$UDID" 2>/dev/null || true
open -a Simulator >/dev/null 2>&1 || true
xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1 || true

echo "== 3/4 安装/启动（appearance=${APPEARANCE}） =="
xcrun simctl ui "$UDID" appearance "$APPEARANCE" >/dev/null 2>&1 || true
xcrun simctl install "$UDID" "$APP_PATH"
xcrun simctl terminate "$UDID" "$BUNDLE_ID" >/dev/null 2>&1 || true
if [[ ${#LAUNCH_ARGS[@]} -gt 0 ]]; then
  xcrun simctl launch "$UDID" "$BUNDLE_ID" "${LAUNCH_ARGS[@]}" >/dev/null
else
  xcrun simctl launch "$UDID" "$BUNDLE_ID" >/dev/null
fi
sleep "$WAIT_SECONDS"

echo "== 4/4 截图 =="
xcrun simctl io "$UDID" screenshot "$OUT"
echo "✅ 已保存: $OUT"