# T26 接线批 A：app 叶子逻辑委托 shared（handoff）

- 任务：`task-26`（Phase 1 接线批 A，Gate 1 关键）
- 代理：impl-wiring-a；分支 `ios-port`（Kotlin 2.3.21）；**git 未提交**（lead 统一提交）
- 写域实际改动：仅 4 个 app 文件（见 §1），未触碰 `shared/`、未改任何 app 测试文件、未改 build 配置
- 结论：**4/4 叶子项已接线完成；第 5 项（MessageRepository.getNextMessageId）受写域限制无法在本卡落地，已给接线批 B 明确方案（§5）**
- 验证：`:app:compileDebugKotlin` ✅、`:app:testDebugUnitTest` **324/324 通过 0 失败**（当前树复跑 03:35）、`:app:assembleDebug` ✅、`:shared:testDebugUnitTest` **414/414 通过 0 失败**（T24 基线口径；当前树含 T25 在研用例时为 470 例，11 个失败全属 T25 `domain.automation` 在研范围，非 automation 子集 400/0）、提示词金样门禁 ✅（114 件逐字节资源核对 + `.raw-manifest.sha256` 一致 + GoldenPromptTest 2/2 当前树复跑）

---

## 1. 改动清单（app 公开签名零变化，净删 2993 行双实现）

| app 文件 | 改前 | 改后 | 委托目标（单一来源） | 证据 |
|---|---|---|---|---|
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/PromptTemplates.kt` | 1291 行 / 85 KB | 15 行 | `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/prompt/PromptTemplates.kt:3` `object PromptTemplates` | 22 个字面量逐字节校验（§2.1）；公开成员 `VERSION`/`get` 前后一致 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2PromptTemplates.kt` | 165 行 | 17 行 | `shared/.../prompt/MemoryV2PromptTemplates.kt:3` | 8 个字面量逐字节校验；公开成员 `getL1`/`L2`/`L3` 一致 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/PromptModuleDefaults.kt` | 387 行 | 21 行 | `shared/.../prompt/PromptModuleDefaults.kt:4` | 9 个字面量（8 唯一）逐字节校验；公开成员 `behavior`/`narrationProtocol`/`outputProtocol`/`runtime` 一致 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2Pipeline.kt` | 1347 行 / 75 KB | 144 行 | `shared/.../domain/memory/MemoryV2Pipeline.kt:40` `class MemoryV2Pipeline` | 15/15 公开方法逐一委托；62/62 函数名在 shared 域层有落点（§2.3） |

`git diff --stat app/`：4 files changed, **113 insertions(+), 3106 deletions(−)**。

委托方式（保持调用点零改动）：
- 三个 object：`import ... as SharedXxx` + 每个公开成员一行转发；`const val VERSION = SharedPromptTemplates.VERSION`（编译期常量引用，值仍为 39）。
- pipeline：app 侧保留原名同构造签名的 `class MemoryV2Pipeline'(repository, settings, aiService, memoryVectorService = null, userNicknameProvider)`（尾部 lambda 调用点 `MemoryV2Pipeline(...) { ... }` 继续可用；shared 类构造尾部多一个 `clock: Clock = Clock.System` 默认参，故由 wrapper 显式转发、不直接暴露 shared 构造），内部 `private val delegate = SharedMemoryV2Pipeline(...)`，15 个公开方法原签名逐一转发（含默认参数表达式：`privateChatAllowedSources()` / `settings.privateRecallPrivateChatMemory` / `settings.isMemoryInjectionAllowed(...)`，均留在 app wrapper 侧以保持签名等价）。

文件 sha256（复现用）：
```
b52eadd6…ae729e  app/.../viewmodel/shared/PromptTemplates.kt
37f25b5b…cebf8cc app/.../viewmodel/shared/MemoryV2PromptTemplates.kt
90583125…5a2dabcf app/.../viewmodel/PromptModuleDefaults.kt
7e8e8622…ce39d2d app/.../viewmodel/shared/MemoryV2Pipeline.kt
对应 shared：b1253602…13bd8b / b72123c0…414754 / 817eaca2…6a75b8c / 9eb76e6b…56d413e2
```

## 2. 等价性论证

