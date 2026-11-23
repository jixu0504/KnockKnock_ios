# T21 规格卡 handoff：记忆 V2 管线 + 知识库管线

> 代理：memory-v2-spec-card（T21）。规格卡：`ios-port/specs/memory-v2-spec.md`（25 条行为契约 + 10 项验收；**reviewer PASS-WITH-FIXES 2026-11-23，修正已落实**）。
> 状态：**规格卡撰写完成，reviewer 修正项（金样来源/敏感词 18/任务名统一/评审结论回填）已全部落实**。git 不提交（按任务纪律）。写域仅 ios-port/{specs,handoffs}，仓库只读。

## 1. 方法

- 只读 oracle，全程 read/grep（未动 RhodesLink/ 任何文件）。核心 5 个 viewmodel/shared 文件 + shared/{memory,vector,knowledge} 19 文件 + MemoryV2Repository.kt（462 行）+ VectorMemories.sq/KnowledgeBases.sq/迁移链 .sqm + ChatViewModel 注入编排段（3060-3290）逐行读完，共约 5200 行 oracle 覆盖。
- 每条行为契约带 file:line，共 25 条（≥18 达标）：三层记忆 L1/L2/L3（提取/晋升/稳定性门/归档）、六类锚点（MemoryRanker 评分矩阵）、召回限额/优先级矩阵（fast/balanced/deep 六桶预算）、关系网亲密度 5 档、向量双模式 + 签名隔离 + 会话隔离、知识库导入/切块/索引状态机/召回限额/原因码、注入顺序与 T18 接口、隐私策略、异常处理（空库/索引失败/供应商不可用/迁移后行为）。
- 规格卡完全按 `规格卡模板-v1.md` 八节结构；验收全部可自动判定（金样 diff + 矩阵测试 + 双路径命令）；「抽取落点」逐包给出迁移/包装/保留建议与理由。

## 2. 关键发现

1. **三层命名映射**：Android `MemoryLevel{L1,L2,L3}`（shared/model/Enums.kt:12）= 任务要求的 SHORT_TERM/DAILY/LONG_TERM。已在契约 0 声明 iOS 用别名、语义照搬 oracle。
2. **T18 资产已就位，本卡零复制**：`MemoryV2PromptTemplates`（L1×4/L2/L3/SCHEMA/DATA_BOUNDARY）已随 T18 迁 `shared/prompt/` 且金样门禁闭环（handoffs/impl-prompt.md:14,26——MemoryV2 插值模板走**严格逐字节**比对，不入 allowlist）。本卡验收 #1 只做回归，不重复建门禁。
3. **提取/晋升的「失败=重试」语义精确区**：`parseExtractedL1` 三条抛错规则（非数组 / 有内容零有效 / 有效项≤一半）进源队列重试；合法 `[]` 正常完成（MemoryV2Pipeline.kt:923-951）。退避 10s/60s/300s、3 次后 `failSource` 保留窗口（:50-60）。
4. **晋升归档是乐观事务**：`finishPromotion` 复核父证据未变才归档，父归档还要求全部子向量已索引（MemoryV2Repository.kt:21-50）——这是「删除传播」与「索引一致性」的共同根基，已单列契约 9。
5. **隐私强制覆盖在解析层**：`MemoryPrivacyPolicy.forSource` 是策略表，但 `parseMemoryItems` 对 PRIVATE_CHAT 恒 private、GROUP/MOMENT/COMMENT 恒 public 的强制覆盖才是运行时真相（MemoryV2Pipeline.kt:871-879）——两处都写进了契约 17，实现时不可只迁策略表。
6. **签名隔离 = 召回静默为空**：嵌入配置变更后旧签名向量被 SQL 层过滤（LocalVectorStoreGateway.kt:202），召回返回空而非报错；saveMemory 侧才是抛「向量模型配置已变化」。迁移后行为 = 记忆侧重建索引（getActiveMemoryItemsMissingVector）、知识库侧 pending_confirm（契约 19/25）。
7. **限额矩阵是可金样的数值表**：私聊预算 (80,20,30,20,20)/(150,50,60,30,30)/(260,100,100,40,40)（:533-537）；minScore 0.24(fast)/0.16；注入 4→6 条、近 L1 2→3、视觉保 1（:607-633）；关系网亲密度 20-39→(1,80)…≥80→(5,35)（:1239-1245）；知识库 3/3/60/1200/4500/750/1500ms（RecallPolicy.kt:5-11）——全部转成参数化矩阵测试。
8. **维护逻辑与触发点拆分已落实**：`retryPendingSources` 本体（租约/禁用暂停/时间线跳过/退避）在本卡（契约 21），`ContextMaintenanceWorker`（WorkManager 调度）明确归 T22。
9. **平台化点比预想少**：管线本体唯一 JVM 耦合 = java.text（4 处时间格式化 + MemoryVectorFormatter）+ `ConcurrentHashMap` 锁表（MemoryPromotionLocks）；MemoryV2Repository 462 行已全在 shared 可直接复用（源队列租约/认领/删除传播都不重写）。本地哈希 FNV-1a 的跨平台逐位一致性是最需要双路径金样锁定的点（验收 #3）。
10. **数据面已全在 golden/db/schema**：9 张表（memory_items/anchors/batches/links/source_queue/vector_memories/knowledge_bases/chunks/operator_knowledge_bases）均有 .sq 金样；备份语义有 audit/fields.md 佐证（memoryItems/knowledgeBases 进导出、vector_memories 不进、vector_api_key 不进，:37,41,59）。

## 3. 裁决请求状态（reviewer PASS-WITH-FIXES 已核）

1. `shared/memory/`（AnchorSourcePolicy/MaintenancePolicy）迁移进 `shared/domain/memory/policy/` vs 原地保留（推荐迁移）——待拍板。
2. `shared/knowledge/` 是否加 `shared/domain/knowledge/KnowledgeBaseService` 门面（推荐加，符合跨域接口纪律）——待拍板。
3. 召回结果金样生成来源——**已裁决（reviewer，2026-11-23）：金样必须由 v1.14.27-android-baseline tag 的 Android oracle 代码生成，禁止 shared 实现自证**（已写入规格卡门禁设计 + 验收 #2）。
4. legacy 锚点系统（memory_anchors+MemoryRanker+AnchorSourcePolicy）一期完整实现（推荐实现，UI 二期）——待拍板。
5. 手动记忆（MANUAL_MEMORY）写入入口归记忆管理 UI 卡（本卡只覆盖存储/召回语义）——待拍板。
6. MemoryV2Pipeline 拆文件方案：先 1:1 整文件移植再拆（推荐，保证可 diff）vs 直接四组件移植——待拍板。

## 4. 交付物

- `ios-port/specs/memory-v2-spec.md`（25 条契约、9 表清单、10 项验收、6 项裁决请求）。
- 未创建任何实现文件；golden/memory/ 目录与同步脚本扩展由实现代理按验收 #2/#3 生成。
