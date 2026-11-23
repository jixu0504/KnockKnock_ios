# T27 接线批 B：编排层委托 + 收口（handoff）

- 任务：`task-27`（Phase 1 接线批 B，Gate 1 收口）；代理：`impl-wiring-b`
- 分支 `ios-port`（Kotlin 2.3.21），**git 未提交**（lead 统一提交）
- 结论：**Step 1（收口三小项）完成并全绿；Step 2（ChatViewModel→ChatEngine）与 Step 3（GroupChatViewModel→GroupChatEngine）经可行性验证后整体延后到批 C**，理由见 §3（含 file:line 证据）。Gate 1 判定请 lead 依此 handoff 裁定。
- 写域实际改动：`shared/.../data/MessageRepository.kt`、`shared/.../domain/memory/MemoryDiagnostics.kt`（诊断接口）、`shared/src/commonTest`（2 个测试文件）、`app/.../viewmodel/shared/MemoryV2Pipeline.kt`（适配器）+ 删除 3 个 app 死文件。未触碰 `shared/domain/chat`、`shared/domain/chat/group`、`shared/settings`、build 配置。

---

## 1. Step 1 完成清单

### 1.1 `MessageRepository.getNextMessageId` → `MessageIdAllocator`（消除双公式）

| 项 | 改前 | 改后 | 证据 |
|---|---|---|---|
| 分配公式 | `MessageRepository.kt:296-305` 内联三源 max 公式（`idMutex` + 内存 `nextMessageId` + DB MAX + persisted） | 委托 `MessageIdAllocator.allocate()`（T17 交付的 oracle 逐行复刻），本文件删掉第二份公式 | `MessageRepository.kt:59`（lazy 构造）、`:306-308` |
| 水位维护点 | 4 处 `nextMessageId = maxOf(nextMessageId ?: 0, id+1)`（sendMessage/sendMessageAndCreateReplyTurn/sendReplyAndCompleteTurn/restoreMessage） | 4 处改 `messageIdAllocator.observeUsed(message.id)`（restore 处的 `rememberMaxUsedMessageId` 并入） | `:180`、`:205`、`:253`、`:260` |
| 双份持久化 helper | `persistedMaxUsedMessageId` / `rememberMaxUsedMessageId`（:290-294） | 删除（`SettingsMaxIdStore` 单一来源，键名 `max_used_message_id` 不变） | 全文已无旧符号 |

实现细节与等价性论证：
1. **公式单一来源**：`allocate()` 与 oracle :296-305 逐行一致（三源 max → 内存水位 next+1 → persisted 只增写回），T17 已有 8 例覆盖（并发 1000 唯一/删除不回退/三源自愈/恢复金样）。本卡新增第 9 例做**同序列两种实现等价性比对**（§1.1.1）。
2. **串行语义保留**：`getNextMessageId` 仍持 `idMutex`（与 4 个写路径同锁），并在 `withContext(DatabaseDispatcher.dispatcher)` 内分配，保持原 `DatabaseDispatcher.execute` 的「单车道 SQL 执行」线程模型；唯一差异是 `DatabaseDispatcher.snapshot().runningTask` 不再显示 `message_allocate_id`（仅诊断页展示细节，无行为影响，见 §6 open question 3）。
3. **两处事务内水位点外移**：`sendMessageAndCreateReplyTurn` 在 `DatabaseDispatcher.execute` 前 observeUsed；`sendReplyAndCompleteTurn` 在 `completed==true` 后 observeUsed。oracle 的 Kotlin 赋值本就不参与 SQL 回滚，外移不改变可见结果：失败事务只会少跳号（不会造成 id 复用，因为行未落库），成功事务结果逐字节一致。
4. **lazy 构造**（关键启动路径保护）：`db` 是 `wrapper.database` getter，如果立即构造 `allocator` 会触发 Android 侧 `DatabaseWrapper` 惰性开库（`prepareBeforeOpen` + 驱动创建）从 Application 的 IO warmup 挪到主线程。改为 `by lazy` 后首个 id 操作才初始化，启动时序与改造前一致。
5. **已知保守差异**：`observeUsed` 同时写 persisted（T17 handoff §3.1 已记录的唯一差异），即发送路径比 Android 更早持久化水位；只增不减、不改变任何 allocate 结果。新增的等价性用例末尾断言两侧 persisted 水位一致。

#### 1.1.1 等价性测试（新增 1 例 × 双路径）

