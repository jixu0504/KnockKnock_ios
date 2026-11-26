# 规格卡：A7 iOS 自动化接线（延迟任务宿主 / 前台 tick / 通知事件源）

> 任务：T56（task-56）｜实现代理：impl-a7-wiring｜状态：**已实现并验证（`--a7-glue-selftest` 22/22 PASS，RESULT PASS）**｜日期：2026-11-23
> 上游契约：`ios-port/specs/a7-delay-task-spec.md`（§A-12 冻结通道协议）、`ios-port/handoffs/impl-a7-glue.md`（T25 域层三执行体）、`ios-port/handoffs/a7-presearch.md`、`ios-port/specs/bg-task-notification-spec.md`（T13 实测：模拟器 BGTask 不可用 / provisional 通知可用）。
> 本卡只做**宿主接线**：不重写域层（MomentReplyTask / ContextMaintenanceTask / DelayedTaskLayer 零改动），不 import 新的后台调度框架进 commonMain。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | A7 iOS 自动化接线（IosDelayedTasksGlue / A7Glue） |
| 所属阶段 | Phase 6 收尾（A7 波次） |
| 范围映射 | A7 保留项：动态定时回复（MomentReplyWorker 等价物）+ 记忆维护（ContextMaintenanceWorker 等价物）；附带 A1 群聊自动互动计划（`AutoPlanChanged` → 层） |
| 对应共享实现 | `shared/src/commonMain/.../domain/automation/`（T23 层 + T25 三执行体，本卡零改动） |
| 新增/改动文件 | `shared/src/iosMain/.../iosbridge/A7Glue.kt`、`A7Channels.kt`、`A7Ports.kt`；`GroupChatBridge.kt`（+计划监听钩子/引擎访问器）；`IosAppGraph.kt`（+a7()/自检钩子）；`iosApp/App/{AppEnvironment.swift,RhodesLinkApp.swift,A7BackgroundRefresh.swift}`、`iosApp/Bridge/AppGraphClient.swift`、`iosApp/Config/Info.plist` |
| 不可改动 | A7 §A-12 通道协议、T25 执行体行为、commonMain 任何自动化语义；UI 功能页（T57 写域） |

## 输入 / 输出 / 边界

- **输入**
  1. 领域入队：`MomentReplyTask.reconcile/arm`、`ContextMaintenanceTask.reconcile/arm`（由 catch-up 或后续触发点调用）；
  2. 群计划变化：`GroupChatEngine` 的 `GroupEngineEvent.AutoPlanChanged`（bridge 钩子转回调）；
  3. 宿主生命周期：`start()` / `setForeground(bool)` / BGTask `catchUpNow()` / `setRecoveryActive(bool)` / `setActiveSessionId(id)`；
  4. 到期信号：前台 Timer（通道）到期、tick 兜底扫描、启动/回前台 catch-up、BGTask launchHandler。
- **输出**
  1. 执行体副作用（动态评论落库、记忆抽取/摘要、群自动轮次消息落库）；
  2. `NotificationEvent` → UNUserNotificationCenter 本地通知（按设置开关过滤/同会话去重/静音/深链 payload）；
  3. 诊断：NSLog `[RhodesLink][A7][*]`、`statusLine()`、`recentNotificationsCsv()`、`--a7-glue-selftest` 报告文件。
- **边界**
  - tick 周期默认 45s（30–60s 口径），只做「已到期挂起项补触发」，不做 DB 全量重扫（catch-up 负责重扫）；
  - 通道不信任准点：层执行时判定到期（A7 §A-3），通道早到由层重挂；
  - 后台不执行：`setForeground(false)` 暂停所有计时器并保留挂起表；回前台重排（过期立即触发）；
  - BGTask 在模拟器 submit 必败（T13 实测 Code=1）→ 记日志并降级前台补算；真机才由系统择机回调；
  - 通知授权：首次通知时才请求 provisional（静默），不阻塞、不在启动弹窗；
  - 群自动互动执行失败不叠加层 EXP 退避（计划状态机 `releaseClaimForRetry` + `AutoPlanChanged` 已负责重排）。

## 行为契约（逐条 + 实现落点）

