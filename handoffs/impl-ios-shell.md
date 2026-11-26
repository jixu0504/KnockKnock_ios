# T33 实现 handoff：iOS 真壳工程（Phase 3 启动）

> 代理：impl-ios-shell（task-33）｜规格：`ios-port/specs/ios-shell-spec.md`（本卡产出，v1）
> 状态：**实现完成，xcodebuild 通过，iPhone 17 / iOS 27.2 模拟器实跑通过**（4 Tab / shared 数据 / 聊天发送落库 / 深链路由 / 深浅色）。
> git：**不提交**，`RhodesLink/iosApp/` 为全新未跟踪目录（lead 统一提交）。

## 1. 写域确认

- 新增：`RhodesLink/iosApp/`（工程 + 20 个 Swift 文件 3,187 行 + Config/Info.plist + scheme；内含 gitignore 的 `DerivedData/`、`tmp/`）。
- 新增：`ios-port/specs/ios-shell-spec.md`、`ios-port/handoffs/impl-ios-shell.md`、`ios-port/handoffs/evidence/t33-*`。
- 只读参考、未改动：`ios-port/iosApp-demo/`（Demo 保持原样）、shared/、app/。
- `git status` 中与其它代理相关的 `M/??`（shared/src/... platform、backup/wire 等）**非本卡产生**；本卡唯一新增为 `?? iosApp/`。

## 2. 交付物清单

| 路径 | 说明 |
|---|---|
| `RhodesLink/iosApp/RhodesLink.xcodeproj/project.pbxproj` | objectVersion 77 + `PBXFileSystemSynchronizedRootGroup`；iOS 18.0；Shared.framework SDK 条件路径；`-lsqlite3`；`-disable-sandbox`；`EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64` |
| `RhodesLink/iosApp/RhodesLink.xcodeproj/xcshareddata/xcschemes/RhodesLink.xcscheme` | 共享 scheme（`xcodebuild -scheme RhodesLink`） |
| `RhodesLink/iosApp/Config/Info.plist` | `CFBundleURLTypes`（rhodes scheme）+ `UILaunchScreen` + SceneManifest（手写，`GENERATE_INFOPLIST_FILE=NO`） |
| `RhodesLink/iosApp/RhodesLink/RhodesLinkApp.swift` | `@main` + 4 Tab + 每 Tab NavigationStack + `onOpenURL` + iOS 26 TabBar 最小化 |
| `.../App/AppRouter.swift` | `AppTab` / `Route` / 4 条 NavigationPath / `rhodes://` 深链解析 / 未注册 host 兜底占位 |
| `.../App/AppEnvironment.swift` | AppGraph 单例 + `SettingsStore`（外观真实读写、`settings.snapshot()` 读回） |
| `.../App/SelfTest.swift` | `--selftest` 自检（桥接/深链/发送/四 Tab）+ 日志落盘 + `--open-url` 钩子 |
| `.../Bridge/AppGraphClient.swift` | Shared.framework Swift 包装（Kotlin 类型只在此文件/`ChatSendHandle` 出现） |
| `.../DesignSystem/*`（6 文件） | GlassTokens（深浅自适应）/ GlassPanel / AmbientBackground / GlassInputBar（含停止）/ MessageBubble / CardSurface |
| `.../Features/Chat/*`（4 文件） | 聊天列表 + 聊天页 + ViewModel（send/cancel/onDelta 打字机）+ 2,000 条压力数据 |
| `.../Features/Roles/*`、`.../Memory/*`、`.../Settings/*`、`.../Common/RouteView.swift` | 角色列表/详情、记忆列表/详情、设置主页、Route→页面 + 占位页 |
| `ios-port/specs/ios-shell-spec.md` | 工程结构 / 路由表 / 门面接线 / 设计系统 / 构建命令 / 验证 / 已知限制 |
| `ios-port/handoffs/evidence/` | `t33-build.log`、`t33-selftest.log`、9 张截图（见 §5） |

## 3. 构建

```bash
cd RhodesLink/iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData" \
  CODE_SIGNING_ALLOWED=NO
# → ** BUILD SUCCEEDED **（arm64，SDK 27.2，部署目标 18.0；日志 evidence/t33-build.log）
```

