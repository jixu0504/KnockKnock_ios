# 规格卡：A7 延迟任务层 + 动态定时回复 + 记忆维护（Spec Card v1）

> 工程纪律：reviewer 批准本卡后，实现代理方可开工。
> 黄金法则：Android 1.14.27（RhodesLink @ ios-port，tag v1.14.27-android-baseline，3642d2c）是行为 oracle；所有「行为」描述指向 Android 源码位置（file:line）。仓库只读，本卡全程静态分析。
> 预研/实测依据：`ios-port/decisions/a7-预研.md`（T7，拆分裁决）、`ios-port/specs/bg-task-notification-spec.md`（T13 实测：模拟器 BGTaskScheduler 不可用 code=1、深触发链 5/5、provisional 通知全链路、产品文案）。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | A7 延迟任务层 + 动态定时回复 + 记忆维护 |
| 所属阶段 | Phase 1（本卡定义共享层接口与行为契约；**实现顺序（lead 裁决 2026-11-23）：A1/群聊波次先落地通用层，A7 波次随后接 MomentReply/ContextMaintenance 胶水**；BGTask+UNUserNotification 等 iOS 壳接线由后续 iOS 壳波次完成） |
| 范围映射 | **A7 拆分移植，保留 2 项**：动态定时回复（MomentReplyWorker 等价物）+ 记忆维护（ContextMaintenanceWorker 等价物）。弃 4 项：每日动态、主动私聊、免打扰时段、手动回复定时器（整体移植触发条件已排除——比值 43–65% < 80%，见 §风险「触发条件与回退预案」；弃项仅记录触发点作背景，不进本卡契约） |
| 对应 Android 源码 | `app/src/main/java/com/rhodes/privatechat/automation/MomentReplyWorker.kt`（35 行）、`automation/ContextMaintenanceWorker.kt`（43 行）、`viewmodel/MainViewModel.kt:620-639,706,1216-1279,2781-2793,2833-2912,2927-3022,3060-3066`、`viewmodel/ChatViewModel.kt:501-506,636-638,2205-2267,2622,2721-2732,2776,2803-2808`、`viewmodel/GroupChatViewModel.kt:479,485,2802,2845`；依赖 shared 层 `data/ChatRepository.kt:332-346`、`data/ReplyTurnRepository.kt:9-102`、`settings/SettingsRepository.kt:173,485` |
| 依赖模块 | A1 群聊自动互动（**lead 裁决：通用延迟任务层由 A1/群聊实现波次先行落地，契约以本卡 §A-12 为准；本卡对应的实现波次在其后接入胶水**）、动态广场（moment_comment 管线随动态广场移植）、记忆子系统（摘要/抽取管线随记忆子系统移植）、聊天核心（ReplyTurn 租约协议随共享层移植）、通知层（聊天必建，A7 仅复用） |

## 输入 / 输出 / 边界

- **输入**：
  1. 领域 enqueue 请求：`(uniqueKey, dueAt, payload)`——来自评论生成后 arming（MainViewModel.kt:2847）、启动 reconcile（MainViewModel.kt:628-639）、UI 手动重试（MainViewModel.kt:2866-2874）、摘要失败自动重试（ChatViewModel.kt:638）。
  2. 到期信号：前台 Timer 到期 / BGTask launchHandler 被系统调用 / app 启动 reconcile 扫描。
  3. 空值与非法值行为：`dueAt` 缺省按 `now`（ContextMaintenanceWorker.kt:15 默认 `at = System.currentTimeMillis()`；MomentReplyScheduler 默认 `at = 0L` MomentReplyWorker.kt:15）；`payload` 缺关键字段（如 turn id 为空）→ 直接 `done`（MomentReplyWorker.kt:28、ContextMaintenanceWorker.kt:28）。
