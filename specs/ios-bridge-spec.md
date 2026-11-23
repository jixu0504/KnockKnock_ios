# iOS 桥接规格 v1（T29：IosAppGraph 窄门面 + Swift 集成）

> 依据：`RhodesLink-iOS可行性分析报告.md` §5.6（桥接成本与窄门面设计）、`ios-port/specs/玻璃设计规范-v1.md`、`handoffs/impl-chat-engine.md` §5 门面草案。
> 状态：**v1 已实现并验证**（2026-11-23）。实现记录见 `ios-port/handoffs/impl-ios-graph.md`。

## 1. 目标与边界

KMP 默认 ObjC 导出不适合直接写 SwiftUI：`suspend` 变 completion handler、`Flow/StateFlow` 不自动变
`AsyncSequence`、sealed 无法穷尽 switch、默认参数生成多重载、泛型/函数类型受限。因此在
`shared/src/iosMain` 建一层**窄门面 `IosAppGraph`**：

- SwiftUI 只接触 5 个桥（chatEngine / sessions / settings / backup / memory），不直接碰 domain 对象；
- 所有异步能力用 **回调 + Cancellable** 暴露，Swift 侧包成 `AsyncStream` / `async` 函数；
- 所有跨语言数据是**简单类型 DTO**（String/Int/Long/Boolean/Double），不导出 Flow/StateFlow/sealed/data class；
- 门面层内部是真实实现（真实 DB、真实 Settings、真实 domain `ChatEngine` 状态机），AI 通道先接 fake。

```
SwiftUI (BridgeIntegrationView)
   │  只依赖 Swift struct/enum（SessionItem / MemoryItem / ChatStreamEvent / BackupStep）
AppGraphClient（Swift 包装层，AsyncStream + Cancellable + 错误映射）
   │  ObjC interop（Shared.framework 静态库）
IosAppGraph（Kotlin 窄门面）
   ├─ ChatEngineBridge ── domain.chat.ChatEngine（真实状态机/租约/落库）+ DemoStreamingClient（fake AI）
   ├─ SessionListBridge ─ SessionRepository（真实 SQLite）
   ├─ SettingsBridge ───── SettingsRepository → NSUserDefaults（真实持久化）
   ├─ MemoryBridge ─────── MemoryRepository（真实 SQLite，只读）
   └─ BackupBridge ─────── rhodes.db 文件导出 + 两阶段恢复
```

## 2. 文件与入口

Kotlin（新增，`RhodesLink/shared/src/iosMain/kotlin/com/rhodes/privatechat/shared/iosbridge/`，共 769 行）：

| 文件 | 内容 |
|---|---|
| `IosAppGraph.kt` | 图对象 + `makeAppGraph()` 工厂 + `bootstrap()` + `close()` |
| `ChatEngineBridge.kt` | 真实 `ChatEngine` 组装、fake 流式客户端、fake 提示词组装、打字机 delta |
| `SessionListBridge.kt` | 会话列表/单会话查询、`ensureSession()`（播种用） |
| `SettingsBridge.kt` | String/Bool/Int/Long 最小面 + 外观项 + 诊断快照 |
| `MemoryBridge.kt` | 长期印象 L3 列表 |
| `BackupBridge.kt` | 导出 + 两阶段恢复 + Foundation 文件工具 |
| `IosDtos.kt` | 4 个 DTO |
| `IosCancellable.kt` | 取消句柄 |

Swift（`ios-port/iosApp-demo/GlassShellDemo/`）：
`Bridge/AppGraphClient.swift`（226 行）、`Features/BridgeDemo/BridgeIntegrationView.swift`（397 行）、
`GlassShellDemoApp.swift` 新增「桥接」Tab（`--bridge-tab` / `--bridge-selftest` 启动参数）。

## 3. 门面 API 表

### 3.1 `IosAppGraph` → Swift `RhodesAppGraph`

以 `@ObjCName(swiftName = "RhodesAppGraph")` 命名；工厂是文件级函数
`makeAppGraph()`（Swift：`IosAppGraphKt.makeAppGraph()`）。

| Kotlin | Swift | 线程 | 说明 |
|---|---|---|---|
| `chatEngine(): ChatEngineBridge` | `chatEngine()` | main | 单实例 |
| `sessions(): SessionListBridge` | `sessions()` | main | 单实例 |
| `settings(): SettingsBridge` | `settings()` | main | 单实例 |
| `backup(): BackupBridge` | `backup()` | main | 单实例 |
| `memory(): MemoryBridge` | `memory()` | main | 单实例 |
| `databasePath(): String` | `databasePath`（方法） | main | Application Support/rhodes.db |
| `bootstrap(onDone: (Int, String) -> Unit)` | `bootstrap(onDone:)` | 回调 main | demo 播种（幂等），正式 App 不调用 |
| `close()` | `close()` | main | 取消协程作用域 |

