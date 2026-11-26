# 实现报告：结构化事实键与晋升分组（M2 提前切片）

| 项 | 内容 |
|---|---|
| 依据 | 计划书 v1.1 §4.1.2 / §4.1.3 / §4.2 / §4.4.1；审计 P0-1（晋升不可达）、P1-5（证据错位，已在 M0 修复） |
| 范围 | **T75-lite**（L1 SCHEMA v3 + 结构化字段解析）、**T76a-lite**（极性进入事实键，相反事实不合并）、**T80 草案** |
| 未包含 | T77 整合 Worker、T78 关系语义（supersedes/contradicts）、T69 独立事实列、带旧记忆上下文的提取、真实模型抽取准确率 |

## 1. 改动

### 1.1 提示词 v3（`MemoryV2PromptTemplates.SCHEMA`）

在统一 JSON 结构里新增：`topic` / `subject` / `predicate` / `object` / `polarity` / `qualifiers`，
并补充规则：偏好/事实/计划类必须尽量结构化；否定必须 `polarity=negative`；
`object` 不带主语/动词；`topic` 仅展示。金样 `MemoryV2PromptTemplates_L136_SCHEMA.md` 同步更新，
`GoldenPromptTest` 通过。

### 1.2 事实键（`MemoryParseRules`）

- `structuredTopicKey()`：`fact:<polarity>:<subject>|<predicate>|<object>[|q=…]`；
- 谓词同义归一（dislike/prefer/reside/work/allergy/promise/plan/own/study/birthday）；
- 极性缺省时按谓词推断；qualifiers 进入键（条件不同不合并）；
- 任一段缺失 → 回退旧版内容指纹，v0.2.0 行为不变；
- 事实键存进现有 `memory_items.topicKey`（**本次不新增列**，M2 完整版再迁到独立字段）。

### 1.3 晋升分组（`pickPromotionTopic`）

- 分组键改为 `item.topicKey`（结构化事实键或旧指纹），不再每次从 content 重算；
- **移除「条目总数 < 阈值直接返回」的外层门**：结构化组只按组门判定；
- 结构化组门 = 3 条（强信号 = 2 条，取 `importantPromotionThreshold`）；
- 结构化组要求 ≥2 个不同会话，强信号允许单会话多条；旧指纹组保持 20/2 与原行为；
- 相反极性 → 不同键 → 永不合并。

### 1.4 版本与可追溯

`MemoryBatch.promptVersion` 升级为 `memory_v2_l1_v3` / `l2_v3` / `l3_v3`，便于区分新旧产物。

## 2. 验证

| 项 | 结果 |
|---|---|
| 新增单测（5 条） | 结构化字段 → 事实键；三种措辞无需 20 条逐字相同即可成组；相反极性不合并；同会话非强信号不晋升 / 强信号 2 条可晋升；旧指纹组仍要 20 条 |
| 全量单测 | **634 tests / 8 failed**（8 条为基线既有、需本地测试服务的 `KtorDarwinStreamingTest`） |
| 提示词金样 | `GoldenPromptTest` 通过（SCHEMA 金样已同步） |
| iOS 构建 | 见本轮回复（framework + xcodebuild） |

### 1.5 相反极性同轮注入保护（T78 前置）

- 召回选中后比较事实键基座：`fact:<polarity>:<base>` 中 base 相同、极性相反的条目只保留较新的一条；
- 旧条目不删除、不归档（避免在用户确认前猜测哪条成立），仅本轮不注入；
- 回放集新增 g10（同一事实正/负两条），`矛盾注入用例 = 0`；单测覆盖「保留较新否定」。

## 3. 风险与限制

1. **抽取合规率未测**：模型是否稳定输出 `subject/predicate/object/polarity` 只有真实回放能验证；
   不合规的条目会回退旧指纹，表现为「晋升仍不可达」，不会产生错误合并。
2. **object 归一化偏弱**：模型写「吃香菜」与「香菜」会得到不同键 → 少合并（安全方向），
   需要 T75 的提示词校准或对象归一规则。
3. **topicKey 语义复用**：同时承担展示主题、旧指纹与事实键；后续 T69 迁到独立 `fact_key` 列，
   并为 `fact:` 前缀数据补迁移。
4. **无 supersede/contradicts**：只保证「相反极性不合并」；旧事实仍会与新事实并存，
   过期事实率要等 T78。
5. **无独立整合 Worker**：晋升仍发生在提取协程尾部，长会话/多角色下可能饥饿（T77）。

## 4. 下一步

1. **T68 端到端补齐**：接入模型抽取，验证结构化字段合规率与 L2/L3 形成率（G2 观察值）；
2. **T77**：整合 Worker（独立调度、预算、幂等、dead-letter）；
3. **T78**：关系语义与双时间，关闭过期事实；
4. **T69**：事实键迁到独立列 + 索引，消除 `topicKey` 复用。