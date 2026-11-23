# T19 实现 handoff：私聊轮次引擎 ChatEngine（domain/chat）

> 代理：impl-chat-engine（T19 / task-19）。规格卡：`ios-port/specs/private-chat-engine-spec.md`（20 条行为契约，PASS-WITH-FIXES → lead 批准 2026-11-23）。
> 状态：**实现完成，双路径全绿（JVM 217/217、iOS 245/245）**。git 未提交（lead 统一提交）。
> 写域确认：仅新增 `shared/src/{commonMain,commonTest,androidUnitTest,iosTest}/.../shared/domain/chat/` 四目录，**零改动既有文件**（git status 无 M 条目）；未触碰 app/、backup、domain/{data,media}、prompt、build.gradle.kts。

## 1. 实现清单（file:line 指向 RhodesLink/）

### 1.1 commonMain 新包 `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/chat/`（6 文件，2010 行）

| 文件 | 内容 | oracle 对应 |
|---|---|---|
| `ChatEngineProtocols.kt` | 预算常数 `ChatStageBudget`（180s/240s/50s/120s/15s/12s/5s/3 次/减半 min5/250ms/10s）:14-60；`ChatStageTimeoutException`+`withChatStageTimeout`+`chatStageLabel`（22 项中文映射）:62-117；`InvalidModelResponseException`；`EngineEvent` 11 种状态事件（LoadingChanged/Toast/ClaimDenied/Undelivered/ReplyStored/Settled/TurnStateUpdated/MaintenanceRequested/UnhideSession/UnreadHint/FailureMessageStored）:122-185；`ChatEngineConfig`（全部可注入压缩，测试可加速）:188-210；`ChatClock`/`SystemChatClock`；`ChatStore` 持久化抽象（消息/会话/角色/租约/display events，27 方法）:230-260；`StreamingClient`（只依赖抽象，Ktor Darwin 取消语义留宿主接线）:262-273；`ModelOutputCodec`；`PromptAssembler`+`PromptBuildRequest`；`SettlementPolicy`（affectionMod `(2+mod.coerceIn(-2,2)).coerceIn(0,4)`、nextHistoryLimit、isContextLimitError）；`FailureMessages` | ChatViewModel.kt:102-140/:1281-1296/:1448-1453/:1649-1652/:1251-1256/:1239-1248、ChatStageTimeout.kt 全文 |
| `TurnStateMachine.kt` | 租约状态机：`claim`（SQL 条件逐条复刻 ReplyTurns.sq:40-46，Claimed/Denied 双结果）:37-45；`complete/release/fail`；`cancel/cancelBySource`（SQL 终止态 cancelled，无 Kotlin 常量，不可再 claim/reconcile/孤儿清理触及）；`retryableIds`（启动 reconcile 原语，ReplyTurns.sq:106-112）:60-62；`cleanupOrphanedSucceededTurns`（只删 succeeded+source 缺失，pending/running 永不动）:66-68；`turnId`/`sourceMessageId` 双向解析 | ReplyTurnRepository.kt:36-76/:111-114、ReplyTurns.sq:40-112 |
| `ReplyCodec.kt` | `VisibleReplyContract`：isCompletePrivateReply（唯一硬契约=至少一条非旁白非空台词）/ensureVisiblePrivateReply/stripLeakedSegmentLabel/containsFirstPersonNarration/sanitizePrivateResponse/sanitizeVisibleSegmentContent/visiblePrivateSegmentCount/privateReplyFailureReason/privateReplyStructureIssues/replyPreview；`ImageMessageContract`：占位→visionSummary(≤800)→status=failed 三态 JSON 往返+`isSendFailed`；`PrivateReplyFormat`：giftPromptText/formatImageMessageForPrompt/isPrivateSegmentRecalled/formatPrivateHistoryForPrompt/messagePreviewForSession；`ReplySegmentMutation`：markSegmentRecalled/markRecalled/isRecalled（非法→null 退化整条撤回）；`DefaultReplyCodec`（严格 JSON→标签脚本→纯文本兜底 + cleanJson 截断修复） | ChatViewModel.kt:1122-1237/:1063-1096/:1102-1119/:2814-2863/:2900-2923/:2269-2321、MessageParser.kt:137-157、AIService.kt:298-330 |
| `PrivatePromptAssembler.kt` | buildApiMessages 域实现（T15 自持）：四层系统拼接 **foundation(保护边界+behavior) → protocol(模板 system 层) → tagProtocol(默认标签协议/outputProtocol) → narrationProtocol**（契约 13/14，PromptAssemblyOrderTest 锁定顺序）:344-357；信任上下文 `【本轮参考资料】/【资料开始】/【资料结束】` 边界块:390-404；`【用户本轮消息】`+`【输出要求】` 恒压尾:406-414；必要读（operator 10s/short_term 10s/history 10s）+ 可选池（12s 共享，`optionalRead` 语义=超时/失败/预算不足一律降级 fallback，绝不阻断）:47-53/:172-200；`readPrivateHistoryWindow`/`recentPrivateRounds`/`trimPromptHistory`（自动上下文开关分流）；`buildDefaultTagProtocol`（online/offline 双形态，段数字数从 settings 渲染） | ChatViewModel.kt:3034-3501（逐段对应）、T18 交付原语（PromptTemplates/PromptModuleDefaults/PromptRender/PromptClock） |
| `FakeAiEngine.kt` | 确定性 fake AI（lead 裁决）：顺序脚本 + `byUserContent` 内容匹配两种模式；**先记录快照再模拟耗时**（在飞观察语义，测试靠它做取消/防抖断言）；`RecordedRequest(stage, messages, output)` 供金样回放断言输入序列 | 验收 #6 测试替身 |
| `ChatEngine.kt` | 核心状态机：内存层（loadingSessions StateFlow/sessionGenerations/pendingUserMessageIds/retryingMessageIds/recalledSourceMessageIds/chatAiJobs/promptBuildJobs/activeReplySourceIds/activeRequestBySession/per-session aiMutex，全部 stateMutex 守护）；API：`cancelSessionRequests`:112 / `invalidateSession`:136 / `sendMessage`:145 / `resumePersistedReply`:187 / `resumeImageReply`:217 / `onAppStartReconcile`:241 / `resumeSessionTurns`:248（前台补算）/ `runSendPipeline`:264（主流程）/ `generateCompleteReply`:532（内容重试 1 次+剩余<5s 跳过）/ `sendImageMessage`:621 / `retryFailedMessage`:805（suspend 防抖）/ `recallMessage`:844 / `recallMessageSegment`:871 / `regenerateAiMessage`:887 / `continueAiMessage`:944；`RepositoryChatStore`（ChatRepository 生产适配器）:996+ | ChatViewModel.kt:1298-1794/:1147-1203/:1819-2006/:1796-1817/:2742-2756/:2083-2190/:2363-2482、ManualReplyScheduler 语义 |

