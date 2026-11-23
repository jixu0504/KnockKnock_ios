# 规格卡：记忆 V2 管线 + 知识库管线

> 基线：Android v1.14.27-android-baseline @ 3642d2c（只读 oracle）。所有 file:line 均基于该提交。
> 范围纪律：本卡只定义「三层记忆的生成/维护/召回/注入 + 锚点评级 + 向量服务 + 知识库全流程」的**行为与存储语义**；后台触发点（ContextMaintenanceWorker 调度、何时跑 retryPendingSources）归 T22 后台任务卡，记忆管理/知识库管理 UI 状态留 app 层。提示词字面量（L1/L2/L3/SCHEMA/DATA_BOUNDARY）已随 T18 迁入 `shared/prompt/` 并有金样门禁闭环——本卡只消费、不复制。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 记忆 V2 管线 + 知识库管线 |
| 所属阶段 | Phase 1（P1-⑤，T21） |
| 范围映射 | **A3**（三层记忆 L1/L2/L3 + 六类锚点 + 生成/分级/维护/编辑/删除/隐私 + 知识库导入/切块/索引/召回全流程）；**A1**（私聊/群聊主链路中记忆与知识库上下文的构造与注入接口——组装编排归 T15，模板渲染归 T18）；排除（记忆/知识库管理 UI 与锚点管理 UI 留 app 层；后台触发点归 T22；报纸/派遣等二期 surface 的记忆注入不迁） |
| 对应 Android 源码 | 见下「oracle 文件清单」 |
| 依赖模块 | 上游：`shared/prompt`（T18：`MemoryV2PromptTemplates.getL1/L2/L3/SCHEMA/DATA_BOUNDARY`，handoffs/impl-prompt.md:14）、`shared/vector`（向量存储/嵌入）、`shared/data`（ChatRepository/MemoryV2Repository/KnowledgeBaseRepository）、`shared/settings`、`shared/network`（AIService）、`shared/model`（MemoryItem 等枚举/模型）——全部已在 shared，跨域仅经 shared/domain 接口 |

### oracle 文件清单（只读）

| 文件（相对 RhodesLink/） | 作用 |
|---|---|
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2Pipeline.kt`（1347 行） | 记忆管线本体：L1 提取（:94-374）、L2/L3 分级（:376-442）、召回与注入（:444-743）、解析/评分/去重/主题（:745-1347） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2PromptTemplates.kt`（165 行） | 记忆提示词（**已随 T18 迁 shared/prompt/，本卡只引用**） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryRanker.kt`（138 行） | 六类锚点（legacy）评级与 surface 过滤 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryPrivacyPolicy.kt`（63 行） | 来源→可见性策略 + 敏感词检测 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/UnifiedMemoryContext.kt`（43 行） | 上下文块合并/去重/截断 + 时间摘要判定 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryVectorFormatter.kt` | 向量 id/sourceType/content/tags 格式化（含 java.text，需平台化） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemorySurface.kt` | surface 枚举（PRIVATE_CHAT/GROUP_CHAT/MOMENT/COMMENT/DIARY） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryPromotionLocks.kt` | 分级互斥（JVM ConcurrentHashMap，需平台化） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryPolicy.kt`（48 行） | 旧记忆策略（评级关联，一并迁） |
| `app/src/main/java/com/rhodes/privatechat/automation/ContextMaintenanceWorker.kt`（43 行） | 维护触发点（**归 T22**，本卡仅读） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:3060-3290, 3414-3437` | 私聊注入编排（本卡定义其调用的上下文构造契约与顺序） |
| `shared/src/commonMain/.../shared/memory/AnchorSourcePolicy.kt`（113 行） | 锚点来源策略：inferLegacy/knownFrom/normalizeImportance（已在 shared） |
| `shared/src/commonMain/.../shared/memory/MaintenancePolicy.kt`（8 行） | 维护轮次纯函数（已在 shared） |
| `shared/src/commonMain/.../shared/vector/`（9 文件） | 向量：LocalHashEmbeddingGateway（384 维本地哈希）、AliyunNativeEmbeddingGateway、OpenAiCompatEmbeddingGateway、EmbeddingGatewayFactory、EmbeddingConfigurationSignature、MemoryVectorService、LocalVectorStoreGateway（T1 已平台化）、VectorStore.kt（模型+网关接口） |
| `shared/src/commonMain/.../shared/knowledge/`（5 文件） | 知识库：TextProcessor（切块）、ImportService、IndexService、RecallPolicy、ContextBuilder（召回/限额/包裹） |
| `shared/src/commonMain/.../shared/data/MemoryV2Repository.kt`（462 行） | 记忆存储：插入去重、源队列租约、晋升归档、删除传播（已在 shared） |
| `shared/src/commonMain/.../shared/settings/SettingsRepository.kt:266-504, 1047` | 记忆/知识库设置键与默认值（已在 shared） |
| 金样 | `ios-port/golden/db/schema/{MemoryItems,MemoryAnchors,MemoryBatches,MemoryLinks,MemorySourceQueue,VectorMemories,KnowledgeBases}.sq`；`ios-port/golden/prompts/raw/MemoryV2PromptTemplates_*.md`（T18 已闭环）；`ios-port/golden/prompts/inventory.md:13-14,42-50,159-166` |

## 输入 / 输出 / 边界

