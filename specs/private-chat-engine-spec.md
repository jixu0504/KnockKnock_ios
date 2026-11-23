# 规格卡：private-chat-engine（私聊轮次引擎 ChatEngine）

> 模板：ios-port/specs/规格卡模板-v1.md ｜ 任务：task-15（T15）｜ 状态：**PASS-WITH-FIXES（reviewer 2026-11-23，修正已落实）→ lead 已批准（2026-11-23）**
> 范围：Phase 1 P0-2 —— 私聊轮次状态机与域逻辑抽取（Android 1.14.27 @ ios-port 分支为行为 oracle）。
> 黄金法则：每条行为契约必须带 Android file:line。路径缩写：`app/` = `RhodesLink/app/src/main/java/com/rhodes/privatechat/`，`shared/` = `RhodesLink/shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/`，`sq/` = `RhodesLink/shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/`。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 私聊轮次引擎（ChatEngine）——单会话「用户消息 → AI 回复」轮次的状态机、持久化协议与恢复语义 |
| 所属阶段 | Phase 1（P0-2）；正式实现在 Phase 2 |
| 范围映射 | A1 核心聊天（ChatOperator 路由）：发消息/图片、三模式、撤回、重试、重新生成、续写、存档/重启/清除、断线恢复（对照 `ios-port/checklist/功能对照清单-v1.md` §1.1、§2.1、§2.2）。**不在本卡**：群聊轮次（lead 裁决：单独立卡，下一批）、UI 渲染（MessageList/ChatScreen）、提示词**模板**细节（T14 卡）、记忆/滚动摘要管线（后续卡）、消息 ID 分配器实现（T16 卡，本卡只定接口语义）。**上下文装配 buildApiMessages（ChatViewModel.kt:3034）属本卡自持** |
| 对应 Android 源码 | `app/viewmodel/ChatViewModel.kt`（3829 行，sendMessage:1298 / cancelSessionRequests:1258 / resumePersistedReply:1796 / sendImageMessage:1819 / recallMessage:2083 / retryFailedMessage:2150 / recallMessageSegment:2173 / regenerateAiMessage:2363 / continueAiMessage:2443 / resumeImageReply:2742、buildApiMessages:3034（上下文装配，T15 自持））；`app/viewmodel/ChatStageTimeout.kt`（62 行）；`shared/network/AIService.kt`（chat:424，streamChat:668）；`shared/data/ChatRepository.kt`（replyTurns:52，协议转发:327-346）；`shared/data/ReplyTurnRepository.kt`（115 行）；`shared/data/MessageRepository.kt`（getNextMessageId:296-305）；`app/ui/chat/util/MessageParser.kt`（图片消息 JSON:137-157）；`sq/ReplyTurns.sq`、`sq/ChatMessages.sq`、`sq/ChatDisplayEvents.sq`；`app/automation/ManualReplyScheduler.kt` + `ManualReplyWorker.kt`（杀进程恢复） |
| 依赖模块 | 提示词管线（待 T14 卡：**模板与提示文本内容**（PromptTemplates）、模式过渡提示文本 :1035-1043、导演模式块 :3662）→ 仅经 shared/domain 接口；**上下文装配 buildApiMessages（:3034）本卡自持**；记忆管线（待后续卡：schedulePrivatePostReplyMaintenance :2759、generateShortTermSummary :501）→ 回复完成后回调，不阻塞本轮；消息 ID 分配器（待 T16 卡，语义 oracle 见 MessageRepository.kt:296-305）；流式传输（T9 已验证机制：ios-port/handoffs/ios-data-base.md §3.7 Ktor Darwin 取消语义）；设置仓库（SettingsRepository 读取全部预算/阈值参数） |

## 输入 / 输出 / 边界

