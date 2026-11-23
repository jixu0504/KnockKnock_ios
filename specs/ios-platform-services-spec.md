# iOS 平台服务层规格（T32：剪贴板 / 分享 / 文件 / 通知 / 深链 / 日志）

> 状态：**已实现并在 iPhone 17 模拟器（iOS 27.2）实跑验证**（2026-11-23）。
> 实现记录与证据见 `ios-port/handoffs/impl-ios-services.md`；任务卡：`task-32`。
> 关联规格：`bg-task-notification-spec.md`（T13 授权时机/通知实测）、`ios-bridge-spec.md`（窄门面模式）、
> `ios-data-base-spec.md`（T9 Application Support/文件 IO）、`NotificationEvent.kt`（T25 数据模型）。

## 1. 目标与分层

SwiftUI 壳不直接操作系统服务；所有服务按「**Kotlin 定义接口 + Swift 注入宿主**」的模式分层：

```
SwiftUI（ServicesSelfCheckView / 正式壳）
  │  只调用 Swift 门面 PlatformServices（async 包装）与 Kotlin 服务 object
PlatformServices.swift（iosApp-demo/GlassShellDemo/Services/）
  │  - 注入 FilePickerHost（UIDocumentPickerViewController 真实实现 / HeadlessFilePickerHost 自检实现）
  │  - 设置 UNUserNotificationCenter delegate（前台横幅 + 点击）
  │  - onOpenURL → Kotlin DeepLinkParser
Shared.framework（shared/src/iosMain/.../platform/）
  ├─ IosPlatformServices     剪贴板/分享/文件分享/宿主注入（Swift 稳定入口）
  ├─ IosFileTransfer         文件导出/导入（staging、校验、落盘）
  ├─ IosNotificationScheduler 权限/调度/取消/查询（UNUserNotificationCenter 封装）
  ├─ IosDebugLogger          文件日志（Application Support/logs/）+ 导出
  ├─ DeepLinkParser          rhodes:// 纯 Kotlin 解析（无平台 import，可搬 commonMain）
  └─ IosServicesSelfTest     全链路自检（demo `--services-selftest` 自动跑）
PlatformUtils.ios.kt         既有 expect actual 补齐（shareText / 剪贴板）
```

设计原则（沿用 iosbridge）：
- **UI 操作全部在 Swift**：Kotlin 不硬编码 `presentViewController` 之外的系统 UI；文件面板通过协议回调注入。
- **回调统一收口**：Kotlin 服务回调一律投递主队列；跨语言只传简单类型/String。
- **纯逻辑可测**：深链解析、文件名清洗、状态映射不依赖 UI，单测在 `iosTest`。

## 2. 接口表

### 2.1 PlatformUtils actuals（`shared/src/iosMain/.../ui/util/PlatformUtils.ios.kt`）

| expect | iOS actual | 线程/说明 |
|---|---|---|
| `copyToClipboard(text)` | `UIPasteboard.generalPasteboard.string = text` | 任意线程可用 |
| `getClipboardText(): String?` | `UIPasteboard.generalPasteboard.string` | 同上 |
| `shareText(text)` | `UIActivityViewController`（keyWindow→windows 回退取 rootVC，iPad 设置 popover 锚点） | 内部投递主队列 `NSOperationQueue.mainQueue` |
| `showToast(message)` | 仍为 TODO（Phase 3 玻璃壳轻提示） | 不影响本卡验收 |
| `minimizeApp()` | no-op（iOS 无最小化） | — |

### 2.2 `IosPlatformServices`（Swift 稳定入口 object）

| 方法 | 说明 |
|---|---|
| `copyToClipboard(text)` / `clipboardText(): String` / `clipboardProbe(text): String` | 剪贴板写/读/写后读回 |
| `shareText(text)` | 文本分享面板 |
| `shareFile(path)` | 文件分享面板（日志导出后 share sheet） |
| `installFilePickerHost(host: FilePickerHost?)` | 注入系统文件面板宿主 |
| `hasFilePickerHost(): Boolean` | 宿主是否就绪 |

### 2.3 文件导出/导入（`IosFileTransfer` + `FilePickerHost`）

