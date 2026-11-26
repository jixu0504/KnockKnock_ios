# iOS 真壳规格 v1（T33：RhodesLink/iosApp 工程 / 路由 / DesignSystem / 桥接接线）

> 状态：**v1 已实现并验证**（2026-11-23）：xcodebuild 通过；iPhone 17（iOS 27.2 模拟器）安装启动、4 Tab 可切换、聊天页展示 shared 真实数据并完成一轮真实发送（落库）、深链路由命中、浅/深色与 Dynamic Type 响应。
> 实现记录与证据：`ios-port/handoffs/impl-ios-shell.md`、`ios-port/handoffs/evidence/t33-*`。
> 上游依据：`玻璃设计规范-v1.md`（T8）、`timeline-2000-spec.md`（T12）、`ios-bridge-spec.md`（T29）、`handoffs/impl-ios-graph.md`、`handoffs/ios-shell-design.md`。

---

## 1. 目标与边界

Phase 3 启动任务：把 Phase 0/1/2 的「玻璃壳 Demo + IosAppGraph 桥接 Demo + 2,000 条时间线原型」收敛成一个**可编译、可运行的真 iOS App 壳工程**（正式产物目录，不是 Demo）：

- SwiftUI 4 Tab（聊天 / 角色 / 记忆 / 设置）+ 每 Tab 独立 `NavigationStack` + 统一路由表 + `rhodes://` 深链；
- 执行环境：shared `RhodesAppGraph` 单例（`IosAppGraph.makeAppGraph`）→ `AppGraphClient` → SwiftUI；
- DesignSystem 从 Demo 迁移（玻璃 token / GlassPanel / AmbientBackground / GlassInputBar / 气泡）；
- 聊天页骨架：LazyVStack 时间线（沿用 T12 结论）+ 状态条 + 玻璃输入栏 + `ChatEngineBridge` send/cancel + onDelta 打字机；
- 角色 / 记忆 / 设置骨架页（数据来自 shared 真实 DB / 真实 NSUserDefaults）；
- 可访问性（降低透明度 / 增强对比度 / 减弱动态 / Dynamic Type）与深浅色跟随系统。

**不在本卡**：真实 AI 供应商、提示词正式组装、记忆写入/管理、备份 UI、群聊、语音/素材、52 路由全量页面。这些在路由表中以 `.placeholder` 占位。

---

## 2. 工程结构（`RhodesLink/iosApp/`）

```
RhodesLink/iosApp/
├── RhodesLink.xcodeproj/
│   ├── project.pbxproj                        # objectVersion 77 + PBXFileSystemSynchronizedRootGroup
│   └── xcshareddata/xcschemes/RhodesLink.xcscheme
├── Config/Info.plist                          # 手写：CFBundleURLTypes(rhodes) + UILaunchScreen + SceneManifest
├── .gitignore                                 # DerivedData/ tmp/ build-*.log
├── DerivedData/                               # 工作区内构建产物（gitignore）
├── tmp/                                       # 工作区内 TMPDIR（gitignore）
└── RhodesLink/                                # ← 同步组源目录（新增文件免改 pbxproj）
    ├── RhodesLinkApp.swift                    # @main + RootTabView + 4 个 Tab 根 + iOS 26 TabBar 最小化
    ├── App/
    │   ├── AppRouter.swift                    # AppTab / Route / NavigationPath / 深链解析
    │   ├── AppEnvironment.swift               # AppGraph 单例 + SettingsStore（外观真实读写）
    │   └── SelfTest.swift                     # --selftest 自检 + --open-url 钩子 + 日志落盘
    ├── Bridge/AppGraphClient.swift            # Shared.framework Swift 包装（唯一接触 Kotlin 类型处）
    ├── DesignSystem/
    │   ├── GlassTokens.swift                  # 深浅色自适应 token（自我迁移+新增 surface/stroke/vignette）
    │   ├── GlassPanel.swift                   # iOS 26 glassEffect / iOS 18 Material + 可访问性降级
    │   ├── AmbientBackground.swift            # 渐变+光斑+立绘占位+暗角，视差与降级
    │   ├── GlassInputBar.swift                # 输入栏（iOS 26 GlassEffectContainer / iOS 18 Material）
    │   ├── MessageBubble.swift                # 气泡（非玻璃）+ 流式光标 + 系统提示 + AvatarBadge
    │   └── CardSurface.swift                  # 非玻璃卡片/分组标题/空状态
    └── Features/
        ├── Chat/{ChatListView,ChatView,ChatViewModel,ChatModels}.swift
        ├── Roles/{RolesView,RoleDetailView}.swift
        ├── Memory/MemoryListView.swift        # 含 MemoryDetailView
        ├── Settings/SettingsView.swift
        └── Common/RouteView.swift             # Route → 页面 + PlaceholderView
```