### 输入
| 输入 | 类型/来源 | 空值与非法值行为（oracle） |
|---|---|---|
| 用户文本 | UI 输入框 / `sendMessage(textOverride)`；图片消息 JSON `{imageUri, caption, visionSummary, status?}` | 无 session → Toast「聊天正在恢复，请稍后再试」并 return（:1311-1314）；trim 后空文本 → 静默 return（:1316）；聊天配置无效（缺 API key/model/URL）→ Toast 配置错误并清重试标记（:1317-1322） |
| 引用/重试目标 | `retryMessageId` + `messageTypeOverride`（gift_hidden） | 重试仅接受 `isMe && type ∈ {send_failed, gift_reply_failed}` 的消息（:2152-2154）；`retryingMessageIds` 防抖去重（:2151） |
| 图片消息 | `sendImageMessage(imageUri, imageForModel, caption)`；`existingMessageId`（恢复用） | 未配识图模型 → Toast「图片聊天需要先设置识图模型…」（:1832-1836）；`imageForModel` 空 → Toast「无法读取这张图片…」（:1837-1842） |
| 中断恢复输入 | `resumePersistedReply(sessionId, messageId, onComplete, replyTurnId?, replyLeaseToken?)` / `resumeImageReply(...)` | session 或消息缺失/非 isMe → `onComplete(true/false)` 直接返回（:1798-1803, :2744-2747）；租约令牌透传（:1812-1813） |

### 输出
| 输出 | 去向 | 副作用 |
|---|---|---|
| 用户消息落库（`chat_messages`） | 先乐观进内存列表（:1401-1405），再 `sendMessageAndCreateReplyTurn` 持久化（:1408-1416），**读回校验**（:1418-1422） | `reply_turns` 同事务创建（triggerKind=manual/gift/image）；失败回滚内存列表并恢复输入框草稿（:1735-1739） |
| AI 回复落库 | `sendReplyAndCompleteTurn`（type=`ai_json`，content=清洗后 JSON）（:1616-1624） | turn 置 succeeded + 清 lease；`chat_display_events` 分段 reveal 顺序；会话 lastMessage/unread（:1655, :1642） |
| 流式/状态事件 | 非流式 `AIService.chat`（:424）整包返回后本地解析；UI 状态经 `isLoading` StateFlow（:171-174）+ `_loadingSessions` | 首 token 语义由**阶段超时**表达（见边界）；T9 已验证 Ktor Darwin 取消传播（114ms 首 token / 1ms 取消，实测值） |
| 状态事件流 | `PrivateTurnState`（emotion/location/activity/turnStatus…，:1593-1613）写入 settings 并 `privateTurnStateUpdates++`（:1625-1626） | 回复后结算：亲密度 +2..+4（:1649-1651）、每日龙门币 50（:1652）、催眠轮次递减（:1653）、后台通知（:1643） |
| 错误输出 | 失败系统消息 `savePrivateFailure`（:1268-1279）；失败消息置 `send_failed`/`gift_reply_failed`（:1281-1296） | 超时 Toast：`ChatStageTimeoutException.userMessage`（ChatStageTimeout.kt:35-36）；整轮超时 Toast（:1695） |

