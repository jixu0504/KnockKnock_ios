# BGTask + 本地通知实验规格 —— 「前台补算 + 本地通知」产品文案依据（A7）

- 实验人：bg-task-probe（T13）。日期：2026-11-23。环境：Xcode 27.2 / iOS 27.2 模拟器（iPhone 17e，UDID `C1304373-4175-4730-A8D2-7D4E12FE1CB4`）。
- 工程：`ios-port/bg-task-probe/BGTaskProbe.xcodeproj`（bundle `com.rhodeslink.probe.BGTaskProbe`；BGAppRefresh 任务 id `…Probe.refresh`、BGProcessing 任务 id `…Probe.processing`；Info.plist 含 `BGTaskSchedulerPermittedIdentifiers` + `UIBackgroundModes(fetch, processing)`）。
- 原始数据：`ios-port/bg-task-probe/artifacts/bgtask-log.json`（NDJSON 全量 63 行）、`artifacts/series.txt`（5 连触发流水）、`artifacts/*.png`（屏幕证据）。方法细节见 `ios-port/handoffs/bg-task-probe.md`。

## 1. 实测数据

### 1.1 核心发现一：iOS 27.2 模拟器上 BGTaskScheduler 系统调度被平台禁用

| 探测 | 结果 | 证据 |
|---|---|---|
| `submit(BGAppRefreshTaskRequest)` / `submit(BGProcessingTaskRequest)` | 一律失败：`BGTaskSchedulerErrorDomain Code=1 "BGTaskScheduler is not available on this platform."` | log 中 `schedule-error` ×4（两次启动各 2 条） |
| `getPendingTaskRequests` | 返回 `count=0`，无错误 | log `pending-requests` |
| `register(forTaskWithIdentifier:using:launchHandler:)` | 成功（两个标识符均在 `_registrations` 中） | LLDB dump `_registrations` |
| `UIApplication.backgroundRefreshStatus` | `.available`（=2）——设置里后台刷新是开的，限制在调度器本身 | 每条 `task-start` 的 `bgStatus=2` |
| Apple 文档 | `BGTaskScheduler.Error.unavailable`："The app runs on Simulator which doesn't support background processing." | [developer.apple.com](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler/error/unavailable) |

**结论：iOS 27.2 模拟器上「真实等待系统调度」路径物理上不存在（提交即被拒，队列永远为空），60 分钟等待无意义。真实调度只能在真机验证（列入遗留问题）。**

### 1.2 核心发现二：私有触发链可用 —— 5 连触发 100% 成功

绕过平台闸门的触发链（详见 handoff §2）：
1. 注册照常生效；`_simulateLaunchForTaskWithIdentifier:` 存在但**空转**——其实现是「取 pending 请求 → 按标识符匹配 → 执行」，队列恒空（LLDB 反汇编证据）。
2. 深触发：`_initWithIdentifier:activity:`（activity 传 nil）本地构造 `BGAppRefreshTask/BGProcessingTask` 实例 → 从 `_registrations` 取注册对象 → `_runTask:registration:` 调用注册的 launchHandler。iOS 27.2 运行时 BGTask 类未实现 `_activity`（直接调用会抛 `unrecognized selector`，见 handoff），需 `class_addMethod` 补一个返回 nil 的 `_activity`——恰好命中框架自带的「模拟任务」分支（框架日志 "Marking simulated task complete"）。
3. 过期路径：`_simulateExpirationForTaskWithIdentifier:` → 框架日志 "Expiring simulated task" → 任务的 `expirationHandler` 被调用（`_callExpirationHandlerWithReason:0`）。

**5 连触发实测（间隔 27–38s，连续无失败）：**

| # | 触发时刻(本地+08:00) | 任务 | 请求工作时长 | 结束方式 | grantedSec（实际获得） | deltaSec（实际开始 − earliestBeginDate） |
|---|---|---|---|---|---|---|
| T1 | 00:34:35 | refresh | 10s | completed | 10.2 | −3.0 |
| T2 | 00:35:04 | refresh | 15s | completed | 15.2 | +25.5 |
| T3 | 00:35:31 | processing | 10s | completed | 10.0 | −7.5 |
| T4 | 00:35:59 | refresh | 30s | expired（第 12s 人工模拟过期） | 12.0 | +81.0 |
| T5 | 00:36:37 | processing | 20s | expired（第 10.5s 人工模拟过期） | 10.5 | +58.5 |

