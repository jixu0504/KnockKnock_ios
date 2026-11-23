# 规格卡：A5 全类目字段级重放（T31）

> 模板：`ios-port/specs/规格卡模板-v1.md`。黄金法则：Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`）是行为 oracle；每条行为契约必须指向 Android 源码 file:line。
> 前置：已批准规格卡 `backup-restore-minimal-spec.md`（T10，最小闭环 roles/chats）与 `chat-import-impl-spec.md`（T30，分类选择/自检/分页覆盖）；T30 handoff §5 遗留 1 明列本卡范围。
> 金样：`ios-port/golden/backup-full/`（生成器 `make_full_backups.py` 可复跑 + 自校验；8 个确定性样本包）。
> 写域纪律：实现只**新增**文件（`shared/.../backup/wire/` 新包 + 测试 + `ios-port/{golden/backup-full,specs,handoffs}`）；T10/T30 现有文件（`BackupFileReader`/`BackupWireModels`/`BackupRestoreExecutor`/`BackupSelectiveRestoreExecutor`/`BackupRestoreCoordinator(Ex)`/`BackupRestoreSelfCheck`）零改动。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | A5 备份 v2 **全类目字段级重放**（T31）：完整 wire 模型 + 全量恢复执行器 + 全类目金样 + 恢复后自检 |
| 所属阶段 | Phase 2（T31；T30 裁决单列项） |
| 范围映射 | A5（修订）：「Android 1.14.27 完整备份可在 iOS 导入并正确恢复（100% 金样通过）；恢复后自检」（计划书 line 66-68、line 179）；本卡 = T30 未覆盖的 memories/social/knowledgeBases/extras/settings 全字段重放 |
| 对应 Android 源码 | 导出/快照：`app/.../data/ExportPayload.kt`、`data/backup/BackupSnapshotBuilder.kt`、`BackupPagePlanner.kt`、`BackupFileWriter.kt`、`BackupModels.kt`、`PortableSettings.kt`；恢复：`data/backup/RestoreDatabaseExecutor.kt`、`BackupRestoreCoordinator.kt`、`BackupContentFilter.kt`、`BackupRestorePayloadRewriter.kt`；读取校验：`BackupFileReader.kt` |
| 依赖模块 | T10 `shared/.../backup/{BackupZip,BackupCrypto,BackupFileReader,BackupPagePlanner,BackupMediaSink,BackupPortableSettings}`；T30 `{BackupRestorePayloadRewriter,BackupContentFilter,BackupRestoreSelfCheck,BackupRestoreRepository}`；shared 模型 `model/{Memory,MemoryAnchor,MemoryItem,Moment,MomentComment,MomentLike,Diary,KnowledgeBase,ChatArchive,SharedExperience,...}`；SQLDelight 24 个 `.sq`/28 表（`ios-port/golden/db/schema`，与 shared 逐字节一致，已 diff 验证） |

## 0. 范围与判据

**范围内**（对照 golden/db/schema 28 表，`ios-port/golden/db/migrations.md`「.sq 表清单」）：

| 类别 | 恢复表（22） | 重置表（6，不恢复只清空） |
|---|---|---|
| roles | operators、relationships | — |
| chats | chat_sessions、chat_messages、chat_display_events、chat_archives、chat_history_segments | reply_turns |
| memories | memories、memory_anchors、memory_items | memory_links、memory_source_queue、memory_batches、vector_memories |
| social | moments、moment_comments、moment_likes、diaries、shared_experiences、shared_experience_participants | — |
| knowledgeBases | knowledge_bases、knowledge_base_chunks、operator_knowledge_bases | — |
| extras | gift_records、dispatch_records、mahjong_saves | daily_deliveries |
| settings | Preferences（`PortableSettings` 白名单，非 SQLite） | — |

**范围外**（明确不做，防止范围蠕变）：Android 侧任何改动；备份容器格式变更（v2 容器、分页规则、`mediaId` 路径派生语义全部冻结）；运行期重建（`DailyContentScheduler`/`GroupAutoChatScheduler`/向量重建索引 → Phase 7）；`before_restore_*.rbackup` 文件落盘策略（T10 裁决 b 保留项）；UI 接线（Phase 3/7）；v1 聊天导出（T30 已冻结）。

**任务词「identities/relations/impressions」的映射**（v2 端口径，不作新表）：
- **identities** = `operators`（22 字段人设/身份，`Operator.kt:6`）+ 用户身份设置（`user_name/user_gender/user_avatar_uri/user_signature/appearance_skin`，`PortableSettings.kt:8-14`）；
- **relations** = `relationships`（7 字段，`RelationshipExport`，`ExportPayload.kt:71`）+ `shared_experiences` 参与者（`SharedExperienceParticipant`）；
- **impressions** = 三层记忆 `memories`（`MemoryType.SHORT_TERM/DAILY/LONG_TERM`）+ `memory_anchors`（`AnchorType`）+ `memory_items`（`MemoryLevel.L1/L2/L3`，`MemoryV2.kt:6`）。

**与 T10/T30 的关系**：
1. T10 `BackupFileReader` 仍是**唯一校验源**（结构/哈希/分页/引用/媒体一致性，`BackupFileReader.kt:32-244`）；本卡新增 `FullBackupReader` 只做「校验通过后把分页二次解码为完整字段模型」，不复制校验逻辑（契约 C1）。
2. T10 `BackupRestoreExecutor`/T30 `BackupSelectiveRestoreExecutor` 仍服务 roles/chats 最小闭环与既有测试，**不改**；全类目走新 `BackupFullRestoreExecutor`。
3. T30 的媒体重写/孤儿过滤/自检在完整模型上各需一份全类目版（`FullRewriter`/`FullFilter`/`FullSelfCheck`，新文件）；语义逐条镜像 T10/T30 与 Android（契约 C9/C10/C12）。
4. T30 已裁决的「插入也按分类选择门禁」在本卡全类目沿用（比 Android 更保守；平台差异白名单 P1）。

## 输入 / 输出 / 边界

- 输入：
  - 备份包 `ByteArray`（iOS document picker 的 `Data`，与 T10 同形）；
  - `BackupRestoreOptions(skipIssueCodes, contentSelection)`（`BackupWireModels.kt:207-210`；`normalized()` 语义 `:183-199`）；
  - 媒体 sink（生产 `BackupMediaSinkIos`；测试 `InMemoryMediaSink`）；
  - 金样：`ios-port/golden/backup-full/*.zip`（8 包，§金样设计）。
- 输出：
  - `BackupFullRestoreResult(success, stage, manifest, reason, selfCheck)`；阶段枚举沿用 `BackupRestoreStage`（`BackupRestoreCoordinator.kt:16-25`，T10 文件只读引用）。
  - `BackupRestoreSelfCheckResult`（复用 T30 类型，`BackupRestoreSelfCheck.kt:15-19`）：`passed/checked/mismatches`，`checked` 覆盖全类目。
  - 副作用：22 表全量替换 + 6 表清空；媒体文件落 staging；Preferences 快照/清除/回填；进度回调。
- 边界（逐项）：
  - **上限**：沿用 T10 读取上限（条目 1000/单条目 100MB/总 500MB，`BackupFileReader.kt:10-13`、分页 ≤16MB `:87`）；单页记录数 ≤500（`BackupPagePlanner.kt:PAGE_SIZE=500`，oracle android `:9`）。全类目恢复**不设**额外条数上限（与 Android 一致）。
  - **空数据/空类目**：所有列表为空时照常执行（删除选中类目、写 0 行）；`content.settings` 为 null 时跳过设置 apply（与 T10 `:104-107` 同口径）。
  - **坏包**：校验失败发生在 DB 事务前 → DB/媒体/设置零残留（C13）。
  - **事务内失败**：单事务回滚 + 媒体目录清理 + 设置快照回放（三层回滚，C12）。
  - **取消**：`CancellationException` 直接上抛，清理媒体、不返回 failure（与 T10 `:121-124` 同口径）。
  - **并发/重入**：恢复由宿主串行（`restoreMutex`/actor，与 T10 `:58` 同语义）；actor/锁不在本卡实现，本卡执行器本身声明**非线程安全**，由 coordinator 串行调用。
  - **磁盘满/IO**：媒体 staging 失败 → 清理部分解出目录并失败（T10 P2 补强沿用）；DB 失败 → 三层回滚。

## 类目—表—字段矩阵

> 「wire 模型」= 恢复侧完整字段模型来源。凡 Android `ExportPayload` 直接复用 shared `@Serializable` 模型的类目，iOS 也直接复用同一模型（零 schema 漂移）；Android 用专用 Export 类的（knowledgeBases/relationships）在 `backup/wire/` 新建完整 wire 类。现有 T30 `BackupWireModels.kt:120-174` 的占位类（`BackupDiaryWire` 空类等）**不再使用**，由新完整模型替代。

### 1) roles（operators / relationships）

| 表（schema 行） | wire 模型（字段序 = JSON 序） | 字段数 |
|---|---|---|
| `operators`（`Operators.sq:1`） | `Operator`（shared `model/Operator.kt:6`；Android 同形导出 `OperatorExport.kt:46-69`）：id,name,title,description,gender,avatarUri,location,activity,emotion,intimacy,privatePrompt,groupPrompt,memoryInjection,userRelation,lmb,attack,defense,meldPref,activityLevel,voiceName,voiceSpeed,voicePitch | 22 |
| `relationships`（`Relationships.sq:1`） | 新 `BackupFullRelationshipWire`（镜像 `ExportPayload.kt:71-84`）：operatorId,relatedOperatorId,relatedOperatorName,type,intimacy,isPreset,note（`type` 为 String，非法值降级 `RelationshipType` 首值，oracle `:75-77`） | 7 |

- 恢复语义：operators/relationships **id 原样保留**（`RestoreDatabaseExecutor.kt:77-78,90-91`）；relationships.id 不进备份（自增）。roles-only（`roles=true,chats=false`）走 upsert 不删现有角色（`:69-74` 注释语义，T30 已实现同口径）。
- 自检指标：`operators`/`relationships` 计数 = payload（roles&&chats 替换语义时严格相等）；`operators` 22 字段逐字段相等；`relationships` 7 字段相等（type 名落库）。
- 金样：`full-all-v2.zip`（2 角色 + 2 关系）、`full-extras/media/knowledge` 包内附 1-2 角色（引用校验需要）、`full-empty-v2.zip`（0）。

### 2) chats（sessions / messages / displayEvents / archives / historySegments / reply_turns 重置）

| 表（schema 行） | wire 模型 | 字段数 |
|---|---|---|
| `chat_sessions`（`ChatSessions.sq:1`） | `ChatSession`（`model/ChatSession.kt:6`；`SessionExport` `ExportPayload.kt:86-101`） | 12 |
| `chat_messages`（`ChatMessages.sq:1`） | `ChatMessage`（`model/ChatMessage.kt:6`；`MessageExport` `ExportPayload.kt:103-114`） | 15 |
| `chat_display_events`（`ChatDisplayEvents.sq:1`） | `BackupChatDisplayEvent`（shared `data/MessageRepository.kt:34-39`）：messageId,segmentIndex,sessionId,revealOrder | 4 |
| `chat_archives`（`ChatArchives.sq:1`） | `ChatArchive`（`model/ChatArchive.kt:6`） | 12 |
| `chat_history_segments`（`ChatArchives.sq:18`） | `ChatHistorySegment`（`model/ChatArchive.kt:28`） | 6 |
| `reply_turns`（`ReplyTurns.sq:1`） | 不恢复（运行队列）；chats 选中时 `deleteAllReplyTurns`（`RestoreDatabaseExecutor.kt:48`） | — |

- 恢复语义：sessions/messages/displayEvents id 原样保留；displayEvents 用 `insertDisplayEventIfAbsent`（主键 `(messageId,segmentIndex)` 天然幂等，`ChatDisplayEvents.sq:29`）；archives/history id 原样保留（String 主键，`ChatArchives.sq:41,66`）；reply_turns 清空不重建。
- 自检指标：sessions/messages/displayEvents/archives/historySegments 计数；messages 首尾 `(id,timestamp,type,isMe,content)`；displayEvents 三元组唯一；archives `sessionId/operatorId` 引用完整。
- 金样：`full-all-v2.zip`（2 私聊 + 1 群聊、12 消息含 text/ai_json/image、4 displayEvents、2 archive + 1 history）、`full-media-v2.zip`（存档 messagesJson 内嵌媒体 URI）、`full-extras-v2.zip`（displayEvents/archives/history）。

### 3) memories（memories / anchors / memoryItems / 向量与运行队列表重置）

| 表（schema 行） | wire 模型 | 字段数 |
|---|---|---|
| `memories`（`Memories.sq:1`） | `Memory`（`model/Memory.kt:6`）：id,sessionId,operatorId,type(MemoryType),content,keywords,preferences,taboos,createdAt,expiresAt | 10 |
| `memory_anchors`（`MemoryAnchors.sq:1`） | `MemoryAnchor`（`model/Memory.kt:20`）：id,sessionId,operatorId,type(AnchorType),content,isPrivate,createdAt,expiresAt,source,sourceName,sourceActor,sourceTarget,importance,knownFrom | 14 |
| `memory_items`（`MemoryItems.sq:1`） | `MemoryItem`（`model/MemoryV2.kt:6`）：id,ownerType,ownerId,memoryLevel(MemoryLevel),memoryType,sourceKind(MemorySourceKind),sourceRefId,sessionId,content,nickname,importance,privacy,unmetNeed,location,emotionValence,eventTime,createdAt,updatedAt,expiresAt,status,scheduledTime,action,careType,topicKey,sourceActor,sourceTarget,lastUsedAt,usedCount,confidence,rawJson,vectorId | 30 |
| `memory_links`/`memory_source_queue`/`memory_batches`/`vector_memories` | 不恢复（运行期派生：links=promotion 审计、queue=提取队列、batches=批处理、vectors=索引）；memories 选中时全部清空（`RestoreDatabaseExecutor.kt:39-42`） | — |

- 恢复语义（`RestoreDatabaseExecutor.kt:106-113,165-174`）：
  - memories/anchors **id 不保留**（自增；`insertMemory` `Memories.sq:43`、`insertAnchor` `MemoryAnchors.sq:33` 无 id 参数）；
  - memoryItems 的 `status`：`L1` 保留 payload.status，`L2/L3` 强制 `"archived"`（`:166`）；`vectorId` 强制 `""`（`:174` 最后参数，清空向量引用）；
  - `sourceRefId` 重映射：`sourceKind=="MOMENT"` → 新 moment id 映射；`"MOMENT_COMMENT"` → 新 comment id 映射；映射缺失/非数字保留原值（`:167-171`）；
  - 孤儿降级：`ORPHAN_MEMORY`/`ORPHAN_ANCHOR` 由读取器产出（`BackupFileReader.kt:182-189`），`skipIssueCodes` 命中时过滤（`BackupContentFilter.kt:14-25` 同口径）。
- 自检指标：memories/anchors/memoryItems 计数；memoryItems `vectorId` 全空、L2/L3 status 全 `archived`、L1 status 保留；memoryItems MOMENT/MOMENT_COMMENT 的 `sourceRefId` 全部指向本次新插入的 moments/comments id；测试种子向量（`insertVectorMemory`）恢复后按 `(ownerType,ownerId)` 查询为空。
- 金样：`full-all-v2.zip`（3 记忆 = 3 层、3 锚点、3 memoryItems = L1/L2/L3 + MOMENT/MOMENT_COMMENT 源）、`full-memories-v2.zip`（6 锚点类型全覆盖 + 孤儿记忆/锚点 → issue/skip 路径）。

### 4) social（moments / comments / likes / diaries / sharedExperiences / participants）

| 表（schema 行） | wire 模型 | 字段数 |
|---|---|---|
| `moments`（`Moments.sq:1`） | `Moment`（`model/Moment.kt:6`）：id,operatorId,operatorName,content,isUserPost,mentionedOperatorIds,likeCount,commentCount,createdAt | 9 |
| `moment_comments`（`MomentComments.sq:1`） | `MomentComment`（`model/Moment.kt:19`）：id,momentId,operatorId,operatorName,content,parentCommentId,replyToName,createdAt,isRead | 9 |
| `moment_likes`（`MomentLikes.sq:1`） | `MomentLike`（`model/Moment.kt:32`）：id,momentId,operatorId,operatorName,createdAt | 5 |
| `diaries`（`Diaries.sq:1`） | `Diary`（`model/Diary.kt:6`）：id,operatorId,operatorName,content,date,version,createdAt | 7 |
| `shared_experiences`（`SharedExperiences.sq:1`） | `SharedExperience`（`model/MemoryV2.kt:86`）：id,sourceKind,sourceRefId,groupId,content,importance,status,createdAt,expiresAt | 9 |
| `shared_experience_participants`（`SharedExperiences.sq:13`） | `SharedExperienceParticipant`（shared `data/SharedExperienceRepository.kt:48`）：experienceId,operatorId,role | 3 |

- 恢复语义（`RestoreDatabaseExecutor.kt:115-142,153-161`）：
  - **id 全部不保留**（moments/comments/likes/diaries/experiences 自增），依赖映射：`momentIds[oldMomentId] = last_insert_rowid()`（`:119`）、`commentIds[oldCommentId] = ...`（`:130`）、`experienceIds[oldExperienceId] = getLastSharedExperienceId()`（`:156`）；
  - 评论按**父级拓扑**分批插入：`parentCommentId==0 || parentCommentId in commentIds` 的 ready 集合，循环直到空，否则抛 `"评论父级引用无法恢复"`（`:121-132`）；parentCommentId 写入新父 id；
  - likes 用 `momentIds[like.momentId]` 映射，映射缺失静默跳过（`:134-136`）；
  - **计数回填**：`backfillLikeCounts()` / `backfillCommentCounts()`（`:137-138`，`Moments.sq:66-72`）——payload 中的 likeCount/commentCount **仅作插入初值**，最终以实际行数为准；
  - participants 仅当 experience 映射存在时插入（`:158-161`）；孤儿 participants 由 `ORPHAN_SHARED_EXPERIENCE_PARTICIPANT` 降级（`BackupFileReader.kt:200-203`）。
- 自检指标：六表计数；每个 moment 的 `likeCount == COUNT(moment_likes)`、`commentCount == COUNT(moment_comments)`（backfill 后）；评论 `parentCommentId` 链可解析；participants 的 experienceId 全部存在。
- 金样：`full-all-v2.zip`（2 动态 = user post + 角色 post、3 评论 = 1 级 1 嵌套、2 likes、2 日记、2 共同经历 + 3 参与者）、`full-social-v2.zip`（含孤立评论/点赞 → issue/skip 路径）。

### 5) knowledgeBases（knowledge_bases / chunks / operator_knowledge_bases）

| 表（schema 行） | wire 模型 | 字段数 |
|---|---|---|
| `knowledge_bases`（`KnowledgeBases.sq:1`） | 新 `BackupFullKnowledgeBaseWire`（镜像 `ExportPayload.kt:41`，**不含** `indexStatus/indexedEmbeddingSignature`）：id,name,rawContent,sourceFileName,sourceFormat,sourceType,chunkingMode,createdAt,updatedAt | 9 |
| `knowledge_base_chunks`（`KnowledgeBases.sq:15`） | 新 `BackupFullKnowledgeBaseChunkWire`（镜像 `ExportPayload.kt:42`，**不含** `indexedAt/indexError`）：id,knowledgeBaseId,ordinal,sourceHeading,content,userKeywords,enabled,createdAt,updatedAt | 10 |
| `operator_knowledge_bases`（`KnowledgeBases.sq:33`） | 新 `BackupFullKnowledgeBaseAssignmentWire`（`ExportPayload.kt:43`）：operatorId,knowledgeBaseId,enabled,sortOrder | 4 |

- 恢复语义（`RestoreDatabaseExecutor.kt:80-89`）：三表 id **原样保留**；写入时强制 `indexStatus="pending"`、`indexedEmbeddingSignature=""`（`:82`）、chunk `indexedAt=0`、`indexError=""`（`:85`）——索引是派生状态，恢复后必须重建（重建动作归运行期/RK，本卡只保证状态为 pending）。引用完整性由读取器强制（chunks→kb、assignments→operator&kb，`BackupFileReader.kt:158-168`）。
- 自检指标：三表计数；`knowledge_bases` 9 字段相等 + `indexStatus=="pending"` + signature 为空；chunks 10 字段 + `indexedAt==0`；assignments 4 字段；`chunk.ordinal` 在 book 内唯一。
- 金样：`full-all-v2.zip`（2 书 + 3 切块 + 2 关联）、`full-knowledge-v2.zip`（1 书 2 块 1 关联 + 2 角色以满足关联引用）。

### 6) extras（gift_records / dispatch_records / mahjong_saves / daily_deliveries 重置）

| 表（schema 行） | wire 模型 | 字段数 |
|---|---|---|
| `gift_records`（`GiftRecords.sq:1`） | `GiftRecord`（`model/GiftRecord.kt:6`）：id,operatorId,imageUri,giftName,senderName,createdAt | 6 |
| `dispatch_records`（`DispatchRecords.sq:1`） | `DispatchRecord`（`model/DispatchRecord.kt:6`）：id,taskType,durationHours,budget,netProfit,operatorIds,logChain,status,startTime,endTime,totalSegments,segmentInterval,items | 12 |
| `mahjong_saves`（`MahjongSaves.sq:1`） | `MahjongSave`（`model/MahjongSave.kt:6`）：id,saveJson,ruleType,savedAt | 4 |
| `daily_deliveries`（`DailyDeliveries.sq:1`） | 不恢复（运行队列）；extras 选中时 `deleteAllDailyDeliveries`（`RestoreDatabaseExecutor.kt:33`） | — |

- 恢复语义（`:143-152`）：gifts/dispatches/mahjong id 原样保留；dispatch `status` 归一：`"active"|"generating"` → `"paused"`，其余保留（`:146-149`）——恢复后不自动续跑；`imageUri` 参与媒体重写（C9）。archive/history 已在 chats 类目。
- 自检指标：三表计数；gifts/dispatches 逐字段相等 + dispatch status 归一规则；`mahjong_saves` 存在性 + 4 字段；daily_deliveries 清空。
- 金样：`full-all-v2.zip`（2 礼物含图、2 派遣 = active + finished、1 麻将存档）、`full-extras-v2.zip`。

### 7) settings（Preferences 全量，非敏感）

- wire 模型：`settings: Map<String,String>?`（`ExportPayload.kt:38`），值前缀编码 `s:/b:/i:/l:`（`PortableSettings.kt:51-77,79-102`）。
- 白名单三组 key：string `:8-14`（15 个，含 `user_name/user_gender/user_signature/user_avatar_uri/appearance_skin/...`）、boolean `:15-29`（40 个）、int `:30-49`（73 个）；另有 `hidden_ids`（`:55`）与角色/会话语境派生 key：`operator_prompt_slot_<id>_{private,group}[_{1..3}]`、`msg_<id>`、`dyn_<id>`、`voice_volume_<id>`、`chat_tts_<id>`、`bg_<id>`、`diary_read_at_<id>`、`last_mode_<operatorId>`、`gbg_<sessionId>`（`:56-76`）。iOS 实现 `BackupPortableSettings` 已逐字对齐 T10（`BackupPortableSettings.kt:10-118`）。
- 恢复语义（oracle `BackupRestoreCoordinator.kt:96-111`）：settings 选中时先快照 + `clearForRestore`，再 `apply` 备份值；失败回放快照。**密钥不在白名单**（fields.md §a3）：`api_key/vector_api_key/vision_api_key/asr_api_key/tts_api_key/newspaper_deepseek_key/asr|tts_provider_configurations/group_auto_plan_token_*` 永不进备份、恢复不触碰。
- 自检指标：恢复后 `snapshot` 与备份 settings 逐 key 相等（`settings` 全量键集合内）；`hidden_ids` 相等；每个已恢复角色的角色局部 key 存在；密钥类 key 值未被改动（测试预置哨兵值断言）。
- 金样：`full-all-v2.zip`（全三组 + hidden_ids + 2 角色角色局部 key + 群 `gbg_` + `last_mode_`）、`full-empty-v2.zip`（`settings={}`）、其余包最小 settings（多为 `null` 或少量键）。

## 行为契约（逐条，每条带 Android oracle 位置）

### C1 完整 wire 模型 + FullBackupReader（校验单源、二次解码）

1. `FullBackupReader.read(bytes)` = 先 `BackupFileReader.read(bytes)`（T10，全校验/issue/媒体，`BackupFileReader.kt:32-244`）→ 再用 `BackupZip.read` 重读 ZIP 并按 `manifest.pages` 排序逐页解码到 `FullBackupPayloadPageWire`（`category`/`pageIndex` 复核），按 T10 `merge` 同语义合并（列表按页序拼接；settings/mahjong 取最后一个非空；media 拼接；`content.version=5/type="full_backup"`；`BackupPagePlanner.kt:14-40`）。
2. 二次解码用 `Json { ignoreUnknownKeys = true }`（与 `BackupFileReader.kt:15` 同配置）；合并后断言：full payload 各集合计数 == T10 占位 payload 各集合计数、full `media` == T10 `media`（防双解码漂移，失配抛 `BackupFormatException`）。
3. v1 单 payload（`formatVersion==1`）同样支持：解码 `data/backup.json` 为完整模型（T10 已支持读取，`BackupFileReader.kt:76-81`）。
4. `FullBackupReader.validate` 直接委托 T10 reader（`BackupValidationResult` 类型不变）。

### C2 删除顺序（单事务内，子先于父；严格按 oracle）

2.1 顺序与门禁（与 `RestoreDatabaseExecutor.kt:30-74` 逐行一致）：
1. `extras`：`daily_deliveries` → `gift_records` → `dispatch_records` → `mahjong_saves`（`:32-37`）；
2. `memories`：`memory_links` → `memory_source_queue` → `memory_batches` → `vector_memories` → `memory_items` → `memory_anchors` → `memories`（`:38-46`）；
3. `chats`：`reply_turns` → `chat_display_events` → `chat_archives` → `chat_history_segments` → `chat_messages` → `chat_sessions` → 群 `chat_sessions`（`:47-55`）；
4. `social`：`moment_likes` → `moment_comments` → `moments` → `diaries` → `shared_experience_participants` → `shared_experiences`（`:56-63`）；
5. `knowledgeBases`：`operator_knowledge_bases` → `knowledge_base_chunks` → `knowledge_bases`（`:64-68`）；
6. `roles && chats`：`relationships` → `operators`（`:69-74`）。
2.2 未选中的类别既不删也不插（T30 裁决沿用；`BackupContentSelection.normalized()`：memories⇒chats，且任一类目⇒roles，`BackupWireModels.kt:183-199`）。

### C3 插入顺序（依赖拓扑；严格按 oracle）

3.1 顺序（与 `RestoreDatabaseExecutor.kt:76-174` 一致）：operators → knowledge_bases → chunks → assignments → relationships → sessions → messages → displayEvents → memories → anchors → moments → comments（父级拓扑循环）→ likes → backfill 计数 → diaries → gifts → dispatches → mahjong → sharedExperiences → participants → archives → historySegments → memoryItems（最后，依赖 moments/comments 的新 id）。
3.2 每类目仍受选择门禁（未选中不写）；`normalized()` 保证 memories 选中时 chats 同时恢复（否则记忆引用断裂）。
3.3 进度回调口径沿用 `report(detail, added)`（`:26-29`）；测试断言总进度终值 == 计划记录数。

### C4 引用重映射（新 id 映射表）

4.1 `momentIds: Map<oldId,newId>`（`:116-120`）、`commentIds`（`:121-131`，含父评论拓扑排序）、`experienceIds`（`:153-157`）；`memoryItems.sourceRefId` 按 `sourceKind` 映射（`:165-171`）。
4.2 映射缺失时：likes 静默跳过（`:135`）；participants 静默跳过（`:158-161`）；memoryItems 保留原 sourceRefId（`:168-170`）。**不得**因映射缺失而中止恢复（与 oracle 一致）。
4.3 引用完整性在读取阶段已强校验（`BackupFileReader.kt:134-207`）：知识库/关系/群成员/消息/displayEvents/archives/history/gifts 的非法引用直接拒绝包；仅 5 类孤儿降级 issue（可 skip）。

### C5 派生字段归一（恢复后状态必须是「未重建」态）

5.1 KB：`indexStatus="pending"`、`indexedEmbeddingSignature=""`；chunk `indexedAt=0`、`indexError=""`（`:82,85`）。
5.2 dispatch：`active|generating → paused`（`:146-149`）。
5.3 memoryItems：L2/L3 → `status="archived"`；`vectorId=""`（`:166,174`）。
5.4 向量/队列/链接/批次/daily_deliveries/reply_turns 清空且不重建（`:33,39-42,48`）。
5.5 moments 计数以实际 likes/comments 行数回填（`:137-138`）。

### C6 分类选择门禁与幂等

6.1 选择语义：删除与插入都按 `normalized()` 门禁；`roles-only` upssert（不删角色）；`settings`/`media` 独立门禁（settings 快照/清除仅当选中；media staging 仅当选中，`BackupRestoreCoordinator.kt:85-95` 同口径）。
6.2 幂等：同一包连续恢复两次 → 第二次为全量替换，DB 内容与第一次等价（22 表计数/字段/新 id 相对关系一致；自增表 id 值可变但映射结构一致——测试按业务键/有序集合比对）。

### C7 单事务 + 提交前自检 + 提交后自检

7.1 `runRestoreTransaction`（生产 `ChatRepository.runRestoreTransaction`，`ChatRepository.kt:55`；测试 `RhodesDatabase.transaction`）内完成 C2+C3；**插入后、提交前**按 payload 预期计数逐表对账（§自检指标），任一失配抛 `BackupFormatException` → 单事务回滚（DB 零残留）。
7.2 提交成功后执行 `BackupFullSelfCheck`：manifest.recordCounts（17 键，`BackupFileWriter.kt:111-131`）∪ payload 派生计数（补 `memories/anchors/memoryItems/likes/sharedExperienceParticipants/mahjongSave` 六项 Android recordCounts 未覆盖的键）与 DB 对账；结果写入 `BackupFullRestoreResult.selfCheck`，**不翻转**已提交 `success`（与 T10 规格 #13 口径一致）。
7.3 事务内自检口径 = 本次选中类目；未选中类目不检查（与 T30 `BackupRestoreSelfCheck` 同原则）。

### C8 设置全量恢复语义（本卡裁决项 D3，见「风险与开放问题」）

8.1 `selection.settings=true`：`snapshot` → `clearForRestore` → 事务前 `apply(备份 settings)`；失败回放快照（`BackupRestoreCoordinator.kt:96-111`）。
8.2 iOS 沿用 T30 的补齐语义（`BackupRestoreCoordinatorEx.kt:99-108`）：仅 `selection.settings` 时 apply；**与 Android 的差异**（Android 无论是否选中都 apply，`:124`）记平台差异白名单 P2。若 lead 采纳「角色局部设置随 roles 恢复」，则 `selection.settings=false` 时只 apply 角色局部 key 家族（§类目 7 清单中的 `operator_prompt_slot_*`/`msg_*`/`dyn_*`/`voice_volume_*`/`chat_tts_*`/`bg_*`/`diary_read_at_*`/`last_mode_*`/`gbg_*`），全局 key 不动。

### C9 媒体 URI 重写（全类目版）

9.1 映射表 `payload.media[mediaId].originalUri → stagedMedia[mediaId]`；原始 URI 精确匹配 + 裸 mediaId 兜底（T10 裁决 a，`BackupRestorePayloadRewriter.kt:1-37`）。
9.2 扫描域与 Android 一致：operators.avatarUri、sessions.avatarUri、messages.content、settings `user_avatar_uri`（保留 `s:` 前缀）与 `support_conversation`、giftRecords.imageUri、chatArchives.messagesJson/stateJson、chatHistorySegments.messagesJson（oracle `BackupRestorePayloadRewriter.kt:3-42`）。**不含** moments/diaries/memories/memoryItems 内容（Android 同款，不扩大）。
9.3 未映射的引用保留原值；重写只作用于选中恢复的数据。

### C10 孤儿过滤（全类目版）

10.1 与 T30 `BackupContentFilter` 同语义、同 5 个 issue code（`:8-27`；oracle `BackupRestoreCoordinator.kt:135-155`）：`ORPHAN_MEMORY/ORPHAN_ANCHOR/ORPHAN_MOMENT_COMMENT/ORPHAN_MOMENT_LIKE/ORPHAN_SHARED_EXPERIENCE_PARTICIPANT`；未命中 skip 时原样保留（与 Android 一致，孤儿行允许落库）。
10.2 过滤在事务前对完整 payload 执行；预期计数按过滤后 payload 计算（C7）。

### C11 与 T10/T30 并存

11.1 新类全在 `shared/.../backup/wire/` 与 `shared/.../backup/` 新文件；T10/T30 文件零改动（git diff 校验）。
11.2 `BackupFullRestoreCoordinator` 与 T10 `BackupRestoreCoordinator` 接口同形（构造注入 repository/settings/mediaSink/onProgress/options；返回结果含 stage/success/reason/selfCheck），便于 Phase 3 UI 二选一接线。

### C12 三层回滚 / 失败语义

12.1 校验失败（事务前）：返回 failure，DB/媒体/设置零副作用（`BackupRestoreCoordinator.kt:66-72`）。
12.2 事务内自检失败/任何异常：单事务回滚 + 媒体目录 cleanup + 设置快照回放（`:107-111` + T10 P2 补强）。
12.3 提交后自检 mismatch：不翻转 success，结果携带 mismatch 列表。
12.4 取消：`CancellationException` 上抛、清理媒体、事务未提交自然回滚（`:121-124`）。

### C13 坏包矩阵（无残留）

13.1 复用 T10 校验（`BackupFileReader.kt` 全项）：缺 manifest / formatVersion∉{1,2} / scope≠full / files 与 ZIP 不一致 / 逐条目 sha256 不符 / 不安全路径（`../`、绝对路径、`:/`）/ 重复路径 / v2 缺分页 / 分页 recordCount 不符 / 引用不完整 / 媒体清单不一致 / 媒体字节损坏 → `Invalid(reason)` 且不进入 DB。
13.2 测试变体以生成器产出的金样字节现改现用（`BackupZip`+`BackupZipWriter` 改写，T30 已有工具链），不落盘坏包。

### C14 进度/取消/串行

14.1 阶段：VALIDATING → STOPPING_BACKGROUND_WORK → RESTORING_MEDIA → RESTORING_DATABASE（内含类目进度）→ REBUILDING_RUNTIME（no-op）→ COMPLETED/FAILED（`BackupRestoreStage` 沿用）。
14.2 执行器注 `onProgress(completed,total)`；coordinator 串行调用；非线程安全声明。

## 数据与迁移

- 涉及表：见 §0 矩阵（22 恢复 + 6 重置；共 28，与 `migrations.md` 清单逐表对应）。**不新增迁移**（shared schema 与 golden baseline 已 diff 一致）。
- 字段进备份：22 表可移植字段全量（Android `ExportPayload`/`BackupPayload` 字段集）；派生/运行期字段故意不进：KB `indexStatus/indexedEmbeddingSignature`、chunk `indexedAt/indexError`、memoryItem `vectorId`、`memory_links/source_queue/batches/vector_memories/daily_deliveries/reply_turns` 整表、`max_used_message_id`。
- 密钥不进备份：见 §类目 7；iOS 密钥存 Keychain，恢复路径不触碰。
- 失败回滚：三层（事务 + 媒体 + 设置），见 C12。
- 版本兼容：`formatVersion=2` 分页 + `formatVersion=1` 单 payload（C1.3）；`ignoreUnknownKeys` 前向兼容；枚举值非法时 decode 失败（与 Android 同）。

## 金样设计（`ios-port/golden/backup-full/`）

### 生成器

- `make_full_backups.py`（纯 python3 标准库）：确定性构造 + `--verify <zip>` 自校验 + `--list`；两次运行逐字节一致（固定 `createdAt/backupId/timestamp/zip 条目时间`）。
- 构造依据（全部只读，tag `v1.14.27-android-baseline` @ `3642d2c`）：容器/清单/写入序 `BackupFileWriter.kt:46-98,101-131`；分页规则 `BackupPagePlanner.kt:14-83`（ROLES 0/10000/90000、CHATS 0/10000/20000/30000/40000、MEMORIES 0/10000/20000、SOCIAL 0/10000/20000/30000/40000/50000、KNOWLEDGE_BASES 0/10000/20000、EXTRAS 0/10000/20000、SETTINGS 0）；载荷字段序 `ExportPayload.kt:22-43` + `BackupModels.kt:87-98`；`recordCounts` 17 键 `BackupFileWriter.kt:111-131`；mediaId 路径派生 = `sha256(canonicalPath)[:24]`（`BackupMediaCollector.kt:71-72`，T10 裁决 3 保留）；zip 条目 deflate + 固定时间戳；manifest 最后写。
- 自校验（`--verify`，镜像 T10 reader 关键检查 + 本卡增补）：路径安全/唯一；manifest 三元组（format/version/scope）；files 集合 == ZIP−manifest 且逐条目 size+sha256；v2 分页清单/标识/recordCount 公式（`BackupFileReader.kt:123-130`）；引用完整性（同 `:134-207`）；媒体清单 == ZIP `media/` 且 mediaId 路径派生；7 类目覆盖断言（all 包 7 个 `BackupCategory` 页面均存在且记录数 >0）；金样 SHA-256 打印。
- 只生成「合法包」；坏包变体由测试用改写工具现造（C13.2）。

### 样本包（8 个，名字冻结；每类目 ≥1 包覆盖）

| 包 | 规模 | 覆盖点 |
|---|---|---|
| `full-all-v2.zip` | 2 角色/3 会话（2 私聊 + 1 群）/12 消息/4 displayEvents/2 存档/1 历史/2 关系/3 记忆/3 锚点/3 memoryItems/2 书 3 块 2 关联/2 动态/3 评论（含嵌套）/2 赞/2 日记/2 礼物/2 派遣/1 麻将/2 共同经历 + 3 参与者/全量 settings/3 媒体 | **7 类目全字段重放主金样**（#2-#5 验收） |
| `full-memories-v2.zip` | 3 记忆（3 层 + 全局 daily）/6 锚点（6 类型）/4 memoryItems（L1/L2/L3 + MOMENT/MOMENT_COMMENT 源）/2 角色 2 会话 4 消息（引用底座）/2 孤儿记忆 + 1 孤儿锚点 | memories 三层 + 锚点 + 向量重置 + ORPHAN/skip 路径 |
| `full-social-v2.zip` | 2 动态/3 评论（含嵌套）/2 赞/2 日记/2 共同经历 + 3 参与者/1 角色；1 孤儿评论 + 1 孤儿赞 | social + 引用重映射 + ORPHAN/skip |
| `full-knowledge-v2.zip` | 1 书/2 块/1 关联 + 2 角色 | KB 三表 + pending 归一 |
| `full-extras-v2.zip` | 2 角色/1 会话/2 消息/2 displayEvents/1 存档/1 历史/2 礼物/2 派遣（active+finished）/1 麻将 | extras + chats 附属 + dispatch status 归一 |
| `full-media-v2.zip` | 1 角色（头像媒体）/1 会话（头像媒体）/2 消息（image + ai_json 内嵌图）/1 礼物（图）/1 存档 messagesJson 内嵌图/1 历史/ settings `user_avatar_uri`+`support_conversation`/4 媒体 | 媒体重写全扫描域（C9） |
| `full-empty-v2.zip` | 0 记录（`settings={}`；仅 SETTINGS/0 页） | 空包恢复 = 清空选中类目（幂等/选择门禁） |
| `full-v1-single.zip` | 精简全类目（每类 1-2 条），`formatVersion=1`、`layout="single_payload"`、`data/backup.json` | v1 单 payload 全类目恢复（C1.3） |

- 与 T10/T30 格式关系：**同 v2 容器**（`rhodes-backup` v2、`paged_payload`、manifest/files/逐条目 sha256、mediaId 路径派生、分页 pageIndex 规则与 `BackupPagePlanner` 完全一致）；T10 `minimal-1.14.27.zip` 只覆盖 ROLES/CHATS 少量页，本目录补齐 MEMORIES/SOCIAL/KNOWLEDGE_BASES/EXTRAS/SETTINGS 全部页型；T30 `chat-import` 是独立 `rhodes-chat` v1 容器（内容派生 mediaId），与本目录**互不替代**（读取器互斥已由 T30 验收 #6 覆盖）。
- 测试资源同步：`sync_golden.sh`（生成器可复跑校验 + 冻结 SHA-256 表 + 拷入 `shared/src/commonTest/resources/golden/backup-full/` 与 `shared/src/androidUnitTest/resources/golden/backup-full/`；iOS 走 commonTest bundle，读法与 T30 `readTestResourceBytes` 同）。
- 冻结表：生成后写入 `README.md` §1（8 包 SHA-256）与 `sync_golden.sh` 的 `EXPECTED_HASHES`；测试内 `FullBackupGoldens.FROZEN_SHA256` 同步并在加载时断言（防资源漂移）。

## 验收（必须可自动判定）

| # | 验收项 | 判定方式 | 判定脚本/命令 |
|---|---|---|---|
| 1 | 生成器确定性 + 自校验 | 两次生成逐字节一致；8 包 `--verify` 全 PASS；冻结 SHA-256 一致 | `python3 make_full_backups.py && shasum -a 256 *.zip`；`--verify` ×8；`sync_golden.sh` |
| 2 | 8 包均被 T10 `BackupFileReader` 判 Valid（含引用/媒体/分页全项） | 双路径 commonTest：`reader.validate` == Valid 且 issues 符合预期 | `FullBackupGoldenTest.acceptance1_*`（JVM+iOS） |
| 3 | **全类目字段级往返**（`full-all-v2.zip`） | 恢复后逐表逐字段断言：22 表计数 + 字段值（operators 22/sessions 12/messages 15/displayEvents 4/archives 12/history 6/relationships 7/memories 10/anchors 14/memoryItems 30/KB 9/chunks 10/assignments 4/moments 9/comments 9/likes 5/diaries 7/gifts 6/dispatches 12/mahjong 4/experiences 9/participants 3/settings 全量） | `acceptance3_fullCategoryRoundTrip` |
| 4 | id 语义 + 引用重映射 | 保留 id 断言（operators/sessions/messages/KB/chunks/archives/history/gifts/dispatches/mahjong）；自增表映射断言（评论父链、likes→新 moment、participants→新 experience、memoryItem sourceRefId→新 moment/comment） | `acceptance4_idAndReferenceMapping` |
| 5 | 派生字段归一 | KB `indexStatus=pending`、chunk `indexedAt=0`、dispatch `active→paused`、memoryItems L2/L3 `archived`、`vectorId=""`、moments 计数回填 == likes/comments 行数、6 张运行表清空（含测试种子向量被清） | `acceptance5_derivedFieldNormalization` |
| 6 | 恢复后自检 | 全包 `selfCheck.passed=true`，`checked` 键覆盖 23 项；篡改 `manifest.recordCounts` 后自检 mismatch 但 success 不翻转 | `acceptance6_selfCheck` |
| 7 | 幂等 | 同包连续恢复两次：22 表内容等价、无重复行、媒体无新增 | `acceptance7_idempotentReRestore` |
| 8 | 坏包无残留 | 6 变体（缺分页页/recordCount 错/sha 错/不安全路径/重复路径/媒体字节坏）→ Invalid + DB 0 行 + 媒体 cleanup | `acceptance8_badPackagesLeaveNoResidue` |
| 9 | 事务内失败三层回滚 | 注入 DB 失败（如篡改引用触发 requireNotNull/计数失配）→ DB 恢复前快照、媒体清理、设置回放 | `acceptance9_transactionFailureRollback` |
| 10 | 分类选择门禁 | 预置现网数据后按类目单选恢复：未选中类目零改动；memories⇒chats 强制；roles-only upsert 不删角色；settings/media 门禁 | `acceptance10_selectionGating` |
| 11 | 空包/空类目 | `full-empty-v2.zip` → 选中类目清空、不崩溃；0 媒体/0 settings | `acceptance11_emptyPackage` |
| 12 | 媒体重写全扫描域 | `full-media-v2.zip`：avatar/会话头像/消息内 imageUri/礼物图/存档 JSON/历史 JSON/`user_avatar_uri`/`support_conversation` 全部替换为 staging URL；未知 URI 保留 | `acceptance12_mediaRewriteCoverage` |
| 13 | ORPHAN issue / skip 语义 | memories/social 包 issues 列表与计数符合预期；带 skipIssueCodes 时孤儿行被剔除且预期计数随过滤；不带时孤儿行落库 | `acceptance13_orphanSkipSemantics` |
| 14 | 分类包 + v1 单 payload | 4 个分类包各自往返；`full-v1-single.zip` 全类目恢复等价 | `acceptance14_categoryPackagesAndV1` |
| 15 | 双路径全量回归 | JVM `:shared:testDebugUnitTest` ≥ **504** 基线 + T31 新增（实测 520/0）；iOS `:shared:iosSimulatorArm64Test` ≥ **532** 基线 + T31 新增（实测 559/0） | gradle 命令见下 |
| 16 | app 不回归 | `:app:testDebugUnitTest` **345** 全绿（本卡零 app 改动）；`:app:assembleDebug` 编译通过 | gradle 命令见下 |

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd ios-port/golden/backup-full && python3 make_full_backups.py && python3 make_full_backups.py --verify full-all-v2.zip
bash sync_golden.sh --sync-only
cd ../../.. && cd RhodesLink
pgrep -fl gradle && sleep 30   # 并行代理排队纪律：有 gradle 先等
./gradlew --no-daemon :shared:testDebugUnitTest --tests "*FullBackupGoldenTestJvm*"
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*FullBackupGoldenTestIos*"
./gradlew --no-daemon :shared:testDebugUnitTest
./gradlew --no-daemon :shared:iosSimulatorArm64Test
./gradlew --no-daemon :app:testDebugUnitTest
```

- 金样 diff 白名单：`createdAt/backupId/timestamp/zip 时间戳` 为固定值（金样确定性 vs 真实导出时间）；自增表 id 值不参与断言（按映射结构/业务键断言）；`manifest.recordCounts` 不参与读取校验（reader 不校验其值，`BackupFileReader.kt:123-130` 只校验分页 recordCount）。
- 边界用例清单：空包/空类目；v1 单 payload；坏包 6 变体；事务内失败；取消；重复恢复；选择门禁；孤儿 issue/skip；大字段（rawContent 1MB 级 chunk 文本）；幂等。

## 风险与开放问题

1. **D1（本卡实现策略，报 lead 确认）**：新增 `backup/wire/` 包 + 二次解码，而非改 T10/T30 现有文件（写域「仅新增文件」）。代价：恢复路径对 ZIP 解码两次（manifest+占位 payload 一次、完整 payload 一次；实测金样 <1MB 无感；真实大备份 500MB 理论上限下多一次解码，记 P2 性能风险，可在 Phase 7 合并单解码）。收益：T10/T30 已验收代码零回归风险。
2. **D2（本卡实现策略）**：提交前自检放在事务内（失配抛错回滚），提交后自检只做 manifest 对账不翻转 success。理由：满足 T31「单事务 + 三层回滚 + 提交前自检」且保留 T10 语义。若 lead 要求「提交前自检」= 仅 payload 校验级，可去掉事务内计数。
3. **D3（需 lead 裁决）**：settings 选择语义。Android 无论是否选中都 apply 全部备份设置（`BackupRestoreCoordinator.kt:124`）；T30 iOS 仅选中时 apply（`BackupRestoreCoordinatorEx.kt:105-108`）。推荐：选中=全量 apply；未选中=只 apply 角色局部 key 家族（保留角色 prompt 槽位/权限随角色恢复的 Android 意图，不覆盖用户全局设置）。备选 A：完全镜像 Android（未选中也全量 apply）；备选 B：完全沿用 T30（未选中不动任何设置）。
4. **D4（需 lead 裁决）**：Android `manifest.recordCounts` 缺 6 项（memories/anchors/memoryItems/likes/participants/mahjong）——iOS 自检以 payload 派生计数补足（不改备份格式、不写 manifest）。若要求 manifest 也覆盖，则需 Android 侧导出器改格式（超出本卡范围，建议不做）。
5. **R1**：moments likeCount/commentCount 以实际行数回填（oracle 同款）→ 若备份本身计数与行数不一致（历史脏数据），恢复后计数会「被修正」，金样/自检必须按行数断言而非 payload 值。
6. **R2**：自增表 id 不保留（与 Android 一致）→ 引用它的 `memoryItems.sourceRefId`、comments.parent、participants 已映射；但 `stateJson/rawJson` 内嵌的 moment/comment id（若有）不重映射（Android 同款，不扩大扫描域）。
7. **R3**：媒体引用扫描域不含 moments/diaries/memoryItems 内容（Android 同款）→ 这些内容里若有旧 `file://` URI 会残留不可渲染（与 Android 现状一致，记已知限制）。
8. **R4**：向量/索引恢复后为「待重建」态（`vectorId=""`、KB pending、chunk indexedAt=0）；重建由运行期/MemoryVectorService/KnowledgeBaseIndexer 后续完成，Phase 7 接自动重建。本卡只保证状态正确 + 自检断言。
9. **R5**：v1 单 payload 的全类目恢复在 Android 1.14.27 已是遗留兼容路径（`BackupFileReader.kt:76-81`）；金样仅 1 包覆盖，作为兼容回归，不代表真实导出主路径。
10. **平台差异白名单**：媒体 URI 重写 = mediaId 直映射（T10 裁决 a，沿用）；插入也按选择门禁（T30 裁决，Android 插入不门禁）；失败路径无条件清理媒体目录（T10 P2）；settings 选择语义待 D3 裁决；WorkManager/调度器重建 = no-op（T10 白名单）。

## 评审结论

- reviewer：未单独评审（lead 批准并直接裁决 D1-D4；实现完成后双路径金样测试作为行为证据）。
- lead：**批准（2026-11-23）**，D1-D4 裁决：
  - D1 批准「新增 `backup/wire/` 包 + 先 T10 全校验再二次解码」策略（性能风险记 Phase 7 合并）；
  - D2 批准「提交前自检在事务内、提交后对账不翻转 success」（T10 口径）；
  - D3 **采纳推荐**：settings 选中=全量 apply；未选中但恢复 roles/chats=只 apply 角色局部 key 家族；
  - D4 批准：iOS 自检用 payload 派生计数补足 manifest 缺的 6 项（memories/anchors/memoryItems/likes/participants/mahjong），不改备份格式。