`shared/src/commonTest/.../domain/data/MessageIdAllocatorTest.kt` 新增
`allocatorMatchesOracleInlineFormula_onSameOperationSequence`：
- 测试内置 oracle 原内联公式的**逐行参照实现** `OracleMessageIdFormula`（发送路径只抬内存、restore 才持久化，与 oracle 一致）；
- 24 步同序列（allocate / insert / delete / observeUsed 交叠，含 DB max 回退、persisted 高水位、observe 超过 DB max）分别驱动两个独立 env 的「参照实现」与「MessageIdAllocator」；
- 断言：每步 allocate 结果相等、序列结束 `max_used_message_id` 相等、**冷启动新实例首分配相等**（跨重启不撞 id）。
- 实测：`MessageIdAllocatorTestJvm` 9/9、`MessageIdAllocatorTestIos` 9/9（原 8 + 本卡 1），0 失败。

### 1.2 `MemoryDiagnostics` 诊断接口恢复（operation 时间线接回 DebugLogger）

| 文件 | 改动 |
|---|---|
| `shared/.../domain/memory/MemoryDiagnostics.kt` | 新增 `MemoryOperationSink` 接口（`beginOperation`/`conversationStep`，:57-61）+ `installOperations(...)`（:27）；`beginOperation`/`conversationStep`（:41-46）由 no-op 改为转发 sink，未安装时保持原 no-op（返回空 id、不记录）。log/trace 通道与 `allowSensitiveTrace` 门禁不动。 |
| `app/.../viewmodel/shared/MemoryV2Pipeline.kt` | `init` 内 `installOperations(object : MemoryOperationSink { ... })` 把两个回调原样转发 `DebugLogger.beginOperation` / `DebugLogger.conversationStep`（:49-57），恢复诊断页「私聊/群聊记忆提取」operation 卡片（T26 handoff §6.1 / T24 handoff §8.3 的遗留项）。 |
| `shared/src/commonTest/.../domain/memory/MemoryDiagnosticsTest.kt`（新增） | 3 例：默认 no-op；安装 sink 后 begin/step 按序原样转发；log/trace + 敏感 trace 门禁不变。 |

调用点（`MemoryV2Pipeline.kt` 34 处 `MemoryDiagnostics.*`，其中 `beginOperation` 2、`conversationStep` 16）零改动，语义恢复为 oracle 的 DebugLogger 时间线。

### 1.3 清理 0 引用死代码（先 grep 再删）

删除前全仓 grep（含 `app/src/test`、`shared`）确认除自身定义外 **0 引用**：

| 删除文件 | 行数 | 引用核验 |
|---|---|---|
| `app/.../viewmodel/shared/MemoryPromotionLocks.kt` | 4 | app 内仅自身；shared 的同名对象是 T24 正式件且被 shared pipeline 使用（不受影响） |
| `app/.../viewmodel/shared/MemoryPrivacyPolicy.kt` | 63 | app 内仅自身；shared 的同名对象 + 测试为 T24 正式件（不受影响） |
| `app/.../data/repository/ChatRepository.kt` | 260 | 0 引用（`grep -rn "data.repository.ChatRepository"` 0 命中）；`TypeAliases.kt` 保留（`BfsNode` 仍被 `OperatorDetailScreen.kt:66` 使用） |

净删 327 行；`:app:compileDebugKotlin` 与 324 例 app 测试证明无编译/行为回归。

---

## 2. 测试与门禁证据（全部本机、`--no-daemon`、gradle 前 `pgrep`）

| 门禁 | 命令 | 结果 |
|---|---|---|
| app 编译 | `./gradlew --no-daemon :app:compileDebugKotlin` | ✅ |
| app 单测 | `./gradlew --no-daemon :app:testDebugUnitTest` | **324 tests / 0 failures / 0 errors**（39 个 class，XML 聚合，03:46 复跑于全部改动后） |
| app 打包 | `./gradlew --no-daemon :app:assembleDebug` | ✅ `app/build/outputs/apk/debug/app-debug.apk`（102,257,156 B，03:46） |
| shared JVM | `./gradlew --no-daemon :shared:testDebugUnitTest` | **474 tests / 0 failures**（50 class；基线 470 + 等价性 1 + 诊断 3） |
| shared iOS | `./gradlew --no-daemon :shared:iosSimulatorArm64Test`（iPhone 17 模拟器已 boot） | **502 tests / 0 failures**（55 class，03:47；基线 498 + 4） |
| 提示词金样 | `bash ios-port/golden/prompts/sync_golden.sh` | ✅「金样 diff 门禁通过」（114 件 raw + GoldenPromptTest；sync 为 no-op，git status 无 resources 变更） |
| 记忆金样 | `bash ios-port/golden/memory/sync_golden.sh` | ✅ BUILD SUCCESSFUL（`*MemoryRecallGolden*`；sync 为 no-op） |
| 平台符号扫描 | `grep -rn "android\.\|java\.text\.\|java\.util\." shared/src/commonMain --include=*.kt` | 8 处命中**全部为注释行**（SettingsRepository/PromptClock/BackupZip/PlatformLog/MemoryDiagnostics/MemoryPromotionLocks/MemoryTime 的迁移说明）；排除注释后 **0 代码命中**（`exit=1`） |