规模：20 个 Swift 文件 / 3,187 行；pbxproj 329 行；Info.plist 57 行。

---

## 3. Xcode 工程配置

| 项 | 值 | 说明 |
|---|---|---|
| 工程格式 | `objectVersion = 77` + `preferredProjectObjectVersion = 77` | 同步根组方案（同 Demo），增删源文件无需维护 pbxproj |
| 源同步组 | `PBXFileSystemSynchronizedRootGroup` path=`RhodesLink` | Sources/Frameworks/Resources 三个 phase 文件列表留空 |
| 部署目标 | `IPHONEOS_DEPLOYMENT_TARGET = 18.0` | iOS 18 为下限；iOS 26 玻璃 API 全部 `@available(iOS 26.0, *)` 分支 |
| Bundle id | `com.rhodeslink.app` | 占位；正式签名前由 lead 定 |
| Swift | `SWIFT_VERSION = 5.0`，`OTHER_SWIFT_FLAGS = "$(inherited) -disable-sandbox"` | 后者沿用 Demo 的 DSH 沙箱适配（非沙箱 CI 可移除） |
| Info.plist | 手写 `Config/Info.plist`，`GENERATE_INFOPLIST_FILE = NO` | 需要 `CFBundleURLTypes`（`INFOPLIST_KEY_*` 无法生成）；`UILaunchScreen`/SceneManifest 一并手写 |
| Shared.framework | 路径条件化（相对 `$(SRCROOT)` = `iosApp/`）：sim→`$(SRCROOT)/../shared/build/bin/iosSimulatorArm64/debugFramework`，device→`$(SRCROOT)/../shared/build/bin/iosArm64/releaseFramework`；`OTHER_LDFLAGS = "$(inherited) -framework Shared -lsqlite3"` | 与 T29 Demo 一致；`-lsqlite3` 必需（静态 framework 用系统 SQLite） |
| 模拟器架构 | `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` | **K/N 产物只有 arm64 slice**（`lipo -info` 实测）；generic Simulator destination 默认会造 x86_64 并链接失败，故排除（Apple Silicon 主机限定）。真机走 `iosArm64/releaseFramework` |
| 签名 | `CODE_SIGN_STYLE = Automatic`，验证构建用 `CODE_SIGNING_ALLOWED=NO` | 真机需开发者团队 |

`Info.plist` 关键内容：
- `CFBundleURLTypes` → scheme **`rhodes`**（`CFBundleURLName = com.rhodeslink.app.deeplink`）；
- `UILaunchScreen`（空 dict，系统生成启动屏）；`UIApplicationSceneManifest`（`UIApplicationSupportsMultipleScenes=false`）；
- 竖屏为主（iPad 四方向）。

---

## 4. 路由表

### 4.1 Tab（根，`AppTab`）

| Tab | rawValue | 根视图 | 数据源 |
|---|---|---|---|
| 聊天 | `chat` | `ChatListView` | `SessionListBridge.list`（真实 SQLite） |
| 角色 | `roles` | `RolesView` | 同上（SessionDto → 角色卡，见 §8 限制 3） |
| 记忆 | `memory` | `MemoryListView` | `MemoryBridge.list`（L3 长期印象，只读） |
| 设置 | `settings` | `SettingsView` | `SettingsBridge` + `RhodesAppGraph.databasePath` |

每个 Tab 独立 `NavigationPath`（`AppRouter.chatPath/rolesPath/memoryPath/settingsPath`），四个 Tab 的 `navigationDestination(for: Route.self)` 都渲染 `RouteView(route:)`。

