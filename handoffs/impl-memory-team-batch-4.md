# 实现报告：智能体团队并行批次 4

| 项 | 结果 |
|---|---|
| 团队 | Lead + `conflict` + `entities` |
| 验证 | **687 tests / 8 failed**（基线网络）；iOS 构建通过；schema **30**、38 表 |

## 1. T78 冲突标记（`conflict`）
- `MemoryFacts.sq` 增标记/计数/列表查询；`MemoryFactRepository.markConflict/countSuspected/listSuspected`；
- Pipeline 在 `contradicts` 关系成立时把新旧 fact 置 `conflictStatus='suspected'`（缺失忽略），日志只写计数；
- 额外在 `MemoryV2Repository` 加转发方法（唯一超 scope 改动）；
- 3 条 SQL 测试通过。

## 2. T69 实体表与抽取（`entities`）
- `MemoryEntities.sq` + `29.sqm`：`canonical_entities` / `entity_aliases` / `memory_entities`；
- `MemoryEntityRepository`：upsertEntity/linkMemory/findMemoryIdsByNames/getEntitiesForMemory；
- `MemoryEntityExtractor` 纯函数：subject/object → 实体草稿（名字归一、role=subject/object）；
- 7 条测试通过；**未接 Pipeline**（Lead 下一步集成）。

## 3. 风险 / 遗留
1. 实体未接线、未被召回消费；实体通道（T72）仍未做。
2. 冲突标记没有确认 UI/队列消费；`updatedAt` 被刷新。
3. `supersedes`/`valid_to`/双时间未做。
4. 备份内容过滤仍未按 memoryId 过滤事实/关系。
5. Android 兼容 DDL 未同步。

## 4. 下一步
- Lead：实体接线 + 实体召回通道；冲突队列暴露到体检页；
- T78 完整：supersedes/valid_to/确认入口；
- T81–T83 印象实体/证据/纠错。
