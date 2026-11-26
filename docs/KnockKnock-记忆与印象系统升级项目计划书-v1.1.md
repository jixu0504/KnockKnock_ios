# KnockKnock_ios 记忆与印象系统升级项目计划书 v1.1

| 项 | 内容 |
|---|---|
| 项目 | 敲敲（KnockKnock）iOS —— 记忆与印象系统升级（Memory & Impression v2） |
| 代码仓库 | `github.com/jixu0504/KnockKnock_ios`（默认分支 `main`，基线 commit `0e08a814` / tag `v0.2.0`） |
| 工作副本 | 本机 `敲敲开发 workplace/rhodeslink_ios/`（目录名为历史遗留；`origin` 指向 KnockKnock_ios，与远端 `main` 逐提交同步） |
| 上游依据 | 《审计报告：KnockKnock_ios（敲敲）v0.2.0 —— 记忆系统与印象系统》v1.1 → `docs/审计-记忆与印象系统-0.2.0.md` |
| 本版性质 | **v1.0 的修订版**（v1.0 已归档，不再作为批准依据）。本版吸收外部架构审阅的全部 P0/P1 意见 |
| 修订重点 | 事实正确性（去重/冲突）、检索评分口径、BM25 数据完整性、iOS 后台执行假设、隐私与访问控制、Gate 指标有效性、排期口径 |
| 执行模式 | DSH 代理开发（lead + 领域子代理 + reviewer + QA），人类保留签字点 |
| 编写日期 | 2026-11-26 |
| 版本 | v1.1（待批准） |

> **命名澄清**：本计划的目标工程是 **KnockKnock_ios（敲敲）**，不是 Android 原版 RhodesLink。
> 工程已统一为 `KnockKnock`（target / scheme / 源码目录 / 产物），bundle id 为 `com.xsbcjx.knockknock`；历史文档中的 `RhodesLink` 仅指 Android 原版。
> 命名清理（target/目录改名）**不在本项目范围**，单列为可选的运维任务。

---

## 〇、批准摘要（一页纸）

**做不做**：做。v0.2.0 的「长期记忆」主干链路是断的（晋升不可达、摘要不生成、清理不运行、换模型/恢复即失忆）。继续加功能只会放大沉没成本。

**做什么**：把记忆从「聊天附属的碎片库」升级为**事实正确、可演化、可解释、可纠错、离线可用、不越权**的角色记忆系统；把「大家的印象」从只读过滤视图升级为一等实体。

**怎么做（v1.1 的核心修正）**：
1. **正确的合并靠结构，不靠向量**——向量相似度只用于召回候选，**不得单独授权合并或覆盖**；去重分三级（确定性重复/疑似同义/潜在冲突），冲突进队列而不是猜。
2. **ADD-only 不等于不看旧记忆**——在线提取先检索 Top-N 相关旧记忆作为**只读去重上下文**，只输出 ADD 候选；取代由后台整合器在有证据的前提下判定。
3. **检索分两段**——RRF 只做排名融合；相关性准入用回放集标定的规则 + 查询类型权重，**允许不注入**（abstention）。
4. **BGTask 只是补充**——核心维护由前台空闲与启动 catch-up 保证，全部任务可中断续跑；不再假设「每日必跑」。
5. **隐私即访问控制**——`privacy` 不再兼任「可见范围」；跨角色共享必须用户逐条授权。
6. **指标不靠数量**——印象不以「至少 N 条」驱动，改看「有证据的候选中的形成率 + 准确率 + 证据可追溯率」。

**多久**：**基准 9.6 周墙钟**（乐观 6.2 周 / 悲观 12.8 周），约 **106 代理日**（等价 135 人日，含 15% 集成缓冲）。
**多少钱**：API 基准 **约 ¥0.89–1.64 万**（乐观 ¥0.14–0.24 万 / 悲观 ¥2.96–4.94 万）。

**怎么验收**：不靠「与 Android 一致」，靠**长对话回放集**上的产品指标（Recall@4 + Precision@4 + 过期事实率 + 矛盾注入率 + 重复注入率 + abstention + 证据可追溯率 + P50/P95 + 迁移/恢复可恢复率），五道 Gate 逐档放行；评测集在 M0 就位，**调参集与保留集分离**。

**最大风险**：① 无端侧语义模型时召回上限（G1 不达标则提前启动 Core ML PoC）；② 迁移期双表/双索引一致性；③ 自动合并误判伤害信任 —— 用「分级去重 + 冲突队列 + 用户纠错」三重兜底。

### 0.1 v1.0 → v1.1 修订记录（逐条回应外部审阅）

| # | 外部审阅意见 | 优先级 | v1.1 处理位置 |
|---|---|---|---|
| 1 | 近似去重（同 topic + 余弦≥0.92）可能误合并相反事实 | P0 | 删除单条件合并 → **§4.2 三级去重**、原则 P3；新增任务 **T76a** |
| 2 | ADD-only 与「不看旧记忆」被混为一谈 | P0 | 原则 P2、**§4.1 旧记忆只读上下文**（mem0 v3 实测流程核对见附录 C） |
| 3 | BM25 缺 per-term DF / doc length / avgdl | P0 | **§4.6.2 三张统计表**；任务 T70 扩为含统计一致性 |
| 4 | RRF 分数不能直接接 3σ 语义阈值 | P0 | **§4.6.3 两阶段检索**（融合排序 → 校准准入 + abstention）；T72 扩容 |
| 5 | iOS BGTask 被假设为「每日必跑」 | P0 | **§4.10 机会式调度**：前台保证 + 启动 catch-up + checkpoint；删除「每日一次」「24h 内」等硬承诺 |
| 6 | Schema/产品/验收不一致（level 枚举、日志原文、P2 未映射） | P0 | **§4.5** 统一枚举、**§4.11** 日志去原文、**§8.4 P2 闭环表** |
| 7 | 事实结构不足（主体/谓词/极性/时间条件） | P1 | **§4.1.3 事实字段** + 双时间定义（§4.1.4） |
| 8 | `superseded_by` 表达不了多种关系 | P1 | **§4.3 关系语义**（supersedes / contradicts / refines / supports / contextual）+ `memory_relations` 表 |
| 9 | 实体通道只做字符串精确匹配 | P1 | **§4.6.4 实体规范化**（canonical id + 别名表） |
| 10 | 向量 BLOB 缺维度/格式/归一化，迁移不可续跑 | P1 | **§4.5.2 向量表字段** + **§9.1 迁移状态机** |
| 11 | 回填时间会把未知伪装成真实 | P1 | **§9.2** `NULL`/`time_source` 标记，禁止伪造 `source_message_ids` |
| 12 | 印象 Schema 语义不一致、缺冻结/拒绝抑制/编辑历史/证据外键 | P1 | **§4.5.5** 完整 DDL（含 `is_frozen`、`rejected_at`、`impression_history`、`impression_suppressions`） |
| 13 | 召回日志 `queryText` 与「不写原文」冲突 | P0（隐私） | **§4.5.6** 删除原文列，改脱敏摘要；默认关闭 |
| 14 | 删除与证据传播规则未成矩阵 | P1 | **§4.12 11 类操作矩阵** |
| 15 | 「注入即有用」的自我强化 | P1 | **§3.4 三态信号**、**§4.6.6 salience 定义** |
| 16 | 印象「≥8 条」是数量驱动指标 | P0（评估） | **§2.2** 改为「有足够独立证据的样本中的形成率 + 准确率 + 可追溯率」 |
| 17 | 跨来源 ≠ 跨隐私范围 | P1 | **§4.9 逐条授权**、**§4.8.3 可见性矩阵** |
| 18 | 审计 P2 未映射任务 | P1 | **§8.4 P2 闭环追溯表**（X-01…X-09） |
| 19 | hit@4 基线写「推测值」与 G0 实测冲突 | P0 | **§2.2** 基线一律「待测」；推测值移入 planning assumption，不参与 Gate |
| 20 | 只测 hit@4 不够 | P0 | **§2.2 指标矩阵**（Precision/stale/contradiction/duplicate/abstention/evidence/P95/rebuild） |
| 21 | 回放集没有调参集/保留集分离 | P0 | **§8.1** 分离规则与分层/对抗样本要求；**T68 前移到 M0** |
| 22 | 并行度 2.2 与 2.5 口径不一致 | P1 | **§6.1** 全期统一 `c=2.2`，峰值另行说明 |
| 23 | 用户画像层绑定核心交付 | P1 | **§10 MVP 界定**：画像层 UI 延后，核心记忆改造不依赖它 |
| 24 | 预算缺模型版本冻结与评测/生产分计 | P1 | **§11** 增加冻结约定、评测预算单列、暂停阈值 |
| 25 | 新任务卡建议（T68a/T70a/T72a/T76a/T77a/T81a/T84a/T88a） | P1 | **§7** 采纳，其中 T70a/T72a/T77a 折算进 T70/T72/T77（避免任务碎片化），T76a/T81a/T84a/T88a 独立成卡 |
| 26 | 开源源组件应标注「是否产生运行时依赖」 | P1 | **§5 选型表新增列** + 四类分组 + **附录 D PoC 模板** |
| 27 | 「USearch Swift Package 声明 iOS 15」 | — | **外部核对更正**：现 `Package.swift` 为 `iOS(.v18)`（与本 App 一致）；仍不做 MVP 依赖（附录 C） |

### 0.2 批准条件（15 项，全部满足方可进入 M1）

- [ ] 明确 ADD-only、旧记忆只读上下文、后台整合三者的边界（§4.1、§4.3）；
- [ ] 删除「本地哈希向量余弦≥0.92 即自动合并」的单条件规则（§4.2）；
- [ ] 设计事实极性、主体、有效时间、记录时间与冲突状态（§4.1.3/§4.1.4）；
- [ ] 补齐 BM25 所需 per-term DF、document length、avgdl 数据结构（§4.6.2）；
- [ ] RRF 仅用于排名融合，准入阈值由回放集校准且支持 abstention（§4.6.3）；
- [ ] 明确 query intent 与实体识别的实现来源，默认不新增隐式 LLM 调用（§4.6.1/§4.6.4）；
- [ ] 完成向量 BLOB 维度/格式定义与可续跑迁移状态机（§4.5.2、§9.1）；
- [ ] 修正印象 level 枚举、冻结、拒绝抑制、编辑历史与证据外键（§4.5.5）；
- [ ] 解决 `queryText` 与「日志不写原文」的冲突（§4.5.6）；
- [ ] 明确 BGTask 仅作补充、核心任务可前台续跑与中断恢复（§4.10）；
- [ ] 增加全局模型调用预算与活跃角色调度（§4.4.2）；
- [ ] 审计 P2 逐条映射到任务与验收（§8.4）；
- [ ] 修正 G1/G4 指标，加入 Precision、过期事实率、重复注入率与拒绝注入能力（§2.3）；
- [ ] 将最小回放集与基线工具前移至 M0（§6、T68）；
- [ ] 统一排期并行度口径（§6.1）。

---

## 一、背景与问题定义

### 1.1 一句话现状

v0.2.0 的记忆子系统**零件齐全、长期链路未通电**：三层记忆 + 向量召回 + 记忆管理页都实现了，但产生「长期感」的四条链路——**晋升、滚动摘要、生命周期清理、索引重建**——在 iOS 端全部断开或不可达。用户实际获得的能力约等于「最近 15 轮原文历史 + 最多 4 条 180 字碎片」。

### 1.2 问题清单（编号与审计报告 v1.1 一致）

