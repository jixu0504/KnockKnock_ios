# 聊天记录导入可行性验证（T6 裁决输入）

- 日期：2026-11-22
- 基线：RhodesLink Android @ v1.14.27-android-baseline（3642d2c），纯源码静态分析，未构建
- 决策规则（计划书 A5 修订，计划书:69-73）：`RhodesLinkChatExport.zip` = `manifest.json` + `conversation.json` + `media/`（相对 mediaId）；预计总投入 ≤8 代理日（含 Android 侧结构化导出器）则纳入一期。
- 写域声明：本文件与 handoffs 仅在 ios-port/{decisions,handoffs}；RhodesLink/ 仓库未做任何改动。

---

## 一、现状盘点：Android 1.14.27 现有导出/导入能力

### 1.1 角色卡导出/导入（已有，单向可用）

| 能力 | 位置 | 说明 |
|---|---|---|
| 导出服务 | `app/src/main/java/com/rhodes/privatechat/data/backup/OperatorPackageService.kt:29-53` | `exportCard()`：Operator + 关系 + 头像 + 提示词槽位（`OperatorPromptSlots`） |
| ZIP 写入 | `app/src/main/java/com/rhodes/privatechat/data/backup/OperatorPackage.kt:37-66` | 容器格式 `format="rhodes-operator"` v1、`scope="operator_card"`；条目 `data/operator.json` + `media/avatar.<ext>` + `manifest.json`；每条目 SHA-256；manifest `files:[{path,size,sha256}]`；排除 `chat_history/memories/...`（:57） |
| 导入解析 | `app/src/main/java/com/rhodes/privatechat/data/backup/OperatorPackage.kt:68-119` | `OperatorPackageReader.read()` 全量校验（重复路径、sha256、manifest↔文件一致）；`readCompatible()`（:103-119）兼容旧版单角色 JSON（`ExportHelper.importFromString`） |
| 导入模式 | `app/src/main/java/com/rhodes/privatechat/data/backup/OperatorPackageService.kt:19,58-...` | `NEW` / `PERSONA_AND_APPEARANCE` / `FULL_REPLACE` 三模式，含同名去重、关系映射 |
| 资源上限 | `OperatorPackage.kt:136-138` | ≤16 条目 / 单文件 ≤20MB / 总 ≤25MB |
| UI 入口 | `app/src/main/java/com/rhodes/privatechat/ui/settings/BackupRestoreScreen.kt:203`（导出）、`:225`（校验）、`:558`/`:578`（导入） | 设置→数据管理→备份/恢复页内 |

**结论**：角色卡不含聊天记录（`excludedCategories` 明确排除），不能作为聊天导入载体，但其 ZIP+manifest+SHA256+安全路径校验的骨架可整体复用。

### 1.2 聊天记录导出（已有，但全部不可机器导入）

| 形式 | 位置 | 说明 |
|---|---|---|
| 可读文本/Markdown | `app/src/main/java/com/rhodes/privatechat/data/backup/ReadableChatExporter.kt:14-34`；UI `BackupRestoreScreen.kt:236-260` | `ai_json` 段拼接可见文本（:41-48），剥离模板变量 `{{...}}`（:38）；纯人读，无媒体、无结构 |
| 分享图片 | `app/src/main/java/com/rhodes/privatechat/ui/chat/ChatShareContent.kt:61,99,128-...`；群聊入口 `app/src/main/java/com/rhodes/privatechat/ui/group/GroupDetailScreen.kt:470` | `drawShareBitmap()` 渲染 PNG；`ChatExportDialog.kt:45` 只取最近 8 条（该 Dialog 本身无调用点，疑似死代码） |
| 旧版 JSON 单会话 | `app/src/main/java/com/rhodes/privatechat/viewmodel/DataViewModel.kt:142-152` | `ExportPayload(type="chat", session, messages)` 经 `ExportHelper.exportToFile` 写 JSON；**无媒体**、无清单、无校验，且 Android 侧无对应「聊天导入」入口 |

**结论**：三种现有聊天导出均不能满足「跨端结构化导入」需求；Android 侧没有聊天记录导入能力（全库恢复除外）。