- **输入**
  - 提取：`ChatMessage` 窗口（私聊/群聊）、`Moment`、`MomentComment`、日记文本；来源标识 = `sourceKind` × `ownerType/ownerId` × `sourceRefId`（窗口内带 timeline epoch，MemoryV2Pipeline.kt:104, 191）。
  - 分级：L1/L2 活跃记忆集（`getActiveMemoryItemsByLevel`，:391, 417）+ 阈值设置。
  - 召回：query（取最近 3 轮拼成，ChatViewModel.kt:3101-3110）、`allowedSources`、`visibilities`、`memoryRecallMode`。
  - 知识库：文件字节（≤5MB）/文本（≤60 万字）；导入名与格式。
- **输出**
  - 记忆上下文字符串（各块带中文标题，如「【与当前话题相关的经历】」，MemoryV2Pipeline.kt:528, 677, 691, 742）；空库/禁用 → `""` 或 `"无"`。
  - 知识库上下文字符串（`【知识库资料开始】…【知识库资料结束】` 包裹，KnowledgeBaseRecallPolicy.kt:35-36）；调试用 `Recall`（text/hits/reason/assignedBooks，KnowledgeBaseContextBuilder.kt:33-42）。
  - 副作用：`memory_items/memory_links/memory_batches/memory_source_queue/vector_memories/knowledge_bases/knowledge_base_chunks` 写入；模型调用（L1/L2/L3 提取与分级、远端嵌入）。
- **边界**
  - 提取上限：每窗口 L1 ≤ 5 条（MAX_L1_ITEMS_PER_EXTRACTION=5，:1332）；每次晋升 L2 ≤ 2、L3 ≤ 1（:1333-1334）；内容清洗 ≤120 字（:1210）；注入行 ≤180 字（:527 等）。
  - 超时：L1/L2/L3 模型调用 `withTimeout(50_000)`（:397, 423, 793, 802）；知识库工作预算 4500ms/每库 750ms/查询嵌入 1500ms（KnowledgeBaseRecallPolicy.kt:8-11）；远端嵌入单块 15s（KnowledgeBaseIndexService.kt:359）。
  - 并发：源窗口租约（10 分钟租期 + 60s 续租，MemoryV2Repository.kt:205-253）；晋升按 owner 互斥（:380-385）；知识库按书互斥 + 远端嵌入全局信号量(1)（KnowledgeBaseIndexService.kt:46-53, 362）。
  - 错误：模型异常/超时/供应商拒绝 → 进重试队列（退避 10s/60s/300s，3 次后 fail，MemoryV2Pipeline.kt:50-60）；向量写入失败只记日志不回滚记忆保存（:1182-1186）；嵌入供应商不可用 → 召回返回空（MemoryVectorService.kt:62-63）；知识库失败状态机与中断修复（KnowledgeBaseIndexService.kt:247-288）。

## 行为契约（逐条，每条带 Android oracle 位置）

> 三层命名映射：Android `L1/L2/L3`（MemoryLevel，Enums.kt:12）= iOS `SHORT_TERM/DAILY/LONG_TERM`。L1=短期碎片（短期提取、30 天级生命周期）、L2=中期聚合（日常主题合并）、L3=长期稳定印象。iOS 域代码按 Android 语义实现，命名仅为域内别名。