### 2.1 提示词三件套：app 原字面量全部逐字节存在于 shared（脚本校验）
对改前文件（`/tmp/app-*.bak`，可从 `git show HEAD:...` 复现）提取所有 `"""…"""` 字面量，验证其**每一个唯一字面量**在 shared 中逐字节存在：
```
PromptTemplates:          app literals=22 unique=22 bytes=82550  → 缺失 0
MemoryV2PromptTemplates:  app literals=8  unique=8  bytes=6315   → 缺失 0
PromptModuleDefaults:     app literals=9  unique=8  bytes=15259  → 缺失 0
```
逐行 diff（改前 app vs shared）差异仅为**可见性**与 shared 追加的金样锚点：
- `PromptTemplates`：`HUMAN_REALISM` private→internal（同值）；shared 文件尾部多 `internal object ArchivedTemplates`（T18 金样锚点，非运行时）；`get()` 的 15 分支 when 分发逐字符一致。
- `MemoryV2PromptTemplates`：`DATA_BOUNDARY`/`SCHEMA` private→internal（同值）；`getL1` 分发一致。
- `PromptModuleDefaults`：shared 尾部多 `internal object SourceFragments`（金样锚点，非运行时）；4 个公开函数函数体一致。
→ 运行时输出等价；shared 侧另有 T18 金样门禁把 47 件模板逐字节锁定（见 §3.4），即字面量内容受测试背书。

### 2.2 公开 API 面不变（脚本对比改前/改后）
| 文件 | 改前公开成员数 | 改后 | 完全一致 |
|---|---|---|---|
| PromptTemplates | 2（`VERSION`/`get`） | 2 | ✅ |
| MemoryV2PromptTemplates | 3（`getL1`/`L2`/`L3`） | 3 | ✅ |
| PromptModuleDefaults | 4（`behavior`/`narrationProtocol`/`outputProtocol`/`runtime`） | 4 | ✅ |
| MemoryV2Pipeline | 15（ingest*/build*/privateChatAllowedSources/retryPendingSources…） | 15 | ✅ |

### 2.3 MemoryV2Pipeline：T24 整文件迁移 + 本卡委托的等价链
1. **同源**：shared pipeline 由 T24 从 Android oracle `app/.../MemoryV2Pipeline.kt`（即改前文件）整文件迁移，仅平台化 + 纯函数外提（T24 handoff §1 表；`ios-port/handoffs/impl-memory.md`）。
2. **函数覆盖**：app 改前 pipeline 的全部 62 个函数名，均能在 `shared/domain/memory/`（pipeline + MemoryParseRules + MemoryRecallEngine + MemoryPolicy/Privacy/VectorFormatter/UnifiedMemoryContext/policy/）找到同名落点：**缺失 0**；仅 1 个新函数（`nowMillis`）。
3. **函数体差异分类**（35 个两文件都定义的函数，归一化平台差异后逐函数 diff；低于 0.97 的 23 个全部命中下述白名单钩子，无“未知差异”）：
   - `System.currentTimeMillis()` → `nowMillis()`（`Clock.System`，同一系统墙钟；oracle 40 处时间全部收敛于此，契约 19）；
   - `DebugLogger.*` → `MemoryDiagnostics.*`（诊断日志平台化，见 §4.2；`beginOperation/conversationStep` 当前为 no-op 占位）；
   - 召回/解析/晋升纯函数外提：`recallEngine.*`（MemoryRecallEngine，T24 对六桶预算/相似度/评分/注入选择有金样测试）、`parseRules.*`（MemoryParseRules，解析失败语义/SCHEMA 校验/晋升门/expiresAt 有矩阵测试）、`beijingTimestamp`（等价 `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Asia/Shanghai)`，`MemoryTime.kt:21-25` 补零一致）、`MemoryPromotionLocks.lockFor`（等价 `ConcurrentHashMap.getOrPut+computeIfAbsent`，`MemoryPromotionLocks.kt:16`）；
   - 逐点人工核对的等价样本：`buildPrivateStableImpression`（`recallEngine.buildStableImpressionText` 与 oracle 内联 joinToString/takeIf/let 逐字符一致）、`formatMoment/formatMomentComment/formatPrivateMessage/formatGroupMessage`（仅时间函数替换）、`buildLevelMessages`（逐字符一致）、`maybePromoteOwnerMemory`（锁语义一致）。
