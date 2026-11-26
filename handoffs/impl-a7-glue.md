# T25 实现 handoff：A7 胶水（MomentReply + ContextMaintenance 接入 DelayedTaskLayer）

> 代理：impl-a7-glue（T25 / task-25）。规格卡：`ios-port/specs/a7-delay-task-spec.md`（PASS-WITH-FIXES → lead 批准 2026-11-23）。
> 前置：T23 `shared/domain/automation/DelayedTaskLayer.kt`（§A-12 冻结契约，本卡零改其协议）、T19 ChatEngine/TurnStateMachine、T24 记忆 V2 管线。
> 状态：实现完成，JVM / iOS 双路径验证见 §3。git 未提交（lead 统一提交）。
> 写域确认：仅新增 `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/automation/`（3 文件）与
> `shared/src/commonTest/kotlin/.../domain/automation/`（9 文件）；**未触碰** `app/`、其他 shared 包、`shared/build.gradle.kts`、既有 `DelayedTaskLayer.kt`。
> 工作树中 `M app/...` 条目属并行波次，与本卡无关。

## 1. 实现清单（file:line 相对 `RhodesLink/shared/src/`）

### 1.1 `commonMain/.../domain/automation/NotificationEvent.kt`（182 行，新增）

| 内容 | 位置 | 说明 |
|---|---|---|
| `NotificationEvent` / `NotificationEventSink` / `NotificationCategory` | :10-36 | 通知**事件数据**（域层不发送、零平台 import）；`id` 为幂等键（宿主用作 UNNotificationRequest identifier） |
| `NotificationCopy` | :45-80 | 「XX 回复了你的评论」标题模板、正文 120 字截断去换行（oracle RhodesNotificationCenter content.take(120)）、事件工厂 `momentReplyCompleted` |
| `A7ProductCopy` | :91-146 | T13 §2/§3 产品文案常量逐字引用：定时开关副标题/通知引导/兜底说明/FAQ/三条可承诺口径（「打开 App 立即补算」「完成即通知」「尽力而为定时」） |
| `A7CopyGuard` | :148-182 | 负向词表扫描（「准点」「精确到秒」…），**否定式免责声明放行**（被批准文案含「不承诺精确到秒」；规则：命中前 12 字内有否定词则放行） |

### 1.2 `commonMain/.../domain/automation/MomentReplyTask.kt`（862 行，新增）

| 内容 | 位置 | oracle 落点 |
|---|---|---|
| `MomentReplyKeys` | :50-59 | 唯一键 `moment-reply-$id`（MomentReplyWorker.kt:21） |
| `MomentReplyContract` | :62-115 | 常量全集：surface/trigger/mode、租约 10min、4min 总超时、500ms 评论上下文、45s 模型、2 次内容生成、`attemptCount>=4` 终态、退避 1/2/4/8min、随机 10–30s；`buildTurnId`（SHA-256 identity，:105）；`backoffDelayMs`（:101） |
| `MomentReplySettings` | :124-136 | 设置快照（commentMin/MaxChars、commentContextCount、memoryV2Enabled、momentCommentMemoryGenerationEnabled） |
| `MomentReplyConfig` | :138-154 | 可压缩时间常数（测试注入） |
| `MomentReplyPort` | :156-202 | 持久化端口（claim CAS / 租约内落库同事务 / release/fail/cancel/reset/pending 查询） |
| `MomentCommentContextProviders` | :208-223 | 可选上下文端口（记忆/知识库/社交/记忆风格/来源规则/用户资料），全部失败降级 |
| `MomentCommentPromptContext` | :225-254 | 管线输入快照 |
| `MomentCommentPromptAssembler` | :256-345 | moment_comment 提示词组装：`PromptTemplates.get("moment_comment")` → 30+ 变量替换（:297-330）→ `PromptRender.buildCachePromptLayers` + 自然运行时上下文 + 资料边界（`DATA_BOUNDARY` :342）+【本轮任务】（oracle MainViewModel.kt:535-556/:2978-3021） |
| `PlainCommentContentNormalizer` | :347-401 | 模型输出清洗逐字迁移（代码栅栏/JSON content/引号/「以下是评论」前缀/拒绝 AI 免责声明；oracle PlainGeneratedContentNormalizer.kt） |
| `MomentReplyPayloadCodec` / `JsonMomentReplyPayloadCodec` | :407-420 | `autoPlanToken` JSON 编解码（model.MomentReplyTask） |
| `MomentReplyEvent` / `MomentReplyDeferReason` | :422-467 | 语义事件（ClaimDenied/SourceRemoved/ReplyStored/MemoryIngestFailed/Deferred/TaskFailed/NotificationRequested/Reconciled） |
| `MomentReplyTask` | :481-862 | 执行体与接线：`register`（:509）/ `buildTurn`（:519，立即回 vs 随机 10–30s）/ `createAndArm`（:553）/ `arm`（:564，层钳 0）/ `armAll` / `reconcile`（:580，启动重挂）/ `retryFailed`（:597，UI 手动重试）/ `execute`（:609）/ `executeTurn`（:617-670：恢复→retry、网络→retry、终态/异面→done、claim→retry、来源守卫→cancel+done、4min+同 moment 串行）/ `runMomentCommentPipeline`（:672-800：上下文→prompt→fake AI→清洗→租约内落库→记忆→计数→通知事件）/ `generateValidComment`（:802）/ `handleFailure`（:832：≥4 fail，否则 release 1/2/4 min） |

