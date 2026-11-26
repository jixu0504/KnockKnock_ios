# 实现报告：智能体团队并行批次 6

| 项 | 结果 |
|---|---|
| 团队 | Lead + `impressions-bridge` + `backup-impressions` + `specs` |
| 验证 | **709 tests / 8 failed**（基线网络）；framework + `xcodebuild` 通过；schema 32 不变 |

## 1. T82 印象页切新表（`impressions-bridge`）
- `ImpressionsBridge.list()` 优先读 `impressions`（active+frozen，long_term/ongoing→L3/L2）；整表为空才回退 `memory_items` 旧视图；
- DTO 补 `impressionId/confidence/evidenceCount/status/firstSeenAt/lastConfirmedAt/dataSource`；
- 新增 `evidence(id)`（`memoryId|weight|时间`，≤8 条）、`reject/freeze/unfreeze`（status 落库；冻结可解冻，拒绝不可逆）；
- Swift 行内展示置信度/证据数/状态，行尾菜单操作；保留角色分组与层级筛选。

## 2. T88 备份印象/证据（`backup-impressions`）
- payload 增可空 `impressions` / `impressionEvidence`；wire 全字段；
- 恢复顺序：证据先删→印象先删→…→印象→证据；impressions 新增自增 id 映射，证据两侧 id 都回填；
- 计数/自检扩至 26 表 27 项；旧包（full-v1）两数组为 null、两表 0 行；
- 备份测试 81 项通过（新增 acceptance18）。

## 3. T74/T80 规格（`specs`）
- `memory-recall-v2-spec.md`、`memory-consolidation-v2-spec.md` 逐项标注已实现/部分/未实现 + 代码入口；
- 新增 `memory-acceptance-cards.md`：T68/T74/T80/T81/T82 的范围、条款、门槛、证据命令、当前值/未测，数字只引用 baseline 与 handoff。

## 4. 风险 / 遗留
1. 印象列表每角色 200 条上限、**无分页**；证据只有 id/权重，无正文与跳转。
2. **拒绝不可撤销**（冻结可解冻）；无「编辑纠正」（T83 未做）。
3. 证据未参与备份内容过滤，手工残缺包可能落悬空证据，需 `deleteOrphans` 清理。
4. 实体通道未被回放覆盖；holdout 语义类失败改善未测。
5. 冲突标记无确认入口；supersedes/valid_to 未做；Android 兼容 DDL 未同步。

## 5. 下一步
- T82 分页 + 证据正文/跳转；T83 编辑纠正与撤销拒绝；
- 真机/DB 回放测实体通道与 P95；T78 冲突确认 UI；发布 Gate 实测（抽取合规率、L2/L3 形成率）。
