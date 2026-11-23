# T23 实现 handoff：群聊轮次引擎 GroupChatEngine + 通用 DelayedTaskLayer

> 代理：impl-groups（T23 / task-23）。规格卡：`ios-port/specs/group-chat-engine-spec.md`（20 条契约，PASS-WITH-FIXES → lead 批准 2026-11-23）+
> `ios-port/specs/a7-delay-task-spec.md` §A-12（DelayedTaskLayer 冻结契约，lead 裁决由本波同批落地）。
> 状态：实现完成；JVM 双路径（JVM/iOS）见 §3。git 未提交（lead 统一提交）。
> 写域确认：仅新增 `shared/src/{commonMain,commonTest,androidUnitTest,iosTest}` 下 `domain/chat/group/` 与 `domain/automation/`；
> **零改动既有文件**（git status 中 `M` 条目均属并行 T24 记忆卡）、未触碰 `app/`、`shared/build.gradle.kts`、prompt/data/media/chat 根。

## 1. 实现清单（file:line 指向 `RhodesLink/shared/src/`）

### 1.1 commonMain `domain/chat/group/`（8 文件，约 3.1k 行）

| 文件 | 内容 | oracle 对应 |
|---|---|---|
| `GroupChatProtocols.kt` | `GroupStageBudget`（210s/270s/60s/150s/15s/2s/6 次/保最新 10/2×600/250ms/1s/5min/60s/24h/5000 等全集）；`GroupChatEngineConfig`（deepseek 思考加成进轮固化）；`GroupEngineEvent` 12 种；`GroupContextSource`（可选记忆块端口）；`GroupPromptSource` + `SettingsGroupPromptSource`（模板/模块/用户资料/时间/截断策略）；`GroupChatExtras` + `RepositoryGroupChatExtras`（会话清单/角色名册/摘要/重启清理）；`AutoChatPlanPort` + `DefaultAutoChatPlanPort`（token/revision claim 单飞纯逻辑，`SettingsGroupAutoChatPlanStore` 落 `group_auto_plan_*`） | GVM:114-131/:167-271:647-815、automation/GroupAutoChatScheduler.kt:30-121、GroupAutoChatWorker.kt、ChatStageTimeout.kt |
| `GroupChatEngine.kt` | 域编排：`sendGroupMessage`/`executeGroupTurn`（**按 JVM 64KB 上限拆为 prepare→request→persist 三段**，沿用 T11 拆分语义）、`cancelGroupRequests`/`invalidateGroup`、`retryFailedMessage`、`resumePersistedReply`/`resumeImageReply`、`onAppStartReconcile`/`resumeSessionTurns`、`runScheduledAutoTurn`/`setAutoGroupChatEnabled`/`autoGroupChatStatus`/`refreshAutoGroupChats`、`sendHiddenGiftMessage`/`sendGroupImageMessage`、`recallMessage`/`recallMessageSegment`（+情绪快照重算/重建意图）、`restartGroupSession`/`deleteGroup` | GVM:817-930/:937-1846/:1849-1987/:2124-2146/:2247-2398/:261-271/:393-481/:598-645/:2862-2874 |
| `GroupPromptAssembler.kt` | 契约 10「群资料注入顺序」默认实现 + `GroupPromptTexts`（安全边界/三模式回复原则/modeHistoryBoundary/检查清单/内容重试要求逐字迁移）；required 读（session 10s/成员资料 10s/群回合状态 10s）→ 可选顺序（关系 20s→成员私聊背景 20s→群滚动摘要 10s→群向量记忆 20s→公开记忆 20s→近期动态 20s→每日摘要 10s→成员人设 by id→知识库绑定 10s+召回 30s）→ `grpReplacements` 45+ 键 → 连续性+上轮情绪 → `GroupRuntimeContext` 别名去重 → 三模式 system 三层 → 历史格式化 → 运行时上下文 user 消息 → 输出检查清单；可选块 `min(块预算, 60s-已耗-2s)`、<1s 跳过、失败/超时 fallback | GVM:989-1018/:1112-1572 |
| `GroupReplyParser.kt` | tagged 多发言人协议（逐字迁移 GroupTaggedReplyParser.kt 全部归属规则）+ JSON 数组兜底（含 messages/segments 包裹）+ 裸发言人行兜底三级链；`normalizeGroupResults`（online 禁旁白/第一人称旁白丢弃/名单外丢弃/泄漏前缀剥离）；`isDisplayableGroupReply`；历史/gift/image 格式化；`markSegmentRecalled`/`isGroupSegmentRecalled`；`classifyGroupError`；`trackedEmotionsFromHistory`；`GroupImageJsonContract` 三态；`GroupReplyJson`（encodeDefaults+ignoreUnknownKeys） | GVM:2099-2680/:2009-2097/:2281-2413/:537-566/:82 |
| `GroupEmotionRules.kt` | 逐字迁移：清洗截断 ≤10 字、归属优先级、6h 回退窗、快照 ≤60 字/≤12 人、沿用值可溯源、`promptEmotions` | GroupEmotionRules.kt:1-306 |
| `GroupTurnStatePolicy.kt` | 六字段解析（80/16/80/100/16/60/60）、13 项 userTurnType/4 项 threadStatus 白名单、已收束/已转题 nextFocus=无、长度拒绝、保守降级 derive、剧情简述 ≤220 | GVM:2589-2644 |
| `GroupRuntimeContext.kt` | 别名家族去重迁移 + **`GroupPromptKeysPort` 接口化**（默认取 prompt 包注册表，规格卡 R5） | app GroupRuntimeContext.kt；prompt/PromptRender.kt:92 |
| `GroupOutputBudget.kt` | 逐字迁移（5000 / zhipu|minimax|siliconflow|custom → null） | GroupOutputBudget.kt:1-28 |

