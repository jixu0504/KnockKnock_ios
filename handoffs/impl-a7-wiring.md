# T56 实现 handoff：A7 自动化接线（延迟任务宿主 / 前台 tick / 通知事件源）

> 代理：impl-a7-wiring ｜ 任务：task-56 ｜ 状态：**实现完成并验证：`--a7-glue-selftest` 22/22 PASS（RESULT PASS）；shared framework link + xcodebuild BUILD SUCCEEDED；iPhone 17 模拟器实跑取证通过（2026-11-23）**
> 上游：`ios-port/specs/a7-delay-task-spec.md`（§A-12 冻结通道契约）、`ios-port/handoffs/impl-a7-glue.md`（T25 三执行体）、`ios-port/handoffs/a7-presearch.md`、`ios-port/specs/bg-task-notification-spec.md`（T13 实测）。
> 本卡规格：`ios-port/specs/a7-ios-glue-spec.md`。**git 未提交**（纪律：lead 统一提交）。task-56 已 claim→complete。

## 1. 交付摘要

iOS 宿主侧 A7 自动化接线（零改 commonMain 域层、零改 UI 功能页）：

1. **A7Glue**（`shared/src/iosMain/.../iosbridge/A7Glue.kt`，923 行）：同一个 `DelayedTaskLayer` 注册三执行体——
   `MomentReplyTask`（`moment-reply-<turnId>`）、`ContextMaintenanceTask`（`context-maintenance-<sessionId>`）、
   群自动互动（`group-auto:<groupId>`）；启动/回前台 **catch-up**（reconcile 扫描 + 过期任务立即补算）、
   前台 **tick 循环**（默认 45s，后台暂停、回前台恢复）、BGTask 兜底入口 `catchUpNow()`。
2. **A7Channels**（294 行）：`A7ForegroundChannel`（`DelayedTaskChannel` 的 iOS Timer 实现：唯一键、
   前台/后台暂停恢复、`flushOverdue` tick 原语）；`A7NotificationRouter`（设置开关过滤 + 同幂等键去重 +
   同会话静音 + 活跃会话静音 + `rhodes://chat/<sessionId>` 深链）；`IosA7NotificationSender`（首次通知时
   provisional 静默授权）/ `A7RecordingNotificationSender`（自检记录，不弹窗）。
3. **A7Ports**（391 行）：`MomentReplyPort` / `ContextMaintenancePort` 生产适配（复用 ReplyTurnRepository 租约
   CAS、`MomentRepository.insertCommentForReplyTurn` 同事务语义，不 fork 状态机）；真模型摘要生成
   （AIService，oracle ChatViewModel.kt:520-660 精简同构）、MemoryV2 抽取/尾部维护、moment 模型通道
   （真实供应商优先，未配置走确定性演示回退）、记忆/知识库依赖装配。
4. **桥与启动接线**：`GroupChatBridge` 新增内部 `AutoPlanChanged` 监听钩子 + `domainEngine()` 访问器；
   `IosAppGraph` 新增 `a7()`（懒构建）与 `--a7-glue-selftest` 启动钩子；Swift：`AppGraphClient` 6 个
   A7 方法、`AppEnvironment.start()` 启动 glue、`RhodesLinkApp` scenePhase 前后台切换 +
   `A7BackgroundRefresh`（BGAppRefreshTask register/launch/expiration/submit）+ Info.plist 声明。

## 2. 触发链（从生命周期到副作用）

