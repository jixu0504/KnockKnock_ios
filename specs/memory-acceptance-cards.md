# 记忆系统验收卡（T68 / T74 / T80 / T81 / T82）

> 数字口径：实测值只引用 `docs/eval/baseline-v0.md` 与 `handoffs/impl-memory-*.md`；门槛数字取自计划书 v1.1 对应任务验收列；未跑过的写「未测」，不估算、不外推。
> 证据命令在仓库根执行（iOS 模拟器目标）；「当前实测」= 本卡写作时可复现的 handoff/基线记录。

| 卡 | 结论 | 最大缺口 |
|---|---|---|
| T68 | **部分达标**（仅检索层） | 14/20 组、无抽取层、无 P95、关键负例人工确认未做 |
| T74 | **规格已定稿，待评审** | 实体通道未进回放，reviewer 未跑 |
| T80 | **规格已定稿，待评审** | 抽取合规率、L2 忠实度未测 |
| T81 | **未达标（指标未测）** | 覆盖率/准确率未测；无证据门未硬校验 |
| T82 | **未达标（未接新表）** | 分页/置信度/证据展开/跳会话未做，UI 自检未跑 |

## T68 回放集 v0 与基线

| 项 | 内容 |
|---|---|
| 范围 | 检索层回放：固定数据集、tune/holdout 拆分、指标计算与回归断言 |
| 输入/前置 | `shared/src/commonTest/resources/eval/retrieval-cases-v0.jsonl`；生产默认本地哈希向量 + 真实 BM25；无需网络/模型 |
| 验收条款 | ① 数据集 20 组、每角色 200–300 轮；② tune/holdout 分离且 holdout 只在 Gate 跑；③ 报告含 hit/recall/precision/abstention/重复/矛盾/P50/P95；④ 关键负例 100% 人工确认；⑤ 一条命令产出报告 |
| 门槛 | tune hit@4 ≥0.80、precision@4 ≥0.75、abstention=100%、重复注入=0、矛盾注入=0；holdout 只报告不断言 |
| 证据命令 | `gradle :shared:iosSimulatorArm64Test --tests "com.rhodes.privatechat.shared.eval.RetrievalReplayEvalIosTest" --rerun-tasks` |
| 当前实测 | tune 17/19=89.5%、recall 0.81、precision 0.83、abstention 3/3、重复 0、矛盾 0；holdout 13/16=81.3%、0.78、0.63、3/3、0、0（`baseline-v0`，14 组/41 题） |
| 未达标动作 | 扩到 20 组并接模型抽取做端到端回放；补故障注入与 P95；关键负例逐条人工确认后再报 G0/G1；本组数字早于实体通道（batch-5），不得当作含实体通道的基线 |

## T74 召回规格与回归

| 项 | 内容 |
|---|---|
| 范围 | `specs/memory-recall-v2-spec.md` 定稿 + 召回矩阵按新阈值口径回归 |
| 输入/前置 | 召回引擎（向量/词法/实体通道）、`MemoryRecallMatrixTest` 等矩阵单测、`baseline-v0` |
| 验收条款 | ① 每条能力标「已实现/部分/未实现」且有代码入口；② 阈值与 baseline 一致、预算与规格一致；③ 矩阵覆盖预算档、意图、abstention、事实去重、极性消解、同主题上限；④ RRF 与实体通道各有回归；⑤ reviewer PASS |
| 门槛 | reviewer PASS；且 tune 回归不退（hit@4 ≥0.80、precision@4 ≥0.75、abstention=100%） |
| 证据命令 | `gradle :shared:iosSimulatorArm64Test --tests "*MemoryRecallMatrixTest" --tests "*MemoryLexicalRecallTest" --tests "*MemoryRankFusionTest" --tests "*MemoryFactDedupeTest" --tests "*MemoryEntityRecallTest" --rerun-tasks` |
| 当前实测 | 规格已按实现更新（本卡同日）；矩阵/融合/实体单测随全量 **708 tests / 8 failed** 通过（8 条为基线 `KtorDarwinStreamingTest`）；reviewer **未跑**；实体通道无回放指标 |
| 未达标动作 | 送 reviewer 并记录结论；为实体通道补回放用例（经 Pipeline 装配）；重标定只在 tune 上做，holdout 不动 |

## T80 整合规格与晋升矩阵

