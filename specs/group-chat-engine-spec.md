# 规格卡：group-chat-engine（群聊轮次引擎 GroupChatEngine）

> 模板：ios-port/specs/规格卡模板-v1.md ｜ 任务：task-20（T20）｜ 状态：**PASS-WITH-FIXES（reviewer 2026-11-23，修正已落实）→ lead 已批准（2026-11-23）**
> 范围：Phase 1 P1-④ —— 群聊轮次状态机与域逻辑抽取（Android 1.14.27 @ ios-port 分支为行为 oracle）。
> 黄金法则：每条行为契约必须带 Android file:line。路径缩写：`app/` = `RhodesLink/app/src/main/java/com/rhodes/privatechat/`，`shared/` = `RhodesLink/shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/`，`sq/` = `RhodesLink/shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/`，`GVM` = `app/viewmodel/GroupChatViewModel.kt`。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 群聊轮次引擎（GroupChatEngine）——多成员群聊「用户消息/自动触发 → 多名成员按标签协议轮流发言」轮次的状态机、tagged 回复解析、群情绪规则、成员权限校验、群资料注入、持久化协议与恢复语义 |
| 所属阶段 | Phase 1（P1-④）；正式实现在 Phase 2 |
| 范围映射 | A1 核心聊天：GroupChatRoute（发言/图片/自动互动开关定时/群设置/成员权限/群历史/分享/删群，`ios-port/checklist/功能对照清单-v1.md` §1.1 行 46）+ GroupChatHistoryRoute（行 47）。**群自动互动 A1 的 iOS 语义**（`startAutoGroupChat` 前台循环 + durable plan）本卡自持；其触发层经通用 DelayedTaskLayer（契约 = T22 spec §A-12，lead 裁决由本卡实现波次同批落地）共享（见风险 R1）。**不在本卡**：NewGroup/EditGroup 群表写入与成员/禁言编辑（A2 群资料卡，本卡只消费 `saveGroup` 落库结果 SessionViewModel.kt:79-121）；提示词**模板文本**与协议模块内容（T14 卡，本卡只定组装顺序与注入 key 语义）；记忆/滚动摘要管线实现（后续卡，本卡只定调用时机与失败隔离）；UI 渲染（GroupChatScreen/GroupDetailScreen）；消息 ID 分配器实现（T16 卡，沿用 T15 接口语义） |
| 对应 Android 源码 | `GVM`（2945 行：sendGroupMessage:817 / executeGroupTurn:937（T11 已按 JVM 64KB 方法上限拆出的整轮执行体，内部嵌套 prepareTurn:1040 / runModelTurn:1514，抽取沿用该结构）/ sendHiddenGiftMessage:1849 / retryFailedMessage:1886 / resumePersistedReply:2124 / runScheduledAutoTurn:1933 / sendGroupImageMessage:2247 / resumeImageReply:2862 / recallMessageSegment:393 / restartGroupSession:598 / deleteGroup:631 / 自动互动 :647-815 / 解析与降级 :2099-2680 / 维护 :2693-2929）；`app/viewmodel/shared/GroupEmotionRules.kt`（306 行）、`GroupTaggedReplyParser.kt`（128 行）、`GroupRuntimeContext.kt`（84 行）、`GroupOutputBudget.kt`（28 行）；`app/automation/GroupAutoChatScheduler.kt`（134 行）+ `GroupAutoChatWorker.kt`（55 行）；`app/automation/ManualReplyScheduler.kt`（49 行）+ `ManualReplyWorker.kt`（96 行，群聊 surface 分支 :42-46, :80）；`app/viewmodel/ChatStageTimeout.kt`（62 行）；`shared/model/NetworkModels.kt`（GroupTurnState:210-222、GroupMsgResult:281-289）；`shared/data/ChatRepository.kt`（:327-344 协议转发）；`shared/data/ReplyTurnRepository.kt`（115 行）；`sq/ReplyTurns.sq`；`shared/settings/SettingsRepository.kt`（群设置键全集，见数据节）；`app/viewmodel/SessionViewModel.kt:79-121`（saveGroup）；`app/viewmodel/MainViewModel.kt:1813-1834`（setGroupMode 6 向过渡）；`RhodesApplication.kt:83`（启动 reconcile） |
| 依赖模块 | ChatEngine（T15 已批准卡）：**复用** `TurnStateMachine`/`ReplyTurnLease`/`withChatStageTimeout`（surface="group" 参数化）与 reply_turns 全部 SQL；提示词管线（T14 卡：`getPromptTemplate("group", templateMode)`、`getPromptModule("behavior"/"protocol"/"runtime", "group", …)`、`PromptModuleDefaults.outputProtocol("group", mode)` GVM:1482、`PromptPlaceholderRegistry.runtimeKeys("group", templateMode)` GVM:1411）→ 仅经 shared/domain 接口；记忆管线（后续卡：MemoryV2Pipeline/滚动摘要/每日摘要）→ 回复完成后回调，不阻塞本轮；消息 ID 分配器（T16 卡）；A2 群资料卡（saveGroup/成员/禁言写入）；通用 DelayedTaskLayer（`shared/domain/automation/`，契约以 **T22 spec §A-12** `DelayedTaskChannel` 冻结接口为准；lead 裁决 2026-11-23：由本卡实现波次同批落地该通用层，A1 触发的通道适配实现归 T22 胶水批，见 R1） |

## 与私聊引擎共性 / 差异表（复用点 vs 群聊独有）