（另有热身组：refresh 10s completed 10.1s；refresh 30s expired 7.5s。）

**解读：**
- launchHandler 调用、`setTaskCompleted` 回执、`expirationHandler` 过期回调三件事在模拟触发链上全部按 BGTask 语义工作；5 连触发成功率 **5/5（100%）**，机制无记忆性/冷却问题。
- `completed` 的几次任务**拿满了请求时长**（10.2/15.2/10.0s），但这是模拟路径的特性（activity=nil，系统不给 deadline）。**真机上系统给的时间窗（refresh ~30s / processing 分钟级）在模拟器上不可测**——这是本实验最重要的真机差异。
- `deltaSec` 是「模拟立即触发」相对「从未生效的 earliestBeginDate」的产物，不是系统调度偏差；模拟触发约在 invoke 后 0–2s 内执行。真实调度偏差不可测（原因同 1.1）。
- refresh 与 processing 在模拟链路上行为无差异（差异在真机的时间预算与电量约束）。

### 1.3 本地通知实测

| 步骤 | 结果 | 证据 |
|---|---|---|
| 冷启动请求 provisional 授权（不弹窗） | `granted=true`，`status=3`(provisional)，全程无弹窗 | log `auth-request mode=provisional`、`auth-status` |
| 发送本地通知（延迟 12s，app 转后台） | `add()` 无错误；12s 后通知中心**送达 count=1** | log `notification-sent err=nil`、`notification-delivered-count count=1`；BulletinBoard `VersionedSectionInfo.plist` 生成该 bundle 条目 |
| 后台时屏幕（截图存档） | 无横幅——provisional 语义就是静默进通知中心，符合 Apple 文档 | `artifacts/notif-provisional-screen.png` |
| provisional 之后再请求完整授权 | 模拟器上**无弹窗、回调不触发**（真机应显示升级弹窗——待真机验证） | `artifacts/auth-dialog.png`（OCR 证实屏幕无弹窗） |

**授权时机合规要点（实测支撑）：** 首次启动用 `.provisional` 请求（不弹窗、静默送达通知中心）→ 用户进入相关功能（如「消息提醒」设置页）时再请求 `.alert/.badge/.sound` 完整授权（真机弹窗）。权限被拒后用 `UNUserNotificationCenter.openSettings()` 引导。

## 2. 结论：可承诺 / 不可承诺清单

### 可承诺（有本实验实测依据）
1. **「打开 App 立即补算」**——前台路径完全可控：前台定时器 + 启动时扫描过期任务立即执行（对应 Android 端已有「前台补算」原语，a7-预研 §发现 1）。模拟触发机制 5/5 稳定，前台 Timer 是确定性语义。
2. **「补算完成用本地通知提醒」**——本地通知全链路实测可用（provisional 静默授权 → 送达 count=1）。通知的**发出时刻由 app 决定**，因此「完成即提醒」这个承诺是准点的（准点性在发出侧，不在任务执行侧）。
3. **「尽力而为的定时」**——BGTaskScheduler 注册与执行机制在 iOS 27.2 运行库中有效（注册成功、执行/过期语义实测正常）；真机上 app 在后台时由系统择机执行、在前台时由 app 自己执行，语义成立。
4. **「用户随时手动触发」**——UI 按钮/下拉刷新直接跑补算管线，不依赖任何调度。

### 不可承诺（数据/文档依据）
1. **「准点/定时回复」**——BGTaskScheduler 是机会型调度，`earliestBeginDate` 只是下限；模拟器上连提交都被平台拒绝（code=1）。**计划书 A7「iOS 语义=前台补算+本地通知，不承诺准点」得到实测支撑。**
2. **「后台长任务」**——真机 BGAppRefresh ~30s 窗口、BGProcessing 分钟级且受电量/网络约束；本实验模拟路径任务拿满请求时长（10–15s completed）**不代表真机预算**。
3. **「通知一定送达」**——用户可关闭通知权限；provisional 是静默送达（不进横幅），触达强度取决于用户后续设置。
4. **「重启后准点恢复」**——BGTask 请求的持久性由系统保证（真机机制），模拟器不可验证；重启恢复应设计为「启动时 reconcile 过期任务 + 前台立即补算」，而不是依赖系统准点。

## 3. 产品文案措辞建议（中文，可直接引用）