### 边界（参数全集，逐项 oracle）
| 参数 | 值 | oracle | 备注 |
|---|---|---|---|
| 整轮总预算 | **180_000ms**，DeepSeek 深度思考开启时 +60_000 → **240_000ms** | :107, :124, getter :139-140 | 租约 `leaseUntil = now + turnBudgetMs` 用同一值（:1344, :1447） |
| 提示词构建预算 | 50_000ms（实际 = min(50s, 剩余整轮预算)） | :108, :1546 | 专用单线程 lane `promptBuildDispatcher`（:218）防池饥饿 |
| 可选增强上下文池 | 12_000ms | :115 | 超出即跳过注入，不影响回复 |
| 模型请求预算 | 120_000ms（深度思考 +60s）；每次调用 `withChatStageTimeout` 取 min(模型预算, 剩余整轮) | :116, :132-133, :1160-1164 | 阶段名 `model_primary_request` / `model_content_retry` |
| 内容重试 | 首次解析无可见台词 → 重试**一次**（改写 system 提示 :1190-1193）；剩余预算 <5s 跳过（:1186-1189） | :1184-1202 | 仍失败抛 `InvalidModelResponseException` |
| 消息写超时 | 15_000ms：msgId 分配（:1391, :1465）、用户消息写（:1410）、读回校验（:1418）、AI 回复写（min(15s, 剩余) :1617） | :117 | 超时→外层 catch 回滚（:1733-1742） |
| 重启清理等待 | ≤10_000ms join，超时取消继续发送（:1369-1373） | :1369 | 防新轮次与旧会话清理竞争 |
| 会话选择超时 | 8_000ms（getOrCreateSession / 初始消息 / count） | :665, :689, :694 | 超时抛错，UI 走错误态 |
| 上下文超限降级 | 400 + context_length/token 类错误 → 历史轮数减半（min 5），最多 3 次重试（:1708-1716）；历史阶梯 `nextHistoryLimit`（:1251-1256） | :1506, :1703-1716 | 仅当 `effectiveHistoryMessages > 5` |
| 用户消息合并 | 一批 ≤2 条、合计 ≤600 字符（:105-106, :1493-1503） | :105-106 | 防合并超过 600 字 |
| 并发/重入 | 每会话 `aiMutex`（:185, :1467-1469）；`activeRequestBySession`+`requestSequence` 载荷守卫（:186-187, :195-203）；发送前 `delay(250)` 后校验 pendingIds（:1482-1492） | :1482-1492 | 双发第二发被 supersede → 租约释放，由恢复 worker 补答 |
| 取消语义 | `cancelSessionRequests`（:1258-1266）：generation+1、取消 cleanup/chatAiJobs、清 pending、清 loading；CancellationException 在管线中透传（:1697-1699, :1743-1748） | :1258 | Ktor Darwin 取消经协程传播，T9 实测取消 1ms |
| 会话切换/杀进程 | `sessionGenerations` 失效检查（:1470-1476, :1592, :1681）；杀进程 → WorkManager `ManualReplyWorker`（5 分钟延迟，claim 新租约）驱动 `resumePrivateReply`/`resumePrivateImageReply`（ManualReplyWorker.kt:21-46） | :1470-1476; automation | iOS 无 WorkManager：需**后台重入点**（见风险） |

## 行为契约（逐条，每条带 Android oracle 位置）

> 状态机图（源码实际语义）：
> 内存视图：`Idle → Thinking（isLoading=true）→ ReplyStored / Undelivered(send_failed) / TimeoutToast / Cancelled / Failed(系统消息)`；
> 持久化租约视图（`reply_turns.status`）：`pending → running(claim: token+leaseUntil) → succeeded(complete)`｜`pending(release: retryAt+error)`｜`failed`｜`cancelled`（SQL 终止态，无 Kotlin 常量）；`retryableTurnIds` 捞出 pending/failed/租约过期 running（ReplyTurnRepository.kt:111-114，对应 SQL selectRetryableReplyTurns :106-112，即**启动 reconcile 原语**）。状态常量 `ReplyTurnStatus`（shared/model/ReplyTurn.kt:23-28：仅 PENDING/RUNNING/SUCCEEDED/FAILED 四项）；`cancelled` 由 `sq/ReplyTurns.sq:94`（cancelReplyTurn）与 `:99`（cancelReplyTurnBySource）落成，不可再被 claim/恢复/孤儿清理触及；SQL 转移 `sq/ReplyTurns.sq:40-62`。