| ID | 问题 | 严重度 | 计划书对应工作包 |
|---|---|---|---|
| **M-01** | 晋升分组键是「内容指纹」，L2/L3 在自然对话中几乎不可达 | P0 | T75 / T76a / T77 / T78 |
| **M-02** | 滚动摘要与记忆维护在 iOS 从未运行（5 个触发点 0 调用点） | P0 | T62 |
| **M-03** | 备份恢复后 L2/L3 归档 + 向量清空 + 无重建 | P0 | T64 / T65 / T88 / T88a |
| **M-04** | 换嵌入配置后旧向量永久失效，无重建入口 | P0 | T64 / T88 |
| **M-05** | 事实冲突/变更无覆盖机制 | P0 | T76a / T78 |
| **M-06** | 召回是字面重合 + 绝对阈值 + 候选被预截断 | P1 | T70 / T71 / T72 / T73 |
| **M-07** | 无使用反馈闭环 | P1 | T73（按 §3.4 三态信号） |
| **M-08** | 无界增长（默认永久、清理不跑、JSON 存向量、印象无分页） | P1 | T63 / T79 / T82 |
| **M-09** | 诊断与成本不可见 | P1 | T66 / T87 |
| **M-10** | 晋升证据按下标对齐，会挂错父项 | P1 | T67 |
| **I-01** | 印象是只读过滤视图，无独立模型 | P1 | T81 / T81a / T82 |
| **I-02** | 印象主流程几乎无生产者 | P0 | T77 / T81 |
| **I-03** | 只收私聊来源 | P1 | T84 / T84a |
| **I-04** | 无证据链、无置信度、只读 | P1 | T81 / T82 / T83 / T81a |
| **I-05** | L1 段无门槛、页面无分页 | P1 | T72 / T82 |
| **X-01…X-09** | 死代码/隐私语义/重复注入/截半条/群聊复制/幂等/失败窗口/死设置/规格漂移 | P2 | 见 §8.4 闭环表 |

### 1.3 为什么现在做

按「每天 100 条消息、3 个常聊角色」推算（静态估算，口径见审计报告附录 B）：记忆行 ≈1.2 万行/年/角色、嵌入按现状 ≈8 KB/行 → 约 290 MB/年；每轮召回固定解码 ≈2.5 MB JSON；晋升产出 0 条 L2/L3；恢复备份一次 = 召回归零。

**结论**：不做升级，产品每多跑一年，「记忆」这个卖点就多贬值一年。

### 1.4 反范围

| 不做 | 理由 |
|---|---|
| 云端记忆服务 / 账号同步 | 与本地优先、明文不上云的定位冲突；另立项目 |
| 端侧大模型 / 本地 LLM 推理 | 与记忆系统无直接关系，体积与算力不可控 |
| 记忆跨 App 共享（App Intents、Spotlight） | 二期再议 |
| 语音/ASR/TTS、麻将、游戏 | 无关既有 backlog |
| target/目录改名 | 独立运维任务，避免写域冲突 |
| 知识库重构 | 只做「与记忆共享检索底座」的接口预留，不改切块/索引/召回语义 |
| 关系图可视化 | 二期（§10 可延后项） |

---

## 二、目标与成功指标

### 2.1 产品目标（三条）

1. **记得住且记得准**：跨周、跨月的偏好/约定/事件能被正确想起；**过期事实不再被当作现状**；矛盾事实不同时注入。
2. **会成长**：角色对用户的「认识」随互动从「互动印象」沉淀为「持续了解」再到「长期印象」，并能被关系亲密度驱动演化；**证据不足时宁可不形成**。
3. **可信任**：看得见「为什么记得」、能改/能删/能冻结/能纠错、能重建；记忆健康与成本一目了然；跨角色共享必须授权。

### 2.2 指标体系

> **原则**：基线一律写「待测」，G0 实测后回填。规划阶段的估值只作预算参考（planning assumption），**不得参与任何 Gate 判定**。
> 所有指标必须在**固定测试集 + 固定模型版本 + 固定提示词版本**上测量（见 §11 冻结约定）。

| 类别 | 指标 | 定义/口径 | 基线 | Gate 目标 |
|---|---|---|---|---|
| 召回 | **Recall@4 / hit@4** | 标注「应被想起」的问题中，注入内容含正确事实的比例 | 待测 | G1：≥ max(基线+15pp, 60%)；G4：≥75% |
| 召回 | **Precision@4** | 注入条目中「确实相关且正确」的比例 | 待测 | G1：不得低于基线；G4：≥70% |
| 正确性 | **过期事实率（stale fact rate）** | 已 `valid_to` 关闭的事实被当作现状注入的比例 | 待测 | G2：≤10%；G4：≤5% |
| 正确性 | **矛盾注入率** | 同一轮同时注入两条 `contradicts` 事实的比例 | 待测 | G2：≤10%；G4：≤3% |
| 正确性 | **重复注入率** | 同一事实以 L1/L2/L3 多版本同时出现的比例 | 待测 | G3：≤5% |
| 正确性 | **abstention 质量** | 无相关记忆时正确选择「不注入」的比例；无关问题不强行注入 | 待测 | G1：≥80% |
| 记忆质量 | 事实抽取准确率 | L1 条目人工抽检「无幻觉/无错归因」 | 待测 | ≥85% |
| 记忆质量 | 矛盾率（库内） | 同 fact_key 下仍有效的冲突对占比 | 待测 | G2：≤10%；G4：≤5% |
| 印象 | **印象形成覆盖率** | 在「独立证据足够」的候选主题中，最终形成印象的比例 | 待测 | G3：≥60% |
| 印象 | 印象准确率 | 人工抽检「事实正确 + 角色视角合理 + 无幻觉」三项全过 | — | G3：≥80% |
| 印象 | 证据可追溯率 | 每条印象都能展开到真实会话/消息并跳转的比例 | — | G3：100% |
| 印象 | 用户否决后复活率 | 用户「不对」后，同主题相似印象再次自动生成的比例 | — | G3：0（抽样 50 条） |
| 性能 | 召回 P50 / P95 | 单轮记忆召回（含嵌入、解码、排序，本地模式） | 待测 | G1：P95 ≤300 ms |
| 性能 | 注入预算 | 每轮记忆相关注入字符数（UTF-8 预算，按字符） | 待测 | ≤1200 字符 |
| 成本 | 记忆 token 占比 | 记忆相关请求 token / 聊天请求 token | 未计量 | ≤12% |
| 成本 | 月模型调用 | 每角色每月记忆相关调用次数；全设备日调用上限 | 待测 | 角色 ≤180 次/月；设备 ≤300 次/日（可配） |
| 稳定性 | 失败可见率 | 记忆管线失败中「有诊断 + 可重试」的比例 | 0% | 100% |
| 稳定性 | **索引重建成功率** | 迁移/恢复/换签名后，索引可恢复的比例与耗时 | 0% | G4：≥95%，≤24h（下次活跃内完成） |
| 稳定性 | 任务续跑率 | 被系统中断的后台任务在下次活跃时成功续跑的比例 | — | G4：≥95% |
| 存储 | 单角色年增量 | 记忆相关存储年增量 | 待测（估算 ≈100 MB） | ≤20 MB |

### 2.3 Gate（逐档放行，直接采纳外部审阅的加强版）

| Gate | 位置 | 判定条件（全部满足） |
|---|---|---|
| **G0** | M0 结束 | ① 摘要/重试/重建/清理在真机链路可触发且有日志；② 队列可中断续跑、坏窗口不阻塞 cursor（故障注入测试）；③ 基线报告产出（2.2 全部指标实测，含 P50/P95）；④ 审计 P2 清单（X-01…X-09）已建卡；⑤ 评测集 v0（含调参/保留拆分）可用 |
| **G1** | M1 结束 | ① schema 迁移无损（含旧备份恢复）；② BM25 统计自洽（抽样手算 = 程序）；③ Recall@4 ≥ max(基线+15pp, 60%) 且 Precision@4 不下降；④ 过期事实率、矛盾注入率不升高；⑤ abstention ≥80%；⑥ 真机 P95 ≤300ms |
| **G2** | M2 结束 | ① 事实关系语义完整（supersedes/contradicts/refines/supports/contextual 各有测试）；② 矛盾率 ≤10%、重复注入率 ≤10%；③ 证据独立性达标（不同会话数/日期跨度）；④ 整合任务幂等（重跑不重复扣费）与全局预算生效；⑤ L2/L3 形成速度仅作观察值，不设硬产量配额 |
| **G3** | M3 结束 | ① 印象形成覆盖率 ≥60%、准确率 ≥80%、证据可追溯率 100%；② 用户编辑不被覆盖、冻结不被改写、拒绝后不复活（抽样 50 条）；③ 跨角色隐私隔离（未授权时 A 不能召回 B 私聊事实）；④ 印象页按角色分组 + 分页 |
| **G4** | M5 结束 | ① 备份/恢复/换嵌入/删除传播/后台中断/清理/7 天真机长跑全部通过；② Precision@4、Recall@4、过期事实率、矛盾注入率、重复注入率同时达标；③ 索引重建成功率 ≥95%；④ 成本与性能达标；⑤ 文档与规格卡 v2 归档 |

---

## 三、总体技术路线

### 3.1 七条设计原则（v1.1 修订）

| # | 原则 | 说明 |
|---|---|---|
| **P1** | 产品语义优先于 Android parity | 验收以产品指标为准；Android 仅作数据兼容参考；旧金样降级为回归基线 |
| **P2** | **ADD-only 提取，但允许只读旧记忆上下文** | 在线提取先检索 Top-N 相关旧记忆，**只用于识别重复/补充/潜在冲突**；提取器只输出 ADD 候选，不直接 UPDATE/DELETE；取代由后台整合器在有证据时判定并保留历史 |
| **P3** | **向量相似度不得单独授权合并或覆盖** | 向量只用于召回候选；合并需事实结构一致，冲突需显式关系判定（§4.2/§4.3） |
| **P4** | **哈希向量是粗召回通道，不是语义召回** | 默认本地模式 = 词法 + 实体 + 时间 + 哈希向量；真正的同义/概念级召回依赖可选第三方嵌入或端侧模型 |
| **P5** | 双时间与可追溯 | 每条事实都有「有效时间」与「记录时间」；每条派生结论都有证据链；未知就是未知，不伪造时间 |
| **P6** | **隐私即访问控制** | `privacy`（敏感度）与 `visibility`/授权（谁能看）分离；跨角色共享逐条授权，默认不传播 |
| **P7** | 前台保证、后台补充、可中断续跑 | BGTask 是机会式补充；核心维护必须能在前台空闲/启动 catch-up 内继续，且每次执行有预算与 checkpoint |

### 3.2 目标架构

```
                 ┌──────────────── 写入（在线，轻；1 次 LLM） ────────────────┐
聊天消息 ─▶ 窗口(12条) ─▶ 检索 Top-N 旧记忆（只读去重上下文）
                       ─▶ L1 提取（ADD-only，含事实结构与时间）─▶ 精确/结构去重 ─▶ memory_items
                                                                        ├─▶ 倒排索引（n-gram+IDF）
                                                                        └─▶ 实体索引（规范化）
                 ┌──────────────── 整合（后台，重；节流+预算+幂等） ─────────────┐
前台空闲 / 启动 catch-up / BGTask(补充) ─▶ 整合 Worker（每角色，状态机+checkpoint）
   ├─ 去重合并（A 确定性 / B 疑似同义交模型判定 / C 冲突入队）
   ├─ L2 聚合（fact_key/topic + 实体 + 时间窗 + 证据独立性）
   ├─ L3 稳定化（跨会话证据门 + 稳定性门）
   ├─ 印象合成（character_impressions + evidence，按关系档位门控）
   ├─ 关系判定（supersedes / contradicts / refines / supports / contextual）
   └─ 衰减归档（salience，权重标定，上下限）
                 ┌──────────────── 检索（在线，多信号两阶段） ─────────────────┐
query = 最近3轮 + 意图分类（规则）+ 本地实体识别（词典/别名）
  阶段1 候选生成与融合排名：向量 TopK ┬ 词法 BM25 TopK ┬ 实体 TopK ┬ 时间 TopK ─▶ RRF
  阶段2 相关性准入与重排：按查询类型加权 + 校准阈值（或分位）→ 允许不注入
        → lineage/父子去重 → MMR → 预算裁剪（整行截断）
                 ┌──────────────── 呈现（可解释、可纠错、不越权） ─────────────┐
「大家的印象」按角色分组：长期印象 / 持续了解 / 互动印象（来自 impression 表）
        每条：正文 + 语气 + 置信度 + 形成/最近确认时间 + 证据（可跳会话）
        操作：「不对」（归档+抑制）/ 编辑（不被覆盖）/ 冻结 / 允许补充证据 / 撤销编辑
「记忆体检」：条数/索引状态/失败窗口/任务续跑/成本/最近召回（脱敏）
```

### 3.3 ADR 摘要