### 1.3 完整备份/恢复中的会话数据结构（可复用的主力管线）

**容器**：ZIP `format="rhodes-backup"` v2 —— `BackupFileWriter.kt:18-24`（常量）、`:48-57`（分页 `data/pages/<category>/NNNNNN.json`，每页 ≤16MB）、`:60-79`（媒体流式写入 `media/...`）、`:81-95`（manifest）。

**清单**：`BackupModels.kt:36-51` —— `format/formatVersion(2)/scope/appVersion/schemaVersion/createdAt/backupId/mediaIncluded/recordCounts/files[{path,size,sha256}]/excludedCategories/layout/pages`。

**载荷**：`BackupModels.kt:86-98` `BackupPayload` = `ExportPayload`（`app/src/main/java/com/rhodes/privatechat/data/ExportPayload.kt:18-39`：operators/relationships/sessions/messages/memories/anchors/moments/.../settings）+ `displayEvents/chatArchives/chatHistorySegments/giftRecords/dispatchRecords/mahjongSave/sharedExperiences/sharedExperienceParticipants/media`。

**会话与消息字段**（导入格式的核心）：
- `SessionExport`（`ExportPayload.kt:86-100`）：`id, operatorId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers`
- `MessageExport`（`ExportPayload.kt:103-113`）：`id(Long), sessionId, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe`
- 运行时模型 `ChatMessage`（`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/model/ChatMessage.kt:6-22`）与导出字段一一对应。
- 消息 `type` 枚举（实际使用）：`text / ai_json / image / narration / system / gift_hidden / gift_reply_failed / send_failed / ...`（`ChatViewModel.kt` 全文件引用）。
- **图片消息内容为 JSON**：`{"imageUri","caption","visionSummary","status"}` —— 写入 `app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:1859-1862`；解析 `app/src/main/java/com/rhodes/privatechat/ui/chat/util/MessageParser.kt:137-158`。`ai_json` 的 segments 内也可嵌 `imageUri`（礼物图）。

**媒体处理**：
- 收集：`BackupMediaCollector.kt:17-36` —— 只收 `filesDir/{images,chat_images,restored_media}`（:68-69）内自有文件；`mediaId="local-image/<sha256(canonicalPath) 前 24 hex>"`（:71-72）；`archivePath="media/images/<stableId>.<ext>"`；`originalUri=file://…`。
- 引用扫描：`:43-57` 扫 operator/session avatarUri、giftRecords.imageUri、**messages.content 内嵌 `"imageUri":"..."`**（正则 `:86`）、settings（user_avatar_uri、support_conversation）。
- 落盘：`BackupMediaStager.kt:8-32` 按 manifest 解到 `filesDir/restored_media/<backupId>`，mediaId→File 映射，缺文件即整体失败。
- 恢复重写：`BackupRestorePayloadRewriter.kt:3-42` 按 originalUri/canonical/`androidPrivateFilesKey`（:48-63，硬编码 `/data/data/`、`/data/user/0/`、`/files/`）把旧绝对路径换成恢复后路径 —— **路径→路径重写，而非 ID 化**（见 3.2）。

**写库（恢复）**：
- `RestoreDatabaseExecutor.kt:8-176`：先按选择删除类目（chats 删除在 :47-55：replyTurns/displayEvents/archives/historySegments/messages/sessions/groups），再逐表插入，全部包在 `repository.runRestoreTransaction`（:30）单事务中；**没有合并语义，只有「删+插」**。
- 编排：`BackupRestoreCoordinator.kt:58-...` —— 校验 → 停后台任务 → 恢复前安全备份 → 媒体暂存 → 数据库恢复 → 重建运行时；失败可回滚（安全备份 + 事务）。
- 校验：`BackupFileReader.kt:12-16`（≤1000 条目 / 单条目 ≤100MB / 总 ≤500MB）、安全路径、重复路径、sha256、manifest↔文件一致。