- **输出**：执行体返回三态 `success / retry / done`（MomentReplyWorker.kt:33：`executeMomentReplyTask(id)` true→success、false→retry；`ContextMaintenanceWorker.kt:39` 同构）。副作用：评论落库（MainViewModel.kt:2928）、评论分层记忆写入（MainViewModel.kt:2932）、摘要/抽取记忆写入（ChatViewModel.kt:2239,2253,2262）、本地通知（iOS 新增，见 B-13 平台差异白名单）。
- **边界**：
  - 上限：启动 reconcile 扫描窗口 24h（`REPLY_RECONCILE_WINDOW_MS = 24*60*60*1000` MainViewModel.kt:140）、`retryableReplyTurnIds` limit 50（ChatRepository.kt:346 默认 limit 50L）。
  - 超时：单任务执行 4 分钟（`withTimeout(4*60_000L)` MainViewModel.kt:2891）；租约 10 分钟（`claim(id, token, now, now + 10*60_000L)` MainViewModel.kt:2882）；评论上下文读取 500ms（MainViewModel.kt:2950）。
  - 并发：同 turn 双通道去重（`pendingCommentJobs.putIfAbsent` MainViewModel.kt:2858-2859）；跨执行体互斥靠租约 `claim`（MainViewModel.kt:2882，claim 失败→retry）；同 moment 评论信号量 `mentionedCommentSemaphore`（MainViewModel.kt:2892）；记忆维护会话锁 `ContextMaintenanceLocks.forSession(session.id).tryLock()`（ChatViewModel.kt:2217-2219、GroupChatViewModel.kt:485）。
  - 错误：网络约束 CONNECTED（MomentReplyWorker.kt:20、ContextMaintenanceWorker.kt:19）；备份恢复进行中一律 retry（MomentReplyWorker.kt:27、ContextMaintenanceWorker.kt:27）；执行异常→retry、CancellationException 透传（ContextMaintenanceWorker.kt:40-41）；来源/角色删除→cancel+done（MainViewModel.kt:2887-2889）；空模型回复→视为失败（MainViewModel.kt:3024-3025 抛 `IllegalStateException("模型未返回有效评论")`）。

## 行为契约（逐条，每条带 Android oracle 位置）

### A. 通用「延迟任务层」DelayedTaskLayer（抽取落点 `shared/domain/automation/`，A1/A7 共用）

> 口径（lead 裁决 + a7-预研 §2a 薄层结论）：iOS 不移植 WorkManager；新建通用薄层，**领域接口不依赖 WorkManager/BGTask/UNUserNotification**，iOS 壳波次实现「调度通道」宿主协议。Android 5 个 Scheduler 共用同一惯用法（`enqueueUniqueWork + Constraints + 退避`，MomentReplyWorker.kt:16-21 / ContextMaintenanceWorker.kt:16-21 / DailyContentScheduler.kt:205-211 / ManualReplyScheduler.kt:20-25 / GroupAutoChatScheduler.kt:124-129），证明该原语是唯一共享点。