迭代记录（3 轮内收敛）：
1. `IosCancellable` 泄漏到 Feature 层 → 新增 `ChatSendHandle`（包装 `IosCancellable`），SwiftUI 层不再出现 Kotlin 类型；
2. generic Simulator destination 造 x86_64 slice，而 K/N `Shared.framework` 只有 arm64（`lipo -info` 实测）→ 链接失败；加 `EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64` 后通过；
3. `--open-url` 自动化钩子补齐（见 §6）。

## 4. 运行验证（iPhone 17 模拟器，iOS 27.2）

```bash
xcrun simctl boot "iPhone 17"
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch booted com.rhodeslink.app --selftest
```

自检日志（`evidence/t33-selftest.log`，节选，04:27 最后一次运行）：

```
04:27:18.016 sessions list loaded count=1 first=session_amiya
04:27:18.026 environment ready db=.../Library/Application Support/rhodes.db sessions=1 memories=1
04:27:18.117 sessions count=1 first=session_amiya title=阿米娅 lastMessage=（iOS 桥接演示）我听见了：…
04:27:18.125 memories count=1 first=演示记忆（真实 DB 写入）：博士与阿米娅在罗德岛走廊第一次说话。
04:27:18.127 settings write note=RhodesLink iOS 真壳写入 @04:27:18 readBack=RhodesLink iOS 真壳写入 @04:27:18
04:27:18.127 settings readBack=... snapshot=dark=false;skin=rhodes_night;demo_note=
04:27:18.128 deeplink rhodes://chat/session_amiya handled=true lastHandledLink=rhodes://chat/session_amiya
04:27:19.709 chat send text=自检：你好，阿米娅。这是来自 iOS 真壳的消息。
04:27:20.752 chat done ok=true aiId=4 chars=60 error=
04:27:27.953 sessions after send count=1 lastMessage=（iOS 桥接演示）我听见了：自检：你好，阿米娅。…
04:27:27.953 tab switched to roles / memory / settings / chat   （四 Tab 程序化切换）
04:27:28.882 === selftest done ===
```

**真实 DB 直查**（模拟器数据容器内 `Library/Application Support/rhodes.db`）：

```
chat_messages 累计 4 行（两轮自检各 1 条用户 + 1 条 AI，ai_json 落库）
chat_sessions: session_amiya|阿米娅|（iOS 桥接演示）我听见了：自检：你好，阿米娅。…|…|online
memories: 演示记忆（真实 DB 写入）：博士与阿米娅在罗德岛走廊第一次说话。
```

**证据截图**（`ios-port/handoffs/evidence/`，均为模拟器实际画面）：

| 文件 | 内容（OCR 已核对） |
|---|---|
| `t33-chat-selftest.png` | 聊天页：头部「阿米娅 / 在线·私聊」+ 用户气泡 + 真实 AI 打字机回复；底部 4 Tab |
| `t33-tab-roles.png` | 角色 Tab：「角色 · 来自 shared 会话数据 / A2」+ 阿米娅角色卡 |
| `t33-tab-memory.png` | 记忆 Tab：「长期印象 · MemoryBridge / A3 / 真实 SQLite 只读 · 1 条」+ 记忆内容 |
| `t33-tab-settings.png` | 设置：Shared.framework 已连接 / DB 路径 / 会话·记忆 1/1 / 设置快照 / 外观 segmented / Dynamic Type 实时状态 |
| `t33-deeplink-role-detail.png` | 深链 `rhodes://role/session_amiya` → 角色详情页（开始聊天 + 占位行 + 数据来源说明） |
| `t33-deeplink-settings-appearance.png` | 深链 `rhodes://settings/appearance` → 外观设置占位路由页 |
| `t33-chat-stress-2000.png` | 聊天页注入 2,000 条消息：状态条「性能基线 · 2000 条本地消息（LazyVStack）」+ LazyVStack 渲染 |
| `t33-chat-dark.png` | 深色外观（跟随系统 → simctl appearance dark） |
| `t33-deeplink-system-confirm.png` | iOS 26 系统对 `simctl openurl rhodes://…` 的确认框「在 RhodesLink 中打开？」（证明 scheme 已注册） |
| `t33-build.log` / `t33-selftest.log` | 构建成功日志 / 自检完整日志 |