4. **工程侧背书**：`:app:compileDebugKotlin` + `:app:assembleDebug` 成功（3 个 ViewModel 的构造/调用点零改动），`:app:testDebugUnitTest` 324/324；shared 域层自带 414 例（其中 memory 召回/晋升/隐私/金样 8 套）全绿。

## 3. 测试与门禁证据（全部本机、`--no-daemon`、gradle 前 pgrep 排队）

### 3.1 app JVM 单测：324/324，0 失败
```
cd RhodesLink && source ../ios-port/toolchain/env.sh
./gradlew :app:testDebugUnitTest --no-daemon      # BUILD SUCCESSFUL in 27s
```
XML 聚合（`app/build/test-results/testDebugUnitTest/`，39 个 class）：`tests=324 failures=0 errors=0 skipped=0`，最后执行时间 `2026-11-22T19:35:45Z`（= 03:35 CST，全部 4 处改动之后、当前工作树含 T25 在研文件时复跑仍绿）。

> 口径说明（回答卡面「预期 376」）：`ios-port/handoffs/spec-chat-engine.md:24` 的 376 是当时 **app/src/test + shared/src 合计**实测（324+52）；`ios-port/handoffs/reviewer-phase1-specs.md:167` 亦写明「376 口径中的 **324 基线**」。当前 app 基线仍为 324（`ios-port/baseline/tests.md:7`：39 文件/324 @Test，与 HEAD 一致），shared 现为 414。故本卡 app 侧验收口径 = **324/0**（376 在 shared 测试补齐后已自然分化为 324 + 414）。

### 3.2 app 编译 + 打包
```
./gradlew :app:compileDebugKotlin --no-daemon   # EXIT=0（改完提示词三件套后、改完 pipeline 后各跑一次）
./gradlew :app:assembleDebug --no-daemon        # BUILD SUCCESSFUL in 40s
```
产物：`app/build/outputs/apk/debug/app-debug.apk`（102 MB，03:31 CST 重新生成）。build-fix 轮次：**0**（无编译错误）。

### 3.3 shared JVM 回归：414/414，0 失败
```
./gradlew :shared:testDebugUnitTest --no-daemon  # BUILD SUCCESSFUL in 28s
```
XML 聚合（`shared/build/test-results/testDebugUnitTest/`，41 个 class）：`tests=414 failures=0 errors=0 skipped=0`，执行时间 `2026-11-22T19:31:44Z`（= 03:31 CST，T25 新测试文件合入前），与 T24 基线 414 一致、无回归。本卡未改 `shared/`，此结果用于证明委托目标侧基线未漂移。
- 补充（当前树，含 T25 在研文件，03:34 复跑）：`tests=470 failures=11`，**11 个失败全部是 T25 未完成的 `domain.automation` 新用例**（MomentReplyGuardTest/MomentReplyIdempotencyTest/MomentReplyTaskTest）；**非 automation 子集 400 例 0 失败**（400 = 414 基线 − T23 的 DelayedTaskLayerTest 14 例，该 14 例仍全绿）。即：T24 基线相关 414 路径无回归，失败面 100% 属 T25 在研范围。

### 3.4 提示词金样门禁 ✅（非侵入式复核 + 当前树复跑，未改金样）
- `ios-port/golden/prompts/raw/` 共 **114 件**；与 `shared/src/commonTest/resources/golden/prompts/raw`、`shared/src/androidUnitTest/resources/golden/prompts/raw` 均 `diff -rq` **无差异**（`sync_golden.sh --sync-only` 会是 no-op）；
- `.raw-manifest.sha256` 与按 raw 目录重算的 sha256 清单 **完全一致**；
- 金样测试 `shared.prompt.GoldenPromptTest` 当前树（03:35 CST）复跑：`--tests "*GoldenPrompt*"` **EXIT=0**，XML `tests=2 failures=0 errors=0`（含 47 件逐字节比对），T25 在研文件不影响该门禁。