> `(Int, String)` 回调在 Swift 侧是 `(KotlinInt, String) -> Void`（Kotlin Int 装箱）；
> 用 `count.intValue` 取值（见 §6 模板）。

### 3.2 `ChatEngineBridge` → Swift `ChatEngineBridge`

| Kotlin | Swift | 说明 |
|---|---|---|
| `send(text: String, onDelta: (String) -> Unit, onDone: (ChatResultDto) -> Unit): IosCancellable` | `send(text:onDelta:onDone:)` | 落库后按 2 字/20ms 回调 onDelta；结束回调 onDone 一次 |
| `cancel()` | `cancel()` | 幂等；引擎 generation 失效 + job 取消 |

**契约**：
- 单飞：同一时刻只保留一轮；新 `send` 会先取消上一轮。
- 回调一律 Main 队列；`onDone` 在成功/失败/取消时**都会**回调（失败看 `ok=false` + `error`）。
- 取消后仍会收到 `onDone(ok=false, error="本轮未生成可见回复")`（引擎 finally 语义）。
- `onDelta` 是**整包落库后**对可见文本的打字机分片，不是真实 token 流（沿用 lead R2 裁决）。

### 3.3 `SessionListBridge` / `MemoryBridge`

| Kotlin | Swift |
|---|---|
| `sessions().list(onResult: (List<SessionDto>, String?) -> Unit)` | `list(onResult: { sessions, error in })` |
| `sessions().find(sessionId: String, onResult: (SessionDto?, String?) -> Unit)` | `find(sessionId:onResult:)` |
| `sessions().pin(sessionId: String, pinned: Boolean, onDone: (String?) -> Unit)` | `pinSession(id:pinned:)`（T40） |
| `sessions().togglePin(sessionId: String, onDone: (String?) -> Unit)` | `togglePinSession(id:)`（T40） |
| `sessions().markRead(sessionId: String, onDone: (String?) -> Unit)` | `markSessionRead(id:)`（T40） |
| `sessions().markAllRead(onDone: (String?) -> Unit)` | `markAllSessionsRead()`（T40） |
| `sessions().hide(sessionId: String, onDone: (String?) -> Unit)` | `hideSession(id:)`（T40，写 settings.hiddenIds） |
| `sessions().hideAll(onDone: (String?) -> Unit)` | `hideAllSessions()`（T40） |
| `memory().list(onResult: (List<MemoryDto>, String?) -> Unit)` | `list(onResult:)` |

回调 Main 队列；`error == nil` 表示成功，`List<T>` 在 Swift 是 `[T]`。

### 3.4 `SettingsBridge`（同步，可在主线程直接调）

| Kotlin | Swift |
|---|---|
| `getString(key): String` / `putString(key, value)` | `getString(key:)` / `putString(key:value:)` |
| `getBoolean(key): Boolean` / `putBoolean(key, value)` | `getBoolean(key:)` / `putBoolean(key:value:)` |
| `getInt(key): Int` / `putInt(key, value)` | `getInt(key:)` / `putInt(key:value:)` |
| `getLong(key): Long` / `putLong(key, value)` | `getLong(key:)` / `putLong(key:value:)` |
| `appearanceSkin(): String` / `putAppearanceSkin(value)` | `appearanceSkin()` / `putAppearanceSkin(value:)` |
| `darkMode(): Boolean` / `putDarkMode(value)` | `darkMode()` / `putDarkMode(value:)` |
| `snapshot(): String` | `snapshot()` |
| `companion.DEMO_NOTE_KEY` | `SettingsBridge.companion.DEMO_NOTE_KEY` |

### 3.5 `BackupBridge`

| Kotlin | Swift |
|---|---|
| `databasePath(): String` | `databasePath()` |
| `pendingRestorePath(): String` | `pendingRestorePath()` |
| `hasPendingRestore(): Boolean` | `hasPendingRestore()` |
| `exportToFile(destinationPath: String, onProgress: (BackupProgressDto) -> Unit)` | `exportToFile(destinationPath:onProgress:)` |
| `restoreFromFile(sourcePath: String, onProgress: (BackupProgressDto) -> Unit)` | `restoreFromFile(sourcePath:onProgress:)` |

进度阶段：`prepare(0.0) → copy(0.3) → verify(0.9) → done(1.0)`，失败 `failed`；
恢复额外有 `staged(1.0)`（已暂存，冷启动换入）。

### 3.6 `IosCancellable` → Swift `IosCancellable`

`cancel()`（幂等）、`isCancelled()`。

## 4. DTO 表（Kotlin `iosbridge` → Swift 只读 class）