### 4.2 `Route` 枚举与覆盖状态

| Route | 页面 | 状态 |
|---|---|---|
| `.chat(sessionId:)` | `ChatView` | ✅ 已实现（聊天页骨架） |
| `.roleDetail(sessionId:)` | `RoleDetailView` | ✅ 已实现（角色详情骨架） |
| `.memoryDetail(id:)` | `MemoryDetailView` | ✅ 已实现（只读详情） |
| `.settingsAppearance` | `PlaceholderView` | 🟡 路由已注册、页面占位 |
| `.settingsAccessibility` | `PlaceholderView` | 🟡 路由已注册、页面占位 |
| `.placeholder(title:detail:)` | `PlaceholderView` | 🟡 兜底占位（群聊 / 备份 / 提示词 / 语音 / 素材 / 未知深链） |

> 全量 52 路由的映射见 `ios-port/checklist/功能对照清单-v1.md`；本卡只覆盖 A1/A2 主链路 + 设置主页，其余留占位并在后续批次逐个替换。

### 4.3 深链（`rhodes://`）

| URL | 行为 |
|---|---|
| `rhodes://chat/{sessionId}` | 切到聊天 Tab → push 聊天页（会话 id 不存在时页面显示"会话不存在"空态） |
| `rhodes://chat` | 聊天列表 |
| `rhodes://role/{sessionId}` / `rhodes://roles` | 角色 Tab → push 角色详情 / 角色列表 |
| `rhodes://memory/{id}` / `rhodes://memory` | 记忆 Tab → push 记忆详情 / 列表 |
| `rhodes://settings/{appearance\|accessibility}` / `rhodes://settings` | 设置 Tab → 子页（占位）/ 设置主页 |
| `rhodes://group/{id}` | 聊天 Tab → 群聊占位路由 |
| 其它 host | 当前 Tab → `.placeholder`（不崩、可扩展） |

人口：`RootTabView.onOpenURL { router.handle(url:) }`。`handle` 记录 `lastHandledLink` / `linkHistory` 并写自检日志。

> **验证方式说明**：iOS 26 对 `simctl openurl` 打开自定义 scheme 会弹系统确认框「在“RhodesLink”中打开？取消/打开」，模拟器命令行无法点击；因此自动化验证用启动参数 `--open-url <url>` 走**同一个** `AppRouter.handle(url:)` 代码路径（`SelfTestDriver.handleLaunchURLIfNeeded`）。系统确认框本身也作为"scheme 已注册"证据截图 `evidence/t33-deeplink-system-confirm.png`。人工点在模拟器/真机上是正常流程。

---

## 5. 执行环境与门面接线

```
SwiftUI Views
   │ 只接触 Swift struct（SessionItem / MemoryItem / ChatRoundResult …）
AppGraphClient.shared（@MainActor，单例）
   │ IosAppGraphKt.makeAppGraph() → RhodesAppGraph
Shared.framework（T29 窄门面：ChatEngineBridge / SessionListBridge / SettingsBridge / MemoryBridge / BackupBridge）
```

| Swift 包装（`Bridge/AppGraphClient.swift`） | shared 门面 | 线程 |
|---|---|---|
| `bootstrap() async -> (count, error)` | `RhodesAppGraph.bootstrap { KotlinInt, String }` | 回调 main |
| `loadSessions() async -> Result<[SessionItem], BridgeError>` | `sessions().list` | 回调 main |
| `findSession(id:) async -> SessionItem?` | `sessions().find(sessionId:)` | 回调 main |
| `loadMemories() async -> Result<[MemoryItem], BridgeError>` | `memory().list` | 回调 main |
| `settingsString/setSettingsString/setDarkMode/settingsSnapshot` | `settings()` 同步方法 | main（同步） |
| `sendChat(text:onDelta:onDone:) -> ChatSendHandle` | `chatEngine().send` | 回调 main |
| `cancelChat()` / `ChatSendHandle.cancel()` | `chatEngine().cancel()` / `IosCancellable.cancel()` | main |
| `databasePath` | `databasePath()` | main |