**ID 方案**（合并/去重的关键事实）：
- 私聊会话 ID **确定性**：`session_<operatorId>`（`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/SessionRepository.kt:42`）；群聊为 `group_<id>`。
- 消息 ID：`max(id)+1` 顺序分配（`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/MessageRepository.kt:296-299`）——**跨设备必然碰撞**，合并导入必须重分配并重映射引用。
- 消息 ID 被引用处：`displayEvents.messageId`、`chatArchives.messagesJson`、`chatHistorySegments.messagesJson`、`replyTurns`（恢复插入见 `RestoreDatabaseExecutor.kt:102-104,163-164`）。

---

## 二、差距分析：实现 RhodesLinkChatExport 约定格式

### 2.1 Android 侧需新增（结构化导出器）

现状没有任何「单会话 + 媒体 + 机器可读清单」导出。新增内容（全部有现成积木）：

1. **新建导出服务**（约 1 个新文件，150-250 行，建议 `app/.../data/backup/ChatExportService.kt` + 模型文件 `ChatExportModels.kt`）：
   - 快照：复用 `BackupSnapshotBuilder` 的仓库读取（`getAllSessionsSync` / `getAllMessagesForBackup`，`BackupSnapshotBuilder.kt:31-33`），按 sessionId 过滤为单会话。
   - conversation.json：直接投影 `SessionExport` + `MessageExport`（`ExportPayload.kt:86-113`），避免新 schema 漂移；`type="image"`/`ai_json` 的 content 原样保留（JSON 字符串），仅重写 mediaId。
   - 媒体收集：复用 `BackupMediaCollector` 的 owned-file 白名单与 `imageUriPattern`（`BackupMediaCollector.kt:59-69,86`）扫描本会话消息；**mediaId 改为内容 SHA-256**（`image/<sha256>`）——不能用现有 `local-image/<sha256(path)>`（路径哈希在 iOS 无意义，见 3.2）。
   - 打包：复用 `BackupFileWriter` 的 ZIP+SHA256+manifest 模式（`BackupFileWriter.kt:46-97`），manifest 用同一 `BackupManifest` 结构（`format="rhodes-chat"` v1、`scope="conversation"`）。
   - 可空字段/异常消息兜底：`readCompatible`（`OperatorPackage.kt:103-119`）的兼容思想照搬。
2. **UI 入口**（约 60-100 行）：`BackupRestoreScreen.kt` 内新增「导出（跨平台）」行 + `CreateDocument("application/zip")` launcher + 进度/错误弹窗——完全镜像现有可读导出入口 `BackupRestoreScreen.kt:236-260`。
3. **金样与自检**（约 0.25-0.5 天）：构造 文本/图片/ai_json 内嵌图/2000 条 四类夹具会话，导出自检 + 入库为 iOS 侧金样。

**估算：0.5-1.0 代理日**（代码），加金样 0.25-0.5 → **Android 合计 1.0-1.5 代理日**。≈90% 逻辑复用现有备份管线，无需新依赖、无需 schema 迁移。

### 2.2 iOS 侧需新增（导入）

前提：A5 必做项（Android 完整备份 → iOS 恢复，100% 金样）已在 Phase 2 `domain-backup-data` 工作流内（计划书:167、:175「完整备份/恢复/导入导出」）。聊天导入是同一管线的**子集 + 合并语义**：

1. **解析 + 转换**（≈0.5 天）：读 zip（复用 rhodes-backup 读取器的安全路径/sha256/上限校验模式）→ 校验 manifest v1 → conversation.json 反序列化为与 `BackupPayload` 子集同构的内部类型 → 走同一恢复执行器。
2. **媒体落盘 + 引用改写**（≈0.5 天，与必做项高度共享）：`BackupMediaStager` 等价物把 `media/<mediaId>` 写入 iOS MediaStore（相对 ID，计划书:131「媒体只存相对 ID」）；消息 content 内 `imageUri` 改写为 mediaId——这是 paths.md P0「运行时层 ID 化」的一部分，边际成本低。
3. **写库 + 合并/去重**（≈1.0-1.5 天，**全新逻辑，Android 无 oracle**）：
   - 会话匹配：私聊 `sessionId = "session_<operatorId>"` 确定性（`SessionRepository.kt:42`）→ 已存在则合并，否则新建；群聊按 operatorId 集合匹配。
   - 消息 ID 重分配 + 引用重映射（displayEvents/archives/historySegments 内 messagesJson），因为 Android 恢复只有删+插（`RestoreDatabaseExecutor.kt:47-55`），无现成参考。
   - 幂等去重键需定义（见开放问题 2）。
   - 单事务写入（对应 `runRestoreTransaction`），失败整体回滚 + 媒体临时文件清理。
