# T32 实现记录：iOS 平台服务层（文件/剪贴板/通知/深链/日志导出）

- 任务：`task-32`（Phase 2，impl-ios-services）｜日期：2026-11-23｜状态：**完成**
- 规格：`ios-port/specs/ios-platform-services-spec.md`
- 环境：Kotlin/Native 2.3.21（toolchain 2.3.21）｜Xcode 27.2（iPhone 17 模拟器，iOS 27.2，UDID `A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA`）
- 纪律：gradle `--no-daemon`，构建前 `pgrep` 检查；**git 未提交**（RhodesLink 侧仅 modified + untracked 新文件）。

## 1. 交付清单

### 1.1 Kotlin（`RhodesLink/shared/src/iosMain/.../shared/`）

| 文件 | 行数量级 | 内容 |
|---|---|---|
| `platform/IosPlatformServices.kt` | ~110 | Swift 稳定入口：剪贴板写/读/写后读回、文本/文件分享（UIActivityViewController + iPad popover 锚点）、宿主注入 |
| `platform/IosFileTransfer.kt` | ~230 | `FilePickerHost` 协议 + 导出/导入服务（staging 目录、文件名清洗、导入落盘、无宿主降级） |
| `platform/IosNotificationScheduler.kt` | ~290 | UNUserNotificationCenter 封装：provisional/完整授权、`scheduleEvent(NotificationEvent)` 幂等调度、取消、pending/delivered 查询、openSettings |
| `platform/IosDebugLogger.kt` | ~190 | `Application Support/logs/rhodes.log` 落盘（NSLock + NSFileHandle 追加、512KB 轮转、最多 5 归档）、读取/枚举、导出 URL、走保存面板导出 |
| `platform/DeepLink.kt` | ~230 | `rhodes://` 纯 Kotlin 解析（chat/operator/settings/timeline/memories/home，query/fragment/percent 解码，严格拒绝非法路由） |
| `platform/IosServicesSelfTest.kt` | ~240 | 全链路自检（深链/剪贴板/日志/文件/通知授权+调度+送达），`[T32SELFTEST]` 行输出 |
| `ui/util/PlatformUtils.ios.kt`（既有，修改） | — | `shareText`→UIActivityViewController（主队列、keyWindow→windows 回退）；`copyToClipboard/getClipboardText`→UIPasteboard；`showToast` 仍 TODO |

### 1.2 Kotlin 单测（`shared/src/iosTest/.../platform/DeepLinkParserTest.kt`，11 用例）

合法路由 10 条金样 + 5 条拒绝 + query/fragment + 大小写 + UTF-8 percent 解码 + 空/缺 id/多余段/未知路由。

### 1.3 Swift（`ios-port/iosApp-demo/GlassShellDemo/`）

| 文件 | 内容 |
|---|---|
| `Services/PlatformServices.swift`（新） | `DocumentPickerHost`（真实 UIDocumentPickerViewController 导出/导入 + delegate 回调）、`HeadlessFilePickerHost`（自检模拟）、`AppNotificationDelegate`（前台横幅/点击）、`PlatformServices` 门面（onOpenURL、剪贴板、分享、日志导出、通知 async 包装、自检） |
| `Features/Services/ServicesSelfCheckView.swift`（新） | 「服务」Tab 自检页：自检按钮/输出、剪贴板、通知、文件导出导入、日志、深链卡片；`--services-selftest` 自动运行 |
| `GlassShellDemoApp.swift`（修改） | 新增「服务」Tab、`.onOpenURL` 转发、`.task` 安装通知 delegate |
| `Config/Info.plist`（新）+ `project.pbxproj`（修改） | 注册 `rhodes` URL scheme；`INFOPLIST_FILE=Config/Info.plist` 与 `GENERATE_INFOPLIST_FILE=YES` 合并（放在同步目录外避免 Info.plist 重复产物） |

