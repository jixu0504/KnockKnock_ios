# 规格卡：动态发布后干员自动回复/点赞（T60）

> 代理：impl-moment-reply ｜ 任务：task-60 ｜ 状态：实现与验证记录见 `ios-port/handoffs/impl-moment-reply.md`。
> Oracle：Android 1.14.27 `MainViewModel.postUserMoment` + `MomentReplyWorker/MomentReplyScheduler` +
> `shared/domain/automation/MomentReplyTask.kt`（T23/T25 已实现的域层执行体，本卡零改域层）。
> 用户问题原话：「我发布动态后没有干员回复」——T50 的 `MomentsBridge.publish` 只落库，未调度 AI 互动。

## 1. 模块边界

| 层 | iOS 落点 | 职责 |
|---|---|---|
| 桥（发布） | `shared/iosMain/.../iosbridge/MomentsBridge.kt` | `publish` 落库成功后调用 `momentReplyHook`（T60 hook，失败不影响发布结果）；新增自检用 `publishForSelfTest` / `debugMomentRow` |
| 调度编排 | `shared/iosMain/.../iosbridge/A7Glue.kt` | `onUserMomentPublished(momentId)`（生产入口）→ `scheduleMomentReplies(...)`：候选干员选择、`moment_comment` reply turn 生成 + arm、干员点赞落库、oracle 提示词（@/旁观者）逐字迁移 |
| 执行体 | `shared/commonMain/.../domain/automation/MomentReplyTask.kt`（复用，不改） | 租约 claim、来源守卫、4min 超时、评论管线、租约内落库、记忆写入、计数刷新、通知事件化、失败退避 |
| 调度层 | `DelayedTaskLayer` + `A7ForegroundChannel`（复用，不改） | 唯一键 `moment-reply-<turnId>`、KEEP、延迟钳 0、前台 Timer、tick/catch-up 兜底、启动 reconcile |
| 通知 | `A7NotificationRouter`（复用） | `notify_moments` 开关（T53 四键）；评论落库后 `MOMENT_REPLY_COMPLETED` 事件 → 本地通知（同会话 60s 去重） |
| UI | `iosApp/Features/Moments/MomentsView.swift` | 发布后短轮询 3 次（8s/20s/35s，覆盖 10–30s 随机延迟 + 执行）刷新列表评论/点赞数 + 评论预览；回前台（scenePhase active）刷新 |

**零改动**：`commonMain` 域层、数据库 schema/迁移、Diary/Chat/Settings 页、A7Glue 大结构（只新增 T60 段）。

## 2. 行为契约（oracle file:line → iOS 落点）

| # | 契约 | oracle | iOS 落点 |
|---|---|---|---|
| 1 | 发布成功后创建 reply_turns（不是发布前） | `MainViewModel.kt:3055-3067`（insertMomentWithReplies 事务内建 turn → `armMomentReplies`） | `MomentsBridge.publish` → `scheduleMomentReplies(id)`；`A7Glue.scheduleMomentReplies` 逐条 `createIfAbsent` + `arm` |
| 2 | 候选评论者 = 被 @ 干员（必回）+ `dyn_<operatorId>` 允许的旁观者 | `:3049-3052`、`SettingsRepository.getOperatorDynPermission` | `A7Glue.scheduleMomentReplies`：`mentionedIds` 从 `moments.mentionedOperatorIds` 解析；`eligible = operators.filter { name != userName && getOperatorDynPermission(id) }` |
| 3 | 旁观者人数随机 1..3 | `:3052` `take((1..3).random())` | `bystanderPool.shuffled(random).take(1 + random.nextInt(3))` |
| 4 | 参与人数/延迟：被 @ 立即回，旁观者 10–30s 拟人随机延迟 | `:2833` `randomCommentDelayMs()`、`:2843` `nextAttemptAt = now + if (immediate) 0 else random` | 复用 `MomentReplyTask.buildTurn(immediate=mentionedHere)`（域层 `MomentReplyConfig.randomDelayMs` 10–30s）；自检可注入 `delayOverrideMs` |
| 5 | 评论字数设置键 | `SettingsRepository.commentMinChars`（默认 10）/ `commentMaxChars`（默认 40） | `A7Glue.momentSettings()` 快照 → `MomentReplySettings`；`COMMENT_MIN_CHARS/MAX_CHARS` 变量由域层装配进模板 |
| 6 | 评论上下文条数设置键 | `commentContextCount`（默认 5） | 同上（域层 `MomentReplySettings.commentContextCount`） |
| 7 | 点赞 = 被 @ 干员 + 旁观者随机 2..6 人，发布后立即写并回写 likeCount | `:3069-3074` | `A7Glue.scheduleMomentReplies`：`insertLike`（幂等 `getLike==null` 才插）+ `updateLikeCount(momentId, getLikeCount())` |
| 8 | 两种提示词语义（@ / 旁观者） | `:3092-3118` | `buildMentionedCommentPrompt` / `buildBystanderCommentPrompt` 在 A7Glue 逐字迁移（作为 payload.customPrompt，oracle 同样作为「互动意图」注入） |
| 9 | turn 唯一 id = SHA-256(momentId:operatorId:parent:source/userContent)，session=`moment:<id>`，surface/trigger=`moment_comment` | `:2836-2844` | 域层 `MomentReplyContract.buildTurnId` / `buildTurn`（复用） |
| 10 | 调度持久化 + 双通道（Worker + 进程内 LAZY job） | `:2847-2859` + `MomentReplyWorker` | `DelayedTaskLayer.enqueue`（`moment-reply-<turnId>`，KEEP）+ 前台 Timer；启动/回前台 `reconcile`（T56 已实现） |
| 11 | 启动重挂 pending turn（App 被杀后恢复） | `:628-631` | T56 的 `momentTask.reconcile(layer)`（`pendingMomentTurnIds`） |
| 12 | 评论落库 + reply_turns succeeded 同事务/租约 | `MomentRepository.insertCommentForReplyTurn` | 域层 `store.insertCommentForReplyTurn`（复用） |
| 13 | 评论后通知（开关 `notify_moments`） | `RhodesNotificationCenter` | 域层 `NotificationRequested` → `A7Glue.notificationRouter`（T56 已有；默认开） |
| 14 | 未读评论角标 `getUnreadCommentCount(30d, 用户名)` | `MomentsViewModel.getUnreadCommentCountSuspend` | `MomentsBridge.badgesSync`（T50 已实现；AI 评论 `isRead=false` 自动计入） |
| 15 | UI 在 AI 评论到达后刷新 | Android Flow 收集 → Compose 重组 | MomentsView 发布后短轮询 3 次 + 回前台刷新 + 下拉刷新（最多 3 次，避免长期轮询） |