```kotlin
interface FilePickerHost {   // Swift 实现；K/N 导出为 ObjC 协议
    fun presentExport(url: String, suggestedName: String, onResult: (FileOperationResult) -> Unit)
    fun presentImport(onResult: (FileOperationResult) -> Unit)
}
```

| `IosFileTransfer` | 说明 |
|---|---|
| `installHost(host?)` / `hasHost()` | 宿主注入/查询 |
| `exportsDirectory()` / `importsDirectory()` | `Application Support/exports/`、`Application Support/imports/` |
| `exportText(fileName, text, onResult)` / `exportBytes(...)` | 先写 staging 再交宿主保存 |
| `exportExistingFile(sourcePath, suggestedName, onResult)` | 复制已有文件后导出（日志复用） |
| `importFile(onResult)` | 宿主选文件 → Kotlin 校验并复制进 `imports/` |
| `copyIntoImports(sourcePath, displayName, onResult)` | 外部文件落盘（时间戳前缀防覆盖） |
| `sanitizeFileName(name)` | 去路径分隔/控制字符，限长 120，保留扩展名 |

`FileOperationResult`：`ok / path / error / cancelled`（导入取消时 `ok=false, cancelled=true`）。
宿主契约：**Swift 必须把 security-scoped 选择结果复制到临时目录后再回调路径**（`asCopy: true` 已满足）。

Swift 侧实现（demo）：
- `DocumentPickerHost`：真实 `UIDocumentPickerViewController(forExporting:asCopy:)` / `(forOpeningContentTypes:asCopy:)`；
  导出/取消回调、导入回调；正常启动（非自检）时注入。
- `HeadlessFilePickerHost`：`--services-selftest`/`--services-headless` 时注入，导出=复制到 `Documents/T32Exports/`，
  导入=创建临时文件模拟选择——供自动化验收（系统面板本身无法被 simctl 驱动）。

### 2.4 通知（`IosNotificationScheduler`，接 T25 `NotificationEvent`）

| 方法 | 说明 |
|---|---|
| `authorizationStatus(onResult)` | 查询权限（不弹窗） |
| `requestProvisionalAuthorization(onResult)` | **首启动静默授权**（alert+badge+sound+provisional；不弹窗，T13 §1.3） |
| `requestFullAuthorization(onResult)` | 进相关设置页时的完整授权（真机弹窗） |
| `openSettings()` | 打开系统设置（权限被拒引导） |
| `scheduleEvent(event: NotificationEvent, fireAfterSeconds)` | 以 `event.id` 为 identifier；重复调度**覆盖**（幂等） |
| `scheduleMomentReplyDemo(turnId, commenterName, contentText, seconds, onResult)` | 用 T25 `NotificationCopy.momentReplyCompleted` 造事件 |
| `scheduleCatchUpDemo(seconds, onResult)` | `DELAYED_TASK_CATCH_UP` 演示事件 |
| `cancel(identifier)` / `cancelAll()` | 取消待送达+已送达（幂等） |
| `pendingCount` / `pendingIdentifiers` / `deliveredCount` / `deliveredIdentifiers` | 派发/送达查询（自检与诊断） |

- `NotificationPermissionStatus`：`status`（0 notDetermined/1 denied/2 authorized/3 provisional/4 ephemeral）、
  `statusName`、`granted`、`provisional`、`error`。
- `NotificationScheduleResult`：`ok / identifier / error`。
- 授权时机口径：**冷启动不弹窗**（provisional）；设置页/消息提醒页再请求完整授权。域层只产
  `NotificationEvent`，发送由本调度器承担（T25 边界）。
- 前台展示：`UNUserNotificationCenter.delegate` 由 Swift 设置（`willPresent → [.banner,.list,.sound]`），
  否则前台通知不显示、无法自检验证送达。

### 2.5 深链（`DeepLinkParser`，纯 Kotlin）

支持路由（scheme/action 大小写不敏感，id 保留原样、percent 解码）：