1. **发送主流程（sendMessage）**：guard（session/空文本/配置）→ msgId 分配 → 乐观 UI 插入 → `sendMessageAndCreateReplyTurn`（同轮次创建 `ReplyTurn`，id=`private:{sessionId}:{msgId}`，triggerKind=manual/gift）→ 读回校验 `check(persisted)` → 调度恢复 worker → claim 租约 → 250ms 防抖+generation 复核 → 合并批次 → 提示词构建（buildApiMessages :3034，本卡自持）→ 模型请求（内容重试 1 次）→ 解析清洗 → `sendReplyAndCompleteTurn`（lease 校验）→ 结算。oracle：`app/viewmodel/ChatViewModel.kt:1298-1794`。
2. **消息写入时机**：用户消息在 AI 请求前**同步持久化**（:1408-1422），AI 消息在整包解析成功后**一条事务落库**（:1616-1624）；两者 id 均经 `getNextMessageId`（:1391, :1465）。重试路径不重复写用户消息，只重置 type（:1429-1435）。差异：iOS 无「半包落库」——Android 1.14.27 私聊本身也是整包后落库（非逐 delta），流式仅存在于 `streamChat` 兼容接口（AIService.kt:668-729，标注「保留用于兼容」），**平台差异白名单：iOS 沿用整包落库，delta 流仅用于 UI 展示层（若 T9 流式用于打字机效果）**。
3. **重复发送防抖**：`retryingMessageIds` 防重试重入（:2151）；`aiMutexFor` 每会话串行（:185）；`activeRequestBySession` 载荷守卫使 `finishLoading` 只清自己的 requestId（:195-203）；发送后 250ms 检查 pendingIds，被 supersede 的轮次静默让位给恢复 worker（:1482-1492）；`sessionGenerations` 复核防跨代写入（:1470-1476）。
4. **租约与孤儿防护**：claim 被拒 → Toast「消息已保存，回复稍后自动补上」并 return（:1448-1453）；恢复 worker 在 claim **之前**调度（:1442-1445），杜绝「消息已存但无人补答」；孤儿 turn 清理 `cleanupOrphanedSucceededTurns` 只删「source 已不存在且 succeeded」的行（ReplyTurnRepository.kt:67-76）。
5. **取消（cancelSessionRequests）**：generation 递增使一切在飞轮次失效；取消重启清理 job 与 chatAiJobs；清 pendingUserMessageIds 与 loading（:1258-1266）。管线内 CancellationException 透传并在外层把已持久化用户消息置 `send_failed`（:1743-1748）。
6. **分阶段超时**：`withChatStageTimeout(surface, stage, budget)` 包装（ChatStageTimeout.kt:15-33），超时抛 `ChatStageTimeoutException`（含 stage/budget/elapsed，:7-13）；用户文案 `"{label}{阶段中文名}超时（预算X秒），本轮未生成或保存AI回复，请重试"`（:35-36）；阶段中文名映射 22 项（:38-62）。管线捕获：ChatStageTimeoutException → 标记未送达+Toast（:1670-1684）；裸 TimeoutCancellationException → 整轮超时 Toast（:1685-1696）。
7. **整轮预算与深度思考**：`turnBudgetMs` 进轮时固化（:1344, :1156-1157），租约与超时同源；深度思考只对 DeepSeek 且开关开启时 +60s（:128-140）。
8. **无可见回复判定**：唯一硬契约 = 至少一条非旁白且非空台词（`isCompletePrivateReply` :1122-1127）；不满足 → 用户消息批量置 `send_failed`，**不结算**亲密/龙门币（:1656-1667）；协议漂移只记日志不拦截（`logPrivateReplyStructure` :1129-1144）。
9. **重试（retryFailedMessage）**：仅 `send_failed`/`gift_reply_failed`；重置 type 后走 sendMessage 的重试分支（复用 msgId、不新建用户消息、turn 缺失时补建，:1423-1435）；礼物消息用 `giftPromptText` 还原载荷（:2160-2168）。UI 态「未送达，点击重试」（MessageList.kt:520，checklist §2.1）。
10. **撤回整条（recallMessage）**：若为在飞源消息 → 先取消其 job 与 promptBuildJob（:2090-2098）；`cancelReplyTurnForSource`（turn 终态 cancelled）+ `deleteMessage`（display events+turn+行，MessageRepository.kt:318-324）+ 会话预览更新 + 上下文重建（:2101-2113）。
11. **撤回分段（recallMessageSegment）**：仅 ai_json；`markSegmentRecalled` 改写 JSON 段标记（:2179, :2269+）；`updateMessageContentAndPreview` + `deleteDisplayEvent(msgId, segmentIndex)` + 上下文重建（:2185-2189）；非法参数退化整条撤回（:2175-2183）。段恢复语义由 `isRecalled`/`isPrivateSegmentRecalled` 判定（:2319, :2900）。
12. **重新生成（regenerateAiMessage）**：先取消在飞 job；`replaceReplyIfUnchanged(originalReply, newRaw)` 乐观锁——原回复被改/被撤回则不覆盖（:2395）；成功 → 同 id 原地替换 + 清 display events + turn state 更新 + 上下文重建（:2393-2403）；失败 → 保留原回复 + 系统失败消息（:2404-2417）。
13. **续写（continueAiMessage）**：取消在飞 job；追加新 ai_json 消息（新 msgId）；经 `modeTransitionNotices` 注入「继续指令」（:2457, :2460）；失败 → 系统消息（:2470-2477）。
14. **中断恢复（resumePersistedReply）**：进程重建后由 `ManualReplyWorker`（5 分钟延迟、网络约束、claim 新租约）调用；消息必须是 isMe 且属该会话；type 重置（gift_reply_failed→gift_hidden，其余→text）后走重试分支（:1796-1817）；`ManualReplyScheduler.completeTurn` 在成功后取消 WorkManager 任务（:1632-1633）。图片恢复 `resumeImageReply` 从图片 JSON 还原 uri/caption 后重走 `sendImageMessage(existingMessageId=…)`（:2742-2755）。**平台差异（lead 裁决 2026-11-23）**：iOS 无 WorkManager——恢复 = 启动 reconcile（selectRetryableReplyTurns）+ 前台补算（进入会话/发送前扫可重试 turn），领域层不依赖 BGTaskScheduler。
15. **图片消息管线**：占位 JSON（imageUri/caption/visionSummary=""）先落库（:1859-1871）→ 识图（VisionGateway，提示契约固定 5 字段 JSON，:1902-1913）→ 失败 → JSON 加 `status:"failed"` + Toast「图片已保留，但识别失败…」（:1922-1932）→ 成功 → 回填 visionSummary（≤800 字，:1937-1942）→ 构建图片上下文 → 正常回复管线（:1952-1977）。UI 解析：`status=="failed"` → `isSendFailed`（MessageParser.kt:137-157）；「图片识别失败，点击重试」（MessageList.kt:501）。
16. **存档（创建/读取/删除）**：容量按亲密度 3/5/8/12/16/20（:302-309）；创建需非 loading 且模型可用，互斥 `archiveCreationMutex`（:322-327）；`loadArchive`：先 `cancelSessionRequests` + timeline epoch 推进，旧进度落 `chat_history_segments`，`restoreChatArchive` 恢复消息+短期记忆，恢复 turn state/mode，重启边界清零（:448-490）；启动时恢复 pending 存档摘要（init :241-257）。
17. **重启与擦除（restartSession / erasePrivateSessionAndRestart）**：重启先设新边界（restartAt=now）再异步清理——旧章节落 history_segments、删 memoryV2/短期记忆、清预览、插系统消息「新的篇章从这里开始…」（:800-855）；擦除 = 关系+会话消息全清、restartAt 归零（:857-881）。两者均先 `cancelSessionRequests`。
18. **会话切换（selectOperator / selectOperatorSync）**：selectionSequence 防旧异步回调污染新会话（:652, :667）；同会话 → 保消息、复活死 flow；异会话 → 清空状态、DB 初始页 ≤8s、SQLDelight flow 为权威（:668-708, :731-768）；hypnosis 每角色持久化（:654-662）。
19. **三模式分支（online/offline/director）**：模式在发送开始固化并随消息落库（:1330, :1398）；`setMode` 生成 6 向过渡提示并落 pending（:1035-1047，提示文本内容归 T14）；引擎侧硬分支：online 仅保留 dialogue 段（`ensureVisiblePrivateReply` :1216-1220）、offline/director 要求首段旁白+末段台词协议检查（:1135-1143）；turnStatus 已收束/已转题 → 清 unresolved/pending（:1600-1607）；导演模式系统块（:3662，模板内容归 T14）。每消息 mode 独立，重试沿用原消息 mode（:2166）。
20. **结算与后处理**：仅可见回复结算：亲密度 +2+affection_mod(-2..2 截断 → 0..4)、每日龙门币 50、催眠递减、滚动摘要/记忆维护调度、未读计数、后台通知（:1649-1655, :1643）；`schedulePrivatePostReplyMaintenance` 绝不占用本轮锁（:2759-2796）。