### 1.3 `commonMain/.../domain/automation/ContextMaintenanceTask.kt`（515 行，新增）

| 内容 | 位置 | oracle 落点 |
|---|---|---|
| `ContextMaintenanceKeys` | :29-47 | 唯一键 `context-maintenance-$sessionId` + 既有设置键（rebuild_pending/changed/previous_summary） |
| `ContextMaintenanceLocks` | :55-59 | 会话锁（`MutableMap + platformSynchronized`，等价 Android ConcurrentHashMap；T23 无该原语，新建） |
| `ContextMaintenancePolicy` | :62-98 | 摘要退避 `60s<<(failures-1)` 上限 **32min**（`SUMMARY_RETRY_MAX_SHIFT=5`，oracle `coerceAtMost(5)`，lead 2026-11-23 裁决 oracle 优先）；启动分流/自动重试/退避窗口纯函数 |
| `ContextMaintenanceConfig` | :100-116 | 抽取批 30、最多 6 批、排除 system/send_failed/gift_reply_failed、pending 来源重试上限 20 |
| `ContextMaintenancePort` | :118-139 | 持久化端口（会话/摘要/游标分页/invalidate/摘要生成/记忆抽取/pending 来源重试） |
| `ContextMaintenanceEvent` / `ContextMaintenanceDeferReason` | :141-187 | 语义事件（LockHeld/RebuildStarted/OldSummaryPreserved/SummaryGenerated/MemoryIngested/PendingSourcesRetried/SummaryRetryScheduled/Deferred/Skipped/Completed） |
| `ContextMaintenanceTask` | :190-515 | 5 类触发点入口 + 执行体：`register`（:204）、① `startupReconcileRecords`（:211，分流矩阵）/`reconcile`（:237）、② `onPrivateContextChanged`（:243，标记 pending + 保留旧摘要 + dueAt=now）、③ `onGroupContextChanged`（:259，group=true）、④ `onSummaryFailure`（:284，写 failed 状态 + 退避 dueAt，仅自动重试开启时入队）、⑤ `onForegroundReplyCompleted`（:266）/`maintainNow`（:274）；`execute`（:325）/`runSession`（:334：恢复/网络守卫→retry、会话已删→done、会话锁 tryLock 失败→retry）/`runLocked`（:360：重建分支→`runRebuild` :405：纪元推进/游标重置/invalidate/保留旧摘要/批量抽取/滚动摘要/清 pending；非重建分支→failed+自动重试到点重跑；尾部 `retryPendingSources`） |

### 1.4 与冻结契约（§A-12）的接线

- 层负责：唯一键、延迟钳 0、到期真实判定、EXP 退避、双通道去重、启动 reconcile、恢复护栏、cancel 语义 → 本卡**不复制**层逻辑；
- 本卡只实现执行体三态（`DelayedTaskExecutor`）+ 领域逻辑（租约 claim、来源守卫、管线、领域级退避、通知事件）；
- `registerHandler("moment-reply-", …)` / `registerHandler("context-maintenance-", …)` 为唯二接线点；
- 层不 import 平台框架，本卡同样零平台依赖（验收 #10：`grep -r "BGTask\|UNUserNotification\|WorkManager" commonMain/.../automation/ → 0 命中`，见 §3）。

## 2. 与规格卡契约的落点

