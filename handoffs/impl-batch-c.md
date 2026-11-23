# T28 批 C 专项 handoff：编排层委托（步骤 0+1 完成，步骤 2/3 判定延后）

- 任务：`task-28`（Phase 2 首个专项，接线批 C）；代理：`impl-batch-c`
- 分支 `ios-port`（Kotlin 2.3.21），**git 未提交**（lead 统一提交；当前工作区仅本卡改动，T29/T30 已由 lead 提交为 `d8767b4`/`736fe86`）
- 结论：**步骤 0（接线缝 + 诊断事件字段）完成**；**步骤 1（ChatViewModel 测试替身 14 例 + 群聊测试替身 7 例）完成并全绿**；**步骤 2/3（逐入口替换）未执行**——依据本卡新测试实测的两处基线行为差异与既有 B2/B3/B4 阻断项，逐入口替换仍无安全落地路径，按任务卡兜底条款如实记录，Gate 由 lead 依本 handoff 裁定。
- 写域实际改动：`shared/data/MessageRepository.kt`（步骤 0 显式要求，见 §7 open question 1）、`shared/domain/chat/{ChatEngineProtocols,ChatEngine}.kt`（仅诊断事件字段）、`shared/commonTest`（1 新文件 + harness 小改）、`app/src/test/.../viewmodel`（3 新文件）、`app/build.gradle.kts`（仅测试依赖，见 §7 open question 2）、`ios-port/handoffs`。**未改动 ChatViewModel/GroupChatViewModel 任何一行生产逻辑**，未触碰 T29/T30 写域。

---

## 1. 步骤 0 成果

### 1.1 暴露 `MessageRepository` 唯一 allocator 实例（接缝，严禁第二实例）

| 项 | 内容 |
|---|---|
| 文件 | `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/MessageRepository.kt:70` |
| API | `val allocator: MessageIdAllocator get() = messageIdAllocator`（返回既有 `by lazy` 实例，**没有新建**） |
| 契约注释 | 明确「严禁第二 allocator（独立 Mutex + 内存水位 → 并发撞 id）」+「取用方必须惰性/IO 车道首次取用，不得在 Application 启动主线程触发开库」 |
| 验证 | `app/.../ChatViewModelCorePathTest.messageRepositoryExposesSingleAllocatorInstance`：同一实例断言（`assertSame`）+ `getNextMessageId()` 与 `allocator.allocate()` 共享水位（连号） |

### 1.2 诊断事件字段（lead 裁决 §7.1：开放 `shared/domain/chat`）

| 项 | 内容 |
|---|---|
| 新类型 | `ReplyDiagnostics`（`ChatEngineProtocols.kt:140`）：`mode / promptMessageCount / historyMessageCount / contentRetryCount / segmentCount / visibleCount / protocolComplete`，即 `finishDebugRound`（oracle VM:1641/:1665）所需、原先只能从引擎内部量拿到的那部分 |
| 事件携带 | `EngineEvent.ReplyStored.diagnostics: ReplyDiagnostics? = null`（:179）、`EngineEvent.Settled.diagnostics: ReplyDiagnostics? = null`（:190）——默认 null，既有调用点/测试零改动 |
| 引擎填充 | 私聊主流程 `ChatEngine.kt:446`（ReplyStored/Settled 共用同一快照）；图片管线 `ChatEngine.kt:777`（mode/构建消息数/历史轮数/段数/可见数/协议完整性） |
| 宿主导出边界（明确留给接线层） | 模型用量/缓存统计（StreamingClient 包装层的 `ChatCallResult`）、溯源摘要（PromptAssembler shim）、`promptHistoryStats`（宿主历史窗口统计）——引擎不感知，KDoc 已写明 |
| 失败路径遗留 | `Undelivered`（无可见回复/超时/取消）未携带诊断快照：失败轮 `finishDebugRound` 的 prompt 计数/retryCount/失败原因仍缺。属**后续批次**扩展点（本卡按任务卡只改 Settled/ReplyStored，未扩大写域） |

