# SQLDelight 迁移清单（基线 v1.14.27-android-baseline @ 3642d2c）

- 位置：`shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/*.sqm`
- 数量：**23 个**（1.sqm … 23.sqm），与可行性报告预期一致。
- 表数：`.sq` 文件中 `CREATE TABLE` 共 **28**（24 个 .sq 文件），与预期一致。
- 注意（与报告的差异说明）：
  - 迁移 18 建的表 `data_repair_runs` 未进入任何 .sq 文件，因此**运行时数据库实际为 29 张表**；
    报告口径「28 张」按 .sq 的 CREATE TABLE 计数成立。
  - 迁移 11 删除 `world_events` 表（历史上由早期迁移创建，现已不存在）。
  - 迁移 4 / 6 / 9 为 no-op（`SELECT 1;` / `SELECT 1 WHERE 0;`），疑似已撤销的历史迁移占位。

## 迁移明细

| 编号 | 操作 | 涉及表 | 简述 |
|---|---|---|---|
| 1 | ALTER TABLE | operators | 加列 `activityLevel REAL NOT NULL DEFAULT 0.5`（活跃度） |
| 2 | ALTER TABLE | operators | 加列 `gender TEXT NOT NULL DEFAULT ''` |
| 3 | ALTER TABLE ×6 | memory_anchors | 加列 source / sourceName / sourceActor / sourceTarget / importance / knownFrom |
| 4 | no-op | — | `SELECT 1;`（空迁移） |
| 5 | UPDATE + 索引 | diaries | 同 operator+date 去重（date 拼 id）+ 唯一索引 `idx_diaries_operator_date` |
| 6 | no-op | — | `SELECT 1;`（空迁移） |
| 7 | ALTER TABLE | operators | 加列 `memoryInjection TEXT NOT NULL DEFAULT ''` |
| 8 | ALTER TABLE ×3 | operators | 加列 voiceName / voiceSpeed / voicePitch（TTS 音色） |
| 9 | no-op | — | `SELECT 1 WHERE 0;`（空迁移） |
| 10 | DROP INDEX + ALTER + 索引 | diaries | 加列 `version`，索引重建为 `idx_diaries_operator_date_version` |
| 11 | DROP TABLE | world_events | 删除 world_events 表 |
| 12 | 索引 ×5 | moments, moment_comments, moment_likes | 朋友圈查询索引 + likes 唯一约束 `uq_moment_likes_moment_operator` |
| 13 | CREATE TABLE + 索引 | chat_display_events | 建聊天展示事件表 + 会话顺序索引 |
| 14 | CREATE TABLE ×3 | relationships, shared_experiences, shared_experience_participants | 关系表 + 共同经历两张表 |
| 15 | CREATE TABLE ×2 + 索引 | chat_archives, chat_history_segments | 存档摘要 + 分段历史表及索引 |
| 16 | CREATE TABLE | gift_records | 礼物记录表 |
| 17 | 唯一索引 | moment_likes | 重建唯一约束（幂等修复） |
| 18 | CREATE TABLE | data_repair_runs | 数据修复运行记录表（**仅存在于迁移中，不在 .sq**） |
| 19 | CREATE TABLE ×3 + 索引 | knowledge_bases, knowledge_base_chunks, operator_knowledge_bases | 知识库三表 + 按书索引 |
| 20 | ALTER TABLE | knowledge_bases | 加列 `sourceType TEXT NOT NULL DEFAULT 'user'` |
| 21 | 索引 ×4 | chat_messages, relationships, vector_memories | 消息时间索引、关系唯一索引、向量记忆召回索引 |
| 22 | CREATE TABLE + 索引 ×3 | reply_turns | 回复轮次表（手动/群自动唯一约束 + claimable 索引） |
| 23 | 索引 | memory_items | 备份导出索引 `memory_items_backup_created_at` |

## .sq 表清单（28，按文件）

| 文件 | 表 |
|---|---|
| ChatArchives.sq | chat_archives, chat_history_segments |
| ChatDisplayEvents.sq | chat_display_events |
| ChatMessages.sq | chat_messages |
| ChatSessions.sq | chat_sessions |
| DailyDeliveries.sq | daily_deliveries |
| Diaries.sq | diaries |
| DispatchRecords.sq | dispatch_records |
| GiftRecords.sq | gift_records |
| KnowledgeBases.sq | knowledge_bases, knowledge_base_chunks, operator_knowledge_bases |
| MahjongSaves.sq | mahjong_saves |
| Memories.sq | memories |
| MemoryAnchors.sq | memory_anchors |
| MemoryBatches.sq | memory_batches |
| MemoryItems.sq | memory_items |
| MemoryLinks.sq | memory_links |
| MemorySourceQueue.sq | memory_source_queue |
| MomentComments.sq | moment_comments |
| MomentLikes.sq | moment_likes |
| Moments.sq | moments |
| Operators.sq | operators |
| Relationships.sq | relationships |
| ReplyTurns.sq | reply_turns |
| SharedExperiences.sq | shared_experiences, shared_experience_participants |
| VectorMemories.sq | vector_memories |

## schema/ 归档

`golden/db/schema/` 下 24 个 `.sq` 文件全部取自
`git show v1.14.27-android-baseline:shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/<file>.sq`，
文件名与仓库一致，保留单层目录（仓库内 .sq 与 .sqm 同目录、无更深层级）。