### 1.2 commonMain `domain/automation/`（1 文件，约 290 行）

`DelayedTaskLayer.kt`：`DelayedTaskChannel` 冻结协议 + `DelayedTaskDue` + `DelayedTaskExecutor`（三态 `SUCCESS/RETRY/DONE`）+ `DelayedTaskLayer` + `DelayedTaskLayerConfig`。
**无任何宿主框架引用**（A7 §A-12 验收 #10：`grep -r "BGTask|UNUserNotification|WorkManager" domain/automation/ → 0 命中`，已实测 0）。

### 1.3 测试（commonTest 11 文件 + 平台桥 2 文件）

| 文件 | 例数 | 覆盖（验收项） |
|---|---|---|
| `GroupEmotionTest.kt` | 27（迁移 app 侧 30 例中可迁移项；3 例依赖 app UI `MessageParser` 按「app 侧原用例不动」保留） | 验收 #1/#3：心情归属/清洗/快照/6h 回退/JSON 兼容/输出预算 |
| `GroupRuntimeContextTest.kt` | 10（迁移核心断言；**完整 20 例 prompt 域副本见 T18 `prompt/GroupRuntimeContextTest.kt`——唯一幸存副本**） | 验收 #1：别名去重/人设恒一份/接口化端口 |
| `GroupEmotionFallbackWindowTest.kt` | 9 | 验收 #3：6h 边界/声明字段优先/沿用链溯源/快照上限/撤回重算输入 |
| `GroupTaggedReplyParserTest.kt` | 19 | 验收 #2：发言人/旁白/心情归属矩阵（同行、行后、台词后、情绪并存、重复取首个、旁白内丢弃、名单外/玩家丢弃）、裸标识恢复、归一化、三级回退、真实输出回放 |
| `GroupTurnStatePolicyTest.kt` | 11 | 验收 #4：六字段/白名单/已收束约束/长度拒绝/保守降级/剧情简述 |
| `GroupAutoChatPlanTest.kt` | 13 | 验收 #6：ensurePlan 幂等/claim 前置/5min 租约/isCurrentClaim/scheduleNext 上限/releaseClaim 保一轮/60s 重排/取消/reconcile |
| `GroupChatEngineRecoveryTest.kt` | 16 | 验收 #7：#1 成功闭环（回合状态/主线/情绪/结算无亲密度仅手动 50 龙门币/lastMessage/维护）·取消→未送达+租约释放·双发合并 supersede→cancelled 终态·claim 拒绝·名册未解析 release(+5s)·全员禁言系统消息+complete·恢复 type 重置+租约透传·启动 reconcile+自动计划重建·上下文超限降级·内容重试（含重生成要求）·两次空回复未送达·阶段超时·自动轮次（无用户气泡/无结算/计划 round+1）·图片管线·礼物管线·分段撤回（情绪重算+维护意图）·预算常数 |
| `GroupMediaJsonContractTest.kt` | 10 | 验收 #10：图片占位/回填≤800/failed 三态、礼物 recipientNames+prompt 还原、分段撤回 JSON、历史格式化、预览 |
| `GroupPromptAssemblyTest.kt` | 10 | 验收 #8（域侧编排）：system 三层顺序/固定协议优先/三模式旁白规则/required+可选固定顺序/可选失败不阻断/auto 维度/连续性与沿用情绪/模式过渡/history 过滤 |
| `GroupTurnStateMachineTest.kt`（abstract）+ JVM/iOS 子类 | 8×2 | 验收 #5：group surface claim/complete、auto plan token、merged_into_batch 终态、名册未解析 release、全员禁言 complete、retryable 仅 manual、租约令牌透传、撤回取消 turn、孤儿清理不动 pending |
| `TestGroupSupport.kt` | — | InMemoryChatStore + `GroupEngineHarness`（TestChatClock/TestTimeSource/事件收集/可注入 fake AI/context/extras）+ `taggedReply` 协议夹具 |