测试（`shared/commonTest/.../ChatEngineDiagnosticsTest.kt`，4 例 × 双路径）：
1. `successfulPrivateTurnCarriesDiagnosticsOnReplyStoredAndSettled`：字段值逐项等于引擎内部量；Settled 与 ReplyStored 携带同一快照；`visibleCount` 与结算一致；
2. `contextDegradationRetryIsReflectedInDiagnostics`：上下文超限降级一次 → `contentRetryCount=1`、`historyMessageCount=7`（15/2，floor 5）；
3. `imageTurnCarriesDiagnostics`：图片管线 replyStored 也带快照；
4. `undeliveredPathDoesNotEmitReplyStoredDiagnostics`：无可见回复 → 不发 ReplyStored/Settled（失败路径诊断遗留的显式锁定）。

---

## 2. 步骤 1 成果：ViewModel 测试替身（回归网）

### 2.1 基础设施（`app/src/test/java/com/rhodes/privatechat/viewmodel/ChatTestSupport.kt`，342 行）

| 组件 | 做法 | 说明 |
|---|---|---|
| Android 宿主 | Robolectric 4.14.1（`@Config(application = Application::class, sdk=[34])`）+ `WorkManagerTestInitHelper` | 真实 `Application`+真实 SQLDelight Android 驱动（临时文件库，测试间隔离）；WorkManager 供 VM 成功路径的 `ManualReplyScheduler/ContextMaintenanceScheduler` |
| 设置存储 | `InMemoryObservableSettings`（手写，等价 shared `androidUnitTest` 同名实现） | 不引 multiplatform-settings-test；确定性、可断言 |
| 模型替身 | `FakeModelServer`：Ktor `MockEngine` + `AIService(client = …)` | **走真实 AIService 解析/错误分类链**；`reply/failMessage/delayMs` 可编程，`requests` 可观测；无网络 |
| 仓库 | 真实 `ChatRepository(DatabaseWrapper(application), settings)` | 不 mock DB/仓库，断言落在真实表（chat_messages/reply_turns/chat_sessions/chat_archives/settings） |
| 其他 | 真实 `SharedUtils/OperatorStateUpdater/AppStateHolder`；`memoryVectorService=null`、`visionGateway=null`、Koin 缺失（VM 内部 try/catch 已容错）；`MemoryV2/每日摘要` 在 env 关闭避免后台模型调用争用替身 | |
| VM 构造 | `createViewModel()` / `createGroupViewModel()`（与生产 `MainViewModel` 同参接线，含 prompt 模板/模块 lambda） | 群聊 7 例复用同一 env |
| 等待策略 | `waitUntil { suspend block }` 轮询 + `sendMessage/onResponseComplete` CompletableDeferred | 不依赖虚拟时间；VM 内部墙钟/预算不可注入（见 §5 建议） |

测试依赖（`app/build.gradle.kts:107-116`，仅测试类路径，APK 无变化）：
`robolectric:4.14.1`、`kotlinx-coroutines-test:1.9.0`、`ktor-client-mock:3.0.3`、`ktor-client-content-negotiation:3.0.3`、`ktor-serialization-kotlinx-json:3.0.3`、`work-testing:2.10.0`；`testOptions.unitTests.isIncludeAndroidResources = true`。
首次运行需下载 Robolectric `android-all-instrumented`（SDK 34，约百 MB，本机已验证可用；此后单测套件 ~10s）。

### 2.2 私聊 `ChatViewModelCorePathTest`（14 例，全绿）

| # | 用例 | 锁定的基线行为 |
|---|---|---|
| 1 | `messageRepositoryExposesSingleAllocatorInstance` | 步骤 0a 单实例契约 |
| 2 | `sendMessage_blankTextIsNoOp` | 空文本零副作用 |
| 3 | `sendMessage_withoutSessionToastsRestoreHint` | 「聊天正在恢复」Toast |
| 4 | `sendMessage_missingModelConfigBlocksBeforePersisting` | 配置守卫先于落库 |
| 5 | `sendMessage_success_persistsReplyAndSettles` | 用户行+ai_json 行+租约 succeeded+亲密度+回合状态 |
| 6 | `sendMessage_modelError_marksSendFailedAndReleasesTurn` | 厂商 500 → send_failed + 租约回 pending + 无 AI 行 |
| 7 | `retryFailedMessage_afterFailure_recoversAndStoresReply` | 重试成功、不新建用户行、租约 succeeded |
| 8 | `cancelSessionRequests_duringSlowModel_marksSendFailedAndStopsLoading` | 取消 → 回调 false、无 AI 行；**实测基线 userType=text / turnStatus=running**（见 §3.2 差异 D1） |
| 9 | `resumePersistedReply_completesInterruptedTurn` | 恢复 persisted reply：失败类型重置、AI 行、租约 succeeded |
| 10 | `recallMessage_deletesSourceMessageAndCancelsTurn` | 源行删除、会话预览回退；**实测基线租约行被连带删除**（差异 D2） |
| 11 | `recallMessageSegment_marksOnlyTargetSegmentRecalled` | 目标段 `recalled:true`、首段不受影响 |
| 12 | `regenerateAiMessage_replacesReplyInPlace` | 原行原地替换、行数不变、无新增用户行 |
| 13 | `continueAiMessage_appendsNewReplyWithoutNewUserRow` | 追加新 ai_json、不新建用户行 |
| 14 | `createCurrentArchive_savesSnapshotForCompletedRound` | 存档落库、标题/快照内容正确 |
| — | 超时 | **未覆盖**：VM 预算为私有常量（180s/50s/15s），不可注入；超时矩阵由 shared 引擎 12 例（`ChatEngineTimeoutTest`）覆盖。VM 层超时需先给 VM 注入可配置预算/时钟（见 §5 建议 3） |

