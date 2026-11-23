# T29 实现 handoff：IosAppGraph 桥接层 + Swift 集成 Demo（Phase 2）

> 代理：impl-ios-graph（T29 / task-29）。规格：`ios-port/specs/ios-bridge-spec.md`（本卡产出，v1）。
> 依据：可行性报告 §5.6、`handoffs/impl-chat-engine.md` §5、`handoffs/impl-groups.md`、`handoffs/impl-memory.md`。
> 状态：**实现完成；framework 双路径链接通过；demo 在 iPhone 17 模拟器实跑通过（UI 显示真实 DB 数据）**。
> git 不提交：`RhodesLink/shared/src/iosMain/.../iosbridge/` 7 个新文件由 lead 统一提交；`ios-port/` 非 git 仓库。

## 1. 写域确认

- RhodesLink：**只新增** `shared/src/iosMain/kotlin/com/rhodes/privatechat/shared/iosbridge/`（7 文件 769 行），
  `git status` 中无我造成的 `M` 条目（其它 `M/??` 属并行 T28 等，与本卡无关）。
- ios-port：`iosApp-demo`（Swift 侧 + pbxproj）、`specs/ios-bridge-spec.md`、`handoffs/impl-ios-graph.md`。
- 未触碰 `shared/build.gradle.kts`、`app/`、domain/data/settings 既有文件。

## 2. 实现清单

### 2.1 Kotlin 窄门面（`shared/src/iosMain/.../iosbridge/`，769 行）

| 文件 | 行 | 内容 |
|---|---|---|
| `IosDtos.kt` | 42 | `SessionDto` / `MemoryDto` / `ChatResultDto` / `BackupProgressDto`（普通 class，仅简单类型） |
| `IosCancellable.kt` | 32 | `IosCancellable`（幂等 cancel，`@ObjCName`） |
| `ChatEngineBridge.kt` | 235 | 真实 `ChatEngine` 组装（`RepositoryChatStore` + `MessageIdAllocator(DatabaseMessageMaxIdSource)` + `SettingsRepository`）、`DemoPromptAssembler`、`DemoStreamingClient`（fake AI，120ms 首响延迟）、事件收集（`ReplyStored/Toast/Undelivered`）、整包落库后读 `ai_json` 抽可见文本并按 2 字/20ms 打字机回调、单飞 + cancel |
| `SessionListBridge.kt` | 68 | `SessionRepository.getAllSessionsSync()` 列表、单会话查询、`ensureSession()`（无会话时 `ensurePresetOperators` + `getOrCreateSession`） |
| `SettingsBridge.kt` | 49 | `SettingsRepository` 的 String/Bool/Int/Long 读写 + `appearanceSkin/darkMode` + `snapshot()` |
| `MemoryBridge.kt` | 39 | `MemoryRepository.getAllLongTermImpressions()`（L3 长期印象，只读） |
| `BackupBridge.kt` | 179 | `NSFileManager/NSData` 文件工具、导出（prepare→copy→verify→done，SQLite 头校验）、两阶段恢复（`rhodes.db.restore-pending`，冷启动换入）、`applyPendingRestoreIfAny` |
| `IosAppGraph.kt` | 125 | 组合根：Application Support DB（`DatabaseWrapper()`，复用 T9 `DatabaseLocations/DatabaseDriverFactory` 语义）、Main/Default 两个协程域、五桥 getter、`databasePath()`、`bootstrap()`（预设角色 + 会话 + 一条 L3 记忆）、`close()`、`makeAppGraph()` |

命名控制：类/入口用 `@ObjCName(swiftName = ...)`（`RhodesAppGraph` / `makeAppGraph` / 各 Bridge / `IosCancellable`），
DTO 走默认（K/N 自动去框架前缀）。

### 2.2 Swift 侧（`ios-port/iosApp-demo/`）

