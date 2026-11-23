#!/usr/bin/env bash
# 把 ios-port/golden/backup-full 的 8 个全类目金样同步进测试 resources
# （commonTest 供双路径读取：JVM classpath + iOS test bundle；androidUnitTest 供 JVM classpath），
# 并校验 SHA-256 未被手改 / 生成器可复跑 / 自校验通过。
#
# 用法：
#   bash ios-port/golden/backup-full/sync_golden.sh              # 校验 + 同步 + 跑双路径全类目单测
#   bash ios-port/golden/backup-full/sync_golden.sh --sync-only  # 只校验 + 同步
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
REPO="$ROOT/RhodesLink"
COMMON_RES="$REPO/shared/src/commonTest/resources/golden/backup-full"
JVM_RES="$REPO/shared/src/androidUnitTest/resources/golden/backup-full"

# 金样冻结哈希（README.md §1；改金样必须同改本表 + README + 规格卡 + 测试冻结表）
EXPECTED_HASHES=$(cat <<'EOF'
d9ed1a9513b6b72939d08a0c354a76c72352801b90c413f4e16d12c13a06bbb7  full-all-v2.zip
6cfc6553cf60c85affa07eb91b6b7b4d63fd7a0a8cdae16476ec61aa581a7180  full-empty-v2.zip
5de57cc882eec680262ffffa9aead0c43f15292a04ae1416a3d4d5918314e551  full-extras-v2.zip
a99207f63442c54320471df5d10e49952de42209746257bbaf6a342c8c41ba6a  full-knowledge-v2.zip
e4bdfc060f824ab9689fc258b73686d0f25a137979b011417953e090821c507d  full-media-v2.zip
2fe8d76fdece9915e0b47b9f8569d9982b104625ba5ba4f57516a52019388143  full-memories-v2.zip
561954730942fb9a411f3f77bb36379a6f554b03c4b1e31d98c5acdbb4094f4e  full-social-v2.zip
d1cf1f70c239c5804f121146eaae30d395be25e3a899ecac0cd47eafcc5e6c32  full-v1-single.zip
EOF
)

echo "[sync] golden src: $HERE"
echo "[sync] test resources: $COMMON_RES"
echo "[sync] jvm test resources: $JVM_RES"

# 1) 冻结哈希校验（防生成器漂移/手改）
ACTUAL="$(cd "$HERE" && shasum -a 256 *.zip | sort)"
EXPECTED="$(printf '%s\n' "$EXPECTED_HASHES" | sort)"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "[sync] FAIL: 金样与冻结哈希不一致（请重新运行 make_full_backups.py 并核对）" >&2
  diff <(printf '%s\n' "$EXPECTED") <(printf '%s\n' "$ACTUAL") >&2 || true
  exit 1
fi
echo "[sync] OK: 8 件金样 SHA-256 与冻结表一致"

# 2) 生成器自校验（含确定性逐字节重生成比较）
for ZIP in "$HERE"/*.zip; do
  python3 "$HERE/make_full_backups.py" --verify "$ZIP" >/dev/null
done
echo "[sync] OK: 生成器自校验 8/8 PASS"

# 3) 同步到测试 resources
mkdir -p "$COMMON_RES" "$JVM_RES"
cp "$HERE"/*.zip "$COMMON_RES/"
cp "$HERE"/*.zip "$JVM_RES/"
cp "$HERE/README.md" "$COMMON_RES/README.md"
cp "$HERE/README.md" "$JVM_RES/README.md"
printf '%s\n' "$EXPECTED_HASHES" > "$COMMON_RES/SHA256SUMS"
printf '%s\n' "$EXPECTED_HASHES" > "$JVM_RES/SHA256SUMS"
echo "[sync] OK: 8 件金样已同步（commonTest + androidUnitTest resources）"

if [ "${1:-}" = "--sync-only" ]; then
  exit 0
fi

# 4) 跑双路径全类目金样测试（队列等待其他 gradle 任务，最多 30 分钟）
cd "$REPO"
for i in $(seq 1 60); do
  if pgrep -fl "gradle" >/dev/null 2>&1; then
    echo "[sync] gradle 忙，等待 30s（第 $i 次）"
    sleep 30
  else
    break
  fi
done
source "$ROOT/ios-port/toolchain/env.sh"
./gradlew --no-daemon :shared:testDebugUnitTest --tests "*FullBackupGoldenTestJvm*"
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*FullBackupGoldenTestIos*"
