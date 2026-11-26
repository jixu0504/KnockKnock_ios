# T60 实现 handoff：动态发布后干员自动回复接线

> 代理：impl-moment-reply ｜ 任务：task-60 ｜ 状态：**实现完成并端到端验证（2026-11-23）**：
> `--moment-reply-selftest` **27/27 PASS（RESULT PASS）**；真实 UI 发布路径实跑（生产 hook + 10–30s 随机延迟 +
> 前台 Timer 执行 + UI 短轮询刷新）通过；`--a7-glue-selftest` 回归 **22/22 PASS**；framework link +
> xcodebuild BUILD SUCCEEDED。设备：专用模拟器 T60-sim（iPhone 17 / iOS 27.2，
> `B588736E-25FF-44A3-BE43-EA07650F110A`；与并行 T59/T61 隔离，避免共用 iPhone 17 的截图竞态）。
> 用户问题原话：「我发布动态后没有干员回复」。
> 规格卡：`ios-port/specs/moment-auto-reply-spec.md`（本卡产出）。
> 上游：T50 `impl-moments.md` 限制 #2（iOS 发布不创建 reply_turns）、T56 `impl-a7-wiring.md`（A7 前台
> tick/catch-up + MomentReplyTask 执行体已接线）、T25 `impl-a7-glue.md`（域层执行体）。
> 纪律：**git 不提交**（lead 统一提交）；未改 `app/`、commonMain 域层、DB schema、Diary/Chat/Settings 页；
> A7Glue 只新增 T60 段（未重构 T56 结构）。

## 1. 改动清单（file:line 相对 `RhodesLink/`）

| 文件 | 性质 | 内容 |
|---|---|---|
| `shared/src/iosMain/.../iosbridge/MomentsBridge.kt` | 最小 patch | 构造参数 `momentReplyHook`（T60 发布钩子，null=不触发）；`publish` 落库成功后调用 hook（失败只记日志）；`publishForSelfTest`（同路径自检）＋`debugMomentRow`（列表 DTO 断言） |
| `shared/src/iosMain/.../iosbridge/A7Glue.kt` | 新增 T60 段 | `onUserMomentPublished`（生产入口）＋`scheduleMomentReplies`（候选干员/人数/延迟/提示词/点赞；oracle `postUserMoment` 等价）；`buildMentionedCommentPrompt`/`buildBystanderCommentPrompt`（oracle 逐字）；`--moment-reply-selftest/--moment-reply-demo` 自检与 `--moment-reply-fixture-clean` 清理 |
| `shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 最小 patch | MomentsBridge 注入 `{ momentId -> a7Glue.onUserMomentPublished(momentId) }`；`--moment-reply-*` 启动钩子（与 T61 在本文件的改动互不重叠） |
| `iosApp/RhodesLink/Features/Moments/MomentsView.swift` | 最小 patch | 发布后短轮询 3 次（8/20/35s）刷新评论与点赞数；评论预览每次 reload 重新拉取；`scenePhase == .active` 回前台刷新；`onDisappear` 取消轮询 |
| `ios-port/specs/moment-auto-reply-spec.md` | 新增 | 规格卡（契约/参数/验证/限制） |
| `ios-port/handoffs/impl-moment-reply.md` | 新增 | 本文件 |
| `ios-port/handoffs/evidence/t60-*` | 新增 | framework link 日志、xcodebuild 日志、自检日志、DB 核对、截图 |

未改：`MomentReplyTask.kt`（commonMain 执行体，T25 已实现）、`DelayedTaskLayer`/`A7ForegroundChannel`、
`A7Ports.kt`、NotificationBridge/A7NotificationRouter、数据库 schema/迁移。

## 2. 触发链（发布 → 落库 → 执行 → UI）

```
MomentsView.MomentComposerView.publish()
 └─ bridge.publishMoment(text:imageId:)                       # T50 桥
     └─ MomentsBridge.publish (workScope)
         ├─ insertUserMoment(...)  → moments 行（isUserPost=1）
         └─ momentReplyHook(id)   → A7Glue.onUserMomentPublished(id)
             ├─ scheduleMomentReplies(layer, momentTask, id)
             │   ├─ 候选：mentioned + dyn_<id> 允许的干员（name != 用户昵称）
             │   ├─ 评论者：被@（immediate）+ random(1..3) 旁观者（延迟 10–30s）
             │   │   └─ MomentReplyTask.buildTurn → reply_turns.createIfAbsent → layer.enqueue
             │   │       （唯一键 moment-reply-<turnId>，KEEP；前台 Timer 到期触发）
             │   └─ 点赞：被@ + random(2..6) 旁观者 → moment_likes + moments.like_count
             └─ 通知开关预置（notify_moments 默认 true）
