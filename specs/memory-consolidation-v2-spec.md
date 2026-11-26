# 记忆整合 v2 规格卡（T80）

| 项 | 内容 |
|---|---|
| 结论 | 事实键、事实表、关系表（仅 supports/contradicts）、冲突标记、实体表、印象表、证据链、回填、备份均已落地；**supersedes/valid_to/双时间、独立整合 Worker、印象纠错/冻结、抽取合规率未完成** |
| 状态 | 主体实现完成（T75-lite/T76a-lite/T77 切片/T78 切片/T81/T81a 切片）；T80 待 reviewer |
| 代码基线 | 前缀 `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/`：`domain/memory/{MemoryParseRules,MemoryV2Pipeline,MemoryRelationRules,ImpressionBuilder,ImpressionEvidencePlanner,MemoryConsolidationPolicy}.kt`、`data/{MemoryFactRepository,MemoryEntityRepository,ImpressionRepository,ImpressionEvidenceRepository,MemoryV2Repository}.kt`、`prompt/MemoryV2PromptTemplates.kt`、`backup/wire/*` |
| 上游 | 计划书 v1.1 §4.1–§4.5、§4.4.2；审计 P0-1/P1-5/X-06/X-07；handoff `impl-memory-m2-structured-facts`、`impl-memory-t77-consolidation-policy`、`impl-memory-team-batch-1..5` |

## 1. 能力状态（结论先行）

| 能力 | 状态 | 代码入口 | 缺口 |
|---|---|---|---|
| 事实键 | 已实现 | `MemoryParseRules.structuredFactOf/structuredTopicKey/normalizeFactPart` | 格式 `fact:<polarity>:<subject>\|<predicate>\|<object>[\|q=…]`；仍存 `memory_items.topicKey`，未迁独立 `fact_key` 列；`object` 归一弱，措辞不同会少合并（安全方向） |
| 事实表 | 部分（只写） | `MemoryFacts.sq`（迁移 27）、`MemoryFactRepository.upsert/findByFactKey`、`MemoryV2Pipeline.saveMemoryFact` | 仅 L1 抽取路径写；手动/导入/恢复不写；召回只用 `topicKey`，事实行未被消费；`validFrom=createdAt` 已写，`validTo` 恒 null（无关闭逻辑） |
| 关系表 | 部分 | `MemoryRelations.sq`（迁移 25）、`MemoryRelationRules.relationType/baseOf/oppositeKey`、`MemoryV2Pipeline.recordStructuredRelations` | 六类关系只实现 `supports`/`contradicts`；`supersedes/refines/contextual` 与 `valid_to` 未做；不关闭旧事实 |
| 冲突标记 | 部分 | `MemoryFactRepository.markConflict/countSuspected/listSuspected`；`markFactConflict` SQL | `contradicts` 时新旧事实置 `conflictStatus='suspected'`；无确认 UI/队列消费；会刷新 `updatedAt`；不合并、不归档 |
| 实体表 | 已实现（写） | `MemoryEntities.sq`（迁移 29）、`MemoryEntityRepository.upsertEntity/linkMemory/findMemoryIdsByNames`、`MemoryEntityExtractor` | 三表（canonical/alias/关联）；`entity_aliases` 未参与召回；抽取只看 subject/object；代词类（用户/角色）已排除 |
| 实体通道 | 已实现（见召回卡） | `MemoryV2Pipeline.mergeEntityRecallCandidates` | 仅 operator 私聊；完整实体名子串匹配，无别名/边界规则 |
| 印象表 | 部分 | `Impressions.sq`（迁移 30）、`ImpressionBuilder.build/confidenceOf`、`ImpressionRepository.upsert/listForCharacter` | 置信=0.6+0.1×条数（L3 再 +0.1，封顶 0.95）；L3→long_term、L2→ongoing；**页面仍读 legacy `memory_items`**；无 rejected/frozen 写入入口，纠错/冻结未做 |
| 证据链 | 部分 | `ImpressionEvidence.sq`（迁移 31）、`ImpressionEvidencePlanner.plan`（≤8，L3 0.9/L2 0.7/L1 0.5）、`ImpressionEvidenceRepository.linkAll/list` | 只引用记忆 id、不复制正文；UI 无证据展开；证据删除传播未端到端验证 |
| 回填 | 部分 | `MemoryV2Repository.rebuildImpressionsForOwner`、`A7Glue.backfillImpressionsIfNeeded` | housekeeping 一次性、标记 `impressions_backfill_done`；**单角色失败被 `runCatching` 吞掉后仍置位**，不会按 handoff 所述「失败下次重试」 |
| 备份 | 已实现 | `FullBackupWireModels.{memoryFacts,memoryRelations,impressions,impressionEvidence}`、`BackupFullRestoreExecutor`、`FullBackupGoldenTest.acceptance18_impressionsAndEvidenceRoundTrip` | 事实/关系/印象/证据均入包并按新 id 重建；内容过滤仍未按 `memoryId` 裁剪事实/关系（batch-3 遗留）；batch-5 曾记为「未纳入」，以当前代码与金样为准 |
| 使用计数 | 部分（见召回卡） | `memory_usage`（迁移 28）、`MemoryUsageRepository.bumpInjected` | 只记 injectionCount；retrieved/useful 未写 |
| 整合幂等/退避/预算 | 部分 | `MemoryConsolidationPolicy.signature/shouldSkip/onFailure/canSpend`、`MemoryV2Pipeline.maybePromoteOwnerMemoryLocked` | 幂等签名（候选 id/updatedAt/importance 的 FNV+promptVersion）；10s/60s/300s、3 次 dead-letter；4 次/角色/日、300 次/设备/日；一次运行最多 L2+L3 两次请求；**无独立 Worker/队列/checkpoint/租约**；dead-letter 无重试入口 |
| 晋升分组 | 已实现 | `MemoryParseRules.pickPromotionTopic`、`maybePromoteOwnerMemoryLocked` | 结构化组 3 条（强信号 2）+≥2 会话；旧指纹 20 条（强信号 2）；移除外层「总数不足即返回」 |
| L1 输出契约 v3 | 已实现 | `MemoryV2PromptTemplates.SCHEMA`（topic/subject/predicate/object/polarity/qualifiers）、promptVersion `memory_v2_l1_v3/l2_v3/l3_v3` | 模型实际合规率未测；不合规回退旧指纹（安全但晋升不可达） |

