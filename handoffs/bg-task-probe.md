# bg-task-probe 交接文档 —— 方法、完整实验记录、遗留问题

- 代理：bg-task-probe（T13）。日期：2026-11-23。规格结论见 `ios-port/specs/bg-task-notification-spec.md`。
- 环境：Xcode 27.2、iOS 27.2 模拟器（iPhone 17e，UDID `C1304373-4175-4730-A8D2-7D4E12FE1CB4`；iPhone 17 被 T12 占用未动）。
- 工程：`ios-port/bg-task-probe/`（pbxproj 同 GlassShellDemo 范式：objectVersion 77 + PBXFileSystemSynchronizedRootGroup + `OTHER_SWIFT_FLAGS=-disable-sandbox`；Info.plist 放在同步目录**外**并指 `INFOPLIST_FILE=Info.plist`，否则会被当资源重复拷贝导致 BUILD FAILED）。

## 1. 工程与运行方法

- 源码 `BGTaskProbe/`：AppDelegate（注册 BGAppRefresh+BGProcessing，启动/进后台重挂，earliestBeginDate=+60s/+120s）、TaskRunner（任务内 5–30s 计数工作，写 task-start/task-end：grantedSec、deltaSec、completed/expired）、NotificationProbe（provisional/完整授权、发通知、送达回查）、SimTriggerPoller（轮询 Documents/command.json 执行一次性命令）、Support（NDJSON 日志 Documents/bgtask-log.json + NSLog 双通道）。
- 构建/运行：
  ```
  xcodebuild -project ios-port/bg-task-probe/BGTaskProbe.xcodeproj -scheme BGTaskProbe \
    -configuration Debug -sdk iphonesimulator \
    -destination 'platform=iOS Simulator,id=C1304373-4175-4730-A8D2-7D4E12FE1CB4' \
    -derivedDataPath ios-port/bg-task-probe/DerivedData build
  xcrun simctl install <udid> DerivedData/Build/Products/Debug-iphonesimulator/BGTaskProbe.app
  xcrun simctl launch --console-pty <udid> com.rhodeslink.probe.BGTaskProbe   # 后台任务挂起前台，stdout 实时看 NSLog
  ```
- 注意：本机有两台模拟器同时 booted（T12 的 iPhone 17），**`simctl` 一律用显式 UDID**，`booted` 会报错。
- 无头命令通道：向 app 容器 `Documents/command.json` 写一次性命令（app 1.5s 轮询、执行后删除）：
  `simulate`（私有 `_simulateLaunchForTaskWithIdentifier:`）、`simulate-expiration`、`run-task`（深触发，见下）、`pending`（getPendingTaskRequests 探针）、`request-auth`（provisional|full）、`send-notification`（delaySec）、`set-work`（workSeconds）。脚本：`scripts/cmd.sh`、`scripts/poll-log.sh`、`scripts/container.sh`、`scripts/run-series.sh`、`scripts/trigger-lldb.sh`。

## 2. 方法：如何拿到模拟器上的任务执行数据（关键路径）

### 2.1 平台闸门（结论：iOS 27.2 模拟器 = BGTaskScheduler 不可用）
- `submit()` 一律 `BGTaskSchedulerErrorDomain Code=1 "BGTaskScheduler is not available on this platform."`；`getPendingTaskRequests` 返回空且无错；`register()` 成功（LLDB dump `_registrations` 有两条）；`backgroundRefreshStatus == .available`。Apple 文档 [BGTaskScheduler.Error.unavailable](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler/error/unavailable) 明确「Simulator doesn't support background processing」。→ 真实等待调度在模拟器不可行，60 分钟等待被证据链否决（无 pending 请求可被接受）。