| # | 契约 | 落点（`shared/src/iosMain/.../iosbridge/`） |
|---|---|---|
| G1 | 层装配：前台通道为唯一 primary；BGTask 不作为第二通道（避免双执行体在 iOS 后台不可测面），兜底改由「系统回调 → catchUpNow()」等价触发 | `A7Glue.kt:86-130`（构造）、`A7Channels.kt:41`（`A7ForegroundChannel`） |
| G2 | 前台 Timer 通道实现冻结协议 `DelayedTaskChannel`：唯一键 schedule/cancel/pendingKeys/onDue；回调前先移出通道挂起表（层重挂时再入表） | `A7Channels.kt:41-160` |
| G3 | 前后台语义：后台取消全部计时器但保留挂起表；回前台重排（`delay(max(0,due-now))`），过期项立即触发 | `A7Channels.kt:82-93`、`A7Glue.kt:177-195` |
| G4 | 启动 catch-up：flushOverdue → MomentReply reconcile（pending turn 按 `nextAttemptAt`，层 24h 窗口/钳 now）→ ContextMaintenance reconcile（rebuild pending 立即 / 摘要失败按退避，`summaryAutoRetryEnabled=false` 不入队）→ 群计划重建 → 再 flushOverdue | `A7Glue.kt:238-267`（`runCatchUp`） |
| G5 | 前台 tick 循环：默认 45s；`flushOverdue` 兜底触发并计数；后台自动暂停；`tickOnce()`/`debugTick()` 供自检 | `A7Glue.kt:270-322` |
| G6 | 恢复护栏：`recoveryActive` 时层不执行直接 retry（oracle BackupRestoreMaintenance.active） | `A7Glue.kt:197-201`、执行体构造 `isRecoveryActive` |
| G7 | 群自动互动：bridge 暴露 `AutoPlanChanged` 监听 + `domainEngine()`；glue 先 `cancel(key)` 再按新 plan 入队（REPLACE）；到期调 `runScheduledAutoTurn(groupId, token, planRevision)`；成功由计划机排下一轮、失败不叠 EXP | `GroupChatBridge.kt:104-115,409-414`、`A7Glue.kt:375-425` |
| G8 | 通知事件源：订阅 `MomentReplyEvent.NotificationRequested`（B-13）+ catch-up 完成事件；统一走 router | `A7Glue.kt:330-368`、`A7Channels.kt:218-292` |
| G9 | 设置开关过滤：`notify_moments`（moment） / `notify_group_reply`（group:）/ `notify_private_reply`（session:）/ `notify_diary`（diary:）；catch-up 不挂会话开关 | `A7Channels.kt:284-292` |
| G10 | 去重/静音：同幂等键窗口内抑制 `duplicate`；同会话窗口内抑制 `session_muted`；用户在 App 内正查看该会话抑制 `session_active`（`setActiveSessionId`） | `A7Channels.kt:246-282` |
| G11 | 深链 payload：`rhodes://chat/<sessionId>`（moment 事件 sessionId=`moment:<momentId>`，与 T50 动态评论会话别名一致；T57 路由可按前缀跳动态详情） | `A7Channels.kt:247`、`A7Channels.kt:256` |
| G12 | 授权时机：首次通知前查询权限；`notDetermined` → provisional 静默授权后调度；已授权直接调度；被拒调用仍记录（系统不弹） | `A7Channels.kt:167-192`（`IosA7NotificationSender`） |
| G13 | BGTask 可选兜底：Info.plist `BGTaskSchedulerPermittedIdentifiers` + `UIBackgroundModes(fetch)`；`BGAppRefreshTask` register/launchHandler/expirationHandler/setTaskCompleted；submit 失败只记日志 | `iosApp/RhodesLink/App/A7BackgroundRefresh.swift`、`iosApp/Config/Info.plist:36-43` |
| G14 | Swift 接线：启动注册 BGTask；AppEnvironment.start → `a7.start()`；scenePhase active/background → `setA7Foreground` + schedule；AppGraphClient 暴露 start/foreground/catchUp/setActiveSession/setRecoveryActive/status/recentNotifications | `iosApp/RhodesLink/RhodesLinkApp.swift:17-44`、`AppEnvironment.swift:61-62`、`AppGraphClient.swift:960-996` |
| G15 | 自检钩子：`--a7-glue-selftest` → 独立 testScope/testLayer + 确定性 AI + 记录型通知 sender，夹具写真实 DB、断言后清理群自动开关；报告写 `Application Support/t56-a7-glue-selftest.log` | `IosAppGraph.kt:158-168`、`A7Glue.kt:539-923` |