> 注意：`Info.plist` 不能放在 `GlassShellDemo/`（PBXFileSystemSynchronizedRootGroup 会自动当资源，导致
> “Multiple commands produce Info.plist”）；已移到 `ios-port/iosApp-demo/Config/Info.plist`。

### 1.4 文档与证据

- 规格：`ios-port/specs/ios-platform-services-spec.md`
- 本记录：`ios-port/handoffs/impl-ios-services.md`
- 证据：`ios-port/iosApp-demo/evidence/t32-*`
  - `t32-services-selftest.png`（服务页截图：summary passed=12 failed=0 skipped=0）
  - `t32-selftest.log`（`[IosServices]` 全量 NSLog，含 12 项 PASS 明细）
  - `t32-rhodes.log`（App 沙箱内 `Application Support/logs/rhodes.log` 副本）
  - `t32-clipboard.txt`（`simctl pbpaste` 外部读到自检写入的剪贴板文本）
  - `t32-build.log`（xcodebuild BUILD SUCCEEDED）、`t32-framework-link.log`（linkDebugFramework BUILD SUCCESSFUL）、`t32-tests.log`（单测 BUILD SUCCESSFUL）
  - `t32-deeplink.png` / `t32-deeplink-cold.png`（`simctl openurl` 触发的 iOS 26 系统「在 App 中打开?」确认框截图——见限制 2）

## 2. 验证命令与结果（全部实跑）

```bash
# 1) framework 编译（通过）
cd RhodesLink && source ../ios-port/toolchain/env.sh
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64     # BUILD SUCCESSFUL (38s)

# 2) 深链单测（11/11）
./gradlew --no-daemon :shared:iosSimulatorArm64Test \
  --tests "com.rhodes.privatechat.shared.platform.DeepLinkParserTest"  # BUILD SUCCESSFUL

# 3) demo 构建（通过）
cd ../ios-port/iosApp-demo
xcodebuild -project GlassShellDemo.xcodeproj -scheme GlassShellDemo -configuration Debug \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO build            # BUILD SUCCEEDED

# 4) 安装 + 自检实跑（12/12）
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/GlassShellDemo.app
xcrun simctl launch booted com.rhodeslink.demo.GlassShellDemo \
  --services-tab --services-selftest --deeplink-selftest
xcrun simctl spawn booted log show --last 2m --predicate 'process == "GlassShellDemo"' \
  --style compact | grep IosServices
# → [T32SELFTEST] summary passed=12 failed=0 skipped=0
# → PASS: deeplink.parse(10/10) / deeplink.reject(5/5) / deeplink.query / clipboard.roundtrip /
#         logger.write / logger.export / file.export / file.import /
#         notification.authorization(status=3 provisional) / notification.schedule /
#         notification.idempotent(pendingCount=1) / notification.delivered

# 5) 剪贴板外部验证
xcrun simctl pbpaste booted          # → RhodesLink-T32-clipboard-<ts>（与自检写入一致）

# 6) 日志文件存在性
CONTAINER=$(xcrun simctl get_app_container booted com.rhodeslink.demo.GlassShellDemo data)
cat "$CONTAINER/Library/Application Support/logs/rhodes.log"
ls "$CONTAINER/Library/Application Support/exports/" "$CONTAINER/Library/Application Support/imports/"

# 7) 深链 onOpenURL（真实 LaunchServices 路径；App 自开避开系统确认框）
#    由 --deeplink-selftest 触发 UIApplication.open(rhodes://chat/demo-session?from=launch-arg)
# → [IosServices] deeplink raw=rhodes://chat/demo-session?from=launch-arg ok=true kind=chat route=chat/demo-session id=demo-session
```

## 3. 关键设计决策

1. **文件面板 = Kotlin 协议 + Swift 注入**（同 iosbridge 窄门面）：`FilePickerHost` 由 Swift 实现
   （真实 UIDocumentPickerViewController / headless 模拟），Kotlin 只准备文件与校验落盘。避免 K/N 硬编码 UI。
