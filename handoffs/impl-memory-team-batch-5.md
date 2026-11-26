# 实现报告：智能体团队并行批次 5

| 项 | 结果 |
|---|---|
| 团队 | Lead（实体接线/召回/集成）+ `impression-entity` + `impression-evidence` |
| 验证 | **708 tests / 8 failed**（基线网络）；iOS 构建通过；schema **32**、40 表 |

## 1. 实体收口（T69/T72，Lead）

- `MemoryEntityExtractor` 停用词补「用户/角色/说话人」：抽取提示词的说话人写法不是实体，否则几乎每条记忆都挂同一实体；
- Pipeline 事实落库后写实体并 `memory_entities` 关联（role=subject/object）；
- 召回新增实体通道：查询包含 owner 实体目录中的归一名字（长度≥2）→ 直接用 `findMemoryIdsByEntityNames` 换回记忆；
  固定 `lexicalScore=2.6`、`maxTermIdf=2.5`（过 FACT/TIME 准入、不冒充高相关），已由词法命中的不重复加入；
- 新增 3 条实体相关性单测；抽词法/实体冲突由回归覆盖。

## 2. 印象实体与证据（agents）

- `impressions` 表（迁移 30）：owner/character/layer/subject/predicate/value/polarity/factKey/confidence/tone/证据数/首见/确认/status；
- `ImpressionBuilder`：L2/L3 按 factKey 聚合，L3→long_term、L2→ongoing，置信 0.6+0.1×条数（≤0.95），legacy 键兜底；
- `memory_impression_evidence`（迁移 31）：印象-记忆证据，L3 0.9/L2 0.7/L1 0.5、最多 8 条；
- Pipeline：L2/L3 落库后聚合印象并挂证据；结构化键回查同事实全部 active 记忆，legacy 只用本次条目。

## 2.1 历史回填（Lead 补）

- `MemoryV2Repository.rebuildImpressionsForOwner`：取 owner 的 L2+L3 active 记忆 → `ImpressionBuilder` → upsert + 证据；
- A7 `runHousekeeping` 一次性触发（`impressions_backfill_done` 标记），失败不置位、下次重试；
- 只回填不删除：`rejected/frozen` 状态由 `ImpressionRepository.upsert` 保留，不会被回填复活。

## 3. 风险 / 遗留

1. **ImpressionsBridge 仍读 legacy `memory_items`**：新印象表尚未接到页面，UI 看到的仍是旧过滤视图。
2. **印象/证据未纳入备份 v2**；恢复后印象表为空，需 T81/T88 扩展。
3. **实体通道只覆盖私聊 operator**，且只在查询中出现完整实体名时生效；未接群聊/跨来源。
4. **实体名归一仍是子串匹配**：短名（2 字）可能误命中，需 T72 的别名/边界规则。
5. **印象聚合仅在新写入时触发**：历史 L2/L3 没有回填，需要一次性 rebuild。
6. **冲突标记无确认 UI**、supersedes/valid_to 未做；Android 兼容 DDL 未同步。

## 4. 下一步

- ImpressionsBridge 切到新表 + 证据展开 + 纠错/冻结（T82/T83）；
- 备份 v2 纳入 impressions/evidence；历史回填任务；
- T78 冲突确认入口；T74/T80 规格定稿与 Gate 材料。
