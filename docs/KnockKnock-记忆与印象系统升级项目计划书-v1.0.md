# KnockKnock_ios 记忆与印象系统升级项目计划书 v1.0

> ⚠️ **本文件已归档**：外部架构审阅后由 **v1.1** 替代（见 `KnockKnock-记忆与印象系统升级项目计划书-v1.1.md`）。
> v1.1 修订记录见其 §0.1（含 27 项逐条处理）；本文件仅用于版本对照，**不再作为批准依据**。

| 项 | 内容 |
|---|---|
| 项目 | 敲敲（KnockKnock）iOS —— 记忆与印象系统升级（Memory & Impression v2） |
| 代码仓库 | `github.com/jixu0504/KnockKnock_ios`（默认分支 `main`，基线 commit `0e08a814` / tag `v0.2.0`） |
| 工作副本 | 本机 `敲敲开发 workplace/rhodeslink_ios/`（目录名为历史遗留；`origin` 已指向 KnockKnock_ios，与远端 `main` 逐提交同步） |
| 上游依据 | 《审计报告：敲敲 iOS v0.2.0 记忆与印象系统》`docs/审计-记忆与印象系统-0.2.0.md` |
| 本文档性质 | 立项计划书（范围 / 方案 / 排期 / 预算 / 风险 / 验收），批准后转 T62 起的任务卡与规格卡 |
| 执行模式 | DSH 代理开发（lead + 领域子代理 + reviewer + QA），人类保留签字点 |
| 编写日期 | 2026-11-26 |
| 版本 | v1.0（待批准） |

> **命名澄清**：本计划的目标工程是 **KnockKnock_ios（敲敲）**，不是 Android 原版 RhodesLink。
> 工程已统一为 `KnockKnock`（target / scheme / 源码目录 / 产物），bundle id 为 `com.xsbcjx.knockknock`；历史文档中的 `RhodesLink` 仅指 Android 原版。
> 命名清理（target/目录改名）**不在本项目范围**，单列为可选的运维任务。

---

## 〇、批准摘要（一页纸）

**做不做**：做。当前版本的「长期记忆」主干链路是断的（晋升不可达、摘要不生成、清理不运行、换模型即失忆），继续加功能只会放大沉没成本。

**做什么**：把记忆从「聊天附属的碎片库」升级为「可演化、可解释、可纠错、离线可用的角色记忆系统」，并把「大家的印象」从只读过滤视图升级为一等实体。

**多久**：**基准 8 周墙钟**（乐观 5 周 / 悲观 11 周），约 **88 代理日**（区间 56–118，等价 113 人日）。

**多少钱**：API 基准 **约 ¥0.75–1.35 万**（乐观 ¥0.12–0.20 万 / 悲观 ¥2.5–4.1 万），已含金样重跑与探索性重试；固定支出仅 Apple 账号（已有）与签字点时间。

**怎么验收**：不靠「与 Android 一致」，靠 **长对话回放集上的产品指标**（召回命中率、矛盾率、印象覆盖率、单位 token 成本、P95 延迟），四条 Gate 逐档放行。

**最大风险**：① 中文语义召回在没有端侧嵌入模型时的上限；② 迁移期间新老索引并存的一致性；③ 印象质量的主观性 → 用「回放集 + 人工抽检」锁定，而非一次性主观验收。

---

## 一、背景与问题定义

### 1.1 一句话现状

v0.2.0 的记忆子系统**零件齐全、长期链路未通电**：三层记忆 + 向量召回 + 记忆管理页都实现了，但真正产生「长期感」的四条链路——**晋升、滚动摘要、生命周期清理、索引重建**——在 iOS 端全部断开或不可达。用户实际获得的能力约等于「最近 15 轮原文历史 + 最多 4 条 180 字碎片」；「大家的印象」页面在真实数据下长期只有「互动印象」一段。

### 1.2 问题清单（承接审计报告，编号供追溯）

| ID | 问题 | 现状证据（repo 内 file:line） | 对长期使用的影响 |
|---|---|---|---|
| **M-01** | 晋升分组键是「内容指纹」而非主题，L2/L3 不可达 | `shared/.../domain/memory/MemoryParseRules.kt:210-260`；`MemoryParsePromotionTest.kt:156` | 角色永远只有碎片记忆，「长期印象」区块长期空白 |
| **M-02** | 滚动摘要与记忆维护在 iOS 从未运行（5 个触发点 0 调用点） | `domain/automation/ContextMaintenanceTask.kt:243-276`；`iosMain/.../A7Glue.kt:156,234` | 无中期摘要；失败窗口不重试；编辑历史不重建 |
| **M-03** | 备份恢复后 L2/L3 被强制归档 + 向量清空 + 无重建 | `backup/wire/BackupFullRestoreExecutor.kt:24,86,315,330` | 恢复 = 记忆失忆，长期印象消失 |
| **M-04** | 换嵌入配置后旧向量永久失效，无重建入口 | `vector/EmbeddingConfigurationSignature.kt`；`VectorMemories.sq:63-92`；`MemoryV2Repository.kt:259` 无调用点 | 用户切第三方嵌入 = 历史记忆清零 |
| **M-05** | 事实冲突/变更无覆盖机制（L1 不携带旧记忆，去重 key 含 window 标识） | `MemoryV2Pipeline.kt:600-615`；`MemoryV2Repository.kt:57-64` | 搬家/换工作后新旧事实长期并存 |
| **M-06** | 召回是字面重合 + 绝对阈值 + 候选被时间/重要度预截断 | `vector/LocalHashEmbeddingGateway.kt:6-27`；`MemoryRecallEngine.kt:133-143`；`VectorMemories.sq:63-92` | 该想起的想不起，旧记忆静默不可达 |
| **M-07** | 无使用反馈闭环（`markMemoryItemUsed` 0 调用点） | `MemoryV2Repository.kt:406-408` | 噪声与精华同权，不会自我净化 |
| **M-08** | 无界增长（默认永久、清理器不跑、embedding 以 JSON 存行内、印象页无分页） | `MemoryParseRules.kt:203-206`；`CleanupRepository.kt:14-21`；`VectorMemories.sq:9`；`ImpressionsBridge.kt:57-130` | 一年后体积/首屏/召回延迟同步恶化 |
| **M-09** | 诊断与成本不可见（Diagnostics 未安装、token 统计从不写入） | `MemoryDiagnostics.kt:26-37`；`SettingsRepository.kt:1376-1392` | 「记忆为什么没生效」无人可查 |
| **M-10** | 晋升证据按下标对齐，会挂错父项 | `MemoryParseRules.kt:138-147` | 删除传播会归档错误对象 |
| **I-01** | 印象是 `memory_items` 只读过滤视图，无独立模型 | `iosMain/.../ImpressionsBridge.kt:57-130` | 无法表达语气/关系叙事/矛盾状态 |
| **I-02** | 印象主流程几乎无生产者（L2/L3 不可达；legacy 只在手动创建时写） | 同上 + `MemoryV2Pipeline.kt:393-448` | 长期印象/持续了解长期为空 |
| **I-03** | 只收私聊来源 | `ImpressionsBridge.kt:65` | 群聊/日记/动态记忆永不进印象 |
| **I-04** | 无证据链、无置信度、只读、不可纠错 | `ImpressionsBridge.kt:399-419`；`ImpressionsView.swift` | 用户看到结论却无法追溯或纠正 |
| **I-05** | L1 段无重要度门槛、页面无分页 | `ImpressionsBridge.kt:67,375`；`ImpressionsView.swift:127+` | 一年后数千条噪声 |
| **X-01** | 死代码/死设置（六类锚点、`PERSONAL_MEMORY_REFERENCE_STYLE`、`archiveMemoryItemsByLevel` 等） | 审计报告 P2 清单 | 维护成本与误导 |

### 1.3 为什么现在做（量化）

按「每天 100 条消息、3 个常聊角色」推算（口径见审计报告附录 B）：

- 记忆行：≈1.2 万行/年/角色 → **≈3.6 万行/年**；嵌入按现状 ≈8 KB/行 → **≈290 MB/年**；
- 每轮召回固定解码 320 行 × 8 KB ≈ **2.5 MB** JSON，随库增长线性恶化；
- 晋升产出：**0 条 L2/L3**（M-01）→ 「长期印象」功能实质上不存在；
- 恢复备份一次 = 全部记忆召回归零（M-03）。