## 2. 契约与数值

| 项 | 值 |
|---|---|
| 事实键 | `fact:<polarity>:<subject>\|<predicate>\|<object>[\|q=<qualifier,…>]`；小写、去标点/空白、截 24 字 |
| 谓词归一 | dislike/prefer/reside/work/allergy/promise/plan/own/study/birthday（顺序敏感）；极性缺省按谓词推断；qualifiers 进键 |
| 组门 | 结构化组 3（强信号 2，取 `importantPromotionThreshold`）+≥2 个不同 `sessionId`；旧指纹组 20（强信号 2） |
| 幂等 | 同签名成功/同签名 dead-letter/退避未到 → 跳过；新证据改签名 → 自动解除 dead-letter |
| 状态存储 | settings `memory_consolidation_state_<ownerType>_<ownerId>_<sourceKind>`；预算键按日；无新表 |
| 可观测 | 首次 dead-letter 计数 + 最近错误；记忆体检行显示「整合失败 N 次」 |

## 3. 未测 / 未实现

| 项 | 状态 | 归属 |
|---|---|---|
| 模型抽取结构化字段合规率（目标 ≥85%） | **未测**（需真实模型回放） | T68/G2 |
| L2 内容忠实度（晋升后是否忠实于证据） | **未测** | G2 |
| 印象形成覆盖率 ≥60%、准确率 ≥80%、无证据不生成 | **未测**（仅结构约束：只在 L2/L3 聚合） | T81/G3 |
| 过期事实率（supersedes 关闭旧事实） | 未实现也未测 | T78 |
| dead-letter 手动重试、失败窗口 UI | 未做 | T87 |
| 事实键迁独立列 + 索引 | 未做（暂存 `topicKey`） | T69/T75 |
| 带旧记忆上下文的 ADD-only 提取 | 未做 | T75 |
| 印象纠错/冻结/拒绝状态机、证据展开 | 未做 | T81a/T83 |

## 4. 验收（本切片）

- 单测：三种措辞同事实（无逐字重复）→ 成组晋升；相反极性 → 不合并；同会话 3 条非强信号 → 不晋升、强信号 2 条 → 晋升；无结构化字段 4 条 → 不晋升（旧门槛不放宽）。
- 单测：整合签名顺序无关/变化敏感、退避与三次 dead-letter、预算上限。
- 单测：印象聚合幂等、证据 ≤8 与权重、备份含事实/关系/印象/证据往返。
- 回归：**708 tests / 8 failed**（8 条为基线既有 `KtorDarwinStreamingTest`，需本地测试服务）；schema 32、40 表；iOS 构建通过。
- 待补：真实模型抽取回放与 L2 忠实度抽检（G2），通过后再谈 T80 定稿。

## 5. 不做

- 不得用相似度或词法命中自动合并相反事实；旧事实不因新证据直接删除或归档；未标定的抽取/整合阈值不得上线放宽；dead-letter 未经新证据不得手改状态绕过。