| DTO | 字段（全部简单类型） |
|---|---|
| `SessionDto` | `id: String`, `title: String`, `avatarId: String`, `unreadCount: Int`, `lastMessage: String`, `lastTime: Long`, `mode: String`, `isPinned: Boolean`（T40 新增） |
| `MemoryDto` | `id: Long`, `kind: String`（LONG_TERM/…）, `content: String`, `createdAt: Long` |
| `ChatResultDto` | `ok: Boolean`, `userMessageId: Long`, `aiMessageId: Long`, `text: String`, `error: String` |
| `BackupProgressDto` | `stage: String`, `progress: Double`, `message: String` |

**纪律**：DTO 用普通 `class`（非 data class），字段只增不改名；Swift 侧用 struct 重建（`SessionItem` 等），
不在 View 层持有 Kotlin 对象。

## 5. 命名映射（Kotlin → ObjC → Swift）

K/N 默认把类名加框架前缀（`Shared`），并在 `__attribute__((swift_name(...)))` 里给出 Swift 名。
`@ObjCName(swiftName = ...)` 用来消除难看的默认名并锁定 Swift 名：

| Kotlin | ObjC | Swift | 控制方式 |
|---|---|---|---|
| `IosAppGraph` | `SharedIosAppGraph` | `RhodesAppGraph` | `@ObjCName(swiftName="RhodesAppGraph")` |
| `makeAppGraph()`（顶层函数） | `+[SharedIosAppGraphKt makeAppGraph]` | `IosAppGraphKt.makeAppGraph()` | `@ObjCName(swiftName="makeAppGraph")` |
| `ChatEngineBridge` | `SharedChatEngineBridge` | `ChatEngineBridge` | `@ObjCName` |
| `SessionListBridge` / `SettingsBridge` / `MemoryBridge` / `BackupBridge` | `Shared*` | 同名 | `@ObjCName` |
| `IosCancellable` | `SharedIosCancellable` | `IosCancellable` | `@ObjCName` |
| `SessionDto` / `MemoryDto` / `ChatResultDto` / `BackupProgressDto` | `Shared*Dto` | `*Dto` | 默认（类名即 Swift 名） |
| `list(onResult:)` | `-listOnResult:` | `list(onResult:)` | 默认 |
| `getString(key)` | `-getStringKey:` | `getString(key:)` | 默认 |
| `send(text, onDelta, onDone)` | `-sendText:onDelta:onDone:` | `send(text:onDelta:onDone:)` | 默认 |
| `bootstrap(onDone)` | `-bootstrapOnDone:` | `bootstrap(onDone:)`；闭包 `(KotlinInt, String)` | 默认 |

规则：Kotlin 首个参数名并入 selector；返回 `Unit` → `void`；`String?` → `NSString * _Nullable`；
`List<T>` → `NSArray<T *> *` → Swift `[T]`；Kotlin `Int` 作为**函数类型参数**时装箱为 `KotlinInt`
（普通方法返回值仍是 `int32_t`）。

## 6. Swift 包装模板

### 6.1 回调 → `async`

```swift
func loadSessions() async -> Result<[SessionItem], BridgeError> {
    await withCheckedContinuation { continuation in
        graph.sessions().list { dtos, error in
            if let error { continuation.resume(returning: .failure(BridgeError(message: error))) }
            else {
                let items = dtos.map(SessionItem.init(dto:))   // SessionDto -> Swift struct
                continuation.resume(returning: .success(items))
            }
        }
    }
}
```

`bootstrap` 的 `(Int, String)` 回调：

```swift
graph.bootstrap { count, error in
    let value = Int(count.intValue)   // KotlinInt（NSNumber）
    continuation.resume(returning: (value, error.isEmpty ? nil : error))
}
```

### 6.2 回调 + Cancellable → `AsyncStream`

```swift
func chatStream(text: String) -> AsyncStream<ChatStreamEvent> {
    AsyncStream { continuation in
        let cancellable = graph.chatEngine().send(
            text: text,
            onDelta: { delta in continuation.yield(.delta(delta)) },
            onDone: { dto in
                continuation.yield(.done(ChatRoundResult(ok: dto.ok, text: dto.text,
                                                         error: dto.error,
                                                         aiMessageId: dto.aiMessageId)))
                continuation.finish()
            }
        )
        chatCancellable = cancellable
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.chatCancellable?.cancel() }
        }
    }
}

// 消费端
for await event in client.chatStream(text: text) {
    switch event {
    case .delta(let piece): streamingText += piece
    case .done(let result): chatStatus = result.ok ? "完成" : "失败：\(result.error)"
    }
}
```

同一模板用于 `BackupBridge` 的 `exportToFile(destinationPath:onProgress:)` → `AsyncStream<BackupStep>`，
`stage == done/failed` 时 `finish()`。

### 6.3 错误映射