即：不做升级，产品每多跑一年，「记忆」这个卖点就多贬值一年，且换机/恢复是确定性事故。

### 1.4 反范围（明确不做）

| 不做 | 理由 |
|---|---|
| 云端记忆服务 / 账号同步 | 与「本地优先、隐私明文不上云」的产品定位冲突；如需，另立项目 |
| 端侧大模型 / 本地 LLM 推理 | 与记忆系统无直接关系，成本与体积不可控 |
| 记忆跨 App / 系统级共享（App Intents、Spotlight） | 二期再议 |
| 语音/ASR/TTS、麻将、游戏 | 与本项目无关的既有 backlog |
| target/目录改名（RhodesLink → KnockKnock） | **已完成**（项目 / target / scheme / 产物均改名，bundle id 与数据不变） |
| 知识库（Knowledge Base）重构 | 只做「与记忆共享检索底座」的接口预留，不重写切块/索引/召回语义 |

---

## 二、目标与成功指标

### 2.1 产品目标（三条）

1. **记得住**：跨周、跨月的偏好/约定/事件能被正确想起，且**知道它什么时候说的**。
2. **会成长**：角色对用户的「认识」会随互动从「互动印象」沉淀为「持续了解」再到「长期印象」，并能被关系亲密度驱动而演化。
3. **可信任**：用户能看见「为什么记得」、能改、能删、能一键重建，也能一眼看出记忆是否健康（条数/索引状态/失败/成本）。

### 2.2 指标体系

> 基线值在 M0 结束时用现有代码实测填入（G0 交付「基线报告」）；目标值为 Gate 判定线。

| 层级 | 指标 | 定义/口径 | 基线（待实测） | 目标 |
|---|---|---|---|---|
| 北极星 | **长期回忆命中率** | 回放集标注的「应被想起」问题中，注入内容包含正确事实的比例（hit@4） | ≤30%（推测） | **≥75%** |
| 记忆质量 | 事实抽取准确率 | L1 条目人工抽检「无幻觉/无错归因」比例 | 待测 | ≥85% |
| 记忆质量 | **矛盾率** | 同一 topic 下「旧事实仍为 active 且与新事实冲突」的条目占比 | 待测（预期高） | **≤5%** |
| 记忆质量 | 覆盖率 | 回放集内「应被记住的事实」中被成功入库的比例 | 待测 | ≥70% |
| 印象 | 印象形成速度 | 连续对话产生首条「持续了解」/「长期印象」的轮次 | 永不（L3） | ≤80 轮 / ≤250 轮 |
| 印象 | 印象覆盖量 | 300 轮后每角色有效印象条数（L2+L3+impression） | ≈0 | ≥8 条 |
| 印象 | 印象准确率 | 人工抽检「语句符合角色视角且与事实一致」比例 | — | ≥80% |
| 性能 | 召回 P95 | 单轮记忆召回（含嵌入+解码+排序，本地模式） | 待测 | **≤300 ms** |
| 性能 | 注入预算 | 每轮记忆相关注入字符数 | ≈740 | ≤1200 |
| 成本 | 记忆 token 占比 | 记忆相关请求 token / 聊天请求 token | 未计量 | **≤12%** |
| 成本 | 月模型调用 | 每角色每月记忆相关模型调用次数 | 待测 | ≤180 次 |
| 稳定性 | 失败可见率 | 记忆管线失败中「有诊断记录 + 可重试」的比例 | 0% | **100%** |
| 稳定性 | 恢复正确性 | 备份恢复后 24h 内召回恢复率（相对恢复前） | 0% | **≥95%** |
| 存储 | 单角色年增量 | 记忆相关存储年增量 | ≈100 MB | **≤20 MB** |

### 2.3 Gate（逐档放行）

| Gate | 位置 | 判定条件（全部满足） |
|---|---|---|
| **G0** | M0 结束 | 摘要/重试/重建/清理在真机链路可触发并有日志；基线报告（2.2 全部指标实测）产出 |
| **G1** | M1 结束 | schema 迁移 100% 通过（含旧备份恢复）；混合召回在回放集上 hit@4 提升 ≥15 个百分点；召回 P95 ≤300ms |
| **G2** | M2 结束 | 回放集上 L2 产出 ≥1 条/角色/50 轮、L3 ≥1 条/角色/200 轮；矛盾率 ≤10%（阶段性） |
| **G3** | M3 结束 | 印象页三条链路（生成→展示→纠错）端到端可用；人工抽检准确率 ≥80% |
| **G4** | M5 结束 | 全部目标指标达标；连续 7 天真机长跑无记忆相关崩溃/无静默失败；文档与规格卡 v2 归档 |

---

## 三、总体技术路线

### 3.1 五条设计原则

1. **产品语义优先于 Android parity**。v0.2.0 的验收哲学是「与 Android oracle 逐行一致」，这保证了移植完整性，也让 oracle 的缺陷（内容指纹分组、只读印象页）原样落地。本项目起，**产品行为与用户指标才是 oracle**；Android 仅作数据兼容参考。旧金样保留为「回归基线」，不再作为新功能的判定标准。
2. **写入轻、整合重**。L1 提取保持「一次 LLM 调用、只增不改」（参考 mem0 v3 的 single-pass ADD-only），矛盾判定、合并、印象合成全部移到**后台整合任务**——那里有完整上下文和旧印象，且不占聊天时延与预算。
3. **离线优先、零配置可用**。默认链路不得依赖第三方嵌入服务；检索靠「向量 + 词法 + 实体 + 时间」多信号融合。第三方语义嵌入作为**可选增强**（已有配置项），切换后自动重建索引。
4. **一切都可解释、可纠错**。每条记忆/印象都能给出证据（会话 + 消息 id）、时间、置信度；用户可以改、删、否决，并能触发重建。
5. **一切可观测**。诊断、token、召回明细、失败原因默认可见（本地日志 + 设置页入口），不允许「静默降级」再次出现。

### 3.2 目标架构

```
                    ┌──────────────────────────── 写入（在线，轻） ────────────────────────────┐
聊天消息 ──▶ 窗口(12条) ──▶ L1 提取（ADD-only，1 次 LLM）──▶ 近似去重/合并 ──▶ memory_items
                                                                  │                    │
                                                                  └──▶ 倒排/实体索引 ──┘
                    ┌──────────────────────────── 整合（后台，重） ────────────────────────────┐
A7 维护任务/前台 tick/BGTask ──▶ 整合 Worker（每角色节流）
        ├─ L2 聚合（topic_slug + 实体 + 时间窗）
        ├─ L3 稳定化（稳定性门 + 跨窗口证据）
        ├─ 印象合成（character_impressions + evidence）
        ├─ 取代/时效（valid_to / superseded_by，保留历史）
        └─ 衰减/归档（salience 热度，低热度 L1 归档）
                    ┌──────────────────────────── 检索（在线，多信号） ─────────────────────────┐
query = 最近3轮 + 意图 + 摘要关键词
        ├─ 向量通道：BLOB float32 → topK（暴力/IVF）        ┐
        ├─ 词法通道：n-gram 倒排 + IDF/BM25                  ├─ RRF 融合 → 长度归一化阈值
        ├─ 实体通道：memory_entities 精确命中                ┘        │
        └─ 使用度/新鲜度/等级加权 → MMR 多样性 → 选 4–6 条 → 分块注入（带来源与时间标注）
                    ┌──────────────────────────── 呈现（可解释） ──────────────────────────────┐
「大家的印象」按角色分组：长期印象 / 持续了解 / 互动印象（来自 impression 表）
        每条可展开证据 → 跳会话；可「不对」→ 归档 + 负反馈；可编辑 → 用户编辑优先
「记忆体检」：条数 / 层级分布 / 索引状态 / 失败窗口 / 本月调用与估算费用 / 最近召回明细
```

### 3.3 关键设计决策（ADR 摘要）