### 1.2 测试（commonTest 8 文件 61 例 × 双路径；androidUnitTest/iosTest 各 1 个平台子类）

| 文件 | 例数 | 覆盖（验收项） |
|---|---|---|
| `ReplyTurnStateMachineTest.kt`（抽象，`SqlChatStore` 直驱 RhodesDatabase 真实 SQL）+ `ReplyTurnStateMachineTestJvm/Ios` | 10×2 | 验收 #2：pending→running→succeeded / release(retryAt) / fail 后再 claim(attemptCount+1) / cancel 终止态不可 claim+不可 reconcile / claim 拒绝（外方未过期租约、nextAttemptAt）/ 租约过期可重试可重领 / retryable 过滤（succeeded/cancelled/image triggerKind/since）/ 孤儿清理（succeeded+source 缺失删、running 保留）/ cancelBySource 只动目标 turn |
| `ChatEngineTimeoutTest.kt` | 12 | 验收 #3：预算常数=规格卡值；prompt_build 100ms 超时→send_failed+租约释放；model 超时；ai_reply_write 超时；整轮超时文案（240s 口径）；AI 消息 ID 分配超时→已持久化静默未送达（不 Toast）；内容重试 1 次（narration-only→台词，system 改写断言）；剩余<5s 跳过重试（TestTimeSource 推进 236s，仅 1 次请求，不结算）；上下文降级 30→15→7（3 次上限）；降级地板 min5（6→5 后停止）；用户消息写超时→零持久化+Toast |
| `ChatEngineRecoveryTest.kt` | 10 | 验收 #4：发送中取消→send_failed+租约释放回 pending；撤回源消息→job 取消+turn cancelled（终止态不可 reconcile）；双发第二发（resume 补发）claim 拒绝→ClaimDenied 事件+静默让位（Toast 仅非重试路径，oracle :1452）；resumePersistedReply type 重置（send_failed→text / gift_reply_failed→gift_hidden+giftPromptText 还原）；租约令牌透传（跳过 claim 直接用 worker token 完成 turn）；缺失 session/消息/非 isMe 零副作用返回 true；onAppStartReconcile 精确列出可重试集；resumeSessionTurns 前台补算闭环；invalidateSession generation 复核拦截跨代写入（消息不误标失败、租约释放） |
| `ChatEngineMutationTest.kt` | 11 | 验收 #5：整条撤回（行删除+turn cancelled+会话预览更新）；分段撤回（JSON recalled 标记+display event 删除）；非法参数退化整条撤回；重新生成原地替换+清 display events；replaceReplyIfUnchanged 乐观锁（并发修改→保留原回复+系统失败消息）；无可见回复→保留原回复+失败消息；续写追加新 ai_json；重试 type guard+不存在拒绝+成功闭环；并发重试防抖（恰好一次成功）；空文本/配置错误 guard |
| `ImageMessageJsonContractTest.kt` | 10 | 验收 #7：占位/回填(≤800 截断)/failed 三态往返；金样图片 JSON 解析；垃圾输入 null；图片管线全流程（占位→识图回填→AI 回复→turn succeeded，图片路径不结算，oracle 行为）；识图失败→status=failed+Toast+租约释放可重试；未配识图模型/空 imageForModel guard；resumeImageReply 从持久化 JSON 重建 |
| `ChatEngineGoldenReplayTest.kt` | 3 | 验收 #6：金样 zip sha256 绑定→BackupFileReader 解析 7 条消息逐条比对；回放「输入序列→消息序列」（种子 7 条原样保留+第 8 条用户消息+第 9 条确定性 fake AI 回复，diff 白名单=id/timestamp/租约字段，turn succeeded 闭环）；输入快照断言（真实 PrivatePromptAssembler：四层顺序+金样历史三种形态格式化+【用户本轮消息】压尾+内嵌图段按 oracle 规则成【台词】[图片]） |
| `PromptAssemblyOrderTest.kt` | 8 | T18 建议随本卡落地：offline/online 四层顺序（offline 含【旁白硬规则】、online 无旁白协议）；信任上下文边界块包裹；历史三种形态格式化；用户消息+输出要求压尾顺序；USER_CONTENT 在 system 层恒空（T18 CachePromptLayering 契约）而内容押尾；历史末条与 USER_CONTENT 重复剔除；settings 段数渲染进标签协议 |
| `TestChatSupport.kt` | — | `InMemoryChatStore`（ReplyTurns.sq 语义逐条复刻的内存库）+ `ChatEngineTestHarness`（TestTimeSource/TestChatClock/事件收集/可替换 FakeAiEngine）+ `SqlChatStore`（状态机测试直驱真实 SQL） |