> 口径变化说明：470→474、498→502 均因本卡**新增**测试（等价性 1 例 + 诊断 3 例，双路径各 +4），非基线漂移。树内另有 T25 的 2 个未提交改动（`shared/.../domain/automation/ContextMaintenanceTask.kt`、`BackoffTest.kt`，非本卡写域、未触碰）。

---

## 3. Step 2 / Step 3：本轮不执行（整体延后批 C），证据与判定

**判定**：`ChatViewModel`/`GroupChatViewModel` 的编排层替换**不能按「逐入口替换」安全落地**——前置适配层与宿主事件契约未就绪，且任何部分替换都会造成双编排器并存的状态破坏。若强行替换，只能得到「编译绿、行为不可验证」的假门禁。按任务卡兜底条款（Step 1 完整 + 双端构建测试全绿，Step 2/3 部分回退并如实记录）执行。以下为代码级证据与批 C 方案。

### 3.1 阻断项（按严重度）

**B1 引擎事件面不足以承载宿主诊断/结算（写域外依赖）**
`ChatEngine` 只发 11 种事件（`ChatEngineProtocols.kt:129-183`）。而 `ChatViewModel.sendMessage` 的宿主逻辑大量依赖引擎不暴露的中间量：
- `DebugLogger.startConversationRound` + 每阶段 `conversationStep`、`traceFinalSaved`、`attachOperationModule("模型用量")`、`dumpThreadStacks`、`failureSnapshot`（VM:1355-1416/:1631/:1665+）；
- `finishDebugRound` 的 summary 需要 `apiMessages.size`、`retryCount`、`effectiveHistoryMessages`、`promptHistoryStats`（VM:1641）；
- 这些数据 `ReplyStored/Settled` 事件均不携带（`ReplyStored` 只有 sessionId/userMessageIds/aiMessageId，`Settled` 只有结算数值）。
→ 完整迁移需给 `shared/domain/chat` 增发诊断事件或注入 diagnostic port，**该包不在本卡写域**（卡面：禁止触碰其余 shared 包）。T19 handoff §6.1 已把它列为 open question（「宿主结算回调清单」）。

**B2 两个编排器争用同一 reply_turns 租约层 → 部分替换必破坏单写者语义**
- VM 自有内存编排状态：`chatAiJobs`/`sessionGenerations`/`pendingUserMessageIds`/`activeRequestBySession`/`activeReplySourceIds`/`retryingMessageIds`（VM:186-226）；
- 引擎内部同构状态：`ChatEngine.kt:73-86`（`sessionGenerations`/`pendingUserMessageIds`/`chatAiJobs`/`activeRequestBySession`）；
- 两者共享持久化租约 `reply_turns`（`RepositoryChatStore` vs VM 直接调 `repository.replyTurns`）。
→ 例：把 `cancelSessionRequests`（VM:1258）换成 `engine.cancelSessionRequests`（引擎:112）时，VM 自有的在飞 job 不会被引擎 generation 失效；把 `recallMessage`（VM:2083）换成引擎版（:844）时，VM 自有的在飞 job 仍会在消息删除后落库回复。**这些入口必须整组替换（含状态所有权转移），不能逐个上线。**