1. **唯一键入队 + KEEP 语义**：`enqueue(uniqueKey, dueAt, payload)`；同键已存在挂起任务时不重复入队、不重置时间。Oracle：`enqueueUniqueWork("moment-reply-$id", ExistingWorkPolicy.KEEP, request)` MomentReplyWorker.kt:21；ContextMaintenanceWorker.kt:21 同构。
2. **延迟下限钳制**：`initialDelay = (dueAt - now).coerceAtLeast(0)`——过期任务立即执行。Oracle：MomentReplyWorker.kt:18、ContextMaintenanceWorker.kt:17。
3. **到期判定在执行时，不信任系统准点**：层把「是否到期」判定放在执行体入口/执行时（`nextAttemptAt ≤ now + 容忍窗口`），因为 iOS BGTask 是机会型调度、`earliestBeginDate` 仅下限（T13 实测：模拟器提交即被拒 `code=1`、真实 earliestBeginDate 无法生效）。Oracle 侧证：Android 端同样不假设准点——启动重挂把 `nextAttemptAt` 传回调度器（MainViewModel.kt:628-631），计划器自带「迟建铺开」恢复（DailyContentScheduler.kt:229-241）。
4. **EXP 退避 1 分钟**：层默认 `Exponential backoff, initial 1min`。Oracle：MomentReplyWorker.kt:19、ContextMaintenanceWorker.kt:18。（领域级退避另见 B-10/C-7，比层默认更精细，实现以领域为准。）
5. **网络约束**：任务声明需要网络（CONNECTED）；不满足时挂起。Oracle：MomentReplyWorker.kt:20、ContextMaintenanceWorker.kt:19。
6. **执行三态协议**：`done`（含「无此任务/已终态/会话已删」的幂等成功）、`success`、`retry`（退避后重入队）。Oracle：MomentReplyWorker.kt:28（缺参→success）、:33（true→success/false→retry）；ContextMaintenanceWorker.kt:28,31,39。
7. **启动 reconcile**：app 启动时扫描挂起/应重试清单并重入队。Oracle：MainViewModel.kt:620-639 一个 init 块内三件套（手动回复 620-627 / 动态回复 628-631 / 记忆维护 632-639）；窗口 24h（MainViewModel.kt:140,622）。iOS 等价：启动 + 回前台时各扫一次，过期任务交给前台主通道立即执行。
8. **双通道执行（Android 原语，iOS 平移）**：同一 turn 同时 arm「持久调度 + 进程内延迟」，进程存活走进程内通道、进程被杀走持久通道；进程内通道用 map 去重。Oracle：`armMomentReplies` MainViewModel.kt:2847-2859（`MomentReplyScheduler.schedule` + `appScope.launch { delay; executeMomentReplyTask }` + `pendingCommentJobs.putIfAbsent`）。**iOS 映射**：前台 Timer（`DispatchQueue.asyncAfter`/Timer）为主通道，BGTaskScheduler 为兜底通道；两通道最终都调同一个执行体入口（保证语义一致、只争一次租约）。
9. **恢复护栏**：备份/恢复进行中，任何到期任务不执行、直接 retry。Oracle：MomentReplyWorker.kt:27、ContextMaintenanceWorker.kt:27（`BackupRestoreMaintenance.active`）。iOS 等价：恢复流（随备份/恢复卡）置全局标志，任务层读取并 retry。
10. **层不做领域互斥**：互斥（租约/锁）由执行体领域逻辑负责，层只负责「唯一键 + 到期 + 退避 + 重挂」。Oracle：claim 在 executeMomentReplyTask 内（MainViewModel.kt:2882），锁在 runContextMaintenance 内（ChatViewModel.kt:2217）。
11. **取消**：Android 无显式 cancel 调用点（成功后自然销号；失败释放租约后排下一次，MainViewModel.kt:2909-2910）。iOS 层仍提供 `cancel(key)`（唯一键取消，供后续 UI/领域使用），但**不承诺中断已 running 任务**（等价于 Android 语义）。
12. **iOS 接线接口边界（本卡定义、A1/群聊实现波次按此落地通用层，iOS 壳波次实现通道适配）**：层仅依赖协议 `DelayedTaskChannel`（`schedule(key, dueAt, payload)` / `cancel(key)` / `pendingKeys()` / `onDue(callback)`），iOS 壳提供两个实现：前台 `TimerDelayedTaskChannel`（进程内 Timer，主通道）与 `BGTaskDelayedTaskChannel`（BGAppRefreshTask，兜底，Info.plist 注册 `BGTaskSchedulerPermittedIdentifiers`，T13 实测注册/launchHandler/expirationHandler 语义可用）。层不 import BackgroundTasks/UserNotifications。**本条为冻结契约：A1 波次与 A7 波次均不得单方面修改。**

### B. 动态定时回复（MomentReplyWorker 等价物）