约定：
- `IosCancellable` 被包成 `ChatSendHandle`，Feature 层不出现 Kotlin 类型；
- 启动时 `AppEnvironment.start()` 幂等：读会话 → **若为空调用一次 `bootstrap()` 播种**（预设角色 + `session_amiya` + 一条 L3 记忆）。这是 Phase 3 壳的临时行为（正式产品由 onboarding/导入取代），日志标注 `bootstrap`；
- `AppEnvironment.settings`（`SettingsStore`）负责外观：`appearance=system|light|dark` 写入 `ios_shell_appearance` + `dark_mode`（shared `SettingsBridge`），根视图 `.preferredColorScheme(appearance.colorScheme)` 实时生效；
- 界面同时保留 Swift 侧的 DB 路径 / 计数 / `settings.snapshot()` 读回，作为"真实数据"自证。

---

## 6. DesignSystem（自 Demo 迁移 + Phase 3 调整）

| 组件 | 迁移差异 |
|---|---|
| `GlassTokens` | **全部颜色改为深浅色自适应**（`Color.rhodesHex(light:dark:)`）；`glassTint` 不再默认套在所有面板（Demo 全橙在真实页过重）；新增 `surface/surfaceStrong/strokeSubtle/vignette/bubbleSystem` |
| `GlassPanel` | `tint` 改为可选（默认 nil = 系统玻璃原色）；降低透明度/增强对比度/粗体文字降级逻辑保留 |
| `AmbientBackground` | 深浅色 token；立绘占位仅深色显示；视差/光斑/暗角沿用可访问性降级 |
| `GlassInputBar` | 增加 `isStreaming/onCancel`：发送中按钮变「停止」→ `ChatEngineBridge.cancel()`；玻璃按钮 ≤3 |
| `MessageBubble` | 非玻璃气泡 + 流式光标（减弱动态时静止）+ 系统提示胶囊 + `AvatarBadge` |
| `CardSurface` | 新增：非玻璃卡片 / 分组标题 / 空状态（一屏玻璃 ≤5 的落地手段） |

**玻璃纪律**（`玻璃设计规范-v1.md` 设计原则 2/3）：每屏 ≤5 玻璃元素；长文本/气泡不用玻璃；玻璃上文字 ≥17pt（`.body`）。各页实际计数：聊天页 2（状态条 + 输入栏，输入栏内含 2 个玻璃按钮）、列表/角色/记忆页 1（头部卡）、设置页 2（执行环境 + 外观卡）。

---

## 7. 聊天页骨架（`Features/Chat/`）

- **时间线**：`ScrollView + LazyVStack(spacing: 10)`；消息 id 为**稳定 Int**（T12 结论），`scrollTo` 精确定位；气泡非玻璃、正文 `.body`、最大宽 320pt；系统提示居中胶囊。T12 实测 2,000 条 59.62 fps / P95 59.81（`handoffs/perf-result-lazyvstack.json`），本卡沿用同一结构。
- **2,000 条冒烟开关**：`--stress-2000` 启动参数在聊天页注入 2,000 条确定性异构消息（`TimelineStress`，固定 LCG 种子），用于壳工程内回归（截图 `t33-chat-stress-2000.png`）。
- **头部状态条**：`GlassPanel(.clear)` 内含头像 / 会话名 / 在线点 / 模式 / 未读；会话元数据来自 `findSession`（shared DB）。
- **输入栏**：`GlassInputBar` → `ChatViewModel.send()` → `ChatEngineBridge.send(text:onDelta:onDone:)`；`onDelta` 追加到占位气泡（打字机，2 字/20ms）；`onDone` 用最终文本替换并刷新会话 `lastMessage`；发送中显示「停止」→ `cancel()`（引擎 generation 失效，忽略取消后的 onDone）。
- **会话摘要**：shared 门面**没有消息历史桥**，时间线初始只展示本次会话的发送流；历史消息读取见 §10 限制 1。

---

## 8. 角色 / 记忆 / 设置

