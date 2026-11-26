#!/usr/bin/env bash
# 把 ios-port/golden/memory 金样同步进测试 resources（commonTest 供双路径；androidUnitTest 供 JVM classpath），
# 并跑金样门禁测试。用法：./sync_golden.sh [--sync-only]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REPO="$ROOT/RhodesLink"
COMMON_RES="$REPO/shared/src/commonTest/resources/golden/memory"
JVM_RES="$REPO/shared/src/androidUnitTest/resources/golden/memory"

rm -rf "$COMMON_RES" "$JVM_RES"
mkdir -p "$COMMON_RES/recall" "$JVM_RES/recall"
cp "$HERE/fixtures.json" "$COMMON_RES/"; cp "$HERE/fixtures.json" "$JVM_RES/"
cp "$HERE/embedding_local.json" "$COMMON_RES/"; cp "$HERE/embedding_local.json" "$JVM_RES/"
cp "$HERE"/recall/*.md "$COMMON_RES/recall/"; cp "$HERE"/recall/*.md "$JVM_RES/recall/"
echo "synced $(ls "$COMMON_RES/recall" | wc -l | tr -d ' ') recall goldens + embedding_local.json"

cd "$REPO"
source "$ROOT/ios-port/toolchain/env.sh"
for i in $(seq 1 60); do
  if ! pgrep -fl "GradleDaemon\|gradlew" >/dev/null 2>&1; then break; fi
  echo "  gradle busy, sleep 30 (${i}/60)"; sleep 30
done
if [ "${1:-}" = "--sync-only" ]; then exit 0; fi
./gradlew :shared:testDebugUnitTest --tests "*MemoryRecallGolden*" --no-daemon