### 2.3 群聊 `GroupChatViewModelCorePathTest`（7 例，全绿——步骤 3 的「先建测试替身」部分）

| # | 用例 | 锁定行为 |
|---|---|---|
| 1 | `sendGroupMessage_success_persistsTaggedReply` | 用户行+群 ai_json 条目+租约 succeeded（JSON 数组协议回放） |
| 2 | `sendGroupMessage_missingConfig_blocksBeforePersisting` | 配置守卫 + `lastSendError` |
| 3 | `sendGroupMessage_unparseableReply_marksSendFailed` | 解析失败 → send_failed、无 ai_json |
| 4 | `retryFailedMessage_afterGroupFailure_recovers` | 群重试成功、不新建用户行 |
| 5 | `resumePersistedReply_completesInterruptedGroupTurn` | 群恢复：类型重置、ai_json、租约 succeeded |
| 6 | `recallMessageSegment_marksTargetSegmentRecalled` | `setCurrentGroup` 加载后分段撤回，目标段 recalled、首段保留 |
| 7 | `sendGroupMessage_allMembersMuted_returnsSystemMessageWithoutModelCall` | 全员禁言：系统消息、零模型调用；**实测基线回调 true**（差异 D3） |

---

## 3. 步骤 2/3：未执行（判定 + 本卡实测证据）

**判定**：仍**不能**逐入口安全替换。T27 的 B1-B6 阻断项在本卡完成后只剩 B1（诊断事件）被拆除；B2（双编排器争用租约/内存状态）、B3（提示词 shim）、B4（StreamingClient/allocator 接线）、B5（结算副作用映射）、B6（回归网——现已就绪）仍在。且本卡测试实测到「同入口、两套实现语义已不同」的三处硬证据：替换后**必然**改变可观测行为，必须整组替换 + 契约迁移，逐入口小步替换会得到绿但语义漂移的假门禁。

### 3.1 逐入口替换/回退对照表（全部未替换，无回退动作）

私聊（`ChatViewModel.kt` → `ChatEngine.kt`，行号见 `impl-wiring-b.md` §3.2）：

| 入口 | 目标引擎 API | 状态 | 阻断项 | 替换前必须完成 |
|---|---|---|---|---|
| `sendMessage` | `sendMessage`/`runSendPipeline` | **未替换** | B2 B3 B4 B5 + D1/D2 | 适配层（§5 步骤 1-3）+ 事件映射表 |
| `cancelSessionRequests` | `cancelSessionRequests` | 未替换 | B2 + **D1** | 状态所有权转移：VM 在飞 job/generation 必须一并交给引擎，否则取消无效 |
| `retryFailedMessage` | `retryFailedMessage` | 未替换 | B2 B5 | 同上；suspend 防抖语义差（引擎 suspend vs VM fire-and-forget） |
| `regenerateAiMessage` | `regenerateAiMessage` | 未替换 | B1(已解) B3 B5 | PromptAssembler shim；`replaceReplyIfUnchanged` 乐观锁已由引擎实现 |
| `resumePersistedReply` | `resumePersistedReply` | 未替换 | B4 B5 | VisionGateway/StreamingClient 接线 |
| `recallMessage` | `recallMessage` | 未替换 | B2 + **D2** | 租约终止态语义对齐（基线删行 / 引擎 cancelled） |
| `recallMessageSegment` | `recallMessageSegment` | 未替换 | B2 + D2 | 分段 JSON 契约一致（已由 ReplyCodec 提供） |
| `continueAiMessage` | `continueAiMessage` | 未替换 | B3 B5 | 同 sendMessage |
| `sendImageMessage`/`resumeImageReply` | 同名 | 未替换 | B4 B5 | vision 端口注入 |
| 存档/删除会话 | 引擎无 API | 保留 VM | — | 非本卡范围 |
| `buildApiMessages`（3034-3501） | `PrivatePromptAssembler` | 未替换 | B3 | 先 shim 包住现有实现（行为不变），再切真实 provider |