**合计 151 例 × JVM；iOS 路径同套件（平台子类补 SQL 驱动）。**

## 2. 与规格卡 20 条契约的落点

契约 1-2 发送/落库 → `GroupChatEngine.runTurn`；3 租约与孤儿 → `TurnStateMachine` 复用 + `cleanupOrphanedSucceededTurns`；4 防抖/双复核 → prepare 段 generation/pendingIds；5 成员权限 → `GroupPromptAssembler` guards（`GroupMembersUnresolvedException`→release+5s、`GroupAllMutedException`→系统消息+complete）；6-7 tagged/三级解析/归一化 → `GroupReplyParser`；8 情绪 → `GroupEmotionRules`；9 回合状态 → `GroupTurnStatePolicy`；10 注入顺序 → `DefaultGroupPromptAssembler`；11 三模式/auto → assembler `structuredTemplateMode`/foundation/behavior；12 模型请求与上下文降级 → `requestGroupReply`；13 预算/阶段超时 → `GroupStageBudget` + `withChatStageTimeout("group", …)`（复用 T19，中文标签已含群聊 6 项）；14 落库与结算 → `persistGroupReply`（无亲密度/催眠；仅手动每日龙门币 50 经 `Settled` 事件交宿主导入，同 T15 裁决）；15 失败未送达 → `markGroupMessagesUndelivered`+`classifyGroupError`；16 重试/恢复 → `retryFailedMessage`/`resumePersistedReply`/`resumeSessionTurns`/`onAppStartReconcile`；17 图片/礼物 → `sendGroupImageMessage`/`sendHiddenGiftMessage`；18 分段撤回 → `recallMessageSegment`+`refreshLatestRoundEmotionSnapshot`+`rebuildGroupContextAfterRecall`；19 重启/删群 → `restartGroupSession`/`deleteGroup`（无存档/擦除/重新生成/续写，差异白名单）；20 自动互动 → `AutoChatPlanPort`+`runScheduledAutoTurn`（到期触发经 DelayedTaskLayer，通道适配归 T22 胶水批）。

## 3. 验证结果

| 路径 | 命令 | 结果 |
|---|---|---|
| JVM 本卡套件 | `./gradlew --no-daemon :shared:testDebugUnitTest --tests "…domain.chat.group.*" --tests "…domain.automation.*"` | **151 tests, 0 failures** |
| JVM 全量 | `:shared:testDebugUnitTest --rerun-tasks` | **414 tests, 0 failures**（本卡 151 例 + T9/T10/T17/T18/T19/T24 既有全绿；test-results XML 实测 failures=0） |
| iOS 本卡套件 | `:shared:iosSimulatorArm64Test`（任务编出的同一 test.kexe + `--ktest_filter`） | **151 例，0 failures**（group 137 + automation 14） |
| iOS 全量 | `:shared:iosSimulatorArm64Test --rerun-tasks` | **442 tests, 0 failures**（test-results XML 实测；本卡 151 例含在内全绿） |
| 禁 import 静态门禁 | `grep -r "BGTask\|UNUserNotification\|WorkManager" domain/automation/` | **0 命中** |