**B3 提示词组装前置未就绪（行为等价的关键）**
引擎要求 `PromptAssembler`（`ChatEngineProtocols.kt:290`），生产实现 `PrivatePromptAssembler`（`PrivatePromptAssembler.kt:59`）需要注入 `PrivateContextProviders`（7 个 suspend provider：短期摘要/群近况/稳定印象/个人记忆/关系网/公开记忆/知识库，:31-56）+ `userProfileProvider` + config。VM 现有 `buildApiMessages`（VM:3034-3501，约 470 行）内联了 T15 依赖的 `MemoryV2Pipeline`/`KnowledgeBaseContextBuilder`/`ChatTrace`/`ContextProvenance`/`promptHistoryStats` 逻辑，尚未抽出 provider。直接换用 `PrivatePromptAssembler` 会**静默丢失记忆/知识库/溯源注入**（提示词质量回归，且无 app 测试可捕获）。
可行的分阶段手段是「先用 `PromptAssembler` shim 包住现有 `buildApiMessages`」保证行为不变，再在批 C 末段切 `PrivatePromptAssembler`——该 shim 本身即批 C 的适配层工作。

**B4 模型/识图客户端适配层缺失**
- `StreamingClient.complete(messages, stage)`（`ChatEngineProtocols.kt:262-273`）无 app 实现；需包装 `AIService.chat`（`shared/.../network/AIService.kt:424`）并保持 DeepSeek thinking/温度/超时参数与 VM 现状一致；
- `visionAnalyze`/`imageForModelResolver`/`chatConfigError`（引擎构造参数）需接 `visionGateway`（VM:1819 的 `sendImageMessage` 路径）与 `sharedUtils.chatConfigurationError()`。
- 引擎的 `MessageIdAllocator` 需要 `RhodesDatabase` 句柄：app 侧虽可经 Koin（`RhodesApplication.kt:40`、`SharedModule.kt:25` 构造 `ChatRepository(get(), get())`）拿到 `DatabaseWrapper`，但**必须复用 `ChatRepository.messages` 里的唯一 allocator 实例**；自行再建第二个实例会出现「repo 分配未插入、引擎并发分配到同一 id」的跨实例窗口（各自独立 Mutex）。批 C 第一步应是暴露该单例（见 §4 步骤 0），而非新建。

**B5 结算/Android 专属副作用的映射表未冻结**
`Settled` 事件的宿主写入在 VM:1642-1655：`onUnhideSession`/`notifyIfBackground`/`operatorStateUpdater.updateOperatorIntimacy`/`settings.grantDailyLmb`/`decrementHypnosis`/`schedulePrivatePostReplyMaintenance`/`markUnreadIfNotCurrent`；另有 Android 专属 `ManualReplyScheduler`（VM:1443/:1632/:2102/:1877）与 `ContextMaintenanceScheduler`（VM:638/:2211）、`restartCleanupJobs` 等待逻辑（VM:1374-1418）。这些必须保留在 VM 壳并**按引擎事件的精确时序**重放；目前只有 T19 handoff §6.1 的原则性清单，没有逐事件映射与顺序约定。

**B6 没有回归网：per-entry 验证信号不存在**
`app/src/test` 共 39 个测试类/324 例，**0 个**引用 `ChatViewModel`/`GroupChatViewModel`（`grep -rln "ChatViewModel\|GroupChatViewModel" app/src/test | wc -l` = 0）。因此任务卡要求的「每入口替换后跑 `--tests "*Chat*"` 子集」在这两个 VM 上**无对应测试**，唯一的信号是编译通过——对 3829/2945 行的行为迁移而言是假绿。批 C 必须先建 VM 级测试替身（fake ChatStore/AIService）或至少建立「事件→UI 状态」映射的单测，否则回归风险不可控。

### 3.2 入口对照表（改造成本清单，供批 C 执行）

私聊（`ChatViewModel.kt` → `ChatEngine.kt`）：

| VM 入口（行） | 引擎等价 API（行） | 依赖阻断项 | 批 B 判定 |
|---|---|---|---|
| `sendMessage` 1298-1794 | `sendMessage` 145 / `runSendPipeline` 264 | B1 B2 B3 B4 B5 | 延后 |
| `cancelSessionRequests` 1258-1266 | `cancelSessionRequests` 112 | B2 | 延后 |
| `resumePersistedReply` 1796-1817 | `resumePersistedReply` 187 | B4 B5 | 延后 |
| `sendImageMessage` 1819-2006 | `sendImageMessage` 621 | B1 B3 B4 B5 | 延后 |
| `retryFailedMessage` 2150-2170 | `retryFailedMessage` 805 | B2 B5 | 延后 |
| `recallMessage` 2083-2114 | `recallMessage` 844 | B2（VM 侧 ManualReplyScheduler + 记忆上下文重建需在 job 完成后执行） | 延后 |
| `recallMessageSegment` 2173-2190 | `recallMessageSegment` 871 | 同上 | 延后 |
| `regenerateAiMessage` 2363-2421 | `regenerateAiMessage` 887 | B1 B3 B5 | 延后 |
| `continueAiMessage` 2443-2482 | `continueAiMessage` 944 | B1 B3 B5 | 延后 |
| `resumeImageReply` 2742-2756 | `resumeImageReply` 217 | B4 | 延后 |
| 存档/删除会话等 | 引擎无对应 API | — | 保留 VM（非本卡范围） |