| 契约 | 落点 |
|---|---|
| §A-1/2/3/4 唯一键·钳 0·执行时到期·EXP 1min | 复用 T23 `DelayedTaskLayer`（本卡不重写）；`arm`/`reconcile` 传入领域 dueAt |
| §A-5 网络约束语义事件化 | `MomentReplyTask.isNetworkAvailable` / `ContextMaintenanceTask.isNetworkAvailable`：不可用 → 不执行、RETRY + `Deferred(NETWORK_UNAVAILABLE)`，域层不碰平台网络 API |
| §A-6/11 三态与取消 | `DelayedTaskOutcome` 映射；`requiresNetwork` 声明；取消走层 `cancel(key)`（不中断 running，与 Android 语义一致） |
| §A-7 启动 reconcile | `MomentReplyTask.reconcile`（pendingMomentTurnIds → `nextAttemptAt`）、`ContextMaintenanceTask.startupReconcileRecords`（rebuild/failed 分流 + 24h 窗口由层裁剪） |
| §A-9 恢复护栏 | 两个执行体入口先判 `isRecoveryActive` → RETRY（oracle 两个 Worker :27） |
| §B-1 turn 生成 | `MomentReplyContract.buildTurnId`（SHA-256 identity）、`buildTurn`（surface/trigger/mode/status/attemptCount/nextAttemptAt） |
| §B-2/3 arming·重挂 | `createAndArm`/`arm`/`armAll`（层 KEEP 去重）、`reconcile` |
| §B-4/6 守卫 | `executeTurn`：surface/status 守卫 → DONE；来源/父评论/moment/operator 任一删除 → `cancelTurn` + DONE |
| §B-5 租约 10min | `claimTurn(id, token, now, now+600_000)`；claim 失败 → ClaimDenied + RETRY |
| §B-7/8/9 管线 | 4min `withTimeout` + 同 moment `Mutex` 串行；500ms 评论上下文；模板/记忆/知识库/社交变量装配；2 次生成 + 清洗；租约内落库；分层记忆（失败仅记事件）；评论计数刷新 |
| §B-10 退避 | `handleFailure`：`attemptCount>=4` → fail 终态（**第 4 次执行失败即终态**，claim 时已 ++）；否则 release `retryAt=now+60s<<(n-1).coerceIn(0,3)`（1/2/4/8min；8min 为公式末项，纯函数 golden 覆盖） |
| §B-11 成功判定 | 执行后 status ∈ {succeeded, cancelled} → 成功/DONE |
| §B-12 UI 手动重试 | `retryFailed(momentId)`：failed → `resetFailedMomentTurn(now)` + 重新入队 |
| §B-13 完成通知事件化 | `NotificationCopy.momentReplyCompleted` → `MomentReplyEvent.NotificationRequested`（不发送；幂等键 `moment-reply-done-$turnId`；发出时刻=落库时刻；正文 120 字） |
| §C-1 五类触发点 | ① `reconcile`/`startupReconcileRecords` ② `onPrivateContextChanged` ③ `onGroupContextChanged` ④ `onSummaryFailure` ⑤ `onForegroundReplyCompleted`/`maintainNow` |
| §C-2/3 唯一键·守卫 | `context-maintenance-$sessionId` KEEP；恢复中→retry；session 缺失→done；会话已删→done |
| §C-4 会话锁幂等 | `ContextMaintenanceLocks.forSession(id).tryLock()` 失败→RETRY 事件；成功→finally unlock |
| §C-5 重建保留旧摘要 | `runRebuild`：先读旧摘要写 `context_rebuild_previous_summary_$id`（不删除旧摘要；摘要只有生成成功才替换）；纪元推进/游标重置/invalidate 仅在 changedId 存在时 |
| §C-6 记忆抽取 | 批 30 × 最多 6 批 + 排除类型 + 纪元变更即 retry + 游标推进；尾部 `retryPendingMemorySources`（oracle MemoryV2Pipeline.retryPendingSources，T24 已交付） |
| §C-7 摘要退避 | `ContextMaintenancePolicy.summaryRetryAt`：1/2/4/8/16/32min 封顶（oracle `coerceAtMost(5)`）；`summaryAutoRetryEnabled=false` 不入队（只写状态） |
| §C-9 group 分支 | payload `group=true`；`runRebuild` 按 group 选择 `groupMemoryGenerationEnabled`/`groupSummaryGenerationEnabled`，端口按 group 分派（oracle Worker :37-38） |
| §C-11 无通知 | 记忆维护只产领域事件，零 NotificationEvent |

## 3. 验证结果