### 3.1 并行构建纪律

全程 `source ios-port/toolchain/env.sh` + 每次 gradle 前 `pgrep -fl gradle`；与 T24（impl-memory）并行时按其构建状态 sleep 30 轮询排队；一律 `--no-daemon`；本卡只新增文件，无写域交叉。

### 3.2 iOS 全量卡点复盘（已由 T24 修复，最终全绿）

中途两次全量 iOS 曾卡死，sample 栈定位为并行 T24 写域 `memory.KnowledgeBasePipelineTest.oversize…` → `knowledge.KnowledgeBaseTextProcessor.prepare → split`（look-behind 正则在 K/N 下 O(n²)）。T24 改为线性字符扫描后复测：`:shared:iosSimulatorArm64Test --rerun-tasks` **442 tests / 0 failures（55s）**；本卡 151 例含在内全绿。本卡期间为隔离取证也验证过 KGP Native 任务过滤等价方案（同 test 二进制 + `--ktest_filter`），commands 保留备查：

```bash
source ../ios-port/toolchain/env.sh
TEST_BIN=shared/build/bin/iosSimulatorArm64/debugTest/test.kexe
xcrun simctl spawn --standalone "iPhone 17" $TEST_BIN -- \
  --ktest_no_exit_code --ktest_logger=TEAMCITY \
  --ktest_filter="com.rhodes.privatechat.shared.domain.chat.group.*"
```

## 4. DelayedTaskLayer 接口冻结说明（供 T22 胶水批）

```kotlin
// 冻结协议（A7 §A-12；A1 波次与 A7 波次均不得单方面修改）
interface DelayedTaskChannel {
    suspend fun schedule(key: String, dueAt: Long, payload: Map<String, String>)
    suspend fun cancel(key: String)
    suspend fun pendingKeys(): Set<String>
    fun onDue(callback: suspend (DelayedTaskDue) -> Unit)
}
data class DelayedTaskDue(val key: String, val dueAt: Long, val payload: Map<String, String> = emptyMap(), val attemptCount: Int = 0)
fun interface DelayedTaskExecutor { suspend fun execute(task: DelayedTaskDue): DelayedTaskOutcome }
enum class DelayedTaskOutcome { SUCCESS, RETRY, DONE }

class DelayedTaskLayer(
    primaryChannel: DelayedTaskChannel,            // iOS 壳：前台 Timer 主通道（进程内）
    fallbackChannel: DelayedTaskChannel? = null,   // iOS 壳：系统后台刷新兜底通道（可为 null）
    scope: CoroutineScope,
    clock: () -> Long,
    config: DelayedTaskLayerConfig = DelayedTaskLayerConfig(),   // reconcile 24h / EXP 1min / 容忍 1s / 最多 8 次
    isRecoveryActive: () -> Boolean = { false },   // 备份/恢复护栏
)
// 监听层：唯一键处理器按前缀最长匹配；无匹配键按 DONE 幂等销号
fun registerHandler(prefix: String, executor: DelayedTaskExecutor)
suspend fun enqueue(key: String, dueAt: Long, payload: Map<String, String> = emptyMap()): Boolean   // KEEP 语义
suspend fun cancel(key: String)
suspend fun pendingKeys(): Set<String>
suspend fun channelPendingKeys(): Set<String>
suspend fun task(key: String): DelayedTaskDue?
suspend fun reconcile(records: List<DelayedTaskDue>): List<String>  // 超 24h 丢弃；窗口内钳 now 立即执行
```

