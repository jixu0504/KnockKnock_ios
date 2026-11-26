#!/usr/bin/env bash
# 把 ios-port/golden/chat-import 的 4 个聊天导出金样同步进测试 resources
# （commonTest 供双路径读取；androidUnitTest 供 JVM classpath），并校验 SHA-256 未被手改。
#
# 用法：
#   bash ios-port/golden/chat-import/sync_golden.sh              # 同步 + 校验
#   bash ios-port/golden/chat-import/sync_golden.sh --sync-only  # 仅同步
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REPO="$ROOT/RhodesLink"
COMMON_RES="$REPO/shared/src/commonTest/resources/golden/chat-import"
JVM_RES="$REPO/shared/src/androidUnitTest/resources/golden/chat-import"

# 金样冻结哈希（README.md §1；改金样必须同改本表 + README + 规格卡）
EXPECTED_HASHES=$(cat <<'EOF'
178e7abbd6308299be319920f3e764dd5dda6f94e9e2a6ba9d3f868619d8150d  chat-text-v1.zip
0872d3df81847c43120d9fde58ad76147ed4bb752e4884df28e779246717a612  chat-image-v1.zip
629c61b5db0aa52264b545acfa5a56f0cbfc2b80bf6ee89a4993f512d20d1c91  chat-ai-json-v1.zip
19c5f63c89a162cc76633f33074694445b71c6b4548104eb8701d6d33d26f981  chat-2000-v1.zip
EOF
)

echo "[sync] golden src: $HERE"
echo "[sync] test resources: $COMMON_RES"
echo "[sync] jvm test resources: $JVM_RES"

# 1) 生成器可复跑性：重新生成后哈希必须与冻结表一致（防生成器漂移）
ACTUAL="$(cd "$HERE" && shasum -a 256 *.zip | sort)"
EXPECTED="$(printf '%s\n' "$EXPECTED_HASHES" | sort)"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "[sync] FAIL: 金样与冻结哈希不一致（请重新运行 make_chat_exports.py 并核对）" >&2
  diff <(printf '%s\n' "$EXPECTED") <(printf '%s\n' "$ACTUAL") >&2 || true
  exit 1
fi
echo "[sync] OK: 4 件金样 SHA-256 与冻结表一致"

# 2) 自校验（镜像 iOS ChatExportReader 契约）
for ZIP in "$HERE"/*.zip; do
  python3 "$HERE/make_chat_exports.py" --verify "$ZIP" >/dev/null
done
echo "[sync] OK: 生成器自校验 4/4 PASS"

# 3) 同步到测试 resources
mkdir -p "$COMMON_RES" "$JVM_RES"
cp "$HERE"/*.zip "$COMMON_RES/"
cp "$HERE"/*.zip "$JVM_RES/"
cp "$HERE/README.md" "$COMMON_RES/README.md"
cp "$HERE/README.md" "$JVM_RES/README.md"
printf '%s\n' "$EXPECTED_HASHES" > "$COMMON_RES/SHA256SUMS"
printf '%s\n' "$EXPECTED_HASHES" > "$JVM_RES/SHA256SUMS"
echo "[sync] OK: 4 件金样已同步（commonTest + androidUnitTest resources）"

if [ "${1:-}" = "--sync-only" ]; then
  exit 0
fi

# 4) 跑双路径金样测试（队列等待其他 gradle 任务，最多 30 分钟）
cd "$REPO"
source "$ROOT/ios-port/toolchain/env.sh"
for i in $(seq 1 60); do
  if ! pgrep -fl "GradleDaemon\|gradlew" >/dev/null 2>&1; then break; fi
  echo "  gradle busy, sleep 30 (${i}/60)"; sleep 30
done
./gradlew --no-daemon :shared:testDebugUnitTest --tests "*ChatImportGolden*"
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*ChatImportGolden*"
echo "[sync] OK: 双路径金样测试完成"
