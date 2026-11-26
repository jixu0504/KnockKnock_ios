# 实现报告：智能体团队并行批次 2

| 项 | 内容 |
|---|---|
| 目标 | 并行推进 T69 事实表、T72 RRF/MMR、印象页分组，Lead 独占迁移与全量验证 |
| 团队 | Lead + `facts-schema` + `rrf` + `impressions-ui` |
| 结果 | 三路完成并集成；全量 **670 tests / 8 failed**（基线网络测试）；iOS 构建通过 |

## 1. 工作流

### T69 事实与有效性表（`facts-schema`）
- `MemoryFacts.sq` + `27.sqm`：`memory_facts`（memoryId PK、owner、subject/predicate/object、polarity、qualifiers、factKey、validFrom/validTo、recordedAt、timeSource、conflictStatus）；
- `MemoryFactRepository`：upsert/get/findByFactKey；`MemoryV2Repository` 转发；
- `MemoryParseRules.StructuredFact`：复用谓词归一/极性推断，`structuredTopicKey` 行为不变；
- Pipeline 仅在 L1 落库时写事实，失败不阻断记忆保存；解析 7 例 + SQLite 2 例测试。

### T72 RRF 与同主题上限（`rrf`）
- `MemoryRecallEngine`：在合并候选上计算向量/词法名次，`fused = Σ1/(60+rank)`，先融合分后原分排序；**准入阈值不变**；
- `capPerTopic`：同一 fact/topic key 最多 2 条，保留选中顺序；
- 新增 2 条单测；memory 90 项全过，回放指标与基线一致。

### 印象页分组（`impressions-ui`）
- 仅改 `ImpressionsView.swift`：按角色 section（RoleAvatar + 名称 + 条数）、层级筛选、空态保留；模拟器构建通过。

## 2. Lead 集成

- `DatabaseMigrationTest`：schema 27→**28**、34 表；升级 18→28 断言 `memory_facts` 存在；
- 全量回归 + framework + `xcodebuild` 通过。

## 3. 风险 / 遗留
1. **备份不含 `memory_facts`**：文件级备份包含 DB 表，但全类目备份的 wire 模型未含该表；恢复/导出前需 T88 扩展。
2. **手动/导入/恢复路径不写事实**：只有 L1 抽取路径写；历史数据与恢复数据无事实行，需回填任务（T64 扩展）。
3. **事实表尚未被召回/冲突队列消费**：当前只写不读（下一步 T78 冲突队列、M3 证据链）。
4. **主题上限取 subject|predicate 偏粗**：RRF 主题键复用事实键；legacy 指纹主题可能过细。
5. **印象页分组未真机验证**；筛选为新增控件，需产品确认视觉。
6. **Android 兼容**：新表未在 Android 兼容 DDL 中同步；当前只构建 iOS。

## 4. 下一步
- T78 完整：conflictStatus 消费、supersedes/valid_to、待确认队列；
- T69 实体表 + T72 实体通道；T73 反馈三态；T74/T80 定稿；
- T88 备份 v2 纳入 memory_facts/relations 与迁移前备份。