1. **Turn 生成**：用户评论生成后，为每个目标角色创建 ReplyTurn：唯一 id = `"moment-" + SHA-256(momentId:operatorId:parentCommentId:sourceCommentId|userContent)`；`surface="moment_comment"`、`triggerKind="moment_comment"`、`mode="online"`、`status="pending"`、`attemptCount=0`、`nextAttemptAt = now + (immediate ? 0 : 随机 10–30s)`。Oracle：MainViewModel.kt:2835-2845（id 生成 :2836-2838）、:2833（`randomCommentDelayMs = 10_000 + random*20_000`）、:2843（nextAttemptAt）。触发点：评论保存后 :2781-2787（`immediate = op.id == moment.operatorId` 帖主立即回）、提及场景 :3060-3066。
2. **Arming 双通道 + 去重**：每个 turn 入队 `moment-reply-$id` 且启动进程内 delay 任务；同 id 已有进程内任务则 cancel 新 job 不重复 start。Oracle：MainViewModel.kt:2847-2859。
3. **启动重挂**：启动时 `pendingMomentTurnIds()` 逐个重入队，`dueAt = nextAttemptAt ?: 0`。Oracle：MainViewModel.kt:628-631。
4. **执行守卫**：恢复中→retry；turn 不存在→done（true）；`surface != "moment_comment"` 或 `status ∉ {pending, running}`→done（true）。Oracle：MainViewModel.kt:2877-2879。
5. **租约 claim（10 分钟）**：随机 token + `claim(id, token, now, now+10min)`；claim 失败（他人持有）→retry。Oracle：MainViewModel.kt:2880-2882（claim 协议 ReplyTurnRepository.kt:31-34：CAS 到 running + 校验 leaseToken）。
6. **来源存在性检查**：source 评论/parent 评论/moment/operator 任一已删除→`cancel(id, "互动来源或角色已删除")` + done（不生成回复）。Oracle：MainViewModel.kt:2885-2889。
7. **执行体**：4 分钟总超时 + `mentionedCommentSemaphore`（同 moment 评论回复串行），内部调 `triggerSingleAiReply`。Oracle：MainViewModel.kt:2891-2896。
8. **moment_comment 管线（与广场手动回复同一管线，a7-预研 §2b）**：模板 `getPromptTemplate("moment_comment")`（MainViewModel.kt:2987）→ 评论上下文 `takeLast(settings.commentContextCount)`（:2947-2950）→ 记忆上下文 `buildCommenterMemoryContext`（:2954，定义 :2271）→ 知识库上下文 + 社交上下文（:2962-2969）→ 替换变量装配（:2978-3021）→ `generateValidComment`（:3022，min/maxChars 由 `settings.commentMinChars/commentMaxChars` 控制）；模型返回空→抛异常→失败路径。Oracle：MainViewModel.kt:2939-3022。
9. **落库与副作用**：`insertCommentForReplyTurn(comment, turnId, leaseToken, sourceCommentId)`（租约内落库）；`memoryV2Enabled && momentCommentMemoryGenerationEnabled` 时 `memoryV2Pipeline.ingestMomentComment`（分层记忆写入，失败仅记日志不阻塞）；`refreshMomentCommentCount` + `refreshMomentsNow`。Oracle：MainViewModel.kt:2927-2936。
10. **失败退避（领域级，覆盖层默认）**：`attemptCount ≥ 4` → `fail`（终态）；否则 `release` 且 `retryAt = now + 60s << (attemptCount-1).coerceIn(0,3)`，即 1/2/4/8 分钟。Oracle：MainViewModel.kt:2907-2912（release 协议 ReplyTurnRepository.kt:50-52）。**口径修正（lead 2026-11-23，oracle 优先）**：claim 时 `attemptCount` 已 `++`，因此**第 4 次执行失败时 `attemptCount=4` 即进入 fail 终态**，不会出现「第 5 次失败」；8min 分支仅存在于公式（纯函数 golden 覆盖），实际不可达。
11. **成功判定**：执行后 turn `status ∈ {succeeded, cancelled}` → success。Oracle：MainViewModel.kt:2897。
12. **UI 手动重试**：用户点重试→`momentTurns(momentId).filter{status=="failed"}` 逐个 `resetFailedMomentTurn(id, now)` + 重新入队。Oracle：MainViewModel.kt:2866-2874（reset 协议 ReplyTurnRepository.kt:82-84）。
13. **完成补算发本地通知（iOS 平台差异白名单）**：回复落库成功后发一条本地通知「XX 回复了你的评论」。**白名单理由**：Android 动态回复无专属通知（a7-预研 发现 2：仅主动私聊发通知，MainViewModel.kt:1265-1267）；iOS 补算多发生在后台/刚打开，通知是唯一触达面，T13 实测通知全链路可用（provisional 授权→送达 count=1）。通知发出时刻=落库时刻（「完成即提醒」准点在发出侧，T13 §2）。授权节奏按 T13 §1.3：首次启动 provisional 静默授权，进入相关设置页再请求完整授权。
14. **唯一键**：`moment-reply-$id`。Oracle：MomentReplyWorker.kt:21。

### C. 记忆维护（ContextMaintenanceWorker 等价物）

1. **触发点全清单**（实现必须逐点覆盖）：
   | # | 触发点 | Oracle |
   |---|---|---|
   | ① | 启动重挂：每会话若 `context_rebuild_pending_${sessionId}` 或 `summary.state=="failed" && settings.summaryAutoRetryEnabled` → 入队 `dueAt = summary.nextRetryAt` | MainViewModel.kt:632-639（`settings.getBoolean("context_rebuild_pending_${session.id}")` :634） |
   | ② | 私聊消息编辑/上下文变更 → 入队（`dueAt=now`） | ChatViewModel.kt:2211 |
   | ③ | 群聊回复/变更 → 入队（group=true） | GroupChatViewModel.kt:479 |
   | ④ | 摘要生成失败 → 按退避时刻入队（私聊 `:638`；群聊 `:2802`） | ChatViewModel.kt:636-638、GroupChatViewModel.kt:2802 |
   | ⑤ | 前台内联维护（**非 worker，随聊天主路径**）：正常回复后 `generateShortTermSummary`（ChatViewModel.kt:2776）、失败重试（:2803-2808）、记忆抽取 `ingestPrivateMemoryV2`（:2721-2732） | ChatViewModel.kt:2776,2803-2808,2732 |