```
App 启动
 ├─ RhodesLinkApp.init → A7BackgroundRefresh.register()（BGTask id 注册；模拟器 submit 失败→降级）
 ├─ AppEnvironment.start → bridge.startA7Glue() → A7Glue.start()
 │    ├─ startTickLoop()（45s；仅前台运行）
 │    └─ workScope.launch { runCatchUp("startup") }
 │         ├─ channel.flushOverdue(now)            // 后台暂停期/漂移的到期项兜底
 │         ├─ momentTask.reconcile(layer)          // pendingMomentTurnIds → dueAt=nextAttemptAt（层 24h 窗口）
 │         ├─ contextTask.reconcile(layer, sessions)// rebuild pending 立即 / 摘要 failed 且自动重试按退避
 │         ├─ groupEngine.refreshAutoGroupChats()  // 计划重建 → AutoPlanChanged → syncAutoPlan → 层入队
 │         └─ channel.flushOverdue(now)            // 过期即补算（「打开 App 立即补上」）
 ├─ scenePhase .active → setForeground(true) → catch-up + tick 恢复
 └─ scenePhase .background → setForeground(false) → 计时器暂停（挂起表保留）
        ↓ 到期（前台 Timer / tick / BGTask catchUpNow）
 DelayedTaskLayer.onChannelDue（真实到期判定 + 双通道/重入去重）
        ↓ handler（最长前缀）
 MomentReplyTask.execute → claim 租约 → 来源守卫 → moment_comment 管线 → 租约内落库 → 通知事件
        ↓ events(NotificationRequested)
 A7NotificationRouter.onEvent(event, "moment:<momentId>")
        ├─ notify_moments 关闭 → switch_off（记录，不发）
        ├─ 同 key 窗口内 → duplicate；同会话窗口内 → session_muted；正查看该会话 → session_active
        └─ 发送 → payload+deeplink(rhodes://chat/moment:<id>) → UNUserNotificationCenter
 ContextMaintenanceTask.execute → 会话锁 → 重建（纪元/游标/invalidate/保留旧摘要/分批 ingest/摘要）
        → rebuild_pending=false + memories/rolling_summary_status 落库
 group-auto 到期 → GroupChatEngine.runScheduledAutoTurn(token, revision) → ai_json 消息落库 + 下一轮计划
```

## 3. 实现清单（file:line）

| 文件 | 行 | 内容 |
|---|---|---|
| `shared/src/iosMain/.../iosbridge/A7Glue.kt` | 53 | `A7GlueConfig`（tick 45s / catch-up settle 60s / quiet 1.2s / group auto 4min） |
| | 86-160 | `A7Glue` 构造：channel+layer、三端口/三执行体、通知 router、groupChat 监听、handler 注册（`tracked` 计数） |
| | 163-235 | `start()` / `setForeground()` / `setRecoveryActive()` / `setActiveSessionId()` / `catchUpNow()` |
| | 238-267 | `runCatchUp(reason)`：flush → moment reconcile → context reconcile → 群计划重建 → flush |
| | 269-322 | `catchUpAndSettle()`（BGTask 结算：等执行体静默窗口）+ `startTickLoop/stopTickLoop/tickOnce/debugTick` |
| | 324-368 | 执行体计数包装、Moment/Context 语义事件日志、catch-up 完成通知（DELAYED_TASK_CATCH_UP） |
| | 370-425 | `syncAutoPlanOn()`（先 cancel 再 enqueue = REPLACE）、`executeGroupAuto()`（runScheduledAutoTurn + 4min 上限） |
| | 427-455 | `statusLine()` / `recentNotificationsCsv()` / `close()` |
| | 457-537 | moment 执行体装配：设置快照、7 路可选上下文（记忆 buildPrivateMemoryContext / 知识库 recall / 资料边界等） |
| | 539-923 | `debugRunSelfTest` + `runGlueSelfTest`（22 断言矩阵，见 §4） |
| `shared/src/iosMain/.../iosbridge/A7Channels.kt` | 41-160 | `A7ForegroundChannel`：唯一键挂起表、`schedule/cancel/pendingKeys/onDue`、`setForeground` 暂停/恢复、`flushOverdue`、`plantWithoutTimer`（自检） |
| | 162-215 | `A7NotificationSender` / `IosA7NotificationSender`（首次通知 provisional）/ `A7RecordingNotificationSender` |
| | 218-294 | `A7NotificationRouter`：开关映射（notify_* 四键）、duplicate/session_muted/session_active、records、深链 |
| `shared/src/iosMain/.../iosbridge/A7Ports.kt` | 50-125 | `RepositoryMomentReplyPort`（13 方法：claim/create/getComment/getMoment/getOperator/recentComments/insertCommentForReplyTurn/release/fail/cancel/refreshCommentCount/pendingMomentTurnIds/momentTurns/resetFailed） |
| | 128-168 | `RepositoryContextMaintenancePort`（getSession/shortTermSummary/messagesAfterCursor/invalidateChangedMessage/generateShortTermSummary/ingestMemory/retryPendingMemorySources） |
| | 170-311 | `A7RealSummaryGenerator`：oracle 摘要管线等价（游标/保留轮数/四段 JSON/替换短期记忆/失败退避写 rolling_summary_status 同键） |
| | 313-345 | `A7RealMemoryIngestor`（ingestPrivateChat / ingestGroupChat）+ `A7RealMemoryMaintenance`（retryPendingSources） |
| | 347-391 | `A7MomentStreamingClient`（真实供应商优先/确定性演示回退）、`A7MemoryWiring`（AIService+向量+MemoryV2+知识库） |
| `shared/src/iosMain/.../iosbridge/GroupChatBridge.kt` | 104-115 | `autoPlanListener`/`setAutoPlanListener`/`domainEngine()`（T56 钩子，默认 no-op） |
| | 409-414 | `GroupEngineEvent.AutoPlanChanged` → 读 plan → 回调（cancelled → null） |
| `shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 132-151 | 懒构建 `a7Glue`（A7MemoryWiring + A7MomentStreamingClient + 真摘要/抽取/维护 + IosA7NotificationSender）；`a7()` |
| | 158-168 | `maybeRunA7GlueSelfTest()`（`--a7-glue-selftest`；未带参数零开销） |
| | close() | 释放 a7 tick 协程 |
| `iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 960-996 | `startA7Glue/setA7Foreground/a7CatchUp/setA7ActiveSession/setA7RecoveryActive/a7StatusLine/a7RecentNotifications` |
| `iosApp/RhodesLink/App/AppEnvironment.swift` | 61-62 | start 后 `bridge.startA7Glue()` + 自检日志记录状态行 |
| `iosApp/RhodesLink/RhodesLinkApp.swift` | 17-44 | `init` 注册 BGTask；scenePhase active/background → `setA7Foreground` + `A7BackgroundRefresh.schedule()` |
| `iosApp/RhodesLink/App/A7BackgroundRefresh.swift` | 全文件 | BGAppRefreshTask 注册/launchHandler（调 `a7CatchUp`）/expirationHandler（取消）/submit（失败记日志降级） |
| `iosApp/Config/Info.plist` | 36-43 | `BGTaskSchedulerPermittedIdentifiers` + `UIBackgroundModes(fetch)` |