4. **UI 入口 + 验收**（≈0.5-1.0 天）：设置→数据管理「导入聊天记录」+ 错误提示（对照 Android 错误文案风格）；模拟器/真机金样往返 + 重导入幂等验证。

**估算：iOS 2.5-4.0 代理日**（其中 1.0-1.5 为无 oracle 的合并/去重逻辑）。

### 2.3 合计估算

| 侧 | 内容 | 代理日 |
|---|---|---|
| Android | 导出服务 + UI 入口 + 金样夹具 | 1.0-1.5 |
| iOS | 解析/转换 + 媒体 + 合并写库 + UI + 验证 | 2.5-4.0 |
| 测试 | commonTest 往返用例 + 双端金样比对 | 0.5-1.0 |
| **总计** | | **4.0-6.5（取中位 ~5.5）** |

≤8 代理日阈值满足。若合并/去重规则被裁决为「v1 仅支持导入到新会话/仅私聊」，总投入可压至 ~4.0-4.5。

---

## 三、风险评估

### 3.1 与完整备份恢复的关系（低-中）

- 依赖：iOS 侧导入器应**先有** A5 必做项的 rhodes-backup 恢复管线（解析/校验/暂存/事务/自检），聊天导入作为其上的增量。若两个格式的解析器分叉实现，成本翻倍；对策：conversation.json 直接投影 `SessionExport`/`MessageExport`（§2.1），iOS 侧用同一内部 payload 类型。
- 语义差异：rhodes-backup 恢复 = 全删+全插；聊天导入 = 追加/合并。后者没有 Android oracle，是唯一「从零设计」的部分（iOS 1.0-1.5 天）。
- 顺序：Android 导出器先行、iOS 只导入 Android 能生成的格式（计划书:73）——与估算顺序一致。

### 3.2 媒体 ID 化依赖（中，已有 P0 结论）

- paths.md:86-91 结论：备份层已有 mediaId 抽象，但**运行时层（DB/settings/message JSON）存 `file://` 绝对路径**（`Operator.avatarUri`、`Session.avatarUri`、messages.content 内嵌 imageUri、`bg_*` 等），iOS 必须 ID 化（P0）。
- 对聊天导入的直接影响：
  - Android 现有 `mediaId = "local-image/<sha256(path)>"`（`BackupMediaCollector.kt:71-72`）**基于路径哈希，iOS 无意义**；导出器须改为内容哈希或按约定定义 `image/<sha256(content)>`。
  - `BackupRestorePayloadRewriter` 的路径三重映射（含硬编码 `/data/data/` 特征串，:48-63）不能移植；iOS 用「mediaId 直映射」替代（paths.md:89 已确认 P0）。
  - 好消息：iOS 运行时按计划书:131「媒体只存相对 ID」从零构建，导入时 mediaId 原样落库，无反向改写。
- 残余风险：`ai_json` segments 内礼物图、`support_conversation` 设置内嵌 URI 是否纳入 v1 扫描范围 —— 建议 v1 与 `BackupMediaCollector.kt:43-57` 同口径（含 messages.content 全量正则扫描）。

### 3.3 双端兼容矩阵

| 数据流 | 支持 | 依据/状态 |
|---|---|---|
| Android 完整备份 v2 → iOS 恢复 | ✅ 必做（A5） | 计划书:67；Gate 0/2/发布 Gate 验收 |
| Android `RhodesLinkChatExport` v1 → iOS 导入 | ✅ 本决策项 | 导出器需在 Android 1.14.27 之后版本新增 |
| Android 可读 txt/md 导出 | ❌ 无导入（人读） | `ReadableChatExporter.kt` |
| Android 旧版单会话 JSON | ❌ 非目标（可留作兼容，不建议 v1） | `DataViewModel.kt:142-152` |
| Android 角色卡（rhodes-operator v1） | ✅ 已有，iOS 按 A5「角色卡导入导出」移植（Phase 2） | `OperatorPackage.kt` |
| iOS → Android / iOS → iOS 聊天导入 | ❌ 范围外（单向 Android→iOS） | 计划书:73 |