## 数据与迁移

- **涉及表**（对照 `ios-port/golden/db/schema/`）：`chat_sessions`（mode/lastMessage/lastTime/unreadCount）、`chat_messages`（type ∈ text/ai_json/image/system/gift_hidden/send_failed/gift_reply_failed；emotion/activity/location）、`chat_display_events`（messageId+segmentIndex+revealOrder 分段展示顺序）、`reply_turns`（19 字段，见 sq/ReplyTurns.sq:1-19；唯一索引 per-source :21-22）、`chat_archives` + `chat_history_segments`（存档与重启归档）、`memories`（滚动摘要短期记忆）。
- **设置键（不进 SQL 表）**：`max_used_message_id`（ID 单调自愈，MessageRepository.kt:285-294）、`session_restart_at`、`summary_cursor`、`memory_extraction_cursor`、`pending_private_mode_transition`、`last_mode_{operatorId}`、`private_turn_state_{sessionId}`、滚动摘要状态与重试时间。
- **迁移**：本卡不新增 iOS 专属迁移（沿用 1..23.sqm 全集，T9 已验证 fresh=28 表/user_version=24）；Android 侧 reply_turns 已由既有迁移建立，无需新版本。
- **备份/恢复表现**：`chat_messages` 全量进备份（getAllMessagesForBackup，ChatMessages.sq）、`chat_display_events`/`chat_archives`/`chat_history_segments` 在 BackupPayload 内（audit/fields.md a2）；**`reply_turns` 不进备份**——恢复/读档后由 `cleanupOrphanedSucceededTurns` 清理孤儿 turn（ReplyTurnRepository.kt:67-76），恢复路径必须保留该调用点；设置键随 settings 全量导出（PortableSettings 白名单，fields.md a3 待补核对）。