### 2.2 任务书指定的 LLDB 私有触发（`_simulateLaunchForTaskWithIdentifier:`）——在 iOS 27.2 上**空转**
- LLDB attach 模拟器 app 进程完全可行（`xcrun lldb -p <pid>`，expr -l objc 求值 OK）。
- 反汇编证据：该方法 = `getPendingTaskRequestsWithCompletionHandler:` + 在回调里按 identifier 匹配 pending 请求 → 匹配到才执行。pending 恒空 → **永不执行**（调用后仅框架日志 "Simulating launch for task with identifier …"，无任务启动）。
- `_simulateExpirationForTaskWithIdentifier:` 反汇编：查 `_runningTasksMap` → 有任务且无 activity 时 dispatch 一个块 → 调任务 `expirationHandler` + `_callExpirationHandlerWithReason:0`（框架日志 "Expiring simulated task"）→ 该路径**可用**，用于测过期。

### 2.3 深触发（本实验实际采用，5/5 成功）
1. `class_createInstance(BGAppRefreshTask.self, 0)` —— **注意 iOS 27.2 SDK overlay 把它改声明为返回 `Any?`（直接含实例对象，`as? NSObject` 取用）**；老写法（UnsafeMutableRawPointer + unsafeBitCast）在本 SDK 编译不过/运行崩（`Can't unsafeBitCast between types of different sizes`）。
2. `perform(_initWithIdentifier:activity:, with: taskId, with: nil)` —— activity 传 nil（真实 activity 由系统 XPC 注入，进程内无法构造）。
3. 取注册对象：`scheduler.value(forKey: "_registrations")[taskId]`（`_BGTaskSchedulerRegistration`）。
4. `perform(_runTask:registration:, with: task, with: reg)` —— 框架把任务放入 `_runningTasksMap`、设 handler 队列，并在注册队列上 dispatch 调 launchHandler（框架日志 "Running task: …"）。
5. **iOS 27.2 运行时坑**：`_runTask:` 内部调 `[task _activity]`，而 BGTask 类族在 27.2 未实现 `_activity`（只有 *Request 类实现了）→ 直接调用抛 `NSInvalidArgumentException: -[BGAppRefreshTask _activity]: unrecognized selector`（app 崩溃，LLDB 表达式同因）。**解法**：`class_addMethod(cls, "_activity", 返回 nil 的 block IMP, "@@:")`——nil activity 恰好命中框架自带的「模拟任务」分支（完成时日志 "Marking simulated task complete"）。
6. 过期：任务运行中发 `simulate-expiration` → expirationHandler 触发 → `setTaskCompleted` 收尾。整链实测 5/5。

- LLDB 直发同一调用链亦可（本实验曾试），但 lldb ObjC++ 表达式对私有 selector 需要 `performSelector:(SEL)NSSelectorFromString(...)` 逐层绕过编译期检查，且遇异常回滚、不易控制对象生命周期；**in-app 命令通道等价且稳定**，故 5 连触发用它。`trigger-lldb.sh` 保留作证据复现入口。

## 3. 完整实验记录（时间线，全部本地时间 +08:00）

