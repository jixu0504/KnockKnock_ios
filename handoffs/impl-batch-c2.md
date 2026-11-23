# T35 批 C 续作 handoff：适配层 + D1/D2/D3 语义对齐 + 逐入口接线（步骤 1+2 完成，步骤 3 部分完成，步骤 4 未启动）

- 任务：`task-35`（Phase 2 收尾：批 C 续作）｜代理：`impl-batch-c2`
- 分支 `ios-port`（Kotlin 2.3.21）｜**git 未提交**（lead 统一提交）
- 前置文档：`impl-batch-c.md`（T28：VM 测试替身 21 例 + D1/D2/D3 实测差异）、`impl-chat-engine.md`（T19 引擎 61 例）、`specs/private-chat-engine-spec.md`
- 结论速览：
  1. **步骤 1（适配层）完成**：`ChatEngineFactory` + 事件映射（私聊 11 / 群聊 12 全量）冻结，7 例 app 测试覆盖；
  2. **步骤 2（D 语义对齐）完成**：引擎新增可选语义模式（默认值保持引擎原行为，shared 既有 61 例零改动），D1/D2/D3 基线在 shared（4 例）+ app（3 处断言冻结）双层固化，证据见 §3；
  3. **步骤 3（逐入口替换）部分完成**：撤回类 **2 个入口已委托引擎**（`recallMessage` / `recallMessageSegment`，宿主壳保留），其余 7 类入口保留原实现并给出阻断分析（§4，均为「适配层就绪但宿主侧编排迁移量 > 本卡安全预算」，非语义无法对齐）；
  4. **步骤 4（群聊委托）0/7**：仅完成 D3 语义对齐 + 群聊事件映射表冻结；群聊生产端口 `GroupChatExtras/GroupContextSource` 未实现（§6 建议）。
- 写域实际改动：仅 `app/src/{main,test}/.../viewmodel`、`shared/src/commonMain/.../domain/chat`、`shared/src/commonTest`、`ios-port/handoffs`（§7 变更清单）。未触碰其他代理写域（树中 T34/T36 的未提交改动与本卡无关）。

---

## 1. 步骤 1：适配层设计（ChatEngineFactory + 事件映射）

### 1.1 文件与职责