2. **唯一键与延迟**：`context-maintenance-$sessionId`，KEEP；`dueAt` 缺省 now。Oracle：ContextMaintenanceWorker.kt:15,21。
3. **执行守卫**：恢复中→retry；输入 session id 缺失→done；`getSession(id)==null`（会话已删）→done。Oracle：ContextMaintenanceWorker.kt:27-31。
4. **会话锁（幂等核心）**：`ContextMaintenanceLocks.forSession(id).tryLock()` 失败→retry（另一执行体在维护）；成功→`runContextMaintenanceLocked`，finally unlock。Oracle：ChatViewModel.kt:2216-2219（锁实现随共享层）；群聊同构 GroupChatViewModel.kt:485。
5. **维护内容（重建 pending 优先）**：若 `context_rebuild_pending_${id}` → 推进记忆纪元、重置抽取/摘要游标、`invalidateChangedMessage`、**保留旧摘要可见**（重建成功前不擦除上下文）、重建摘要；否则按滚动摘要状态处理（failed 且 `summaryAutoRetryEnabled` → 重跑摘要；failed 且未开自动重试 → 跳过）。Oracle：ChatViewModel.kt:2222-2267（重建 :2224-2246、状态处理 :2248-2262）。
6. **记忆抽取**：`ingestPrivateMemoryV2` 批量抽取（失败返回 false → retry）。Oracle：ChatViewModel.kt:2239、定义 :2622、前台路径 :2732。
7. **摘要退避（领域级）**：失败后 `retryAt = now + 60s << (failures-1)`（`coerceAtMost(5)`，即 1min 起、**上限 32min**），且仅 `summaryAutoRetryEnabled` 时入队。Oracle：ChatViewModel.kt:636-638（`summaryAutoRetryEnabled` 设置键 SettingsRepository.kt:173）。**口径修正（lead 2026-11-23，oracle 优先）**：原卡「上限 64min」为笔误——oracle `coerceAtMost(5)` 算术上封顶 32min，已由 lead 修正为 32min，实现以 oracle 为准。
8. **幂等与无取消**：无显式取消调用点；靠锁 + 唯一键 KEEP + 「会话已删→done」保证重复触发无害。Oracle：ContextMaintenanceWorker.kt:21,31、ChatViewModel.kt:2217；a7-预研 表行 2。
9. **group 分支**：payload 带 `group: Boolean`，执行时分派 `groupChatViewModel.runContextMaintenance` / `chatViewModel.runContextMaintenance`。Oracle：ContextMaintenanceWorker.kt:20,29,37-38。
10. **网络约束**：CONNECTED。Oracle：ContextMaintenanceWorker.kt:19。
11. **无通知**：记忆维护不产生任何系统通知。Oracle 依据：a7-预研 发现 2（Android 无专属通知）；iOS 不新增。

## 数据与迁移

- **reply_turns 表**（shared 层 `ReplyTurnRepository.kt:9-102` 全量随共享层移植，**本模块零新增迁移**）：字段 id/sessionId/surface/triggerKind/sourceMessageId/autoPlanToken/mode/status/attemptCount/nextAttemptAt/leaseToken/leaseUntil/responseMessageId/lastError/createdAt/updatedAt/completedAt（map :14-18）；状态机 `pending → running → succeeded | failed | cancelled`（claim :31-34、complete :41-44、release :50-52、fail :54-57、cancel :97-101）；查询 `pendingMomentTurnIds` :86、`resetFailedMomentTurn` :82、`retryableReplyTurnIds` ChatRepository.kt:346。
- **设置键（读，不新建）**：`summaryAutoRetryEnabled`（SettingsRepository.kt:173）、`commentContextCount`（:485）、`memoryV2Enabled` / `momentCommentMemoryGenerationEnabled`（MainViewModel.kt:2931 读）、`context_rebuild_pending_${sessionId}` 及配套 `context_rebuild_changed_/previous_summary_`（MainViewModel.kt:634、ChatViewModel.kt:2224-2239）。
- **备份/恢复表现**：恢复进行中任务一律 retry（MomentReplyWorker.kt:27、ContextMaintenanceWorker.kt:27）——恢复完成前不产生任何领域写入；恢复完成后启动 reconcile 自然补算（MainViewModel.kt:620-639 启动重挂路径）。挂起任务（pending/running turns + 重挂清单）为本地状态，不承诺跨设备同步（与 Android 一致：WorkManager 持久化同样仅本机）。
- **版本兼容**：本卡不引入 schema 变更；ReplyTurn 协议与状态机沿用 shared 层既有实现，禁止 iOS 侧 fork 一份状态机。