Kotlin 侧不抛业务异常给 Swift（门面全部把错误收成 `String?` 参数或 `ChatResultDto.error`），
Swift 包装统一映射为 `struct BridgeError: LocalizedError { let message: String }`。
不要 try/catch ObjC 的 `KotlinThrowable`（门面保证不跨边界抛）。

## 7. Xcode 集成配置（已写入 `ios-port/iosApp-demo/GlassShellDemo.xcodeproj/project.pbxproj`）

静态 `Shared.framework`（`isStatic = true`），不 embed，仅链接：

```
FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*] = "$(inherited) $(SRCROOT)/../../RhodesLink/shared/build/bin/iosSimulatorArm64/debugFramework";
FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]        = "$(inherited) $(SRCROOT)/../../RhodesLink/shared/build/bin/iosArm64/releaseFramework";
OTHER_LDFLAGS = "$(inherited) -framework Shared -lsqlite3";
```

- `-lsqlite3`：静态 framework 里 SQLDelight native driver（sqliter）使用系统 sqlite3，静态库不携带
  依赖的 linker 选项，必须由宿主补 `libsqlite3.tbd`。**实测缺它会在链接期报 `_sqlite3_*` undefined。**
- 保留 DSH 沙箱适配 flags：`ENABLE_USER_SCRIPT_SANDBOXING = YES`、
  `OTHER_SWIFT_FLAGS = "$(inherited) -disable-sandbox"`（本工程无 Run Script，保留供后续嵌入脚本）。
- 设备构建需先产出 `linkReleaseFrameworkIosArm64`（本卡已产出）。
- 已知告警：链接时 `libicu.icudtl_dat.o` 提示 built for iOS 18.5 vs linked 18.0（K/N 自带 ICU 的
  min version）。模拟器运行无影响；若要消警把 demo `IPHONEOS_DEPLOYMENT_TARGET` 提到 18.5。

## 8. 已知限制（Phase 3 接线清单）

| # | 限制 | Phase 3 接线建议 |
|---|---|---|
| 1 | AI 通道是 `DemoStreamingClient`（确定性 fake，不联网） | 用 T9 已验证的 Ktor Darwin `StreamingClient` 实现替换 `ChatEngineBridge.aiClient`；取消语义已由引擎 generation + job.cancel 保证 |
| 2 | 提示词是 `DemoPromptAssembler`（system + user 历史），未接 T15/T24 记忆/知识库管线 | 注入真实的 `PrivatePromptAssembler` + `PrivateContextProviders`（记忆 V2 / 知识库 / 模板） |
| 3 | `onDelta` 是落库后的打字机分片，不是真实 token 流 | 若产品要真流式：在 `StreamingClient` 侧加 delta 通道并让桥转发，同时保留整包落库 |
| 4 | 备份导出是文件级复制，未做 WAL checkpoint / 一致性快照 | 导出前 `PRAGMA wal_checkpoint(FULL)`（需 driver 句柄）或接 `BackupRestoreCoordinator` 分页导出 |
| 5 | 恢复是两阶段（先 `rhodes.db.restore-pending`，冷启动在打开 DB 前换入），运行期不换库 | 进 App 后提示"需重启生效"；正式版接 `BackupRestoreCoordinator.restore()` 24 步在线恢复 |
| 6 | `memory()` 只读（L3 长期印象） | 接记忆 V2 管线的写入/召回/管理页 |
| 7 | `bootstrap()` 是 demo 播种（预设角色 + 一条 L3 记忆），会写真实 DB | 正式 App 不调用；改用真实初始化流程 |
| 8 | K/N 默认导出整个 shared 模块的头文件（domain 层也可见） | 门面纪律靠使用约定；Phase 3 可用 `@HiddenFromObjC` / export 白名单收敛 |
| 9 | 一个进程一份 graph；`makeAppGraph()` 每次新建 DB 连接 | Swift 侧单例（`AppGraphClient.shared`），不要重复调用 |
| 10 | Demo 玻璃卡片在 iOS 27.2 模拟器上 tint 偏实色 | T12 组件行为，非本卡回归；视觉验收时复核 GlassPanel tint |

## 9. 验证命令

```bash
# framework
cd RhodesLink && source ../ios-port/toolchain/env.sh
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew --no-daemon :shared:linkReleaseFrameworkIosArm64

# demo
cd ios-port/iosApp-demo
xcodebuild -project GlassShellDemo.xcodeproj -scheme GlassShellDemo -configuration Debug \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO build

xcrun simctl boot "iPhone 17"
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/GlassShellDemo.app
xcrun simctl launch booted com.rhodeslink.demo.GlassShellDemo --bridge-tab --bridge-selftest
xcrun simctl spawn booted log show --last 5m --predicate 'process == "GlassShellDemo"' --style compact | grep IosBridge
xcrun simctl io booted screenshot evidence/t29-bridge-selftest.png
```