## 2. 状态机图（实现语义）

```
持久化租约层 reply_turns.status：
                 claim(token,leaseUntil)               complete(token)
  pending ──────────────────────────────▶ running ───────────────────▶ succeeded（终态·清租约）
    ▲                                        │  ├─ fail(token) ────────▶ failed（可再 claim，attemptCount+1）
    │                                        │  └─ release(token,retryAt)▶ pending（nextAttemptAt=retryAt）
    └────────────────────────────────────────┘
  pending/failed/running ── cancel / cancelBySource ──▶ cancelled（SQL 终止态，无 Kotlin 常量；
                                                        claim/reconcile/孤儿清理永不可触及）
  claim 条件（ReplyTurns.sq:40-46）：status∈{pending,failed,running} ∧ nextAttemptAt<=now
                                    ∧ (status≠running ∨ leaseUntil<=now)
  启动 reconcile：retryableTurnIds（triggerKind='manual' ∧ pending/failed/租约过期 running）
  孤儿清理：仅 succeeded ∧ source 行不存在 → 删；pending/running 永不动

内存层：
  Idle ──sendMessage──▶ Thinking(isLoading=true，per-session aiMutex 串行)
     ├─ generation 复核（:1470/:1592 等价）不匹配 → 静默让位（租约释放，恢复补答）
     ├─ pendingIds 防抖复核（250ms）失败 → supersede → 同上
     ├─ 可见回复 → ReplyStored + Settled + turn succeeded
     └─ 阶段超时/无可见回复/取消 → Undelivered(send_failed|gift_reply_failed) + turn release→pending
```

## 3. 双路径测试结果（实测）