## 不可承诺清单与文案（直接引用 T13 实测结论，实现不得弱化）

**不可承诺**：① 「准点/定时精确到秒」——BGTask 机会型调度，模拟器连提交都被拒（code=1）；② 「后台长任务」——真机 BGAppRefresh ~30s 窗口，LLM 生成可能超窗，长生成只承诺前台完成；③ 「通知一定送达」——用户可关权限，provisional 为静默进通知中心；④ 「重启后准点恢复」——恢复语义 = 启动 reconcile + 前台立即补算。

**产品文案（设置页/FAQ，须 golden 化进测试）**：
- 定时回复开关副标题：「按你的节奏，尽快回复。」iOS 不承诺精确到秒的定时；RhodesLink 会在条件允许时尽快执行，App 打开时立即补上。
- 通知引导：「开启通知后，回复完成时第一时间提醒你。首次不会打扰你；你也可以在系统设置中把 RhodesLink 改为横幅提醒。」
- 兜底说明：「如果系统延后了定时任务，打开 RhodesLink 会自动补上，不会丢。」
- FAQ：「定时回复为什么不一定准点？」→ iOS 为省电采用机会式后台调度……App 在前台时按你的节奏执行；在后台时由系统择机执行；你打开 App 的瞬间立即补算，并在完成后发本地通知提醒你。
- **禁用措辞**：❌「准点回复」「X 秒后回复」「定时精确到秒」；✅「尽快回复」「回复后第一时间提醒你」「打开 App 立即补上」。

## 验收（必须可自动判定）