群聊（`GroupChatViewModel.kt` → `GroupChatEngine.kt`）：

| 入口 | 状态 | 阻断项 | 备注 |
|---|---|---|---|
| `cancelGroupRequests`（**private**，`GroupChatViewModel.kt:261`） | 未替换 | B2 + D3 | 目标 API 为引擎公开方法；替换前需决定是否公开 VM 入口（现有测试无法直接驱动该入口） |
| `sendGroupMessage`/`executeGroupTurn` | 未替换 | B2 B3 B4 B5 | 需 `GroupChatExtras`/`GroupContextSource` 端口实现 |
| `retryFailedMessage`/`resumePersistedReply` | 未替换 | B2 B5 | 测试替身已就绪（本卡 #4/#5） |
| 图片/礼物 | 未替换 | B4 | vision 注入 |
| `recallMessageSegment` | 未替换 | B2 | 情绪快照重算/上下文重建改事件驱动 |
| 重启/删群 | 未替换 | B5 | `GroupChatExtras` 记忆清理注入 |
| 自动互动循环 | 未替换 | A7 胶水 | Timer/WorkManager 壳保留，计划纯逻辑用 `AutoChatPlanPort` |

### 3.2 本卡实测的三处「两套实现行为已不同」证据（替换时必须对齐）

| # | 场景 | 基线 VM 实测 | 引擎实现（T19/T23 测试锁定） | 影响 |
|---|---|---|---|---|
| **D1 取消** | 慢模型在飞时 `cancelSessionRequests` | 回调 false、无 AI 行；但 `CANCEL_BASELINE userType=text turnStatus=running`——catch/finally 里的 DB 写入在已取消协程中被跳过，**用户消息未置 send_failed、租约未释放** | `send_failed` + 租约 release 回 pending（`ChatEngineRecoveryTest.cancelDuringSend_…`） | 直接换引擎 → 取消后消息类型/租约状态变化；UI「发送失败可重试」语义随之变化 |
| **D2 撤回** | 撤回用户消息 | `MessageRepository.deleteMessage` 连带删除 `reply_turns` 行（本卡测试 `assertNull`） | 引擎保留 `cancelled` 终止态租约（不可 reconcile，`ReplyTurnStateMachine` 契约） | 换引擎 → 租约生命周期变化；启动 reconcile/孤儿清理输入集合变化 |
| **D3 全员禁言** | 群聊全员禁言发送 | `responseStored=true` → `onResponseComplete(true)`，系统消息落库、零模型调用 | 引擎：系统消息 + `complete` 租约（语义接近） | 回调语义需逐条对齐后再迁移 |

> 结论：**逐入口替换必须先完成「入口整组 + 状态所有权 + 事件→宿主副作用映射」的设计冻结**，否则本卡新回归网会如实变红或（更糟）绿着漂移。T27 §3.3 的 8 步顺序仍然有效，建议 lead 在批 C 排期时按「适配层（1 人日）→ 事件映射（0.5 人日）→ 恢复类入口 → 撤回类 → 重新生成/续写 → sendMessage/图片 → prompt 切换 → 群聊同构」推进。

---

## 4. 测试与门禁证据（本机实测，`--no-daemon`，gradle 前 `pgrep`）