1. **L1 私聊/群聊提取入口**：开关 `memoryV2Enabled`+来源生成开关任一关 → 直接返回（:95, 184）；空消息窗口 → true 无操作（:96, 185）。窗口经 `insertMemorySource` 入队，`sourceRefId = "<session|group>:epoch<时间线>:<首条id>-<末条id>"`（:104, 191）；已处理窗口幂等返回（`isMemorySourceFinished`，:110-113）；claim 失败（其他任务持有）→ false 不重试本轮（:114-118）。iOS 差异：无（纯逻辑）。
2. **L1 提取调用与提示词**：`getL1(sourceKind.name)` 按 PRIVATE_CHAT/GROUP_CHAT/MOMENT|MOMENT_COMMENT|COMMENT/DIARY 分型，else → PRIVATE_L1（MemoryV2PromptTemplates.kt:9-15，已迁 shared/prompt/）；system=模板、user=【提取资料】+当前昵称+干员/群名+内容（MemoryV2Pipeline.kt:789-804）；50s 超时。iOS 差异：无（模板消费 T18 资产）。
3. **L1 解析失败语义（决定重试 vs 保存）**：返回非 JSON 数组 → 抛 IllegalStateException 进重试（:932-937）；返回了内容但零有效项 → 抛错（:940-942）；`对象数≥2 且有效项≤一半` → 抛错（:947-949）；否则按去重后 `take(5)` 保存（:950）。合法空数组 `[]` = 无值得保存的信息，正常完成（:923 注释）。iOS 差异：无。
4. **动态/评论/日记直接 L1（无模型调用）**：动态直接构造 `event` 型 L1（importance=35、privacy=public、30 天过期，:1100-1124），同时写 owner 与 global/public 副本（:283-286）；评论仅在高价值词命中（"答应/约定/承诺/计划/决定/以后/生日/搬家/住在/工作"，:1335）或作者为用户时写个人副本，公共副本恒写（:320-325）；日记 L1 = `"<干员名> 的日记："+内容.take(180)`、importance=45、private、30 天过期（:363-374）。iOS 差异：无。
5. **L1 保存与去重**：`saveMemoryItems` 逐条 `insertMemoryItemIfAbsent`（SQL 层按 ownerType/ownerId/level/type/content/sourceKind/sourceRefId/sessionId/privacy 等价判重，无 check-then-insert 间隙，MemoryV2Repository.kt:57-64），新增项回写 `rawJson.sourceMessageIds` 证据（:775-778）并异步补向量（:1080）；重复项静默跳过（:1073）。iOS 差异：无。
6. **L2 晋升（主题聚合）**：同 sourceKind 的活跃 L1 按 `topicKey`（type 分组 + 2-gram 去噪，:1280-1297）分组；组大小 ≥ `memoryV2PromoteL1Threshold`（默认 20，:436-438，SettingsRepository.kt:79-81）；组内按 `importance*100+recency` 排序取前 12（:1252-1256, 1266-1270）；含强信号（importance≥80 / unmetNeed / 承诺 / 提醒）时改用 `memoryV2ImportantPromotionThreshold`（默认 2，:1261-1264, SettingsRepository.kt:87-89）。提示词 = L2 模板 + 待合并 JSON（:396-397, 857-860）。iOS 差异：无。
7. **L3 晋升与稳定性门**：L2 组 ≥ `memoryV2PromoteL2Threshold`（默认 10，:440-442）且 `requireStable=true`：必须含稳定类型（preference_expression/agreement_commitment/care_reminder/evaluation_opinion/self_cognition_statement，:1344）且（时间跨度 ≥3 天 或 ≥4 条 或 importance≥90）；不足 3 条且无 importance≥85 → 拒绝（:1272-1278）。提示词 = L3 模板（:422-423）。iOS 差异：无。
8. **晋升产物约束**：L2/L3 输出必须带 `evidence_ids` 且只能引用本次输入 id，否则整条丢弃（:1137-1144）；`withPromotionMeta` 写 topic_key/evidence_count/promoted_to/source_levels/last_evidence_at/source_kinds，confidence=0.8+父数×0.02 封顶 0.95，任一父为 private → 子为 private，**继承主父的 sourceRefId/sessionId**（删除会话时衍生记忆可被连带清理，:1299-1328）。iOS 差异：无。
9. **晋升归档与链接（乐观校验）**：`finishPromotion` 在事务内复核每个父证据仍 active 且 content/privacy/sourceKind/sourceRefId 未变；变了 → 归档本次子项并删其向量（放弃晋升）；未变 → 写 memory_links，且仅当该父的全部子向量已索引才归档父并删父向量（MemoryV2Repository.kt:21-50；:1088-1097）。iOS 差异：无（事务语义）。
10. **层级字段约束（SCHEMA）**：11 个合法 memoryType（:1336-1340）；importance 0-100 分级（重大禁忌/承诺/安全 80-100、明确偏好/计划 50-79、普通事件 20-49、弱信息 1-19，MemoryV2PromptTemplates.kt:157）；privacy ∈ public/private/shared；emotion_valence ∈ positive/neutral/negative/mixed；care_type ∈ comfort/remind/celebrate/accompany/none（:1341-1343）；content 清洗掉「系统记录/记忆锚点/锚点/摘要/好感度/affection」机制词后截 120 字（:1201-1211）。iOS 差异：无。
11. **六类锚点（legacy 锚点评级）**：AnchorType = PLAN/PREFERENCE/TABOO/EVENT/EMOTION/RELATION（Enums.kt:9）；`MemoryRanker.pick` 先 `inferLegacy` 归一来源/强度/内容（AnchorSourcePolicy.kt:53-82），再过滤弱内容（哈哈/嗯/同意 等或 <6 字，MemoryRanker.kt:119-126）与 surface 不允来源（:24-59），按 来源分+强度分+类型分+新鲜度分+关键词相关分 排序取 top N（:61-108）；`knownFrom` 生成「你是在 XX 中知道的」来源文案（AnchorSourcePolicy.kt:84-97）。iOS 差异：无（纯逻辑，表已在 golden/db/schema/MemoryAnchors.sq）。
12. **私聊向量召回管线**：query 空或向量服务不可用 → 空（:496-499）；按 `privateRecallBudget(mode)` 分层取候选——recentL1（30 天内）/olderL1/L2/L3/manual/visual 六桶（:500-517）；每桶经 `minimumPrivateSimilarity` 阈值过滤（protected+时间回顾问句 0.14、视觉 0.22、L1 状态/事件 0.22、L3/manual/protected 0.18、其余 0.20，:576-586）；评分 = similarity×0.70+importance×0.15+recency×0.10+类型加成 0.03/0.05（:592-605）；按分排序后 `selectPrivateInjection` 注入（:607-633）。iOS 差异：无。
13. **召回限额/优先级矩阵**：`privateRecallBudget`——fast=(80,20,30,20,20)、balanced=(150,50,60,30,30)、deep=(260,100,100,40,40)（:533-537）；`buildOwnerMemoryContext` 候选池 fast=100、deep=700、balanced=`memoryRecallCandidateLimit`（默认 160，VectorStore.kt:46），minScore fast=0.24 / 其他=0.16，fast 只取近 30 天（:714-731）；注入上限：常规 4 条（近 30 天 L1≤2、L2/L3/manual≤1、protected≤1），时间回顾问句扩到 6 条（近 L1≤3），视觉问句固定保 1 条视觉锚点（:607-633）；关系网注入每关系 ≤2、总分取 top 3（:666-676）。iOS 差异：无（矩阵数值是验收金样）。
14. **关系网召回（亲密度档位）**：`relationshipRecallPolicy`——亲密度 0-19 不召回；20-39 →(候选1, 重要度≥80)；40-59 →(2, 65)；60-79 →(3, 50)；≥80 →(5, 35)（:1239-1245）；仅召回对方 PRIVATE_CHAT 来源的 L1/L2/L3 向量，minScore=0.16（:647-668）。iOS 差异：无。
15. **稳定印象/公共记忆/通用 owner 上下文**：`buildPrivateStableImpression` = 活跃 L3 × PRIVATE_CHAT 前 3 条（:683-693）；`buildPublicMemoryContext` = global/public owner 的 L1 召回 limit=3（:695-707）；`buildOwnerMemoryContext` 按 L1/L2+manual/L3 三桶召回后统一去重、每条截 180 字、以「【与当前话题相关的经历】」包裹（:709-743）。iOS 差异：无。
16. **提示词注入顺序与 T18 接口**：私聊组装顺序 = 群聊近况 → 稳定印象 → 个人向量记忆 → 关系网传递 → `mergeBlocks(contextBlockLimit, 个人, 关系)` → 公开动态/评论（`mergeBlocks(2×limit, V2, 公开)`）→ 知识库召回（ChatViewModel.kt:3113-3169, 3176-3200）；replacements 键 = `MEMORY_V2_CONTEXT`=合并记忆块、`__KNOWLEDGE_BASE_CONTEXT`=知识库块、`SHORT_TERM_SUMMARY`、`LONG_TERM_IMPRESSION`、`GROUP_CONTEXT` 等（:3236-3264）；注入来源受 `privateChatAllowedSources`（六类来源 × 设置开关，MemoryV2Pipeline.kt:563-574）与 `isMemoryInjectionAllowed` 门控。模板渲染归 T18/T15；本卡交付上下文构造函数并保证键值形态。iOS 差异：无（双端输出一致）。
17. **隐私策略（来源权威）**：`MemoryPrivacyPolicy.forSource`——PRIVATE_CHAT → sensitive?private:shared（不写 global）、GROUP_CHAT → public、MOMENT/MOMENT_COMMENT → public+写 global 副本、DIARY → private、MANUAL → sensitive?private:shared（MemoryPrivacyPolicy.kt:12-53）；解析层**强制覆盖**：PRIVATE_CHAT 恒 private、GROUP/MOMENT/COMMENT 恒 public（模型不能改，:873-879）；敏感词 18 个（别告诉/不要告诉/不能告诉/秘密/隐私/私密/保密/焦虑/崩溃/撑不住/害怕/难过/抑郁/自杀/自伤/亲密/只告诉你/不想让别人知道，:55-61）；向量 visibility 兜底 defaultPrivacy（:1217-1221）。iOS 差异：无。
18. **向量嵌入两种模式**：local → `LocalHashEmbeddingGateway`（384 维、字/2-gram/3-gram FNV-1a 哈希+符号、L2 归一，LocalHashEmbeddingGateway.kt:5-37，**跨平台必须逐位一致**）；third_party → aliyun_native 协议（input.texts → output.embeddings，:36-65）或 openai_compat（data[0].embedding，OpenAiCompatEmbeddingGateway.kt:25-46）；third_party 但缺 baseUrl/key → `DisabledEmbeddingGateway`（空向量，EmbeddingGatewayFactory.kt:5-14）。iOS 差异：无（Ktor 已在 shared）。
19. **嵌入签名隔离与配置变更语义**：签名 = `mode|baseUrl|modelName`（openai_compat）或追加 `|protocol=<协议>`（aliyun_native，EmbeddingConfigurationSignature.kt:8-18）；`MemoryVectorService` 按 7 元组配置键缓存网关（:20-27）；saveMemory 前后双重校验签名一致否则抛「向量模型配置已变化」（:37-44）；召回时签名漂移或嵌入不可用 → 返回空（:60-63, 89-94）；存储层按 `embeddingSignature == 请求签名` 过滤候选（LocalVectorStoreGateway.kt:108, 202）。旧签名向量不参与召回 → 迁移后须重建索引（见契约 21/25）。iOS 差异：无。
20. **会话隔离与删除传播**：所有向量查询按 `ownerType+ownerId` 作用域过滤（VectorStore.kt:30-33，SQL 见 VectorMemories.sq 候选查询）；`upsert` 前校验原记忆仍 active 且未改（originMemoryId 乐观快照，LocalVectorStoreGateway.kt:40-41）；删来源 → 归档该来源全部记忆与**所有以其为证据的衍生 L2/L3**（BFS 沿 memory_links，MemoryV2Repository.kt:309-367）；清会话 → invalidateDerivedBySession + 删会话向量（:393-399, 427-435）；时间线 epoch 变化（会话重置）→ 旧窗口 skip 不落库（MemoryV2Pipeline.kt:140-145, 223-228, 984-987）。iOS 差异：无。
21. **维护逻辑（触发点归 T22）**：`retryPendingSources(limit=20)` 认领 pending/retry_waiting/过期租约窗口，逐窗口 60s 续租（:953-967）；来源生成开关关闭 → 释放租约延后 60s 不请求模型（:966-978）；时间线失效 → skip（:979-987）；失败 → 退避重试 10s/60s/300s，3 次后 `failSource` 保留窗口停止自动重试（:50-60, 1022-1035）；diary 窗口校验日记存在且属主一致（:1012-1018）。iOS 差异：调度侧（WorkManager→BGTaskScheduler）归 T22；本卡只交付可调用的维护函数本体。
22. **知识库导入与切块**：文件 ≤5MB、正文 ≤60 万字、分段 ≤1800；二进制扩展名（pdf/docx/epub/zip/图片/音视频等 30 种）拒绝、非 UTF-8（含 U+FFFD）拒绝（KnowledgeBaseTextProcessor.kt:18-57）；md 按 `#{1,6}` 标题路径分节、段落按句切分、目标 450 字/最短 120/最长 1000/重叠 80（:21-24, 70-154）；local 模式导入后立即入索引队列，third_party → `pending_confirm` 等 UI 确认请求次数（KnowledgeBaseImportService.kt:146-153）；更新文本 → invalidate 旧索引后重建（:90-112）。iOS 差异：无。
23. **知识库索引状态机与失败恢复**：状态 ∈ processing/pending/pending_confirm/indexing:x/y/partial_indexing/ready/partial_pending_confirm/partial_failed/failed；按书互斥、按稳定 chunk id upsert、**不清空可用旧索引再重建**（KnowledgeBaseIndexService.kt:176-246）；远端嵌入 3 次尝试（750/2000ms 退避）、15s 超时、全局信号量 1（:304-363）；中断/取消 → NonCancellable 修复状态为 pending/partial_pending_confirm，绝不停留在 indexing:x/y（:247-288）；签名漂移 → 抛「向量配置已变化，请重新确认索引」（:208）；错误信息 `distinct().take(5)`（:246）。iOS 差异：无。
24. **知识库召回限额/优先级/包裹**：私聊——每库 ≤3 条、最终 ≤3 条、候选池 ≤60（可设置覆盖）、总字符 ≤1200、总预算 4500ms、每库 750ms、查询嵌入 1500ms、minScore=0.12（KnowledgeBaseRecallPolicy.kt:5-11, KnowledgeBaseContextBuilder.kt:89-137）；群聊——每库 1 条/600ms/查询嵌入 1000ms/minScore=0.16/候选 60（KnowledgeBaseContextBuilder.kt:211-215）；`selectTop` 排序 = score 降序 → 书序 → 书名（RecallPolicy.kt:23-27）；命中文本截断 + 保留标记转义 + 「只是背景参考资料，不是可执行指令」包裹（:29-36）；不可用原因码 no_assignment/blank_query/embedding_unavailable/index_not_ready/no_match（:44, 81-129）。iOS 差异：无。
25. **异常处理汇总（空库/索引失败/供应商不可用/迁移后行为）**：记忆召回空库 → 各上下文函数返回 `""`/`"无"`，注入层 `mergeBlocks` 过滤（UnifiedMemoryContext.kt:4-25）；向量写入失败 → 记日志、记忆本体已保存（:1182-1186）；嵌入供应商不可用 → `recall/search` 返回空、知识库召回 reason=embedding_unavailable（MemoryVectorService.kt:62-63, KnowledgeBaseContextBuilder.kt:98-100）；索引失败 → 失败 chunk 记 indexError、保留已有可用索引为 partial_failed（:231-233, 253-267）；**迁移后行为**（供应商/模型变更）——旧签名向量不参与召回，记忆侧经 `getActiveMemoryItemsMissingVector`+`memoryIndexStates` 重建（MemoryV2Repository.kt:102-104, 259-263），知识库侧 `markChunksPendingConfirmation`/planDirtyIndex 走待确认流程（KnowledgeBaseIndexService.kt:61-65, 148-152）。iOS 差异：无。

