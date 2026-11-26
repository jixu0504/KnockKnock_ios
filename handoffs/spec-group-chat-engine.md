# handoff：spec-group-chat-engine（群聊轮次引擎规格卡，task-20 / T20）

> 代理：group-chat-spec-card ｜ 任务：task-20 ｜ 状态：**完成（reviewer PASS-WITH-FIXES 2026-11-23，修正已落实；lead 已批准 2026-11-23）**
> 交付：`ios-port/specs/group-chat-engine-spec.md`（按模板 v1，20 条行为契约全部带 file:line、共性/差异表、验收可自动判定）、本文件。

## 1. 方法

1. 认领 task-20（revision 1 → claim → 2），写域仅 ios-port/{specs,handoffs}，仓库 RhodesLink/ 全程只读。
2. 静态只读勘察 oracle：
   - `GroupChatViewModel.kt` 2945 行全读（sendGroupMessage :817-930、executeGroupTurn :937-1846、sendHiddenGiftMessage :1849、retryFailedMessage :1886、resumePersistedReply :2124、runScheduledAutoTurn :1933、sendGroupImageMessage :2247、resumeImageReply :2862、recallMessageSegment :393、restartGroupSession :598、deleteGroup :631、自动互动 :647-815、解析/降级 :2099-2680、维护 :2693-2929）。
   - `viewmodel/shared/` 四件套全读：GroupEmotionRules.kt（306）、GroupTaggedReplyParser.kt（128）、GroupRuntimeContext.kt（84）、GroupOutputBudget.kt（28）。
   - automation：GroupAutoChatScheduler.kt（134 全读）、GroupAutoChatWorker.kt（55 全读）、ManualReplyScheduler.kt/ManualReplyWorker.kt（群聊 surface 分支 :42-46, :80）。
   - 支撑：ChatStageTimeout.kt（62 全读）、ReplyTurnRepository.kt（115 全读）、NetworkModels.kt（GroupTurnState :210-222 / GroupMsgResult :281-289）、ChatRepository.kt :327-344、SettingsRepository.kt 群设置键全集、SessionViewModel.saveGroup :79-121、MainViewModel.setGroupMode :1813-1834、RhodesApplication.kt:83、BackupRestoreCoordinator.kt:117。
   - 资产交叉：checklist 功能对照清单 §1.1 行 46-47、§2.5（A7 保留项）、handoffs/a7-presearch.md:50、golden/backup/minimal-1.14.27.md（无群会话）、golden/prompts/inventory.md（群聊块 18 个 raw 文件）、现有测试 GroupEmotionTest(30)/GroupRuntimeContextTest(app 18 + shared 18)。

## 2. 关键发现（对后续卡重要的）

1. **T11 拆分已落地，抽取沿用其结构**：GVM 内 `executeGroupTurn`（:937）为 JVM 64KB 方法上限的逐字抽取，内部嵌套 `prepareTurn`（:1040-1513）/`runModelTurn`（:1514-1791）两个局部函数，闭包按引用捕获局部变量。规格卡明确 GroupChatEngine 按此结构切，不重排控制流。
2. **与私聊共享的层远比想象多**：reply_turns 同一张表同一套 SQL（surface="group"、triggerKind 多一个 group_auto）、同一 ManualReplyWorker（surface 路由 :42-46）、同一合并批次常数（2×600）、同一 withChatStageTimeout（surface="group" 参数化）。真正群聊独有的是：tagged 多发言人协议解析、GroupEmotionRules、GroupTurnState 策略、成员名册/禁言校验、GroupRuntimeContext 别名去重、自动互动 durable plan、注入顺序契约。
3. **超时预算全集（与私聊不同，勿混用）**：整轮 210s(+60s)=270s、prompt 60s、model 150s(+60s)、write 15s、重启清理等待 2s、可选上下文块 10-30s（剩余 prompt 预算-2s、<1s 跳过）、内容重试**无条件一次**（私聊有剩余<5s 门槛，群聊没有）、上下文超限重试 ≤6 次保最新 10 条（私聊 3 次减半 min 5）。
4. **双层租约**：自动轮次在 reply_turns 之外还有 GroupAutoChatPlan（token/revision/state/claimedAt/leaseExpiresAt）二次 claim（GroupAutoChatScheduler.kt:80-86，lease 5min）；双触发（前台内存循环 + Worker）靠 plan token/revision 单飞。
5. **成员权限 = 名册解析 + mutedMembers，不查 operator 级 msg permission**：`getOperatorMsgPermission` 仅用于主动私聊/动态（MainViewModel），群聊引擎内的权限面是 session.members/mutedMembers（GVM:1121-1134）+ 发言人白名单（:2537-2547）。「名册未解析」与「全员禁言」是两条不同的失败路径：前者 release+5s 重试不燃尽 turn（:1142-1154），后者系统消息+complete（:1155-1167）。
6. **群聊没有**：重新生成、续写、存档创建/加载、擦除、亲密度/催眠结算——都是差异白名单（Android 本身没有）。结算面只剩手动轮次每日龙门币 50 + 未读 + 通知 + lastMessage。
7. **金样缺口**：golden/backup 仅私聊 7 条；群聊回放 fixture 需实现期合成（ROLES 页角色为成员 + 确定性 fake AI，沿用 T15 R5 裁决）。**提示词金样则现成**：shared/commonTest golden/prompts 有群聊块 18 个 raw 文件 + GoldenPromptTest diff 机制——但 inventory.md 行号是 T11 重构前快照。**处置（reviewer P2 修正）**：金样保持基线快照（raw 114 件 + .raw-manifest.sha256 门禁，sync_golden.sh 仅单向同步），行号校准以从 v1.14.27-android-baseline tag 重抽方式、不覆盖基线金样（R4）。
8. **五个仅定义未调用的助手**（grep 确认无调用点）：isLowSignalKnowledgeQuery :2191、saveGroupAnchorToVector :2420、untrustedGroupRepairInput :2528、isStrictGroupJson :2682、requiresFormatRepair :2690——规格卡建议 YAGNI 不迁移，请 reviewer 确认（R6）。
9. **A7 交叉结论（lead 已裁决 2026-11-23）**：a7-presearch.md:50 已确认「BGTask 只是外壳，语义承载层与 A1 群聊自动互动共享」。**裁决**：通用 DelayedTaskLayer（shared/domain/automation/）由**本卡实现波次同批落地**，契约 = T22 spec §A-12 `DelayedTaskChannel`（schedule/cancel/pendingKeys/onDue）冻结接口；本卡领域层只依赖该协议 + AutoChatPlanPort，A1 自动互动的通道适配实现（Timer/BGTask）归 T22 胶水批；手动回复恢复仍为启动 reconcile + 前台补算（不经 DelayedTaskLayer）。
10. **群资料注入顺序是可契约化的固定序列**（GVM:1112-1543）：required 3 步 → optional 10 步（任一失败跳过）→ replacements(45+) → 连续性/情绪 → GroupRuntimeContext 别名家族去重 → 成员标识头。这是群聊与私聊 prompt 组装的最大差异，已在规格卡契约 10 逐条锚定。