| URL | route | kind |
|---|---|---|
| `rhodes://chat/{sessionId}` | `chat/{sessionId}` | CHAT |
| `rhodes://operator/{operatorId}` | `operator/{operatorId}` | OPERATOR |
| `rhodes://settings` / `rhodes://settings/{section}` | `settings` / `settings/{section}` | SETTINGS |
| `rhodes://timeline` / `rhodes://memories` / `rhodes://home` | 同名 | TIMELINE/MEMORIES/HOME |
| `rhodes://`（空路径） | `home` | HOME |

`DeepLinkParseResult`：`ok / raw / kind(Kotlin enum) / kindName（Swift 用）/ id / section / route / query / error`。
支持 `?a=b&c=d` 与 `#fragment`；拒绝：非 `rhodes` scheme、未知 action、缺 id、多余路径段、空串。
Swift：App 根部 `.onOpenURL { PlatformServices.shared.handleOpenURL($0) }` → 解析并 NSLog 路由（Phase 3 接真实导航）。

### 2.6 日志（`IosDebugLogger`）

| 方法 | 说明 |
|---|---|
| `log(level, tag, message)` / `info` / `warn` / `error` / `debug` | 追加一行到 `logs/rhodes.log` |
| `logsDirectory()` / `currentLogPath()` | `Application Support/logs/`、`logs/rhodes.log` |
| `readCurrentLog()` / `listLogFiles()` / `currentLogBytes()` | 读取/枚举/大小（诊断页） |
| `exportToFile(): String` | 复制到 `exports/rhodes-log-<epoch>.log` 并返回 URL；Swift 用 share sheet 分享 |
| `exportAndPresent(onResult)` | 走系统保存面板导出 |
| `clearAll()` | 清理日志（幂等） |

- 行格式：`<ISO instant> [LEVEL] [tag] message`（`kotlin.time.Clock`）。
- 轮转：`rhodes.log` 超过 512 KB → 归档 `rhodes-<epoch>.log`，最多保留 5 份。
- 线程：`NSLock` 串行化；`NSFileHandle` 追加写。
- 复用 T9：目录解析 `MediaDirectory.applicationSupportDirectory()`、文件读写 `FileIo`。

### 2.7 自检（`IosServicesSelfTest`）

`run(onLine, onDone)`：深链解析 10 条金样 + 5 条拒绝 + query；剪贴板写入读回；日志写入/导出；
文件导出/导入（宿主就绪时）；通知权限→调度→同 id 幂等→2s 后送达查询→清理。
每行带 `[T32SELFTEST]` 前缀；demo `--services-selftest` 自动触发并 NSLog 透出。

## 3. Swift 注入模式（关键）

```swift
final class DocumentPickerHost: NSObject, FilePickerHost, UIDocumentPickerDelegate {
    func presentExport(url: String, suggestedName: String,
                       onResult: @escaping (FileOperationResult) -> Void) { /* UIDocumentPicker(forExporting:) */ }
    func presentImport(onResult: @escaping (FileOperationResult) -> Void) { /* UIDocumentPicker(forOpeningContentTypes:) */ }
    // delegate: 导出 didPick 成功/取消回调；导入把选中文件路径回调（asCopy: true 已在沙箱内）
}

@MainActor final class PlatformServices: ObservableObject {
    static let shared = PlatformServices()
    private init() {
        IosPlatformServices.shared.installFilePickerHost(host: DocumentPickerHost())  // 自检时换 Headless
    }
    func bootstrap() { UNUserNotificationCenter.current().delegate = AppNotificationDelegate.shared }
}
```

- **协议方法名由 K/N 导出**：`presentExport(url:suggestedName:onResult:)`、`presentImport(onResult:)`；
  回调可在任意线程，Swift 更新 UI 前自行 `DispatchQueue.main.async`（demo 已做）。
- 文件面板必须由持有 VC 上下文的 Swift 弹出；Kotlin 只做「准备文件 → 等回调 → 校验/落盘」。
- 宿主缺失时 `exportBytes` 仍返回 staging 路径（`error` 标注 local only），`importFile` 失败并说明原因。

## 4. 验证（2026-11-23，iPhone 17 / iOS 27.2 模拟器，UDID `A63CD17A-…`）

