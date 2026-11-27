# 实现报告：长期记忆可达 + 自定义记忆（v0.3.5 / build 7）

## 1. 问题（用户反馈：记忆管理始终没有长期记忆）

真机数据库实测（升级前）：

| 层级 | 条数 |
|---|---|
| L1 短期 | 144 active（+2 archived） |
| L2 日常 | 1 |
| L3 长期 | **0** |

不是「没提取到」，而是**晋升链路不可达**，三个卡点叠加：

1. **分组过细**：事实键为精确召回带宾语（`user|prefer|铃身上的香气` / `user|prefer|铃的身体`），
   每组的真实规模只有 1~2 条；L1→L2 组门是 20 条（强信号 2 条），**真机最大组只有 2 条**。
2. **单会话被一刀切拒绝**：`requiredGroupSize` 对结构化事实要求 ≥2 个会话，否则返回 `Int.MAX_VALUE`。
   用户主要在一个会话里聊，等于永久禁止晋升。
3. **L3 双门过严**：除了 L2 组门复用 L1 的 10 条之外，`isStableEnoughForL3` 还要求
   「跨 3 天或 ≥4 条证据」，同日聊天永远不满足。

## 2. 修复

| 改动 | 说明 |
|---|---|
| `MemoryParseRules.consolidationKeyOf`（新） | 整合分组按「极性 + 主语 + 谓语」聚合，宾语记 `汇总` 占位（保持三段式键可解析）；单值谓语（reside/work/birthday/age/live_city）仍保留宾语，避免「住杭州/住上海」被并成一条 |
| `requiredGroupSize` | 单会话允许整合，但要求同话题证据 ≥3 条；1~2 条仍拒绝（保留「证据要够厚」的原意） |
| `isStableEnoughForL3` | 改为「类型稳定或有强证据」+「跨 ≥1 天、或 ≥3 条、或强证据」 |
| `memoryV2PromoteL3Threshold`（新设置） | L2→L3 独立组门，默认 3，不再复用 L1→L2 的 10 |
| `MemoryBridge.promote`（新） | 手动定层级（短期/日常/长期），升长期把过期时间置为最大值；只允许 `memory_items`（`v2`/空等价），legacy 派生表拒绝 |
| `MemoryItems.sq` `updateMemoryLevel` | 层级更新语句（不动内容与向量索引） |

真机数据离线回放新规则：**可升 L2 的组从 1 个变为 15 个**（`relationship|promise` 18 条跨 2 会话、
`user|prefer` 13 条跨 2 会话等），L3 亦可达成。

## 3. 新增功能

- **手动添加记忆**：记忆管理页右上「＋」→ 角色 / 内容（≤500 字）/ 层级 / 重要度 / 类型 / 私密；
  空内容时保存置灰。
- **记忆详情「记忆层级」卡**：短期 / 日常 / 长期一键切换，升长期不再被清理策略回收。

## 4. 验证

| 项 | 结果 |
|---|---|
| 真机记忆自检（`--selftest --memory-mgmt-selftest`） | **PASS=true passed=32 failed=0** |
| 手动提级 → 长期列表可见 | PASS（`promote.level=L3` / `promote.listedInL3 count=1`） |
| 降级回日常可逆 | PASS |
| 自定义记忆直接建长期 | PASS（level=L3，source=MANUAL_MEMORY） |
| 真机截图 | `docs/assets/memory-l3-0.3.5.png`、`docs/assets/memory-custom-0.3.5.png` |
| 全量单测 | **未执行**：本机模拟器运行时被清空（`simctl runtime list` 为 0 个镜像），按用户要求不下载运行时 |

自检过程中修掉两个真 bug：
1. 添加记忆页因 `.knockHideSystemChrome()` 隐藏了系统导航栏，**保存/取消按钮不可见** → 改为自绘顶栏；
2. 手动提级把 `sourceTable="v2"` 误判为非法来源而拒绝 → 放行 `v2`/空，仅拒绝 legacy。

## 5. 待办

模拟器运行时恢复后补跑 `:shared:iosSimulatorArm64Test`（新增 5 个晋升规则用例 + 更新 1 个旧口径用例）。