## 验收（必须可自动判定）

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | 现有单测迁移：聊天域相关 8 套（下表）在 commonTest 全绿 | commonTest（JVM+Darwin 双跑） | `./gradlew --no-daemon :shared:testDebugUnitTest` 与 `./gradlew --no-daemon :shared:iosSimulatorArm64Test` |
| 2 | 状态机转移矩阵（pending→running→succeeded / release / fail / cancel + claim 拒绝 + 租约过期） | 新 commonTest `ReplyTurnStateMachineTest`（SQLDelight in-memory 驱动） | 同上，`--tests "*.shared.domain.chat.ReplyTurnStateMachineTest"` |
| 3 | 超时矩阵：prompt_build 50s / model 120s / 整轮 180s(240s) / ai_reply_write 15s / 内容重试跳过（剩余<5s） | 新 commonTest `ChatEngineTimeoutTest`（fake clock + fake AI） | 同上 |
| 4 | 取消/中断恢复矩阵：发送中取消→send_failed、双发防抖、claim 拒绝、resumePersistedReply 语义（type 重置、租约透传） | 新 commonTest `ChatEngineRecoveryTest` | 同上 |
| 5 | 撤回/重试/重新生成：整条撤回（turn cancelled+行删除）、分段撤回（display event 删除+JSON 标记）、replaceReplyIfUnchanged 乐观锁、重试防抖 | 新 commonTest `ChatEngineMutationTest` | 同上 |
| 6 | 金样会话回放：输入序列 → 消息序列 diff（含类型/顺序/display events） | 种子 = `ios-port/golden/backup/minimal-1.14.27.zip` 的 **7 条消息**（id=1..7：text/ai_json/image 混合，含图片 JSON 与内嵌图引用，经 Android 1.14.27 备份金样恢复验证）；引擎跑**确定性 fake AI**（lead 裁决）回放，与预期消息序列 diff（白名单：id/timestamp/租约字段） | 同上；金样目录 `ios-port/golden/backup/`（`make_minimal_backup.py --verify` 可复现） |
| 7 | 图片消息 JSON 契约：占位→visionSummary 回填→status=failed 三态解析 | commonTest `ImageMessageJsonContractTest`（对照 MessageParser.kt:137-157 与 :1859-1942） | 同上 |
| 8 | 性能（对齐 timeline-2000 结论）：2,000 条消息下引擎状态更新不阻塞 UI | Phase 2 与 UI 卡合并测量；本卡仅保证引擎侧 O(最新页) 读取（CHAT_PAGE_SIZE=50 分页 :104） | 性能数据（真机） |