| 维度 | 私聊 ChatEngine（oracle） | 群聊 GroupChatEngine（oracle） | 复用 / 独有 |
|---|---|---|---|
| 轮次状态机与租约层 | `reply_turns` 表 + claim/complete/release/fail/cancel；turnId=`private:{sessionId}:{msgId}`，surface=`private`（T15 卡） | **同一张表与同一套 SQL**；turnId=`group:{sessionId}:{msgId}`（GVM:870）/`auto:{groupId}:{plan.token}`（:1948），surface=`group`，triggerKind ∈ {manual, gift, image, group_auto}（:878, :1950-1953, :2300）；claim 语义一致（ReplyTurnRepository.kt:31-34） | **复用** TurnStateMachine/ReplyTurnLease（surface 参数化）；独有：auto turn 携带 `autoPlanToken` |
| 自动互动第二层租约 | 无 | `GroupAutoChatPlan`（token/revision/state/claimedAt/leaseExpiresAt/dueAt，SettingsRepository.kt:1195-1223）二次 claim（GroupAutoChatScheduler.kt:80-86），CLAIM_LEASE_MS=5min（:24） | **独有**（A1 群自动互动语义；触发层与 T22 A7 共享） |
| 超时预算 | 整轮 180s(+60s)/prompt 50s/model 120s/write 15s/增强上下文 12s/内容重试剩余<5s 跳过（T15 卡） | 整轮 **210s(+60s → 270s)** GVM:123,131,207-208 / prompt **60s** :121 / model **150s(+60s → 210s)** :122,203-204 / write 15s :119 / 可选上下文 10-30s 逐项、剩余 prompt 预算-2s、<1s 跳过 :997-1002 / 重启清理等待 **2s** :120, :856 / 内容重试无条件一次 :1677-1698 | **复用** withChatStageTimeout（ChatStageTimeout.kt:15-33，surface="group"）+ 阶段中文名（:55-60）；**独有**：预算常数集不同 → `GroupStageBudget` |
| 上下文超限降级 | 历史轮数减半（min 5），最多 3 次（T15） | 历史条数减半（>20→半、>10→10、>5→5、否则 1，保最新 10），最多 **6** 次，`removeAt(1)` 逐条裁剪（GVM:1648-1671） | 复用机制，**参数不同**（参数化） |
| 用户消息合并批次 | ≤2 条、≤600 字符、250ms 防抖、pendingIds supersede（T15） | **同常数同机制**（MAX_MERGED_USER_MESSAGES=2 :117、MAX_MERGED_USER_CHARS=600 :118、delay(250) :1064、pendingIds :1069-1099）；被合并 turn → `cancelReplyTurn(…, "merged_into_batch")` 终态（:1077, :1084） | **复用**；群聊 cancel 理由串不同 |
| 取消语义 | `cancelSessionRequests`（generation+1） | `cancelGroupRequests`（:261-271）：generation+1 + 清 pendingIds/groupAiJobs + **autoChatGenerations 换代 + 停自动循环** + 清 loading | 机制复用，**独有** auto 侧失效 |
| 回复解析协议 | 单回复 JSON（OfflineModeResponse）+ MessageParser | 多发言人 tagged 协议：【发言人: 发言标识】【旁白】【心情/情绪】【群聊回合状态】六字段（GroupTaggedReplyParser.kt:23-42, 48-102）→ JSON 数组兜底（GVM:2504-2526）→ 裸发言人行兜底（:2647-2680）→ 归一化白名单过滤（:2099-2121） | **独有**（GroupTaggedReplyParser/normalizeGroupResults/extractSpeakerLines） |
| 空回复判定 | 至少一条非旁白非空台词（isCompletePrivateReply） | 至少一条**当前成员** dialogue 且非旁白非空（isDisplayableGroupReply :2149-2150）；不满足→内容重试一次（改写 system 追加【重新生成要求】 :1685-1692）→仍空→markGroupMessagesUndelivered（:1701-1709） | 语义同构，**判定与重试文案独有** |
| 群情绪规则 | 私聊单人 emotion 字段 | 成员心情短词（≤10 字）+【成员心情】快照（≤60 字/≤12 人）+ **6 小时回退窗** + (沿用上一轮) 标注注入（GroupEmotionRules.kt:19-28, 183-233, 288-305）；撤回后重算快照（GVM:427-458） | **独有**（GroupEmotionRules） |
| 群回合状态/主线 | PrivateTurnState | 【群聊回合状态】解析/校验/保守降级（GVM:2596-2644）+ 主线摘要（:2589-2594）+ 连续性块注入（:1428-1435） | **独有**（GroupTurnStatePolicy） |
| 成员权限校验 | 无 | 成员名册解析（session.members，AppState 兜底 DB :1124-1129）+ **mutedMembers 过滤**（:1133-1134）+ 全员禁言系统消息（:1155-1167）+ 名册未解析 release(retryAt=+5s, "group_members_unresolved")（:1142-1154）+ 发言人白名单（extractTaggedGroupResults :2537-2547） | **独有** |
| 群资料注入顺序 | buildApiMessages 单链 | 固定顺序：session(10s) → 成员资料(10s) → 群回合状态/主线(10s) → 关系(20s 可选) → 被点名成员私聊背景(20s 可选) → 群滚动摘要(10s 可选) → supervisorScope{成员私聊背景|群向量记忆|公开记忆}(20s 可选) → 近期动态(20s 可选) → 每日摘要(10s 可选) → 成员人设(排序 by id) → 知识库绑定(10s)+召回(30s 可选) → grpReplacements(45+ key) → 连续性+上轮情绪 → GroupRuntimeContext 别名去重合并（:989-1543）；**任一可选块超时/失败跳过不影响回复**（:999-1017） | **独有**（注入顺序契约 + GroupRuntimeContext alias 去重，GroupRuntimeContext.kt:8-14, 26-74） |
| 三模式分支 | online/offline/director 引擎侧硬分支（T15） | online 禁止旁白（归一化硬删 :2109；输出清单 :1571）；offline/director 允许旁白 + 共享场景规则（foundation :1390-1406、modeHistoryBoundary :1407）；模式过渡 pending 一次性注入（setGroupMode MainViewModel.kt:1813-1834；GVM:1550-1563, :1770-1772）；auto 轮次 templateMode="auto" 但行为按真实 mode（:1307, :1440） | 机制同构，**分支内容与 auto 维度独有** |
| 图片管线 | 占位 JSON → 识图回填/failed | 同构（GVM:2281-2361；5 字段识图提示 :2325-2333；visibleSummary ≤500 :2355、visionSummary ≤800 :2359）；差异：群聊 promptText 让**所有在场成员**回应图片（:2362-2368） | **复用**管线形状；群聊 prompt 独有 |
| 礼物管线 | gift_hidden + giftPromptText（T15） | 群聊礼物 payload 增加 recipientNames（GVM:1861-1867）；turn triggerKind=gift（:1878） | 机制复用，payload 独有 |
| 撤回 | 整条撤回 + 分段撤回 + cancelReplyTurnForSource | **仅分段撤回** recallMessageSegment（:393-421）；非法参数退化整条删除（:395-412）；成功路径 deleteDisplayEvent + **情绪快照重算 + 上下文重建意图落盘**（:414-420, :427-458, :461-481） | 独有（撤回后情绪/上下文一致性） |
| 重新生成 / 续写 / 存档 | 有 regenerate/continue/archive（T15） | **均无**（GroupChatViewModel 无对应入口；群聊无存档表交互） | **差异白名单：iOS 不实现**（见 R2） |
| 恢复（杀进程） | ManualReplyWorker 5min → resumePrivate* | 同一 worker：turnId 分支按 surface 路由 `resumeGroupReply`/`resumeGroupImageReply`（ManualReplyWorker.kt:42-46）；resumePersistedReply 语义同构（type 重置 gift→gift_hidden 其余→text，GVM:2124-2146）；iOS 同 T15 裁决：启动 reconcile + 前台补算，领域层不依赖 BGTask | **复用**（同一 worker/同一裁决） |
| 结算 | 亲密度+2..4、每日龙门币 50、催眠递减（T15） | **无亲密度/催眠结算**；仅手动轮次（!isAuto）每日龙门币 50（:1783-1786）+ 未读计数（visibleGroupMessageCount 旁白不计 :2488-2490）+ 后台通知（:2474-2478）+ lastMessage 更新（:1782）；自动轮次不结算 | **独有**（结算面收敛） |
| 会话切换/分页 | 50 条分页、flow 权威（T15） | 同构：CHAT_PAGE_SIZE=50（:116）、一次历史读失败不杀死 flow 订阅（:297-308）、loadOlder hasMore 判定（:333-352）、merge 去重排序（:364-377） | **复用**（UI 订阅层，本卡域层只需 getRecentMessages 接口） |
| 输出预算 | 厂商默认/服务端上限 | GroupOutputBudget：显式 5000（4096×1.22），zhipu/minimax/siliconflow/custom 返回 null 沿用默认（GroupOutputBudget.kt:17-27）；请求时传入 chatResult（GVM:1617-1620） | **独有** |

## 输入 / 输出 / 边界