| # | 决策 | 备选 | 选择理由 |
|---|---|---|---|
| D1 | 在线提取 = ADD-only + **旧记忆只读上下文** | 不检索旧记忆（v1.0 原案） | mem0 OSS v3 实测流程为先检索 Top-10 旧记忆做去重上下文再单次 ADD-only，且用哈希做精确去重；盲 ADD 会让重复与冲突堆积 |
| D2 | 事实结构：`subject/predicate/object/polarity/qualifiers`（可结构化字段 + 原文兜底） | 仅自然语言 + 向量 | 没有极性/时间字段就无法可靠判冲突；纯向量在否定与时间变化上不可靠 |
| D3 | 关系用 `memory_relations` 多类型 | 单一 `superseded_by` | 「不同」≠「失效」：补充/支持/条件成立都必须保留旧事实 |
| D4 | 双时间：`valid_from/valid_to`（有效时间）+ `recorded_at`（记录时间） | 单时间戳 | 用户 11 月才说「8 月已搬家」时不能把 valid_from 记成 11 月 |
| D5 | 检索两阶段：RRF 排名 → 校准准入（查询类型加权） | RRF 分数直接接 3σ 阈值 | RRF 是排名分而非相关性概率；跨查询类型分布不可比 |
| D6 | 默认不做端侧语义模型 | 立刻引入 Core ML/ONNX | 体积/延迟/质量需 PoC；先靠「剥离前缀 + IDF + 混合通道 + 校准」拿到大部分收益，G1 未达标则提前启动 PoC |
| D7 | 印象 = 一等实体 + 状态机（编辑/冻结/拒绝抑制/历史） | 只读过滤视图 | 过滤视图无法表达演进、纠错与抑制 |
| D8 | 分层保留期 + 软删宽限 + 自动重建 | 永久保留 | 无界增长且不可逆；分层符合「碎片会过期、长期结论不朽」 |
| D9 | 验收 = 回放集指标（调参集/保留集分离） | 金样 diff 为主 | 金样只能证明「与 oracle 一致」 |
| D10 | 隐私拆成 `sensitivity`（敏感度）+ `visibility`/授权 | 单 `privacy` 字段 | 「私密」与「谁可以看」是两种语义（审计 X-02） |
| D11 | 后台执行 = 前台保证 + 机会式后台 | 依赖 BGTask 每日必跑 | iOS 不保证调度时刻；必须可中断续跑 |
| D12 | 评测/生产模型版本在每个 Gate 内冻结 | 随时换模型 | 否则不同 Gate 指标不可比 |

### 3.4 反馈三态（v1.1 新增，替代「注入即有用」）

| 事件 | 定义 | 落库 |
|---|---|---|
| **Retrieved** | 进入候选集但未注入 | `retrieval_count` |
| **Injected** | 实际进入本轮上下文 | `injection_count`、`last_injected_at` |
| **Useful / Confirmed** | 用户点「有帮助」、或用户主动确认/补充、或该记忆被后续对话引用且未被推翻 | `useful_count`（强信号，权重最高） |

**禁止**：仅凭「被注入」就提升权重；仅凭「回复里出现记忆关键词」作为唯一有效性判断（易自证）。

---

## 四、技术方案

### 4.1 写入：事实抽取与旧记忆上下文

#### 4.1.1 在线提取流程（每 12 条消息或空闲合并窗口）

1. **检索旧记忆（只读）**：按本窗口内容检索同角色 Top-10 相关旧记忆（混合检索，§4.6），作为**去重上下文**拼进提示词；它们**不可被提取器修改**。
2. **单次 ADD-only 提取**：输出候选事实（含结构化字段与时间），不输出 UPDATE/DELETE。
3. **窗口内精确去重**：同 `fact_key` + 同极性 + 同时间条件 → 合并为一条（合并 `mention_count`）。
4. **落库**：写入 `memory_items` + 倒排 + 实体索引（同一事务或可恢复索引任务）。
5. **空闲合并**：App 空闲 ≥10 分钟时，把最多 3 个待处理窗口合并为一次 LLM 调用（成本优化），上限 30 条消息。

#### 4.1.2 SCHEMA 变更（提示词 v3）

在现有字段基础上新增（模型输出）：
- `topic`：2–6 字主题标签（**仅展示与弱分组**，不作唯一分组键，见 §4.6.1）；
- `subject`：`user | character | relationship | world | other`；
- `predicate`：规范化谓词（喜欢/不喜欢/居住/从事/拥有/承诺/计划/关系…，允许自由文本但优先枚举）；
- `object`：对象文本；
- `polarity`：`positive | negative | unknown`；
- `qualifiers`：程度/条件/范围（如「周末」「最近」「暂时」）；
- `valid_from` / `valid_to`：事件时间（可为空 = 未知）；
- `recorded_at`：由系统写入（不采信模型）；
- `confidence`：抽取置信度（0–1）。

**约束**：结构化失败时允许只写自然语言原文并标 `fact_kind=freeform`（保证不阻塞写入）；但**含否定词/时间限定/程度词的条目必须尝试结构化**，否则进 `needs_review`。

#### 4.1.3 事实标识（fact_key）

`fact_key = normalize(subject) | normalize(predicate) | normalize(object)`：
- `normalize` = 去标点、统一全半角、英文小写、数字/单位规范化；
- 谓词同义归一化（喜欢/爱吃 → `prefer`；住在/搬家到 → `reside`）；
- `fact_key` 仅表示「可能是同一事实」，**不表示极性/时间成立**（极性/时间单独比较）。

#### 4.1.4 双时间定义

| 字段 | 含义 | 来源 |
|---|---|---|
| `valid_from` | 事实在现实世界开始成立的时间 | 模型抽取（相对时间由系统按消息时间换算）；无法确定 → NULL |
| `valid_to` | 事实在现实世界停止成立的时间 | 整合器判定 `supersedes` 时写入；NULL = 仍成立 |
| `recorded_at` | 系统获知该事实的时间 | 系统写入（写入时刻） |
| `time_source` | `explicit`（用户明说）/ `inferred`（模型推断）/ `unknown` | 抽取时标注 |
| `time_precision` | `minute / day / month / year / unknown` | 抽取时标注 |

### 4.2 去重三级（不可用向量单条件合并）

| 级别 | 判定条件 | 动作 |
|---|---|---|
| **A. 确定性重复** | 同 owner + 同 `fact_key` + 同极性 + 同 `valid_from`（或均未知）+ 无相互矛盾的 qualifiers | 合并证据：`mention_count++`、`last_confirmed_at=now`、importance 取 max，**不产生新版本** |
| **B. 疑似同义** | 同 topic/实体/谓词族，候选相似，但结构/极性/时间不完全一致 | 建立 `memory_relations(supports/refines)` 候选，交后台整合模型判定；**不合并** |
| **C. 潜在冲突** | 同 `fact_key` 但 polarities 相反、object 冲突、时间状态变化、qualifiers 互斥 | **禁止自动合并**；进入冲突队列 `conflict_status=suspected`，保留两条证据，等待整合判定或用户确认 |

**强制规则**
1. 向量相似度只用于召回候选，**不得单独触发合并或覆盖**；
2. 含否定（不/没/未/别/无）、程度变化（有点/非常/不再）、时间限定（以前/现在/暂时/最近）、条件限定（如果/周末/工作时）的记忆 → 必须走 B 或 C；
3. 用户明确纠正（「不是……」「我已经不……了」）作为高优先级证据，但仍保留原事实历史；
4. 无法判断是否冲突 → 保留两条并标 `needs_review`，**不得猜测性取代**。

### 4.3 关系语义（替代单一 superseded_by）

`memory_relations(from_id, to_id, relation_type, confidence, decided_by, createdAt)`

| 关系 | 含义 | 对 old 的影响 |
|---|---|---|
| `duplicate_of` | 完全重复（A 级） | 合并证据，不新建版本 |
| `supports` | 新证据再次确认 | `mention_count++`、`last_confirmed_at` 更新 |
| `refines` | 补充细节（对象更具体） | 保留旧事实；新事实可作为更精确版本优先注入 |
| `contextual` | 场景不同但都成立（工作日/周末） | 两条都保留，按查询场景选择 |
| `contradicts` | 互相矛盾但无法确认谁对 | **不关闭旧事实**；标 `conflict_status=confirmed`，降低双方置信度，进入待确认队列 |
| `supersedes` | 用户状态变化，旧事实自某时点起不再成立 | 写 `valid_to = 新事实 valid_from（或 now）`、旧事实 `superseded_by=新 id`；**保留历史与证据** |

**只有 `supersedes` 可以推进 `valid_to`。** 所有关系必须由后台整合器写入（不允许 L1 提取器直接生成 `supersedes_ids`）。

### 4.4 整合 Worker

#### 4.4.1 任务序列（每角色，状态机）

```
pending → running → (done | retry_wait | failed | cancelled)
每步持久化：cursor(memoryId)、attempt、nextRetryAt、leaseUntil、runnerVersion
```

1. **L1 维护**：补索引、修复失败窗口、处理 `needs_review`；
2. **去重与关系**：A 级合并；B/C 级提交模型判定（输入：候选对 + 证据 + 旧事实 + 时间）；
3. **L2 聚合**：按 `fact_key`/实体/主题 + 时间窗分组，要求「不同会话数 ≥2」或「强信号 + 单会话多条」；输出 1–3 条 L2 + `evidence_ids` + 关系建议；
4. **L3 稳定化**：候选 L2 需「不同会话 ≥3 且日期跨度 ≥7 天」或「importance ≥90 + 证据 ≥2 会话」；输出 1 条；
5. **印象合成**：见 §4.8；
6. **衰减归档**：`salience` 更新，低热度 L1 归档（不删除）；
7. **收尾**：预算统计、checkpoint、下次调度时间。

#### 4.4.2 预算、调度与幂等

| 项 | 规则 |
|---|---|
| 单角色节流 | L2 ≤6 次/天、L3 ≤2 次/天、印象 ≤1 次/天（可在设置调整） |
| **全局预算** | 设备级：模型调用 ≤300 次/天（可配）；单次运行 CPU/DB 批次上限；超限自动暂停并在体检页标注 |
| **活跃角色** | 仅对「最近 7 天有消息」或「有新证据」的角色调度；不活跃角色不空跑 |
| 并发 | 全局并发 1（避免限流与电量），同角色互斥 |
| 超时 | 单次模型 50s；整任务单次执行 ≤120s（超时落 checkpoint） |
| 重试 | 退避 10s/60s/300s，3 次后 `failed` 并进 dead-letter |
| **幂等键** | `owner + sourceKind + 候选集合 hash + promptVersion`；重复执行命中即跳过计费 |
| 失败兜底 | 坏窗口可跳过（cursor 前进），不阻塞后续窗口；提供「记忆体检 → 重试」入口 |

### 4.5 数据模型（schema v24 → v31）

> SQLDelight migration 从 `24.sqm` 起（当前最大 `23.sqm`）。全部 `ADD COLUMN` 带默认值；需要重建表的走「新表 + 分批搬迁 + 校验 + 切换 + 保留旧表」。

#### 4.5.1 memory_items 扩展（v24）

```sql
-- 事实结构（可空/默认空 = 未结构化）
ALTER TABLE memory_items ADD COLUMN subject TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN predicate TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN object TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN polarity TEXT NOT NULL DEFAULT 'unknown';   -- positive|negative|unknown
ALTER TABLE memory_items ADD COLUMN qualifiers TEXT NOT NULL DEFAULT '[]';      -- JSON 数组
ALTER TABLE memory_items ADD COLUMN fact_kind TEXT NOT NULL DEFAULT 'freeform'; -- structured|freeform
ALTER TABLE memory_items ADD COLUMN fact_key TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN topic TEXT NOT NULL DEFAULT '';
-- 双时间
ALTER TABLE memory_items ADD COLUMN valid_from INTEGER;          -- NULL = 未知
ALTER TABLE memory_items ADD COLUMN valid_to INTEGER;            -- NULL = 仍成立
ALTER TABLE memory_items ADD COLUMN recorded_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN time_source TEXT NOT NULL DEFAULT 'unknown';     -- explicit|inferred|unknown
ALTER TABLE memory_items ADD COLUMN time_precision TEXT NOT NULL DEFAULT 'unknown';  -- minute|day|month|year|unknown
-- 证据与计数
ALTER TABLE memory_items ADD COLUMN source_message_ids TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN evidence_session_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN mention_count INTEGER NOT NULL DEFAULT 1;
ALTER TABLE memory_items ADD COLUMN last_confirmed_at INTEGER;
ALTER TABLE memory_items ADD COLUMN retrieval_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN injection_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN useful_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN last_injected_at INTEGER;
-- 状态
ALTER TABLE memory_items ADD COLUMN conflict_status TEXT NOT NULL DEFAULT 'none';    -- none|suspected|confirmed|needs_review
ALTER TABLE memory_items ADD COLUMN superseded_by INTEGER;
ALTER TABLE memory_items ADD COLUMN salience REAL NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS memory_items_factkey
  ON memory_items(ownerType, ownerId, fact_key, status, valid_to);
CREATE INDEX IF NOT EXISTS memory_items_pending_conflict
  ON memory_items(ownerType, ownerId, conflict_status, status);
CREATE INDEX IF NOT EXISTS memory_items_superseded ON memory_items(superseded_by);
```