群聊（`GroupChatViewModel.kt` → `GroupChatEngine.kt`，行号引用 T23 handoff §5）：

| VM 入口 | 引擎 API | 备注 |
|---|---|---|
| `cancelGroupRequests` 261 | `cancelGroupRequests` | 同 B2 |
| `sendGroupMessage` 817-930 | `sendGroupMessage` | 另需 `GroupChatExtras`/`GroupContextSource` 端口实现 |
| `sendHiddenGiftMessage` 1849 / `sendGroupImageMessage` 2247 | 同名 | 图片路径另需 vision 注入 |
| `retryFailedMessage` 1886 / `resumePersistedReply` 2124 | 同名 | |
| `recallMessageSegment` 393-481 | 同名 | 情绪快照重算/上下文重建意图改事件驱动 |
| `restartGroupSession` 598 / `deleteGroup` 631 | 同名 | 记忆清理经 `GroupChatExtras` 注入 |
| 自动互动循环 647-815 | `AutoChatPlanPort`/`runScheduledAutoTurn` | WorkManager/前台 Timer 壳保留，Android `GroupAutoChatWorker` 仍为宿主 |
| `GroupEmotionRules`/`GroupOutputBudget`/`GroupRuntimeContext`/`GroupTaggedReplyParser` app 副本 | shared group 包唯一副本 | 随批 C 一起删（T23 handoff §5 已列） |

### 3.3 规模与建议

- 规模：ChatViewModel 3829 行 / 9 个入口；GroupChatViewModel 2945 行 / 13 个入口；两者合计约 900-1500 行域逻辑下沉 + 约 300-500 行宿主适配/事件映射；建议按 **2-3 人日 + 独立评审**排期，且必须与 Gate 1 判定解耦。
- 建议批 C 顺序（每步独立可回退）：
  0. **接线缝**：在 `MessageRepository` 暴露唯一 `messageIdAllocator`（或由 `ChatRepository` 提供 accessor；**严禁新建第二实例**，见 B4）；补一条「同一实例」契约注释。
  1. **宿主适配层**（新文件，不改 VM 行为）：`ChatEngineFactory`（Koin `DatabaseWrapper` → allocator；`RepositoryChatStore`；`DefaultReplyCodec`；`StreamingClient` 包 `AIService.chat`；`PromptAssembler` 先 shim 现有 `buildApiMessages`；`visionAnalyze`/`imageForModelResolver`/`chatConfigError` 注入）。
  2. **事件契约**：把 11 个 `EngineEvent` → VM 现有 UI/诊断/结算行的映射冻结成表；把 B1 缺失的诊断数据补成引擎事件（需 lead 授权 `shared/domain/chat` 写域）或加 diagnostic port。
  3. **VM 测试替身**：至少为「事件→状态」与「租约/取消」建 JVM 测试，作为逐入口替换的回归网（否则维持 B6 风险）。
  4. 先换恢复类入口（`resumePersistedReply`/`resumeImageReply`/`retryFailedMessage`/`cancel`），每入口编译 + 手工冒烟；
  5. 再换撤回类（保留 Android scheduler/记忆重建在 VM 壳，以引擎返回 Job 的完成回调为时序点）；
  6. 再换重新生成/续写；
  7. 最后换 `sendMessage`/`sendImageMessage`（结算与诊断副作用按步骤 2 表迁移）；
  8. 提示词从 shim 切到 `PrivatePromptAssembler` + `PrivateContextProviders`（行为等价验收：金样 + 现有 324 例 + 新 VM 用例）。
  群聊按同构顺序执行（自动互动最后）。
- 可直接复用的已就绪资产：`RepositoryChatStore`（ChatEngine.kt:996+）、`PrivatePromptAssembler`/`PrivateContextProviders`、`FakeAiEngine`/`ChatEngineTestHarness`（shared 侧 61 例引擎测试）、`DelayedTaskLayer`（群聊自动互动）。本卡完成的「单一 allocator」与「诊断接口恢复」正是步骤 0/2 的前置。