截图侧验证方式说明：本机无 GUI 触控自动化（dsh-ios 工具按要求不使用，`simctl` 无 tap 能力），因此采用
①`--tab` 启动参数逐 Tab 截图；②`--open-url` 启动参数走与 `onOpenURL` **相同**的 `AppRouter.handle(url:)`；③`--stress-2000` 注入压力数据；④自检日志核对。系统确认框无法用命令行点击，人工在模拟器/真机上 `xcrun simctl openurl` 或点链接即可完成最后一步。

## 5. 关键设计决策

1. **手写 Info.plist + `GENERATE_INFOPLIST_FILE=NO`**：`CFBundleURLTypes` 无法用 `INFOPLIST_KEY_*` 生成；Info.plist 放在同步组外（`Config/`）避免被当作资源复制。
2. **同步组（objectVersion 77）**：与 Demo 一致，后续加页面文件零 pbxproj 维护。
3. **`EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64`**：K/N 静态 framework 只有 arm64；否则 generic Simulator 双架构链接必失败。真机走 `iosArm64/releaseFramework`（路径条件已配好，签名待 lead）。
4. **路由设计**：`Route` 单一枚举 + 每 Tab 独立 `NavigationPath`；`RouteView` 统一分发；未注册深链 → 当前 Tab 占位页（不崩）；`rhodes://group/*` 等预留。
5. **桥接纪律**：`RhodesAppGraph` 只在 `AppGraphClient` 内出现；`ChatSendHandle` 隔离 `IosCancellable`；回调统一 Main 队列（`assertMainQueue` 保护）。
6. **外观**：Swift 侧 `ios_shell_appearance`（system/light/dark）+ shared `dark_mode` 双写；根视图 `preferredColorScheme` 实时生效；设置页显示 `settings.snapshot()` 读回作为"真实写入"证据。
7. **播种策略**：`AppEnvironment.start()` 幂等，仅当会话为空时调用 shared `bootstrap()`；正式产品由 onboarding/导入取代（规格 §5 已标注）。
8. **一屏 ≤5 玻璃**：所有列表/设置行用 `CardSurface`（非玻璃），玻璃只保留状态条/输入栏/头部卡（各页 1~2 个）。

## 6. 已知限制（与规格 §12 同步）

1. shared 门面**无消息历史桥**：聊天页时间线只显示本次发送流 + 会话 `lastMessage` 摘要，历史消息需新增桥接。
2. 角色数据由 `SessionDto` 推导（无 operator/RoleBridge 导出门面）；立绘/人设/素材未接。
3. fake AI 通道（`DemoStreamingClient` + `DemoPromptAssembler`）；真实供应商/提示词管线待接。
4. 记忆只读；备份/提示词/群聊/语音/素材均为占位路由。
5. iOS 26 真玻璃观感、浅色视觉、AX5 大字号未做人类评审（Gate 3 签字项）。
6. 模拟器仅 arm64（Intel Mac 不支持）；真机构建需签名团队。
7. `-disable-sandbox` 与工作区内 TMPDIR/DerivedData 为 DSH 沙箱构建适配，非沙箱 CI 可移除前者。
8. 深链真实 `openurl` 的系统确认框需人工点击（自动化走 `--open-url` 同一代码路径）。

## 7. 复现命令

见 `ios-port/specs/ios-shell-spec.md` §10（构建 / 安装 / 自检 / 四种启动参数 / 截图 / 取日志）。
关键启动参数：`--selftest`、`--tab chat|roles|memory|settings`、`--open-url <rhodes://…>`、`--stress-2000`。

## 8. 对后续 Wave 的建议

| 优先级 | 项 | 落点 |
|---|---|---|
| P0 | `MessageHistoryBridge`（分页读 chat_messages）→ 聊天页历史时间线 | shared iosbridge + `ChatViewModel.load()` |
| P0 | 真实 AI 供应商 + 提示词管线替换 | `ChatEngineBridge` 内部 |
| P1 | `RoleBridge`（operators / 立绘 / 人设） | `RolesView` / `RoleDetailView` |
| P1 | 备份导出/恢复 UI（`BackupBridge` 已就绪） | 设置页二级页面 |
| P2 | 群聊正式路由页 | `Route` 增加 `.group` |
| P2 | 一屏玻璃计数与浅色视觉复评（人类评审） | DesignSystem token |