> 说明：**不保留 `topic_slug`**（v1.0 设计），改用 `fact_key`（强结构）+ `topic`（弱标签，仅展示/弱分组）。外部审阅指出 2–6 字 slug 稳定性不足，v1.1 采纳。

#### 4.5.2 memory_relations（v24 同批）

```sql
CREATE TABLE memory_relations (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  fromMemoryId INTEGER NOT NULL,
  toMemoryId INTEGER NOT NULL,
  relationType TEXT NOT NULL,       -- duplicate_of|supports|refines|contextual|contradicts|supersedes
  confidence REAL NOT NULL DEFAULT 0.5,
  decided_by TEXT NOT NULL DEFAULT 'model',   -- model|user|rule
  createdAt INTEGER NOT NULL DEFAULT 0,
  UNIQUE(fromMemoryId, toMemoryId, relationType)
);
CREATE INDEX IF NOT EXISTS memory_relations_from ON memory_relations(fromMemoryId, relationType);
CREATE INDEX IF NOT EXISTS memory_relations_to ON memory_relations(toMemoryId, relationType);
```

#### 4.5.3 向量表 v25（BLOB + 格式元数据 + 可续跑迁移）

```sql
CREATE TABLE vector_memories_v2 (
  id TEXT NOT NULL PRIMARY KEY,
  ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  sourceType TEXT NOT NULL, sourceId TEXT NOT NULL,
  content TEXT NOT NULL,
  importance REAL NOT NULL DEFAULT 0,
  embedding BLOB,                          -- 小端 float32（或 int8，按 dtype）
  embeddingDimension INTEGER NOT NULL DEFAULT 0,
  embeddingDtype TEXT NOT NULL DEFAULT 'float32',
  embeddingVersion INTEGER NOT NULL DEFAULT 1,
  isNormalized INTEGER NOT NULL DEFAULT 1,
  tags TEXT NOT NULL DEFAULT '', visibility TEXT NOT NULL DEFAULT 'public',
  embeddingSignature TEXT NOT NULL DEFAULT '',
  createdAt INTEGER NOT NULL DEFAULT 0,
  expiresAt INTEGER NOT NULL DEFAULT 9223372036854775807
);
CREATE INDEX IF NOT EXISTS vector_v2_owner_sig_type
  ON vector_memories_v2(ownerType, ownerId, embeddingSignature, sourceType, expiresAt);

CREATE TABLE memory_vector_migration_state (
  id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
  phase TEXT NOT NULL DEFAULT 'pending',   -- pending|copying|validating|switched|cleanup_old
  cursor TEXT NOT NULL DEFAULT '',
  migratedCount INTEGER NOT NULL DEFAULT 0,
  failedCount INTEGER NOT NULL DEFAULT 0,
  lastError TEXT NOT NULL DEFAULT '',
  updatedAt INTEGER NOT NULL DEFAULT 0
);
```

**校验**：`blobSize == dimension * (dtype==float32?4:1)`；签名/维度/格式不匹配 → 标 `stale` 并排入重建，**不得静默返回「没有记忆」**（体检页显示降级原因）。

#### 4.5.4 词法与实体索引（v26 / v27）

```sql
-- 语料统计（BM25 的 N 与 avgdl）
CREATE TABLE memory_corpus_stats (
  ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  docCount INTEGER NOT NULL DEFAULT 0,
  totalDocumentLength INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (ownerType, ownerId)
);
-- 每个 term 的文档频次（BM25 的 df）
CREATE TABLE memory_term_stats (
  term TEXT NOT NULL, ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  documentFrequency INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (term, ownerType, ownerId)
);
-- 每条文档的长度（BM25 的 dl）
CREATE TABLE memory_document_stats (
  memoryId INTEGER NOT NULL PRIMARY KEY,
  documentLength INTEGER NOT NULL DEFAULT 0
);
-- 倒排（tf）
CREATE TABLE memory_terms (
  term TEXT NOT NULL, ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  memoryId INTEGER NOT NULL, tf INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (term, ownerType, ownerId, memoryId)
);
CREATE INDEX IF NOT EXISTS memory_terms_lookup ON memory_terms(term, ownerType, ownerId);

-- 实体规范化
CREATE TABLE canonical_entities (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  canonicalName TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'unknown',
  confidence REAL NOT NULL DEFAULT 0.5,
  UNIQUE(ownerType, ownerId, canonicalName, kind)
);
CREATE TABLE entity_aliases (
  alias TEXT NOT NULL, canonicalId INTEGER NOT NULL, ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  PRIMARY KEY (alias, canonicalId)
);
CREATE INDEX IF NOT EXISTS entity_aliases_lookup ON entity_aliases(alias, ownerType, ownerId);
CREATE TABLE memory_entities (
  canonicalId INTEGER NOT NULL, memoryId INTEGER NOT NULL,
  ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  PRIMARY KEY (canonicalId, memoryId)
);
```

> **停用词不得包含否定词**（不/没/非/未/别/无），它们是极性判断的关键线索。
> 索引统计（df/dl/avgdl）的更新必须与记忆写入/归档/删除/合并/恢复在同一事务或可恢复索引任务中，否则 IDF 会漂移。

#### 4.5.5 印象实体（v28）

```sql
CREATE TABLE character_impressions (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  characterId TEXT NOT NULL,
  statement TEXT NOT NULL,
  subjectType TEXT NOT NULL DEFAULT 'user',    -- user|relationship|world|character
  level TEXT NOT NULL DEFAULT 'interaction',   -- interaction|sustain|longterm（唯一枚举，全端一致）
  topic TEXT NOT NULL DEFAULT '',
  tone TEXT NOT NULL DEFAULT 'neutral',        -- positive|neutral|negative|mixed
  confidence REAL NOT NULL DEFAULT 0.6,
  valid_from INTEGER,
  valid_to INTEGER,
  superseded_by INTEGER,
  first_seen_at INTEGER NOT NULL DEFAULT 0,
  last_confirmed_at INTEGER,
  mention_count INTEGER NOT NULL DEFAULT 1,
  evidence_count INTEGER NOT NULL DEFAULT 0,
  evidence_session_count INTEGER NOT NULL DEFAULT 0,
  is_user_edited INTEGER NOT NULL DEFAULT 0,
  edited_at INTEGER,
  edit_revision INTEGER NOT NULL DEFAULT 0,
  is_frozen INTEGER NOT NULL DEFAULT 0,
  is_rejected INTEGER NOT NULL DEFAULT 0,
  rejected_at INTEGER,
  rejection_reason TEXT NOT NULL DEFAULT '',
  created_by TEXT NOT NULL DEFAULT 'consolidation',   -- consolidation|user|import
  generation_version TEXT NOT NULL DEFAULT '',        -- 模型/提示词版本
  createdAt INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS character_impressions_owner
  ON character_impressions(characterId, level, valid_to, last_confirmed_at DESC);
CREATE INDEX IF NOT EXISTS character_impressions_topic
  ON character_impressions(characterId, topic, valid_to);

CREATE TABLE impression_evidence (
  impressionId INTEGER NOT NULL,
  memoryId INTEGER NOT NULL,
  sessionId TEXT NOT NULL DEFAULT '',
  messageIds TEXT NOT NULL DEFAULT '',
  addedAt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (impressionId, memoryId),
  FOREIGN KEY (impressionId) REFERENCES character_impressions(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS impression_evidence_memory ON impression_evidence(memoryId);

-- 用户拒绝对应「抑制规则」：防止下次整合换个措辞复活同一错误印象
CREATE TABLE impression_suppressions (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  characterId TEXT NOT NULL,
  topic TEXT NOT NULL DEFAULT '',
  statementPattern TEXT NOT NULL DEFAULT '',
  reason TEXT NOT NULL DEFAULT '',
  createdAt INTEGER NOT NULL DEFAULT 0
);

-- 编辑历史（撤销编辑/恢复 AI 版本）
CREATE TABLE impression_history (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  impressionId INTEGER NOT NULL,
  revision INTEGER NOT NULL,
  statement TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'consolidation',   -- consolidation|user
  createdAt INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (impressionId) REFERENCES character_impressions(id) ON DELETE CASCADE
);
```

**枚举统一**：`interaction`（互动印象）/ `sustain`（持续了解）/ `longterm`（长期印象）。DB 默认值、Kotlin enum、Swift 文案、prompt 输出、测试 fixture 必须一致（对照表见 §8.3）。

**删除传播**：删除 `memory_items` 中某条 → `impression_evidence` 对应行级联删除；若某印象证据数降为 0，则归档该印象（不物理删除，保留历史供审计）。

#### 4.5.6 召回日志（v29，隐私优先）

```sql
CREATE TABLE memory_recall_log (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  ownerType TEXT NOT NULL DEFAULT 'operator',
  ownerId TEXT NOT NULL DEFAULT '',
  sessionId TEXT NOT NULL DEFAULT '',
  turnAt INTEGER NOT NULL DEFAULT 0,
  queryDigest TEXT NOT NULL DEFAULT '',        -- 仅长度+哈希+查询类型，不存原文
  queryLength INTEGER NOT NULL DEFAULT 0,
  queryIntent TEXT NOT NULL DEFAULT '',        -- fact|preference|time|relationship|unknown
  candidateCount INTEGER NOT NULL DEFAULT 0,
  channelHits TEXT NOT NULL DEFAULT '{}',      -- {"vector":n,"lexical":n,"entity":n,"time":n}
  injectedIds TEXT NOT NULL DEFAULT '',
  degradedReason TEXT NOT NULL DEFAULT '',
  engineVersion TEXT NOT NULL DEFAULT '',
  embeddingSignature TEXT NOT NULL DEFAULT '',
  latencyMs INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'ok',           -- ok|degraded|abstained|error
  createdAt INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS memory_recall_log_owner ON memory_recall_log(ownerId, createdAt DESC);
```

**隐私约定**：默认关闭写入（仅 Debug 构建或用户在体检页显式开启）；滚动保留 7 天；导出/调试日志中不出现记忆正文、不出现用户消息原文、不出现 `queryDigest` 以外的查询信息。**v1.0 中的 `queryText` 列已删除。**

#### 4.5.7 用户画像（v30，可选包）

```sql
CREATE TABLE user_profile_facts (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  factKey TEXT NOT NULL UNIQUE,           -- name|nickname|birthday|job|timezone|allergy|taboo|...
  value TEXT NOT NULL,
  sensitivity TEXT NOT NULL DEFAULT 'normal',   -- normal|sensitive
  source TEXT NOT NULL DEFAULT 'inferred',      -- inferred|user_confirmed|imported
  shareScope TEXT NOT NULL DEFAULT 'all',       -- all（全角色）|selected（逐角色授权）|none
  confidence REAL NOT NULL DEFAULT 0.6,
  createdAt INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE user_profile_shares (
  factId INTEGER NOT NULL, characterId TEXT NOT NULL,
  grantedAt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (factId, characterId)
);
```

> 默认 `shareScope = 'none'`：画像事实**不自动跨角色传播**，必须用户确认或被显式授权给某角色。v1.0「画像层默认全角色可见」的设计已撤销。

### 4.6 检索（多信号、两阶段）

#### 4.6.1 查询构造与意图（无额外 LLM）

- `query` = 最近 3 轮（各 ≤120 字）+ 当前用户消息；
- **查询意图**用规则分类（关键词/正则，零成本）：`fact`（姓名/地点/日期/数字）、`preference`（喜欢/讨厌/爱吃）、`time`（以前/最近/第一次/上次）、`relationship`（我们/一起/答应/关系）、`unknown`；
- **实体识别**用本地实体词典 + 别名最长匹配（`entity_aliases`），不调用 LLM；
- 若意图无法分类 → 使用默认融合权重，且允许不注入。

#### 4.6.2 词法通道（BM25，自建倒排）