| 验收项 | 命令/方式 | 结果 |
|---|---|---|
| framework 编译 | `./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64` | BUILD SUCCESSFUL（含新增 8 个 Kotlin 文件） |
| 深链单测 | `./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "…platform.DeepLinkParserTest"` | 11/11 通过 |
| demo 构建 | `xcodebuild … -destination 'platform=iOS Simulator,name=iPhone 17' build` | BUILD SUCCEEDED |
| 服务自检 | `simctl launch … --services-tab --services-selftest --deeplink-selftest` | `[T32SELFTEST] summary passed=12 failed=0 skipped=0` |
| 通知授权 | 首次 `authorizationStatus=0` → provisional 请求 → `status=3/provisional provisional=true` | 无弹窗（符合 T13） |
| 通知送达 | 2s 后 `delivered=[moment-reply-done-t32-selftest]` + `willPresent id=…` 系统日志 | 送达通知中心 |
| 通知幂等 | 同 id 重新调度后 `pendingCount=1` | 覆盖不重复 |
| 剪贴板 | 自检写入读回一致；外部 `xcrun simctl pbpaste booted` 读到同一文本 | 双向验证 |
| 文件导出/导入 | headless 宿主：导出到 `Documents/T32Exports/t32-selftest.txt`、导入落盘 `imports/imported-*.txt` | ok |
| 日志落盘/导出 | `Application Support/logs/rhodes.log` 生成（342B，含自检标记）；导出 `exports/rhodes-log-*.log` | ok |
| 深链 onOpenURL | `--deeplink-selftest` 由 App 自开 `rhodes://chat/demo-session?from=launch-arg` → `deeplink raw=… ok=true route=chat/demo-session` | 真实 LaunchServices→onOpenURL 路径 |

证据文件：`ios-port/iosApp-demo/evidence/t32-*`（截图、自检日志、构建日志、rhodes.log）。

## 5. 已知限制（Phase 3 接线清单）

| # | 限制 | 建议 |
|---|---|---|
| 1 | **系统文件面板无法被 simctl 自动化**：真实 `DocumentPickerHost` 已编译注入，但打开/保存面板需人工点按；自动化验收用 `HeadlessFilePickerHost` 模拟回调 | 真机/UI 测试补 DocumentPicker 实机走查；Phase 3 UI 测试用 XCUITest |
| 2 | `simctl openurl` 打开 `rhodes://` 时 iOS 26+ 弹「在 App 中打开?」确认框（SpringBoard 行为），无法脚本点按 | 自动化用 `--deeplink-selftest`（App 自己 `UIApplication.open` 走真实 onOpenURL）；人工验证点「打开」 |
| 3 | 通知 provisional→完整授权的升级弹窗在模拟器上不出现（T13 已记录） | 真机验证完整授权弹窗与横幅样式 |
| 4 | 域层 `NotificationEvent` 目前不直接暴露给 Swift（Swift 用 `scheduleMomentReplyDemo`/`scheduleCatchUpDemo`）；正式壳应在域层产出事件后调 `scheduleEvent` | Phase 3 把 T25 `NotificationEventSink` 接到 `IosNotificationScheduler.scheduleEvent`（Kotlin 侧直连，无需跨语言） |
| 5 | `PlatformLog.logError` 尚未接到文件日志（仍是 NSLog） | Phase 3 在 `PlatformLog.ios.kt` 增加 `IosDebugLogger.log` 旁路（本卡写域未含该文件） |
| 6 | `showToast` 仍 TODO | 玻璃壳轻提示组件落地时补 UIAlertController/自定义横幅 |
| 7 | 深链只做解析与日志，未接导航 | Phase 3 在 `handleOpenURL` 结果上驱动路由（chat/operator/settings 页） |
| 8 | 深链 `+` 不按表单语义解码（%20 可解）；query 重复键后者覆盖 | 与 Android 对齐时统一解析库 |
| 9 | 日志为单文件追加（非原子替换）；轮转仅在单文件超限时 | 高频日志可改批量缓冲；导出前可 flush |
| 10 | 分享只支持文本/文件；图片分享未列入本卡 | 图片走 `shareFile`（临时导出图片文件）即可复用 |