| 时刻 | 事件 | 结果/证据 |
|---|---|---|
| 00:16 前 | 建工程、两次修复构建（Info.plist 资源重复；Event 参数序/可选 Date） | BUILD SUCCEEDED |
| 00:17:07 | 首启（iPhone 17e） | `schedule-error code=1 unavailable` ×2；`auth-status status=0` |
| 00:17–00:18 | LLDB attach + `_shortMethodDescription` dump | 确认两个私有 simulate 方法存在；`_registrations` 有两条注册 |
| 00:18:04 | 命令通道 `simulate`（refresh） | selector available=true、invoked，但**无 task-start**（空转，反汇编定因） |
| 00:23:39 | `pending` 探针 | `count=0`（无错） |
| 00:23:44 前后 | 首版 `run-task` | 崩溃 1：`unsafeBitCast` 尺寸错误（SDK overlay 根因，后查明） |
| 00:30–00:31 | LLDB 直发 `_runTask:registration:`（4 次尝试） | 每次框架日志 "Running task" 后抛 `_activity` unrecognized selector（LLDB 内异常回滚，map 恒空） |
| 00:32:49 | 加 `_activity` shim 后重装重启 | 启动日志正常 |
| 00:33:44 | 热身触发 T0：refresh 10s | **task-start deltaSec=-54 → task-end completed grantedSec=10.1** ✅ |
| 00:34:07–14 | 热身触发：refresh 30s + 第 8s `simulate-expiration` | **expiration-fired → task-end expired grantedSec=7.5** ✅ 框架日志 "Simulating/Expiring/Marking simulated task complete" 齐全 |
| 00:34:35 | 系列 T1：refresh 10s | completed，grantedSec=10.2 ✅ |
| 00:35:04 | 系列 T2：refresh 15s | completed，grantedSec=15.2 ✅ |
| 00:35:31 | 系列 T3：processing 10s | completed，grantedSec=10.0 ✅ |
| 00:35:59 | 系列 T4：refresh 30s + 第 12s 过期 | expired，grantedSec=12.0 ✅ |
| 00:36:37 | 系列 T5：processing 20s + 第 10.5s 过期 | expired，grantedSec=10.5 ✅ |
| 00:37:29 | `request-auth provisional` | **不弹窗** granted=true，status=3(provisional) ✅ |
| 00:38:04 | 发通知（12s 延迟）+ app 转后台（开 Settings） | `notification-sent err=nil` |
| 00:38:21 | 送达回查 | **count=1**（通知中心有 1 条）✅；截图 `notif-provisional-screen.png`（静默、无横幅） |
| 00:38–39 | `request-auth full`（provisional 之后） | 模拟器无弹窗、回调不触发（差异，见遗留） |
| 00:40 | 归档 artifacts | bgtask-log.json（63 行）、series.txt、两张截图 |

5 连触发间隔：T1→T2 29s、T2→T3 27s、T3→T4 28s、T4→T5 38s；**成功率 5/5**。

## 4. 遗留问题 / 风险

1. **真实调度未验证（模拟器物理不可行）**：`earliestBeginDate` 生效性、系统实际给的执行时刻偏差、refresh ~30s / processing 分钟级时间窗、BGProcessing 电量/网络约束、后台冷启动（app 被杀后系统拉起）、请求持久化重启恢复——**全部必须真机验证**。建议 Phase 1 排一个真机冒烟（真 iPhone + Xcode，用 Console 观察 duetactivityscheduler）。
2. **系统调度不可控性（产品层面已定调）**：BGTask 是机会式调度，本实验没有推翻也没有证实任何"准点"可能——证据方向反而是"连模拟器都不接受提交"。文案按「不承诺准点」写（spec §3）。
3. **模拟路径的时间数据不代表真机预算**：模拟任务拿满请求时长（10–15s completed）是 activity=nil 的产物；真机 10s 工作对 refresh 的 ~30s 窗口可能拿满、也可能被提前过期，且 BGProcessing 受电量影响。
4. **私有 API 不可上架**：`_initWithIdentifier:activity:`、`_runTask:registration:`、`_simulateExpirationForTaskWithIdentifier:`、`_activity` shim 只允许存在于 Debug/测试构建；正式代码只保留公开 API（register/submit/expirationHandler/setTaskCompleted）。
5. **provisional → 完整授权弹窗在模拟器未复现**（无弹窗且回调不触发）：真机行为是显示升级弹窗，需真机确认；provisional 本身（静默、不打扰）实测可靠。
6. **通知横幅视觉验证受限**：provisional 静默送达（无横幅）符合预期；横幅形态需完整授权+真机（或人工在模拟器点一次 Allow）。
7. **工程小坑备忘**：`class_createInstance` 在 iOS 27.2 SDK 返回 `Any?`；每次触发故意保活一个任务对象（`SimTriggerPoller.liveTasks`）——探测工程可接受，不可移植到正式代码。
8. **T12 共享写域警告**：`ios-port/specs`、`ios-port/handoffs` 与 task-12 写域重叠，本代理只新建/写了 `bg-task-notification-spec.md` 与 `bg-task-probe.md` 两个新文件，未触碰其他文件。
