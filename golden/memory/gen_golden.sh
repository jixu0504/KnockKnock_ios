#!/usr/bin/env bash
# T24 记忆召回/嵌入金样生成器 —— 金样必须由 v1.14.27-android-baseline tag 的 Android oracle 代码生成。
#
# 流程：
#   1. git show 取 baseline tag 的 Android oracle 源码（app/viewmodel/shared/MemoryV2Pipeline.kt 等）
#   2. 机械转换（package/import、repository 参数置空）后注入 shared/src/androidUnitTest 一次性 harness
#   3. 用固定 fixtures 运行 oracle 代码，把输出写进 ios-port/golden/memory/{recall/*.md,embedding_local.json}
#   4. 删除 harness（不进入产品代码，git 不提交）
#
# 禁止：用迁移后的 shared 实现生成金样（防自证）。任何字节差异重跑本脚本，禁止手改金样。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"          # default-workspace
REPO="$ROOT/RhodesLink"
TAG="v1.14.27-android-baseline"
APP="app/src/main/java/com/rhodes/privatechat/viewmodel/shared"
ORACLE_PKG="com/rhodes/privatechat/shared/memory/oracle"
ORACLE_DIR="$REPO/shared/src/androidUnitTest/kotlin/$ORACLE_PKG"
SHIM_DIR="$REPO/shared/src/androidUnitTest/kotlin/com/rhodes/privatechat/util"

cleanup() { rm -rf "$ORACLE_DIR" "$SHIM_DIR/DebugLogger.kt"; }
trap cleanup EXIT

echo "== 1/4 从 baseline tag $TAG 提取 oracle 源码 =="
mkdir -p "$ORACLE_DIR" "$SHIM_DIR"
git -C "$REPO" show "$TAG:$APP/MemoryV2Pipeline.kt" > /tmp/t24_oracle_pipeline.kt
git -C "$REPO" show "$TAG:$APP/UnifiedMemoryContext.kt" > /tmp/t24_oracle_unified.kt
git -C "$REPO" show "$TAG:$APP/MemoryPrivacyPolicy.kt" > /tmp/t24_oracle_privacy.kt
git -C "$REPO" show "$TAG:$APP/MemoryVectorFormatter.kt" > /tmp/t24_oracle_formatter.kt
git -C "$REPO" show "$TAG:$APP/MemoryPromotionLocks.kt" > /tmp/t24_oracle_locks.kt
git -C "$REPO" show "$TAG:shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/vector/LocalHashEmbeddingGateway.kt" > /tmp/t24_oracle_hash.kt

python3 - "$REPO" <<'PY'
import re, sys, os
repo = sys.argv[1]
pkg = "com.rhodes.privatechat.shared.memory.oracle"
oracle = os.path.join(repo, "shared/src/androidUnitTest/kotlin/com/rhodes/privatechat/shared/memory/oracle")

def rd(name): return open("/tmp/t24_" + name + ".kt", encoding="utf-8").read()
def wr(name, text): open(os.path.join(oracle, name), "w", encoding="utf-8").write(text)

def setpkg(s, extra_imports=""):
    s = re.sub(r"^package .*$", "package " + pkg, s, count=1, flags=re.M)
    if extra_imports:
        s = s.replace("package " + pkg + "\n", "package " + pkg + "\n" + extra_imports, 1)
    return s

# 1) MemoryV2Pipeline：package 变更 + Repository 参数置空（召回路径不触碰，stable/relationship 仍走 oracle 原逻辑）
s = rd("oracle_pipeline")
s = setpkg(s, "import com.rhodes.privatechat.shared.prompt.MemoryV2PromptTemplates\n")
s = s.replace("    private val repository: ChatRepository,", "    private val repository: ChatRepository? = null,")
s = s.replace("repository.", "repository!!.")
# 固定 Clock 注入：oracle 用 System.currentTimeMillis()（wall clock）。生产实现按规格卡用 Clock 注入，
# 金样必须可复现，这里只做等价的机械替换（无业务语义变化），时间值由 harness 用 fixtures.now 设定。
s = s.replace("System.currentTimeMillis()", "OracleFixedClock.now")
s = s.rstrip() + """

/** 金样 harness 固定时钟（等价生产 MemoryV2Pipeline(clock) 注入；由 harness 设为 fixtures.now）。 */
object OracleFixedClock {
    var now: Long = 0L
}
"""

wr("MemoryV2Pipeline.kt", s)

# 2) 同包依赖类（baseline 原文，仅 package 变更）
for name, out in [("oracle_unified","UnifiedMemoryContext.kt"), ("oracle_privacy","MemoryPrivacyPolicy.kt"),
                  ("oracle_formatter","MemoryVectorFormatter.kt"), ("oracle_locks","MemoryPromotionLocks.kt")]:
    wr(out, setpkg(rd(name)))

# 3) LocalHashEmbeddingGateway：baseline 原文，仅改 package/类名以避开 shared 同名类
s = setpkg(rd("oracle_hash"), "import com.rhodes.privatechat.shared.vector.EmbeddingGateway\n")
s = s.replace("class LocalHashEmbeddingGateway(", "class OracleLocalHashEmbeddingGateway(")
wr("OracleLocalHashEmbeddingGateway.kt", s)
print("oracle sources ->", oracle)
PY

echo "== 2/4 注入一次性 harness（DebugLogger no-op 替身 + 金样生成测试）=="
cp "$HERE/harness/DebugLoggerShim.kt" "$SHIM_DIR/DebugLogger.kt"
cp "$HERE/harness/MemoryGoldenGenTest.kt" "$ORACLE_DIR/MemoryGoldenGenTest.kt"

echo "== 3/4 运行 oracle 生成金样（gradle 排队：T23 并行时等待）=="
cd "$REPO"
source "$ROOT/ios-port/toolchain/env.sh"
for i in $(seq 1 60); do
  if ! pgrep -fl "GradleDaemon\|gradlew" >/dev/null 2>&1; then break; fi
  echo "  gradle busy, sleep 30 (${i}/60)"; sleep 30
done
MEMORY_GOLDEN_DIR="$HERE" ./gradlew :shared:testDebugUnitTest --tests "*MemoryGoldenGen*" --no-daemon

echo "== 4/4 金样清单 =="
ls -l "$HERE/recall" "$HERE/embedding_local.json"
sha256sum "$HERE"/recall/*.md "$HERE"/embedding_local.json > "$HERE/.golden-manifest.sha256"
cat "$HERE/.golden-manifest.sha256"