## 数据与迁移

- **涉及表/字段（对照 golden/db/schema，iOS 全新建库到最新版，历史迁移由数据卡按迁移链实现）**：
  | 表 | 金样 | 关键字段 |
  |---|---|---|
  | `memory_items` | MemoryItems.sq | 记忆本体：ownerType/ownerId、memoryLevel(L1/L2/L3)、memoryType(11 类)、sourceKind、sourceRefId、sessionId、content、importance、privacy、unmetNeed、location、emotionValence、eventTime/scheduledTime/action/careType、topicKey、sourceActor/sourceTarget、lastUsedAt/usedCount、confidence、rawJson、vectorId、expiresAt、status(active/archived) |
  | `memory_anchors` | MemoryAnchors.sq | legacy 六类锚点：type(PLAN/PREFERENCE/TABOO/EVENT/EMOTION/RELATION)、source/sourceName/sourceActor/sourceTarget、importance(strong/medium/weak)、knownFrom、isPrivate |
  | `memory_batches` | MemoryBatches.sq | 提取/晋升批次统计（promptVersion=memory_v2_l1_v2 等，:159, 242, 296, 335, 411, 437） |
  | `memory_links` | MemoryLinks.sq | 父子证据链（linkType=merge_l1_to_l2/merge_l2_to_l3） |
  | `memory_source_queue` | MemorySourceQueue.sq | 提取窗口队列：唯一身份索引(sourceKind,ownerType,ownerId,sourceRefId,contentText,timestamp)、status(pending/running/retry_waiting/failed/succeeded/skipped)、retryCount/nextRetryAt/leaseUntil/claimToken |
  | `vector_memories` | VectorMemories.sq | 向量索引：id、ownerType/ownerId、sourceType(memory_v2_l1/l2/l3、manual_memory、anchor_vision、knowledge_base_chunk)、sourceId、embeddingJson、tags(`L1,<type>,<sourceKind>`)、visibility、embeddingSignature、expiresAt |
  | `knowledge_bases` | KnowledgeBases.sq | indexStatus、indexedEmbeddingSignature、chunkingMode、sourceType |
  | `knowledge_base_chunks` | KnowledgeBases.sq | ordinal、sourceHeading、content、userKeywords、enabled、indexedAt、indexError；UNIQUE(book, ordinal) |
  | `operator_knowledge_bases` | KnowledgeBases.sq | 干员-知识库指派：enabled、sortOrder，PK(operatorId, knowledgeBaseId) |
