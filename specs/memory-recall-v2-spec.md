# 记忆召回 v2 规格卡（T74）

| 项 | 内容 |
|---|---|
| 结论 | 词法 BM25、两阶段准入、abstention、RRF、同主题上限、事实去重/极性消解、实体通道均已实现；**完整 MMR、反馈三态、实体别名与跨来源未实现** |
| 状态 | 实现完成（T72 主体）；T74 待 reviewer；阈值只在 tune 标定，holdout 未参与调参 |
| 代码基线 | `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/` 下 `domain/memory/{MemoryRecallEngine,MemoryLexical,MemoryV2Pipeline}.kt`、`data/MemoryLexicalIndex.kt`、`domain/chat/InjectionBudget.kt` |
| 上游 | 计划书 v1.1 §4.5.4/§4.6/§4.7；审计 P1-1/X-03/X-04；基线 `docs/eval/baseline-v0.md` |
| 验收口径 | 本卡约束「候选如何生成、如何准入、如何裁剪」；实测值只引用 baseline 与 handoff，未测项单列 §4 |

## 1. 能力状态（结论先行）

| 能力 | 状态 | 代码入口 | 缺口 / 证据 |
|---|---|---|---|
| 词法 BM25 | 已实现 | `MemoryLexicalIndex.indexItem/search/ensureReady/warm`、`MemoryLexical`（BM25） | 中文 2–3 元 n-gram，k1=1.2/b=0.75；单条 ≤160 term；`docCount ≠ active 条数` 即整所有者重建；插入同事务增量写 |
| 两阶段准入 | 已实现 | `MemoryRecallEngine.buildPrivateChatMemoryContext`（准入段） | 向量沿用 v0.2.0 阈值；词法=意图阈值+IDF≥1.5；双通道=0.15/1.5/≥2 词+判别性词 |
| Abstention | 已实现 | 同上（无候选通过即返回空串） | tune/holdout 均 3/3 |
| RRF | 已实现 | `MemoryRecallEngine.fuseRanks/channelRanks` | Σ1/(60+rank)，只改排序；词法整轮无分时回退标定分排序 |
| 查询意图 | 已实现 | `MemoryQueryIntent.classify` | 规则分类、零模型调用；FACT/TIME/RELATIONSHIP/PREFERENCE/UNKNOWN；「哪天/什么时候」归 FACT；阈值按意图取 |
| 同主题上限 | 已实现（MMR-lite） | `MemoryRecallEngine.capPerTopic` | 同主题最多 2 条；**完整 MMR（相关性-多样性权衡）未做** |
| 事实级去重 | 已实现 | `MemoryRecallEngine.dedupeByFact` | 同事实 L1/L2/L3 只留层级最高一条（X-03） |
| 相反极性消解 | 已实现（展示层） | `MemoryRecallEngine.resolveContradictions` | 只注入较新一条；不删不归档；确认队列归 T78 |
| 实体通道 | 已实现（使用侧） | `MemoryV2Pipeline.mergeEntityRecallCandidates` | 仅 operator 私聊；查询含完整实体名（≥2 字）；固定 lexicalScore 2.6 / IDF 2.5；无别名与边界规则 |
| 词法单通道名额上限 | 已实现 | `MemoryRecallEngine.selectPrivateInjection`（上限 2） | 字面重合不得挤满预算 |
| 注入整行截断 | 已实现 | `InjectionBudget.mergeContextBlocksWithinBudget` | 超预算整行丢，不输出半句、不留空标题 |
| 使用计数 | 部分 | `MemoryRecallEngine.onSelected` → `MemoryUsageRepository.bumpInjected` | 只写 injectionCount；retrieved/useful 列存在但未写；无 salience |
| 诊断 | 已实现 | `MemoryDiagnostics.log`：`Memory/Recall`、`/词法`、`/实体`、`/矛盾消解`、`/降级` | 只写计数/意图/耗时，不写正文与 query 原文 |

## 2. 通道与准入（数值契约）

| 项 | 值 |
|---|---|
| 候选通道 | 向量按 `memory_v2_l1/l2/l3/manual_memory/anchor_vision` 分桶（fast 80/20/30/20/20，balanced 150/50/60/30/30，deep 260/100/100/40/40）+ 词法 ≤120 命中回表 + 实体 ≤40 条记忆 |
| 词法准入阈值 | FACT 2.0 / TIME 2.5 / RELATIONSHIP 2.0 / PREFERENCE 3.5 / UNKNOWN 3.0 |
| 判别性词 | 至少命中 1 个 IDF ≥1.5 的 term，否则总分达标也不准入 |
| 双通道一致 | similarity ≥0.15、lexicalScore ≥1.5、命中 ≥2 个查询词、有判别性词 |
| 排序 | RRF 优先；无词法分时 `similarity×0.70 + importance×0.15 + recency×0.10 + typeBonus + lexicalBoost`，加分 ≤0.25 |
| 选择预算 | 常规 4 条 / 时间类 6 条；近期 L1 ≤2（扩展 3）；L2/L3/manual 合计 1；强信号 1；词法单通道 ≤2；同主题 ≤2 |
| 注入 | user 角色「【本轮参考资料】」+ 数据边界声明，≤1200 字符；不得回退 system |

## 3. 实测（检索层，`baseline-v0`）

| 指标 | tune（调参集） | holdout（保留集） |
|---|---|---|
| hit@4 | 17/19 = 89.5% | 13/16 = 81.3% |
| Recall@4 | 0.81 | 0.78 |
| Precision@4 | 0.83 | 0.63 |
| abstention | 3/3 | 3/3 |
| 重复注入 / 矛盾注入 | 0 / 0 | 0 / 0 |

覆盖边界：只测向量哈希嵌入 + BM25（14 组 / 41 题）；**实体通道在 `MemoryV2Pipeline` 内，回放测试直接构造引擎，结构上不覆盖**；不含模型抽取，也不含印象。语义类 5 题（年龄/周末/饮料/养猫/乐器）仍失败，属哈希向量能力边界。

## 4. 未实现 / 未验收

| 项 | 状态 | 归属 |
|---|---|---|
| 完整 MMR（多样性重排） | 未实现（仅同主题上限） | T72 完整版 |
| 实体通道的回放覆盖与阈值标定 | 未做（固定分 2.6/2.5 未经回放） | T68 → G1 |
| RRF/实体通道后的整体重标定 | 未做（现数值来自双通道基线） | T68 |
| 实体别名/边界规则（2 字子串误命中） | 未做 | T72 |
| 群聊/跨来源实体召回 | 未做（仅 operator 私聊） | T72/T84 |
| 反馈三态 retrieved/injected/useful、salience | 部分（仅 injection） | T73 |
| 真机 P50/P95 与端到端召回率 | 未测 | G1 |

## 5. 不做（防止越权/漂移）

- 向量相似度**不得**授权合并或覆盖；词法/实体命中**不得**用于自动合并；未标定阈值不得因个案放宽；调参只走回放集 tune，holdout 只在 Gate 运行。