> 本卡写域内改动仅上述文件；**未触碰** commonMain 域层、任何 UI 功能页（T57 写域）、app/。

## 4. 验证证据

### 4.1 `--a7-glue-selftest`：22 PASS / 0 FAIL（RESULT PASS）

命令：
```bash
source ios-port/toolchain/env.sh
cd RhodesLink && ./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64        # BUILD SUCCESSFUL
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t56" CODE_SIGNING_ALLOWED=NO  # BUILD SUCCEEDED
xcrun simctl install A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA .../RhodesLink.app
xcrun simctl launch  A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA com.rhodeslink.app --a7-glue-selftest
```
报告：`evidence/t56-a7-glue-selftest.log`（清单位置见 §4.2）。

| 组 | 断言 | 结果 |
|---|---|---|
| 夹具 | fixture.operators count=147 | PASS |
| 动态回复 catch-up | moment.pending.scan（pending=1）/ moment.turn.succeeded（succeeded）/ moment.comment.inserted（commentId=7）/ moment.memory.ingested / notify.moment.recorded / notify.deepLink=`rhodes://chat/moment:6` | 6 PASS |
| 群自动互动 | group.auto.messages count=1 / chat_messages.evidence types=[ai_json] / group.auto.turn status=succeeded | 3 PASS |
| tick 补触发 + 记忆维护 | tick.queued / tick.paused（foreground=false）/ tick.fired=1 / context.rebuild.cleared（pending=false）/ context.memory.ingested（短期摘要落库） | 5 PASS |
| 通知路由 | router.sent / dedupe(duplicate) / mute.sameSession(session_muted) / mute.activeSession(session_active) / switch.off(switch_off) | 5 PASS |
| 层语义 | layer.keep（first=true second=false）/ layer.cancel（keys=[]） | 2 PASS |

### 4.2 DB 直查证据（模拟器容器 `Application Support/rhodes.db`，自检后取证）

`evidence/t56-db-evidence.log` 摘录：
```
reply_turns: moment-0d1d…da|succeeded|1|moment_comment|moment_comment
moment_comments: 7|6|M3|看你说得这么香，我下次也想一起去尝尝看。|1795420454366
                 6|6|博士|看起来好好吃！下次带我一个。|1795420452273
chat_messages:   53|group_1a20765fe4bd452|ai_json|[{"speaker":"Misery","message":"（演示模式，本地群聊桩）…"}]
memories:        group_1a20765fe4bd452|…|SHORT_TERM|T56 自检滚动摘要：博士与T56 自动化验证群约定明天去食堂。
                 moment:6|mon3tr|SHORT_TERM|T56 自检：M3回复了动态评论「…」
reply_turns:     auto:group_1a20765fe4bd452:t56-…|succeeded|group_auto|group
```