### 输入
| 输入 | 类型/来源 | 空值与非法值行为（oracle） |
|---|---|---|
| 用户文本/自动触发 | UI/自动循环 → `sendGroupMessage(groupSessionId, groupName, text, mode, autoSpeak, isAuto, autoGeneration, autoPlanToken, autoPlanRevision, userMessageAlreadyStored, sourceMessageId, retryMessageId, replyTurnIdOverride, replyLeaseTokenOverride, …)`（GVM:817） | isAuto 且（总开关关或群开关关）→ `onResponseComplete(false)`（:822）；isAuto 且 plan token/revision 非当前 claim → false（:823）；isAuto 且 groupAiJobs 活跃 → false（:824）；非 auto 且配置无效 → `_lastSendError` + false（:826-832）；文本 trim 后空且非 auto → 不写用户消息（:860） |
| 恢复输入 | `resumePersistedReply(groupSessionId, msgId, …)` / `resumeImageReply` | session 或消息缺失/非 isMe → `onComplete(true/false)` 直接返回（:2128-2131, :2869）；租约令牌透传（:2141-2142） |
| 自动轮次输入 | `runScheduledAutoTurn(groupId, token, expectedGeneration, planRevision)` | groupAiJobs 活跃则 `while … delay(100)` 等待不抢占（:1935）；claim 失败（plan 非 pending/已超租约）→ false（:1937）；generation 不符 → releaseClaim 后 false（:1938-1941）；session 不存在 → cancel + false（:1942-1945） |
| 图片输入 | `sendGroupImageMessage(…, imageForModel, caption, existingMessageId, …)` | 未配识图模型 → Toast 文案 + false（:2255-2259）；imageForModel 空 → 「无法读取这张图片…」+ false（:2260-2264） |
| 群资料 | session.members/mutedMembers/rules（ChatSession.kt:15-18）；成员 Operator.groupPrompt | 名册一个都解析不出 → release + 「群成员资料尚未同步，请稍后重试」（:1142-1154）；全员禁言 → 系统消息 + complete turn（:1155-1167）；rules 空 → 注入「无」（:1331） |

### 输出
| 输出 | 去向 | 副作用 |
|---|---|---|
| 用户消息落库 | `sendMessageAndCreateReplyTurn`（同事务建 turn，ChatRepository.kt:327） | 成功后 `onMessageSent`（:885）+ `ManualReplyScheduler.scheduleTurn`（:887）+ pendingIds 登记（:891）+ `resetAutoGroupChatTimer`（:893）+ unhideSession（:894）；写失败回滚不落任何行 |
| AI 群回复落库 | `sendReplyAndCompleteTurn`（type=`ai_json`，content=GroupMsgResult 数组 JSON，emotion/speakerId encodeDefaults，GVM:82, :1724-1761） | `check(…){ "reply turn lease lost" }`（:1760）；成功后写群回合状态+主线摘要（:1762-1763）、completeTurn（:1766）、清 pending 模式过渡（:1770-1772）、unhide+后台通知（:1779-1780）、lastMessage（:1782）、未读（:1788）、维护调度（:1789） |
| 群回合状态 | settings 键 `group_turn_state_{groupId}`（GroupTurnState 9 字段，NetworkModels.kt:210-222） | 仅「本轮解析出的状态经校验」或「保守降级状态」可写（:1731-1753, :2617-2644）；主线摘要另写 `group_plot_summary_`（:1763） |
| 自动计划状态 | settings 键 `group_auto_plan_{groupId}`（GroupAutoChatPlan） | 成功且仍是当前 claim → scheduleNext（round+1 或达上限置 complete）（GroupAutoChatScheduler.kt:51-63）；失败 → releaseClaim 保一轮重试（:65-67） |
| 错误输出 | 失败消息 `markGroupMessagesUndelivered`（gift_hidden→gift_reply_failed，其余→send_failed，:1919-1930） | `_lastSendError` 文案分类 classifyGroupError（:2492-2502）；finally 无条件 release turn（error="reply_not_completed"，:1838-1840） |

### 边界（参数全集，逐项 oracle）
| 参数 | 值 | oracle | 备注 |
|---|---|---|---|
| 整轮总预算 | **210_000ms**，DeepSeek 深度思考 +60_000 → **270_000ms** | GVM:123, :131, getter :207-208 | `turnBudgetMs` 进轮固化一次（:986）；租约 leaseUntil 用同一 getter（:1048, :1955） |
| 提示词构建预算 | 60_000ms；超时抛 `ChatStageTimeoutException("group","prompt_build",…)`（:1608-1611） | :121 | 可选上下文另按 min(块预算, 60s-已耗-2s)、<1s 跳过（:997-1002） |
| 模型请求预算 | 150_000ms（+60s 深度思考）；每次取 min(模型预算, 剩余整轮)（:1614） | :122, :203-204 | 阶段名 `model_primary_request` / `model_content_retry` |
| 上下文超限重试 | 最多 **6** 次、历史条数阶梯减半保最新 10（:1648-1671） | :1646-1667 | 仅 `isContextLimitError`（:2230-2239）触发 |
| 内容重试 | 首次解析无可展示台词 → 重试**一次**（system 追加【重新生成要求】，:1685-1692） | :1677-1698 | 无条件一次（不设剩余预算门槛，与私聊不同） |
| 消息写超时 | 15_000ms：msgId（:863）、用户消息事务（:872-881）、AI 回复写 min(15s, 剩余)（:1722, :1755） | :119 | 用户消息写超时 → 「群消息保存超时，请稍后重试」（:897-906） |
| 重启清理等待 | 2_000ms join 重启清理 job（:120, :856） | :120 | 防新轮次与旧时间线清理竞争 |
| 会话/成员读取 | session_read 10s、member_profiles_read 10s、group_turn_state_read 10s（required，:1112-1174） | :1112-1174 | requiredGroupContext 失败直接抛（:1019-1039） |
| 可选上下文块 | 关系/成员私聊背景/群向量记忆/公开记忆/近期动态 20s；摘要/每日摘要/知识库绑定 10s；知识库召回 30s（:1180-1288） | :989-1018 | 超时/失败 → fallback（""/「无」/null），不影响回复 |
| 用户消息合并 | 一批 ≤2 条、合计 ≤600 字符（:117-118, :1081-1103） | :117-118 | 合并提示「用户连续补充了以下消息…」（:1101-1103） |
| 自动循环 | MIN_AUTO_LOOP_DELAY_MS=1s 轮询下限（:114, :719）；计划租约 5min（GroupAutoChatScheduler.kt:24）；轮次上限 `groupAutoMaxRounds`（:56-59）；间隔随机 ∈ [min,max] 秒（:115-121） | :114-121 | 已到期计划不热旋 |
| 情绪规则上限 | 心情 ≤10 字、快照 ≤60 字/≤12 人、回退窗 6h（GroupEmotionRules.kt:19-28） | GroupEmotionRules.kt | 回退值不写进落库 JSON（:5-13 注释） |
| 群资料上限 | rules ≤3000、单成员人设 ≤1500、成员资料块 ≤8000 字（:124-126） | :124-126 | 注入前不硬截断，仅模型侧协议约束（上限为设置/提示词约束） |
| 并发/重入 | 每群 `sharedGroupMessageMutexes`（:136, :235, :1059）；`sharedGroupAiJobs` 载荷守卫（:137, :1108, :1842）；generation 双复核（:1062-1063, :1702-1703, :1718-1720）；发送前 delay(250)（:1064） | :1062-1064 | 双发第二发被 supersede → turn cancelled（merged_into_batch），由恢复 worker 补答 |
| 取消语义 | `cancelGroupRequests`（:261-271）：generation+1、清 pending、cancel groupAiJobs、autoChatGenerations 换代+停自动循环、清 loading | :261-271 | CancellationException 透传并置未送达（:1821-1823） |
| 杀进程恢复 | ManualReplyWorker（5 分钟延迟，claim 新租约，180s worker 租约 ManualReplyWorker.kt:32）路由 surface=group（:42-46） | automation | iOS 无 WorkManager：沿用 T15 裁决（启动 reconcile + 前台补算） |

## 行为契约（逐条，每条带 Android oracle 位置）

