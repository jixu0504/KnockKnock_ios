# 实现报告：记忆检索底座 M1-A（T70 + T72 部分 + X-04）

| 项 | 内容 |
|---|---|
| 依据 | `docs/KnockKnock-记忆与印象系统升级项目计划书-v1.1.md` §4.5.4 / §4.6 / §4.7（M1：T70、T72） |
| 前置 | `handoffs/impl-memory-m0-wiring.md`（M0 已落地：摘要/清理/重建/恢复/诊断/正确性） |
| 本次范围 | **T70 倒排 + BM25 统计**；**T72 的词法通道、查询意图、两阶段准入、abstention、词法单通道名额上限**；**X-04 整行截断** |
| 未包含 | T68 回放集与基线（本次阈值因此未标定）；T69 事实字段 schema；T71 向量 BLOB；T73 反馈三态；T74 规格卡；实体通道（T70 的实体表部分） |

---

## 1. 本次改动

### 1.1 存储（迁移 24 / schema v24→v25）

新增 4 张表（`MemoryLexical.sq` + `24.sqm`）：

| 表 | 用途 |
|---|---|
| `memory_terms(term, ownerType, ownerId, memoryId, tf)` | 倒排（tf） |
| `memory_term_stats(term, ownerType, ownerId, documentFrequency)` | BM25 的 df |
| `memory_document_stats(memoryId, documentLength)` | BM25 的 dl |
| `memory_corpus_stats(ownerType, ownerId, docCount, totalDocumentLength, updatedAt)` | BM25 的 N / avgdl，同时充当索引新鲜度标记 |

**版本号偏离说明**：计划书 §9.1 把倒排排在 v26、向量 BLOB 排在 v25。本次让词法通道先落地，占用迁移 24；
向量 BLOB（T71）顺延到后续迁移编号。已在 `24.sqm` 文件头与本节记录，Gate 追溯时以本文件为准。

`DatabaseMigrationTest` 同步更新：fresh 32 张表、`Schema.version = 25`、升级 18→25 且存在 `memory_terms`。

### 1.2 索引写入与一致性（`MemoryLexicalIndex`）

- **增量**：`MemoryV2Repository.insertMemoryItemInternal` 在插入记忆的同一事务内调用 `indexItem`，
  写倒排 + doc stats + df + 语料统计；事务失败则整笔回滚，不会留下「有记忆、搜不到」。
- **陈旧判定**：`ensureReady` 比较 `memory_corpus_stats.docCount` 与 active 记忆条数；不一致
  （首次、删除、归档、备份恢复、批量导入）即**整所有者重建**。这样无需在十几个删除路径里各写一遍
  增量清理，也避免漏改导致索引漂移。
- **检索**：一次取 df、postings、doc length 三组数据，Kotlin 侧按 BM25 求和排序（k1=1.2, b=0.75）。
- **预热**：启动/回前台的后台维护会为所有有 active 记忆的 owner 检查并重建索引（`MemoryLexicalIndex.warm`），
  避免升级后第一次聊天在 12s 提示词预算内同步扫全库。
- **写放大控制**：SQLite 3.18 方言不支持 UPSERT，改用 `INSERT OR REPLACE ... SELECT` 单语句自增；
  每条记忆的写入由「2×term 次」降为「1×term + 1 次语料自增」。
- **体积上限**：单条记忆最多索引 160 个 term（`MAX_TERMS_PER_DOCUMENT`）。120 字中文约产生
  119 bigram + 118 trigram，不设上限会让倒排体积超过向量本身；160 的上限让典型记忆近似「bigram 全量 +
  少量 trigram」。这是容量与召回的折中，T79 会用同一上限做体积标定（见 §4 风险）。

### 1.3 召回接入（T72 部分）

`MemoryV2Pipeline` 构造 `MemoryRecallEngine` 时注入词法候选提供者：

```
BM25 命中（≤120） → 回表 active 记忆 → 过滤来源开关 → VectorMemory(originMemoryId, lexicalScore)
```

引擎侧（`MemoryRecallEngine`）：

1. 向量候选与词法候选按向量 id 去重合并；同一记忆同时命中两条通道时保留两边的分数
   （否则「向量相似度低但词法很强」的条目会被当成纯向量候选拒掉）；
2. `MemoryQueryIntent` 规则分类（FACT / PREFERENCE / TIME / RELATIONSHIP / UNKNOWN），零模型调用；
3. 两阶段准入：
   - 向量通道：沿用原相似度阈值（**未改动**，避免无标定的回归）；
   - 词法通道：`BM25 ≥ 按意图的阈值` 且至少命中一个 `IDF ≥ 1.5` 的判别性词
     （FACT 2.0 / TIME 2.5 / RELATIONSHIP 2.0 / PREFERENCE 3.5 / UNKNOWN 3.0，已在 T68 v0 调参集标定）；
   - 双通道一致：向量相似度 ≥0.15、词法 ≥1.5、命中 ≥2 个查询词且有判别性词；
   - 都不满足 → 不注入（abstention）；