## 3. 调度参数（实测/实现口径）

| 参数 | 值 | 来源 |
|---|---|---|
| 唯一键 | `moment-reply-<turnId>`，KEEP（同键不重排） | oracle `MomentReplyWorker.kt:21` |
| 旁观者延迟 | 10_000 + random*20_000 ms（10–30s） | oracle `MainViewModel.kt:2833` |
| 被 @ 延迟 | 0（立即） | oracle `:2843` |
| 参与评论人数 | 被 @ 全部 + random(1..3) 旁观者 | oracle `:3052` |
| 点赞人数 | 被 @ 全部 + random(2..6) 旁观者 | oracle `:3069` |
| 评论字数 | `comment_min_chars`（默认 10）..`comment_max_chars`（默认 40） | oracle SettingsRepository:1132-1138 |
| 评论上下文 | 最近 `comment_context_count`（默认 5）条 | oracle `:2947-2949` |
| 执行超时 | 单任务 4min；单次模型 45s；内容重试 1 次 | 域层（T25 已对齐） |
| 失败退避 | attempt ≥4 终态 fail；否则 release 1/2/4/8min | 域层 `MomentReplyContract.backoffDelayMs` |
| 前台 tick | 45s（兜底扫描 `flushOverdue`；主通道是 Timer） | T56 `A7GlueConfig.tickIntervalMs` |
| 回前台 | `setForeground(true)` 重排 + catch-up `reconcile` | T56 |

## 4. 数据

- 读：`moments`（发布行/正文）、`operators`（候选/名字/人设/头像）、`settings`（`dyn_<id>`、评论字数/上下文、`notify_moments`、`user_name`）。
- 写：`reply_turns`（surface=`moment_comment`，status pending→running→succeeded/failed）、`moment_comments`（AI 评论，`isRead=0`）、`moment_likes` + `moments.like_count/comment_count` 回写。
- 幂等：turn 唯一 id SHA-256 identity → `insertReplyTurnIfAbsent`；点赞 `getLike` 判重；层 KEEP。
- 清理：`--moment-reply-fixture-clean` 删除内容含 `T60` 的用户动态（级联点赞/评论）与其 `reply_turns`。

## 5. 验证（可自动判定）

1. 构建：`:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL；`xcodebuild build`（iOS Simulator）BUILD SUCCEEDED。
2. `--moment-reply-selftest`（真实 SQLite + 确定性演示 AI + 测试层）：
   `publish.ok` → `schedule.turns`(1..3) → `schedule.likes`(2..6) → `tick.fired`（tick/catch-up 原语补触发）
   → `turn.succeeded` → `comment.inserted/operatorName/length(10..40)/content/unread` → `moment.commentCount`
   → `badge.unreadComments` → `notify.recorded` → `ui.row.commentCount/likeCount` → `schedule.idempotent`
   → RESULT PASS；报告 `Application Support/t60-moment-reply-selftest.log`。
3. DB 直核（sqlite3）：`moment_comments` ≥1（含 `operatorName` 非用户、内容字数在设置范围）、
   `reply_turns.status='succeeded'`、`moment_likes` 行数 ≥2。
4. UI：`--moment-reply-demo` 写演示夹具 → iPhone 17 模拟器截图（动态广场展示干员 AI 评论与点赞数）；
   `--moment-reply-fixture-clean` 清理。

## 6. 限制

1. **延迟语义**：旁观者 10–30s 随机延迟期间 App 若被杀，评论不即时产生；下次启动 catch-up（24h 窗口）
   会补执行（A7 口径）。iOS 无 WorkManager 级持久调度，接受「打开 App 立即补算」。
2. **@ 提及**：iOS 发布器（T50）无 @ 选择 UI，`mentionedOperatorIds` 恒空 → 生产路径只有旁观者路径；
   @ 分支已在调度层实现且由自检覆盖（构造 payload 层），待发布器加 @ 后即生效。
3. **带图动态**：图片用内容内嵌标记 `\n[图片] media/...`（T50 约定），域层 `POST_CONTENT` 会带上该标记；
   AI 评论可能看到媒体标记文本（不影响评论文本本身）。`userContent` 已剥离标记。
4. **设置快照**：`MomentReplyTask` 的设置快照在 A7Glue 构造时读取（T56 限制），改评论字数需重启 App。
5. **模型**：真实供应商已配置时走真实模型；未配置时域层走 `A7MomentStreamingClient` 的确定性演示评论
   （自检强制演示 AI + 固定字数设置，保证可复现）。