### 设置页（通知/定时相关开关）
- 定时回复开关副标题：
  > **「按你的节奏，尽快回复。」** iOS 不承诺精确到秒的定时；RhodesLink 会在条件允许时尽快执行，App 打开时立即补上。
- 通知权限引导（用户进入相关设置时展示）：
  > 「开启通知后，回复完成时第一时间提醒你。首次不会打扰你；你也可以在系统设置中把 RhodesLink 改为横幅提醒。」
- 后台受限兜底说明：
  > 「如果系统延后了定时任务，打开 RhodesLink 会自动补上，不会丢。」

### 说明页 / FAQ
- Q：定时回复为什么不一定准点？
  > A：iOS 为省电采用机会式后台调度，任何 App 都无法承诺精确到秒的后台执行。RhodesLink 的做法是：App 在前台时按你的节奏执行；在后台时由系统择机执行；你打开 App 的瞬间立即补算，并在完成后发本地通知提醒你。
- Q：为什么没收到通知？
  > A：请到 系统设置 → 通知 → RhodesLink 检查开关。首次授权是静默模式（不弹横幅），可在系统设置中调整为横幅/声音提醒。
- Q：定时回复会丢吗？
  > A：不会。所有待回复都会持久保存，任何一次 App 启动都会检查并补上过期任务。

### 不可用的措辞（避免使用）
- ❌ 「准点回复」「X 秒后回复」「定时精确到秒」
- ✅ 替代：「尽快回复」「回复后第一时间提醒你」「打开 App 立即补上」

## 4. A7 保留项（动态定时回复 / 记忆维护）iOS 实现建议

1. **通用「延迟任务层」（A1 与 A7 共用，落地 a7-预研 §2a 薄层结论）**：
   - 唯一键 + 延迟 + 指数退避重试 + 启动 reconcile + 双通道执行（前台 Timer 主通道 / BGTaskScheduler 兜底通道）。到期判定放在**执行时**（`nextAttemptAt ≤ now + 容忍窗口`），不信任系统准点。
2. **动态定时回复（MomentReplyWorker → iOS）**：
   - 前台主通道：`DispatchQueue.asyncAfter` 随机 10–30s 拟人延迟（对齐 Android `MainViewModel.kt:2833`）；执行走与广场手动回复**同一管线**（对齐 a7-预研 §2b）。
   - 启动重挂：启动时扫描 pending replyTurns（shared 层 `ReplyTurnRepository` 随共享层移植，a7-预研 §发现 3），过期且前台 → 立即执行；否则 `BGAppRefreshTaskRequest` 兜底。
   - 完成通知：回复落库后发本地通知（此时 app 多在后台，通知是主要触达；授权按 §1.3 合规节奏）。
3. **记忆维护（ContextMaintenanceWorker → iOS）**：
   - 幂等 + 会话锁（对齐 Android ContextMaintenanceLocks）→ 不需要严格调度；`BGProcessingTaskRequest(requiresNetworkConnectivity: false, requiresExternalPower: false)` 兜底即可。
   - 前台回复完成后同管线同步维护（对齐 Android 正常回复后同路径触发）。
4. **测试策略**：本实验的模拟触发链（`_runTask:registration:` + `_simulateExpirationForTaskWithIdentifier:`）可作为 CI/开发期验证 BGTask 处理逻辑的测试替身；**真实调度（提交被接受、earliestBeginDate 生效、系统给的时间窗、BGProcessing 电量/网络约束、provisional 升级完整授权弹窗）必须真机验证**，且 Apple 审查不接受私有 API——私有触发代码必须隔离在 Debug/测试构建之外。

## 5. 实验证据链速查
- `ios-port/bg-task-probe/artifacts/bgtask-log.json` —— 全部 63 条 NDJSON（launch / schedule-error / pending-requests / run-task / task-start / task-end / expiration-fired / auth-* / notification-*）。
- `ios-port/bg-task-probe/artifacts/series.txt` —— 5 连触发时间线。
- 模拟器统一日志（framework 级）：`Running task:`、`Simulating expiration for task with identifier`、`Expiring simulated task:`、`Marking simulated task complete:`（subsystem `com.apple.BackgroundTasks`，见 handoff §3）。
- LLDB 反汇编证据：`_simulateLaunchForTaskWithIdentifier:` = getPendingTaskRequests + 匹配；`_runTask:registration:` 调用链含 `[task _activity]`（iOS 27.2 未实现 → 需 shim）。