| # | 决策 | 备选 | 选择理由 |
|---|---|---|---|
| D1 | L1 提取 = **ADD-only 单次调用** | 每轮 UPDATE/DELETE 判定（旧 mem0 v2 式） | 每轮多一次 LLM 判定会让记忆成本翻倍、延迟不可控；矛盾交给后台整合更准（有旧印象） |
| D2 | 分组键 = `topic_slug`（模型输出 2–6 字）+ 实体交集兜底 | 纯向量聚类 / 纯 2-gram（现状） | 纯 2-gram 只能聚合逐字重复（M-01 根因）；纯向量聚类在本地哈希嵌入下不可靠；slug+实体可控可解释 |
| D3 | 冲突 = **双时间轴简化版**（`valid_from/valid_to` + `superseded_by`，不物理删除） | 直接覆盖 / 只追加 | 覆盖会丢历史、影响可追溯；只追加会累积矛盾。参考 Graphiti 的有效期窗口 |
| D4 | 检索 = **三通道 RRF 融合**（向量/词法/实体） | 仅向量 / 仅词法 | 现状纯向量+字面哈希质量不足（M-06）；mem0 v3 亦采用「语义+BM25+实体」多信号融合 |
| D5 | 默认不做端侧语义模型，先修几何与融合 | 立即引入 ONNX 小模型 | 端侧模型 +30–100 MB 体积与 PoC 风险；先用「剥离前缀 + IDF 加权 + 混合通道」取得大部分收益，端侧模型列为 M5 可选 PoC |
| D6 | 印象 = **一等实体**（独立表 + 证据 + 状态机） | 继续做过滤视图 | 过滤视图无法表达演进、语气、纠错（I-01/I-04） |
| D7 | 生命周期 = **分层保留期 + 软删宽限 + 自动重建** | 永久保留（现状） | 现状无界增长且不可逆（M-08）；分层保留符合「碎片会过期、长期结论不朽」的产品语义 |
| D8 | 验收 = **回放集指标** + 旧金样回归 | 继续以金样 diff 为主 | 金样只能证明「与 oracle 一致」，证明不了「长期可用」（审计报告结论） |

---

## 四、技术方案（按子系统）

### 4.1 写入：L1 提取升级

**变更点**

| 项 | 现状 | 目标 |
|---|---|---|
| 提取语义 | 单次调用、可能重复入库 | ADD-only 单次调用 + **窗口内去重** + 跨窗口**近似去重/合并** |
| SCHEMA 字段 | type/content/importance/privacy/... | 增加 `topic_slug`（2–6 字主题）、`entities[]`（人物/地点/物品/作品/组织）、`fact_key`（可选，稳定事实标识） |
| 落库 | SQL 精确去重（key 含 sourceRefId） | 精确去重 + 语义近似（同 owner + 同 topic_slug + 余弦 ≥0.92 → 合并，`mention_count++`、`last_confirmed_at` 更新、importance 取 max） |
| 旧记忆输入 | 无 | 不注入（保持 ADD-only），矛盾在处理侧解决 |
| 索引 | 向量（local 哈希） | 向量 + 倒排 + 实体三索引同步写入 |

**提示词改动**（`shared/.../prompt/MemoryV2PromptTemplates.kt`）
- SCHEMA 增字段，并用「主题用 2–6 字概括，例如：饮食偏好 / 小猫名字 / 工作变动」给出示例；
- 增加「事实 vs 感受」区分规则，减少「聊得很开心」类噪声；
- 提示词版本号 `memory_v2_l1_v3`，写入 `memory_batches.promptVersion`，支持 A/B。

**成本控制**：默认窗口仍为 12 条消息（≈6 轮）；闲置 ≥10 分钟时把多个窗口**合并成一次调用**（参考 memobase 的 per-user buffer 思路），上限 3 个窗口。

### 4.2 整合：后台 Worker

**触发**：① A7 维护任务（每次回复后入队、节流）；② 启动 reconcile；③ 前台空闲 ≥60s；④ BGTask catch-up。

**节流规则**（防成本失控）：
- 每角色 L2 聚合 ≤6 次/天、L3 ≤2 次/天、印象合成 ≤1 次/天（可用设置调整）；
- 全局并发 1；单次模型调用 50s 超时、失败退避 10s/60s/300s、3 次后标记 failed 并上报「记忆体检」。

**任务序列**（每角色）

1. **L2 聚合**：取候选 = 同 `topic_slug`（或实体交集 ≥2）的 active L1，按时间窗分页聚合，要求 `count ≥ 3`（强信号 2）；输入 = 组内 top12 的 JSON + 该 topic 最近 3 条 L2；输出 = 1–3 条 L2 + `evidence_ids` + `supersedes_ids`。
2. **L3 稳定化**：候选 = 同 topic 的 active L2 ≥2 且（时间跨度 ≥7 天 或 evidence_count ≥4 或 importance ≥85）；输出 1 条 L3，必须带证据与稳定性说明。
3. **取代（supersede）**：整合产出与既有结论冲突时（模型判定 + 主题相同 + 时间更新），旧条目 `valid_to=now`、`superseded_by=新 id`；**默认不物理删除**。
4. **印象合成**：输入 = 该角色的 L3 + 高重要度 L2 + 最近 30 天事件摘要；输出 = 新增/更新印象（`character_impressions`），带 tone/confidence/level/evidence；同主题合并、反对证据足够时降 confidence 或归档。
5. **衰减**：`salience = w1·importance + w2·log(1+mention) + w3·recency + w4·usage - w5·age`；低于阈值的 L1 归档（不是删除）。
6. **维护**：重建缺失索引、修复失败窗口、TTL 清理。

### 4.3 数据模型（schema v24 → v29）

> SQLDelight migration 文件按现有约定放在 `shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/`，从 `24.sqm` 起。当前最大为 `23.sqm`。
> 所有 `ALTER TABLE ... ADD COLUMN` 带默认值，保证旧库可平滑迁移；需要重建表的（BLOB 迁移）走「新表 + 搬迁 + 改名」。

**v24 — memory_items 扩展**

```sql
ALTER TABLE memory_items ADD COLUMN topic_slug TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN entities TEXT NOT NULL DEFAULT '[]';
ALTER TABLE memory_items ADD COLUMN fact_key TEXT NOT NULL DEFAULT '';
ALTER TABLE memory_items ADD COLUMN valid_from INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN valid_to INTEGER;              -- NULL = 仍有效
ALTER TABLE memory_items ADD COLUMN superseded_by INTEGER;
ALTER TABLE memory_items ADD COLUMN mention_count INTEGER NOT NULL DEFAULT 1;
ALTER TABLE memory_items ADD COLUMN last_confirmed_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN salience REAL NOT NULL DEFAULT 0;
ALTER TABLE memory_items ADD COLUMN source_message_ids TEXT NOT NULL DEFAULT '';
CREATE INDEX IF NOT EXISTS memory_items_topic_valid
  ON memory_items(ownerType, ownerId, memoryLevel, topic_slug, status, valid_to);
CREATE INDEX IF NOT EXISTS memory_items_superseded ON memory_items(superseded_by);
```

**v25 — 向量改 BLOB（float32 小端）**

```sql
CREATE TABLE vector_memories_v2 (
  id TEXT NOT NULL PRIMARY KEY, ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  sourceType TEXT NOT NULL, sourceId TEXT NOT NULL, content TEXT NOT NULL,
  importance REAL NOT NULL DEFAULT 0, embedding BLOB NOT NULL,
  tags TEXT NOT NULL DEFAULT '', visibility TEXT NOT NULL DEFAULT 'public',
  embeddingSignature TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL DEFAULT 0,
  expiresAt INTEGER NOT NULL DEFAULT 9223372036854775807
);
-- 迁移：embeddingJson → embedding（Kotlin 侧分批回填，不阻塞启动）；完成后 DROP 旧表并改名
```

**v26 — 词法倒排**

```sql
CREATE TABLE memory_terms (
  term TEXT NOT NULL, ownerType TEXT NOT NULL, ownerId TEXT NOT NULL,
  memoryId INTEGER NOT NULL, tf INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (term, ownerType, ownerId, memoryId)
);
CREATE INDEX IF NOT EXISTS memory_terms_lookup ON memory_terms(term, ownerType, ownerId);
CREATE TABLE memory_term_stats (            -- IDF 用
  ownerType TEXT NOT NULL, ownerId TEXT NOT NULL, docCount INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (ownerType, ownerId)
);
```

**v27 — 实体索引**

```sql
CREATE TABLE memory_entities (
  entity TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'unknown',
  ownerType TEXT NOT NULL, ownerId TEXT NOT NULL, memoryId INTEGER NOT NULL,
  PRIMARY KEY (entity, memoryId)
);
CREATE INDEX IF NOT EXISTS memory_entities_lookup ON memory_entities(entity, ownerType, ownerId);
```