- **迁移清单（Android app/src/test 现有 8 套，实测共 40 个 @Test，随域逻辑一并下沉 commonTest）**：
  `ChatInteractionPolicyTest`（8：orderChatBubbles 排序/MessagePersistenceReceipt/preserveEditedDraft）、`CancelledScopeStatusWriteTest`（2：取消后状态写入）、`PromptHistorySafetyTest`（7：历史裁剪，T14 交界）、`DeepSeekThinkingScopeTest`（5：预算加成口径）、`TurnStateParsingTest`（1）、`MaintenanceReliabilityTest`（6：后处理）、`IncrementalMessageCacheTest`（2）、`ContextProvenanceTest`（9：溯源记录）。口径说明：**lead 裁决（2026-11-23）以实测 376 为准**（app/src/test+shared/src @Test 计数；androidTest 3 个不计）。
- **金样**：`ios-port/golden/backup/minimal-1.14.27.zip`（**7 条消息**：id=1..7，text/ai_json/image 混合；确定性输出，`python3 ios-port/golden/backup/make_minimal_backup.py --verify` 复现）为回放输入种子；「输入序列 + 期望消息序列」对由实现代理在实现期以**确定性 fake AI**（lead 裁决）产出，reviewer 复核；diff 白名单 = 消息 id/timestamp/租约字段。
- **边界用例清单**：空文本/空 session、配置缺失、双发、发送中切会话、发送中重启会话、发送中撤回源消息、claim 被拒、写库超时（用户消息未持久化→回滚+草稿恢复）、写库超时（已持久化→send_failed）、模型空回复、模型 400 上下文超限降级、深度思考开关中途切换、杀进程后 worker 恢复、图片识图失败、存档加载中发送。

## 抽取落点

- **shared 域层**：`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/chat/`
  - `ChatEngine.kt`：sendMessage/cancel/resume/retry/recall/regenerate/continue 的域编排（依赖注入：ChatRepository + SettingsRepository + 提示词管线接口 + AIService 接口 + ID 分配器接口 + 时钟）；
  - `TurnStateMachine.kt` + `ReplyTurnLease.kt`：pending/running/succeeded/failed/cancelled 转移与租约（直接复用 ReplyTurnRepository）；
  - `ChatEngineProtocols.kt`：`ChatStageBudget`（180s/240s/50s/120s/15s/12s/5s 常数集中）、`VisibleReplyContract`（isCompletePrivateReply 语义）、`EngineEvents`（状态事件流：loading/replyStored/undelivered/timeout/cancelled/failed/结算事件）。