| 门禁 | 命令 | 结果 |
|---|---|---|
| app 单测 | `./gradlew --no-daemon :app:testDebugUnitTest` | **345 tests / 0 failures / 0 errors（41 class）** = 基线 324 + 本卡 21（私聊 14 + 群聊 7）；XML 聚合实测 |
| app 打包 | `./gradlew --no-daemon :app:assembleDebug` | ✅ `app/build/outputs/apk/debug/app-debug.apk`（102,514,001 B；任务 UP-TO-DATE，输入未变，含本卡 shared 改动） |
| shared JVM | `./gradlew --no-daemon :shared:testDebugUnitTest --rerun-tasks` | **504 tests / 0 failures**（含本卡诊断 4 例；总数含 T30 新增，见 §7 注） |
| shared iOS | `./gradlew --no-daemon :shared:iosSimulatorArm64Test --rerun-tasks`（iPhone 17 已 boot） | **532 tests / 0 failures**（含本卡诊断 4 例；总数含 T30 新增） |
| 诊断事件双路径 | JVM/iOS test-results XML 均含 `ChatEngineDiagnosticsTest`（4 tests / 0 failures） | ✅ |
| 步骤 0a 单实例 | `ChatViewModelCorePathTest.messageRepositoryExposesSingleAllocatorInstance` | ✅ |

> 口径说明：`474/502` 是本卡开始时的 shared 基线；T30（`736fe86`）并行新增了 chat-import/角色卡/备份恢复测试，故当前树共享层总数为 504/532，**不是本卡引入的漂移**（本卡 shared 净增 4 例）。app 侧 324→345 全部为本卡新增。

---

## 5. 未完成项 + 给后续批次的建议（可直接照做）

### 5.1 未完成（如实记录）

1. **步骤 2（私聊逐入口替换）0/9 完成**，无回退（未动生产代码）。
2. **步骤 3（群聊逐入口替换）0/7 完成**；仅完成其前置「测试替身」7 例。
3. VM 层「超时」用例未覆盖（预算常量私有，不可注入）；由 shared 引擎超时矩阵覆盖。
4. 失败路径诊断（`Undelivered` 无 `ReplyDiagnostics`）未做，失败轮 finishDebugRound 的等价数据仍缺。
5. 群聊 `cancelGroupRequests` 为 private，无法测试/委托，需下一批决定是否公开。

### 5.2 下一批（批 C 续）执行清单

1. **先冻结事件映射表**：`EngineEvent` 11 种 + `GroupEngineEvent` 12 种 → VM 现有 UI/诊断/结算行。本卡已给 `ReplyStored/Settled.diagnostics`，失败/超时路径需要新增 `Undelivered/Toast` 诊断字段（或 diagnostic port）。
2. **ChatEngineFactory（app 新文件，不改 VM 行为）**：`DatabaseWrapper` → `repository.messages.allocator`（**仅此单例**，惰性取用）、`RepositoryChatStore(ChatRepository)`、`DefaultReplyCodec`、`StreamingClient` 包 `AIService.chat/chatResult`（保持 deepseek thinking/温度/超时参数）、`PromptAssembler` 先 shim 现有 `buildApiMessages`（行为不变）、`visionAnalyze/imageForModelResolver/chatConfigError` 注入、`ChatEngineConfig` 由 settings 预算/深度思考加成构造。
3. **替换顺序**（每步：先改对应 VM 测试断言为「引擎契约」，再换实现，跑 `--tests "*ChatViewModelCorePathTest*"` + 全量 app）：
   `resumePersistedReply` → `retryFailedMessage` → `cancel` → 撤回（D2 语义按引擎 cancelled 对齐）→ `regenerate`/`continue` → `sendMessage`/`sendImageMessage`（最后）→ 提示词 shim 切 `PrivatePromptAssembler`。
4. **失败/取消契约变更**：替换 cancel 时，`ChatViewModelCorePathTest#cancelSessionRequests_…` 的基线断言（当前仅记录 userType/turnStatus 打印）应改为断言 `send_failed` + 租约 pending（引擎契约，D1）；替换撤回时改 `assertNull(turn)` 为 `assertEquals("cancelled", turn.status)`（D2）。**这两处是本卡有意留下的契约迁移点。**
5. **新增 VM 超时用例**：给 VM 注入 `ChatEngineConfig`/`ChatClock`（或先抽 `sendMessage` 到可注入类），再用 `TestTimeSource` 驱动；否则超时回归只能靠 shared 引擎。
6. **群聊**：同构执行；`cancelGroupRequests` 公开化或改由引擎 API 直接驱动；自动互动最后（DelayedTaskLayer 胶水）。
7. **失败诊断补齐**：给 `Undelivered`（以及超时路径）加 `ReplyDiagnostics?`/失败原因/阶段字段，达成失败轮 finishDebugRound 等价。