2. **通知 delegate 在 Swift**：前台 `willPresent` 返回 `[.banner,.list,.sound]`，否则前台通知不展示、
   无法自检验证 `delivered`。授权/调度/查询全部在 Kotlin `IosNotificationScheduler`。
3. **授权时机按 T13**：自检先查 `notDetermined` → 请求 provisional（实测 `status=3`、无弹窗）；
   完整授权留给设置页（`requestFullAuthorization`）。冷启动不弹窗。
4. **通知幂等**：`NotificationEvent.id` → UNNotificationRequest identifier，重复调度覆盖；自检验证 `pendingCount=1`。
5. **深链解析纯 Kotlin 且放 iosMain**（未改 commonMain 既有文件，遵守写域）：无平台依赖，Phase 3 可整体搬 commonMain。
6. **日志复用 T9**：`MediaDirectory.applicationSupportDirectory()` + `FileIo`；导出复用 `IosFileTransfer` staging。
7. **自检可脚本化**：`--services-selftest` 自动跑、`--services-headless` 用 headless 文件宿主；结果同时进
   屏幕、NSLog、文件日志三处，便于自动化取证。

## 4. 已知限制 / 转 Phase 3 清单

1. **真实系统文件面板无法 simctl 驱动**：真实宿主已编译注入（正常启动 `headless=false`），但点按导出/打开
   面板需人工/XCUITest；自动化验收用 HeadlessFilePickerHost（回调语义与真实宿主一致）。
2. **`simctl openurl` 会弹 iOS 26 系统确认框**「在 "GlassShell Demo" 中打开?」（截图 t32-deeplink*.png），
   osascript/辅助功能自动化被系统权限阻断。已用 `--deeplink-selftest`（App 自 `UIApplication.open`）证明
   **真实 onOpenURL 链路**可解析并路由；人工验证时点「打开」即可。
3. **provisional→完整授权升级弹窗**模拟器不出现（T13 已记录），真机待验。
4. `NotificationEventSink` 尚未在 Kotlin 层接到调度器（当前 Swift 用 `scheduleMomentReplyDemo` /
   `scheduleCatchUpDemo` 造事件）；Phase 3 在 T25 域层事件出口直接 `IosNotificationScheduler.scheduleEvent`。
5. `PlatformLog.logError` 未接文件日志（本卡写域未含 `PlatformLog.ios.kt`；Phase 3 加旁路即可）。
6. `showToast` 仍 TODO；`shareText` 之外未做图片分享（可复用 `shareFile`）。
7. 深链只到「解析 + 日志 + 服务页展示」，未接导航；Phase 3 用 `handleOpenURL` 结果驱动路由。
8. 日志为单文件追加 + 简单轮转（非原子替换），高频场景可再加缓冲；导出前不强制 flush（当前每次写即 close）。
9. demo 仍保留 7 个 Tab（iOS 上第 5 个起进 More），`--services-tab` 可直达；正式壳按 IA 收敛。

## 5. 交接提示（给 lead / 后续 agent）

- 构建框架：先 `RhodesLink` link framework，再 `iosApp-demo` xcodebuild（T29 既有流程不变）。
- 新增 Swift 文件在 `GlassShellDemo/` 下会被 PBXFileSystemSynchronizedRootGroup 自动纳入；**新增 plist 必须放同步目录外**。
- 若改 `FilePickerHost` 协议方法签名，需同步 `PlatformServices.swift` 的两个实现与 K/N 导出名。
- 若要给真实壳接通知：设置 `UNUserNotificationCenter.delegate`（参考 `AppNotificationDelegate`），
  并在「消息提醒」设置页调 `requestFullAuthorization`。
- 证据复现：`xcrun simctl launch booted com.rhodeslink.demo.GlassShellDemo --services-selftest --deeplink-selftest`；
  截图/日志路径见 §1.4。