App 前台 tick（45s）/ 回前台 catch-up（T56）
 └─ channel.flushOverdue + momentTask.reconcile → 到期 turn → MomentReplyTask.executeTurn
     ├─ claim 租约（10min）→ 来源守卫 → 4min 超时
     ├─ MomentCommentPromptAssembler（moment_comment 模板 + 30+ 变量）→ AI（真实/演示回退）
     ├─ 租约内落库：moment_comments 插入 + reply_turns succeeded（同事务）
     ├─ 记忆写入（memoryV2，失败仅记录）→ comment_count 回写 → 通知事件
     └─ 未读：moment_comments.isRead=0 → MomentsBridge.badges().unreadComments 增加
MomentsView：发布后短轮询 3 次 + 回前台刷新 → 评论数/点赞数/评论预览更新
```

## 3. 调度参数

| 参数 | 值 | oracle |
|---|---|---|
| 唯一键 | `moment-reply-<turnId>`（KEEP 幂等） | MomentReplyWorker.kt:21 |
| 旁观者延迟 | 10–30s 随机 | MainViewModel.kt:2833 |
| 被 @ 延迟 | 立即（0ms） | :2843 |
| 评论者 | 被 @ 全部 + random(1..3) 旁观者 | :3052 |
| 点赞者 | 被 @ 全部 + random(2..6) 旁观者 | :3069 |
| 评论字数 | comment_min_chars（10）..comment_max_chars（40） | SettingsRepository:1132-1138 |
| 评论上下文 | comment_context_count（5） | SettingsRepository:485 |
| 干员开关 | `dyn_<operatorId>`（默认 true） | getOperatorDynPermission |
| 通知开关 | `notify_moments`（默认 true；同会话 60s 去重） | A7NotificationRouter |

## 4. 验证（2026-11-23，模拟器 T60-sim = iPhone 17 / iOS 27.2）

### 4.1 构建

| 项 | 结果 | 证据 |
|---|---|---|
| `:shared:linkDebugFrameworkIosSimulatorArm64`（--no-daemon） | **BUILD SUCCESSFUL**（14s） | `evidence/t60-framework-link.log` |
| `xcodebuild build`（generic iOS Simulator，DerivedData-t60，CODE_SIGNING_ALLOWED=NO） | **BUILD SUCCEEDED**，0 error | `evidence/t60-xcodebuild.log` |

### 4.2 `--moment-reply-selftest`（测试层 + 确定性演示 AI + 真实 DB）：**27/27 PASS，RESULT PASS**

启动：`xcrun simctl launch <T60-sim> com.rhodeslink.app --moment-reply-selftest`
报告：App 容器 `Library/Application Support/t60-moment-reply-selftest.log`（归档 `evidence/t60-moment-reply-selftest.log`）。

```
=== T60 moment auto reply selftest ===
time=1795438579930 pass=27 fail=0
PASS fixture.operators | count=147
PASS publish.ok | id=4
PASS publish.isUserPost | operator=user name=博士
PASS schedule.turns | turns=3
PASS schedule.turns.pending | status=[pending, pending, pending]
PASS schedule.turns.session | session=moment:4
PASS schedule.likes | likes=2
PASS schedule.likeCountField | field=2 rows=2
PASS schedule.turns.due | dueAt=[now, now, now]（自检覆盖延迟为立即）
PASS tick.fired | fired=3 turns=3                       ← tick/catch-up 的 flushOverdue 原语补触发
PASS execute.settled
PASS turn.succeeded | succeeded=3/3 status=[succeeded, succeeded, succeeded]
PASS layer.drained | pending=[]
PASS comment.inserted | ai=3 db=3 turns=3
PASS comment.operatorName | name=迷迭香
PASS comment.length | len=20 range=10..40               ← 评论字数在 comment_min_chars..comment_max_chars
PASS comment.content | content=看你说得这么香，我下次也想一起去尝尝看。
PASS comment.unread | unread=3/3
PASS moment.commentCount | rows=3 field=3
PASS moment.likes | rows=2 scheduled=2
PASS badge.unreadComments | unread=4 ai=3               ← 未读评论角标增加（getUnreadCommentCount 同口径）
PASS notify.recorded | moment-reply-done-...            ← notify_moments 开关 → 通知事件记录
PASS ui.row.present / text / commentCount / likeCount   ← UI 列表 DTO 的评论/点赞数已刷新
PASS schedule.idempotent | turns=3 likes=2              ← 重复发布调度不新增 turn/点赞
RESULT PASS
```

清理：自检结束删除夹具（`fixture.cleaned momentId=4`）；复核 `moments/moment_comments` T60 夹具 = 0。
另以 `--moment-reply-fixture-clean` 在 T60-sim 清理 UI 实跑夹具（T60 动态/评论 = 0；剩余 1 条 `moment:5`
reply turn 属 T56 回归自检夹具，非 T60）。T60-sim 已 shutdown。

### 4.3 真实 UI 生产路径实跑（发布器 → 生产 hook → 10–30s 随机延迟 → Timer 执行 → UI 轮询刷新）

操作（axe 真实触控，两次独立验证）：
动态广场 → 发布 → 输入「T60生产路径：今天天气不错，和干员们在甲板上晒了太阳。」/「T60轮询验证：傍晚在食堂吃了新菜，味道比想象中好。」→ 确认发布。
发布后立刻 sqlite3 复核：新动态落库（isUserPost=1）、`reply_turns` 2–3 条 **pending**、
`nextAttemptAt` 比 `createdAt` 晚约 23–24s（oracle 10–30s 随机延迟生效）、`moment_likes` 已写入。约 45s 后再查：

| 断言 | 实测（第二次「T60轮询验证」动态） |
|---|---|
| `reply_turns` | 3/3 `succeeded`（surface=`moment_comment`，session=`moment:6`，attemptCount=1） |
| `moment_comments` | 3 条（`阿米娅`、`弑君者`、`Misery`，`isRead=0`） |
| `moment_likes` | 5 条（`阿米娅/埃特拉/莱万汀/推进之王/狼卫`），`moments.likeCount=5` |
| `moments.commentCount` | 3（回写成功） |
| UI（**未手动刷新**） | 发布后 35s 内短轮询自动刷新：评论预览「阿米娅/弑君者」+「查看全部 3 条评论」+ 点赞 5 / 评论 3 |

第一次验证（「T60生产路径」动态，moment id=2）同样通过：2 条 pending → 2/2 succeeded（`羽毛笔`、`忍冬`），
4 个点赞（`砾/桑葚/赫默/华法琳`），commentCount=2。
DB 证据归档：`evidence/t60-db-verify.txt`（moments / moment_comments / reply_turns / moment_likes 全量行）。

### 4.4 UI 截图（`--moment-reply-demo` 保留夹具）

| 文件 | 内容 |
|---|---|
| `evidence/t60-moments-reply.png` | 动态广场：T60 动态下 2 条干员 AI 评论预览（山 / 傀影）+「查看全部 3 条评论」+ 点赞 2 / 评论 3 |
| `evidence/t60-moments-production-publish.png` | 真实发布器路径（未手动刷新）：T60轮询验证动态 + 干员评论预览（阿米娅/弑君者）+「查看全部 3 条评论」+ 点赞 5 / 评论 3 |
| `evidence/t60-moment-reply-demo.log` | demo 模式自检报告（keepFixture=true，27/27 PASS） |

### 4.5 回归

- `--a7-glue-selftest`（T56 原有 A7 接线自检）：**22/22 PASS，RESULT PASS**（`evidence/t60-a7-regression.log`）
  —— 证明 T60 新增段未破坏 T56 的层/通知/记忆维护/群自动互动行为。
- T50 自检路径未受影响：T50 自检直接调 `insertUserMoment`（不经 `publish` hook），行为与 T50 交付一致。

## 5. 限制 / 后续

1. iOS 发布器无 @ 选择 UI → 生产只走「旁观者」分支；@ 分支已实现（调度层）并由自检覆盖，待发布器支持后即生效。
2. 旁观者 10–30s 延迟期间 App 被杀 → 下次启动 catch-up（24h 窗口）补执行（iOS 无 WorkManager 级持久调度，A7 口径）。
3. 带图动态：`POST_CONTENT` 为原始内容（含 T50 的 `[图片] media/...` 标记），AI 可能看到标记文本；`USER_CONTENT` 已剥离。
4. `MomentReplyTask` 设置快照在 A7Glue 构造时读取（T56 限制）→ 修改评论字数需重启 App。
5. 评论对「我的评论」的 AI 自动回复（oracle `commentOnMoment` 的 AI 分支）不在本卡范围（用户问题只涉及发布动态）。