| 路径 | 命令 | 结果 |
|---|---|---|
| JVM 本卡套件 | `./gradlew --no-daemon :shared:testDebugUnitTest --tests "com.rhodes.privatechat.shared.domain.automation.*"` | **70 tests, 0 failures**（含 T23 既有 14 例 + 本卡 56 例） |
| JVM 全量 | `./gradlew --no-daemon :shared:testDebugUnitTest` | **470 tests, 0 failures**（基线 414 + 本卡 56；test-results XML 实测 failures=0） |
| iOS 全量 | `./gradlew --no-daemon :shared:iosSimulatorArm64Test`（KGP 自动 boot iPhone 17） | **498 tests, 0 failures**（基线 442 + 本卡 56；`test-results/iosSimulatorArm64Test` 54 个 XML 实测 failures=0；BUILD SUCCESSFUL 57s） |
| 禁 import 静态门禁 | `grep -r "BGTask\|UNUserNotification\|WorkManager" shared/src/commonMain/kotlin/.../domain/automation/` | **0 命中**（新增 3 文件零平台符号；T23 既有 `DelayedTaskLayerTest.kt` 注释含字样，不在 commonMain 编译单元） |
| app/ 零改动 | `git status --short` | 本卡新增文件全部在写域；`app/` 下无本卡改动 |

> iOS 全量 498/498 全绿（基线 442 不回归）。本卡 56 例在 iOS 与 JVM 同套件、同断言。

### 3.1 iOS 路径取证命令（KGP Native test 不支持 --tests 过滤时）

```bash
source ../ios-port/toolchain/env.sh
xcrun simctl spawn --standalone "iPhone 17" \
  shared/build/bin/iosSimulatorArm64/debugTest/test.kexe -- \
  --ktest_no_exit_code --ktest_logger=TEAMCITY \
  --ktest_filter="com.rhodes.privatechat.shared.domain.automation.*"
```

### 3.2 并行构建纪律

全程 `source ../ios-port/toolchain/env.sh`；每次 gradle 前 `pgrep -fl gradle`，T26 接线批占用时 sleep 30 轮询排队；一律 `--no-daemon`；本卡只新增文件、无写域交叉。

## 4. iOS 壳波次接线说明（本卡不实现，交付边界如下）

### 4.1 通道适配（实现冻结协议 `DelayedTaskChannel`）

1. **前台主通道** `TimerDelayedTaskChannel`：到期用 `DispatchQueue.asyncAfter`/`Timer` 回调 `onDue(DelayedTaskDue)`；`pendingKeys()` 维护进程内表；`cancel` 只取消挂起项（不中断 running）。
2. **后台兜底通道** `BackgroundRefreshDelayedTaskChannel`：`BGAppRefreshTaskRequest(identifier)` + `earliestBeginDate = dueAt`，Info.plist 注册 `BGTaskSchedulerPermittedIdentifiers`；`launchHandler` 内 `onDue` 后按执行体结果 `setTaskCompleted(success:)`；`expirationHandler` 取消当前执行体（回层 retry）。
3. 两实现都注入同一 `DelayedTaskLayer(primaryChannel=…, fallbackChannel=…)`；两个执行体各调一次 `register(layer)`。
4. **不提交 BGProcessing**（规格卡待裁决问题 4 建议默认不提交；记忆维护主路径=前台 + 启动 reconcile，减少不可测面）。

### 4.2 执行体/端口生产适配

- `MomentReplyPort` → 包 `ReplyTurnRepository`（claim/release/fail/cancel/reset/pendingMomentTurnIds/momentTurns）+ `MomentRepository`（getComment/getMoment/insertCommentForReplyTurn/refreshCommentCount/getOperator 经 ChatRepository）。
  - `insertCommentForReplyTurn` 必须复用 `MomentRepository.kt:207-226` 的「租约校验+插评论+complete turn 同事务」语义（iOS 侧不得 fork 第二份状态机）。
