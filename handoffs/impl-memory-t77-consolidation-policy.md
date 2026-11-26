# 实现报告：整合幂等 / 退避 / 预算（T77 最小切片）

| 项 | 内容 |
|---|---|
| 依据 | 计划书 §4.4.2（预算、调度与幂等）、审计 X-06（重复调用）/X-07（失败窗口）、R3（预算失控） |
| 范围 | 在现有晋升路径入口加「候选签名幂等 + 退避 + dead-letter + 每角色/设备日预算」 |
| 未包含 | 独立 Worker（队列、跨角色轮转、checkpoint、租约、可中断续跑）、印象合成预算、dead-letter 的 UI 重试入口 |

## 1. 改动

- **`MemoryConsolidationPolicy`**（commonMain）：
  - 幂等签名 = `ownerType + ownerId + sourceKind + 候选集合（L1 id/updatedAt/importance 排序后 FNV）+ promptVersion`；
  - `shouldSkip`：同签名成功、同签名 dead-letter、退避未到 → 跳过；
  - 失败退避 10s / 60s / 300s，3 次后 `failed`（dead-letter）；新证据改变签名 → 自动解除；
  - 预算：每角色每天 4 次整合运行、设备每天 300 次（可在 settings 调整）；只在真的要调用模型时计数。
- **`MemoryV2Pipeline.maybePromoteOwnerMemoryLocked`**：入口读取状态与签名，跳过不必要运行；
  L2/L3 调用前执行预算检查；成功/失败写回状态；取消异常不记为失败。
- **SettingsRepository**：新增 `memory_consolidation_owner_daily_limit`（默认 4）与
  `memory_consolidation_device_daily_limit`（默认 300）；状态与计数均落 settings，无数据库迁移。
- **dead-letter 可观测**：首次进入 `failed` 时累加 `memory_consolidation_dead_letters` 并记录
  `memory_consolidation_last_error`；记忆体检行追加「整合失败 N 次（最近：…）」。

## 2. 验证

| 项 | 结果 |
|---|---|
| 新增单测（5 条） | 签名顺序无关/变化敏感；成功同签名跳过；失败退避与 3 次 dead-letter；新签名解除；每角色/设备预算；状态序列化往返 |
| 全量单测 | **642 tests / 8 failed**（8 条为基线既有、需本地测试服务的 `KtorDarwinStreamingTest`） |
| iOS 构建 | framework + `xcodebuild` 通过 |

## 3. 限制

1. **仍不是独立 Worker**：晋升仍由提取协程尾部触发；跨角色轮转、队列持久化、checkpoint/租约留给 T77 完整版。
2. **预算口径近似**：一次运行最多 L2+L3 两次请求，按「运行次数」计数；精确到每次请求的分类计数需要 T87/成本面板配合。
3. **状态在 settings**：适合单设备；未来若要跨设备同步/审计，需要迁到表。
4. **dead-letter 只能看不能重试**：记忆体检行已显示失败次数与原因，但手动重试入口仍属 T87。

## 4. 下一步

- T78：`memory_relations` + 双时间，关闭过期事实（supersedes/contradicts）；
- T69/T71：事实键迁独立列、向量 BLOB；
- T68 端到端：接模型抽取测量结构化字段合规率与 L2/L3 形成率。
