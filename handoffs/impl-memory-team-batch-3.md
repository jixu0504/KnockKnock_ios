# 实现报告：智能体团队并行批次 3

| 项 | 结果 |
|---|---|
| 团队 | Lead + `backup` + `usage` + `retention-ui` |
| 验证 | 全量 **677 tests / 8 failed**（基线网络测试）；iOS 构建通过 |
| 迁移 | 新增 `28.sqm`（memory_usage），schema **29**、35 表 |

## 1. T88 备份纳入事实/关系（`backup`）

- `FullBackupWireModels` 增 `MemoryFactWire` / `MemoryRelationWire`，payload 增可空 `memoryFacts` / `memoryRelations`；
- 写出、读取（分页/legacy）、恢复执行器、计数与自检全部接入；
- 恢复时 `memoryId` 经 `memoryItemIdMap` 映射到新 id，旧包缺字段按空；
- 测试 80 项通过（含往返一致与旧包兼容）。

## 2. T73 使用反馈计数（`usage`）

- `memory_usage` 表 + `MemoryUsageRepository.bumpInjected/get`；
- `MemoryRecallEngine.onSelected` 在最终选择（去重/矛盾/主题上限之后）回调 originMemoryId；
- Pipeline 注入回调批量累加 injectionCount；无回调路径不变；
- 新增 6 条测试（SQL 自增、回调去重、无回调不崩）。

## 3. 记忆保留期 UI（`retention-ui`）

- 设置页「记忆」分区新增 30/90/永久三档，写 `clean_days_memory_items`（30/90/-1）；
- 文案：先软删、宽限期后清理、不承诺固定时刻；90 天标建议；默认仍为永久；
- `AppGraphClient` 补 `getIntSetting/putIntSetting` 包装；模拟器构建通过并截图核对默认值。

## 4. 风险 / 遗留

1. **备份内容过滤未覆盖事实/关系**：若 memory_items 被过滤而事实仍在，恢复可能产生孤儿事实（`memoryItemIdMap` 回退原 id）。需在 T88 完整版按 memoryId 过滤。
2. **T10 计数/金样**：已纳入计数键，但历史金样只覆盖旧表；跨版本比对需重生成。
3. **使用计数不去重**：同轮重复构建上下文会重复计数；删除记忆不清 usage 行。
4. **保留期写入路径未做触控验证**（模拟器无法注入触控）；仅确认默认读取。
5. **Android 兼容 DDL** 仍未同步 `memory_facts/relations/usage` 与向量新列。
6. **事实仍只写不读**：conflictStatus/validity 未被冲突队列与 M3 证据链消费。

## 5. 下一步

- T78 完整：冲突队列 + supersedes/valid_to + 事实消费；
- T69 实体表 + T72 实体通道；
- T81–T83 印象实体/证据/纠错；
- T88 完整：内容过滤、跨版本金样、迁移前备份演练。