- **角色列表**：`SessionDto` → 角色卡（头像首字 / 名称 / 模式 / 最近消息）→ 详情页（资料卡 + 最近对话 + 「开始聊天」跳聊天 Tab + 语音/素材/人设占位行）。
- **记忆列表**：`MemoryBridge` L3 长期印象（kind 标签 / 内容 / 时间）→ 详情页只读；写入/管理管线待后续批次。
- **设置主页**：
  - 执行环境卡：Shared.framework 连接、DB 路径、会话/记忆计数、`settings.snapshot()` 读回；
  - 外观：跟随系统/浅色/深色 segmented，真实写 `SettingsBridge`（实测 `dark=false;skin=rhodes_night` 读回）；
  - 字体与可访问性：Dynamic Type 实时值、降低透明度/增强对比度/减弱动态实时状态、大字号预览、跳系统设置；
  - SettingsBridge 真实读写：写入测试值 → 立即读回显示；
  - 二级路由：外观/可访问性子页（占位）、备份与恢复、提示词设置（占位）；
  - 关于：bundle/版本/构建/最近深链/设计原则。

---

## 9. 可访问性与外观

| 能力 | 实现 |
|---|---|
| 降低透明度 | `GlassPanel` 退为实色材质；`AmbientBackground` 光斑/立绘淡出；输入栏按钮改 `.bordered(.Prominent)` |
| 增强对比度 | 描边 `glassStrokeStrong`；光斑更实、模糊更小、暗角加深 |
| 减弱动态效果 | 视差系数归零；滚动动画、Tab 切换动画退化为直接切换；流式光标不再闪烁 |
| Dynamic Type | 全部语义字号（`.title2/.headline/.body/.caption`）；输入栏字号 `.body`；无固定高度文本容器；设置页显示当前档位（xS…AX5） |
| 深色 / 浅色 | 跟随系统（默认 `跟随系统`）；token 深浅两套；设置页可强制浅/深（`preferredColorScheme`）；实测浅色与深色截图各一份 |
| 无障碍标签 | 气泡/按钮/角色行/会话行均有 `accessibilityLabel`/`Hint`；系统提示合并朗读；表情/头像装饰 `accessibilityHidden` |

---

## 10. 构建与运行（复现命令）

```bash
cd RhodesLink/iosApp

# 1) 构建（工作区内 DerivedData；模拟器 arm64）
pgrep -fl xcodebuild        # 并发纪律：先确认无并行构建
TMPDIR="$PWD/tmp" xcodebuild build \
  -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$PWD/DerivedData" \
  CODE_SIGNING_ALLOWED=NO
# → ** BUILD SUCCEEDED **

# 2) 安装 + 启动（模拟器 iPhone 17 / iOS 27.2）
xcrun simctl boot "iPhone 17"
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch booted com.rhodeslink.app --selftest          # 自检（日志落盘 Documents/ios-shell-selftest.log）
xcrun simctl io booted screenshot ../handoffs/evidence/t33-chat-selftest.png

# 3) 四 Tab / 深链 / 2,000 条 / 深色（截图脚本）
xcrun simctl launch booted com.rhodeslink.app --tab roles
xcrun simctl launch booted com.rhodeslink.app --tab memory
xcrun simctl launch booted com.rhodeslink.app --tab settings
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://role/session_amiya"
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://settings/appearance"
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://chat/session_amiya" --stress-2000
xcrun simctl ui booted appearance dark   # 深色截图后恢复 light

# 4) 取出自检日志（证据）
CONTAINER=$(xcrun simctl get_app_container booted com.rhodeslink.app data)
cat "$CONTAINER/Documents/ios-shell-selftest.log"
```

启动参数一览：`--selftest`（自检）、`--tab chat|roles|memory|settings`（初始 Tab）、`--open-url <url>`（走 AppRouter.handle 的深链验证钩子）、`--stress-2000`（聊天页 2,000 条本地消息）。

---

## 11. 验证结果（iPhone 17 / iOS 27.2 / Xcode 27.2）