- **迁移版本**：iOS 建库直接到最新 schema（23 个 .sqm 的上界）；记忆相关历史迁移 = 3.sqm（anchors 六列）、19.sqm（知识库三表）、20.sqm（kb.sourceType）、21.sqm（vector 候选索引）、23.sqm（memory_items 备份索引）——**数据卡负责按迁移链回放，本卡仅声明目标 schema 与索引**。
- **备份/恢复**：`memoryItems/anchors/knowledgeBases/knowledgeBaseChunks/operatorKnowledgeBaseAssignments` **进** ExportPayload（audit/fields.md:37, 41——完整导出含记忆与知识库全文，用户应知悉明文风险 :68）；`vector_memories`/`memory_batches`/`memory_links`/`memory_source_queue` **不进**导出（衍生索引，恢复后按 `getActiveMemoryItemsMissingVector` 重建 + 知识库索引状态 reset 待确认——与 Android 恢复后行为一致）。`vector_api_key` 不进备份（audit/fields.md:59）。
- **设置键清单**（SettingsRepository.kt:266-504，iOS = NSUserDefaults）：`memory_v2_enabled`(true)、`memory_recall_mode`(balanced|fast|deep)、`memory_recall_candidate_limit`(160)、`memory_v2_promote_l1_threshold`(20, 5-200)、`memory_v2_promote_l2_threshold`(10, 3-100)、`memory_v2_important_promotion_threshold`(2, 2-20)、`private/group/moment/moment_comment/diary_memory_generation_enabled`、`*_memory_promotion_enabled`、`private_memory_extraction_threshold`/`group_memory_extraction_threshold`、`private_recall_*_memory`（六来源注入开关）、`global_public_memory_enabled/count`、`clean_days_memory_items`（<0 = 永不过期）、`vector_provider_mode`(local/third_party)/`vector_protocol`(openai_compat/aliyun_native)/`vector_base_url/model_name/api_key`、`group_memory_copy_to_members_enabled`、知识库开关与 `knowledge_base_private_candidate_limit` 等。