### 4.3 其他证据

| 文件 | 内容 |
|---|---|
| `evidence/t56-console.log` | 自检全程 NSLog（`[RhodesLink][A7][*]`，含 catchup/group.auto/tick/notify 明细） |
| `evidence/t56-production-start.log` | 生产启动（无参数）：`start: started=true … pending=0` + `foreground=true 恢复 tick + 回前台补算` + `[BG] submit failed（降级前台补算）err=BGTaskScheduler is not available on this platform.` |
| `evidence/t56-selftest-result.png` | 自检后 App 实机截图（聊天列表 + T56 夹具群，取证后已清理干净） |
| `evidence/t56-app-chat-list.png` | 清理夹具后的正常启动截图（4 个会话，无测试群） |

### 4.4 取证后清理（模拟器数据，非代码）

自检夹具取证完成后已用 sqlite 清理（避免污染用户 UI）：
删除 `chat_sessions/chat_messages/reply_turns`（T56 验证群 ×2 及 auto turn）、`moments/moment_comments`（id 5/6）、
`memories`（content LIKE '%T56 自检%'）；群自动开关/完成标记在自检结尾已关闭。复查：groups_after=0、t56_memories_after=0、t56_moments_after=0。

## 5. 环境备注（可复现）

1. 构建：`source ios-port/toolchain/env.sh`；gradle/xcodebuild 前 `pgrep -fl "gradle|xcodebuild"` 排队；一律 `--no-daemon`。
2. 模拟器：iPhone 17 `A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA`；全程 `xcrun simctl`（dsh-ios 插件已禁用）。
3. **iCloud/搬家故障**：本卡收尾阶段工作区发生目录搬迁（后台进行），出现 `Stale NFS file handle`（gradlew / gradle-wrapper.properties / JDK `lib/server` 短暂缺失）与构建偶发等待；按 lead 指示未反复 kill，待文件恢复后构建与自检均成功。期间未保存独立 xcodebuild 日志文件（构建输出在会话记录中，BUILD SUCCEEDED 已确认）；framework link 日志亦因同一故障未落盘，命令与结果见 §4.1。
4. 自检隔离：独立 testScope/testLayer/确定性 AI/记录型通知 sender；仅临时替换 GroupChatBridge 计划监听（finally 恢复）；群自动互动强制走演示 AI 时临时把 provider 置 `custom`+空端点，结束后恢复原 provider/customUrl；通知偏好键在测试后恢复原值。

## 6. 限制 / 后续

1. **BGTask 真机路径未验证**：模拟器 submit 恒失败（T13 结论）；launchHandler/expirationHandler 逻辑就位，真机可用性列入 iOS 壳遗留项（不阻塞）。
2. **MomentReplyTask 设置快照**：T25 域层构造取快照，修改「评论字数/记忆开关」需重启 App 生效（未改域层接口）。
3. **同会话通知静音**：glue 已提供 `setActiveSessionId`，但 UI（ChatView）尚未接入（T57 写域）；未接入时仅靠 60s 窗口去重。
4. **moment 通知深链**：按约定 `rhodes://chat/moment:<momentId>`；T57 路由可加 `moment:` 前缀分支跳动态详情。
5. **自检夹具**：每次运行会在真实 DB 新增夹具行（群/动态/记忆），本次已清理；后续可加 `--a7-glue-fixture-clean` 钩子做自动清理。
6. **catch-up 补算通知**：只在 `catchUpAndSettle`（BGTask/显式 catchUpNow）后按执行数发一条「补算完成」；启动 catch-up 不打扰用户（避免每次冷启动都弹）。
7. **群自动互动失败重试**：依赖计划状态机 `releaseClaimForRetry` → `AutoPlanChanged` 重排；层对该键不叠 EXP 退避（避免双退避）。

## 7. 写域与纪律

- 写域：`RhodesLink/shared/src/iosMain/**`（新增 A7Glue/A7Channels/A7Ports + GroupChatBridge/IosAppGraph 最小 patch）、
  `RhodesLink/iosApp/App/**`、`iosApp/Bridge/AppGraphClient.swift`（追加方法）、`iosApp/Config/Info.plist`；
  `ios-port/{specs,handoffs,handoffs/evidence}`。
- 未改：commonMain 域层、UI 功能页（T57）、app/、构建配置。`git status` 有本卡改动但**未提交**（lead 统一提交）。