```
score = Σ_term IDF(term) · (tf·(k1+1)) / (tf + k1·(1-b + b·dl/avgdl))
IDF(term) = ln(1 + (N - df + 0.5)/(df + 0.5))
k1 = 1.2, b = 0.75（在回放集上调）
```
- 索引单元：去标点小写后的 n-gram（2–3 元）+ 结构化字段（`subject/predicate/object`）；
- **两字查询兜底**：n-gram 倒排（含 bigram）天然支持；若改用 FTS5 trigram（PoC），必须保留 bigram 通道（FTS5 trigram 对 <3 字符不命中，附录 C 已核实）；
- 统计表：`memory_corpus_stats`（N/avgdl）、`memory_term_stats`（df）、`memory_document_stats`（dl）、`memory_terms`（tf）；
- 一致性：写入/归档/删除/合并/恢复时同步更新统计（同事务或可恢复索引任务）。

#### 4.6.3 两阶段检索（RRF 只做排名）

**阶段 1：候选生成 + 融合排名**
- 四通道各自取 TopK：向量（≤120）、词法（≤120）、实体（≤60）、时间（≤40，仅 `time` 意图启用）；
- `RRF(score) = Σ 1/(60 + rank_channel)`；产出**融合排名**，不解释为相关性概率。

**阶段 2：相关性准入 + 重排**
- 准入规则（按意图分别校准，来自回放集）：
  - 各通道原始分（余弦 / BM25 / 实体命中数 / 时间距离）分别达阈值；
  - 或「至少两个通道同时命中」；
  - 或「单通道强命中 + 该查询类型对应主信号」；
- **允许不注入**：无候选通过准入 → 返回空（abstention），不强行凑满 4 条；
- 重排：`lineage/父子去重`（同事实只留最合适等级）→ `MMR`（同主题最多 2 条）→ 预算裁剪；
- **顺序固定**：候选生成 → RRF → 准入 → 父子去重 → MMR → 裁剪 → 注入。

**查询类型权重（初始值，需回放集标定）**

| 查询类型 | 主信号 | 辅助信号 |
|---|---|---|
| `fact` | 实体、词法 | 时间、向量 |
| `preference` | 向量 | 词法、实体 |
| `time` | 时间字段 | 词法、向量 |
| `relationship` | 角色范围 + 证据链 | 实体、时间 |
| `unknown` | 默认融合权重 | — |

#### 4.6.4 实体通道

- 写入时：抽取实体 → 查 `entity_aliases` 归一 → 无则新建 `canonical_entities`；
- 查询时：词典最长匹配 → 展开别名 → 命中 `memory_entities`；
- 跨 owner 不合并（同一实体在不同角色/隐私范围内是不同记录）；
- 同名歧义（如「小林」）→ 结合 `kind` 与上下文，低置信时降权而不合并。

#### 4.6.5 向量通道定位（v1.1 明确）

| 模式 | 能力 | 说明 |
|---|---|---|
| 默认本地 | 词法 + 实体 + 时间 + **哈希向量（粗召回）** | 零配置可用；哈希向量**不具备**可靠的否定/同义/概念理解 |
| 可选第三方嵌入 | 真语义召回 | 用户配置；签名变化 → 后台重建（不静默清空） |
| 可选端侧模型 | 离线语义召回 | M5 PoC（Core ML 优先），G1 未达标时提前启动 |

**几何修复**（无论哪种模式都要做）：向量只对 `content` 嵌入，**剥离 `[日期·来源]` 前缀**（前缀会稀释相似度并让时间信息进入语义空间）；短文本归一化；阈值按 query 长度与候选分布校准。

#### 4.6.6 salience（热度）定义

```
salience = clamp01(
    0.35·importance_norm
  + 0.15·log1p(mention_count)/log1p(10)
  + 0.20·recency_decay(age_days, half_life=45d)
  + 0.20·useful_signal(useful_count, injection_count, retrieved_count)
  - 0.10·conflict_penalty(conflict_status)
)
```
- `useful_signal`：优先用户确认（权重 1.0），其次「注入后未被否定」（0.3 上限），**被注入本身权重 ≈0**；
- 全部项归一化到 [0,1]，权重和为 1（冲突惩罚单独扣减）；上下限 clamp；
- 归档阈值、半衰期、权重均写入配置并在回放集上标定（测试样例见 §8.3）。

### 4.7 注入

| 块 | 内容 | 上限（字符） |
|---|---|---|
| `IMPRESSION_LONG` | 长期印象 ≤3 条 ×≤100 字 | 350 |
| `IMPRESSION_SUSTAIN` | 持续了解 ≤3 条 ×≤100 字 | 350 |
| `MEMORY_RELEVANT` | 相关经历 ≤4 条 ×≤100 字（带时间） | 450 |
| `MEMORY_RECENT` | 最近 7 天事件 ≤2 条 | 200 |
| `MEMORY_FACTS` | 用户画像硬事实（仅已授权） | 200 |
| 合计 | — | **≤1200 字符（UTF-8 按字符计）** |

- **整行截断**：按行填充，超预算整行丢弃（绝不输出半句）；单条超长先截断到 100 字并加 `…`；
- **父子只注入一条**：同 fact_key 的 L1/L2/L3 只注入最合适等级（长期 > 中期 > 短期，且必须有效）；
- 每条带来源与时间标注 `[2026-03-14·私聊]`；
- 注入位置保持现状：user 角色「【本轮参考资料】」+ 数据边界声明，不得回退到 system；
- 转义：内容中的 JSON/Markdown 控制字符不破坏外层格式（注入块为纯文本，不做结构化解析）。

### 4.8 印象系统

#### 4.8.1 证据独立性（避免「同一场对话被算三次」）

形成/升级印象时，证据门槛按**独立性**而非条数：

| 条件 | 是否计入独立证据 |
|---|---|
| 不同会话 + 不同自然日 | ✅ 计 |
| 同一会话不同日期（跨天续聊） | ✅ 计（需跨天） |
| 同一会话同一天多条近似抽取 | ❌ 只计 1 条 |
| 角色/助手自行推断（非用户表述） | ⚠️ 权重 ×0.3，且不能单独支撑长期印象 |
| 用户明确表述（「我就是…」「我不喜欢…」） | ✅ 权重 ×1.0 |
| 存在 `contradicts` 证据 | ❌ 进入待确认，不升级 |

#### 4.8.2 层级门槛（按印象类型分级）

| 目标等级 | 最低独立证据 | 时间跨度 | 适合的类型 |
|---|---|---|---|
| `interaction` 互动印象 | 1 次会话 | — | 事件、情绪、身体状态 |
| `sustain` 持续了解 | ≥2 个会话 | ≥3 天 | 明确偏好、约定、计划 |
| `longterm` 长期印象 | ≥3 个会话 | ≥7 天 | 稳定偏好、习惯、关系模式 |
| **人格/关系型结论**（「很善良」「嘴硬心软」「依赖我」） | ≥4 个会话 + ≥14 天，且无 `contradicts` | ≥14 天 | 仅长期，且需亲密度 ≥60 |

**关系亲密度门控**（沿用五档）：0–19 仅 `interaction`；20–39 允许明确偏好；40–59 允许 `sustain`；60–79 允许 `longterm`；≥80 允许关系型印象。

#### 4.8.3 可见性与跨来源

| 来源 | 可进入印象合成 | 默认能否被其他角色看到 |
|---|---|---|
| 角色与用户的私聊记忆 | ✅ | ❌（默认不跨角色；需用户授权） |
| 群聊记忆（公共） | ✅ | ✅（同群成员可见范围内） |
| 角色日记（角色自述） | ✅（权重 0.5，且不得作为用户事实的唯一证据） | ❌ |
| 动态/评论（公共） | ✅（权重 0.4） | ✅ |
| 用户画像事实 | ✅ | 按 `shareScope` + `user_profile_shares` 逐条授权 |

> `privacy`（敏感度）与可见范围解耦：`sensitivity=sensitive` 的记忆即使在同一角色内也降低注入优先级，且永不进入跨角色通道。

#### 4.8.4 状态机与用户操作

```
interaction/sustain/longterm  → (superseded / archived / rejected / frozen)
用户操作：
 ├─ 编辑（statement）→ is_user_edited=1、edit_revision++、写 impression_history；默认不被自动覆写
 ├─ 冻结 → is_frozen=1，整合只可追加证据，不可改文本
 ├─ 不对（拒绝）→ is_rejected=1、rejected_at、reason、写 impression_suppressions（防复活）
 ├─ 撤销编辑 → 从 impression_history 恢复 + 标 source=user
 └─ 允许补充证据 → 关闭冻结，仅追加 evidence（不改文本）
```

**用户编辑与后续证据冲突**：不静默改写，改为在印象旁显示「有新证据与此印象相关」提示。

#### 4.8.5 印象 UI

- 默认**按角色分组**（可切换「全部角色」）；角色卡显示：长期印象 N · 持续了解 M · 互动 K；
- 每条：正文 + 语气 + 置信度 + 「形成于 X · 最近确认 Y · 来自 N 次对话」；
- 展开证据 → 点击跳转会话/消息；
- 分页（每页 20 条）+「只看长期印象/只看最近的」；
- 操作：不对 / 编辑 / 冻结 / 撤销编辑 / 清空该角色印象。

### 4.9 用户画像层（可选包，默认关闭共享）

- 抽取：整合 Worker 从记忆中发现「用户级事实」（姓名/称呼/生日/职业/时区/过敏/禁忌）→ 写入画像候选（`source=inferred`，`shareScope=none`）；
- 确认：用户在「我的资料」页确认/修改 → `source=user_confirmed`，并选择共享范围（全角色/指定角色/不共享）；
- 注入：只有 `shareScope=all` 或已授权给该角色的事实进入 `MEMORY_FACTS` 块；
- 撤销：随时可撤销授权与删除事实，删除后各角色不再注入。

### 4.10 生命周期与调度（机会式，非「每日必跑」）

| 项 | v1.1 方案 |
|---|---|
| 前台保证 | 回复后轻量 enqueue；聊天前台空闲 ≥60s 处理少量任务；App 启动/回前台 catch-up（每次有预算） |
| 后台补充 | BGTask 仅作「有机会就做」，**不承诺时刻**；每次执行限时、限次、可被系统中断 |
| 可中断续跑 | 任务状态、cursor、attempt、nextRetryAt、lease、runnerVersion 全部落库；中断后下次活跃从 checkpoint 继续 |
| 保留期 | L1 默认 90 天（可配 30/90/永久）；L2 365 天；L3 / 印象 / 画像**永久** |
| 清理 | 启动/回前台/后台机会执行；`expiresAt<now` → 软删 → 30 天宽限 → 硬删 + 清索引 |
| 索引重建 | 触发：签名漂移 / 恢复完成 / 用户点「重建」/ 启动自检发现 stale/missing；分批（每批 200，可取消），进度可见 |
| 不承诺 SLA | 体检页显示「上次成功时间 / pending 数 / 下次计划」，文案为「下一次活跃时继续」，不写「每天」或「24 小时内」 |

### 4.11 可观测性与隐私

- `MemoryDiagnostics` 安装到 `DebugLogger`；**不写记忆正文、不写用户消息原文**，只写 id、计数、耗时、错误码、降级原因；
- 体检页：条数/层级分布/索引状态（ready/stale/missing/expired）/失败窗口/dead-letter/上次任务/成本（本月调用数与估算费用）/最近召回（脱敏）；
- 操作：重建索引、清理过期、重试失败、导出记忆（JSON/Markdown，用户主动）、清空本角色记忆；
- token 计量：按 `requestType` 分类累加（聊天/记忆提取/整合/印象/知识库），月末可对账。

### 4.12 删除与证据传播矩阵（11 类操作）