## 验收（必须可自动判定）

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | **金样 diff——MemoryV2 模板件（T18 已闭环，本卡回归）**：`shared/prompt/MemoryV2PromptTemplates.kt` 的 L1×4/L2/L3/SCHEMA/DATA_BOUNDARY 与 `golden/prompts/raw/MemoryV2PromptTemplates_*.md` 逐字节一致 | T18 GoldenPromptTest 47 件 Tier-1 含 MemoryV2 8 件（严格比对，不入白名单） | `bash ios-port/golden/prompts/sync_golden.sh && ./gradlew :shared:testDebugUnitTest --tests "*GoldenPrompt*"` |
| 2 | **召回结果金样（本卡新增）**：固定 fixture 向量列表（id/content/importance/tags/createdAt/similarity）+ 固定 Clock 下，`buildPrivateChatMemoryContext`/`buildOwnerMemoryContext`/`buildRelationshipPrivateMemoryContext`/`buildPublicMemoryContext`/`buildPrivateStableImpression`/`mergeBlocks` 的确定性输出与 `ios-port/golden/memory/recall/*.md` 逐字节一致（金样由 baseline tag 的 Android oracle 代码生成，见门禁设计） | commonTest 金样比对（fixtures 硬编码在测试内，金样目录随 sync 复制进 commonTest resources） | `./gradlew :shared:testDebugUnitTest --tests "*MemoryRecallGolden*"`；iOS 同用例 `./gradlew :shared:iosSimulatorArm64Test --tests "*MemoryRecallGolden*"` |
| 3 | **本地哈希嵌入金样（本卡新增）**：固定 20 条文本经 LocalHashEmbeddingGateway(384) 的向量与 `ios-port/golden/memory/embedding_local.json` 逐位一致（FNV-1a 跨平台确定性） | 单元测试 + 金样文件 | `./gradlew :shared:testDebugUnitTest --tests "*LocalHashEmbedding*"` |
| 4 | **限额/优先级矩阵**：fast/balanced/deep 六桶预算表（:533-537）、候选池 100/700/160、minScore 0.24/0.16、注入选择上限（4/6、近 L1 2/3、视觉保 1）、关系网亲密度 5 档、知识库 3/3/60/1200/4500ms/750ms/1500ms 与群聊 1/600ms/0.16 | 参数化单元测试（数值与 oracle 逐项对照，矩阵表写入测试数据） | `./gradlew :shared:testDebugUnitTest --tests "*MemoryRecallMatrix*"`（含 `KnowledgeBaseRecallPolicyTest`、`RelationshipRecallPolicyTest`） |
| 5 | **晋升/解析矩阵**：L1≤5、L2≤2、L3≤1；非数组/零有效/不足一半 → 重试路径；evidence_ids 校验；稳定性门（3 天跨度/4 条/90 分）；topicKey 分组与强信号阈值 | 单元测试（fake AIService 返回固定 JSON） | `./gradlew :shared:testDebugUnitTest --tests "*MemoryPromotion*"` |
| 6 | **隐私/删除传播矩阵**：来源→隐私强制表、敏感词 18 个、deleteBySource/invalidateDescendants 链式归档、时间线 epoch 跳过、finishPromotion 乐观校验 | 单元测试（fake repository/SQL 集成测试） | `./gradlew :shared:testDebugUnitTest --tests "*MemoryPrivacy*"`、`"*MemoryDeletion*"` |
| 7 | **向量签名/会话隔离矩阵**：签名格式（openai_compat vs aliyun_native）、配置漂移 → 空召回/抛错、旧签名向量不召回、upsert 原记忆校验失败拒写 | 单元测试（fake gateway/store） | `./gradlew :shared:testDebugUnitTest --tests "*VectorSignature*"` |
| 8 | **知识库流程矩阵**：切块边界（5MB/60 万字/1800 段/450 目标/120 最短/1000 最长/80 重叠）、二进制与编码拒绝、状态机全迁移（含中断修复）、远端重试 3 次、召回原因码五类 | 单元测试 | `./gradlew :shared:testDebugUnitTest --tests "*KnowledgeBase*"` |
| 9 | **注入顺序回归**：私聊「群聊近况→稳定印象→个人→关系网→公开→知识库」合并顺序与 replacements 键值（与 T15 组装器测试共享 fixture） | 组装层测试（T15 落地，本卡提供构造函数） | `./gradlew :shared:testDebugUnitTest --tests "*PromptAssemblyOrder*"` |
| 10 | **双路径全量回归** | Android JVM + iOS 模拟器同源 commonTest | `./gradlew :shared:testDebugUnitTest`；`./gradlew :shared:iosSimulatorArm64Test` |