### 3.5 并发环境说明（不影响上述结论）
- T25（impl-a7-glue）在本卡执行期间并行写 `shared/src/**/domain/automation` 与 commonTest。03:33 起其未提交测试文件曾短暂有 2 个编译错误（`BackoffTest.kt:77:69`、`MomentReplyTaskTest.kt:64:67`），其后 T25 修复并继续迭代（03:34 起可编译，但其 automation 用例仍有 11 例失败，见 §3.3 补充）。该失败与 T26 改动无关（本卡未触碰 shared/，且 414 全绿证据产生于其测试文件合入前，当前树非 automation 子集 400/0）。**建议 lead 在 T25 收口后复跑一次 §3.3/§3.4 命令做最终合流复验。**
- 本卡所有 gradle 调用前均 `pgrep -fl gradle` 检查、串行 `--no-daemon`；两次与 T25 的构建窗口有短暂交错（其构建结束时本卡任务启动），均一次成功、无构建产物损坏。

## 4. 平台化差异（保留在白名单，非本卡引入）

| 位置 | Android oracle | shared 实现 | 影响 |
|---|---|---|---|
| 时间源 | `System.currentTimeMillis()` ×~40 | `MemoryV2Pipeline(clock: Clock = Clock.System)` + `nowMillis()` | 等价（同一系统墙钟）；测试可注入固定 Clock |
| `beijingTimestamp` | `SimpleDateFormat` + `TimeZone("Asia/Shanghai")` | `MemoryTime.kt:21` kotlinx-datetime | 格式 `yyyy-MM-dd HH:mm:ss` 补零一致 |
| 记忆诊断日志（log/trace） | `DebugLogger`（android.util.Log） | `MemoryDiagnostics` 接口；本卡在 wrapper `init` 安装 `DebugLogger.log` sink 并镜像 `allowSensitiveTrace`（`MemoryV2Pipeline.kt:45-48`） | log/trace 通道行为保持 |
| 记忆诊断 operation 时间线 | `DebugLogger.beginOperation/conversationStep`（诊断页可见） | `MemoryDiagnostics.beginOperation()` 返回 `""`、`conversationStep` no-op 占位 | **已知差异**：诊断页不再出现“私聊/群聊记忆提取”operation 卡片（仅影响诊断展示，不影响聊天/记忆数据）；修复需扩 shared 接口，见 §6 建议 1 |
| 晋升互斥锁 | app `MemoryPromotionLocks.locks` | shared `MemoryPromotionLocks.lockFor` | 等价（同 key、同 ConcurrentHashMap 语义）；app 旧 copy 已无引用 |
| 隐私/向量格式化/统一上下文 | app `viewmodel/shared/` 旧副本 | shared `domain/memory/` 正式件 | 同源迁移（T24） |

## 5. 未接线项与原因（留接线批 B）

### 5.1 `MessageRepository.getNextMessageId` → `shared MessageIdAllocator`：**本卡写域内无法落地**
现状与根因（代码级证据）：
1. app 侧早无内联实现：唯一 app 入口 `app/src/main/java/com/rhodes/privatechat/data/repository/ChatRepository.kt:50` 已是一行 `suspend fun getNextMessageId(): Long = shared.getNextMessageId()`；该 app wrapper 类在 `app/src/main` 内**无任何构造/引用点**（全仓 grep 仅自身定义），属历史兼容壳。
2. 真正的内联公式在 `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/MessageRepository.kt:296-305`（`idMutex` + `DatabaseDispatcher.execute` + 三源 max 自愈 + persisted 高水位），与 `shared/.../domain/data/MessageIdAllocator.kt:60-77`（`allocate()`）公式逐行等价；app 层**没有任何 DB/settings 句柄**可构造 `MessageMaxIdSource`（app 无 `RhodesDatabase` 引用，DI 只注入 shared 仓储单例，`app/.../di/AppModule.kt`）。
3. 卡片写域为 app 侧；而“改 shared MessageRepository 调 allocator / 在 SharedModule 暴露 allocator 单例”均落在 `shared/`，本卡明令禁止触碰。
→ 结论：**保持现状（行为已一致、无漂移新增）**，将“shared 内联公式 → allocator 单一来源”留给接线批 B，具体方案见 §6 建议 2。若 lead 授权跨写域，这是 30 行内的小改（构造 `DatabaseMessageMaxIdSource(db)` + `SettingsMaxIdStore(settings)` + 委托 `allocate()`；send/restore 入口的抬水位改为 `allocator.observeUsed(id)`）。