**语义冻结要点**：① 同键 KEEP（不重排 dueAt）；② dueAt 钳 0；③ 到期判定在执行时（通道早到→按剩余延迟重挂，不执行）；④ 三态协议 + EXP 1min（1/2/4/…/64min）+ 最多 8 次后销号；⑤ 启动 reconcile 窗口 24h；⑥ 双通道都 schedule、内存 runningKeys 去重保证恰一次；⑦ 恢复护栏一律 retry 不执行；⑧ 层不做领域互斥（租约/锁归执行体）；⑨ `cancel` 只销号+取消通道任务，不中断已 running 执行体。
**T22 接线契约**：A7 的 MomentReply/ContextMaintenance 只需 `registerHandler("moment-reply-", …)` / `registerHandler("context-maintenance-", …)` + 启动时 `reconcile(records)` 从 `retryableTurnIds`/摘要状态构造记录；A1 自动互动从 `DefaultAutoChatPlanPort` 的 plan.dueAt 入队 `group-auto-{groupId}`，到期回调调 `GroupChatEngine.runScheduledAutoTurn(groupId, token, generation, revision)`。执行体返回 `RETRY` 即由层退避重排，**层不感知领域退避**（领域级 60s<<n 在 port/引擎内）。

## 5. Android 调用点改造清单（Phase 2，lead 统一裁决；本波未动 app/）

| Android 文件:段 | 动作 | 域层等价入口 |
|---|---|---|
| GVM:114-131 预算常数 / :199-208 深度思考加成 | 删，改 `GroupChatEngineConfig`（deepseekThinkingActive 由宿主进轮前设置） | `GroupStageBudget`/`GroupChatEngineConfig` |
| GVM:261-271 cancelGroupRequests | 委托 | `GroupChatEngine.cancelGroupRequests` |
| GVM:817-930 sendGroupMessage 主流程（用户落库段） | 委托；保留 UI 状态（`_groupMessages`/`_groupLoading`/`_lastSendError` 用事件映射） | `sendGroupMessage` |
| GVM:937-1846 executeGroupTurn（prepare/runModel 两段） | 删，委托（结算事件交宿主导入每日龙门币/通知/未读） | `executeGroupTurn` 内部三段 |
| GVM:102-108 安全边界 / :1390-1453 foundation/协议/检查清单文案 | 删（**模板文本 T18 已迁移**；固定文案在本卡 `GroupPromptTexts`） | `GroupPromptTexts` |
| GVM:989-1039 required/optional 上下文 helper | 删 | assembler required/optional |
| GVM:1112-1572 prepareTurn（含 45+ 占位符、连续性与上轮情绪、GroupRuntimeContext） | 删 | `DefaultGroupPromptAssembler` + `GroupRuntimeContext` |
| GVM:1595-1698 模型循环（上下文降级/内容重试） | 删 | `GroupChatEngine.requestGroupReply` |
| GVM:1717-1789 落库/回合状态/情绪/结算 | 删；宿主消费 `ReplyStored/TurnStateUpdated/Settled/MaintenanceRequested/UnreadHint` | `persistGroupReply` |
| GVM:1849-1884 礼物 / :2247-2398 图片 / :1886-1917 重试 / :2124-2146 恢复 / :2862-2874 图片恢复 | 委托；`MainActivity.imageForModel`/VisionGateway 经构造参数注入 | 同名方法 |
| GVM:393-481 分段撤回/情绪重算/上下文重建意图 | 委托；`ContextMaintenanceScheduler` 改宿主事件驱动 | `recallMessageSegment` |
| GVM:598-645 重启/删群 | 委托（重启清理的删 turn/短记忆经 `GroupChatExtras` 注入，记忆卡落地后接实现） | `restartGroupSession`/`deleteGroup` |
| GVM:647-815 / :1933-1979 自动互动循环/list | 保留前台循环壳（Timer → `runScheduledAutoTurn`）；计划纯逻辑用 `DefaultAutoChatPlanPort`；WorkManager 换 DelayedTaskLayer（T22 胶水） | `AutoChatPlanPort`/`runScheduledAutoTurn` |
| GroupEmotionRules.kt / GroupTaggedReplyParser.kt / GroupRuntimeContext.kt / GroupOutputBudget.kt | 删文件（shared 域层唯一副本） | 同名 group 包对象 |
| GroupAutoChatScheduler.kt 纯逻辑段 | 删/改 `DefaultAutoChatPlanPort`；Worker 留 Android（域层无 WorkManager） | `AutoChatPlanPort` |