> 双层：shared 层行为用 KMP commonTest（fake clock，不依赖真机调度）；iOS 壳用 XCTest + Debug-only 双路径命令。全部拒绝人工「看起来对」。

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | **入队/重复入队矩阵**：enqueue 新键成功；同键二次 enqueue 不改 dueAt、不产生第二个任务；cancel 后同键可重新 enqueue；cancel 不存在的键无副作用 | shared 层单测（FakeDelayedTaskChannel 断言 schedule 调用参数） | `./gradlew :shared:iosSimulatorArm64Test --tests "*DelayedTaskLayer*"`（宿主侧 `:shared:test` 等价） |
| 2 | **到期执行**：dueAt≤now → 立即执行；dueAt=now+Δ → 虚拟时钟推进前不执行、推进 Δ+容忍窗口后执行恰好一次 | 单测 + fake clock | 同上 `--tests "*DelayedTaskLayerDueTest*"` |
| 3 | **启动 reconcile**：预置 pending turns（moment+context）与过期 failed 摘要状态 → 模拟启动 → 断言每条按 `nextAttemptAt ?: 0` 重入队；`context_rebuild_pending_` 置位会话与 `summaryAutoRetryEnabled=false` 的 failed 会话分流正确（后者不入队） | 单测：种 DB 状态（shared 内存 DB）→ 调 reconcile 入口 → 断言 channel 收到集合 | `--tests "*StartupReconcileTest*"`；对照 MainViewModel.kt:632-639 分支 |
| 4 | **重复触发幂等**：同一 `moment-reply-$id` 由两通道（前台 Timer + BGTask 模拟）并发到期 → 恰一条评论落库、另一执行体 retry（claim CAS 只放行一个） | 并发单测：两协程同 id 同时调 executeMomentReplyTask | `--tests "*MomentReplyIdempotencyTest*"`（断言 comments 表恰 1 行） |
| 5 | **退避序列 golden**：moment 失败序列断言 nextAttemptAt 增量 1/2/4/8 min（8min 为公式末项/纯函数覆盖；**第 4 次执行失败即 attemptCount=4 → status=failed 终态**，lead 修正原「第 5 次失败」表述）；摘要失败 retryAt=60s<<(n-1) **上限 32min**（oracle `coerceAtMost(5)`；lead 修正原 64min 笔误），`summaryAutoRetryEnabled=false` 时不入队 | 单测 golden 断言 | `--tests "*BackoffTest*"`；对照 MainViewModel.kt:2907-2912、ChatViewModel.kt:636 |
| 6 | **来源删除与终态守卫**：source/parent/moment/operator 删除 → turn 变 cancelled 且无评论生成；已 succeeded/cancelled turn 再触发 → done 无副作用；恢复标志置位 → retry 不执行 | 单测（含 restore 护栏 stub） | `--tests "*MomentReplyGuardTest*"`；对照 MainViewModel.kt:2877-2889 |
| 7 | **记忆维护锁**：同 session 两执行体并发 → tryLock 失败者 retry；会话已删除 → done；`context_rebuild_pending` 重建路径保留旧摘要（重建成功前旧摘要可读） | 单测（两协程 + 内存 DB） | `--tests "*ContextMaintenanceLockTest*"`；对照 ChatViewModel.kt:2216-2267 |
| 8 | **通知文案 golden**：B-13 通知标题/正文与 §不可承诺文案逐字相等；禁用词（「准点」「精确到秒」等）在通知/设置文案中出现即失败 | 文案常量 golden 测试 + 负向词表扫描 | `--tests "*CopyTextTest*"`；词表文件 `ios-port/specs/a7-delay-task-spec.md` §文案（测试引用同源常量） |
| 9 | **双路径命令（iOS 壳，Debug-only）**：① 前台路径：in-app 调试命令 `delay-layer run-now <key>` 直接到期执行并断言落库；② BGTask 兜底路径：Debug 构建复刻 T13 深触发链（`_initWithIdentifier:activity:`(nil) + `_runTask:registration:` + `_activity` shim）触发 launchHandler，断言执行体被调且 `setTaskCompleted` 被回执；③ `_simulateExpirationForTaskWithIdentifier:` 触发 expirationHandler 断言过期取消语义 | XCTest（深触发链代码仅 Debug/测试 target，release 构建编译期隔离——私有 API 不可上架，T13 handoff §2/§4） | `xcodebuild test -scheme <iOS壳> -configuration Debug`；命令通道参考 `ios-port/bg-task-probe/scripts/cmd.sh`（run-task / simulate-expiration） |
| 10 | **接线边界编译期检查**：`shared/domain/automation/` 编译单元不得 import BackgroundTasks / UserNotifications / WorkManager（KMP shared 无这些符号，XCTest 追加源集扫描断言） | 静态断言（grep 编译期依赖 + source-set 白名单） | `./gradlew :shared:compileKotlinIosSimulatorArm64` + 脚本 `grep -r "BGTask\|UNUserNotification\|WorkManager" shared/.../automation/ → 0 命中` |

- **金样**：本卡无网络/图像金样；文案 golden 见 #8。行为金样以 §行为契约逐条 file:line 为准（reviewer 对照 Android 源码复核）。
- **性能**：前台路径到期→执行启动延迟 P95 < 100ms（Timer 通道直呼，无队列漂移）；启动 reconcile 扫描 P95 < 300ms（内存 DB，pending 数 ≤ 100）。测量：单测计时断言（松阈值防抖动）。
- **边界用例清单**：空 payload / dueAt 为负（钳 0）/ 同键并发 enqueue / cancel 后马上 enqueue / 执行中超时（4min 缩短版注入）/ 执行中租约过期 / 执行中恢复标志翻转 / 网络断→挂起→恢复后到期 / 通知权限拒绝（provisional 与拒绝两种状态）/ 会话删除后到期触发。

## 风险与开放问题

