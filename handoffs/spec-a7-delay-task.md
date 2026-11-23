# Handoff：A7 延迟任务层规格卡（T22 a7-delay-spec-card）

- 日期：2026-11-23。交付：`ios-port/specs/a7-delay-task-spec.md`（按模板 v1，13 节 A 契约 + 14 节 B 契约 + 11 节 C 契约，每条带 Android file:line）。仓库只读（只用了 read/grep）；写域仅 ios-port/specs。
- **评审结论：reviewer PASS-WITH-FIXES（0 P1，2026-11-23），修正已落实：① `summaryAutoRetryEnabled` 行号 169→173（实测复核）；② 实现顺序裁决（A1/群聊波次先落地通用层，契约冻结在卡 §A-12，A7 波次随后接胶水并承接验收 #1-#3）。lead 批准（2026-11-23）。**

## 方法
1. 读取模板 v1、T13 实测卡（bg-task-notification-spec.md）、T7 拆分裁决（a7-预研.md）。
2. 通读 oracle 双 worker 全文 + MainViewModel/ChatViewModel/GroupChatViewModel 相关区块，**grep 复核全部行号**（预研文档中两处行号与实测有偏差，本卡以实测为准）：
   - `MainViewModel.kt:632-639`（启动重挂）✓；`moment_comment` 管线 :2939-3022 ✓（模板 :2987、buildCommenterMemoryContext :2954、generateValidComment :3022、ingestMomentComment :2932）；arming 双通道 :2847-2859 ✓；执行体 :2876-2912 ✓（claim 10min :2882、超时 4min :2891、退避 60s<<n 上限 8min :2907-2912）。
   - `ChatViewModel.kt:638`、`:2211` ✓（预研写 638/2211 与实测一致）；锁 :2216-2219 ✓；维护体 :2222-2267 ✓；内联触发 :2776/:2803-2808 ✓。
   - `GroupChatViewModel.kt` 预研写 :478/:2734，实测 schedule 调用在 **:479** 与 **:2802**（卡内已按实测记录）。
   - ReplyTurn 协议落在独立文件 `shared/.../data/ReplyTurnRepository.kt:9-102`（claim :31、complete :41、release :50、fail :54、cancel :97、pendingMomentTurnIds :86、resetFailedMomentTurn :82），ChatRepository.kt:332-346 为转发面。
3. 行为契约按「通用层 A / 动态回复 B / 记忆维护 C」三组组织；验收全部可自动判定（KMP fake-clock 单测 + golden 文案测试 + Debug-only 双路径命令 + 编译期接线隔离断言）。

## 关键发现
1. **层接口证据充分**：Android 5 个 Scheduler 共用「enqueueUniqueWork(KEEP) + initialDelay 钳 0 + EXP 1min + CONNECTED」四行惯用法（MomentReplyWorker.kt:16-21 / ContextMaintenanceWorker.kt:16-21 / DailyContentScheduler.kt:205-211 / ManualReplyScheduler.kt:20-25 / GroupAutoChatScheduler.kt:124-129），通用 DelayedTaskLayer 只需这一个原语——拆分裁决的结构性论据在代码上成立。
2. **Android 已有双通道原语**：`armMomentReplies`（MainViewModel.kt:2847-2859）同一 turn 同时 arm WorkManager 与进程内 delay，`pendingCommentJobs.putIfAbsent` 去重——iOS「前台 Timer 主通道 + BGTask 兜底」是平移不是发明；两通道争抢由租约 claim（:2882）天然互斥，幂等测试有明确 oracle。
3. **到期判定必须放在执行时**：MomentReply 启动重挂把 `nextAttemptAt ?: 0` 传回调度器（:628-631），DailyContentScheduler 自带「迟建铺开」（:229-241）——Android 端本身不假设调度器准点；T13 实测模拟器 BGTask 提交即被拒（code=1），iOS 更不能信任 `earliestBeginDate`。卡内定为层契约 #A-3。
4. **记忆维护的幂等机制是「会话锁 + 唯一键 + 会话已删→done」三件套**（ChatViewModel.kt:2216-2219、ContextMaintenanceWorker.kt:21,31），不是调度器保证——iOS 不需要 BGProcessing 也能等价（前台内联 ChatViewModel.kt:2776 已覆盖主路径）。
5. **通知面极小**：A7 两项在 Android 均无专属通知（a7-预研 发现 2）；卡内唯一新增通知是「补算完成提醒」（B-13），标注为平台差异白名单并给出理由 + 授权节奏（T13 §1.3）。

## Open questions（卡内「待裁决」4 条）
1. 补算完成通知复用私聊回复通知样式还是独立模板（产品确认）。
2. 前台到期时 app 在后台的降级语义（回前台补 vs 等 BGTask）——需定一种作 golden。
3. Debug 深触发链测试替身是否进 CI（T13 实测 5/5 稳定，但涉 in-app 命令通道）。
4. 记忆维护是否提交 BGProcessing 兜底——卡内建议默认不提交（减少不可测面）。
另：验收 #9 的真机验证（真实 earliestBeginDate/时间窗/电量约束）列入 iOS 壳波次遗留，不阻塞本卡。