| 操作 | memory_items | 索引（向量/倒排/实体） | 印象 | 待执行任务 | 备注 |
|---|---|---|---|---|---|
| 删除一条 L1 | 删除 | 同步删 | 证据行删除；证据数归零则归档印象 | 取消相关整合任务 | — |
| 删除一条 L2/L3 | 删除 + 其证据链保留（父项可重新形成） | 同步删 | 同上 | 取消/重排 | `memory_relations` 同步清理 |
| 删除一条印象 | 保留记忆 | — | 删除或归档（按用户选择）+ 写抑制规则 | 无 | 抑制规则防复活 |
| 用户编辑消息 | 相关记忆 `needs_review` | 保持 | 标「有新证据」 | 触发重建（epoch++） | 沿用现有 timeline epoch |
| 删除消息 | 归因该消息的记忆归档（BFS 沿 relations） | 同步删 | 证据降级 | 取消 | 修正 M-10 后才可靠 |
| 删除会话 | 归因该 session 的记忆删除 | 同步删 | 证据降级/归档 | 取消 | — |
| 删除角色 | 该角色全部记忆删除 | 同步删 | 全部删除 | 取消 | 含关系网引用 |
| 清空某角色记忆 | 全部删除（保留印象由用户选择） | 同步删 | 可选保留 | 取消 | 二次确认 |
| 关闭画像 | 画像事实删除 | — | 相关印象不删但降权 | 取消 | — |
| 删除画像事实 | 删除 | — | 引用该事实的印象标 stale | 取消 | — |
| 恢复备份 | 按备份恢复 + 重建 | 全部重建 | 按备份恢复 | 清理旧任务 | 保留原 status（不强制 archived） |

> 矩阵的每一行都必须有自检用例（见 T79a/T84a/T88a 的验收）。

---

## 五、开源选型与参考（GitHub，2026-11-26 核对）

> **原则**：只借算法与数据模型，不引入 Python 服务端运行时；移动端组件必须经 PoC 才可成为依赖。
> 选型分四类：**算法参考 / MVP 待验证原生组件 / 规模化性能候选 / 端侧模型运行时**。