> 状态机图（源码实际语义）：
> 内存视图：`Idle → Saving(用户消息事务) → Thinking（groupLoading）→ ReplyStored / Undelivered(send_failed/gift_reply_failed) / SystemMuted(全员禁言系统消息) / TimeoutToast / Cancelled / Failed(系统消息)`；
> 持久化租约视图：与 T15 同一 `reply_turns` 表（surface=`group`）：`pending → running(claim) → succeeded(complete)`｜`pending(release: retryAt+error)`｜`failed`｜`cancelled`（SQL 终止态：`cancelReplyTurn` ReplyTurns.sq:94、`cancelReplyTurnBySource` :99；Kotlin 常量同 ReplyTurn.kt:23-28 无 CANCELLED）；`retryableTurnIds`（ReplyTurnRepository.kt:111-114）同时捞出群聊/私聊可重试 turn；
> 自动互动第二层：`GroupAutoChatPlan.state: pending → claimed(claim: leaseExpiresAt=now+5min) → pending(scheduleNext round+1 / releaseClaim 保一轮)`；`isGroupAutoChatComplete` 达上限终止（SettingsRepository.kt:1230）。

1. **发送主流程（sendGroupMessage → executeGroupTurn）**：守卫（:822-832）→ 用户消息同步落库（非 auto 且未预存，:860-896）→ `executeGroupTurn`（T11 已按 JVM 64KB 方法上限拆出的执行体，内部 `prepareTurn`/`runModelTurn` 两个嵌套局部函数，闭包按引用捕获局部变量，控制流与拆分前逐字一致）→ claim 租约 → mutex → 250ms 防抖 + generation 复核 → 合并批次 → 群资料注入（见契约 10）→ 模型请求（内容重试 1 次）→ tagged/JSON/裸行三级解析 → 归一化 → 情绪快照 + 回合状态 → `sendReplyAndCompleteTurn`（lease 校验）→ 结算。oracle：GVM:817-930（用户落库段）、:937-1846（整轮执行体）。
2. **用户消息写入时机**：用户消息在 AI 请求前同步持久化（:872-881，同事务建 turn，id=`group:{sessionId}:{msgId}`，triggerKind=manual）；AI 回复在解析+归一化成功后一条事务落库（:1755-1761）；写超时 15s（:863, :872）。**平台差异白名单：iOS 沿用整包落库**（与 T15 R2 同一裁决；群聊亦无 delta 流式落库）。
3. **租约与孤儿防护**：turn 缺失时先 `createReplyTurn` 再 claim（:1044-1047）；claim 被拒 → 「该消息正在恢复发送，请稍候」+ 重新调度恢复（:1049-1055）；恢复 worker 在用户消息事务成功后立即调度（:887），杜绝「消息已存但无人补答」；被合并/被 supersede 的 turn 走 `cancelReplyTurn("merged_into_batch")` 终态（:1077, :1084）——cancelled 不再被任何 claim/reconcile 触及（ReplyTurnRepository.kt:92-100 注释）；`cleanupOrphanedSucceededTurns` 只删 succeeded 孤儿（ReplyTurnRepository.kt:72-76）。
4. **重复发送防抖**：`retryingMessageIds` 防重试重入（:1887）；每群 `sharedGroupMessageMutexes` 串行（:136, :1059）；`groupAiJobs` 载荷守卫使 finally 只清自己的 job（:1842）；发送后 250ms 检查 pendingIds，不在 pending（被合并/被取消）→ turn cancelled 静默让位（:1069-1087）；`groupGenerations` 进轮前后双复核（:1062, :1702-1703, :1718-1720）。
5. **成员权限校验（群聊独有）**：成员名册 = `session.members` 按逗号拆分，先试 AppState 内存名册、任一成员解析不出则整表退回 DB（:1121-1129）；`mutedMembers`（id 或名）过滤出 activeMembers（:1133-1134）；activeMembers 为空且名册非空 → 系统消息「所有成员已被禁言，无法回复」+ turn complete + `responseStored=true`（:1155-1167）；名册全部解析失败（读/同步问题而非禁言规则）→ **不燃尽 turn**：`release(retryAt=now+5s, "group_members_unresolved")` + 「群成员资料尚未同步，请稍后重试」（:1142-1154）。
6. **轮转发言 tagged 协议解析（群聊独有）**：`GroupTaggedReplyParser.extractTagged` 按【发言人: 发言标识】/【旁白】/【心情|情绪】/【群聊回合状态】六内部字段标签切块（GroupTaggedReplyParser.kt:23-42, 48-102）；【心情】归属最近的前一个【发言人】，旁白块内心情丢弃、正文仍算旁白（:86-98）；`resolveSpeaker` 注入：优先稳定 id，显示名仅当唯一且 ≠ 群名（GVM:2537-2547）——**名单外成员与玩家「我」的发言块整体丢弃**（GroupTaggedReplyParser.kt:44-47 注释）。
7. **三级解析回退链与归一化**：tagged 解析 → 空则 JSON 数组（cleanJson/JsonBlockExtractor/messages|segments 包裹，:2504-2526）→ 空则裸发言人行正则（:2647-2680）；`normalizeGroupResults` 硬过滤：online 模式 narration 直接丢弃（:2109）、第一人称旁白丢弃（:2108, :2221-2228）、空消息/名单外 speaker 丢弃（:2110）、旁白 emotion/speakerId 清空（:2117-2118）、`stripSpeakerPrefix`/`stripLeakedSegmentLabel` 剥离泄漏前缀（:2101-2103, :2211-2219）；最终 gate = `isDisplayableGroupReply`（≥1 条当前成员非旁白非空台词，:2149-2150）。
8. **群情绪规则（群聊独有）**：本轮心情归属 = 台词内解析 > 【成员心情】字段声明（仅本轮已发言成员）> 上一轮快照 > 最近 20 轮历史；③④ 属回退并记入 `emotionFallbackKeys`，**6 小时窗口**外或本轮未发言成员不回退（GroupEmotionRules.kt:177-233, :250-280）；清洗截断：去标记/引号、截首个标点、≤10 字（:52-69）；快照 `标识=心情;…` ≤60 字/≤12 人（:98-113）；落库 JSON 只写本轮真实解析的心情（:5-13 注释）；下一轮注入时沿用值标注「(沿用上一轮)」且必须能在历史找到真实产生时刻（:288-305, GVM:1416-1435）；撤回后若被撤回消息正是最新一轮 → 重算快照，整条删除则清空快照（GVM:427-458）。
9. **群回合状态与主线（群聊独有）**：`parseGroupTurnState` 解析六字段（当前主线必填否则 null；各字段截断 80/16/80/100/16/60/60，:2596-2615）；`validateGroupTurnState`：userTurnType ∈ 13 项白名单、threadStatus ∈ {继续,等待用户,已收束,已转题}、已收束/已转题时 nextFocus 必须为「无」、长度超限拒绝（:2617-2624）；解析失败 → `deriveGroupTurnState` 保守降级（提问/亲密邀约/补充/无用户发言 规则 + 上一状态承接，:2626-2644）并记「已保守降级」日志（:1751-1753）；成功后 `putGroupTurnState`（updatedAt=情绪结算时刻）+ `putGroupPlotSummary`（currentTopic，:1762-1763）。
10. **群资料注入顺序（群聊独有，固定顺序契约）**：required 序列 session(10s) → 成员资料(10s) → 群回合状态/主线(10s)（:1112-1176）；可选序列（**任一失败/超时跳过，不影响回复**，:989-1018）关系 20s → 群滚动摘要 10s（restartAt 过滤，:1191-1193）→ supervisorScope 三并行{被点名成员私聊背景(按名点名、≤min(groupMemberMemoryCount,2)人，:1186-1212)、群向量记忆(:1214-1222)、公开记忆(:1223-1231)} → 近期动态 20s(:1234-1241) → 每日摘要 10s(:1242-1248) → 成员人设块（name/gender/title/groupPrompt→privatePrompt→description 兜底、按 id 排序，:1265-1271）→ 知识库绑定 10s+召回 30s(:1272-1288) → `grpReplacements` 45+ 占位符(:1326-1364) → 连续性块+上轮情绪(:1416-1435) → `GroupRuntimeContext.build`（别名家族去重：MEMBER_PROFILES/MEMBER_NAMES/SHORT_TERM_SUMMARY|GROUP_SUMMARY|GROUP_CONTEXT/MEMORY_V2_CONTEXT|SHARED_MEMORIES|MEMORY_ANCHORS/RELATION_HINTS|GROUP_RELATION_HINTS；重复值替换为「（同本轮已提供的X）」，GroupRuntimeContext.kt:8-14, :26-74）→ 成员标识回退头（:61-68）。Provenance 逐条判定注入成败并落结果卡（:153-196, :1295-1303）。
11. **三模式与 auto 维度**：userMessage：auto → 「（用户没有新发言…）」、autoSpeak → 空闲闲聊提示、否则 requestText（:1304）；userObserving 按 mode 差异化（:1312-1316）；grpModeFormat 线上禁止旁白/共享场景可用旁白（:1317-1320）；groupFoundation 线上 vs 面对面/导演规则块（:1390-1406）；modeHistoryBoundary 线下模式禁止沿用线上媒介表达（:1407）；`templateMode = if (isAuto) "auto" else mode` 选模板，但行为模块按**真实 mode**（:1307, :1440）；自定义协议模块叠加在固定协议之上且固定协议必须优先（:1480-1487）；输出检查清单要求首行【群聊回合状态】+ 每位成员【发言人: id】+【心情】(2~6 字)+ 台词（:1567-1572）；模式过渡 pending 一次性注入并在成功轮次清除（:1550-1563, :1770-1772；setGroupMode 6 向过渡文案 MainViewModel.kt:1813-1834）。
12. **模型请求与上下文降级**：`generateGroupReply` 预算 = min(模型预算, 剩余整轮)，maxOutputTokens = `GroupOutputBudget.groupMaxOutputTokens(provider)`（5000 或 null 沿用服务端默认，GroupOutputBudget.kt:17-27；:1613-1623）；本地 token 裁剪仅当 `!automaticContextWindow`（`trimPromptHistory`，:1595-1603）；上下文超限重试 ≤6 次、阶梯减半保最新 10、`removeAt(1)` 逐条（:1648-1671）；提示词构建总耗超 60s 抛阶段超时（:1608-1611）。
13. **整轮预算与阶段超时**：`turnBudgetMs` 进轮固化（:986），租约（:1048）与超时文案同源；可选上下文 skip 阈值 1s（:999-1002）；`withChatStageTimeout("group", stage, budget)`（ChatStageTimeout.kt:15-33），阶段中文名含群聊专属 6 项（group_turn_state_read/group_summary_read/group_relationship_read/member_private_memory_read/group_memory_read/group_public_memory_read，:55-60）；捕获 ChatStageTimeoutException → 置未送达 + `_lastSendError = e.userMessage("群聊")`（:1797-1808）；裸 TimeoutCancellationException → 「群聊整轮处理超时（预算X秒），本轮未生成或保存AI回复，请重试」（:1809-1820）。
14. **回复落库与结算**：aiMsgId 分配（min(15s, 剩余)，:1721-1722）→ `enrichGroupSpeakerIds` 按显示名唯一性补 speakerId（:1723, :2581-2587）→ `sendReplyAndCompleteTurn` + lease 校验（:1755-1761）→ 回合状态/主线写入（:1762-1763）→ ManualReplyScheduler.completeTurn + 批次内逐条 complete（:1766-1769）→ unhide + 后台通知（:1779-1780）→ lastMessage=`"${speaker}：${message.take(50)}"`（:1782）→ **仅手动轮次**每日龙门币 50（:1783-1786）→ 未读（当前非该群时，旁白不计条数 :1788, :2482-2490）→ 维护调度（:1789）。群聊**无**亲密度/催眠结算（差异白名单）。
15. **失败与未送达**：无可展示回复 → `markGroupMessagesUndelivered`（gift_hidden→gift_reply_failed、其余→send_failed，:1919-1930；batchIds 优先，:1709, :1807, :1819, :1822, :1835）；错误文案 `classifyGroupError` 分类（401/402/429/50x/超时/DNS/SSL/连接，:2492-2502）；finally：未落库则 `releaseReplyTurn(…, "reply_not_completed")` + 清 loading + 摘 job + 解锁 + `onResponseComplete(responseStored)`（:1837-1845）。
16. **重试与恢复**：`retryFailedMessage` 仅接受 `isMe && type ∈ {send_failed, gift_reply_failed, image}`（:1889-1890）；image 分支还原 uri/caption 重走图片管线（:1896-1903）；gift 用 `giftPromptText` 还原载荷（:1907-1909）；claim 成功后重置 type（gift→gift_hidden、其余→text，:1913）；`resumePersistedReply` 同构（session/消息校验、type 重置、租约透传，:2124-2146）；`resumeImageReply` 从图片 JSON 还原后重走（:2862-2874）；Android 侧由 ManualReplyWorker surface=group 驱动（ManualReplyWorker.kt:42-46, :80-81）。**平台差异（沿用 T15 R1 lead 裁决）**：iOS 无 WorkManager——恢复 = 启动 reconcile（`retryableTurnIds`，ReplyTurnRepository.kt:111-114）+ 前台补算，领域层不依赖 BGTaskScheduler。
17. **图片与礼物管线**：群图片占位 JSON `{imageUri, caption, visionSummary:""}` 先落库（type=image，triggerKind=image，:2281-2302）→ 识图（5 字段固定 JSON 提示，:2325-2333）→ 失败 → 加 `status:"failed"` + 「图片识别失败，请检查识图模型设置后重试。」（:2337-2349）→ 成功 → 回填 visionSummary(≤800)、visibleSummary 抽 ≤500（:2353-2361）→ promptText 让所有在场成员回应图片（:2362-2368）→ 交还 sendGroupMessage（userMessageAlreadyStored=true，:2372）；claim 被拒时消息已安全持久化，composer 不等待识图（:2318, :2320）；群礼物 payload `{event:"gift", prompt, imageUri, giftName, recipientNames}`（:1861-1867）→ gift_hidden 消息 + turn triggerKind=gift（:1868-1882）。
18. **撤回分段与情绪/上下文一致性（群聊独有）**：`recallMessageSegment` 仅 ai_json；非 ai_json/负索引/无该段 → 退化整条删除（:395-412）；`markSegmentRecalled` 支持数组/messages/segments 包裹、全部段已撤回 → 整条删除（:537-566）；成功 → 改 content + deleteDisplayEvent(msgId, segmentIndex) + 情绪快照重算 + 上下文重建（:414-420）；`refreshLatestRoundEmotionSnapshot` 只处理「被撤回消息 = 最新一轮」的 case，否则不动（:427-458）；`rebuildGroupContextAfterRecall` 先落 `context_rebuild_pending_` 意图再失效（杀进程可续），旧摘要保留至新摘要成功（:461-481）；历史注入时已撤回段过滤（:2019-2021, :2057-2069）。
19. **重启 / 删群（群聊无存档/擦除）**：`restartGroupSession` 先 cancel 再**先发布新边界**（putSessionRestartAt :604）→ 清主线/回合状态/游标（:605-608）→ 异步清理：`deleteReplyTurnsBySession`（G-05：在飞 turn 不得写入新时间线，:614）、删短期记忆、`clearGroupRestartMemory`、系统消息「已从这里开始新的群聊…」（:616-626）；`deleteGroup` = cancel + 停自动 + 删 `group_auto_*` 三键 + cancel scheduler + `group_deleted_` 标记 + 清主线/回合状态 + `deleteSession`（:631-645）。**差异白名单：群聊无存档创建/加载、无重新生成、无续写、无擦除**（GVM 无对应入口，R2）。
20. **自动互动（A1 群自动互动 iOS 语义，A7 交叉）**：开关 = `autoAiEnabled && getGroupAuto(groupId)`（:647-648）；状态文案按 plan.state（claimed→「正在生成」、pending→倒计时/「等待触发」，:650-660）；`setAutoGroupChatEnabled(true)` → 清 complete 标记、roundCounts=0、resetPlan、启动内存循环（:662-685）；`startAutoGroupChat` 以 generation 令牌 + activeRun 守卫启动 LAZY 循环：`ensurePlan → delay(dueAt-now, 下限 1s) → generation 复核 → runScheduledAutoTurn`（:687-740）；用户发言即 `resetAutoGroupChatTimer`（:893, :742-760）；`runScheduledAutoTurn` 先等 groupAiJobs 空闲（:1935）→ plan claim（pending 且到期才可，:80-86）→ 建 `auto:{groupId}:{token}` turn（triggerKind=group_auto，autoPlanToken=plan.token，:1948-1953）→ claim reply turn → sendGroupMessage(isAuto=true) → 成功且仍为当前 claim → scheduleNext（round+1 / 达上限置 complete），失败 → releaseClaim 保一轮重试（:1959-1978）；GroupAutoChatWorker 在 claim 前校验输入（BackupRestoreMaintenance/输入参数校验，GroupAutoChatWorker.kt:22-26），异常时 `releaseClaimForRetry`(60s) 把 plan 置回 pending(dueAt=now+60s) 并重新入队（:44-50, :70-77）；worker 入队用 `ExistingWorkPolicy.REPLACE` 且**未配置 setBackoffCriteria（无退避）**——「重试」语义 = plan 层 pending 重排 + REPLACE 新 worker，而非 WorkManager backoff（GroupAutoChatScheduler.kt:123-130）；启动/恢复后 `reconcileSuspending` 重建所有已开自动群计划（GroupAutoChatScheduler.kt:107-113；RhodesApplication.kt:83；BackupRestoreCoordinator.kt:117）。**iOS 语义（R1 lead 已裁决 2026-11-23）**：通用 DelayedTaskLayer 由本卡实现波次同批落地（契约 = T22 spec §A-12 `DelayedTaskChannel` 冻结接口：schedule/cancel/pendingKeys/onDue）；本卡领域层只依赖该协议 + AutoChatPlanPort，A1 自动互动的通道适配实现（Timer/BGTask 通道）归 T22 胶水批；手动回复恢复 = 启动 reconcile + 前台补算（沿用 T15 R1 裁决，不经 DelayedTaskLayer）。

