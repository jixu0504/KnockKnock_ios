# handoff：spec-chat-engine（私聊轮次引擎规格卡，task-15 / T15）

> 代理：chat-engine-spec-card ｜ 任务：task-15 ｜ 状态：**完成（reviewer PASS-WITH-FIXES 2026-11-23，修正已落实；lead 已批准 2026-11-23）**
> 交付：`ios-port/specs/private-chat-engine-spec.md`（按模板 v1，20 条行为契约，全部带 file:line）、本文件。

## 1. 方法

1. 认领 task-15（revision 3 → claim → 4），写域仅 ios-port/{specs,handoffs}，仓库只读。
2. 静态只读勘察 oracle：
   - `ChatViewModel.kt` 3829 行全结构（顶层声明 grep）+ 精读 sendMessage 全管线（:1298-1794）、cancel（:1258）、resumePersistedReply（:1796）、sendImageMessage（:1819-2008）、recall/segment-recall/retry/regenerate/continue（:2083-2482）、resumeImageReply（:2742）、select/restart/erase/archive/setMode（:448-1048）、generateCompletePrivateReply（:1147-1203）。
   - `ChatStageTimeout.kt`（62 行全读）、`ReplyTurnRepository.kt`（115 行全读）、`ReplyTurns.sq`、`ReplyTurn.kt`、`MessageRepository.kt:285-305`（getNextMessageId 自愈）、`AIService.kt:424/668`、`MessageParser.kt:137-157`、`ManualReplyScheduler/Worker`、`ChatInteractionPolicyTest` 等 8 套聊天相关测试、golden/db/schema 五表、audit/fields.md a2、timeline-2000-spec。
3. 产出规格卡 + 本 handoff。

## 2. 关键发现（对后续卡重要的）

1. **240s 总超时构成**：`PRIVATE_REPLY_TIMEOUT_MS=180_000`（:107）+ 深度思考加成 `THINKING_BUDGET_BONUS_MS=60_000`（:124，仅 DeepSeek 且开关开启）→ 最大 240_000ms；租约 `leaseUntil` 与整轮预算同源（:1344, :1447）。
2. **Android 私聊实为整包落库**，非逐 delta 流式：`AIService.streamChat` 明确注释「保留用于兼容」（:666-668）；AI 消息在整包解析成功后一条事务写入（:1616-1624）。T9 验证的是传输层取消/超时机制，不是打字机落库。
3. **双保险恢复架构**：claim 之前先调度 WorkManager（:1442-1445）防「claim 被拒后无人补答」；`retryableTurnIds`（ReplyTurnRepository.kt:111-114）是共享层捞取 pending/failed/租约过期 turn 的原语——iOS 前台恢复可直接复用。
4. **状态机是双层的**：内存层（isLoading/chatAiJobs/generation/pendingIds，无正式枚举）与持久化租约层（reply_turns.status：pending/running/succeeded/failed + SQL 终止态 cancelled；`ReplyTurnStatus` 常量 ReplyTurn.kt:23-28，**无 CANCELLED 常量**——cancelled 由 ReplyTurns.sq:94/:99 落成）。规格卡按此双层描述，未发明不存在的枚举。
5. **消息 ID 自愈**（T16 卡输入）：`getNextMessageId` = idMutex + max(DB MAX+1, settings.max_used_message_id+1, 内存 next+1)（MessageRepository.kt:296-305）；持久化高水位防撤回/读档/擦除后 ID 回卷导致 reply_turn 孤儿阻塞。
6. **reply_turns 不进备份**（audit/fields.md a2 的 BackupPayload 无此分类）；恢复/读档依赖 `cleanupOrphanedSucceededTurns`（只删 succeeded 孤儿，:67-76）。
7. **结算门槛**：唯一硬契约 = 至少一条非旁白非空台词（:1122-1127）；不满足则不结算亲密/龙门币且用户消息批量 send_failed（:1656-1667）。
8. **三模式引擎侧硬分支**：online 只留 dialogue 段（:1216-1220）；offline/director 首段旁白+末段台词协议检查（:1135-1143）；turnStatus「已收束/已转题」清 unresolved/pending（:1600-1607）。
9. **测试口径**：app/src/test + shared/src 实测 **376** 个 @Test（+3 androidTest）；聊天域相关 8 套 40 个 @Test 已列入迁移清单。任务口径「324」不符 → **lead 已裁决以实测 376 为准**。
10. **图片管线 JSON 契约**：`{imageUri, caption, visionSummary}` 占位 → 识图回填 ≤800 字 → 失败加 `status:"failed"`（:1859-1942）；UI 端 `status=="failed"` → isSendFailed（MessageParser.kt:137-157）。

## 3. 交付文件

| 路径 | 内容 |
|---|---|
| `ios-port/specs/private-chat-engine-spec.md` | 模块信息/输入输出边界（超时参数全集表）/20 条行为契约（逐条 file:line）/数据与迁移/验收（迁移清单+新增用例+金样回放+命令）/抽取落点（shared/domain/chat + Android 改动面 + iOS IosAppGraph 门面草案）/风险 |

## 4. 评审结果与裁决（2026-11-23）

reviewer：**PASS-WITH-FIXES**，3 项修正已落实：
1. 【P2】依赖表 buildApiMessages（ChatViewModel.kt:3034）改归 **T15 自持**（提示词卡 T14 只管模板/提示文本内容：PromptTemplates、模式过渡提示文本 :1035-1043、导演模式块 :3662）。
2. 【P2】验收 #6 输入序列来源改为 `ios-port/golden/backup/minimal-1.14.27.zip` 的 **7 条消息**（id=1..7，text/ai_json/image 混合，含图片 JSON 与内嵌图引用）作种子，替代原 golden/sessions JSON 方案。
3. 【P2】状态机图：ReplyTurnStatus 行号更正为 **ReplyTurn.kt:23-28**（仅 PENDING/RUNNING/SUCCEEDED/FAILED 四项）；`cancelled` **无 Kotlin 常量**——为 SQL 终止态（ReplyTurns.sq:94 cancelReplyTurn、:99 cancelReplyTurnBySource），已按源码实际枚举修正。

lead 裁决（已回填规格卡评审结论节）：
- 后台恢复 = **启动 reconcile + 前台补算**（领域层不依赖 BGTask）；selectRetryableReplyTurns（ReplyTurns.sq:106-112）即 reconcile 原语。
- 打字机效果属 **UI 层**，领域层不承诺 delta 流。
- 测试口径以**实测 376** 为准。
- 金样回放用**确定性 fake AI**。
- 群聊状态机**单独立卡（下一批）**。

## 5. 遗留 open questions（仍未裁决）

1. **reply_turns 不进备份的确认**：audit/fields.md a2 的 BackupPayload 无此分类；恢复/读档依赖 `cleanupOrphanedSucceededTurns`（ReplyTurnRepository.kt:67-76）。若未来要备份租约态需改 A5 卡。
2. **依赖卡接口冻结（R4）**：T14（提示词模板）/T16（ID 分配器）/记忆管线卡尚未产出，本卡接口草案以上游三卡为准；建议三卡评审时交叉核对本卡依赖矩阵。

## 6. 纪律记录

- 全程 read/grep/glob，未修改 RhodesLink 仓库任何文件；仅写入 ios-port/specs/private-chat-engine-spec.md 与本文件（写域合规）。
- 未运行构建/测试（纯文档任务）；无残留后台作业。