---

## 4. Open questions（交 lead 裁决）

1. **B1 写域**：`shared/domain/chat` 是否开放给批 C 以补诊断事件/diagnostic port？若不开放，宿主只能通过 `EngineEvent` 重建诊断页，`finishDebugRound` 的 model 用量/协议统计/溯源字段无法等价恢复（属可接受的诊断降级还是行为差异，需裁定）。
2. **接线缝形态**：allocator 从 `MessageRepository` 暴露（本卡已把唯一实例放在 `MessageRepository.kt:59`）还是由 `ChatRepository` 提供 `messageIdAllocator` accessor？两者都触及 shared/data 公开 API，建议 batch C 第一步统一。
3. **`DatabaseDispatcher.execute` 任务标签**：`getNextMessageId` 改走 `withContext(DatabaseDispatcher.dispatcher)` 后，诊断页 `dbTask` 不再显示 `message_allocate_id`。是否值得为诊断在 `db` 包加 `executeSuspending`（本卡写域外），或接受该展示差异。
4. **`observeUsed` 提前持久化**（T17 已记录）：发送路径每次写 `max_used_message_id`，比 Android 多 settings 写；当前实现接受，若后续性能审计认为高频，可回退为「仅 restore 路径持久化」并调整 allocator 契约（需 T17 复核）。
5. **Step 2/3 排期**：若 lead 判定 Gate 1 需要「至少一个入口已委托」，建议授权批 C 的最小切片 = 步骤 0+1+2（适配层 + 事件映射）先合入，再由批 C 分入口替换；单独替换任一入口的收益不足以抵 B2 风险。

---

## 5. 变更文件一览（git 未提交）

```
D  app/src/main/java/com/rhodes/privatechat/data/repository/ChatRepository.kt                    (-260)
D  app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryPrivacyPolicy.kt             (-63)
D  app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryPromotionLocks.kt            (-4)
M  app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2Pipeline.kt                (+9)
M  shared/src/commonMain/.../shared/data/MessageRepository.kt                                    (+27/−24)
M  shared/src/commonMain/.../domain/memory/MemoryDiagnostics.kt                                  (+33/−3)
M  shared/src/commonTest/.../domain/data/MessageIdAllocatorTest.kt                               (+89)
A  shared/src/commonTest/.../domain/memory/MemoryDiagnosticsTest.kt                              (+72)
A  ios-port/handoffs/impl-wiring-b.md（本文件）
```

`git diff --stat`（不含新文件）：7 files changed, **+158 / −354**（净删 196 行；其中 327 行为死代码删除；新测试文件 +89/+72 未计入 stat）。

## 6. 构建纪律记录

- 全程 `source ../ios-port/toolchain/env.sh` + 每次 gradle 前 `pgrep -fl gradle` + `--no-daemon`；与并行代理无构建交错（执行期间未发现其他 gradle 进程）。
- 构建修复轮次：0（Step 1 全部改动一次编译通过；仅补 1 次 lazy 构造的启动路径修正，修正后 JVM/iOS/app 三路径复跑全绿）。
- iOS 路径：`xcrun simctl boot "iPhone 17"` + `bootstatus -b` 后运行 `:shared:iosSimulatorArm64Test`（1m48s → 48s，K/N 增量编译）。
- 未提交、未改 build.gradle.kts、未触碰 `shared/domain/chat*`。

---

## 7. lead 裁决记录（2026-11-23，编号同 §4/§3.3；批 C 任务引用本记录）

1. **开放 `shared/domain/chat` 写域给批 C**：允许补宿主诊断事件数据，允许给 `Settled`/`ReplyStored` 携带 `finishDebugRound` 所需字段（对应 §3.1 B1 / §4 open question 1）。
2. **批准接线缝形态**：暴露 `MessageRepository` 唯一 allocator 实例，**严禁第二 allocator**（对应 §3.1 B4 / §3.3 步骤 0 / §4 open question 2）。
3. **Gate 1 判定口径**：Gate 1 四条款（Android 不回归 / 核心提示词记忆测试全绿 / 共享层不依赖 Android / A7 决策完成）现已全部满足，**不要求编排层必须已委托**；Step 2/3 顺延为 Phase 2/4 批 C 工作，由后续任务承接（对应 §4 open question 5）。