### 5.2 本卡范围内明确不做的项
- `ChatViewModel.kt` / `GroupChatViewModel.kt` / `MainViewModel.kt` 编排层接线（构造 shared `ChatEngine`/`GroupChatEngine`/`DomainMemoryInjection` 替换内联编排）——按卡面属**接线批 B**，本卡只做叶子层。
- app `viewmodel/shared/` 其余旧副本清理：`MemoryPromotionLocks.kt`、`MemoryPrivacyPolicy.kt` 已因本卡委托变为 **0 引用**（建议批 B 删除）；`MemoryRanker(1)/MemoryPolicy(4)/MemorySurface(5)/UnifiedMemoryContext(3)/MemoryVectorFormatter(1)/GroupEmotionRules(4)/GroupOutputBudget(1)/GroupRuntimeContext(2)/GroupTaggedReplyParser(1)` 仍被 ChatViewModel/GroupChatViewModel 编排层引用，随批 B 一起迁。
- `app/data/PromptTemplates.kt`（已废弃空壳，T18 建议删除）未动（非本卡 4 文件写域）。

## 6. Open questions / 给接线批 B 的建议

1. **诊断 operation 时间线**（建议 P1）：`MemoryDiagnostics.beginOperation` 需从占位 `""` 升级为可注入的 operation 记录接口（或给 no-op 接口加默认 `finishOperation`），并在 app 启动时把 `beginOperation/conversationStep/finish` 接到 `DebugLogger`，以恢复记忆诊断页的“提取窗口/本地保存/本轮总览”卡片。当前差异已在 §4 记录，仅影响诊断展示。
2. **MessageIdAllocator 单一来源**（建议 P1，需 shared 写域授权）：改 `shared/data/MessageRepository.kt` 内 `getNextMessageId()` 为 `allocator.allocate()`，`sendMessage/sendMessageAndCreateReplyTurn/sendReplyAndCompleteTurn/restoreMessage` 的 `nextMessageId = maxOf(...)` 改 `allocator.observeUsed(message.id)`（restore 路径保持“抬水位+持久化”，allocator 已覆盖）；构造侧 `allocator = MessageIdAllocator(DatabaseMessageMaxIdSource(db), settings)`。等价性：公式与 oracle :296-305 逐行一致，`:shared:testDebugUnitTest --tests "*MessageIdAllocator*"` 已覆盖并发/三源自愈/持久化只增。
3. **`MemoryDiagnostics.allowSensitiveTrace` 动态同步**：当前在 pipeline 构造时镜像一次 `DebugLogger.allowSensitiveTrace`（跟随设置页开关重建 ViewModel 后生效）；若要求实时开关，建议在设置写入点同步 `MemoryDiagnostics.allowSensitiveTrace`（批 B 连同 DebugLogger 注入一起做一次）。
4. **可选加强**（非阻塞）：给 app wrapper 加一条 `@Deprecated`/KDoc 标注“仅转发，逻辑在 shared”，并在批 B 删除 `app/data/repository/ChatRepository.kt` 死壳与两个 0 引用旧副本（删除前需全仓再 grep 一次含测试）。
5. **合流复验**：T25 收口后由 lead 统一复跑 `:app:testDebugUnitTest`（324/0）+ `:shared:testDebugUnitTest`（414/0）+ `sync_golden.sh`；本卡证据链（§3）可直接引用。

## 7. 变更文件一览（git 未提交）

```
M app/src/main/java/com/rhodes/privatechat/viewmodel/PromptModuleDefaults.kt              (-380/+14)
M app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2Pipeline.kt           (-1281/+78)
M app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2PromptTemplates.kt    (-159/+11)
M app/src/main/java/com/rhodes/privatechat/viewmodel/shared/PromptTemplates.kt            (-1286/+10)
新增：ios-port/handoffs/impl-wiring-a.md（本文件）
```