## 验收（全部已实测）

| # | 验收项 | 判定 | 结果 |
|---|---|---|---|
| 1 | 启动 catch-up 执行到期动态回复：pending turn（dueAt 过去）→ `reply_turns.status=succeeded` + `moment_comments` 新增 + `memories` 写 + 通知记录 | 自检 `moment.*` 6 项 | PASS |
| 2 | 群聊自动互动：预置到期 plan → 执行 `runScheduledAutoTurn` → `chat_messages(ai_json)` 落库 + `reply_turns(group_auto)=succeeded` | 自检 `group.auto.*`/`chat_messages.evidence` 4 项 | PASS |
| 3 | tick 补触发：后台暂停期间入队（无 Timer）→ 前台 tick 原语恰好补触发 1 次 → 记忆维护重建完成（`rebuild_pending=false` + 短期摘要落库） | 自检 `tick.*`/`context.*` 5 项 | PASS |
| 4 | 通知路由矩阵：发送 / 幂等去重 / 同会话静音 / 活跃会话静音 / 开关关闭 | 自检 `router.*` 5 项 | PASS |
| 5 | 层语义不回归：KEEP 二次入队不改 dueAt、cancel 后销号 | 自检 `layer.*` 2 项 | PASS |
| 6 | 编译：shared framework link + xcodebuild（generic iOS Simulator，CODE_SIGNING_ALLOWED=NO） | BUILD SUCCESSFUL / BUILD SUCCEEDED | PASS |
| 7 | 模拟器实跑：iPhone 17（A63CD17A…），selftest RESULT PASS，生产启动 catch-up 日志正常（BGTask submit 失败自动降级） | evidence/t56-* | PASS |

**证据**：`ios-port/handoffs/evidence/t56-a7-glue-selftest.log`（22 PASS/0 FAIL）、`t56-db-evidence.log`（sqlite 直查）、`t56-console.log`、`t56-production-start.log`、`t56-selftest-result.png`、`t56-app-chat-list.png`。

## 平台差异 / 不可承诺（继承 A7 §不可承诺 + T13 实测）

1. 不承诺准点：BGTask 机会型调度，模拟器连 submit 都不可用；可承诺「打开 App 立即补算 + 前台按节奏尽快执行」。
2. 不承诺后台长任务：真机 BGAppRefresh ~30s 窗口；长生成走前台主通道，后台仅触发 catch-up。
3. 不承诺通知必达：权限可被关闭；provisional 为静默进通知中心。
4. BGTask 兜底路径为**可选**：模拟器路径实测降级为前台补算；真机语义由注册/launchHandler/expirationHandler 保证（T13 深触发链 5/5 实测）。
5. moment 通知深链按约定用 `rhodes://chat/moment:<momentId>`（T57 可加前缀路由）；未改 UI 路由表（T57 写域）。

## 限制与后续

- 群自动互动失败时依赖计划状态机的 `AutoPlanChanged` 重排；若计划机未发出事件（极端异常）依赖启动 catch-up 重建。
- `MomentReplyTask` 的设置快照在 glue 构造时读取（T25 域层签名如此）；修改「评论字数/记忆开关」后新值在下次 App 启动生效（如需即时生效需 T25 增刷新入口，本卡未改域层）。
- UI 侧尚未把当前会话 id 推给 glue（`setActiveSessionId` 已暴露，T57 接 ChatView 即可启用同会话静音）。
- 自检会在真实 DB 创建夹具行（T56 群/动态/记忆），已在取证后清理；如需重复自检可接受新增夹具，或后续加 `--a7-glue-fixture-clean` 钩子。

## 评审状态

- 待 reviewer 复核（本卡为接线实现 + 自检证据；域层行为契约仍以 `a7-delay-task-spec.md` 为准）。
