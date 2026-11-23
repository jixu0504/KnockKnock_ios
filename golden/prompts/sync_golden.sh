#!/usr/bin/env bash
# 金样 diff 门禁同步脚本（T18 impl-prompt）
# 用法：
#   bash ios-port/golden/prompts/sync_golden.sh            # 同步金样 + 跑 JVM 金样测试
#   bash ios-port/golden/prompts/sync_golden.sh --sync-only # 仅同步，不跑测试
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GOLDEN_DIR="$SCRIPT_DIR"                                  # ios-port/golden/prompts
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../RhodesLink" && pwd)"   # RhodesLink/
TEST_RES="$REPO_ROOT/shared/src/commonTest/resources/golden/prompts"
# KGP 不把 commonTest resources 合入 Android 单测 classpath（processDebugUnitTestJavaRes 只收
# androidUnitTest），JVM 路径再复制一份；iOS 侧由 commonTest resources 进 test bundle。
TEST_RES_JVM="$REPO_ROOT/shared/src/androidUnitTest/resources/golden/prompts"
RAW_SRC="$GOLDEN_DIR/raw"

echo "[sync] golden src: $RAW_SRC"
echo "[sync] test resources: $TEST_RES"
echo "[sync] jvm test resources: $TEST_RES_JVM"

for DEST in "$TEST_RES" "$TEST_RES_JVM"; do
  mkdir -p "$DEST/raw"
  rsync -a --delete "$RAW_SRC/" "$DEST/raw/"
  cp "$GOLDEN_DIR/allowlist.json" "$DEST/allowlist.json"
  cp "$GOLDEN_DIR/inventory.md" "$DEST/inventory.md"
done

COUNT=$(ls "$TEST_RES/raw" | wc -l | tr -d ' ')
EXPECTED=114
if [ "$COUNT" -ne "$EXPECTED" ]; then
  echo "[sync] FAIL: raw/ 金样件数=$COUNT，期望=$EXPECTED" >&2
  exit 1
fi
echo "[sync] OK: $COUNT 件金样已同步到 commonTest resources"

# 哈希一致性校验：防止金样被手改而 CI 未察觉（相对 sync 脚本目录生成清单）
HASH_MANIFEST="$GOLDEN_DIR/.raw-manifest.sha256"
(cd "$RAW_SRC" && find . -type f -name '*.md' | sort | xargs shasum -a 256) > "$HASH_MANIFEST.new"
if [ -f "$HASH_MANIFEST" ]; then
  if diff -q "$HASH_MANIFEST" "$HASH_MANIFEST.new" >/dev/null 2>&1; then
    echo "[sync] 哈希清单与上次一致"
    rm -f "$HASH_MANIFEST.new"
  else
    echo "[sync] 注意：金样文件相对上次清单有变化（新增/修改）。若是有意再抽取，请 reviewer 确认后删除 $HASH_MANIFEST 重新生成。"
    mv "$HASH_MANIFEST.new" "$HASH_MANIFEST"
  fi
else
  echo "[sync] 首次生成哈希清单 $HASH_MANIFEST"
  mv "$HASH_MANIFEST.new" "$HASH_MANIFEST"
fi

if [ "${1:-}" = "--sync-only" ]; then
  echo "[sync] --sync-only：跳过测试"
  exit 0
fi

# 构建纪律：pgrep 检查 gradle 排队（最多 30 分钟），--no-daemon
if [ -f "$REPO_ROOT/../ios-port/toolchain/env.sh" ]; then
  # shellcheck disable=SC1091
  source "$REPO_ROOT/../ios-port/toolchain/env.sh"
fi
cd "$REPO_ROOT"

for i in $(seq 1 60); do
  if ! pgrep -fl 'gradle' | grep -q .; then break; fi
  echo "[sync] 检测到 gradle 正在运行（可能是并行代理），等待 30s（$i/60）..."
  sleep 30
done

echo "[sync] 运行 :shared:testDebugUnitTest --tests \"*GoldenPrompt*\""
./gradlew :shared:testDebugUnitTest --tests "*GoldenPrompt*" --no-daemon
echo "[sync] 金样 diff 门禁通过"