| 路径 | 命令 | 结果 |
|---|---|---|
| JVM | `./gradlew --no-daemon :shared:testDebugUnitTest` | **217 tests, 0 failures**（本卡 61 例 + T17/T18/T9/T10 既有全绿） |
| iOS | `./gradlew --no-daemon :shared:iosSimulatorArm64Test` | **245 tests, 0 failures**（iPhone 17 模拟器；本卡 61 例全绿，含 NativeSqliteDriver 驱动的 ReplyTurnStateMachineTestIos；T9 KtorDarwinStreamingTest 8 例在启动 `ios-port/handoffs/mock-sse-server.py` 后全绿——**无 mock 服务器时这 8 例失败是环境前置，非本卡回归**，与 T18 handoff 记录一致） |

- 构建修复轮次：**JVM 3 轮内收口**（① common 语法/返回类型/匿名对象属性可见性+public 属性类型收窄 ② TCE 构造器 internal→借真实 withTimeout 取实例、InMemoryChatStore 历史页序改 newest-first（oracle 语义）③ 断言口径修正：Toast 仅非重试路径 :1452、USER_CONTENT 恒空、终止态需经真实转移达成）；**iOS 1 轮**（MutableMap.merge JVM-only→手写 get/put；@Volatile common 不可用→移除）。合计在 3 轮上限内。
- 关键测试基建决策（需 reviewer 知悉）：`withTimeout` 无法抢占不挂起的阻塞调用（oracle 同款局限，ChatViewModel.kt:3079-3081 注释），故「AI 消息 ID 分配超时」用例以同步抛真实 TCE 模拟超时裁决；其余超时用例均用挂起式延迟驱动。

## 4. Android 调用点改造清单（Phase 2，lead 统一裁决执行，本波未动 app/）

| ChatViewModel.kt 段 | 动作 | 域层等价入口 |
|---|---|---|
| :102-140 预算常数/深度思考加成 | 删，改用 `ChatEngineConfig` | config.effectiveTurnBudgetMs / effectiveModelBudgetMs |
| :1122-1237（isCompletePrivateReply/ensureVisiblePrivateReply/stripLeakedSegmentLabel/containsFirstPersonNarration/logPrivateReplyStructure/sanitize*） | 删，`VisibleReplyContract` | 同名方法 |
| :1258-1266 cancelSessionRequests | 委托 `engine.cancelSessionRequests`（内存层清理下沉） | ChatEngine.kt:112 |
| :1268-1296 savePrivateFailure/markPrivateMessagesUndelivered | 删（域层内建；宿主消费 Undelivered/FailureMessageStored 事件） | ChatEngine.kt:593-608 |
| :1298-1794 sendMessage 主体 | 删，委托 `engine.sendMessage(...)`；保留 UI 状态（_messages 乐观行由 flow 驱动、_inputText 草稿恢复）+ Toast/通知回调 | ChatEngine.kt:145/:264 |
| :1796-1817 resumePersistedReply | 委托 `engine.resumePersistedReply(sessionId, messageId, turnId, token)`（suspend，await 结果） | ChatEngine.kt:187 |
| :1819-2006 sendImageMessage | 委托 `engine.sendImageMessage(...)`；VisionGateway 经 `visionAnalyze` 注入；MainActivity.imageForModel 经 `imageForModelResolver` 注入 | ChatEngine.kt:621 |
| :2083-2114 / :2173-2190 recallMessage / recallMessageSegment | 委托引擎同名方法（workflow 的 ManualReplyScheduler.completeTurn 调用由宿主在 RecallStored 事件后执行或保留在 VM 壳） | ChatEngine.kt:844/:871 |
| :2150-2170 retryFailedMessage | 委托 `engine.retryFailedMessage(sessionId, msgId)`（suspend，防抖在域层） | ChatEngine.kt:805 |
| :2269-2321 markSegmentRecalled/markRecalled/isRecalled | 删，`ReplySegmentMutation` | ReplyCodec.kt |
| :2363-2421 / :2443-2482 regenerateAiMessage / continueAiMessage | 委托引擎同名方法（模式过渡提示经 `settings.putPendingPrivateModeTransition` 注入） | ChatEngine.kt:887/:944 |
| :2742-2756 resumeImageReply | 委托 `engine.resumeImageReply` | ChatEngine.kt:217 |
| :2822-2923 历史/礼物/图片格式化 | 删，`PrivateReplyFormat` | ReplyCodec.kt |
| :3034-3501 buildApiMessages | 删，`PrivatePromptAssembler`（memoryV2Pipeline/知识库经 `PrivateContextProviders` 注入；DebugLogger/溯源/缓存统计留 VM 层） | PrivatePromptAssembler.kt |
| ChatStageTimeout.kt | 删文件（域层自带 `withChatStageTimeout`+中文标签） | ChatEngineProtocols.kt |
| ManualReplyWorker/ManualReplyScheduler | iOS 无 WorkManager（R1 裁决）：启动调 `engine.onAppStartReconcile()`，进入会话调 `engine.resumeSessionTurns(sessionId)`；Android 侧 worker 改为「claim 新租约后调 resumePersistedReply(token 透传)」 | ChatEngine.kt:241/:248 |