## 数据与迁移

- **涉及表**（对照 `ios-port/golden/db/schema/`，与 T15 同一组表，群聊以 `operatorId`/`sessionId` 前缀与字段区分）：
  `chat_sessions`（群会话 id=`group_<uuid>`（SessionViewModel.kt:83）、operatorId=id、operatorName=群名、**members**/**mutedMembers**（逗号分隔，ChatSession.kt:15-18）、**rules**（≤3000 字契约 GVM:124）、mode/lastMessage/unreadCount）；`chat_messages`（群 AI 消息 type=`ai_json`，content 为 `List<GroupMsgResult>` JSON——emotion/speakerId 字段 encodeDefaults 空值也写空串、ignoreUnknownKeys 容忍老数据（GVM:82）；群图片 type=`image` JSON 三态；群礼物 type=`gift_hidden`）；`chat_display_events`（messageId+segmentIndex 分段展示，撤回删段 GVM:417）；`reply_turns`（surface=`group`、triggerKind ∈ manual/gift/image/group_auto、autoPlanToken 列承载 plan.token，sq/ReplyTurns.sq:1-22）；`memories`（群短期摘要/每日摘要，sessionId=groupId）。
- **设置键（不进 SQL 表，均在 SettingsRepository.kt）**：`group_auto_${groupId}`（:1174/:1177）、`group_auto_plan_${groupId}`（GroupAutoChatPlan JSON：token/dueAt/round/state/claimedAt/leaseExpiresAt/revision，:1195-1223，旧三键 group_auto_plan_token_/due_/round_ 读时迁移）、`group_auto_plan_complete_`（:1230-1231）、`group_event_auto_`（:1233-1236）、`group_mode_`（:1239-1242）、`pending_group_mode_transition_`（:1246-1252）、`group_turn_state_${groupId}`（:1505-1513）、`group_plot_summary_${groupId}`（take(220)，:1515-1519）、`group_deleted_${groupId}`（GVM:638）、`context_rebuild_pending_/changed_${groupId}`（GVM:466-467, :491-499）、`archive_context_active_${groupId}`（GVM:2556）；共享键：`session_restart_at`、`summary_cursor`、`memory_extraction_cursor`、`max_used_message_id`、rolling summary 状态。预算/阈值参数：`groupSummaryTriggerRounds`（:161）、`groupSummaryRawTailRounds`（:169）、`groupMemberMemoryCount`（:453）、`groupMemoryExtractionThreshold`（:461）、`groupRelationshipHintCount`（:473）、`autoAiEnabled`（:510）、`groupChatMinInterval`/`groupChatMaxInterval`/`groupAutoMaxRounds`（:616-624）、`groupMsgMin`/`groupSpeechMin`/`groupNarMin` 等（:1090-1115）。
- **迁移**：本卡不新增 iOS 专属迁移（沿用 1..23.sqm 全集；reply_turns/chat_messages 的 emotion、speakerId 均为 JSON 内字段，无新列）。
- **备份/恢复表现**：群会话与群消息随 chats 页全量进备份（session_id 前缀无特殊过滤）；`reply_turns` **不进备份**（同 T15，open ④）；群回合状态/主线/自动计划均为 settings 键 → 随 settings 导出（PortableSettings 白名单待 A5 核对）；恢复后由启动 reconcile 重建自动计划（RhodesApplication.kt:83）+ `cleanupOrphanedSucceededTurns` 清孤儿（ReplyTurnRepository.kt:72-76）。

## 验收（必须可自动判定）

| # | 验收项 | 判定方式（测试/金样 diff/截图基线/性能数据） | 判定脚本或命令 |
|---|---|---|---|
| 1 | 现有单测迁移：`GroupEmotionTest`（30 @Test）+ `GroupRuntimeContextTest`（18 @Test）随域逻辑下沉 commonTest 全绿；GoldenPromptTest 群聊块保持全绿。**去重口径（reviewer 修正）**：app/src/test 与 shared/commonTest 两份 GroupRuntimeContextTest 的 18 个用例名逐一对齐（同一套件的双副本，仅行号差 2），群相关现有 @Test 计 **30 + 18 = 48**（该套件只计一份）；迁移后 commonTest 版为唯一幸存副本，app 版删除 | commonTest（JVM+Darwin 双跑） | `./gradlew --no-daemon :shared:testDebugUnitTest` 与 `./gradlew --no-daemon :shared:iosSimulatorArm64Test` |
| 2 | tagged 协议解析矩阵：发言人/旁白/心情归属（同行、行后、台词后、与情绪并存、重复取首个、旁白内丢弃、名单外丢弃、玩家丢弃、裸标识恢复） | 新 commonTest `GroupTaggedReplyParserTest`（对照 GroupTaggedReplyParser.kt:48-128 与 GVM:2537-2547） | 同上，`--tests "*.shared.domain.chat.group.GroupTaggedReplyParserTest"` |
| 3 | 群情绪规则矩阵：清洗截断（≤10 字）、归属优先级、6h 回退窗、沿用值须可溯源、快照格式化 ≤60 字/12 人、撤回后重算 | 迁移后的 `GroupEmotionTest` + 新 `GroupEmotionFallbackWindowTest`（对照 GroupEmotionRules.kt:19-28, :183-233, :250-305） | 同上 |
| 4 | 群回合状态策略：六字段解析/13 项 userTurnType/4 项 threadStatus/已收束约束/长度拒绝/保守降级规则 | 新 commonTest `GroupTurnStatePolicyTest`（对照 GVM:2596-2644） | 同上 |
| 5 | 状态机转移矩阵（群聊面）：surface=group claim/complete/release/cancel + 全员禁言 complete + 名册未解析 release(5s) + merged_into_batch 终态 + 恢复语义（type 重置、租约透传） | 新 commonTest `GroupTurnStateMachineTest`（SQLDelight in-memory 驱动，复用 T15 ReplyTurnStateMachineTest 基建） | 同上 |
| 6 | 自动互动计划状态机：ensurePlan 幂等/claim 前置条件（pending+到期）/lease 5min/isCurrentClaim/scheduleNext 轮次上限/失败 releaseClaim 保一轮/releaseClaimForRetry 60s/MIN_AUTO_LOOP_DELAY 1s 下限 | 新 commonTest `GroupAutoChatPlanTest`（fake clock，对照 GroupAutoChatScheduler.kt:30-121 纯逻辑部分；Worker/WorkManager 部分留在 Android 侧） | 同上 |
| 7 | 取消/双发/恢复矩阵：发送中取消→未送达、双发 second supersede→merged_into_batch、claim 拒绝、resumePersistedReply/resumeImageReply 语义、批次合并 2×600 | 新 commonTest `GroupChatEngineRecoveryTest`（确定性 fake AI） | 同上 |
| 8 | 提示词组装金样 diff：群聊主模板/协议/运行时上下文的组装输出与 golden 逐字节 diff | 种子 = `shared/src/commonTest/resources/golden/prompts/`（inventory.md 群聊块 18 个 raw 文件：GroupChatViewModel_L101…L2594 + PromptModuleDefaults group 块；GoldenPromptTest 已有 diff 机制）；diff 白名单 = 时间戳类占位（CURRENT_TIME/CURRENT_DATE）；金样保持基线快照（行号漂移处理见 R4） | 同上 |
| 9 | 金样轮次回放：群聊输入序列 → 消息序列 diff（类型/顺序/speaker/emotion/回合状态/display events） | 群聊**无**现成备份金样（golden/backup/minimal-1.14.27.zip 仅私聊 7 条）——实现期以该包 ROLES 页角色为成员种子合成确定性群会话 fixture（含成员/规则/禁言），引擎跑**确定性 fake AI**（沿用 T15 R5 裁决）回放，reviewer 复核；diff 白名单 = id/timestamp/租约字段 | 同上；fixture 落 `ios-port/golden/` 群聊子目录 |
| 10 | 图片/礼物 JSON 契约：占位→回填(≤800)/status=failed 三态、群礼物 recipientNames、giftPromptText 还原 | 新 commonTest `GroupMediaJsonContractTest`（对照 GVM:2281-2368, :1861-1874, :2071-2074） | 同上 |
| 11 | 性能（对齐 timeline-2000 结论）：2,000 条消息下引擎状态更新不阻塞 UI | Phase 2 与 UI 卡合并测量；本卡仅保证引擎侧 O(最新页) 读取（CHAT_PAGE_SIZE=50 :116）与历史格式化惰性（formatGroupHistoryForPrompt 仅当前轮） | 性能数据（真机） |

- **边界用例清单**：空文本/空 session、配置缺失、全员禁言、名册未同步、双发、发送中切会话、发送中重启群、发送中撤回段、claim 被拒、写库超时（用户消息未持久化→Toast 不落行 / 已持久化→未送达）、模型空回复、内容重试一次后仍空、400 上下文超限 6 次降级、深度思考开关中途切换（预算进轮固化）、杀进程后 worker 恢复（group 面）、自动轮次与手动发送竞争（等待不抢占 :1935）、自动 plan claim 被前台消耗、自动达轮次上限、图片识图失败、6h 情绪回退边界、撤回最新轮消息后快照重算。
- **金样**：提示词金样直接复用 shared/commonTest golden 机制（#8）；轮次回放金样由实现期产出（#9，确定性 fake AI，lead 裁决沿用 T15 R5）；`ios-port/golden/backup/minimal-1.14.27.zip` 仍作为私聊回归种子，不承担群聊回放职责。

## 抽取落点

- **shared 域层**：`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/chat/group/`（**子包而非并入 chat/ 根，理由见下**）
  - `GroupChatEngine.kt`：send/cancel/retry/resume/recallSegment/runScheduledAutoTurn/setAutoEnabled 的域编排；依赖注入同 T15 ChatEngine（ChatRepository + SettingsRepository + 提示词管线接口 + AIService 接口 + ID 分配器接口 + 时钟），并**委托复用 `TurnStateMachine`/`ReplyTurnLease`/`ChatStageTimeout`**（surface="group" 参数化，见共性表）；
  - `GroupReplyParser.kt`：GroupTaggedReplyParser（逐字迁移，:1-128）+ normalizeGroupResults/extractSpeakerLines/isDisplayableGroupReply/stripSpeakerPrefix/stripLeakedSegmentLabel/containsFirstPersonNarration（GVM:2099-2121, :2211-2228, :2647-2680, :2149-2150）——resolveSpeaker 回调注入保持单测友好；
  - `GroupEmotionRules.kt`（逐字迁移，已无 Android 依赖，:1-306）；
  - `GroupRuntimeContext.kt`（迁移 + **接口化**：`PromptPlaceholderRegistry.runtimeKeys("group")` 属 app 层 internal（GroupRuntimeContext.kt:15），下沉时经 `GroupPromptKeysPort` 注入）；
  - `GroupOutputBudget.kt`（逐字迁移，:1-28）；
  - `GroupTurnStatePolicy.kt`：parseGroupTurnState/validateGroupTurnState/deriveGroupTurnState/extractGroupPlotSummary（GVM:2589-2644）；
  - `GroupChatProtocols.kt`：`GroupStageBudget`（210s/270s/60s/150s/15s/2s/1s/5min/6 次/2×600/5000 常数集中）、`VisibleGroupReplyContract`（isDisplayableGroupReply 语义）、`GroupEngineEvents`（状态事件流：loading/replyStored/undelivered/systemMuted/timeout/cancelled/failed/autoPlanChanged）、`AutoChatPlanPort`（ensurePlan/claim/scheduleNext/releaseClaim/releaseClaimForRetry/isCurrentClaim/cancel/reconcile 纯状态机，Android 实现 = GroupAutoChatScheduler 纯逻辑部分）。
- **DelayedTaskLayer 与 T22 的边界（lead 裁决 2026-11-23）**：通用延迟任务层 `shared/domain/automation/DelayedTaskLayer` **由本卡实现波次同批落地**，契约以 **T22 spec §A-12** 的 `DelayedTaskChannel`（`schedule(key, dueAt, payload)` / `cancel(key)` / `pendingKeys()` / `onDue(callback)`）为冻结接口，双方不得单方面修改；本卡领域层只依赖该协议 + AutoChatPlanPort（自动回合的到期触发 = 层的 onDue 回调 → runScheduledAutoTurn），**A1 自动互动的通道适配实现（TimerDelayedTaskChannel/BGTaskDelayedTaskChannel）归 T22 胶水批**（iOS 壳波次），本卡不实现通道。
- **理由（子包 vs 并入 chat/）**：(a) 群聊复用 T15 的 `TurnStateMachine`/`ReplyTurnLease`/`ChatStageTimeout`，与 ChatEngine 同层同域，独立子包避免 domain/chat 根目录 6+ 文件平铺，且后续群聊 UI/维护卡可增量扩展；(b) 四个 viewmodel/shared 群文件已是**纯 Kotlin 无 Android 依赖**（仅 GroupRuntimeContext 一处 internal 依赖需接口化），逐字迁移回归风险最低；(c) 群聊独有面（tagged 协议/情绪/回合状态/成员权限/自动计划）与私聊面（单 JSON 协议/结算/存档）交集仅限租约层，切分为两个引擎类共享同一 TurnStateMachine 最贴合 oracle 结构（T11 已把 GVM 拆为 executeGroupTurn 内 prepareTurn/runModelTurn，抽取沿用该结构）。
- **Android 侧改动面**：GroupChatViewModel 保留 UI 状态（_groupMessages/_groupLoading/_currentGroupId/滚动目标）与 Android 专属（通知 notifyIfBackground、WorkManager 调度、ContextMaintenanceScheduler、RhodesAppVisibility、`getUserProfile`/模板 lambda 桥接、DebugLogger 全链路）；域逻辑全部下沉 GroupChatEngine；GroupAutoChatWorker 改为调用域层 `runScheduledAutoTurn`（经 AutoChatPlanPort 校验）。
- **iOS SwiftUI 接口草案**（经 IosAppGraph 门面）：
  ```swift
  protocol GroupChatEngineFacade {  // 仅示意，实现期以 shared framework 导出为准
      func send(text: String, groupId: String, mode: Mode) async -> SendOutcome      // 落库立即返回 userMessageId
      func cancel(groupId: String)
      func retry(groupId: String, messageId: Int64)
      func recallSegment(groupId: String, messageId: Int64, segmentIndex: Int32)     // 非法参数退化整条删除
      func resumeInterrupted(groupId: String, messageId: Int64)                      // 前台恢复入口（启动 reconcile 后逐 turn 调用）
      func runScheduledAutoTurn(groupId: String, token: String, expectedGeneration: Int64?, planRevision: Int64) async -> Bool
      func setAutoEnabled(groupId: String, enabled: Bool)
      func autoStatus(groupId: String) -> GroupAutoStatus                            // enabled/state/倒计时
      func observe(groupId: String) -> AsyncStream<GroupEngineEvent>                 // loading/undelivered/replyStored/systemMuted/timeout...
  }
  ```
  后台重入点（R1）：iOS 无 WorkManager——手动回复恢复 = 启动 reconcile（retryableTurnIds）+ 前台补算（沿用 T15 R1 裁决）；自动互动到期触发经通用 DelayedTaskLayer（契约 = T22 spec §A-12 冻结接口；本卡实现波次同批落地该层，BGTask/Timer 通道适配归 T22 胶水批）。

## 风险与开放问题

- **R1 群自动互动 iOS 语义与 DelayedTaskLayer（lead 已裁决 2026-11-23）**：Android 双触发（前台内存循环 startAutoGroupChat + WorkManager GroupAutoChatWorker）共享同一 durable plan（token/revision claim 保证单飞）；iOS 无 WorkManager。**裁决**：通用 DelayedTaskLayer（`shared/domain/automation/`）由**本卡实现波次同批落地**，契约以 **T22 spec §A-12** `DelayedTaskChannel` 冻结接口为准（A1 与 A7 均不得单方面改协议）；本卡在接口设计中标注与 T22 的边界——A1 自动互动的通道适配实现（Timer/BGTask）归 T22 胶水批（iOS 壳波次）；手动回复恢复仍为启动 reconcile + 前台补算（不经 DelayedTaskLayer，与 T15 R1 一致）。
- **R2 群聊无重新生成/续写/存档/擦除**（差异白名单）：GVM 无 regenerate/continue/archive 入口（对照 T15 契约 12/13/16），且群聊无需亲密度结算（契约 14）。**理由**：Android 1.14.27 本身未提供，iOS 不发明；UI 侧群聊长按菜单仅重试/撤回/复制/分享（checklist §1.1 行 46）。
- **R3 轮次回放金样**：golden/backup 仅私聊 7 条消息；群聊回放 fixture 需实现期合成（以 minimal ROLES 页角色为成员、确定性 fake AI，沿用 T15 R5 裁决），reviewer 复核，落 ios-port/golden/ 群聊子目录。
- **R4 提示词金样行号漂移**（reviewer 修正已落实）：inventory.md 群聊块引用的行号（如 `GroupChatViewModel.kt:1330-1344`）是 T11 重构前快照；T11 拆分后实际行号已平移（如 remainingTurnBudget 块现为 :986-1039）。**金样保持基线快照**——`ios-port/golden/prompts/raw/`（114 件 + `.raw-manifest.sha256` 哈希门禁）不动，`sync_golden.sh` 仅做「金样 → 测试 resources」单向同步，实现期不得以「校准」为由改写/覆盖基线金样；如确需校准行号，以**从 `v1.14.27-android-baseline` tag 重抽**的方式产出新基线件、单独走 review 流程合并，diff 白名单按现有 allowlist.json 口径复核。
- **R5 GroupRuntimeContext 下沉依赖**：`PromptPlaceholderRegistry.runtimeKeys("group")` 为 app 层 internal（GroupRuntimeContext.kt:15）；下沉需接口化（GroupPromptKeysPort），与 T14 提示词卡接口冻结后交叉核对。
- **R6 仅定义未调用的遗留助手（待裁决：迁移 or 删除）**：`isLowSignalKnowledgeQuery`（:2191）、`saveGroupAnchorToVector`（:2420）、`untrustedGroupRepairInput`（:2528）、`isStrictGroupJson`/`requiresFormatRepair`（:2682-2691）在本文件内无调用点（grep 确认）。本卡不迁移实现，仅记录为 oracle 事实；建议实现期按「YAGNI 不迁移、金样 diff 不受影响」处理，请 reviewer 确认。
- **R7 A2 群资料卡边界**：saveGroup（SessionViewModel.kt:79-121）与群编辑（GroupEditScreen.kt:91-155）归 A2 卡；本卡只定义「读取/校验」契约（members/mutedMembers/rules 消费语义），两卡合流后回归依赖矩阵。
- **R8 测试口径**：沿用 T15 裁决（实测 376 为准）；本卡迁移 48 个群相关 @Test（30+18）+ 新增约 6 套，不改变既有口径，仅追加清单。
- **R9 reply_turns 不进备份**（沿用 T15 open ④）：群聊自动计划（settings 键）与 turn 租约的备份语义需在 A5 卡一并确认；恢复后启动 reconcile（RhodesApplication.kt:83）已覆盖「计划丢失后重建」，turn 租约丢失由 orphan cleanup 兜底。

## 评审结论

- reviewer：**PASS-WITH-FIXES**（2026-11-23，0 P1）。修正清单：①【P2】R4 改为「金样保持基线快照；行号漂移以从 v1.14.27-android-baseline tag 重抽的方式校准，不覆盖基线金样」（并同步修正验收 #8 白名单措辞）；②【P3】GroupAutoChatScheduler 实为 REPLACE 无退避——契约 20 按源码实测改写（ExistingWorkPolicy.REPLACE、未配置 setBackoffCriteria；重试 = plan 层 pending(dueAt=+60s) 重排 + REPLACE 新 worker）；③【P3】GroupRuntimeContextTest 双份（app 18 例 + shared 18 例）去重口径写清：用例名逐一对齐为同一套件双副本，群相关现有 @Test 计 30+18=48，迁移后 commonTest 版为唯一幸存副本。以上均已落实。
- lead：**批准**（2026-11-23）。裁决：通用 **DelayedTaskLayer 由本卡实现波次同批落地**（契约以 T22 spec §A-12 `DelayedTaskChannel` 冻结接口为准）；本卡在接口设计中标注与 T22 的边界——A1 自动互动的触发通道适配实现归 T22 胶水批。