| 文件 | 内容 |
|---|---|
| `GlassShellDemo/Bridge/AppGraphClient.swift`（226 行） | `RhodesAppGraph` 单例包装；`SessionItem/MemoryItem/ChatRoundResult/ChatStreamEvent/BackupStep/BridgeError`；`loadSessions/loadMemories`（continuation → async）；`chatStream`（回调+Cancellable → `AsyncStream`，`onTermination` 调 cancel）；`exportBackupStream`；`writeDemoNote/toggleAppearanceSkin/settingsSnapshot` |
| `GlassShellDemo/Features/BridgeDemo/BridgeIntegrationView.swift`（397 行） | 「Shared 桥接」页：状态卡（DB 路径/session/memory 计数）、真实会话列表、设置写入+读回、聊天发送+打字机流式、长期印象、备份进度。支持 `--bridge-tab`（直达该 Tab）与 `--bridge-selftest`（启动即自动跑设置写入 / 聊天一轮 / 备份导出，UI 状态即证据） |
| `GlassShellDemo/GlassShellDemoApp.swift` | 新增第 6 个 Tab「桥接」+ 两个启动参数 |
| `GlassShellDemo.xcodeproj/project.pbxproj` | 新增 SDK 条件 `FRAMEWORK_SEARCH_PATHS`（sim→`iosSimulatorArm64/debugFramework`，device→`iosArm64/releaseFramework`）+ `OTHER_LDFLAGS = "$(inherited) -framework Shared -lsqlite3"`；保留 `ENABLE_USER_SCRIPT_SANDBOXING=YES` 与 `-disable-sandbox` |
| `iosApp-demo/evidence/` | `t29-bridge-tab.png`、`t29-bridge-selftest.png`、`t29-selftest.log` |

### 2.3 文档

`ios-port/specs/ios-bridge-spec.md`：门面 API 表、DTO、命名映射（Kotlin→ObjC→Swift）、
Swift 包装模板（continuation / AsyncStream / 错误映射）、Xcode 集成配置、已知限制 10 条、验证命令。

## 3. framework 产物

| 产物 | 路径 | 大小 | 命令 |
|---|---|---|---|
| Simulator Debug | `RhodesLink/shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework` | 287 MB（静态库 287657032 B） | `:shared:linkDebugFrameworkIosSimulatorArm64` |
| Device Release | `RhodesLink/shared/build/bin/iosArm64/releaseFramework/Shared.framework` | 140 MB（静态库 140708672 B） | `:shared:linkReleaseFrameworkIosArm64` |

均 `BUILD SUCCESSFUL`（`--no-daemon`，`ios-port/toolchain/env.sh`）。Swift 侧通过 `Shared.h` 读取门面 API 编译通过，
证明导出可读（无 `@HiddenFromObjC`，模块头包含 commonMain 全部公开 API）。

## 4. Demo 运行验证（iPhone 17 模拟器，iOS 27.2 runtime）

```bash
xcrun simctl boot "iPhone 17"          # 已 booted
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/GlassShellDemo.app
xcrun simctl launch booted com.rhodeslink.demo.GlassShellDemo --bridge-tab --bridge-selftest
```

实测日志（`ios-port/iosApp-demo/evidence/t29-selftest.log` 节选，最后一次运行 04:07:52）：

```
[IosBridge] graph created db=/Users/.../Library/Application Support/rhodes.db
[IosBridge] bootstrap sessions=1 error=
[IosBridge] sessions loaded count=1
[IosBridge] memories loaded count=1
[IosBridge] settings write note=来自 SwiftUI 的写入 readBack=来自 SwiftUI 的写入 snapshot=dark=true;skin=rhodes_night;demo_note=来自 SwiftUI 的写入
[IosBridge] chat done ok=true aiId=8 text=（iOS 桥接演示）我听见了：自检：你好，阿米娅。这是来自 fake AI 通道的回复。 error=
[IosBridge] backup stage=prepare progress=0.0 → copy 0.3 → verify 0.9 → done 1.0
[IosBridge] sessions loaded count=1 / memories loaded count=1   # 聊天后刷新
```

**真实 DB 直查**（模拟器容器内 `Library/Application Support/rhodes.db`，`sqlite3` 只读验证；
`simctl install` 会保留数据容器，故 id 跨启动累计，最后一次运行新增 7/8 两行）：

```
chat_messages: 7|isMe=1|text|自检：你好，阿米娅
               8|isMe=0|ai_json|{"emotion":"平静","state":"坐在窗边",...}   ← 引擎落库的 OfflineModeResponse JSON
chat_sessions: session_amiya|阿米娅|（iOS 桥接演示）我听见了：自检：你好，阿米娅。这是来自 fake AI 通道的回复。|online  ← lastMessage 已被引擎更新
reply_turns:   private:session_amiya:7|succeeded
memories:      1|LONG_TERM|演示记忆（真实 DB 写入）：博士与阿米娅在罗德岛走廊第一次说话。
Documents/:   rhodes-backup-<ts>.db（4096 B，文件头 = "SQLite format 3\0"）
```

**UI 证据**：`evidence/t29-bridge-selftest.png`（Shared.framework 已连接 / sessions=1 memories=1 /
DB 路径 / 阿米娅会话 lastMessage 为 AI 回复 / 设置读回=「来自 SwiftUI 的写入」/ 聊天打字机回复 /
备份进度）。截图显示数据全部来自 shared；「设置写入生效」由读回值证明。