## 5. iOS IosAppGraph 门面接口草案

```swift
// shared framework 导出（Kotlin）→ Swift 桥接层封装。仅示意，Phase 2 按实际导出成型。
protocol ChatEngineFacade {
    // 落库立即返回；pipeline 异步，结果经 observe 事件流
    func send(text: String, sessionId: String, mode: String) async -> Bool
    func sendImage(imageUri: String, imageForModel: String?, caption: String, sessionId: String, mode: String) async -> Bool
    func cancel(sessionId: String)
    func retry(sessionId: String, messageId: Int64) async -> Bool
    func recall(sessionId: String, messageId: Int64, segmentIndex: Int32?)
    func regenerate(sessionId: String, messageId: Int64)
    func continueReply(sessionId: String, messageId: Int64)
    func resumeInterrupted(sessionId: String, messageId: Int64, turnId: String?, leaseToken: String?) async -> Bool
    func reconcileOnAppStart() async -> [String]          // R1 裁决：启动 reconcile
    func resumeSessionTurns(sessionId: String) async -> Int  // R1 裁决：前台补算
    func observe(sessionId: String) -> AsyncStream<EngineEvent> // loading/undelivered/replyStored/timeoutToast/settled...
}
// 接线要点：Ktor Darwin StreamingClient 包装 AIService.chat（整包，R2 裁决）；
// SettingsRepository 直读预算/阈值；MessageIdAllocator + RepositoryChatStore(ChatRepository)；
// visionAnalyze = VisionGateway 包装；BGTaskScheduler 不参与（R1）。
```

## 6. open questions / 遗留

1. **结算写入宿主化**：`EngineEvent.Settled`（affectionMod/dailyLmb/visibleCount/preview）由宿主执行 `updateOperatorIntimacy/grantDailyLmb/decrementHypnosis/通知/markUnreadIfNotCurrent/schedulePrivatePostReplyMaintenance`——域层只算不写（oracle :1649-1655 的写入点归属 Android 专属）。Phase 2 需要一张「宿主结算回调清单」交叉核对。
2. **图片路径不结算**：oracle sendImageMessage（:1968-1977）不涨亲密/龙门币/不调度维护，引擎逐条复刻（有测试锁定）。若产品口径要求图片回复也结算，需改规格卡（差异白名单候选）。
3. **`getDisplayEvents` 读取在引擎金样回放中为空**：display events 是 UI 层 reveal 产物（MessageList.kt:140），管线不写——金样 zip 的 displayEvents 也为空。分段撤回/整条撤回的**删除**语义已实现并测试。
4. **超时测试口径**：`withTimeout` 对不挂起阻塞不可抢占（oracle 同局限），ai_reply_id 超时用例以同步抛 TCE 模拟；如需真机级「慢 SQL 可抢占」验证，Phase 2 用真 DB + 慢查询注入。
5. **`retryFailedMessage` 为 suspend**（防抖需 stateMutex）：Android 迁移时 VM 壳需 `viewModelScope.launch { engine.retryFailedMessage(...) }` 包装，或 lead 决定改为 fire-and-forget + 域内同步去重结构。
6. **模式过渡提示**：引擎经 `settings.putPendingPrivateModeTransition` 注入（assemble 读同一键）；oracle 另有内存态 `modeTransitionNotices`（setMode 立即生效不落盘路径），Phase 2 setMode 迁移时由 VM 壳决定是否补内存覆盖。
7. **reply_turns 不进备份**（规格卡开放项 ④）未在本卡变更；恢复/读档路径必须保留 `cleanupOrphanedSucceededTurns` 调用点（已在 onAppStartReconcile 内）。

## 7. 构建纪律记录

- 全程 `source ../ios-port/toolchain/env.sh` + `pgrep -fl gradle` 检查 + `--no-daemon`；与并行代理零交叉冲突（本卡只新增文件）。
- 金样回放资产复用 `GoldenBackupFixture.zipBytes`（commonTest，T10）+ `BackupFileReader`（commonMain，T10），未重复内嵌。