- **Android 侧改动面**：ChatViewModel 保留 UI 状态（_messages/_inputText/isLoading/滚动目标）与 Android 专属（通知、WorkManager 调度、催眠持久化）；域逻辑全部下沉 ChatEngine；`ManualReplyWorker` 改为调用域层 resume 入口。
- **iOS SwiftUI 接口草案**（经 IosAppGraph 门面）：
  ```swift
  protocol ChatEngineFacade {  // 仅示意，实现期以 shared framework 导出为准
      func send(text: String, mode: Mode) async -> SendOutcome      // 落库立即返回 userMessageId
      func cancel(sessionId: String)
      func retry(messageId: Int64)
      func recall(messageId: Int64, segmentIndex: Int32?)
      func regenerate(messageId: Int64)
      func resumeInterrupted(sessionId: String, messageId: Int64)   // 前台恢复入口
      func observe(sessionId: String) -> AsyncStream<EngineEvent>   // loading/undelivered/replyStored/timeout...
  }
  ```
  后台重入点：iOS 无 WorkManager；lead 裁决——恢复 = 启动 reconcile + 前台补算，领域层不依赖 BGTaskScheduler。

## 风险与开放问题

- **R1 后台恢复通道**（**lead 已裁决 2026-11-23**）：Android 靠 WorkManager（5 分钟延迟）保证「消息已保存，回复稍后自动补上」；iOS 无等价物。**裁决**：恢复 = 启动 reconcile（selectRetryableReplyTurns，sq/ReplyTurns.sq:106-112）+ 前台补算，领域层不依赖 BGTaskScheduler，不引入平台差异白名单。
- **R2 流式 delta 与整包落库**（**lead 已裁决**）：Android 私聊 1.14.27 实为整包落库，`streamChat` 是兼容遗留（AIService.kt:666-668 注释）。**裁决**：打字机效果属 UI 层，领域层不承诺 delta 流；引擎契约保持整包落库。UI 打字机（若做）由 Phase 2 UI 卡自建桥接，只消费已落库消息。
- **R3 测试口径**（**lead 已裁决 2026-11-23**）：以实测 376 为准（app/src/test+shared/src @Test 计数；androidTest 3 个不计）。
- **R4 边界接口**：提示词管线（T14）、记忆管线、ID 分配器（T16）三卡接口未冻结，本卡以「接口草案」引用；三卡合流后需回归本卡依赖矩阵。
- **R5 金样输入**（**reviewer 修正已落实**）：改用 `ios-port/golden/backup/minimal-1.14.27.zip` 的 7 条消息作种子（确定性、可复现）；「输入序列 + 期望消息序列」对由实现代理在实现期以**确定性 fake AI**（lead 裁决）产出，reviewer 复核。
- **待 reviewer/lead 裁决**：①（后台恢复）②（打字机）③（金样 fake AI）⑤（群聊单独卡）均已由 lead 裁决（见 R1/R2/R3/R5 与范围映射）；**④ reply_turns 不进备份的确认**仍开放（若未来要备份租约态，需改 A5 卡）；**R4 三卡接口冻结**仍开放（依赖 T14/T16/记忆管线卡产出后交叉核对本卡依赖矩阵）。

## 评审结论

- reviewer：**PASS-WITH-FIXES**（2026-11-23）。修正清单：① 依赖表 buildApiMessages 改归 T15 自持；② 验收 #6 输入序列改用 `golden/backup/minimal-1.14.27.zip` 的 7 条消息作种子；③ 状态机图 ReplyTurnStatus 行号更正（ReplyTurn.kt:23-28）、cancelled 无 Kotlin 常量（SQL 终止态，ReplyTurns.sq:94/:99）。以上均已落实。
- lead：**批准**（2026-11-23）。裁决：后台恢复 = 启动 reconcile + 前台补算（领域层不依赖 BGTask）；打字机效果属 UI 层，领域层不承诺；测试口径以实测 376 为准；金样回放用确定性 fake AI；群聊状态机单独立卡（下一批）。