**v28 — 印象实体**

```sql
CREATE TABLE character_impressions (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  characterId TEXT NOT NULL,
  statement TEXT NOT NULL,                        -- 一句自然语言，角色视角
  topic_slug TEXT NOT NULL DEFAULT '',
  tone TEXT NOT NULL DEFAULT 'neutral',           -- positive | neutral | negative | mixed
  level TEXT NOT NULL DEFAULT 'L2',               -- sustain（持续了解）| longterm（长期印象）
  confidence REAL NOT NULL DEFAULT 0.6,
  valid_from INTEGER NOT NULL DEFAULT 0,
  valid_to INTEGER,
  superseded_by INTEGER,
  first_seen_at INTEGER NOT NULL DEFAULT 0,
  last_confirmed_at INTEGER NOT NULL DEFAULT 0,
  mention_count INTEGER NOT NULL DEFAULT 1,
  evidence_count INTEGER NOT NULL DEFAULT 0,
  is_user_edited INTEGER NOT NULL DEFAULT 0,
  is_rejected INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS character_impressions_owner
  ON character_impressions(characterId, level, valid_to, last_confirmed_at DESC);

CREATE TABLE impression_evidence (
  impressionId INTEGER NOT NULL, memoryId INTEGER NOT NULL,
  sessionId TEXT NOT NULL DEFAULT '', messageIds TEXT NOT NULL DEFAULT '',
  createdAt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (impressionId, memoryId)
);
```

**v29 — 召回日志（可关闭，滚动 7 天）**

```sql
CREATE TABLE memory_recall_log (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  sessionId TEXT NOT NULL, turnAt INTEGER NOT NULL,
  queryText TEXT NOT NULL DEFAULT '',
  candidateCount INTEGER NOT NULL DEFAULT 0,
  injectedIds TEXT NOT NULL DEFAULT '',
  channels TEXT NOT NULL DEFAULT '',            -- 各通道命中数，JSON
  degradedReason TEXT NOT NULL DEFAULT ''
);
```

### 4.4 检索：多信号融合

**流程**

```
1) 候选
   ├─ 向量：SQL 取该 owner 候选（BLOB 仅解码，不 SELECT 其他大列），逐条余弦 → top 120
   ├─ 词法：query 切 n-gram（bigram+trigram）→ memory_terms 查 → 按 IDF*BM25 打分 → top 120
   └─ 实体：query 中识别到的实体 → memory_entities 精确命中 → top 60
2) 融合：RRF(score = Σ 1/(k + rank_i), k=60)，过滤 valid_to IS NULL / expiresAt>now / status=active
3) 阈值：长度归一化 + 相对分位（取 top-k 且 ≥ 底噪 3σ），替代硬编码 0.16/0.20
4) 加权：+ 层级权重（L3 > L2 > L1）+ 时间新鲜度 + 使用度（usedCount/lastUsedAt）+ 重要度
5) 多样性：MMR（按 topic_slug 去重、同主题最多 2 条）
6) 选 4–6 条，按块组织：长期印象 / 相关经历 / 近期事件，每条带 【时间·来源】
```

**中文词法通道实现**（不引入第三方分词）：
- 索引单元：内容去标点后的小写 n-gram（2–3 元）+ 结构化字段（人物名、地名、实体）；
- IDF 由 `memory_term_stats` 维护（写入时增量更新）；
- 停用字表（你我他、的了呢）在写索引时过滤，降低噪声；
- 可选加速：若 PoC 验证 iOS 系统 SQLite 启用 FTS5 + trigram tokenizer，则改用 FTS5（`memory_terms` 保留为兜底/迁移路径）。

**可选增强**：第三方语义嵌入启用时，向量通道换成真语义向量（签名变化 → 自动重建），其余通道不变。

### 4.5 注入：分块与预算

| 块 | 内容 | 上限 |
|---|---|---|
| `IMPRESSION_LONG` | 该角色的长期印象（≤3 条，每条 ≤80 字） | 300 字 |
| `IMPRESSION_SUSTAIN` | 持续了解（≤3 条） | 300 字 |
| `MEMORY_RELEVANT` | 与话题相关的经历（≤4 条，每条 ≤100 字，带时间） | 500 字 |
| `MEMORY_RECENT` | 最近 7 天事件（≤2 条） | 200 字 |
| `MEMORY_FACTS` | 用户档案类硬事实（称呼/生日/禁忌等，来自画像层，见 4.7） | 200 字 |

- 总预算 ≤1200 字（现状 ≈740 但质量低）；按块从高优先到低优先填充，**整行截断**（修掉「截半条」问题）；
- 注入位置保持现状（user 角色「【本轮参考资料】」+ 数据边界声明），不得回退到 system；
- 每条带来源标注：`[2026-03-14·私聊]`，让模型能区分「什么时候的事」。

### 4.6 印象系统

**数据流**

```
后台整合 Worker
  ├─ 印象合成（每角色 ≤1 次/天 或 每 30 轮）
  │    输入：L3 全部 + importance≥70 的 L2 + 最近 30 天事件摘要 + 近 7 天已注入记忆
  │    输出：[{ statement, topic_slug, tone, level, confidence, evidence_ids, supersedes }]
  ├─ 合并：同 topic_slug 的旧印象 → valid_to=now、superseded_by
  └─ 证据写入 impression_evidence（含 sessionId/messageIds，供「为什么记得」）
「大家的印象」页面
  ├─ 按角色分组（可切换「全部/单角色」），角色卡显示：长期印象 N · 持续了解 M · 互动 K
  ├─ 每条：正文 + 语气 + 置信度 + 「形成于 X · 最近确认 Y · 来自 N 次对话」
  ├─ 展开：证据列表 → 点击跳转到对应会话/消息
  ├─ 「不对」：归档该条 + 写负反馈（整合时避开同类主题）
  └─ 编辑：用户改写后 is_user_edited=1，整合不覆盖用户编辑（只可 append 证据）
```

**关系演化**：印象的可用等级由亲密度档位门控（复用 `relationshipRecallPolicy` 的五档）：

| 亲密度 | 允许形成的印象等级 | 说明 |
|---|---|---|
| 0–19 | 仅事件级（`互动印象`） | 不形成人格结论 |
| 20–39 | 事件 + 明确偏好 | 需 ≥2 次证据 |
| 40–59 | + 持续了解（L2） | 需 ≥3 次证据且跨 7 天 |
| 60–79 | + 长期印象（L3） | 需 ≥5 次证据或跨 30 天 |
| ≥80 | 全部 + 关系型印象（如「把你当搭档」） | 需 L3 + 关系类型一致 |

**跨来源**：印象可吸收群聊/日记/动态来源的记忆（I-03 修复），但标注来源权重：私聊 1.0、群聊 0.6、日记（角色自述）0.5、动态 0.4；`privacy=private` 的条目默认不进「他人可召回」通道。

### 4.7 用户画像层（护城河项，时间允许则做）

把「你是谁」从 147 个角色各自的记忆里抽出来，做成**跨角色共享、用户可编辑**的画像：

```sql
CREATE TABLE user_profile_facts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  fact_key TEXT NOT NULL UNIQUE,     -- name/nickname/birthday/job/timezone/allergy/taboo...
  value TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'inferred',  -- inferred | user_confirmed
  confidence REAL NOT NULL DEFAULT 0.6, updatedAt INTEGER NOT NULL DEFAULT 0
);
```
- 写入：整合 Worker 检测到「用户级事实」时提议，`confidence` 达阈值才自动填；用户在「我的资料」页可确认/修改（改后 `source=user_confirmed`，任何角色都不得覆盖）；
- 读取：所有角色注入 `MEMORY_FACTS` 块（见 4.5）；角色私有记忆不再重复学习同一件事；
- 收益：解决「每个新角色都要重新认识你」的核心体验问题，并显著降低 L1 体量。

### 4.8 生命周期与可运维性

