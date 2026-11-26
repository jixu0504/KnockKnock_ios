# Handoff：聊天记录导入可行性（T6）

- 代理：chat-import-feas；日期：2026-11-22；基线：RhodesLink @ 3642d2c（v1.14.27-android-baseline）
- 交付：`ios-port/decisions/chat-import-可行性.md`（完整结论）；本文件为方法、证据索引、开放问题。

## 方法

纯静态源码分析（read/grep/glob，未构建、未改 RhodesLink/ 仓库）：

1. 定位全部导出/导入相关代码：`app/src/main/java/com/rhodes/privatechat/data/backup/`（20 个文件，共 2205 行）为主力；UI 侧 `ui/settings/BackupRestoreScreen.kt`、`ui/chat/{ChatExportDialog,ChatShareContent}.kt`、`ui/group/GroupDetailScreen.kt`；旧管线 `viewmodel/DataViewModel.kt`。
2. 逐层读透：容器/清单（BackupFileWriter/Reader/Models）→ 载荷（ExportPayload）→ 快照（BackupSnapshotBuilder）→ 媒体（Collector/Stager/Rewriter）→ 恢复（RestoreDatabaseExecutor/Coordinator）→ 角色卡（OperatorPackage*/Service）。
3. 交叉验证 ID 方案与媒体引用：`SessionRepository.kt:42`（私聊会话 `session_<operatorId>` 确定性）、`MessageRepository.kt:296-299`（消息 ID = max+1，跨设备碰撞）、`ChatViewModel.kt:1859-1862` + `MessageParser.kt:137-158`（图片消息 JSON `{"imageUri","caption","visionSummary"}`）。
4. 引用已有盘点（未重复）：`ios-port/audit/paths.md:86-91`（媒体 ID 化 P0 结论）、`audit/fields.md`（备份字段全表）、`checklist/功能对照清单-v1.md:91`（备份/恢复条目）。
5. 对齐计划书决策规则：A5（计划书:64-73）、Gate 0/2（:167、:175）、架构原则（:131「媒体只存相对 ID」）。

## 主要证据索引（file:line）

| 主题 | 位置 |
|---|---|
| 角色卡容器格式 v1 + 校验 + 兼容旧 JSON | `app/.../data/backup/OperatorPackage.kt:37-66,68-119,136-138` |
| 角色卡导出/导入服务与三模式 | `app/.../data/backup/OperatorPackageService.kt:19,29-53,58-...` |
| 可读聊天导出（txt/md，人读） | `app/.../data/backup/ReadableChatExporter.kt:14-38`；UI `BackupRestoreScreen.kt:236-260` |
| 分享图片导出（PNG，8 条） | `app/.../ui/chat/ChatShareContent.kt:61,99,128`；`GroupDetailScreen.kt:470`；`ChatExportDialog.kt:45` |
| 旧版单会话 JSON（无媒体） | `app/.../viewmodel/DataViewModel.kt:142-152` |
| 备份容器/分页/manifest | `app/.../data/backup/BackupFileWriter.kt:18-24,48-57,60-95`；`BackupModels.kt:36-51,86-98` |
| 会话/消息导出字段 | `app/.../data/ExportPayload.kt:86-100,103-113`；运行时 `shared/.../model/ChatMessage.kt:6-22` |
| 媒体收集/白名单/引用扫描 | `app/.../data/backup/BackupMediaCollector.kt:17-36,43-57,68-72,86` |
| 恢复 = 删+插（无合并语义） | `app/.../data/backup/RestoreDatabaseExecutor.kt:8-176`（chats 删除 :47-55） |
| 恢复编排/回滚/安全备份 | `app/.../data/backup/BackupRestoreCoordinator.kt:58-...`；`BackupMediaStager.kt:8-32` |
| 路径→路径重写（非 ID 化） | `app/.../data/backup/BackupRestorePayloadRewriter.kt:3-42,48-63` |
| 读取上限与校验 | `app/.../data/backup/BackupFileReader.kt:12-16` |
| 私聊会话 ID 确定性 / 消息 ID 分配 | `shared/.../data/SessionRepository.kt:42`；`shared/.../data/MessageRepository.kt:296-299` |
| 图片消息 JSON 格式 | `app/.../viewmodel/ChatViewModel.kt:1859-1862`；`ui/chat/util/MessageParser.kt:137-158` |
| 媒体 ID 化 P0 结论（复用） | `ios-port/audit/paths.md:86-91` |

## 结论摘要

**做**（纳入一期）。总投入 4.0-6.5 代理日（中位 ~5.5）：Android 导出器 1.0-1.5（≈90% 复用备份管线）+ iOS 导入 2.5-4.0（与 A5 必做备份恢复共享 60-70%；唯一新逻辑为合并/去重 1.0-1.5）+ 测试 0.5-1.0 ≤ 8 阈值。

## 开放问题（需 lead / 人类裁决）

1. **Android 仓库可改性**（最高优先）：Phase 0 约定 RhodesLink 只读；导出器需在 Phase 1/2 改 Android 并发版。若 Android 端冻结不再发版，iOS 导入器将无真实输入，本项应降级二期候选。
2. **v1 去重/幂等键**：无唯一约束可依赖。建议 `(sessionId, timestamp, senderId, content 归一化哈希)`；需领域代理确认与「重发同文本消息」场景不冲突。
3. **v1 范围**：仅私聊 vs 私聊+群聊（群聊 `group_<id>` + members 匹配）。建议两者都含（`SessionExport.members` 已在字段内），收窄可再省 ~0.5 天。
4. **archives/historySegments 是否入 v1**：建议 v1 排除（消息 ID 重映射涟漪最小化；完整历史走 A5 必做全量备份），存档仅随全量备份迁移。
5. **导入 UI 落点**：建议「设置→数据管理」（与 Android 一致），而非聊天页内。
6. **媒体 ID 方案确认**：建议导出器用内容哈希 `image/<sha256(content)>`（弃用 Android 路径哈希 `local-image/<sha24>`）；与 paths.md P0「运行时层 ID 化」口径一致，需 domain-backup-data 代理确认。