| 文件 | 行数 | 内容 |
|---|---|---|
| `app/src/main/java/com/rhodes/privatechat/viewmodel/ChatEngineFactory.kt`（新） | 164 | **唯一**把 ChatViewModel 依赖映射为 ChatEngine 注入接口的工厂；`ANDROID_BASELINE_SEMANTICS`（:60）；`buildConfig`（:78，深度思考加成同源 oracle :107-140）；`createPrivateEngine`（:89，全参数生产默认值 + 可替换测试端口）；`SharedUtilsStreamingClient`（:156，包 `SharedUtils.chatResult` 保持厂商/温度/思考/token 日志）；`LazyVisionAnalyzer`（:136-149，逐字复刻 oracle :1902-1913 识图提示词） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/ChatEngineEventMapping.kt`（新） | 138 | `ChatEngineHost`（:30，11 回调 + KDoc **冻结映射表**：事件→oracle VM 副作用点）；`ChatEngineEventMapper`（:53）；`GroupChatEngineHost`（:101，12 回调 + 冻结表）；`GroupChatEngineEventMapper`（:117） |
| `ChatViewModel.kt`（改） | +22/-4 | :238-255 惰性 `privateChatEngine`（经工厂构造；不在 VM 构造期开库，守 T28 步骤 0a 契约）；:2126 / :2206 两个撤回入口委托 |

### 1.2 依赖映射表（oracle = ChatViewModel 构造参数）

| ChatViewModel 依赖 | ChatEngine 注入 | 说明 |
|---|---|---|
| `repository: ChatRepository` | `RepositoryChatStore(repository)` | 真实 DB 适配器，不 mock |
| `repository.messages.allocator` | `allocator: MessageIdAllocator` | **唯一实例**（T28 步骤 0a；工厂不新建） |
| `settings` | `settings` + `ChatEngineConfig.deepseekThinkingActive` | `buildConfig` 按 `provider=="deepseek" && deepseekThinkingEnabled` 置位；预算常数为 `ChatStageBudget` |
| VM 私有 `buildApiMessages`（3500 行） | `PrivatePromptAssembler`（默认构造） | **shim 阶段**：可选上下文 providers 用共享层默认占位（等价 oracle 占位）；memoryV2/知识库/溯源/缓存统计接入留待全量替换（§4） |
| `sharedUtils.chatResult(...)` | `SharedUtilsStreamingClient` | `model_primary_request`→tag `Chat`，`model_content_retry`→`ChatContentRetry`（oracle :1163-1164） |
| `aiService.normalizeOfflineResponse/cleanJson` | `DefaultReplyCodec` | 输出编解码 |
| `isVisionConfigured()` / `currentVisionGateway()` | `visionConfigured` / `visionAnalyze` | 识图返回原样交给引擎裁剪（oracle :1924-1927） |
| `MainActivity.imageForModel(uri)` | `imageForModelResolver` | Android 专属；iOS 宿主换实现 |
| `sharedUtils.chatConfigurationError()` | `chatConfigError` | 发送前配置守卫（oracle :1317-1322） |
| `viewModelScope` | `scope` | fire-and-forget job 归宿主 scope |
| 引擎事件 | `EngineEventSink`（`ChatEngineEventMapper`） | 冻结表见 §1.3 |

### 1.3 事件映射表（冻结）

私聊 `EngineEvent` 11 种 → `ChatEngineHost` 11 回调：`LoadingChanged`→loading 状态；`Toast`→onShowToast；`ClaimDenied`→诊断日志（Toast 由引擎另行发射）；`Undelivered`→内存行同步 send_failed（DB 引擎已写）；`ReplyStored`→finishDebugRound（携带 `ReplyDiagnostics`）；`Settled`→亲密/龙门币/催眠/通知/未读/维护调度（宿主写，引擎只算）；`TurnStateUpdated`→头部状态刷新；`MaintenanceRequested`→摘要/记忆调度；`UnhideSession`；`UnreadHint`；`FailureMessageStored`→诊断日志。逐条 oracle 行号见 `ChatEngineEventMapping.kt` KDoc。

群聊 `GroupEngineEvent` 12 种 → `GroupChatEngineHost` 12 回调，映射表同上（含 `AutoPlanChanged`→Timer/BGTask 通道重排）。

**已知偏差（记录，未改引擎）**：T19 引擎当前**不发射** `LoadingChanged` 事件，真实 loading 经 `engine.loadingSessions: StateFlow` 暴露；映射表保留该位，接线层应订阅 StateFlow（见 §6-2）。`ChatEngineFactoryTest#factoryWiredEngine_sendSuccess_persistsRowsAndMapsEvents` 已按此断言。

---

## 2. 步骤 2：引擎语义模式（默认值 = 既有行为，零回归）