- `MomentCommentContextProviders` → `MemoryV2Pipeline.buildCommenterMemoryContext`、`KnowledgeBaseContextBuilder.forOperator`、`buildRecentSocialContext`、`personalMemoryReferenceStyle`、`sourceAwareUsageRule`、用户资料；全部 `runCatching`/超时降级（域层已兜底）。
- `MomentReplySettings` → `SettingsRepository`：`commentMinChars/commentMaxChars/commentContextCount/memoryV2Enabled/momentCommentMemoryGenerationEnabled`（本卡不新建设置键）。
- `ContextMaintenancePort` → `SessionRepository.getSession`、`MemoryRepository.getShortTermMemory`、`MessageRepository.getMessagesAfterCursor`、`MemoryV2Pipeline.invalidateChangedMessage/retryPendingSources`、私聊/群聊摘要生成与 `ingestPrivateMemoryV2`/`ingestGroupMemoryV2` 分派（按 `group` 参数）。
- 通知：订阅 `MomentReplyEvent.NotificationRequested` → 系统通知中心 `add(request)`；授权节奏按 T13 §1.3（首启 provisional 静默授权，进「消息提醒」设置页再请求 `.alert/.badge/.sound`；权限被拒 `openSettings()` 引导）。文案只取 `NotificationCopy` / `A7ProductCopy`（golden 已锁）。
- 启动/回前台：`applicationDidFinishLaunching` 与 `sceneDidBecomeActive` 各调一次
  `momentReplyTask.reconcile(layer)` + `contextMaintenanceTask.reconcile(layer, sessions)`（层内 24h 窗口；过期任务由层钳 now 交给前台立即补算）。

### 4.3 触发点接线（等价 Android 调用点）

| 触发 | iOS 调用 |
|---|---|
| 广场评论保存后，为每个目标角色生成回复 | `momentReplyTask.createAndArm(layer, payload, now, immediate = (评论者==帖主))` |
| 提及场景（用户动态 @ 角色） | 同上 `immediate=true`，payload `mustInsert=true` |
| 私聊消息编辑/上下文变更 | `contextMaintenanceTask.onPrivateContextChanged(layer, sessionId, changedMessageId)` |
| 群聊回复/变更 | `contextMaintenanceTask.onGroupContextChanged(layer, groupId)` |
| 摘要生成失败（chat/group 管线 catch） | `contextMaintenanceTask.onSummaryFailure(layer, sessionId, group, failures, reason)` |
| 回复完成后的前台内联维护 | `contextMaintenanceTask.onForegroundReplyCompleted(layer, sessionId, group)` 或直接 `maintainNow(sessionId, group)` |
| UI 手动重试动态回复 | `momentReplyTask.retryFailed(layer, momentId)` |

## 5. Open questions / 差异留痕（需 lead/reviewer 定稿）

1. ~~**摘要退避上限算术差异**~~ **已裁决（lead 2026-11-23，oracle 优先）**：oracle `ChatViewModel.kt:637` 为 `(failures-1).coerceAtMost(5)` → 封顶 **32min**；规格卡 §C-7/验收 #5 原「64min」为笔误，已由 lead 修正为 32min，规格卡内已标注。实现 `SUMMARY_RETRY_MAX_SHIFT=5`、`BackoffTest` golden 1/2/4/8/16/32/32/32 已同步。
2. ~~**验收 #5「第 5 次失败→failed」与 oracle 差异**~~ **已裁决（lead 2026-11-23，oracle 优先）**：oracle `attemptCount>=4 → fail` 且 claim 时 `attemptCount++`，因此**第 4 次执行失败即终态**；规格卡 §B-10/§C-7/验收 #5 表述已修正，实现保持 oracle 行为并加测试；`backoffDelayMs(4)=8min` 分支以纯函数 golden 覆盖。
3. **B-13 通知内容归属**（规格卡待裁决问题 1）：本卡用独立模板「XX 回复了你的评论」+ 正文 120 字（不复用私聊回复通知样式）；若产品要求复用需改 `NotificationCopy` 单点。
4. **前台 Timer 在后台挂起时的降级**（问题 2）：本卡域层已保证「任何一次到期/启动 reconcile 都补算 + 完成发通知」；iOS 壳可选择「回前台补 + 通知兜底」或「交后台任务择机执行」，两种都满足域层契约，需定一种作 golden。
5. **层退避与领域退避的叠加**：领域 release 到 1/2/4min，层 RETRY 也按 1/2/4/8min EXP 重挂，两者前 4 次序列一致（本卡实测对齐）；但层不知道领域 `nextAttemptAt`，若未来领域退避序列改动需同步层 config（handoff 留痕，本卡未改冻结契约）。
6. **深触发链 XCTest 是否进 CI**（问题 3）：本卡未涉及（验收 #9 归 iOS 壳波次）；建议按 T13 §4 仅 Debug/测试 target 隔离。
7. **验收 #10 静态门禁范围**：T23 既有 `commonTest/.../DelayedTaskLayerTest.kt` 注释含「BGTask」字样（非编译依赖、非 commonMain）；本卡新增文件已 0 命中。若门禁按 `domain/automation/` 全目录 grep 会命中该历史注释，建议门禁限定 `commonMain` 或同步清理 T23 注释。
