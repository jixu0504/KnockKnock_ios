# 规格卡：A5 聊天记录导入导出 + 角色卡 + 完整备份恢复补齐（T30）

> 模板：`ios-port/specs/规格卡模板-v1.md`。黄金法则：Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`）是行为 oracle；每条行为契约必须指向 Android 源码 file:line。
> 前置裁决：T6 `ios-port/decisions/chat-import-可行性.md`（结论=做，4.0-6.5 代理日，Android 导出器先行）；
> 已批准规格卡：`backup-restore-minimal-spec.md`（T10）、`message-id-media-spec.md`（T17）；
> 金样：`ios-port/golden/chat-import/`（4 类，生成器 `make_chat_exports.py` 可复跑 + 自校验，本卡配套产物已落地）。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | A5 聊天记录导入导出（ChatExport v1）+ 角色卡（rhodes-operator v1）导入导出 + 备份恢复补齐（分类选择/分页/自检） |
| 所属阶段 | Phase 2（T30）；格式冻结在 Android 侧导出器先行（T6 方案要点 1） |
| 范围映射 | A5（修订）："完整备份/恢复、导入导出（含角色卡与聊天记录）"（计划书 A5；2026-11-22 A5 修订：跨端结构化聊天导出 `RhodesLinkChatExport.zip` = manifest.json + conversation.json + media/） |
| 对应 Android 源码 | **导出器新增**：`app/.../data/backup/ChatExportService.kt`、`ChatExportModels.kt`（新建）；**复用**：`BackupFileWriter.kt`、`BackupMediaCollector.kt`、`BackupModels.kt`、`BackupSnapshotBuilder.kt`、`ExportPayload.kt`；**UI**：`BackupRestoreScreen.kt:235-260` 镜像入口；**角色卡 oracle**：`OperatorPackage.kt:37-119`、`OperatorPackageService.kt:29-186`；**备份 oracle**：`BackupFileReader.kt`、`RestoreDatabaseExecutor.kt`、`PortableSettings.kt`、`BackupRestoreCoordinator.kt` |
| 依赖模块 | T10 `shared/.../backup/`（BackupZip/BackupCrypto/BackupFileReader/BackupMediaSink/BackupRestorePayloadRewriter/BackupPortableSettings/BackupRestoreCoordinator）；T17 `shared/domain/data/MessageIdAllocator`（三源 max 自愈）+ `shared/domain/media/{MediaStore,MediaRepository,MediaId}`；shared 模型 `Operator/ChatSession/ChatMessage/ChatArchive/ChatHistorySegment`；SQLDelight 表 `chat_sessions/chat_messages/chat_display_events/chat_archives/operators/relationships`（`golden/db/schema`） |

## 输入 / 输出 / 边界

- **输入**：
  - Android 导出：会话 id（单会话）+ `OutputStream`（SAF `CreateDocument("application/zip")`）+ 导出选项（`includeArchives=true`，v1 默认含存档/历史片段）。
  - iOS 导入：`ByteArray`（document picker 的 Data；与 T10 `BackupFileReader.read(bytes)`/`BackupZip` 输入同形）。
  - iOS 角色卡：`ByteArray`（`rhodes-operator` v1 zip 或旧版单角色 JSON，兼容读取镜像 `OperatorPackage.kt:103-119`）。
  - 依赖注入：`ChatImportRepository`（读/事务写）、`ChatImportMediaSink`（媒体落盘，默认 `MediaStoreChatImportSink(MediaStore)`）、`MessageIdAllocator`、`SettingsRepository`（角色卡 prompt 槽位）。
- **输出**：
  - 导出：ZIP 字节/文件（§数据与迁移 v1 容器）。
  - 导入：`ChatImportResult(success, sessionId, importedMessages, dedupedMessages, importedMedia, archivedCount, reason)` + 阶段回调；副作用=DB 行写入（会话合并/消息重分配/displayEvents/存档）、媒体文件落盘（运行时 media ID）。
  - 角色卡：`OperatorCardPayloadWire`（读）/ zip 字节（写）/ `OperatorCardImportResult(operatorId, createdNew, importedRelationships, skippedRelationships)`。
  - 完整备份恢复补齐：`BackupRestoreResult` + `BackupRestoreSelfCheckResult`（recordCounts 对账）。
- **边界（逐项）**：
  - 坏 zip（CRC 错/截断/非 zip/缺 manifest）：`ChatExportReader` 捕获为 `Invalid(reason)`，DB 与媒体目录零残留（镜像 `BackupFileReader.kt:16-21` 与 T10 三层回滚）。
  - 格式门禁：`format!="rhodes-chat"` / `formatVersion!=1` / `scope!="conversation"` / `conversation.formatVersion!=1` 分别明确拒绝（对照 Android 错误文案风格："不支持的聊天导出类型/格式版本"）。
  - 上限：条目 ≤1000、单条目 ≤100MB、总解压 ≤500MB（`BackupFileReader.kt:10-13` 同口径，`BackupZip` 直接复用）；单会话消息数 v1 软上限 100,000 条（`conversation.json` 单条目；超限报错，不分页）。
  - 重复导入：幂等零新增（去重键见契约 B7）；媒体内容寻址写入，重复导入不产生新文件。
  - 保存失败/磁盘满：媒体写入失败 → 导入失败并清理本次新增文件；DB 事务回滚。
  - 并发：导入串行（`Mutex`），与 T10 恢复互斥（同一把宿主锁；iOS 侧由调用层串行）。
  - 取消：`CancellationException` 直接上抛；清理本次新增媒体与未提交事务（同款语义见 `BackupRestoreCoordinator.kt:107-111,126-131,137-141`：媒体目录清理 / 设置快照回放 / 取消上抛）。

## 行为契约（逐条，每条带 Android oracle 位置）

### A. Android 侧 `ChatExportService`（新增，≈90% 复用备份管线）

1. **快照**：导出前取单会话数据——`repository.getSession(sessionId)` + `repository.getMessagesSync(sessionId)`（排序 `ChatMessage` 按 `timestamp ASC, id ASC`，`ChatMessages.sq:24` 同序）+ `repository.getOperator(session.operatorId)` + 存档/历史片段（`chatArchivesQueries.getArchivesBySession` / `getHistorySegmentsBySession`，`ChatArchives.sq:29,60`）+ displayEvents（`Messages.getAllDisplayEvents` 过滤本会话）。读取口径镜像 `BackupSnapshotBuilder.kt:30-33`；**不**复用全量快照（避免全库读取）。
2. **manifest**：复用 `BackupManifest`/`BackupFileEntry` 模型（`BackupModels.kt:36-76`），值：`format="rhodes-chat"`、`formatVersion=1`、`scope="conversation"`、`appVersion`（调用方传入，同 `BackupFileWriter.kt:13`）、`schemaVersion=1`（`BackupRestoreScreen.kt:124` 同源）、`createdAt=now()`、`backupId=UUID`、`mediaIncluded=媒体非空`、`recordCounts`（operators/sessions/messages/displayEvents/archives/historySegments/mediaFiles）、`excludedCategories=["memories","diaries","moments","knowledge_bases","relationships","settings","credentials"]`、`layout="single_payload"`、`pages=[]`。结构与写入序复用 `BackupFileWriter.kt:81-97` 模式（manifest 最后写、`{path,size,sha256}` 全量清单）。
3. **conversation.json**：新模型（`ChatExportModels.kt`）字段序=Android wire 序——`formatVersion=1, exportedAt, operator: OperatorExport?, session: SessionExport, messages: List<MessageExport>, displayEvents: List<ChatDisplayEventExport>, chatArchives: List<ChatArchiveExport>, chatHistorySegments: List<ChatHistorySegmentExport>, mediaIndex: List<ChatExportMediaItem>`。`OperatorExport/SessionExport/MessageExport` 直接复用 `ExportPayload.kt:46-113`（不新建 schema 漂移）；`ChatArchiveExport/ChatHistorySegmentExport` 复用 shared 模型 `ChatArchive/ChatHistorySegment`（@Serializable）；JSON `encodeDefaults=true`（同 `BackupFileWriter.kt:27`）。
4. **媒体收集（内容派生 mediaId）**：复用 `BackupMediaCollector.kt:43-57` 的引用扫描（`ownedImageFile` 白名单 `:59-69`、`imageUriPattern :86`；扫描 operator.avatarUri / session.avatarUri / messages.content 内 `"imageUri"`），但 **mediaId 改内容派生**：`mediaId="image/<sha256(字节全 64hex)>"`、`archivePath="media/images/<sha256(字节)[:24]>.<ext>"`、`mimeType` 复用 `:74-80`、`originalUri=Uri.fromFile(...)`（`:29` 同款）。相同字节去重为 1 条；非自有/远程 URI 置 `skippedUris` 并保留原引用（不重写 content）。理由：路径派生（`BackupMediaCollector.kt:71-72`）在 iOS 无意义（T6 §3.2；T17 裁决「备份格式路径派生语义保留」仅适用 rhodes-backup，本格式独立，见风险 3）。
5. **ZIP 写出**：`ZipOutputStream` + 逐条目 SHA-256（`BackupFileWriter.kt:46-79,133-141` 同模式）：写 `conversation.json` → `media/**`（按 archivePath 排序）→ `manifest.json`；条目路径安全（`BackupFileReader.isSafeArchivePath` 同规则 `:238-244`）。
6. **UI 入口**：`BackupRestoreScreen.kt` 新增「导出聊天记录（可跨平台导入）」行，镜像可读导出入口 `:235-260`：`CreateDocument("application/zip")` launcher、默认文件名 `RhodesLinkChatExport_<operatorName>_<ts>.zip`、进度弹窗、失败删除所选 URI（`:252-254` 同款）、成功后结果提示。复用现有会话选择器状态 `selectedChatExport`（`:185-188,305`）。
7. **错误语义**：无消息/找不到会话 → 明确中文错误；写盘失败 → 删除已创建文件 + 错误提示（镜像 `:252-257`）。

### B. iOS/shared 侧 `ChatExportReader` + `ChatMergeRestorer`（新增，接入 T10/T17）

8. **ZIP 读取/安全**：复用 `BackupZip.read(bytes, maxEntries=1000, maxEntryBytes=100MB, maxTotalBytes=500MB)`（`shared/.../backup/BackupZip.kt:26-72`，含 CRC-32 校验）；路径 `\`→`/` 归一 + `isSafeArchivePath` + 重复路径拒绝（`BackupFileReader.kt:32-47` 同构）。
9. **manifest 三元组门禁**：`manifest.json` 必须存在且可解析；`format=="rhodes-chat"`、`formatVersion==1`、`scope=="conversation"`、`layout=="single_payload"`、`pages` 为空；`files` 路径唯一且集合 == ZIP 条目集 − manifest.json；逐条目 `size + sha256` 比对（`BackupFileReader.kt:49-68` 同构）。失败返回 `ChatExportValidationResult.Invalid(reason)`。
10. **conversation 载荷校验**：反序列化 `conversation.json`（`ignoreUnknownKeys=true` 前向兼容）；`formatVersion==1`；`session` 必填且 `id`/`operatorId` 非空；`messages` 非空、id 唯一且严格升序（导出契约）；每条 `message.sessionId == session.id`；`operator` 若存在其 `id == session.operatorId`；`displayEvents` 的 `(messageId,segmentIndex)` 唯一、`messageId ∈ messages`、`sessionId==session.id`、`revealOrder>0`（缺失/≤0 时按文件序赋 1-based 序号并记入告警）；存档/历史片段 `sessionId` 一致、`messagesJson` 可解析且其中 `id`/`messageId` 数字值 ⊆ messages ids。
11. **mediaIndex 三方一致**：`mediaIndex` 的 `archivePath` 集合 == manifest 媒体条目 == ZIP `media/` 条目；每项 `size/sha256` 与字节一致；`mediaId == "image/"+sha256(字节)`；`mediaIncluded` 与集合非空一致；重复 mediaId/archivePath 拒绝。
12. **会话合并**：目标会话匹配顺序——(1) `session.id` 精确命中（`ChatSessions.sq:29 getSession`）；(2) 私聊（`operatorId` 非 `group_` 前缀）按确定性 `"session_<operatorId>"` 命中（`SessionRepository.kt:42-43`；镜像 `getSessionByOperator` 首选稳定行，`ChatSessions.sq:19-26`）；(3) 均未命中 → 用导出 `session.id` 新建（若该 id 已被别的 operator 占用则回退 `"session_<operatorId>"`）。命中时合并：保留现有 `isPinned/unreadCount/members/rules/mutedMembers/mode`；`lastMessage/lastTime` 仅在导出 `lastTime` 更新（镜像 `insertSession` 的更新语义 `ChatSessions.sq:38-46 updateLastMessageIfNotNewer`）；`operatorName` 以现有行为准；`avatarUri` 仅在现有为空时用导出值（重写后媒体 ID）。
13. **角色补齐**：目标端无 `session.operatorId` 对应 `Operator` 时，用导出 `operator` 插入（`Operators.sq:58 insertAllOperators`，镜像 `RestoreDatabaseExecutor.kt:78` 字段序）；已存在则**不覆盖**人设（v1 保守合并；覆盖模式留二期，见风险 4）。无 `operator` 且目标端无该角色 → 失败并提示"聊天包缺少角色信息"。
14. **消息 ID 重分配**：对非去重消息按导出顺序调用 T17 `MessageIdAllocator.allocate()`（`shared/domain/data/MessageIdAllocator.kt:48-60`；oracle `MessageRepository.kt:296-305` 三源 max 自愈）生成新 id（严格递增），建立 `oldId → newId` 映射；写库前断言 `chat_messages MAX(id) < 第一个新 id`（事务内，防并发）；写入后 `observeUsed(newId)` 抬高内存高水位（`MessageIdAllocator.kt:68-72`）。**禁止**沿用导出 id（跨设备必然碰撞，T6 §1.3）。
15. **displayEvents 重映射**：`messageId = map(oldId)`；`sessionId = targetSessionId`；`revealOrder` 保留导出值（历史时间戳语义，使旧会话事件排序正确），缺失/≤0 时在导入集内用 `existingMaxRevealOrder + 1..n` 递增；插入用 `insertDisplayEventIfAbsent`（`ChatDisplayEvents.sq:29`，主键 `(messageId, segmentIndex)` 天然幂等）。
16. **存档/历史片段重映射**：`ChatArchive`/`ChatHistorySegment` 的 `messagesJson` 按 `id`/`messageId` 数字键做 old→new 替换（递归 JSON 遍历，`kotlinx.serialization.json`）；`stateJson` 同样递归替换（`ChatArchiveContext.sourceMessagesJson` 内嵌）；`id` 字符串主键在冲突时加 `_imported_<timestamp>` 后缀；`sessionId = targetSessionId`。Android oracle：存档写入 `ChatViewModel.kt:349`、恢复重放 `RestoreDatabaseExecutor.kt:163-164`（无合并语义可参考，重映射为 iOS 新逻辑）。
17. **幂等去重键**：`dedupKey = sha256("$targetSessionId\u0000$timestamp\u0000$senderId\u0000$type\u0000$isMe\u0000$content")`（T6 开放问题 2 建议 `(sessionId,timestamp,senderId,content)` 的收敛版；只做**精确**归一，不做文本模糊化）。导入前查目标会话全部消息建立 `key → 现有 id`；命中者不重新分配 id、不插入，`oldId → 现有 id` 进映射（供 displayEvents/存档重映射）；第二次导入同一包 → 新增 0 条。同毫秒同发送者同内容的两条真实消息会被合并为一条（v1 已记录的已知限制，见风险 5）。
18. **媒体落盘（接入 T17）**：新增 `ChatImportMediaSink` 接口 + 默认实现 `MediaStoreChatImportSink(store: MediaStore)`：按内容寻址写运行时媒体 ID —— 相对路径 `images/media/import-<sha256(content)[:24]>.<ext>`、运行时 ID `media/import-<sha256(content)[:24]>.<ext>`（合法 `MediaId`，可被 `MediaRepository.resolve` 命中）；返回 `oldRef → 新 ID` 映射。**不**复用 T10 的 `restored_media/<backupId>`（那是恢复暂存，导入要进运行时媒体库，T17 契约 12-14）。失败回退：跳过媒体并在告警中记录（镜像 `ImageUtils.kt:24-32` 失败回退原引用）；导入前记录已存在路径，失败/取消时只删除本次新增文件。
19. **引用重写**：把 `messages.content` 中 `"imageUri":"<old>"`、operator/session `avatarUri`、存档/历史 `messagesJson|stateJson` 内的旧媒体引用替换为 §18 的新运行时 ID（按 `originalUri` 与裸 `mediaId` 两种形态精确匹配，镜像 T10 `BackupRestorePayloadRewriter.kt:17-55` 的映射思路，但映射表来自导入媒体 sink）；未映射的引用保留原值（同 T10「缺失保留」）。
20. **单事务 + 自检**：`ChatImportRepository.runImportTransaction { … }`（生产适配 `ChatRepository.runRestoreTransaction`，`shared/data/ChatRepository.kt:55`；测试用 `RhodesDatabase.transaction`）内完成：会话 upsert/合并 → 角色插入（如需）→ 消息插入（`insertMessage`，`ChatMessages.sq:74-77`）→ displayEvents → 存档/历史片段；事务提交前自检：新增消息数 == 计划数、新 id 全部 > 导入前 MAX(id)、目标会话消息数增量一致、无重复 id；任一失败抛错 → DB 整体回滚（三层回滚：事务 + 新增媒体删除 + 无设置改动）。
21. **进度/取消/串行**：`ChatImportStage`：VALIDATING → STAGING_MEDIA → RESTORING_DATABASE → COMPLETED/FAILED；媒体 staging 失败不触碰 DB；`CancellationException` 上抛并清理（同契约 §边界）。
22. **结果自检对账**：成功结果含 `importedMessages/dedupedMessages`，并断言 `importedMessages + dedupedMessages == messages.size`；`ChatExportResult` 的 `recordCounts` 用于测试对账（非运行时强校验）。

### C. 角色卡（rhodes-operator v1）iOS 复用

23. **Writer**（`OperatorCardWriter`）：容器逐字节镜像 `OperatorPackage.kt:37-66` —— 条目 `data/operator.json`（`OperatorCardPayloadWire`）+ 可选 `media/avatar.<ext>` + `manifest.json`（`format="rhodes-operator"`、`formatVersion=1`、`scope="operator_card"`、`mediaIncluded`、`recordCounts{operators,relationships,mediaFiles}`、`excludedCategories=["chat_history","memories","diaries","moments","credentials"]`、每条目 `{path,size,sha256}`、manifest 最后写）。
24. **Reader**（`OperatorCardReader.read`）：镜像 `OperatorPackage.kt:68-100` —— 路径安全/重复拒绝（`:78-83`）、上限 16 条目/单文件 20MB/总 25MB（`:135-139`）、manifest 三元组（`:88-91`）、逐条目 size+sha256（`:92-95`）、payload 解码（`:96`）、头像清单一致性（`:97-99`）；payload 字段= `operator: Operator`（22 字段，与 `OperatorExport` 同形）+ `relationships: List<RelationshipExport>` + `avatar: BackupMediaItemWire?` + `promptSlots: OperatorPromptSlotsWire?`（`privateSlots/groupSlots/activePrivateSlot/activeGroupSlot`，镜像 `OperatorPackage.kt:18-33`）。
25. **旧版兼容**：`readCompatible` 镜像 `:103-119` —— 先按 zip 读；失败则按旧版单角色 JSON 解码（`shared/model/ExportModels.kt:6-13`，`type=="operators"` 且仅 1 个 operator），转换为 `OperatorCardPayloadWire` 后走同一校验路径。
26. **导入执行**（`OperatorCardImporter`）：镜像 `OperatorPackageService.kt:58-186` 三种模式——`NEW`（新 UUID id + 同名去重后缀"（导入）"/"（导入） N"）、`PERSONA_AND_APPEARANCE`（仅人设/头像/语音/prompt 字段）、`FULL_REPLACE`（整条替换）；头像字节→ `MediaStore` 写入运行时媒体 ID（`images/media/card-<sha24>.<ext>`）；prompt 槽位回填 `operator_prompt_slot_<id>_{private,group}_{1..3}` + 活跃槽位（`:146-176` 同款：旧卡无槽位时把当前 prompt 放入当前选中槽）；关系导入仅保留目标端存在的角色（否则 skipped，`:112-130`）；`NEW` 时跳过关系导入（`:112` 同款 `PERSONA_AND_APPEARANCE` 直接返回）——全部包在 `runOperatorTransaction { }` 单事务。
27. **导出执行**（`OperatorCardExporter`）：读取 `Operator` + 关系（`Relationships.sq:21`）+ prompt 槽位（`settings.getInt/getString("operator_prompt_slot_*")`）+ 头像字节（由调用方注入 `resolveAvatarBytes: (String) -> ByteArray?`，生产=T29 桥接层经 `MediaRepository.resolve`）；`operator.privatePrompt/groupPrompt` 用活跃槽位值对齐（镜像 `OperatorPackageService.kt:38-53`），组装 payload 后交 §23 Writer。

### D. 完整备份恢复补齐（本卡界定；lead 2026-11-23 已裁决：本卡=分类选择门禁 + 分页/v1 读取覆盖 + 恢复后自检；全类目字段级重放单列 T31）

28. **分类选择恢复门禁**：新增 `BackupSelectiveRestoreExecutor`（新文件；T10 `BackupRestoreExecutor` 保持不动）——按 `BackupContentSelection.normalized()`（`BackupWireModels.kt:183-199`，镜像 `BackupModels.kt:16-34`）逐类目决定"删+插"：未选中的类目不删也不插（修复 T10 现状"只删不插门禁"的不对称）；roles 仅在 `roles && chats` 时替换（`RestoreDatabaseExecutor.kt:69-74` 同款）。
29. **分页数据**：沿用 T10 `BackupFileReader` v2 分页读取 + `BackupPagePlanner.merge`（`shared/.../backup/BackupFileReader.kt:99-125`；oracle `BackupFileReader.kt:102-121`），本卡不改读路径；验收补一条 v1 单 payload（`layout="single_payload"`）备份的读取等价测试（T10 已支持、无测试覆盖）。
30. **恢复后自检**：新增 `BackupRestoreSelfCheck`（新文件）——恢复完成后按 `manifest.recordCounts` 对账本次选中类目的 DB 计数（operators/sessions/messages/displayEvents/archives/historySegments/roles 关系），返回 `BackupRestoreSelfCheckResult(passed, mismatches)`；对账失败不改写已提交恢复（`BackupRestoreResult.success` 不变，自检结果单独返回，镜像 T10 规格 #13 的"补强项"口径）。
31. **编排补齐**：新增 `BackupRestoreCoordinatorEx`（新文件，不改 T10 `BackupRestoreCoordinator`）：VALIDATING → CREATING_SAFETY_BACKUP（Phase 0 语义）→ STOPPING_BACKGROUND_WORK → RESTORING_MEDIA → RESTORING_DATABASE（selective）→ 自检 → REBUILDING_RUNTIME → COMPLETED；失败三层回滚与 T10 一致（单事务 + 媒体目录删除 + 设置快照回放）。

## 数据与迁移

- **涉及表**：
  - 聊天导入写：`chat_sessions`（`ChatSessions.sq:38 insertSession`）、`chat_messages`（`ChatMessages.sq:74 insertMessage`）、`chat_display_events`（`ChatDisplayEvents.sq:29`）、`chat_archives`（`ChatArchives.sq:41,66`）、`operators`（`Operators.sq:38,58`）；角色卡写 `operators` + `relationships`（`Relationships.sq:42`）。
  - 读：`ChatSessions.sq:19,29`、`ChatMessages.sq:24`、`ChatDisplayEvents.sq:18,23`、`ChatArchives.sq:29,60`、`Operators.sq:32`、`Relationships.sq:21`。
  - 索引：`chat_messages_session_timestamp_id`（`ChatMessages.sq:19-20`）、`idx_chat_display_events_session_order`（`ChatDisplayEvents.sq:9-10`）。
- **迁移版本号**：无新增迁移（v1 只读导入，不改 schema）。
- **v1 容器**（冻结，详见 `ios-port/golden/chat-import/README.md`）：
  - `manifest.json`：`format="rhodes-chat"`、`formatVersion=1`、`scope="conversation"`、`layout="single_payload"`、`pages=[]`、`recordCounts`、`files`（不含 manifest 自身）、`excludedCategories`。
  - `conversation.json`：`formatVersion/exportedAt/operator?/session/messages/displayEvents/chatArchives/chatHistorySegments/mediaIndex`。
  - `media/images/<sha256(content)[:24]>.<ext>`；`mediaId="image/<sha256(content)>"`。
- **备份/恢复中的表现**：
  - 聊天导出**不是** `rhodes-backup` 子集：T10 `BackupFileReader` 首道门禁（`BackupFileReader.kt:53-57`）会以「不支持的备份类型」拒绝——这是预期行为，验收含该断言（防止两种容器混淆）。二者共享 ZIP/manifest/sha256/路径安全/上限校验思路与媒体 ID 语义。
  - 角色卡不进备份（`OperatorPackage.kt:57 excludedCategories` 明确排除 chat_history/memories/…；T6 §1.1）；聊天导出同样不含记忆/动态/日记/知识库/关系/设置/密钥（`excludedCategories`）。
  - 密钥：聊天导出与角色卡均不扫描任何 settings 值，密钥零泄漏；角色卡 prompt 槽位只含 `operator_prompt_slot_*`（非密钥）。
  - 消息 id：导入**重分配**（契约 B14），`max_used_message_id` 由 `MessageIdAllocator.observeUsed` 同步抬高；不做原样 id 插入（与完整备份恢复「原样插入」语义不同，因为聊天导入是合并而非替换）。
  - 失败回滚：DB 单事务回滚 + 本次新增媒体删除；已存在的媒体文件不删除（内容寻址，幂等）。

## 验收（必须可自动判定）

金样：`ios-port/golden/chat-import/` 下 4 个包（§1 哈希冻结）：`chat-text-v1.zip`（8 消息/1 存档/1 历史片段）、`chat-image-v1.zip`（6 消息/2 媒体）、`chat-ai-json-v1.zip`（5 消息/1 媒体）、`chat-2000-v1.zip`（2000 消息/1000 displayEvents）。测试读取走 `expect/actual readTestResourceBytes`（commonTest + 平台源集二实现），与 `sync_golden.sh` 同步的 resources 绑定。

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | 金样包可被 `ChatExportReader` 全项校验通过（4/4） | commonTest：`validate(golden.zip)` 全 `Valid`；逐字段断言 manifest/会话/消息数/媒体数 | `./gradlew --no-daemon :shared:testDebugUnitTest --tests "*ChatImportGolden*"` + `:shared:iosSimulatorArm64Test` 同 filter |
| 2 | 金样往返一致（4 种夹具） | 导入后 DB：消息数/顺序/content/type/displayEvents 揭示序/存档 messagesJson 消息引用与 `conversation.json` 一致（id 已重映射）；媒体：消息 content 内 imageUri 指向新运行时 media ID 且 `MediaStore.readBytes` 字节 sha256 == mediaIndex.sha256 | 同上（`ChatImportGoldenTest`） |
| 3 | 重复导入零重复（幂等） | 连续导入同一包两次：第二次 `importedMessages==0`、`dedupedMessages==第一次条数`；会话数不增、消息总数不变、displayEvents 数不变、媒体文件数不增 | 同上（`acceptance_duplicateImportIsIdempotent`） |
| 4 | 坏 zip 无残留 | 4 变体：①缺 manifest ②formatVersion=2 ③`../` 不安全路径 ④篡改媒体字节（sha 不符）→ 均 `Invalid`；导入前后 DB 计数与媒体文件集不变 | 同上（`acceptance_invalidArchivesLeaveNoResidue`，变体由金样字节改写构造） |
| 5 | formatVersion 门禁 | `rhodes-chat`+formatVersion=2 / scope=full / conversation.formatVersion=2 分别返回明确 reason（含「不支持的聊天导出格式版本」等关键字） | 同上（`acceptance_formatVersionGate`） |
| 6 | 与 T10 读取器互斥 | `BackupFileReader.validate(chat-text-v1.zip)` → `Invalid`，reason 含「不支持的备份类型」 | 同上（`chatExportIsRejectedByFullBackupReader`） |
| 7 | 2000 条性能/规模 | 导入 2000 条：消息数 2000、id 严格递增且 > 导入前 MAX、无超时（< 10s 软线，记录实测） | 同上（`acceptance_2000Messages`） |
| 8 | 角色卡往返 | `OperatorCardWriter` 写 → `OperatorCardReader` 读：operator 22 字段/关系/头像字节/promptSlots 逐字段一致；sha256 清单校验通过 | `--tests "*OperatorCardTest*"` 双路径 |
| 9 | 角色卡坏包门禁 | 缺 manifest/篡改 sha/超 16 条目/不安全路径/format 不符 → 分别拒绝 | 同上 |
| 10 | 角色卡导入三模式 | NEW（新 id+名称去重后缀）/PERSONA_AND_APPEARANCE（仅人设字段）/FULL_REPLACE（全替换）：DB 断言 + 关系仅在目标角色存在时导入 + prompt 槽位回填 | 同上 |
| 11 | 旧版单角色 JSON 兼容 | `readCompatible(legacyJson)` 成功且等价于 zip 读取；非单角色/非 operators 类型明确拒绝 | 同上 |
| 12 | 分类恢复门禁 | `BackupSelectiveRestoreExecutor`：`chats=false` 时现有会话/消息不被删除或覆盖；`settings=false` 时设置不被改写（T10 现状会改写/插入） | `--tests "*BackupSelectiveRestore*"` 双路径 |
| 13 | 恢复后自检 | 金样恢复后 `BackupRestoreSelfCheck`：recordCounts 对账（1 角色/1 会话/7 消息/0 displayEvents）passed；篡改期望值（删 1 条消息后对账）→ mismatch 可判定 | 同上 |
| 14 | v1 单 payload 备份读取等价 | 用 v1 单 payload 布局（`layout="single_payload"` + `data/backup.json`）构造金样变体 → `BackupFileReader.validate` Valid 且与 v2 分页包 payload 层等价 | 同上 |
| 15 | Android 导出器可编译 + 入口可达 | `:app:assembleDebug` BUILD SUCCESSFUL；静态审查确认 writer 规则与金样生成器一致（字段序/清单/哈希/写入序） | `./gradlew --no-daemon :app:assembleDebug` |
| 16 | Android 324 测试不回归 | `:app:testDebugUnitTest` 0 失败（基线口径 324；当前实测 376，以既有报告为口径） | `./gradlew --no-daemon :app:testDebugUnitTest` |

- **金样 diff 允许差异白名单**：`createdAt/exportedAt/timestamp/backupId` 固定值 vs 真实导出的时间/UUID；zip 压缩级别/条目时间戳不参与校验；导入后消息 `id` 必然重分配（与源端 id 不同，断言用映射关系而非绝对值）；媒体落盘路径为 iOS 运行时 ID（非源端 `originalUri`）。
- **性能**：2000 条导入在 iPhone 17 模拟器/JVM 上 < 10s（记录实测值写入 handoff）；内存峰值不做硬门禁（记录）。
- **边界用例清单**：空消息会话（导出拒绝）、单条消息、坏 zip 4 变体、formatVersion 3 类、重复导入 ×2、导入中断（注入异常）、目标会话不存在（新建）/已存在（合并）/群聊会话、角色缺失（插入）/已存在（保留）、2000 条、媒体缺失字节（拒绝）、媒体写入失败（回退+告警）。

## 风险与开放问题

1. **无 Android oracle 的合并/去重语义（中）**：T6 已识别（1.0-1.5 代理日，从零设计）。本卡用「确定性会话匹配 + 内容哈希去重键 + 三源 max id 重分配」收敛，风险集中在「同毫秒同发送者同内容」误合并与「群聊合并不足」。**本卡 v1 只做私聊确定性合并 + 群聊按导出 session.id 精确命中；群聊按成员集合的模糊匹配列为二期**（见问题 1）。
2. **「完整备份恢复补齐」范围界定（请求 lead 裁决）**：本卡 §D 界定为「分类选择门禁 + v1 分页读取覆盖 + 恢复后自检」；**全类目字段级重放（记忆/锚点/动态/评论/日记/知识库/派遣/礼物/共同经历/存档等的完整字段恢复）不在本卡**。理由：T10 `BackupWireModels.kt` 对这些类目是校验级占位模型（`BackupDiaryWire`/`BackupMemoryItemWire` 为空类，`BackupMemoryWire` 仅 10 字段），补齐需新增完整 wire 模型 + 新全量执行器（可复用 shared 模型 @Serializable 类），且需覆盖级金样（T10 金样只有 1 角色/1 会话/7 消息），估计另需 1.5-2 代理日。**请 lead 裁决：接受本卡界定（全类目重放单列 T31），或要求纳入本卡（则本卡工期 +1.5-2 天、金样需扩为全类目备份包）。**
3. **chat 导出 mediaId 内容派生 vs T17 裁决（低）**：T17 对 `rhodes-backup` 保留「路径派生」语义；本格式按 T6 裁决用内容派生（`image/<sha256>`）。两者不冲突（不同容器），但需在实现注释中显式说明，避免后续误改 `BackupMediaCollector` 公式。
4. **角色补齐不覆盖已有角色（低-中）**：v1 保守合并（已有角色保留），可能造成「导入后头像/人设与源端不一致」。替代方案：默认覆盖并提供 UI 选择——本卡选保守，理由是没有 Android 聊天导入 oracle，覆盖风险更高。
5. **去重键误合并（低）**：同毫秒/同发送者/同内容/同 type 的两条消息会合并为 1 条。概率极低（真实用户消息毫秒级不重复），且 v1 接受；如 lead 要求，可把 `segmentGroup/narration` 并入键（成本 +0.1 天）。
6. **Android 导出器跨端验证（中）**：本机无法运行 Android App 做「真机导出 → iOS 导入」端到端（无 instrumentation 环境接入本任务），验收用「生成器金样（同格式同规则）+ writer 静态等价 + `assembleDebug` 编译」。**开放问题 2**：是否接受该口径；若要求真机端到端，需 lead 提供仪器测试环境（另 +0.5 天）。
7. **`chatArchives.stateJson` 内部 id 形态未知（低）**：递归替换 `id`/`messageId` 数字键；若 stateJson 内嵌其他 id 结构（如 `turnState` 内字段名不同）可能残留旧 id。风险记入实现 handoff（仅影响存档回放上下文，不影响消息/显示顺序）。
8. **T10/T17 并行写域**：T30 写域 `shared/src/commonMain/.../backup` 仅新增文件（不改 T10 现有文件）；`commonTest` 与 T28 共享目录但文件不重叠（本卡新增 `backup/chatimport/` 子包测试文件）。构建前 `pgrep -fl gradle` 排队（T28/T29 并行）。

## 评审结论

- reviewer：**PASS-WITH-FIXES（2026-11-23）**——0 个 P1，1 个 P2 + 3 个 P3，不阻塞实现。已回填：
  1. [P2] §D 与本节回填 lead 三点裁决与 reviewer 结论（本文件）；
  2. [P3] 行号漂移修正：`MessageIdAllocator.allocate` → `:48-60`、`observeUsed` → `:68-72`；`BackupRestoreCoordinator` 取消/清理引用 → `:107-111,126-131,137-141`；
  3. [P3] 其余非阻塞项在 handoff 记录。
- lead：**批准（2026-11-23）**——三点裁决：1) **接受范围界定**：本卡「完整备份恢复补齐」= 分类选择门禁 + 分页/v1 读取覆盖 + 恢复后自检；全类目字段级重放（记忆/动态/日记/知识库 wire 模型+执行器+金样）**单列 T31**。2) **接受跨端验证口径**：python 金样（同格式同规则）+ writer 静态等价 + `:app:assembleDebug` 编译三件套；真机端到端留 Phase 7 设备 Gate。3) **接受群聊合并方案**：v1 私聊确定性合并（`session_<operatorId>`）+ 群聊按导出 `session.id` 精确命中；成员集合模糊匹配留二期。实现已按此开工。

## 附：实现文件落点（供批准后执行）

| 侧 | 新增/修改 | 文件 |
|---|---|---|
| Android | 新增 | `app/.../data/backup/ChatExportService.kt`、`ChatExportModels.kt` |
| Android | 修改（最小入口） | `app/.../ui/settings/BackupRestoreScreen.kt`（镜像 `:235-260` 新增 1 个导出 launcher + 1 个 `BackupSection`） |
| shared | 新增 | `shared/.../backup/chatimport/ChatExportWireModels.kt`（conversation 载荷 + 结果/阶段类型）、`ChatExportReader.kt`、`ChatImportMediaSink.kt`、`ChatMergeRestorer.kt`、`ChatImportRepository.kt` |
| shared | 新增 | `shared/.../backup/operatorcard/OperatorCardWire.kt`、`OperatorCardReader.kt`、`OperatorCardWriter.kt`、`OperatorCardService.kt`（importer/exporter） |
| shared | 新增 | `shared/.../backup/BackupSelectiveRestoreExecutor.kt`、`BackupRestoreSelfCheck.kt`、`BackupRestoreCoordinatorEx.kt` |
| shared | 新增（测试） | `shared/src/commonTest/.../backup/chatimport/*`（expected/actual 资源读取 + 金样/幂等/坏包/门禁/2000 用例 + 平台子类）、`.../operatorcard/*`、`.../backup/BackupSelectiveRestoreTest.kt`、平台源集 `androidUnitTest`/`iosTest` 子类（各 1-2 文件） |
| golden | 新增 | `ios-port/golden/chat-import/{make_chat_exports.py,README.md,sync_golden.sh,chat-*-v1.zip}`（已完成）+ 测试 resources 同步（`sync_golden.sh --sync-only`） |
| handoff | 新增 | `ios-port/handoffs/impl-a5-import.md` |