| 验收项 | 结果 | 证据 |
|---|---|---|
| xcodebuild 编译 | ✅ BUILD SUCCEEDED | `evidence/t33-build.log` |
| 模拟器安装启动 | ✅ 无崩溃 | 自检日志 + 截图 |
| 4 Tab 可切换 | ✅ 自检程序化切换 + 四张截图 | `t33-tab-roles/memory/settings.png`、`t33-chat-selftest.png` |
| 聊天页展示 shared 数据 | ✅ 会话名/最近消息来自 DB；发送后 `lastMessage` 被引擎更新 | `t33-chat-selftest.png`；日志 `chat done ok=true aiId=4`、`sessions after send ...` |
| 深链可打开 | ✅ `handle` 命中并 push 页面（`handled=true`） | `t33-deeplink-role-detail.png`、`t33-deeplink-settings-appearance.png`、自检日志 |
| 2,000 条时间线 | ✅ LazyVStack 渲染 + 自动滚到底 | `t33-chat-stress-2000.png` |
| 浅色 / 深色 | ✅ 两套截图 | `t33-chat-selftest.png`（浅）/ `t33-chat-dark.png`（深） |
| 设置真实读写 | ✅ `readBack=RhodesLink iOS 真壳写入 @04:27:18` | 自检日志 / 设置页截图 |
| 真实 DB 落库 | ✅ `chat_messages` 累计 4 行（两轮自检 user+ai）；`chat_sessions.last_message` 已更新 | `sqlite3` 直查（见 handoff） |

---

## 12. 已知限制（Phase 3 后续）

1. **无消息历史桥**：shared 门面只暴露会话列表/单会话，聊天页时间线只显示本次发送流 + 会话 `lastMessage` 摘要；历史消息需要新增 `MessageHistoryBridge`（分页 + 稳定 id）。
2. **角色数据来自会话**：无 `RoleBridge/operator repository` 导出门面；角色列表由 `SessionDto` 推导，立绘/人设/素材未接。
3. **fake AI 通道**：`ChatEngineBridge.DemoStreamingClient`（120ms 首响 + 固定回复 JSON）+ `DemoPromptAssembler`；真实供应商（Ktor Darwin）与 T15/T18 提示词管线待接。
4. **记忆只读**：`MemoryBridge` 只读 L3；写入/管理/召回（Memory V2）未接。
5. **备份 UI 未接**：`BackupBridge` 已就绪（导出 / 两阶段恢复），设置页只有占位路由。
6. **模拟器仅 arm64**：K/N 产物无 x86_64 slice，Intel Mac 不支持；真机走 `iosArm64/releaseFramework`（需签名）。
7. **iOS 26 观感未评审**：真玻璃折射/容器融合/Tab Bar 最小化只在本机模拟器（iOS 27.2）目测，仍属 Gate 3 人类签字项。
8. **浅色 token 需视觉复评**：浅色描边/光斑/对比度按 4.5:1 目标调过，但未经人类评审。
9. **大字号极端档（AX5）**：布局用语义字号 + `lineLimit` 规避溢出，未逐页实截 AX5。
10. **深链系统确认框**：iOS 26 `simctl openurl` 需人工点「打开」；自动化走 `--open-url` 同一代码路径（见 §4.3）。
11. **数据容器**：`simctl install` 保留数据容器，ID 跨启动累计（预期行为）。
12. **构建沙箱适配**：`-disable-sandbox` 与工作区内 `TMPDIR/-derivedDataPath` 是 DSH 沙箱环境需要；非沙箱 CI 可移除 `-disable-sandbox`。

---

## 13. 后续接线建议（Phase 3 下一批）

| 优先级 | 项 | 落点 |
|---|---|---|
| P0 | 消息历史桥（分页读 `chat_messages`）+ 聊天页时间线接真实历史 | `shared/src/iosMain/iosbridge/MessageHistoryBridge.kt` + `ChatViewModel.load()` |
| P0 | 真实 AI 供应商 + 提示词管线 | 替换 `DemoStreamingClient` / `DemoPromptAssembler` |
| P1 | 角色桥（operators/立绘/人设）+ 角色页正式化 | 新增 `RoleBridge`，`RolesView` 换数据源 |
| P1 | 备份导出/恢复 UI | `BackupBridge` → 设置页正式子页 |
| P1 | 会话未读/增量刷新（Flow → 回调或 AsyncStream） | `SessionListBridge.observe` |
| P2 | 记忆管理（写/删/召回） | `MemoryBridge` 扩展 |
| P2 | 群聊路由正式页 | `Route` 增加 `.group(...)`，替换占位 |
| P3 | 收敛 shared 导出面（`@HiddenFromObjC` / 白名单） | 286MB 静态库与头文件膨胀 |