### 5.3 测试基线维护提醒

- 本卡测试断言的是**当前实现**（含 D1/D2/D3 三处已记录的实现怪癖），不是理想契约。替换入口时按 §5.2-4 逐条升级断言，禁止「为了让新实现变绿而放宽断言」。
- 新测试若需真实模型响应，直接改 `FakeModelServer.reply/failMessage/delayMs`；群聊用 JSON 数组协议（`GroupMsgResult` 字段）最稳。
- 单测首次运行会下载 Robolectric android-all（SDK 34）；离线环境需预置或改用已缓存机器。

---

## 6. 变更文件一览（git 未提交）

```
M  app/build.gradle.kts                                        (+测试依赖/测试资源开关)
M  shared/src/commonMain/.../shared/data/MessageRepository.kt  (+9：allocator 接缝)
M  shared/src/commonMain/.../domain/chat/ChatEngineProtocols.kt (+ReplyDiagnostics + 2 事件字段)
M  shared/src/commonMain/.../domain/chat/ChatEngine.kt         (+~20：两处 emit 填充)
M  shared/src/commonTest/.../domain/chat/TestChatSupport.kt    (+3：aiClientOverride 测试参数)
A  shared/src/commonTest/.../domain/chat/ChatEngineDiagnosticsTest.kt (106 行，4 例)
A  app/src/test/java/com/rhodes/privatechat/viewmodel/ChatTestSupport.kt (342 行)
A  app/src/test/java/com/rhodes/privatechat/viewmodel/ChatViewModelCorePathTest.kt (307 行，14 例)
A  app/src/test/java/com/rhodes/privatechat/viewmodel/GroupChatViewModelCorePathTest.kt (143 行，7 例)
A  ios-port/handoffs/impl-batch-c.md（本文件）
```

生产行为零改动（ChatViewModel/GroupChatViewModel 未改）；`assembleDebug` 产物仅含步骤 0 的 shared 改动。

## 7. Open questions（交 lead）

1. **写域例外**：步骤 0 明确要求暴露 `shared/data/MessageRepository` allocator（任务卡写域列表未列 `shared/data`），已按任务卡执行并保持最小 diff。请确认该例外。
2. **`app/build.gradle.kts` 改动**（任务卡写域未列）：VM 测试替身必须引入 Robolectric/MockEngine/coroutines-test/work-testing（仅 `testImplementation` + `isIncludeAndroidResources`），否则 Android ViewModel 无法在 JVM 单测构造（现有 324 例不碰 Android 框架）。请确认。
3. **并行代理写域交叉**：本卡执行期间 T29/T30 正在写 `shared/backup/{operatorcard,chatimport}`、`shared/iosMain/iosbridge`、`app/data/backup` 等，曾导致 2 次 app/shared 编译被其半成品打断（本卡未触碰/回退其文件，等待其完成后复跑全绿）。建议 lead 后续把跨卡共享模块的编译窗口显式排队。
4. **Robolectric 首次下载**：新增测试依赖会引入 `org.robolectric:android-all-instrumented:14-…` 的运行时下载（maven central）。若 CI/离线环境不可联网，需要预置缓存或改用 Robolectric 离线 jar；本机已验证可用。
5. **失败路径诊断**：`Undelivered` 是否允许批 C 续给 `shared/domain/chat` 再加工（诊断字段），决定失败轮 finishDebugRound 能否等价。

## 8. 构建纪律记录

- 全程 `source ../ios-port/toolchain/env.sh` + 每次 gradle 前 `pgrep`（有一次因并行 T29 xcodebuild/gradle 正在运行，采用 sleep 轮询排队）；一律 `--no-daemon`。
- 修复轮次：step 0 shared 测试 1 轮（undelivered 断言口径）；app 测试基建 3 轮（multiplatform-settings 1.2.0 listener API/Dispatchers.setMain import/ktor content-negotiation 依赖）；VM 用例 2 轮（基线行为断言口径修正，均属测试口径而非生产改动）。均在可控范围。
- 未提交、未触碰 T29/T30 写域文件、未改动 ChatViewModel/GroupChatViewModel 生产逻辑。