| 项 | 方案 |
|---|---|
| 保留期 | L1 默认 90 天（可配 30/90/永久）；L2 365 天；L3 / 印象 / 用户画像**永久** |
| 清理 | 启动 + 回前台 + 每日一次：`expiresAt<now` → 软删（status=expired）→ 30 天宽限后硬删 + 清向量/倒排/实体 |
| 索引重建 | 触发：签名漂移 / 恢复完成 / 用户点「重建」/ 启动自检发现 `missing/stale`；分批（每批 200，间隔 50ms，可取消），完成后报告统计 |
| 备份 | 格式 v2：新增 `memoryTerms/memoryEntities/impressions/impressionEvidence/userProfileFacts`（可选带 `recallLog` 否）；恢复后**自动重建向量与索引**；兼容读取 v1 备份（缺的字段按默认值补） |
| 恢复语义 | 保留原 `status`（不再强制 archived）；恢复后 24h 内完成索引重建（Gate：恢复前召回集合的 ≥95% 可再次召回） |
| 卸载/删角色 | 删角色 → 级联删除该角色记忆/印象/索引（含关系网引用）；删会话 → 归因其证据链的派生条目（走 links/BFS，修正 M-10 后可靠） |

### 4.9 可观测性与设置

**记忆体检页**（设置 → 记忆 → 体检）

| 面板 | 内容 |
|---|---|
| 概览 | 总条数（L1/L2/L3/印象）、索引状态（ready/stale/missing/expired）、失败窗口数 |
| 成本 | 本月记忆相关调用次数、输入/输出 token、估算费用（按当前模型单价） |
| 最近召回 | 最近 20 轮的候选数/命中通道/注入条数/降级原因 |
| 操作 | 重建索引、清理过期、导出记忆（JSON/Markdown）、清空本角色记忆 |

**记忆设置页**（新增，替换现在散落在记忆详情里的开关）

- 总开关（关闭后：不提取、不召回、不整合，已有数据只读保留）；
- 按来源：私聊/群聊/动态/日记/手动 的「生成」与「召回」两组开关；
- 记忆模式：省流 / 标准 / 深度（映射现有 `memoryRecallMode` 并说明差异）；
- 保留期：L1 30/90/永久；
- 印象：是否允许形成长期印象、是否允许关系网传递；
- 高级：整合频率、模型（沿用聊天模型）、重建索引。

**诊断**：`MemoryDiagnostics.install(...)` 接入现有 `DebugLogger`；召回每次写一条 `memory_recall_log`（可在设置关闭）；日志导出时自动脱敏（不写原文，只写 id 与统计）。

---

## 五、开源选型与参考（GitHub）

> 结论先行：**不引入任何 Python 运行时依赖**，本项目只借鉴算法与数据模型；移动端只考虑能编译进 iOS/KMP 的原生组件。
> 下表仓库信息于 2026-11-26 通过 GitHub API 核对（star 数取当日）。