4. 词法单通道命中的注入名额上限 **2 条**，避免字面重合挤满预算；
5. 词法分只作为加分项（上限 +0.25），不让 BM25 的绝对尺度压过向量相似度。

**隐私/来源约束不变**：词法候选先按 `isMemoryInjectionAllowed("private_chat", sourceKind)` 过滤；
注入位置仍是 user 角色「【本轮参考资料】」+ 数据边界声明。

### 1.4 注入预算（X-04）

`mergeContextBlocksWithinBudget`（`domain/chat/InjectionBudget.kt`）替换原先的 `take(maxChars)`：

- 按整行填充，超预算整行丢弃，绝不输出半句；
- 一个块只剩标题行能放下时整块丢弃（不出现「【长期印象】」下面没有内容）；
- 多块共享预算，顺序与原先一致。

---

## 2. 验证

| 项 | 结果 |
|---|---|
| 共享层编译 | ✅ |
| 全量单测 | **623 tests / 8 failed**：8 条均为基线已存在的 `KtorDarwinStreamingTest`（需本地 127.0.0.1 测试服务，环境原因） |
| T68 检索层回放（新增） | `RetrievalReplayEvalIosTest`：8 组 / 27 题；tune hit@4 12/14、precision 0.83、abstention 1/1；holdout 8/11、0.69、2/2（见 `docs/eval/baseline-v0.md`） |
| 新增测试（15 条） | `MemoryLexicalRecallTest`（分词/BM25 手算/意图/准入/abstention/名额上限/双通道合并 8 条）、`MemoryLexicalIndexIosTest`（重建/归档重建/增量一致/预热 3 条）、`InjectionBudgetTest`（4 条） |
| 迁移测试 | `DatabaseMigrationTest` 通过（v25、32 表、18→25 升级含 `memory_terms`） |
| iOS App 构建 | 见下方「构建验证」 |

**构建验证**：`xcodebuild -scheme RhodesLink -destination 'generic/platform=iOS Simulator'` 在本次改动后重新执行
（结果见本轮回复；若失败会先修复再交付）。

## 3. 明确的「未标定」清单

计划书要求准入阈值由回放集标定（§0.2 批准条件、§4.6.3）。本次没有回放集（T68 未做），因此：

- 词法阈值（2.0/2.5/3.0/4.0）、双通道地板（0.10/1.0）、词法加分上限（0.25）、名额上限（2）
  **全部是保守初始值**，只保证「无强信号不注入」，不代表已调到最优；
- 每次召回会在诊断日志写一行 `Memory/Recall/词法：intent=… 候选=… 准入=… 阈值=…`（不含正文），
  供 T68 采集真实分布后回填；
- 在 T68 完成前，**不建议**继续放宽这些阈值；若线上出现「无关记忆被注入」，优先调高 FACT/TIME 阈值。

## 4. 风险与后续

| 风险 | 说明 | 缓解 |
|---|---|---|
| 倒排体积 | 160 term/记忆 ≈ 数 KB；默认保留期永久时长期会累积 | T79 体积标定；T63 清理对 archived/expired 的记忆不删倒排行（重建时才清）——建议 T79 增加「删除倒排」路径 |
| 索引陈旧窗口 | 编辑内容但 active 条数不变时，索引可能落后一代 | 排序受影响、正文仍从 DB 读；后续在 `updateMemoryItemContent` 里标记重建 |
| 写事务变重 | 每条新记忆多写 ~160 行 | 已在事务内、单语句自增；如真机 P95 变差，可在 T71 一并做批量写入 |
| 阈值未标定 | 见 §3 | T68 → G1 校准 |
| 词法通道噪声 | 同义/否定仍靠字面 | 极性/事实结构在 M2（T75/T76a）解决；词法只做候选，不做合并授权 |

## 5. 下一步（按计划书顺序）

1. **T68 回放集 v0**：用 `Memory/Recall/词法` 诊断行采集分布 → 标定本次阈值；
2. **T69**：`memory_items` 事实字段 + `memory_relations`（M2 的地基；同时解决「编辑后索引陈旧」的显式标记点）；
3. **T71**：向量 BLOB + 格式元数据（注意迁移编号顺延）；
4. **T73**：反馈三态（retrieved / injected / useful），禁止「注入即有用」；
5. **T74**：`specs/memory-recall-v2-spec.md`，把本次阈值与准入规则写成契约。