### 3.4 其他风险

1. **Android 仓库可改性**（中）：Phase 0 约定 RhodesLink 只读；导出器需在 Phase 1/2 修改 Android 并出包，否则 iOS 导入器无真实输入（只能靠人工构造金样）。若 Android 端冻结不发布，价值骤降 → 需 lead 裁决（开放问题 1）。
2. **ID 重映射涟漪**（中）：消息 ID 被 displayEvents/archives/historySegments 引用；合并导入必须重分配 ID 并重写引用，漏一处即显示错乱。对策：v1 排除 archives（开放问题 4）+ 金样断言 displayEvents 揭示顺序。
3. **重复导入幂等**（低-中）：无唯一约束可依赖，需定义去重键；建议 (sessionId, timestamp, senderId, content 归一化哈希)。
4. **格式版本漂移**（低）：manifest.format/formatVersion 门禁 + `ignoreUnknownKeys` 前向兼容。
5. **估算余量**：总投入取中位 5.5 天，距 8 天阈值余量约 2.5 天，可吸收「群聊合并」「去重规则讨论」的膨胀；若裁决收窄范围（仅私聊/仅新会话）则更稳。

---

## 四、结论：**做** —— 纳入一期

- 总投入估算 **4.0-6.5 代理日**（中位 ~5.5）≤ 8 代理日阈值（计划书 A5 决策规则）。
- 关键依据：Android 侧 90% 复用现有备份管线（导出服务 ≈ 新增 1-2 文件 + UI 行）；iOS 侧 60-70% 与 A5 必做备份恢复共享；唯一新逻辑是合并/去重（1.0-1.5 天）。
- 前置条件（若不满足则降级为二期候选）：① Android 仓库在 Phase 1 允许修改并发版；② A5 必做项备份恢复先于或并行于本项落地。

### 若做：实施方案要点

**阶段归属**：Android 导出器 + 格式规格冻结 → Phase 1（domain-backup-data，与备份模型同波）；iOS 导入器 → Phase 2（完整备份/恢复同波，Gate 2 内验收）；UI 入口 → Phase 2/4。

1. **规格卡先行**（Phase 1 初）：`RhodesLinkChatExport` v1 规格卡 —— manifest（复用 `BackupManifest` 形状，`format="rhodes-chat"`, `formatVersion=1`, `scope="conversation"`）；conversation.json = `{session: SessionExport, messages: [MessageExport], mediaIndex: [{mediaId, mimeType, size, sha256}]}`；mediaId = `image/<sha256(content)>`；reviewer 批准后冻结入库（金样）。
2. **Android 关键文件**（新建）：`app/.../data/backup/ChatExportService.kt`、`ChatExportModels.kt`；（修改）`BackupRestoreScreen.kt` 加入口；（复用）`BackupMediaCollector` owned-file 白名单、`BackupFileWriter` zip+sha256、`BackupFileReader` 校验模式。
3. **iOS 关键文件**（按 KMP 分层，Phase 2 落位）：shared/backup 下 `ChatExportReader`（解析+校验+转换）与 `ChatMergeRestorer`（合并写库+ID 重映射+事务回滚）；iosMain 媒体暂存（MediaStore 相对 ID）；UI 入口 `设置→数据管理`。
4. **验收标准**：
   - 金样往返：4 类夹具（纯文本 / 图片消息 / ai_json 内嵌图 / 2000 条长会话）Android 导出 → iOS 导入，消息数、顺序、content、mediaId、displayEvents 揭示顺序与源端一致；
   - 合并正确：私聊按 `session_<operatorId>` 匹配追加，群聊按成员匹配；重复导入零重复（幂等）；
   - 回滚正确：注入坏 zip（sha256 不匹配/缺媒体/超大条目）导入失败后 DB 与媒体目录无残留；
   - 兼容：manifest.formatVersion 不符时明确报错（对照 Android 错误文案风格）；
   - Android 原功能不回归（Gate 1 回归）。