| 组件 | 仓库 / 许可 | 规模 | 可借鉴的具体点 | 本项目落地方式 | 结论 |
|---|---|---|---|---|---|
| **mem0** | [mem0ai/mem0](https://github.com/mem0ai/mem0) Apache-2.0 | 69.5k★ Python | ①「ADD-only 单次提取」的取舍；②**多信号检索（语义 + BM25 + 实体）融合**；③实体抽取与链接提升召回；④时间感知排序 | 借 D1/D4 设计；在 `MemoryParseRules` + `MemoryRecallEngine` 重写对应逻辑 | **采用（借鉴）** |
| **Graphiti / Zep** | [getzep/graphiti](https://github.com/getzep/graphiti) Apache-2.0 | 33.1k★ Python | **每条事实带有效期窗口**：信息变更时旧事实失效但保留历史；增量更新不重算全图 | 借 D3：`valid_from/valid_to/superseded_by` + 召回默认只取有效；不建图数据库 | **采用（借鉴）** |
| **MemoryOS** | [BAI-LAB/MemoryOS](https://github.com/BAI-LAB/MemoryOS) Apache-2.0 | 1.6k★ Python，EMNLP'25 | 短/中/长三层 + **热度驱动的层间晋升与淘汰** + 用户画像更新 | 借「salience 热度」与「分层晋升」；与现有 L1/L2/L3 天然对齐 | **采用（借鉴）** |
| **Memobase** | [memodb-io/memobase](https://github.com/memodb-io/memobase) Apache-2.0 | 3.0k★ Python | **面向聊天伴侣的用户画像式记忆**；per-user 缓冲批量处理以摊薄 LLM 成本 | 借「画像层」与「批量缓冲」；写入侧做窗口合并 | **采用（借鉴）** |
| **A-MEM** | [agiresearch/A-Mem](https://github.com/agiresearch/A-Mem) MIT | 1.2k★ Python | 记忆笔记之间的**动态链接与演化**（Zettelkasten 式） | 借「链接即演化」思路，强化 `memory_links` 的语义（支持 supersede 链） | 参考 |
| **Cognee** | [topoteretes/cognee](https://github.com/topoteretes/cognee) Apache-2.0 | 33.6k★ Python | 「抽取—图谱化—加载」的管线化组织方式 | 仅参考管线切分，不引入 | 参考 |
| **Letta（原 MemGPT）** | [letta-ai/letta](https://github.com/letta-ai/letta) Apache-2.0 | 25.6k★ | 有状态代理 + 分层记忆 + 后台整合的工程形态 | 参考「后台整合 worker」的产品化形态 | 参考 |
| **sqlite-vec** | [asg017/sqlite-vec](https://github.com/asg017/sqlite-vec) Apache-2.0 | 8.3k★ C | SQLite 内做向量检索（KNN + 元数据过滤），纯 C、极小 | 需 PoC：SQLDelight 的 `NativeSqliteDriver` 默认不加载扩展；若可行则替代手写暴力 KNN | **PoC 后决定** |
| **usearch** | [unum-cloud/usearch](https://github.com/unum-cloud/usearch) Apache-2.0 | 4.4k★ C++ | 单头文件 HNSW，支持 macOS/iOS，有 Swift 包 | 若某个角色的记忆量 >2 万条再引入（KMP cinterop 或 Swift 桥），当前不预置 | **暂不引入（预留）** |
| SQLite FTS5 / trigram | SQLite 官方内置 | — | 中文可用 `tokenize='trigram'` 做子串/短语检索 | 优先**自建 n-gram 倒排**（零依赖、可迁移）；FTS5 作为可选加速，需验证 iOS 系统 SQLite 是否启用 | 自建为主，FTS5 PoC |
| on-device embedding | 待评估（如 bge-small-zh int8 + ONNX Runtime / CoreML） | — | 真正的离线语义召回 | M5 期做 PoC（体积/延迟/质量三维打分），不在 MVP 引入 | **M5 可选** |

**明确不采用**：Mem0/Cognee/Graphiti 的服务端形态（Python 服务、图数据库、云端存储）——与「本地优先」定位冲突；只借算法与数据模型。

---

## 六、里程碑与排期

### 6.1 估算模型（沿用开发计划书 v1.1 第七章口径）

- 代理效率 `e` = **1.6**（人日/代理日）；评审/QA 开销 **+25%**；平均并行度 `c` = **2.2**；
- 代理日 = 人日 / 1.6 × 1.25；墙钟周 ≈ 代理日 / 2.2 / 5；
- 单代理日 API 成本基准 **¥60–110**（DeepSeek 路由，缓存命中率高）。

### 6.2 里程碑

| 里程碑 | 名称 | 交付物 | 人日 | 代理日（含评审） | 并行度 | 墙钟 | Gate |
|---|---|---|---:|---:|---:|---:|---|
| **M0** | 通电与基线（接线修复） | 摘要/重试/重建/清理/诊断全部可触发；基线报告 | 10 | 8 | 2.5 | 0.7 周 | G0 |
| **M1** | 存储与检索底座 | schema v24–v29、词法+实体通道、混合检索、BLOB 向量、重建任务 | 21 | 16 | 3.0 | 1.5 周 | G1 |
| **M2** | 写入与整合 | L1 v3、近似去重、后台 Worker、supersede、衰减 | 20.5 | 16 | 2.5 | 1.5 周 | G2 |
| **M3** | 印象系统 | impression 表与合成器、印象页改版、纠错、关系演化 | 17 | 13 | 2.5 | 1.2 周 | G3 |
| **M4** | 生命周期与设置 | 保留期/清理、记忆设置页、体检页、备份 v2、用户画像层 | 15.5 | 12 | 2.5 | 1.1 周 | — |
| **M5** | 评估、调优与收尾 | 回放集、指标看板、调参、端侧嵌入 PoC、文档与规格卡 v2 | 14 | 11 | 2.0 | 1.0 周 | G4 |
| — | 集成 / 回归缓冲（15%） | 跨里程碑联调、真机长跑、金样重锁 | 14.7 | 12 | 2.0 | 1.0 周 | — |
| **合计** | | | **113** | **88** | 平均 2.5 | **≈8.0 周** | |

> 三档：乐观（e=2.5）≈ **56 代理日 / 5.1 周**；基准（e=1.6）≈ **88 代理日 / 8.0 周**；悲观（e=1.2）≈ **118 代理日 / 10.7 周**。
> 若范围裁到 MVP（M0 + M1 + M2 + 印象只读改版）≈ 60 人日 → **≈46 代理日 / 4–5 周**。

### 6.3 甘特（基准）

```
周次    1    2    3    4    5    6    7    8
M0    ██
M1      ██████
M2            ██████
M3                  █████
M4                    ████
M5                        ████
缓冲                          ████
Gate   G0   G1       G2    G3      G4
```

**关键路径**：M0 → M1（schema 与检索底座）→ M2（整合 Worker 依赖 M1 的索引）→ M3（印象依赖 M2 的晋升产物）→ M5。
可并行：M4 的设置页/体检页（依赖 M1 诊断接口）可与 M2/M3 并行；文档与回放集构造从 M1 起持续做。

### 6.4 并行与写域隔离

| 批次 | 任务 | 写域（互不重叠） |
|---|---|---|
| 批 1（M0） | T62–T68 | `domain/automation`、`iosMain/.../ChatEngineBridge`、`data/Cleanup*`、`backup/wire`、`domain/memory/MemoryDiagnostics`、`MemoryParseRules` |
| 批 2（M1） | T69–T74 | `sqldelight/**`、`data/MemoryV2Repository`、`domain/memory/LexicalIndex`、`MemoryRecallEngine`、`vector/LocalVectorStoreGateway` |
| 批 3（M2） | T75–T80 | `prompt/MemoryV2PromptTemplates`、`domain/memory/MemoryParseRules`+`MemoryV2Pipeline`、`domain/automation/MemoryConsolidationTask` |
| 批 4（M3） | T81–T85 | `sqldelight/impression*`、`domain/impression/**`、`iosMain/.../ImpressionsBridge`、`ios/KnockKnock/Features/Impressions` |
| 批 5（M4） | T86–T89 | `ios/KnockKnock/Features/Settings+Memory`、`backup/wire` |
| 批 6（M5） | T90–T93 | `scripts/eval/**`、`docs/**`、`specs/**` |

> 规则：同一文件不得被两个并行任务同时改；跨域只经 shared `domain` 接口。M1 与 M2 的交接点是「索引写入接口」——由 M1 先冻结签名，M2 消费。

---

## 七、WBS 任务分解（T62 起）

> 每张任务卡落地时按 `specs/规格卡模板-v1.md` 产出规格卡（但 §三.1 已裁决：**新功能的 oracle 是产品指标与本文档，不再要求逐行对照 Android**）。

### M0 通电与基线

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T62 | 维护链路接线 | `ChatEngineBridge`/`GroupChatBridge` 回复后调 `onForegroundReplyCompleted`/`onGroupContextChanged`；`A7Glue` 暴露入口 | 真机聊 30 轮后 `SHORT_TERM_SUMMARY` 非空；编辑历史触发 rebuild | 1.5 |
| T63 | 生命周期接线 | 启动/回前台/每日调 `cleanupExpiredData`；软删改宽限硬删；补 Swift 保留期入口（最小） | 设置 30 天后，过期条目 7 天内转 expired 并在宽限后消失 | 1.5 |
| T64 | 索引重建任务 | 新增 `MemoryIndexRepairTask`（分批 + 可取消 + 统计）；设置页「重建」按钮 | 手工清空 `vector_memories` 后重建，召回恢复到 ≥95% | 2.0 |
| T65 | 恢复语义修正 | 恢复保留原 status；恢复完成自动触发 T64 | 恢复后 L2/L3 不消失；召回 24h 内恢复 | 1.0 |
| T66 | 诊断与召回日志 | 安装 `MemoryDiagnostics` sink；`diagnose()` 接到体检页骨架；召回日志先写 DebugLogger（`memory_recall_log` 表在 M1/T69 落地后再切表） | 日志可见候选数/降级原因/耗时 | 1.5 |
| T67 | 正确性修复 | `parsePromotionItems` 证据对齐；`priorFailures` 传真实值；token 按 `requestType` 计量 | 单测覆盖「非法项/重复项错位」；消费统计出现记忆分类 | 1.0 |
| T68 | 基线报告 | 用现有代码跑回放集 v0（粗标 20 组），产出 2.2 全部指标基线 | 报告归档 `docs/eval/baseline-v0.md` | 1.5 |
| — | **M0 小计** | | | **10.0** |

### M1 存储与检索底座

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T69 | schema v24–v29 与迁移 | 新增列/表/索引；SQLDelight migration；旧库升级测试 | 从 v0.2.0 真实库升级零丢失；备份 v1 可恢复 | 4.0 |
| T70 | 倒排与实体索引 | 索引写入/删除/更新；IDF 统计；停用字表；大小写/标点归一 | 10 万行级索引构建 <5s；查询 <20ms | 4.0 |
| T71 | 向量 BLOB 通道 | float32 编解码；候选查询列裁剪；暴力 topK（预留 IVF） | 单轮解码耗时下降 ≥80%；召回结果与旧实现一致性抽检通过 | 3.0 |
| T72 | 混合检索与融合 | RRF + 长度归一化阈值 + MMR + 层级/新鲜度/使用度加权；降级链（无向量→纯词法） | 回放集 hit@4 相较基线 +15pp；P95 ≤300ms | 6.0 |
| T73 | 使用反馈回路 | 注入即 `markMemoryItemUsed`；`salience` 每日更新；低热度归档 | 被反复注入的记忆 salience 上升；噪声条数下降 | 2.5 |
| T74 | 规格卡与回归 | `specs/memory-recall-v2-spec.md`；召回矩阵测试重写（新阈值口径） | reviewer PASS | 1.5 |
| — | **M1 小计** | | | **21.0** |

### M2 写入与整合

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T75 | L1 提取 v3 | SCHEMA 增 `topic_slug/entities`；提示词 v3；窗口合并（空闲触发） | 抽取准确率 ≥85%；同义偏好产出同 slug | 4.0 |
| T76 | 近似去重与合并 | 同 owner+topic+余弦阈值合并；`mention_count/last_confirmed_at` 更新 | 重复率下降 ≥60%；不误合并（抽检） | 3.0 |
| T77 | 后台整合 Worker | A7 新任务类型；L2/L3 语义分组；节流与退避；失败上报 | 50 轮内产出 L2 ≥1；200 轮内 L3 ≥1 | 6.0 |
| T78 | supersede 与时效 | `valid_from/valid_to/superseded_by`；取代判定；召回过滤；管理页可见历史 | 矛盾率 ≤10%（阶段性）；旧事实默认不再注入 | 4.0 |
| T79 | 衰减与归档 | salience 公式；归档策略；归档可恢复 | 一年模拟数据（合成）下体积 ≤20 MB/角色 | 2.0 |
| T80 | 规格卡与回归 | `specs/memory-consolidation-v2-spec.md`；晋升矩阵重写 | reviewer PASS | 1.5 |
| — | **M2 小计** | | | **20.5** |

### M3 印象系统

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T81 | 印象合成器 | `domain/impression/**`：合成、合并、置信度、关系档位门控 | 300 轮后每角色 ≥8 条；抽检准确率 ≥80% | 5.0 |
| T82 | 印象页改版 | 按角色分组、三段、置信度/时间/证据展开、跳会话 | UI 自检 PASS；截图基线更新 | 4.5 |
| T83 | 纠错与用户编辑 | 「不对」归档 + 负反馈；编辑后不被覆盖；清空 | 全链路自检（生成→改→重生成不覆盖） | 3.0 |
| T84 | 跨来源与关系演化 | 群聊/日记/动态来源权重；亲密度档位；关系网可见性治理（用户开关） | 矩阵测试 + 隐私开关自检 | 3.0 |
| T85 | 规格卡与回归 | `specs/impression-v1-spec.md` | reviewer PASS | 1.5 |
| — | **M3 小计** | | | **17.0** |

### M4 生命周期、设置与画像

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T86 | 记忆设置页 | 总开关/按来源/模式/保留期/印象开关/高级 | 设置项全部生效（自检逐项断言） | 3.5 |
| T87 | 记忆体检页 | 概览/成本/最近召回/操作（重建、清理、导出、清空） | 页面数据与 DB/日志一致 | 3.0 |
| T88 | 备份 v2 | 新表进导出；恢复自动重建；v1 兼容 | 备份↔恢复往返 3 次数据一致 | 4.0 |
| T89 | 用户画像层 | `user_profile_facts` + 检测/确认 UI + 注入块 | 同一事实只学一次；用户确认后跨角色生效 | 5.0 |
| — | **M4 小计** | | | **15.5** |

### M5 评估与收尾

| ID | 任务 | 主要改动 | 验收 | 人日 |
|---|---|---|---|---|
| T90 | 回放集与评估工具 | 20–50 组长对话 + 标注格式 + 评测脚本（hit@k/矛盾率/覆盖率/成本/延迟） | 一键产出指标报告 | 5.0 |
| T91 | 调参与阈值标定 | 阈值/权重/多样性参数网格；本地/第三方两模式 | G4 指标全部达标 | 3.0 |
| T92 | 端侧嵌入 PoC | bge-small-zh int8（ONNX/CoreML）体积/延迟/质量评估 | 决策记录（采用/不采用）+ 数据 | 3.0 |
| T93 | 文档与发布 | README/架构图/规格卡 v2/迁移说明/回滚手册/记忆体检使用说明 | 文档齐套；真机 7 天长跑无崩溃 | 3.0 |
| — | **M5 小计** | | | **14.0** |

> WBS 合计 **98.0 人日**；加 §6.2 的 15% 集成/回归缓冲（14.7）后为 **113 人日**。

---

## 八、质量与验收体系

### 8.1 回放集（Evaluations Set）

| 项 | 规格 |
|---|---|
| 规模 | 20 组 MVP / 50 组完整；每组 = 1 个角色 × 200–300 轮对话（合成 + 人工改写 20%） |
| 覆盖 | 用户事实（姓名/生日/职业/过敏）、偏好（饮食/音乐/作息）、禁忌、约定（生日/见面/提醒）、事件（搬家/换工作/宠物去世）、情绪模式、关系推进 |
| 标注格式 | JSONL：`{turn, type: "should_recall" / "should_absorb" / "should_contradict", expected_facts: [], must_not_include: [], notes}` |
| 生成方式 | 脚本生成对话骨架 → 标注「应记住的事实」→ DSH 扩张对话 → 人工抽检 20%（防止标错） |
| 隐私 | 全部为虚构用户，不含真实个人信息 |

### 8.2 指标计算（与 2.2 对应）

- `hit@k`：注入文本是否包含期望事实的语义匹配（用事实 id 映射 + 关键实体匹配双重判定）；
- `矛盾率`：同 topic_slug 下 `valid_to IS NULL` 的条目中，语义冲突对的比例（模型判定 + 人工抽检）；
- `印象准确率`：对每条印象判定「事实正确 / 角色视角合理 / 无幻觉」，三项全过算对；
- `成本`：从 `memory_recall_log` + `token` 分类统计，按当前模型单价折算。

### 8.3 测试分层

| 层 | 内容 | 门禁 |
|---|---|---|
| 单元/矩阵 | 阈值、融合、RRF、MMR、分组、取代、置信度、档位门控 | 每任务必须新增；覆盖率不降 |
| 集成 | 迁移、恢复往返、重建、清理、并发（多角色同时整合） | CI 必跑 |
| 金样回归 | 旧 `golden/memory/**` 保留，验证「不再漂移」 | 允许因新算法更新金样，但需 reviewer 签字 + 变更说明 |
| 端到端 | 真机 200 轮长跑 + 备份恢复 + 换嵌入模式 | 每 Gate 跑一次 |
| 性能 | 召回 P95、整合耗时、冷启动、体积增长 | 每 Gate 采集 |

### 8.4 「不做什么来通过测试」

明确禁止：为过测试而**写死 fixture**、为过指标而**降低标注难度**、用**模拟向量**替代真实检索路径做验收（旧金样的教训：用固定 similarity 的 fixture 验证阈值，导致阈值从未被真实分布校准）。新测试必须有一条端到端路径使用真实本地哈希嵌入。

---

## 九、迁移、兼容与回滚

### 9.1 迁移链

| 版本 | 内容 | 风险控制 |
|---|---|---|
| v24 | memory_items 扩展列 | 全部带默认值，旧数据 `topic_slug=''` → 后台按 type+content 回填 |
| v25 | 向量 JSON → BLOB | 新表 + 分批回填 + 双读兼容；回填完成才切读；失败回滚保留旧列 |
| v26/v27 | 倒排/实体索引 | 新表；启动时后台构建；构建中检索走「向量 + 无词法」降级 |
| v28 | 印象表 | 新表；首次整合前为空（页面显示空态引导） |
| v29 | 召回日志 | 新表（随 M1/T69 落地）；M0 期间 T66 先写 DebugLogger；可关闭；滚动删除 7 天 |

### 9.2 数据回填

- `topic_slug`：对存量 active 条目，用「type 分组 + 前 2 个高频 2-gram」生成占位 slug，后续整合时由模型纠正；
- `valid_to`：全部 NULL（视为有效）；
- `salience`：按 `importance + log(1+usedCount) + recency` 计算初值；
- 索引（倒排/实体/BLOB）：由 T64 重建任务统一补，**可中断、可续跑、有进度**。

### 9.3 双跑与灰度

- 新增开关 `memory_engine = legacy|v2`（默认在 M1 完成后切 v2，保留 legacy 只读 2 个版本周期）；
- M2–M3 期间 v2 产出与 legacy 并存，指标对比达标后删除 legacy 路径；
- 灰度顺序：开发机 → 自签真机 → TestFlight。

### 9.4 回滚预案

| 场景 | 回滚动作 | 数据影响 |
|---|---|---|
| 迁移失败 | 事务回滚 + 恢复迁移前备份 | 无损 |
| v2 检索异常 | 开关切 legacy（只读旧索引） | 新索引保留，不影响使用 |
| 整合任务异常刷屏 | 关闭整合（设置或远程开关） | 记忆不再沉淀，已有数据不受损 |
| 印象质量事故 | 关闭印象合成；页面回退只读 legacy 视图 | 印象表保留但不展示 |
| 备份格式不兼容 | 只导出 v1 兼容字段 | 新字段丢失但可恢复 |

---

## 十、风险登记册

| ID | 风险 | 概率 | 影响 | 缓解 | 触发信号 |
|---|---|---|---|---|---|
| R1 | 中文语义召回在无端侧模型时仍不达标（G1 不过） | 中 | 高 | M1 先做「几何修复 + 混合通道」，若 hit@4 仍 <60%，提前启动 T92 端侧嵌入 PoC（不等 M5） | G1 指标 <60% |
| R2 | 后台整合与聊天抢模型配额/触发限流 | 中 | 中 | 节流（每角色次数上限）+ 失败退避 + 只在空闲/前台 tick 跑；提供「暂停整合」开关 | 429/超时率 >10% |
| R3 | 迁移期双写/回填导致数据不一致 | 中 | 高 | 回填幂等、可续跑；迁移前后计数对账；恢复往返测试进 CI | 计数差异 ≠0 |
| R4 | 印象主观质量不稳定，抽检不达标 | 中 | 中 | 提示词 v2 + 关系档位门控 + 用户纠错闭环；每 Gate 抽检 30 条 | 抽检 <80% |
| R5 | 代理并行导致写域冲突/合并灾难 | 中 | 中 | 严格按 §6.4 批次与写域；每任务独立分支；lead 统一合并前跑全量回归 | 冲突文件 >0 |
| R6 | 旧金样与新算法冲突，误判为「回归」 | 高 | 低 | 明确「金样=历史基线」并允许带签字更新；新增产品指标作为主门禁 | 金样 diff 频繁 |
| R7 | 电池/性能：整合任务在后台被系统杀死 | 中 | 中 | 断点续跑（任务状态落 DB）；前台补算；进度可恢复 | 任务完成率 <90% |
| R8 | 用户不接受「印象可被自动改写」 | 低 | 中 | 用户编辑优先；提供「冻结印象」；变更历史可见 | 用户投诉/关闭率 |
| R9 | 范围蔓延（画像层/端侧模型/知识库重构） | 高 | 中 | 画像层与端侧模型为「可选包」，G3 后再裁决；知识库明确不动 | 新增需求 >2 条/周 |
| R10 | 成本超预算（模型调用） | 低 | 低 | 每角色调用上限 + 月度成本面板；超出自动降频 | 月费用 > 预算 1.5 倍 |

---

## 十一、成本与资源

### 11.1 代理与 API

| 档位 | 代理日 | 单代理日成本 | API 小计 | ×1.4 杂项（金样重跑/探索） |
|---|---:|---:|---:|---:|
| 乐观（e=2.5） | 56 | ¥15–25 | ¥0.08–0.14 万 | ¥0.12–0.20 万 |
| **基准（e=1.6）** | **88** | **¥60–110** | **¥0.53–0.97 万** | **¥0.74–1.35 万** |
| 悲观（e=1.2） | 118 | ¥150–250 | ¥1.77–2.95 万 | ¥2.5–4.1 万 |

### 11.2 其他

| 项 | 成本 | 说明 |
|---|---|---|
| Apple Developer | 已有（¥688/年） | 真机/TestFlight |
| 测试机 | 已有（自签真机） | 记忆长跑与性能 |
| 用户签字点 | **15–25 小时** | 范围裁决、G0–G4 验收、印象抽样评审（主观项必须人看） |
| CI/额外 Mac | 可选 | 缓解构建串行 |

### 11.3 资源需求

| 角色 | 配置 | 职责 |
|---|---|---|
| Lead（根代理） | 1 | 拆卡、写域裁决、合并、Gate 主持 |
| 领域实现代理 | 2–3 并行 | M1 检索底座 / M2 整合 / M3 印象 分线 |
| Reviewer | 1 | 规格卡评审、金样变更签字、代码复核 |
| QA | 1 | 回放集执行、指标复算、真机长跑 |
| 人类 | 按需 | 签字点 + 印象主观抽样 |

---

## 十二、交付物清单

**代码**
- `shared/src/commonMain/kotlin/.../domain/memory/**`（检索/整合/规则）
- `shared/src/commonMain/kotlin/.../domain/impression/**`（新）
- `shared/src/commonMain/sqldelight/**/24.sqm … 29.sqm`（迁移）
- `shared/src/iosMain/.../iosbridge/**`（桥：MemoryBridge/ImpressionsBridge/ChatEngineBridge/A7Glue）
- `ios/KnockKnock/Features/Impressions/**`、`Features/Memory/**`、`Features/Settings/MemorySettingsView.swift`、`Features/Memory/MemoryHealthView.swift`

**规格与文档**
- `specs/memory-recall-v2-spec.md`、`specs/memory-consolidation-v2-spec.md`、`specs/impression-v1-spec.md`
- `docs/记忆与印象系统架构-v2.md`（含架构图与数据字典）
- `docs/eval/baseline-v0.md`、`docs/eval/g1..g4-report.md`、`docs/eval/replay-set-v1/`
- 迁移与回滚手册、记忆体检使用说明

**证据**
- `handoffs/` 下逐任务实现报告（写域/验证/限制）
- `screenshots/` 印象页/体检页/设置页新基线
- 真机长跑日志与指标报告

---

## 附录 A：问题 → 工作包 → 指标 追溯矩阵

| 问题 | 对应任务 | 验收指标 |
|---|---|---|
| M-01 晋升不可达 | T75, T77, T78 | 印象形成速度；L2/L3 产出量 |
| M-02 维护未接线 | T62 | 摘要非空；失败自动恢复 |
| M-03 恢复失忆 | T64, T65, T88 | 恢复正确性 ≥95% |
| M-04 换签名失忆 | T64, T88 | 换模型后 24h 恢复 |
| M-05 冲突无覆盖 | T76, T78 | 矛盾率 ≤5% |
| M-06 召回质量 | T70, T71, T72, T73 | hit@4、P95 |
| M-07 无反馈 | T73 | salience 有效性（噪声下降） |
| M-08 无界增长 | T63, T79, T88 | 单角色年增量 ≤20 MB |
| M-09 诊断缺失 | T66, T87 | 失败可见率 100% |
| M-10 证据错位 | T67 | 错位用例回归通过 |
| I-01 印象非实体 | T81, T82 | 印象覆盖量/准确率 |
| I-02 无生产者 | T77, T81 | 印象形成速度 |
| I-03 来源窄 | T84 | 跨来源印象占比 >0 且可解释 |
| I-04 不可解释/纠错 | T82, T83 | 纠错链路自检 |
| I-05 噪声/无分页 | T72, T82 | 印象条数受控（按角色分页） |
| X-01 死代码/死设置 | T67, T86 | 死设置清理清单 |

## 附录 B：接口契约（Kotlin，供桥与 UI 消费）

```kotlin
// 检索结果（替代当前「返回 String」的隐式契约）
data class MemoryBlock(
    val kind: String,            // IMPRESSION_LONG | IMPRESSION_SUSTAIN | MEMORY_RELEVANT | MEMORY_RECENT | MEMORY_FACTS
    val lines: List<MemoryLine>,
    val maxChars: Int,
)
data class MemoryLine(
    val memoryId: Long, val text: String,
    val happenedAt: String?,     // 事件时间（展示用）
    val sourceLabel: String,     // 私聊 / 群聊 / 日记 / 动态 / 画像
    val level: String,           // L1 | L2 | L3 | IMPRESSION
)
data class RecallDiagnostics(
    val candidateCount: Int, val channelHits: Map<String, Int>,
    val degradedReason: String?, val elapsedMs: Long,
)

// 印象
data class ImpressionView(
    val characterId: String, val statement: String, val tone: String,
    val level: String, val confidence: Double,
    val firstSeenAt: Long, val lastConfirmedAt: Long,
    val evidenceCount: Int, val isUserEdited: Boolean,
)
data class ImpressionEvidenceView(
    val sessionId: String, val messageId: Long, val snippet: String, val at: Long,
)

// 桥接口（新增/变更）
// ImpressionsBridge: list(scope), detail(impressionId), reject(impressionId), edit(impressionId, statement)
// MemoryBridge:      health(), rebuildIndex(onProgress), export(characterId), clear(characterId)
// SettingsBridge:    memorySettings() / setMemorySetting(key, value)
```

## 附录 C：参考资料（GitHub，2026-11-26 核对）

- mem0 — <https://github.com/mem0ai/mem0>（Apache-2.0；single-pass ADD-only 提取、语义+BM25+实体多信号融合、时间感知召回）
- Graphiti / Zep — <https://github.com/getzep/graphiti>（Apache-2.0；事实带有效期窗口，变更即失效保留历史）
- MemoryOS — <https://github.com/BAI-LAB/MemoryOS>（Apache-2.0；短/中/长期分层 + 热度驱动更新 + 用户画像）
- Memobase — <https://github.com/memodb-io/memobase>（Apache-2.0；面向聊天伴侣的用户画像式长期记忆、per-user 缓冲）
- A-MEM — <https://github.com/agiresearch/A-Mem>（MIT；记忆链接与演化）
- Cognee — <https://github.com/topoteretes/cognee>（Apache-2.0；记忆管线化组织）
- Letta — <https://github.com/letta-ai/letta>（Apache-2.0；有状态代理的分层记忆形态）
- sqlite-vec — <https://github.com/asg017/sqlite-vec>（Apache-2.0；SQLite 向量检索扩展）
- usearch — <https://github.com/unum-cloud/usearch>（Apache-2.0；单头文件 HNSW，支持 iOS）
- Zep 论文 — <https://arxiv.org/abs/2501.13956>（时序知识图谱架构）

## 附录 D：批准页

| 角色 | 结论 | 日期 | 备注 |
|---|---|---|---|
| 产品/用户 | ☐ 批准 ☐ 修改后批准 ☐ 退回 | | 重点裁决：§1.4 反范围、§2.2 指标目标值、§11 预算 |
| Lead | ☐ 批准 ☐ 退回 | | 拆卡前确认 §6.4 写域与批次 |
| Reviewer | ☐ 批准 ☐ 退回 | | 确认 §3.3 ADR 与 §8.4「不做什么来通过测试」 |

> 批准后动作：① 生成 `specs/` 首批三张规格卡（T69/T72/T77）；② 在 Team 任务板建 T62–T93；③ M0 启动。