| 项 | 内容 |
|---|---|
| 范围 | `specs/memory-consolidation-v2-spec.md` 定稿 + 事实键/晋升/整合策略矩阵回归 |
| 输入/前置 | SCHEMA v3 解析、`memory_facts`/`memory_relations`/`impressions` 表、`MemoryConsolidationPolicy` |
| 验收条款 | ① 事实键格式与极性/qualifier 规则有单测；② 相反极性永不合并；③ 结构化组门 3（强信号 2）+≥2 会话；④ 幂等/退避/dead-letter/预算有单测；⑤ reviewer PASS |
| 门槛 | reviewer PASS；晋升矩阵全绿；**零自动合并相反事实** |
| 证据命令 | `gradle :shared:iosSimulatorArm64Test --tests "*MemoryStructuredFactTest" --tests "*MemoryParsePromotionTest" --tests "*MemoryConsolidationPolicyTest" --tests "*MemoryRelationRulesTest" --rerun-tasks` |
| 当前实测 | 规格已按实现更新（本卡同日）；上述单测随 708 tests / 8 failed 通过；抽取合规率 **未测**；L2 忠实度 **未测**；reviewer **未跑** |
| 未达标动作 | 接模型抽取做端到端回放（合规率目标 ≥85%，属 T75/G2）；L2 忠实度抽检；reviewer 通过后才可称 T80 完成 |

## T81 印象合成器

| 项 | 内容 |
|---|---|
| 范围 | 证据独立性、类型分级门槛、关系档位门控、置信度、双时间；产出 `impressions` + 证据链 |
| 输入/前置 | 该 owner 的 L2/L3 active 记忆（结构化 `topicKey` 优先，legacy 内容前缀兜底）；写入路径与回填路径 |
| 验收条款 | ① 无证据不生成；② 同 factKey 聚合成一条，置信=0.6+0.1×条数（L3 再 +0.1，封顶 0.95）；③ 证据 ≤8，权重 L3/L2/L1=0.9/0.7/0.5；④ 回填可重入且不复活 rejected/frozen；⑤ 形成覆盖率 ≥60%、准确率 ≥80% |
| 门槛 | 覆盖率 ≥60%；准确率 ≥80%；无证据不生成 |
| 证据命令 | `gradle :shared:iosSimulatorArm64Test --tests "*ImpressionBuilderTest" --tests "*ImpressionEvidencePlannerTest" --tests "*ImpressionRepositoryIosTest" --tests "*ImpressionEvidenceIosTest" --rerun-tasks` |
| 当前实测 | 表/构建器/证据链/回填已落地，印象/证据单测随 708 tests / 8 failed 通过（batch-5）；覆盖率、准确率 **未测**；「无证据不生成」无专项断言（当前只在 L2/L3 上聚合，id≤0 的证据会被跳过）；页面未消费新表 |
| 未达标动作 | 补「无证据/无 id」用例并加硬门；抽一批人工标注算覆盖率/准确率；回填标记改为「有失败不置位」；不达标不上线印象页 |

## T82 印象页改版

| 项 | 内容 |
|---|---|
| 范围 | 按角色分组 + 分页 + 置信度/时间/证据展开 + 跳回原会话 |
| 输入/前置 | 新 `impressions` 表与证据链（T81）、角色/会话路由、模拟器 |
| 验收条款 | ① 按角色分组且条数准确；② 分页（默认 20）可加载更多；③ 行内显示置信度与首见/确认时间；④ 证据可展开并跳转原会话；⑤ 截图基线更新；⑥ UI 自检 PASS |
| 门槛 | UI 自检 PASS；截图基线更新；证据可跳转率 100% |
| 证据命令 | `xcodebuild -scheme KnockKnock -destination 'generic/platform=iOS Simulator' build`；模拟器跑印象页 UI 自检（`ImpressionsAutomation` 断言日志）；桥自检 `--impressions-selftest`（日志 `t54-impressions-selftest.log`） |
| 当前实测 | 角色分组 + 层级筛选 + 空态已做（batch-2，模拟器构建通过）；`ImpressionsBridge` 仍读 legacy `memory_items`（batch-5）；分页/置信度/证据展开/跳会话 **未做**；UI 自检、截图基线、跳转率 **未测** |
| 未达标动作 | 先把桥切到 `impressions` 表并展开证据；再补分页/置信度/跳转；重跑 UI 自检与截图基线；T81 覆盖率未达标前不得对外宣称「印象可解释」 |