## 3. 交付文件

| 路径 | 内容 |
|---|---|
| `ios-port/specs/group-chat-engine-spec.md` | 模块信息（A1 范围映射）/与私聊引擎共性差异表（21 行：复用 vs 独有逐项标注）/输入输出边界（超时参数全集）/20 条行为契约（逐条 file:line）/数据与迁移（表 + 设置键全集）/验收（11 项可自动判定 + 边界用例清单 + 双路径命令）/抽取落点（shared/domain/chat/group/ 子包 + 理由 + Android 改动面 + iOS 门面草案）/风险与开放问题（R1-R9） |

## 4. 评审结果与裁决（2026-11-23）

reviewer：**PASS-WITH-FIXES**（0 P1），3 项修正已落实：
1. 【P2】R4 表述改为「**金样保持基线快照；行号漂移以从 v1.14.27-android-baseline tag 重抽的方式校准，不覆盖基线金样**」——原「重跑 sync_golden.sh 校准」会误导实现者破坏基线金样；验收 #8 白名单同步去掉「行号元数据」。
2. 【P3】GroupAutoChatScheduler 实为 **REPLACE 无退避**（按源码实测）：worker 入队 `ExistingWorkPolicy.REPLACE`、未配置 setBackoffCriteria（GroupAutoChatScheduler.kt:123-130）；「重试」= releaseClaimForRetry 把 plan 置回 pending(dueAt=+60s) + REPLACE 新 worker（:70-77），契约 20 已改写。
3. 【P3】GroupRuntimeContextTest 双份去重口径写清：app/src/test 与 shared/commonTest 两份 18 个用例名逐一对齐（同一套件双副本），群相关现有 @Test 计 **30+18=48**，迁移后 commonTest 版为唯一幸存副本（验收 #1）。

lead 裁决（已回填规格卡评审结论节）：
- 通用 **DelayedTaskLayer 由本卡实现波次同批落地**，契约以 **T22 spec §A-12** `DelayedTaskChannel` 冻结接口为准；本卡在接口设计（GroupChatProtocols/AutoChatPlanPort、iOS 门面草案、R1）中标注与 T22 的边界——**A1 触发的通道适配实现归 T22 胶水批**。

## 5. 遗留 open questions（仍未裁决）

1. **R2 差异白名单确认**：群聊无重新生成/续写/存档/擦除/亲密度结算（Android 1.14.27 本身没有，iOS 不发明）——reviewer 未驳回，实现期按卡执行。
2. **R3 轮次回放 fixture**：实现期以确定性 fake AI 合成群会话 fixture（沿用 T15 R5 裁决），reviewer 复核，落 ios-port/golden/ 群聊子目录。
3. **R6 五个无调用点助手**（:2191/:2420/:2528/:2682/:2690）迁移 or 删除——本卡记录为 oracle 事实、建议 YAGNI 不迁移，实现期确认。
4. **R7/R9**：A2 群资料卡边界与 reply_turns 不进备份（沿用 T15 open ④）需 A2/A5 卡交叉核对。

## 6. 纪律记录

- 全程 read/grep/glob/bash(ls/wc/sed/grep)，未修改 RhodesLink 仓库任何文件；仅写入 ios-port/specs/group-chat-engine-spec.md 与本文件（写域合规）。
- 未运行构建/测试（纯文档任务）；无残留后台作业。