| 组件 | 仓库 / 许可 | 规模 | 类别 | **是否产生运行时依赖** | 可借鉴点 | 落地方式 |
|---|---|---|---|---|---|---|
| **mem0** | [mem0ai/mem0](https://github.com/mem0ai/mem0) Apache-2.0 | 69.5k★ | 算法参考 | 否 | ①单次 ADD-only + **先检索 Top-10 旧记忆做去重上下文**；②语义+BM25+实体多信号；③实体链接；④时间感知 | 借 D1/D4；`MemoryParseRules`+`MemoryRecallEngine` 自研 |
| **Graphiti / Zep** | [getzep/graphiti](https://github.com/getzep/graphiti) Apache-2.0 | 33.1k★ | 算法参考 | 否 | 事实有效期窗口、变更失效保留历史、双时间 | 借 D3/D4；不建图数据库 |
| **MemoryOS** | [BAI-LAB/MemoryOS](https://github.com/BAI-LAB/MemoryOS) Apache-2.0 | 1.6k★ | 算法参考 | 否 | 短/中/长分层、热度驱动晋升淘汰 | 借 §4.6.6 salience（权重自标定） |
| **Memobase** | [memodb-io/memobase](https://github.com/memodb-io/memobase) Apache-2.0 | 3.0k★ | 算法参考 | 否 | 用户画像式记忆、per-user 缓冲摊薄成本 | 借 §4.1.1 窗口合并 + §4.9 |
| **A-MEM** | [agiresearch/A-Mem](https://github.com/agiresearch/A-Mem) MIT | 1.2k★ | 算法参考 | 否 | 记忆链接与演化 | 借 `memory_relations` |
| **Cognee / Letta** | [cognee](https://github.com/topoteretes/cognee) / [letta](https://github.com/letta-ai/letta) Apache-2.0 | 33.6k★ / 25.6k★ | 架构参考 | 否 | 管线切分、任务可恢复、后台整合形态 | 仅参考 |
| **SQLite FTS5（trigram）** | SQLite 内置 | — | MVP 待验证 | 是（取决于构建） | 子串检索、LIKE/GLOB 加速 | **PoC**：真机 SQLite 是否启用 FTS5 + 能否建虚表 + 两字兜底；不通过则用自建 n-gram |
| **sqlite-vec** | [asg017/sqlite-vec](https://github.com/asg017/sqlite-vec) Apache-2.0 | 8.3k★ | MVP 待验证 | 是 | SQLite 内 KNN + 元数据过滤 | PoC：扩展加载 / SQLDelight 集成 / App Store 构建；issue #71 移动端支持仍在跟踪 |
| **USearch** | [unum-cloud/usearch](https://github.com/unum-cloud/usearch) Apache-2.0 | 4.4k★ | 规模化候选 | 是 | 单头文件 HNSW | 仅当某角色向量 >2 万条且 P95 不达标时评估（Package.swift 现声明 iOS 18，与本 App 一致；集成成本仍需 PoC） |
| **Core ML** | Apple 官方 | — | 端侧运行时 | 是 | 离线语义嵌入 | M5 PoC 首选（iOS-only）；ONNX Runtime 仅在需要 Android 共用时评估 |

**PoC 模板**（统一记录，附录 D）：设备与 iOS 版本、Xcode/Kotlin 版本、依赖体积、构建方式、是否需要自定义 SQLite、冷/热 P50/P95、内存峰值、索引重建时间、并发写行为、App Store/TestFlight 可用性、回滚路径。

---

## 六、里程碑与排期

### 6.1 估算模型（口径统一）

- 代理效率 `e` = **1.6**（人日/代理日）；评审/QA 开销 **+25%**；
- **全期平均并行度 `c` = 2.2（统一口径）**：峰值可到 3.0（写域隔离后），但单 Mac 构建串行、模拟器争用、评审排队会把平均拉回 2.2；
- 代理日 = 人日 / 1.6 × 1.25；**墙钟周 = 代理日 / 2.2 / 5**（四舍五入到 0.1 周）；
- 单代理日 API 成本基准 **¥60–110**。

### 6.2 里程碑

| 里程碑 | 名称 | 交付物 | 人日 | 代理日（含评审） | 墙钟 | Gate |
|---|---|---|---:|---:|---:|---|
| **M0** | 通电、基线、评测集 | 摘要/重试/重建/清理/诊断可触发；**回放集 v0（调参/保留拆分）与指标脚本**；故障注入（中断续跑） | 13.5 | 11 | 1.0 周 | G0 |
| **M1** | 存储与检索底座 | schema v24–v29（含 BM25 统计、向量 BLOB、迁移状态机）、混合检索两阶段、重建任务 | 24.5 | 19 | 1.7 周 | G1 |
| **M2** | 事实整合 | L1 v3（事实结构）、三级去重、关系语义、整合 Worker（预算/幂等/dead-letter）、衰减 | 28.5 | 22 | 2.0 周 | G2 |
| **M3** | 印象系统 | impression 实体+证据+状态机、印象页改版、纠错/冻结/抑制、隐私矩阵 | 21.5 | 17 | 1.5 周 | G3 |
| **M4** | 生命周期、设置与画像 | 保留期/清理、记忆设置页、体检页、备份 v2、画像层（可选包） | 17.5 | 14 | 1.3 周 | — |
| **M5** | 评估、调优与收尾 | 评测扩充与自动化、校准调参、端侧嵌入 PoC、文档与规格卡 v2 | 12.0 | 9 | 0.8 周 | G4 |
| — | 集成/回归缓冲（15%） | 跨里程碑联调、真机长跑、金样重锁、故障注入复测 | 17.6 | 14 | 1.3 周 | — |
| **合计** | | | **135.1** | **106** | **≈9.6 周** | |

> 三档：乐观（e=2.5）≈ **68 代理日 / 6.2 周**；基准（e=1.6）≈ **106 代理日 / 9.6 周**；悲观（e=1.2）≈ **141 代理日 / 12.8 周**。
> 每个 Gate 期间**冻结模型与提示词版本**（见 §11.3），否则指标不可比。

### 6.3 甘特（基准）

```
周次    1    2    3    4    5    6    7    8    9   10
M0    ██
M1      █████
M2            ██████
M3                  █████
M4                    ████
M5                        ███
缓冲                        ████
Gate   G0   G1        G2     G3      G4
```

### 6.4 并行与写域隔离

| 批次 | 任务 | 写域（互不重叠） |
|---|---|---|
| 批 1（M0） | T62–T68 | `domain/automation`、`iosMain/.../ChatEngineBridge`、`data/Cleanup*`、`backup/wire`、`MemoryDiagnostics`、`MemoryParseRules`、`scripts/eval/**` |
| 批 2（M1） | T69–T74 | `sqldelight/**`、`data/MemoryV2Repository`、`domain/memory/LexicalIndex`、`MemoryRecallEngine`、`vector/LocalVectorStoreGateway` |
| 批 3（M2） | T75–T80 + T76a | `prompt/MemoryV2PromptTemplates`、`domain/memory/MemoryParseRules`+`MemoryV2Pipeline`、`domain/automation/MemoryConsolidationTask` |
| 批 4（M3） | T81–T85 + T81a/T84a | `sqldelight/impression*`、`domain/impression/**`、`ImpressionsBridge`、`ios/KnockKnock/Features/Impressions` |
| 批 5（M4） | T86–T89 + T88a | `ios/KnockKnock/Features/Settings+Memory`、`backup/wire` |
| 批 6（M5） | T90–T93 | `scripts/eval/**`、`docs/**`、`specs/**` |

> 规则：同一文件不得被两个并行任务同时改；跨域只经 shared `domain` 接口。M1 与 M2 的交接点是「索引写入接口 + 事实字段契约」——由 M1 先冻结签名，M2 消费。

---

## 七、WBS 任务分解（T62 起）

> 规格卡按 `specs/规格卡模板-v1.md` 产出，但 §3.1-P1 已裁决：**新功能的 oracle 是产品指标与本文档**，不再要求逐行对照 Android。

### M0 通电、基线与评测集

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T62 | 维护链路接线 | 回复后调 `onForegroundReplyCompleted`/`onGroupContextChanged`；`A7Glue` 暴露入口；节流 | 真机聊 30 轮后 `SHORT_TERM_SUMMARY` 非空；编辑历史触发 rebuild | 1.5 |
| T63 | 生命周期接线 | 启动/回前台/后台机会调用清理；软删改宽限硬删（保留期 UI 最小） | 设 30 天后过期条目在宽限后消失；不承诺 SLA 文案 | 1.5 |
| T64 | 索引重建任务 | `MemoryIndexRepairTask`（分批/可取消/进度）；设置页按钮 | 清空向量后重建，召回恢复到 ≥95% | 2.0 |
| T65 | 恢复语义修正 | 恢复保留原 status；完成后自动触发 T64 | 恢复后 L2/L3 不消失；召回恢复 | 1.0 |
| T66 | 诊断与召回日志 | 安装 `MemoryDiagnostics`；召回日志先写 DebugLogger（表在 M1 落地） | 日志含候选/通道命中/降级原因/耗时，**无正文** | 1.5 |
| T67 | 正确性与死键修复 | 证据下标对齐；`priorFailures` 真实值；token 计量；死键清单（X-01）盘点 | 单测覆盖错位；消费统计出现记忆分类；死键清单归档 | 2.0 |
| T68 | **回放集 v0 与基线** | 20 组对话 + 标注格式 + 调参/保留拆分 + 指标脚本（Recall/Precision/stale/矛盾/重复/abstention/P50/P95） | 一键产出基线报告；关键负例 100% 人工确认 | 4.0 |
| — | **M0 小计** | | | **13.5** |

### M1 存储与检索底座

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T69 | schema v24–v29 与迁移状态机 | 事实字段、relations、向量 BLOB+格式元数据、BM25 三表、实体表、印象表、召回日志；`memory_vector_migration_state` | 旧库升级零丢失；分批迁移可中断续跑；任意批次杀进程后可恢复 | 4.0 |
| T70 | 倒排/实体索引 + BM25 统计 | n-gram 倒排、df/dl/avgdl、停用词（不含否定词）、实体规范化与别名、增量一致性 | 随机抽样手算 BM25 = 程序；重建前后结果一致；查询 <20ms | 5.5 |
| T71 | 向量 BLOB 通道 | float32 编解码 + 维度/格式校验；候选列裁剪；暴力 topK（预留 IVF） | 解码耗时下降 ≥80%；格式不匹配显示降级原因 | 3.0 |
| T72 | 两阶段混合检索 | 四通道候选 + RRF 排名 + 查询类型加权 + 校准准入 + abstention + lineage 去重 + MMR + 整行截断 | G1 指标达标；无关问题不强注入；重复注入率 ≤5% | 8.0 |
| T73 | 反馈三态与 salience | retrieved/injected/useful 分记；salience 公式（权重/范围/上下限）；低热度归档 | 被注入权重≈0；用户确认才显著加权；噪声下降 | 2.5 |
| T74 | 规格卡与回归 | `specs/memory-recall-v2-spec.md`；召回矩阵重写（新阈值口径） | reviewer PASS | 1.5 |
| — | **M1 小计** | | | **24.5** |

### M2 事实整合

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T75 | L1 提取 v3 | SCHEMA 增事实结构/时间；旧记忆 Top-10 只读上下文；窗口合并；提示词 v3 | 抽取准确率 ≥85%；否定/时间限定条目全部走结构化或 needs_review | 5.0 |
| T76 | 精确/疑似去重 | A 级合并（证据计数）；B 级建候选交模型；重复率下降 | 重复率下降 ≥60%；不误合并（抽检） | 3.0 |
| **T76a** | **事实极性与冲突保护** | subject/predicate/object/polarity/qualifiers；否定/程度/时间/条件识别；C 级冲突入队；`needs_review` | 「喜欢/不喜欢」「以前/现在」「有条件/无条件」负例全通过；**零自动合并相反事实** | 3.5 |
| T77 | 整合 Worker（含预算/幂等） | A7 任务类型；状态机 + checkpoint；幂等键 + 父项 hash；全局日预算 + 活跃角色调度；dead-letter | 重跑不重复计费；坏窗口不阻塞 cursor；全局调用不超上限 | 8.0 |
| T78 | 关系语义与时效 | `memory_relations` 六类；只有 supersedes 关 valid_to；contradicts 待确认；双时间写入 | 六类关系各有测试；过期事实不再注入 | 5.5 |
| T79 | 衰减与归档 | salience 标定；归档可恢复；合成数据体积测试 | 一年合成数据下 ≤20 MB/角色 | 2.0 |
| T80 | 规格卡与回归 | `specs/memory-consolidation-v2-spec.md`；晋升矩阵重写 | reviewer PASS | 1.5 |
| — | **M2 小计** | | | **28.5** |

### M3 印象系统

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T81 | 印象合成器 | 证据独立性、类型分级门槛、关系档位门控、置信度、双时间 | 形成覆盖率 ≥60%；准确率 ≥80%；无证据不生成 | 5.0 |
| **T81a** | **印象反馈状态机** | 编辑/冻结/拒绝抑制/撤销/历史版本；证据删除传播；与后续证据冲突时提示 | 拒绝后同主题不复活（抽 50 条）；冻结不被改写；编辑可撤销 | 2.5 |
| T82 | 印象页改版 | 按角色分组 + 分页 + 置信度/时间/证据展开 + 跳会话 | UI 自检 PASS；截图基线更新；证据可跳转率 100% | 4.5 |
| T83 | 纠错与用户编辑 | 「不对」归档 + 抑制 + 负反馈；编辑后不被覆盖；清空 | 端到端自检（生成→改→重生成不覆盖） | 3.0 |
| T84 | 跨来源与关系演化 | 群聊/日记/动态来源权重；亲密度档位；群聊记忆生命周期与复制去重 | 矩阵测试 + 权重生效 | 3.0 |
| **T84a** | **隐私与跨角色权限矩阵** | sensitivity/visibility 分离；逐条授权；默认私聊不跨角色；11 类删除传播矩阵 | 未授权时 A 不能召回 B 私聊事实；删除矩阵自检 | 2.0 |
| T85 | 规格卡与回归 | `specs/impression-v1-spec.md`；枚举一致性检查 | reviewer PASS | 1.5 |
| — | **M3 小计** | | | **21.5** |

### M4 生命周期、设置与画像

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T86 | 记忆设置页 | 总开关/按来源/模式/保留期/印象/共享授权/高级 | **每个设置键有生产读取点 + 测试**（X-01/X-08） | 3.5 |
| T87 | 记忆体检页 | 概览/成本/召回（脱敏）/失败与 dead-letter/任务续跑/操作 | 页面数据与 DB/日志一致 | 3.0 |
| T88 | 备份 v2 | 新表进导出；恢复自动重建；v1 兼容；不强制 archived | 往返 3 次数据一致 | 4.0 |
| **T88a** | **迁移/恢复中断测试** | 分阶段 BLOB 迁移、旧表保留、回滚、故障注入 | 任意批次杀进程可续跑；切换前后无 ID 丢失 | 2.0 |
| T89 | 用户画像层（可选包） | `user_profile_facts` + 逐条授权 + 确认/撤销 + 注入块 | 默认不跨角色；用户确认后按授权生效；撤销即失效 | 5.0 |
| — | **M4 小计** | | | **17.5** |

### M5 评估与收尾

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T90 | 评测扩充与自动化 | 保留集扩充（对抗样本）、自动化报告、漂移对比 | 一键产出 G 报告；漂移可定位 | 3.0 |
| T91 | 校准与调参 | 阈值/权重/查询类型权重/MMR/salience 网格；固定版本 | G4 指标达标；调参记录可复现 | 3.0 |
| T92 | 端侧嵌入 PoC | Core ML 优先（体积/延迟/质量/内存/电量） | 决策记录 + 数据 | 3.0 |
| T93 | 文档与发布 | 架构/规格卡 v2/迁移与回滚手册/体检说明/一致性清单 | 文档齐套；7 天真机长跑无崩溃 | 3.0 |
| — | **M5 小计** | | | **12.0** |

> WBS 合计 **117.5 人日**；加 §6.2 的 15% 集成/回归缓冲（17.6）后为 **135.1 人日**。

---

## 八、质量与验收体系

### 8.1 回放集（评测集）

| 项 | 规格 |
|---|---|
| 规模 | **MVP 20 组 / 完整 40–50 组**；每组 = 1 角色 × 200–300 轮 |
| **拆分** | **调参集（60%）** 用于改权重/阈值/提示词；**保留集（40%）** 只在 Gate 运行，调参期不可见 |
| 覆盖分层 | 精确事实 / 偏好 / **否定** / 状态变更 / 时间问题 / 关系型印象 / 无关问题（应 abstain）/ 错误诱导 |
| 对抗样本 | 同实体不同谓词、同主题相反极性、同事实不同措辞、用户自我纠正、角色/助手错误推断、多角色同名 |
| 标注格式 | JSONL：`{turn, type, expected_facts:[], must_not_include:[], expected_abstain:bool, notes}` |
| 生成方式 | 脚本生成骨架 → 标注事实 → DSH 扩张对话 → **关键样本 100% 人工确认，普通样本双人抽检 ≥30%** |
| 隐私 | 全虚构用户，不含真实个人信息 |

### 8.2 指标定义

见 §2.2；所有指标由 `scripts/eval/` 一次性输出，报告含：配置版本、模型版本、提示词版本、数据集版本、逐指标数值与置信区间。

### 8.3 枚举与一致性检查

| 概念 | DB | Kotlin | Swift 文案 | Prompt |
|---|---|---|---|---|
| 印象层级 | `interaction/sustain/longterm` | 同 | 互动印象/持续了解/长期印象 | 同 |
| 关系类型 | `duplicate_of/supports/refines/contextual/contradicts/supersedes` | 同 | 不展示（内部） | 不输出 |
| 记忆层级 | `L1/L2/L3`（既有） | 同 | 短期/日常/长期 | 同 |

**发布前清单**：`docs/eval/consistency-checklist.md` 逐项检查「规格契约 → Kotlin 实现 → Swift UI → 测试 fixture」四方一致。

### 8.4 P2 闭环追溯（对应审计 X-01…X-09）

| 审计 ID | 任务 | 验收 | 检查点 |
|---|---|---|---|
| X-01 死代码/死键 | T67、T86 | 每个设置键有生产读取点 + 测试；无消费者字段删除或标 deprecated | G0 / M4 |
| X-02 隐私语义/跨角色 | T84a、T84 | 未授权时 A 不能召回 B 私聊事实；按事实逐条可见范围 | G3 |
| X-03 重复注入 | T72、T78（lineage 去重） | 重复注入率 ≤5% | G1/G2 |
| X-04 截半条 | T72（UTF-8 预算、整行截断） | 注入块不以半句结尾 | G1 |
| X-05 群聊复制 | T84、T75 | 复制体有归属与晋升路径或明确不晋升；删除传播覆盖 | G2/G3 |
| X-06 晋升幂等 | T77（幂等键 + 父项 hash） | 重跑不重复调用模型；父项必定归档 | G2 |
| X-07 失败窗口阻塞 | T77（dead-letter、跳过坏窗口、cursor 前进） | 坏窗口不阻塞后续；用户可见可重试 | G0/G2 |
| X-08 引用风格设置 | T86（三档行为测试）或删除设置 | 保留则三档 prompt 行为测试通过 | M4 |
| X-09 规格漂移 | T74/T80/T85 + §8.3 一致性清单 | 四方一致 | 每 Gate |

### 8.5 测试分层

| 层 | 内容 | 门禁 |
|---|---|---|
| 单元/矩阵 | 去重三级、关系语义、阈值、RRF、MMR、BM25、polarity、salience、枚举 | 每任务新增；覆盖率不降 |
| 集成 | 迁移状态机（中断续跑）、恢复往返、重建、清理、并发（多角色） | CI 必跑 |
| 故障注入 | Worker 被杀、网络中断、模型超时、DB 锁、重复调度、同角色并发、坏窗口 | 每 Gate 跑 |
| 金样回归 | 旧 `golden/memory/**` 保留为回归基线 | 允许带签字更新 |
| 端到端 | 真机 200 轮 + 备份恢复 + 换嵌入 + 7 天长跑 | 每 Gate |
| 性能 | 召回 P50/P95、整合耗时、冷启动、体积增长、内存峰值 | 每 Gate 采集 |

### 8.6 「不做什么来通过测试」

禁止：写死 fixture、降低标注难度、用固定 similarity 的假向量替代真实路径做验收、为过指标强行注入、为「印象数量」凑条数、在调参集上报告 Gate 指标、跨 Gate 换模型版本。

---

## 九、迁移、兼容与回滚

### 9.1 迁移链

| 版本 | 内容 | 风险控制 |
|---|---|---|
| v24 | 事实字段 + relations | 全部带默认值；旧数据 `fact_kind='freeform'`、`valid_from/valid_to = NULL`（未知） |
| v25 | 向量 JSON → BLOB + 格式元数据 | **状态机**：`pending→copying→validating→switched→cleanup_old`；Kotlin Worker 分批搬迁；校验维度/条数/ID 覆盖/抽样余弦；旧表保留 ≥1 个版本周期 |
| v26 | 倒排 + BM25 统计 + 实体 | 新表；后台构建；构建期走「向量+无词法」降级并显示原因 |
| v27 | 实体规范化 | 同上 |
| v28 | 印象实体 + 证据 + 抑制 + 历史 | 新表；首次整合前为空（空态引导） |
| v29 | 召回日志（无原文） | 新表；默认关闭；滚动 7 天；可清空 |
| v30 | 用户画像（可选包） | 默认 `shareScope='none'`；`user_profile_shares` 控制授权 |
| v31 | 索引与一致性修补 | 依 PoC 结果决定（FTS5 虚表等） |

### 9.2 数据回填（不伪造时间）

- `fact_key`：旧数据用「type + 内容前 N 字哈希」生成占位，`fact_kind='freeform'`，后续整合时由模型纠正；
- `valid_from/valid_to`：**NULL（未知）**，禁止填 0 或 now；
- `recorded_at`：旧数据 = 原 `createdAt`（这是已知的「系统获知时间」）；
- `time_source`：旧数据 `unknown`；
- `source_message_ids`：无证据则不写（禁止伪造）；
- `salience`：用 `importance + recency` 计算初值并标 `initialized=true`（内部列，不展示）；
- 索引（倒排/实体/BLOB）：由 T64 重建任务统一补，可中断、可续跑、有进度。

### 9.3 双跑与灰度

- 开关 `memory_engine = legacy|v2`（M1 后默认 v2，legacy 只读保留 2 个版本周期）；
- M2–M3 期间 v2 与 legacy 产出并存，指标对比达标后删除 legacy；
- 灰度：开发机 → 自签真机 → TestFlight。

### 9.4 回滚预案

| 场景 | 动作 | 数据影响 |
|---|---|---|
| 迁移失败 | 事务回滚 + 迁移前备份 | 无损 |
| BLOB 迁移异常 | 停止 Worker，保留旧表读路径（`phase` 可回退回 pending） | 无损 |
| v2 检索异常 | 开关切 legacy | 新索引保留 |
| 整合任务异常刷屏 | 关闭整合（设置或远程开关） | 记忆不沉淀，已有数据无损 |
| 印象质量事故 | 关闭合成，页面退回只读 | 印象表保留 |
| 备份不兼容 | 只导出 v1 兼容字段 | 新字段丢失但可恢复 |

---

## 十、风险登记册

| ID | 风险 | 概率 | 影响 | 缓解 | 触发信号 |
|---|---|---|---|---|---|
| R1 | 中文语义召回在无端侧模型时仍不达标 | 中 | 高 | M1 几何修复 + 混合通道；G1 <60% 则提前启动 T92（Core ML PoC） | G1 <60% |
| R2 | **自动合并误判（相反事实被合并）** | 中 | 高 | 三级去重 + 零向量授权 + 冲突队列 + `needs_review`；T76a 负例测试 | 审计抽检发现误合并 |
| R3 | 后台整合抢配额/触发限流 | 中 | 中 | 全局日预算 + 活跃角色 + 退避 + 暂停开关 | 429/超时率 >10% |
| R4 | 迁移期双表/双索引不一致 | 中 | 高 | 状态机 + 分批幂等 + 校验 + 旧表保留 + 故障注入 | 计数差异 ≠0 |
| R5 | 印象主观质量不稳定 | 中 | 中 | 证据独立性 + 类型分级 + 用户纠错 + 每 Gate 抽检 | 抽检 <80% |
| R6 | 代理并行写域冲突 | 中 | 中 | §6.4 批次 + 独立分支 + lead 统一合并 + 全量回归 | 冲突文件 >0 |
| R7 | 旧金样与新算法冲突被误判为回归 | 高 | 低 | 金样=回归基线，允许带签字更新；主门禁为产品指标 | 金样 diff 频繁 |
| R8 | BGTask 不执行导致维护滞后 | 高 | 中 | 前台保证 + 启动 catch-up + checkpoint；不承诺 SLA | pending 数持续增长 |
| R9 | 跨角色记忆泄露 | 低 | 高 | 默认不跨角色 + 逐条授权 + T84a 隔离测试 | 授权外召回 |
| R10 | 模型版本漂移导致指标不可比 | 中 | 中 | 每 Gate 冻结模型/提示词版本；锁定评测模型 | 指标异常波动且无代码变更 |
| R11 | 成本超预算 | 低 | 低 | 全局上限 + 月度成本面板 + 自动降频 | 月费用 > 预算 1.5× |
| R12 | 范围蔓延（画像/端侧模型/图可视化） | 高 | 中 | 画像为可选包（T89）、端侧为 PoC（T92）、图可视化明确二期 | 新增需求 >2 条/周 |

---

## 十一、成本与资源

### 11.1 代理与 API

| 档位 | 代理日 | 单代理日成本 | API 小计 | ×1.4 杂项（金样重跑/探索） |
|---|---:|---:|---:|---:|
| 乐观（e=2.5） | 68 | ¥15–25 | ¥0.10–0.17 万 | ¥0.14–0.24 万 |
| **基准（e=1.6）** | **106** | **¥60–110** | **¥0.64–1.17 万** | **¥0.89–1.64 万** |
| 悲观（e=1.2） | 141 | ¥150–250 | ¥2.12–3.53 万 | ¥2.96–4.94 万 |

### 11.2 其他成本

| 项 | 成本 | 说明 |
|---|---|---|
| Apple Developer | 已有（¥688/年） | 真机/TestFlight |
| 测试机 | 已有（自签真机） | 长跑与性能 |
| 用户签字点 | **18–30 小时** | 范围裁决、G0–G4 验收、印象抽样评审（主观项必须人看） |
| CI/额外 Mac | 可选 | 缓解构建串行 |

### 11.3 预算纪律（v1.1 新增）

- **评测模型与生产模型分别计费**：回放集评测使用固定评测路由，单独统计；
- **回放集重复跑上限**：每个 Gate ≤5 次全量评测（超出需 lead 批准）；
- **提示词/模型升级导致重新标定的预算**：每次升级预留 3 代理日 + 1 次全量评测；
- **端侧模型存储/分发成本**（若 T92 采纳）：应用体积与下载流量单列评估；
- **紧急暂停阈值**：月 API 费用 > 预算 1.5× 或单日调用 > 上限 1.2× → 自动降频并告警；
- **版本冻结**：每个 Gate 周期内模型、提示词、评测集版本冻结并记录在评测报告中。

### 11.4 资源需求

| 角色 | 配置 | 职责 |
|---|---|---|
| Lead（根代理） | 1 | 拆卡、写域裁决、合并、Gate 主持 |
| 领域实现代理 | 2–3 并行 | M1 检索底座 / M2 整合 / M3 印象 分线 |
| Reviewer | 1 | 规格卡评审、金样变更签字、代码复核、隐私复核 |
| QA | 1 | 回放集执行、指标复算、故障注入、真机长跑 |
| 人类 | 按需 | 签字点 + 印象主观抽样 + 隐私策略裁决 |

---

## 十二、交付物清单

**代码**
- `shared/src/commonMain/kotlin/.../domain/memory/**`（检索/整合/规则/索引）
- `shared/src/commonMain/kotlin/.../domain/impression/**`（新）
- `shared/src/commonMain/sqldelight/**/24.sqm … 31.sqm`
- `shared/src/iosMain/.../iosbridge/**`（MemoryBridge/ImpressionsBridge/ChatEngineBridge/A7Glue）
- `ios/KnockKnock/Features/Impressions/**`、`Features/Memory/**`、`Features/Settings/MemorySettingsView.swift`、`MemoryHealthView.swift`

**规格与文档**
- `specs/memory-recall-v2-spec.md`、`specs/memory-consolidation-v2-spec.md`、`specs/impression-v1-spec.md`
- `docs/记忆与印象系统架构-v2.md`、`docs/eval/{baseline-v0,g1..g4-report}.md`、`docs/eval/replay-set-v1/`
- `docs/eval/consistency-checklist.md`（四方一致性）
- 迁移与回滚手册、记忆体检使用说明

**证据**
- `handoffs/` 逐任务实现报告；`screenshots/` 新基线；真机长跑日志与指标报告；故障注入记录

---

## 附录 A：问题 → 工作包 → 指标 追溯矩阵

| 问题 | 任务 | 验收指标 |
|---|---|---|
| M-01 晋升不可达 | T75, T76a, T77, T78 | 印象形成覆盖率；L2/L3 观察值 |
| M-02 维护未接线 | T62 | 摘要非空；失败自动恢复 |
| M-03 恢复失忆 | T64, T65, T88, T88a | 索引重建成功率 ≥95% |
| M-04 换签名失忆 | T64, T88 | 换模型后下次活跃内恢复 |
| M-05 冲突无覆盖 | T76a, T78 | 矛盾率 ≤5%；过期事实率 ≤5% |
| M-06 召回质量 | T70, T71, T72, T73 | Recall@4 / Precision@4 / P95 |
| M-07 无反馈 | T73 | salience 有效性；噪声下降 |
| M-08 无界增长 | T63, T79, T82 | 单角色年增量 ≤20 MB |
| M-09 诊断缺失 | T66, T87 | 失败可见率 100% |
| M-10 证据错位 | T67 | 错位用例回归通过 |
| I-01 印象非实体 | T81, T81a, T82 | 覆盖率/准确率/可追溯率 |
| I-02 无生产者 | T77, T81 | 形成覆盖率 |
| I-03 来源窄 | T84, T84a | 跨来源印象占比 >0 且可解释 |
| I-04 不可解释/纠错 | T82, T83, T81a | 纠错/冻结/拒绝链路自检 |
| I-05 噪声/分页 | T72, T82 | 印象按角色分页、条数受控 |
| X-01…X-09 | 见 §8.4 | 见 §8.4 |

## 附录 B：接口契约（Kotlin）

```kotlin
// 检索结果（替代「返回 String」的隐式契约）
data class MemoryBlock(val kind: String, val lines: List<MemoryLine>, val maxChars: Int)
data class MemoryLine(
    val memoryId: Long, val text: String,
    val happenedAt: String?,          // 事件时间（展示）
    val sourceLabel: String,          // 私聊/群聊/日记/动态/画像
    val level: MemoryLevel,           // L1|L2|L3|IMPRESSION
    val lineageRootId: Long = 0L,     // 用于父子去重
)
data class RecallDiagnostics(
    val candidateCount: Int, val channelHits: Map<String, Int>,
    val queryIntent: String, val admittedCount: Int,
    val abstained: Boolean, val degradedReason: String?, val elapsedMs: Long,
)

// 关系与冲突
enum class MemoryRelationType { DUPLICATE_OF, SUPPORTS, REFINES, CONTEXTUAL, CONTRADICTS, SUPERSEDES }
enum class ConflictStatus { NONE, SUSPECTED, CONFIRMED, NEEDS_REVIEW }

// 印象
enum class ImpressionLevel { INTERACTION, SUSTAIN, LONGTERM }
data class ImpressionView(
    val characterId: String, val statement: String, val subjectType: String,
    val level: ImpressionLevel, val tone: String, val confidence: Double,
    val firstSeenAt: Long?, val lastConfirmedAt: Long?,
    val evidenceCount: Int, val evidenceSessionCount: Int,
    val isUserEdited: Boolean, val isFrozen: Boolean, val isRejected: Boolean,
)
data class ImpressionEvidenceView(val sessionId: String, val messageId: Long, val snippet: String, val at: Long)

// 桥接口（新增/变更）
// ImpressionsBridge: list(characterId?), page(offset,limit), detail(id), reject(id, reason),
//                    edit(id, statement), freeze(id, bool), undoEdit(id), clear(characterId)
// MemoryBridge:      health(), rebuildIndex(onProgress), export(characterId), clear(characterId),
//                    retryFailed(), recallLog(on) 
// SettingsBridge:    memorySettings() / setMemorySetting(key, value)
```

## 附录 C：外部事实核对

| 事实 | 来源 | 结论 |
|---|---|---|
| mem0 OSS v3：单次 ADD-only，但**先检索 Top-10 旧记忆做去重上下文**，并用哈希做精确去重 | `mem0ai/mem0` `docs/migration/oss-v2-to-v3.mdx` | 成立；计划书 P2/D1 据此修正 |
| SQLite FTS5 trigram：**少于 3 个字符**的子串不命中 | <https://www.sqlite.org/fts5.html> §4.3.2 | 成立；需 bigram 兜底 |
| sqlite-vec 移动端支持仍在跟踪 | `asg017/sqlite-vec` issue #71（open） | 成立；不作 MVP 前置依赖 |
| USearch Swift Package 平台声明 | `unum-cloud/usearch` `Package.swift`：`iOS(.v18)` | **审阅意见「iOS 15」不准确**；仍不做 MVP 依赖 |
| iOS 后台任务为机会式调度 | Apple BackgroundTasks 文档 | 成立；计划书改为前台保证 + 后台补充 |

## 附录 D：PoC 模板

```
组件：<FTS5 / sqlite-vec / USearch / Core ML>
设备与系统：<机型, iOS 版本>
工具链：Xcode <v>, Kotlin <v>, SQLDelight <v>
集成方式：<系统 SQLite / 自带 SQLite / XCFramework / cinterop / Swift Package>
依赖体积：<MB>；是否需自定义 SQLite：<是/否>
构建与上架：<TestFlight 可构建？扩展加载允许？App Store 风险？>
性能：冷启动 <ms>；查询 P50 <ms> / P95 <ms>；索引重建 1 万条 <s>
资源：内存峰值 <MB>；电量（10 分钟检索）<mAh>
并发与持久化：<读写并发行为、索引持久化方式>
回滚：<关闭开关后的降级路径>
结论：<采用 / 延后 / 放弃> + 理由
```

## 附录 E：批准页

| 角色 | 结论 | 日期 | 备注 |
|---|---|---|---|
| 产品/用户 | ☐ 批准 ☐ 修改后批准 ☐ 退回 | | 重点裁决：§1.4 反范围、§2.2 指标目标、§10 MVP 边界、§11 预算 |
| Lead | ☐ 批准 ☐ 退回 | | 拆卡前确认 §6.4 写域与 §0.2 批准条件 |
| Reviewer | ☐ 批准 ☐ 退回 | | 确认 §3.3 ADR、§4.2 去重三级、§8.6「不做什么来通过测试」 |

> 批准后动作：① 生成 `specs/` 首批四张规格卡（T69 / T72 / T76a / T81）；② Team 任务板建 T62–T93（含 T76a/T81a/T84a/T88a）；③ 启动 M0。