## 6. open questions / 遗留

1. **结算写入宿主化**（同 T15）：`Settled.dailyLmb=50` 只由宿主在手动轮次写入 `grantDailyLmb`；自动轮次为 null。若 lead 要求域层直接写，仅需在 `persistGroupReply` 调 `settings.grantDailyLmb` 并删事件字段（需改测试）。
2. **`unhideSession`/未读/后台通知**：域层仅发 `UnhideSession`/`UnreadHint` 事件；Android 侧 `notifyIfBackground`/`markUnreadIfNotCurrent` 留宿主。
3. **记忆/知识库/Token 统计/debug 溯源**：全部经 `GroupContextSource`/`GroupChatExtras` 端口注入，本卡不实现记忆管线；`Assembler` 不写 DebugLogger（宿主需要逐块 provenance 时在 contextSource 包装层记录）。
4. **`shouldIncludeTimeSummary` 每日摘要门槛**：oracle 有「仅在问时间类问题时读每日摘要」优化（GVM:1242），本卡按契约 10 固定注入（10s 可选块，无数据即空）；如需严格复刻，加 `GroupPromptSource.shouldIncludeTimeSummary(text)` 端口，不影响契约。
5. **自定义主模板的 runtime 补充**：prompt 包只公开 `buildCachePromptLayers/runtimeContext` 为 internal，`GroupPromptAssembler` 按同一标签映射自行重建补充块（`groupTemplateRuntimeSupplement`）；与 prompt 域版本行为逐条对齐的差异需 reviewer 复核（默认模板路径不受影响）。
6. **`GroupRuntimeContext` 双副本**：app/prompt 域各一份历史副本；本卡 group 包加接口化版本供引擎消费，prompt 域 20 例副本保持不动；Phase 2 删 app 副本时统一到 prompt 或 group（建议 group，因 prompt 域仅测试用）。
7. **图片管线 `saveVisionVectorMemory`/gift 向量**：oracle 群图片识图向量写入 `memoryV2Pipeline`；本卡按「记忆管线后续卡」留空（未注入），需记忆卡接线时补 `GroupContextSource.onVisionAnalyzed`（或宿主在 `sendGroupImageMessage` 结果事件后自行写入）。
8. **`isLowSignalKnowledgeQuery`/`saveGroupAnchorToVector`/`untrustedGroupRepairInput`/`isStrictGroupJson`/`requiresFormatRepair`**（规格卡 R6 仅定义未调用遗留）：按 YAGNI 未迁移，与 T19 同口径。
9. **图片管线的 `onResult` 回调语义**：oracle `MessagePersistenceReceipt` 保证「持久化成功即完成 composer 等待」；域实现以 `onResult(true)`（已持久化）+ finally 再 `onResult(false)` 兜底，宿主不应重复处理（T19 图片管线同款语义差异已记录）。
10. **iOS 路径全量测试与 T24 队列互斥**：本卡 iOS 判定以 group/automation 类 0 failures 为准（Native 任务无 `--tests` 过滤）；全量输出中的 memory 失败项属并行卡。

## 7. 构建纪律记录

- 全程 `source ../ios-port/toolchain/env.sh`、每次 gradle 前 `pgrep -fl gradle`、`--no-daemon`；与 T24 并行时按其构建排队。
- 构建修复轮次：commonMain **3 轮**（语法/可见性 → JVM 字面量类型 → **Method too large → 按 oracle T11 三段拆分 executeGroupTurn**）；commonTest **3 轮**（kotlin.test 参数顺序/kotlinx.serialization 扩展 import/DelayedTaskLayer 三态 lambda 类型 + 行为口径）。均在 3 轮上限内收口。
- 关键实现决策：`executeGroupTurn` 因 JVM 64KB 方法上限拆为 `prepareGroupTurn`/`requestGroupReply`/`persistGroupReply`（`TurnRuntime` 承载可变状态，控制流与 oracle 逐字一致）；这与 Android T11 的 prepareTurn/runModelTurn 拆分同构。