**已知风险与处理**：
1. **模拟器无真实 BGTask 调度**（T13 实测 code=1）：真实 earliestBeginDate/系统时间窗/电量约束只能在真机验证。处理：验收 #9 用 Debug 深触发链替代；真机验证列入 iOS 壳波次遗留清单，本卡不阻塞。
2. **私有 API 深触发链不可上架**：处理：仅存在于 Debug/测试构建（编译期隔离），release 只保留公开 API（register/submit/expirationHandler/setTaskCompleted），T13 handoff §4。
3. **两通道并发同 turn**：处理：租约 claim 天然互斥（B-5），单测 #4 覆盖。
4. **LLM 生成 4min 超窗**（BGAppRefresh ~30s 窗口内几乎必然超时）：处理：前台主通道完成长生成，BGTask 路径超时走 retry/前台补算；不承诺后台完成生成（§不可承诺 ②）。
5. **与 A1 群聊自动互动的层共享顺序（lead 裁决，2026-11-23）**：实现顺序已定——**A1/群聊实现波次先落地通用 DelayedTaskLayer**（其验收契约 = 本卡 §A-12，接口冻结、不允许单方面改协议）；**本卡对应的 A7 实现波次在其后接入胶水**（MomentReply/ContextMaintenance 的执行体注册进层、启动 reconcile 挂接、B/C 组行为测试），并承接 §验收 #1-#3 矩阵对 A1 交付层的验证责任。A7 波次不自建调度机制、不复制层逻辑。

**触发条件与回退预案（整体移植排除条件，lead 已裁决，卡内留痕）**：
- 裁决依据（a7-预研 §4）：拆分 5.0–7.5 代理日 vs 整体 11.5–17.0，比值 43–65%（悲观口径 65%）< 80% 触发线；结构性原因：拆分必需的调度栈 = 一次性延迟任务原语（成本上限 ~2.5 代理日），两项保留功能的提示词/记忆管线是前台路径随主功能移植（0 增量），4 项弃项增量是领域逻辑非调度栈。
- **回退预案**：实现期（A1/A7 共享层落地时）实测「拆分所需改动面 ≥ 整体移植 80%」（如发现 DelayedTaskLayer 无法覆盖弃项所需语义、或触发点耦合远超预研 §2 清单），立即暂停实现、上报 lead 按裁决流程重议整体移植；弃 4 项（每日动态 DailyContentScheduler.kt:24-256、主动私聊 MainViewModel.kt:876-950,1216-1279,1265,1380、免打扰 DailyContentScheduler.kt:243-255、手动回复定时器 ManualReplyScheduler/Worker.kt）冻结不动，其触发点（MainViewModel.kt:706 每日计划补建、:1216-1279 主动私聊投递）仅作背景记录，不进实现。

**待 reviewer/lead 裁决的问题**：
1. B-13「完成补算发本地通知」的通知内容是否复用私聊回复通知样式（Android 有私聊回复通知既有路径 ChatViewModel.kt:2052-2059），还是独立模板——需产品确认文案归属。
2. 前台主通道到期执行时若 app 在后台（Timer 挂起），是否降级为「回到前台再补」+ 通知兜底，还是接受下次 BGTask 择机执行——两种都可自动判定，需定一种作 golden。
3. 验收 #9 的深触发链测试替身是否纳入 CI（涉及 LLDB/in-app 命令通道，T13 实测 5/5 稳定），还是仅本机开发用。
4. 记忆维护的 BGTask 兜底是否值得提交（a7-预研 §3 表：BGTask 仅加分项；前台内联 ChatViewModel.kt:2776 已覆盖主路径）——建议默认**不提交** BGProcessing 请求，仅走前台 + 启动 reconcile，减少一个不可测面。

## 评审结论

- reviewer：**通过（PASS-WITH-FIXES，0 P1，2026-11-23）**。P2 修正已落实：① `summaryAutoRetryEnabled` 行号修正为 SettingsRepository.kt:173（原误 169）；② 实现顺序裁决落地——通用 DelayedTaskLayer 由 A1/群聊实现波次先行，契约以本卡 §A-12 为冻结口径，A7 实现波次随后接胶水（MomentReply/ContextMaintenance 接入），并承接验收 #1-#3 矩阵对 A1 交付层的验证。
- lead：**批准（2026-11-23）**。裁决：拆分移植（保留动态定时回复 + 记忆维护）；A1/群聊波次先落地通用延迟任务层，A7 波次在其后接入；卡内待裁决 4 问由实现波次开工时经 lead 定稿。
- lead（T25 实现收尾裁决，2026-11-23，**oracle 优先于规格卡**）：① 摘要退避上限按 oracle `ChatViewModel.kt:637 coerceAtMost(5)` 修正为 **32min**（原卡 64min 为笔误）；② moment 退避终态按 oracle 实测行为：`attemptCount≥4` 时**第 4 次执行失败即 status=failed**（原验收 #5「第 5 次失败」表述已修正）。实现与 handoff 已同步。