截图说明：Demo 的玻璃卡片在该 iOS 27.2 模拟器上 tint 呈近实色橙色（`GlassPanel` 的
`glassEffect(.regular.tint(...))` 行为，属 T12 组件观感，非本卡回归）；信息可读，不影响验证。

## 5. xcodebuild 结果

```
** BUILD SUCCEEDED **   （Debug-iphonesimulator，CODE_SIGNING_ALLOWED=NO，-derivedDataPath 在工作区内）
```
唯一告警：链接期 `libicu.icudtl_dat.o was built for newer 'iOS-simulator' version (18.5) than being linked (18.0)`
（K/N 自带 ICU 的 min version；模拟器运行无影响，消警可把 demo deployment target 提到 18.5）。
`-lsqlite3` 必需（静态 framework 的 sqliter 用系统 SQLite；缺它链接期报 `_sqlite3_*` undefined）。

## 6. 关键设计决策（需 lead 知悉）

1. **chatEngine 用真实 domain 引擎**：`ChatEngine` + `RepositoryChatStore` + `MessageIdAllocator` + 真实
   `SettingsRepository`，只有 `StreamingClient` 与 `PromptAssembler` 是 demo 替身 → 一轮 send 完成
   用户消息落库、租约 claim→succeeded、`ai_json` 回复落库、会话 lastMessage 更新（DB 直查已证）。
2. **onDelta 语义**：引擎按 lead R2「整包落库」不产 token 流；桥在落库后读 `ai_json` 抽可见台词，
   按 2 字/20ms 回调，onDone 末尾一次。真实流式接线只需替换 `DemoStreamingClient`。
3. **cancel 语义**：`IosCancellable.cancel()` → `engine.cancelSessionRequests(sessionId)`（generation 失效）
   + job.cancel；引擎 finally 仍会回调 onDone(ok=false)，Swift 包装按"取消也终态"处理。
4. **backup 两阶段**：运行中 SQLite 连接不能热换库，导出=文件复制+SQLite 头校验；恢复先写
   `rhodes.db.restore-pending`，下次冷启动在 `IosAppGraph.init` 打开 DB 之前换入（已实现
   `applyPendingRestoreIfAny`），UI 明确提示"重启后生效"。
5. **线程**：桥回调统一 Main 队列（`Dispatchers.Main`），DB/AI 在 `Dispatchers.Default`；
   `SettingsBridge` 同步（NSUserDefaults 线程安全）。
6. **`bootstrap()` 仅 demo 用**：`ensurePresetOperators()` + 无会话时 `getOrCreateSession("amiya")` +
   无 L3 时写一条记忆；幂等，正式 App 不调用。

## 7. 后续接线建议（Phase 3）

| 优先级 | 项 | 落点 |
|---|---|---|
| P0 | 真实 AI 供应商（Ktor Darwin `StreamingClient`） | 替换 `ChatEngineBridge.aiClient`；取消/超时沿用引擎 + T9 语义 |
| P0 | 真实提示词组装（T15 `PrivatePromptAssembler` + T24 记忆/知识库端口） | 替换 `DemoPromptAssembler` |
| P1 | 备份在线恢复（`BackupRestoreCoordinator` 24 步）/ 导出加 WAL checkpoint | `BackupBridge`（需 DB driver 句柄或导出器注入） |
| P1 | 会话列表增量/未读更新（Flow → 回调 + 版本号，或 `AsyncStream` 桥） | `SessionListBridge` 增加 observe 方法（本卡只做一次性 list，避免导出 Flow） |
| P2 | 记忆写入 / 管理 / 召回 | `MemoryBridge` 扩写 |
| P2 | 设置面扩展（按页面 pack 分组，避免一函数一 key） | `SettingsBridge` |
| P3 | 收敛导出面（`@HiddenFromObjC` / export 白名单），缓解 286MB 静态库与头文件膨胀 | shared build 配置 + 门面包 |

## 8. 复现命令

见 `ios-port/specs/ios-bridge-spec.md` §9。两次 framework 链接命令均可重复执行；
xcodebuild 需先有 Simulator Debug framework（`-derivedDataPath ios-port/iosApp-demo/DerivedData`）。
`--bridge-selftest` 在 `BridgeIntegrationView.task` 内执行；TabView 会重建 Tab 视图并重复触发 `.task`，
已用进程内 static guard 保证自检只跑一次（实测最后一次运行仅新增 1 组 user/ai 消息）。
`simctl install` 保留数据容器，所以 `chat_messages` id 会跨启动递增，这是预期行为。