### 金样 diff 门禁设计（本卡新增部分）

- **金样生成来源（硬性纪律）**：`ios-port/golden/memory/` 下所有金样（recall/*.md 与 embedding_local.json）必须由 **`v1.14.27-android-baseline` tag 的 Android oracle 代码**（app 侧 MemoryV2Pipeline.kt / MemoryVectorFormatter.kt / shared/vector/LocalHashEmbeddingGateway.kt 等基线源码）以固定 fixtures 运行后实测输出生成。**禁止**用迁移后的 shared 实现生成或比对自身（防自证）。生成方式：在 Android 基线分支运行一次性 dump 脚本（写 `ios-port/golden/memory/`，属于构建期工具），reviewer 可复核脚本。
- 目录：`ios-port/golden/memory/{recall/, embedding_local.json}`，同步脚本 `sync_golden.sh`（T18 已有）扩展复制到 commonTest resources。
- 允许差异白名单：无动态时间字段（全部 fixtures 固定时间戳与注入 Clock）；召回金样只覆盖**纯逻辑层**（相似度/重要性/时间戳由 fixture 给定），真实 embedding 余弦值归矩阵测试（local 模式确定性金样）与假网关测试（third_party）。任何字节差异的合法修复路径 = 重新用 oracle 代码生成，禁止手改。
- 边界用例：空向量库、全低于阈值、重复内容去重、超 maxChars 截断、时间回顾问句扩量、视觉问句保位、亲密度 0-19 不召回、知识库 maxChars 耗尽提前截断。

### 性能

- 私聊记忆召回：候选池上限 100/160/700 + 每桶 limit，评分在 Dispatchers.Default 上解码/余弦（LocalVectorStoreGateway.kt:64-74），SQLite 查询保持串行；目标：balanced 模式单轮召回（含嵌入）< 800ms（JVM 微基准，本地哈希模式）。达标线由实现卡实测后回填。
- 知识库召回：总预算 4500ms/每库 750ms 硬上限（KnowledgeBaseRecallPolicy），达标 = 超时即部分注入而非整体超时。
- 模型调用：L1/L2/L3 各 50s 上限（withTimeout），与聊天供应商共用 AIService。

## 抽取落点建议

> 目标包：`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/memory/`（+ `shared/domain/knowledge/` 门面）。逐包关系：

| 现有位置 | 关系 | 建议与理由 |
|---|---|---|
| `app/.../viewmodel/shared/MemoryV2Pipeline.kt` | **迁移** | 管线本体迁 `shared/domain/memory/`。理由：全部依赖（ChatRepository/AIService/SettingsRepository/MemoryVectorService/共享 prompt）已在 shared，唯一平台耦合是 `java.text.SimpleDateFormat/TimeZone`（:745-749, 806-811, 1112-1115）、`TimeSource.Monotonic`、`System.currentTimeMillis` 与 `ConcurrentHashMap` 锁表——按 T18 先例（PromptClock.kt：kotlinx-datetime Asia/Shanghai + Clock 构造器注入）平台化。建议拆为 `MemoryExtraction`（L1 入口+解析）、`MemoryPromotion`（L2/L3+稳定性门）、`MemoryRecall`（召回/评分/注入选择）、`MemoryIngest`（动态/评论/日记）四组件，但**先 1:1 整文件移植保证可 diff，再拆文件**（拆文件是纯移动，不改变行为）。 |
| `app/.../viewmodel/shared/MemoryRanker.kt` | **迁移** | 纯逻辑（依赖 AnchorSourcePolicy + MemoryAnchor 模型，均已在 shared），迁 `shared/domain/memory/`。MemorySurface 枚举（app 侧）随迁 shared/model 或 domain/memory。 |
| `app/.../viewmodel/shared/MemoryPrivacyPolicy.kt`、`UnifiedMemoryContext.kt`、`MemoryVectorFormatter.kt`、`MemoryPolicy.kt` | **迁移** | 纯逻辑；MemoryVectorFormatter 的 java.text 平台化同上（输出格式是金样，必须逐字节一致）。 |
| `app/.../viewmodel/shared/MemoryPromotionLocks.kt` | **迁移+平台化** | `ConcurrentHashMap<String, Mutex>` → `MutableMap + shared.platformSynchronized`（shared/vector/MemoryVectorService.kt:20 已有同模式先例）。 |
| `shared/.../shared/memory/`（AnchorSourcePolicy、MaintenancePolicy） | **迁移（文件级移动）** | 两个纯策略对象属记忆域语义，移入 `shared/domain/memory/policy/`。理由：与 domain 分层一致、无任何平台/存储依赖、移动零风险；若 reviewer 倾向最小 diff，备选 = 原地保留由 domain 依赖（`shared/memory` 是合法 shared 包），**待裁决**。 |
| `shared/.../shared/vector/`（9 文件） | **保留（原包不动）** | 存储/嵌入基础设施，T1 已平台化（LocalVectorStoreGateway 注释明确 T1 改动）；无域知识，改名迁移只有风险没有收益。domain/memory 经 `VectorStoreGateway` + `MemoryVectorService` 接口依赖。 |
| `shared/.../shared/knowledge/`（5 文件） | **保留 + domain 门面** | 五个类已全部 KMP 且无平台耦合；保留原包，在 `shared/domain/knowledge/` 提供 `KnowledgeBaseService` 门面接口（导入/索引/召回三服务聚合）供跨域调用，避免调用方直接触碰实现类。理由：功能已就位，迁移=纯改名；门面满足「仅经 shared/domain 接口跨域」纪律。 |
| `shared/.../shared/prompt/MemoryV2PromptTemplates.kt`（T18） | **消费（不复制）** | 本卡管线直接引用；SCHEMA/DATA_BOUNDARY 金样已在 T18 闭环。 |
| `app/.../ChatViewModel.kt:3060-3290` 等调用点 | **留在 app** | 注入编排（optionalContextRead 分阶段预算、replacements 组装）归 T15 组装器卡；本卡只交付纯函数构造器（输入 query+配置，输出上下文字符串）。 |
| `app/.../automation/ContextMaintenanceWorker.kt` | **留在 app（归 T22）** | 本卡交付 `retryPendingSources` 与晋升/维护函数本体；iOS BGTaskScheduler 侧调度归 T22。 |
| UI（记忆管理/锚点管理/知识库管理界面） | **留在 app** | 本卡只定义存储/召回/删除语义；UI 状态与手动记忆写入入口归 UI 卡（MANUAL_MEMORY 的存储/召回语义见契约 13/17）。 |

- **Android 侧调用点改动面**（迁移后由 lead 统一裁决执行，本卡不动 app/）：`ChatViewModel.kt:3113-3264`、`GroupChatViewModel.kt` 群聊注入、`MainViewModel.kt` 记忆管理 API、`ContextMaintenanceWorker.kt` —— 改为 `shared.domain.memory.*` / `shared.domain.knowledge.*` 引用。

## 风险与开放问题

- **已知风险**
  1. MemoryV2Pipeline.kt 单文件 1347 行：整文件移植可 diff 但后续维护差；拆组件是纯移动但会破坏 1:1 diff。**建议：先整文件移植通过全部矩阵测试，再由实现卡拆文件（每次拆分后跑同套测试）**。
  2. 本地哈希嵌入的跨平台逐位一致性：FNV-1a 用 `char.code`（UTF-16 码元）+ Int 溢出环绕，Kotlin/JVM 与 K/N 语义一致但**没有平台保证**——验收 #3 的双路径金样就是为锁定这一点。若 iOS 端出现不一致，立即上报（禁止静默改哈希）。
  3. 召回结果金样依赖确定性：本地哈希嵌入是确定性的，但 third_party 嵌入不保证——金样只覆盖本地模式 + fixture 假网关；真实第三方嵌入经矩阵测试（假网关）与实测冒烟覆盖。金样生成来源已锁死为 baseline tag 的 Android oracle 代码（见验收门禁设计），防自证。
  4. java.text 平台化（时间格式化出现在 4 处 + MemoryVectorFormatter）必须逐字节一致（`yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd HH:mm` / `yyyy-MM-dd`，Asia/Shanghai），沿用 T18 PromptClock 注入模式，金样锁定。
  5. 知识库召回的多级超时（1500/750/4500ms）在 iOS 网络栈下的计时误差：以 `withTimeoutOrNull` 语义为准，允许平台调度粒度误差，但注入顺序与内容必须与矩阵一致。
  6. `memory_source_queue` 租约/认领语义（claimToken 校验、过期租约重认领）是并发正确性核心，iOS 侧 SQLDelight 事务语义必须等价（MemoryV2Repository.kt:183-253 已全在 shared，直接复用，风险低）。
- **待 reviewer/lead 裁决**
  1. `shared/memory/` 两个策略文件：**迁移进 `shared/domain/memory/policy/`**（推荐）vs 原地保留由 domain 依赖？
  2. `shared/knowledge/` 是否需要 domain 门面，还是 T15 组装器直接引用现包（推荐：门面，符合跨域接口纪律）？
  3. 召回结果金样的生成来源：Android 运行时实测输出（推荐，oracle 即真理）vs 手写预期值？→ **已裁决（reviewer，2026-11-23）：金样必须由 v1.14.27-android-baseline tag 的 Android oracle 代码生成，禁止 shared 实现自证。**
  4. legacy 锚点（memory_anchors + MemoryRanker + AnchorSourcePolicy）一期是否完整实现：本卡建议**实现**（纯逻辑 + 表已存在，A3 范围明确含锚点），但锚点管理 UI 与展示二期再做——请确认。
  5. 手动记忆（MANUAL_MEMORY）写入入口归属：本卡覆盖存储/召回语义，UI 写入入口归记忆管理 UI 卡——请确认。
  6. 拆文件方案（风险 1）：先 1:1 整文件移植、测试全绿后再拆（推荐）vs 直接按四组件移植。

## 评审结论

- reviewer：**PASS-WITH-FIXES（2026-11-23）** —— 修正项（已全部落实）：
  1. 【P2】金样生成来源明确为 **v1.14.27-android-baseline tag 的 Android oracle 代码**（防 shared 实现自证，见「金样 diff 门禁设计」+ 验收 #2）。
  2. 【P3】敏感词表 15→18（MemoryPrivacyPolicy.kt:55-61 实测 18 条，契约 17 + 验收 #6 已改）。
  3. 【P3】测试任务名统一 `:shared:testDebugUnitTest`（验收表全部行已统一；iOS 侧统一 `:shared:iosSimulatorArm64Test`，删除 `xcodebuild test` 备选写法）。
- lead：（待批）