| 文件 | 改动 |
|---|---|
| `shared/.../domain/chat/ChatEngineProtocols.kt:252` | 新增 `ChatEngineSemantics(cancelSkipsDbWrites: Boolean = false, recallDropsTurnRow: Boolean = true)`（KDoc 写明 D1/D2 语义与证据位置） |
| `shared/.../domain/chat/ChatEngine.kt:63` | 构造参数 `semantics: ChatEngineSemantics = ChatEngineSemantics()`；取消分支 + `finishSendPipeline`（:549）实现 legacy 语义：取消路径跳过 DB 写入（用户行保持 `text`、租约保持 `running`），宿主侧清理（loading/job/锁/**onResponseComplete**）在 `NonCancellable` 中完成 |
| `shared/.../domain/chat/group/GroupChatProtocols.kt:191` | 新增 `GroupChatSemantics(allMutedReportsStored: Boolean = false)` |
| `shared/.../domain/chat/group/GroupChatEngine.kt:396` | 全员禁言分支返回 `if (semantics.allMutedReportsStored) runtime.responseStored else false`（系统消息/租约 complete/零模型调用两态相同） |

> 设计取舍：**默认值全部保持引擎原行为**（shared 既有 61 例聊天引擎测试 + 群聊测试零改动、全绿）；Android 基线语义由工厂 `ANDROID_BASELINE_SEMANTICS` 显式选择，避免「引擎默默变成 oracle 的怪癖复制品」。

---

## 3. D1/D2/D3 对齐证据

### 3.1 shared 层（`shared/src/commonTest/.../domain/chat/ChatEngineSemanticsTest.kt`，4 例）

| 用例 | 断言（双态对照） | 结果 |
|---|---|---|
| `cancelDuringSend_engineDefaultMarksUndeliveredAndReleasesTurn` | 默认：`send_failed` + 租约 `pending` + Undelivered 事件 + 回调 false | ✅ |
| `cancelDuringSend_legacySemanticsSkipsDbWritesLikeAndroidBaseline` | legacy：用户行 **`text`** + 租约 **`running`** + **无** Undelivered + 无 AI 行 + 回调 false + loading 清空 | ✅ |
| `allMuted_engineDefaultReportsNoReplyButStoresSystemMessage` | 默认：系统消息 + 租约 `succeeded` + 回调 **false** | ✅ |
| `allMuted_legacySemanticsReportsStoredLikeAndroidBaseline` | legacy：同上 + 回调 **true**（D3 基线） | ✅ |

### 3.2 app 层（真实 SQLDelight 库 + Ktor MockEngine；`ChatEngineFactoryTest.kt` 7 例）

| 用例 | 锁定的基线行为 | 结果 |
|---|---|---|
| `factoryWiredEngine_sendSuccess_persistsRowsAndMapsEvents` | 工厂全链路：用户行+ai_json 行+租约 succeeded；事件映射 ReplyStored/Settled(affectionMod=2,dailyLmb=50)/Maintenance/Unhide/Unread 全部命中；诊断快照非空；loading 回落 | ✅ |
| `d1Cancel_legacySemanticsKeepsTextAndRunningTurnLikeBaseline` | **D1**：取消后回调 false、用户行 `text`、租约 `running`、无 AI 行、无 Undelivered（与 T28 VM 实测一致） | ✅ |
| `d2Recall_productionStoreDropsTurnRowLikeBaseline` | **D2**：撤回后消息行删除 + **租约行随 `deleteMessage` 级联删除** + 会话预览回退 | ✅ |
| `defaultEngineSemanticsOnProductionStoreHasSameCancellationBlindSpot` | **关键对照证据**：引擎默认语义在生产库上同样因「协程已取消→挂起 DB 写入被跳过」而停在 text/running —— 即引擎文档化的取消行为只在纯内存替身成立；故适配层必须显式选 legacy（并把宿主清理放进 NonCancellable），不能依赖默认 | ✅ |
| `privateEventMapperCoversAllEngineEvents` / `groupEventMapperCoversAllGroupEngineEvents` | 映射表冻结：11/11、12/12 全量按序落回调，payload 透传（diagnostics/speakers 等） | ✅ |
| `buildConfig_mapsDeepseekThinkingBonusLikeOracle` | 180s/120s；深度思考开启 240s/180s（oracle :107-140） | ✅ |

### 3.3 T28 三处基线断言已升级为硬断言（不再是 println）

| 文件 | 改动 |
|---|---|
| `app/src/test/.../ChatViewModelCorePathTest.kt:193-201` | D1：`assertEquals("text", userRow.type)` + `assertEquals(RUNNING, turn.status)` + 无 AI 行（原为 `println("CANCEL_BASELINE …")`） |
| `app/src/test/.../ChatViewModelCorePathTest.kt:225-237` | D2：保留 `assertNull(turn)` 并注释生产级联语义（`MessageRepository.deleteMessage` :332-338 同事务删 reply_turns） |
| `app/src/test/.../GroupChatViewModelCorePathTest.kt:122-131` | D3：`assertEquals(true, sendAndWait(vm))`（原为丢弃返回值） |

### 3.4 D2 的完整结论（消除 T28 差异项）

- oracle（VM :2101-2103）：`cancelReplyTurnForSource` → `deleteMessage` → 预览更新；
- 引擎（`ChatEngine.recallMessage` :878-885）：`turns.cancelBySource` → `store.deleteMessage` → 预览更新；
- 生产 `RepositoryChatStore.deleteMessage` → `MessageRepository.deleteMessage`（`shared/.../data/MessageRepository.kt:332-338`）在**同一 transaction** 删除 `chat_display_events` + `reply_turns` + `chat_messages`；
- ⇒ 生产库上两者可观测结果**完全相同**（无租约行）；T28 的 D2「引擎保留 cancelled 终止态」只在 `InMemoryChatStore` 测试替身成立（T19 状态机契约锁定）。**故 D2 不需要引擎开关**，`recallDropsTurnRow` 仅作契约声明，用 app 真实库测试固化。

---

## 4. 步骤 3：逐入口替换/回退表

替换原则：每个入口先确认「宿主壳保留 + DB 变更是否可整体下沉」；一次只改一个入口、跑全量 app 测试；失败即回退该入口（git checkout 单文件）并记录。

| # | 入口（ChatViewModel.kt） | 状态 | 委托内容 | 保留在 VM 壳的宿主逻辑 | 阻断/理由 |
|---|---|---|---|---|---|
| 1 | `sendMessage` :1298 | **保留原实现** | — | 全部 | 需 VM 私有 `buildApiMessages` 全量 shim（memoryV2/知识库/溯源/缓存统计/Judge 注入，:3034-3501）+ `_messages` 乐观行/草稿恢复/`generateDailyIfNeeded` + 结算副作用（亲密/龙门币/催眠/通知/WorkManager）+ DebugLogger 轮次。单入口迁移 ≈ 500 行编排，超出本卡 2 轮安全预算 |
| 2 | `cancelSessionRequests` :1258 | **保留原实现** | — | 全部 | B2 状态所有权：引擎取消**它自己的** `chatAiJobs/promptBuildJobs`；VM 的 sendMessage 未委托 → VM 仍有未迁移在飞 job。D1 语义已冻结、引擎 legacy 模式已就绪（§2），等 sendMessage 迁移时一并切换 |
| 3 | `retryFailedMessage` :2150 | 保留 | — | 全部 | 依赖 sendMessage 管线 |
| 4 | `regenerateAiMessage` :2363 | 保留 | — | 全部 | 依赖 `buildApiMessages` shim + 乐观替换 UI（`replaceReplyIfUnchanged` 引擎已实现，等入口迁移） |
| 5 | `resumePersistedReply` :1796 | 保留 | — | 全部 | 依赖 sendMessage 管线 + WorkManager 租约 token 透传 |
| 6 | **`recallMessage` :2083** | ✅ **已委托** | `privateChatEngine.recallMessage(sessionId, msgId)?.join()`（:2126）：turn 终态 + display events/租约行/消息行删除 + 会话预览更新 | 在飞 job/promptBuildJob 取消、`pendingUserMessageIds`/loading 清理、`ManualReplyScheduler.completeTurn`（WorkManager 取消，Android 专属）、`_messages` 乐观移除、`recalledSourceMessageIds` 门闩、`rebuildPrivateContextAfterRecall`（记忆时间线重建） | 无（D2 语义已证明等价） |
| 7 | **`recallMessageSegment` :2173** | ✅ **已委托** | `privateChatEngine.recallMessageSegment(...)`（:2206）：`ReplySegmentMutation` 分段 recalled 标记 + display event 删除 | 非法参数退化整条撤回的前置判定、`_messages` 即时更新 + 引擎完成后按 DB 读回内容刷新、`rebuildPrivateContextAfterRecall` | 无（分段 JSON 契约由共享 `ReplySegmentMutation` 统一） |
| 8 | `continueAiMessage` :2443 | 保留 | — | 全部 | 同 sendMessage 阻断（buildApiMessages shim） |
| 9 | `sendImageMessage` :1819 / `resumeImageReply` :2742 | 保留 | — | 全部 | vision 端口已由工厂接通，但同样需 buildApiMessages shim + `_messages`/草稿/`MessagePersistenceReceipt` 宿主逻辑 |
| 10 | 存档 `createCurrentArchive` :317 / 读档 / 重启 / 擦除 | 保留（引擎无对应 API） | — | 全部 | 非本卡范围（T19 §4 明确保留 VM） |

**回退记录**：本卡未发生入口级回退（撤回两入口 1 轮通过）。构建级修复 2 轮（见 §5.4）。

---

## 5. 测试与门禁证据（本机实测，`--no-daemon`，gradle 前 `pgrep` 排队）

### 5.1 最终门禁

| 门禁 | 命令 | 结果 |
|---|---|---|
| app 单测 | `./gradlew --no-daemon :app:testDebugUnitTest` | **352 tests / 0 failures / 0 errors（42 class）** = 基线 345 + 本卡新增 7（`ChatEngineFactoryTest`）；D1/D2/D3 硬断言通过 |
| app 打包 | `./gradlew --no-daemon :app:assembleDebug` | ✅ `app/build/outputs/apk/debug/app-debug.apk`（103,825,197 B，07:57 产物，最终确认 BUILD SUCCESSFUL） |
| shared JVM | `./gradlew --no-daemon :shared:testDebugUnitTest` | **524 tests / 0 failures** = 基线 520 + 本卡 4（`ChatEngineSemanticsTest`） |
| shared iOS | `./gradlew --no-daemon :shared:iosSimulatorArm64Test`（清陈旧 test-results 后 `--rerun`） | **563 tests / 0 failures / 62 class** = 基线 559 + 本卡 4；`ChatEngineSemanticsTest 4/0` 在 iOS 路径同样绿 |
| 回归对照 | 既有 `ChatViewModelCorePathTest` 14 例、`GroupChatViewModelCorePathTest` 7 例、`ChatEngineRecoveryTest` 等全部保持绿 | ✅ |

> 最终三门禁时间与证据：app 352/0 与 shared JVM 524/0（07:58，`evidence/t35-final-gate.log`，XML 逐类聚合）；shared iOS 563/0（08:01，`evidence/t35-ios-tests-3.log`，XML 62 类聚合）；assembleDebug（`evidence/t35-assemble-debug.log`）。

### 5.2 本卡单测明细

- `app/.../ChatEngineFactoryTest.kt`（380 行，7 例）：§3.2 表；
- `shared/.../ChatEngineSemanticsTest.kt`（119 行，4 例）：§3.1 表；
- 基线断言升级：§3.3（3 处）。

### 5.3 证据文件

`ios-port/handoffs/evidence/`：`t35-shared-jvm.log`（shared 524 首跑）、`t35-app-tests-1/2/3.log`（app 迭代：1 轮 2 处断言口径修正、2 轮修绿、3 轮含分段撤回委托）、`t35-final-gate.log`（app 352 + shared JVM 524 的最终 `--rerun`）、`t35-ios-stacktrace.log`（iOS XML 写入失败的根因栈）、`t35-ios-tests-3.log`（清陈旧结果后 iOS 563/0 最终跑）、`t35-assemble-debug.log`。

### 5.4 构建修复轮次（如实记录）

1. **shared JVM 第 1 轮**：`MethodTooLarge`（`ChatEngine.runSendPipeline`，Kotlin 2.3 + 内联 finally 膨胀超 JVM 64KB；T11 同类问题）→ 把 finally 收尾抽成 `finishSendPipeline`（ChatEngine.kt:549），1 轮通过。
2. **app 第 1 轮**：本卡新测试 2 处断言口径错（映射顺序漏 `settled`；`LoadingChanged` 事件引擎不发射）→ 改测试口径，1 轮通过（非生产回退）。
3. **iosSimulatorArm64Test XML 写入失败（2 次，非用例失败）**：现象为 `Could not write XML test results ...` × 72，首次伴随 `TestOutputStore` NPE（`region is null`）。带 `--stacktrace` 复跑定位根因：
   `java.lang.IllegalArgumentException: Multiple entries with same key: 525=TestOutputStore$Index`（`TestOutputStore$IndexBuilder.build`）——即 **`shared/build/test-results/iosSimulatorArm64Test/binary/` 里上一次中断的增量输出索引残留**（同一 testId 525 重复写入），Gradle 在写 XML 前重建索引时崩。**处理**：删除 `shared/build/test-results/iosSimulatorArm64Test`（+ 对应 reports 目录）后 `:shared:iosSimulatorArm64Test --rerun` → **563/0 全绿（31s）**。属 Gradle 8.10 测试结果增量存储的已知脏状态问题（与 KMP/Kotlin Native 测试任务重跑有关），**不是本卡引入的用例失败**；后续遇到同症状先清 `test-results/<task>` 目录再跑，不要误判为回归。

---

## 6. 未完成项 + 给后续的建议

1. **（最高优先）sendMessage 全量迁移前置：提示词 shim**。`ChatEngineFactory` 当前用共享 `PrivatePromptAssembler`（可选上下文为占位）。生产等价需要实现 `PrivateContextProviders` 的 app 适配器：`shortTermMemory`→`repository.getShortTermMemory`、`personalMemory/relationshipMemory/publicMemory`→`MemoryV2Pipeline`、`knowledgeContext`→`KnowledgeBaseContextBuilder`、`groupContext`/`stableImpression`→现有 VM 调用，以及 `userProfileProvider`→`appState.userProfile`。建议单独开一张「prompt shim」小卡，行为以 VM `buildApiMessages` 输出为金样 diff（可用 T18 金样件 + 现有会话）。
2. **LoadingChanged 事件未发射**：接线层应订阅 `engine.loadingSessions` StateFlow 驱动 `_loadingSessions`；若要事件化，改 `beginLoading/finishLoading` 发射事件需先跑 shared 全量（事件顺序断言可能受影响）。
3. **失败路径诊断**（T28 §5.2-7 / T19 §6-1 遗留）：`Undelivered` 仍无 `ReplyDiagnostics`；失败轮 `finishDebugRound` 等价数据缺。迁移 sendMessage 前建议补齐（诊断 port 或 Undelivered 携带失败阶段）。
4. **逐入口接线的推荐顺序**（在 prompt shim 完成后）：`resumePersistedReply` → `retryFailedMessage` → `cancelSessionRequests`（用 `ANDROID_BASELINE_SEMANTICS`，D1 已冻结）→ `regenerate/continue` → `sendMessage`（最后，同批迁移 `_messages` 乐观行/草稿/DebugLogger/结算副作用）→ 图片/礼物。每步沿用本卡方法：先升级 app 基线断言、再替换、全量 app 测试。
5. **群聊（步骤 4，0/7）**：需先实现 `GroupChatExtras`（成员权限/记忆清理/自动计划存储等）与 `GroupContextSource`（名册/可选上下文块）的 app 生产适配器，再建 `ChatEngineFactory.createGroupEngine`。D3 语义（`GroupChatSemantics(allMutedReportsStored=true)`）与群聊事件映射表已就绪，可直接复用。
6. **VM 层超时用例**仍缺（T28 遗留：预算常量为私有常量，不可注入）；建议随 sendMessage 迁移把 `ChatEngineConfig` 注入 VM（本卡工厂已具备 config 参数）。
7. **Robolectric 首次下载**与共享模块并行代理编译窗口排队的纪律沿用 T28/T19 记录。

---

## 7. 变更文件一览（git 未提交）

```
A  app/src/main/java/com/rhodes/privatechat/viewmodel/ChatEngineFactory.kt        (164)
A  app/src/main/java/com/rhodes/privatechat/viewmodel/ChatEngineEventMapping.kt   (138)
M  app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt            (+22/-4：惰性引擎 + 撤回两入口委托)
M  app/src/test/java/com/rhodes/privatechat/viewmodel/ChatViewModelCorePathTest.kt (+D1 硬断言)
M  app/src/test/java/com/rhodes/privatechat/viewmodel/GroupChatViewModelCorePathTest.kt (+D3 硬断言)
A  app/src/test/java/com/rhodes/privatechat/viewmodel/ChatEngineFactoryTest.kt    (380，7 例)
M  shared/src/commonMain/.../domain/chat/ChatEngine.kt                            (+semantics + finishSendPipeline 抽取)
M  shared/src/commonMain/.../domain/chat/ChatEngineProtocols.kt                   (+ChatEngineSemantics)
M  shared/src/commonMain/.../domain/chat/group/GroupChatEngine.kt                 (+semantics，D3 回调)
M  shared/src/commonMain/.../domain/chat/group/GroupChatProtocols.kt              (+GroupChatSemantics)
M  shared/src/commonTest/.../domain/chat/TestChatSupport.kt                       (+semantics 透传参数)
M  shared/src/commonTest/.../domain/chat/group/TestGroupSupport.kt                (+semantics 透传参数)
A  shared/src/commonTest/.../domain/chat/ChatEngineSemanticsTest.kt               (119，4 例)
A  ios-port/handoffs/impl-batch-c2.md（本文件）
```

- 生产行为改动仅两处（均为本卡步骤 3 授权范围）：`ChatViewModel.recallMessage`/`recallMessageSegment` 的 DB 变更改走引擎（DB 结果与 oracle 一致，测试锁定）；其余为新增文件/可选参数（默认值不变）/测试。
- 未触碰：`GroupChatViewModel`、`shared/iosMain`、`iosApp/`、备份/记忆/提示词等并行代理写域。
- 构建纪律：全程 `source ../ios-port/toolchain/env.sh` + gradle 前 `pgrep`（队列等待 T34 框架链接/xcodebuild，30s 轮询）+ 一律 `--no-daemon`；未 git commit。

## 8. 给 lead 的确认点（**lead 已逐条确认，2026-11-23，本批收口**）

1. **D2 判定 —— 接受**：本卡实测确认「生产 ChatStore 上 D2 无差异（同事务级联删租约行）」，因此**不改引擎、不加撤回开关**，仅 `recallDropsTurnRow=true` 契约声明；结论已实证，T28 §3.2 D2 行收口。
2. **适配层默认语义 —— 维持 `ANDROID_BASELINE_SEMANTICS`（D1 legacy）**：Android 生产必须与 oracle 行为一致（取消不写 DB）；**iOS 是否采用引擎原生取消语义（send_failed + 租约 pending，可 reconcile）属产品行为决策，留 Phase 4/5 显式裁决，不在适配层默认里改**。
3. **步骤 3 完成度 —— 接受**：按任务卡兜底条款记录 2/9 入口已委托、7 类入口阻断理由入表；**批 C 剩余继续列为后续专项**（阻断项：prompt shim / 状态所有权 / 宿主结算副作用），后续批次按本 handoff §6 建议顺序推进。

> 附：本卡报告的 Gradle `TestOutputStore` 脏索引坑（§5.4-3）已由 lead 记录，后续遇到同症状先清 `shared/build/test-results/<task>` 再跑。
