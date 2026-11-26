# settings-extra-spec.md — iOS 设置余项（T53：调试日志 / 通知 / 外观）

> 规格版本：v1（2026-11-23）｜实现：T53 / task-53｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / SwiftUI iOS 18+）
> 目标阶段：Phase 5 —— 补齐设置页「调试日志 / 通知 / 外观」三项，使 Phase 5 设置面与 Android 1.14.27 的使用惯性和 iOS 平台语义对齐。

---

## 1. 范围

| 项 | 内容 |
|---|---|
| 调试日志页 | `iosApp/RhodesLink/Features/Debug/DebugLogsView.swift`（新）：读取 shared `IosDebugLogger` 文件日志 → 列表 / 级别筛选 / 关键词搜索 / 导出分享 / 清空（二次确认） |
| 通知设置页 | `iosApp/RhodesLink/Features/Settings/NotificationSettingsView.swift`（新）：授权状态读取/请求 + 4 类提醒开关 + 前台/后台说明 |
| 外观设置页 | `iosApp/RhodesLink/Features/Settings/AppearanceSettingsView.swift`（新，替换原占位）：主题 / 聊天背景 / 字体大小 |
| 设置桥（新增文件，不改 IosAppGraph） | `shared/src/iosMain/.../iosbridge/DebugLogBridge.kt`、`NotificationBridge.kt`、`SettingsExtraBridges.kt`（工厂） |
| Swift 包装 | `iosApp/RhodesLink/Bridge/SettingsExtraClient.swift`（新）：Kotlin 类型不外露 |
| 共享外观 token | `iosApp/RhodesLink/DesignSystem/ChatAppearance.swift`（新）+ `GlassTokens.chatBackground` 改为渲染时读取（聊天页一行未改） |
| 路由 | `AppRouter.swift` / `RouteView.swift` / `SettingsView.swift`：新增 `.settingsNotifications` / `.settingsDebugLogs`，`.settingsAppearance` 接真实页面 |
| 自动化 | `iosApp/RhodesLink/Features/Settings/SettingsExtraAutomation.swift`（`--settings-extra-selftest` / `-verify` / `-restore`） |
| 证据 | `ios-port/handoffs/evidence/t53-*`、`ios-port/handoffs/impl-settings-extra.md` |

**纪律**：不改 `FeaturesView.swift`（T52/T54 并行写域）；不改 Chat / Moments / Diary 功能页（聊天背景通过 `GlassTokens.chatBackground` token 间接生效，ChatView 源码未动）；git 不提交。

---

## 2. 设置键

### 2.1 调试日志

无新增设置键。日志文件由 T32 `IosDebugLogger` 维护：

| 项 | 值 |
|---|---|
| 目录 | `Application Support/logs/` |
| 当前文件 | `rhodes.log`（UTF-8 一行一条） |
| 行格式 | `2026-11-23T05:44:00.123Z [INFO] [tag] message` |
| 轮转 | 单文件 > 512 KB → `rhodes-<epochMs>.log`，最多保留 5 份 |
| 导出 | `Application Support/exports/rhodes-log-<epochMs>.log`（复制主文件） |

> 说明：Android 有 `debug_log_enabled` / `debug_log_payloads_enabled` 设置键；iOS 本卡只做**日志查看/导出/清空**，不新增「日志开关」（Android 的开关语义是控制 `DebugLogger` 是否落盘，iOS 的 `IosDebugLogger` 目前由各模块显式调用，无全局开关）。若后续要开关，需在 shared 层接线，跨卡。

### 2.2 通知（本卡新增 4 键）

| 设置键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `notify_private_reply` | bool | true | 私聊回复提醒（含主动消息） |
| `notify_group_reply` | bool | true | 群聊回复/群消息提醒 |
| `notify_moments` | bool | true | 动态发布/评论/点赞提醒 |
| `notify_diary` | bool | true | 干员新日记提醒 |

- 全部落 `NSUserDefaults`（`SettingsRepository`），冷启动保持；
- 授权状态**不落键**，每次进页面实时查 `UNUserNotificationCenter`（系统状态是权威）；
- Android oracle 无「提醒开关」键（Android 只有通知中心 `RhodesNotificationCenter` + 系统通知权限），本卡 iOS 先定义 4 键（默认全开，不改变现状），UI 明示「偏好已保存，事件源接线属后续批次」。

### 2.3 外观（3 键）

| 设置键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `ios_shell_appearance` | string | 未设置 = 跟随系统 | 现有键（T33）：`system` / `light` / `dark`，`SettingsStore` 读取 → `preferredColorScheme`；同时同步 shared `dark_mode`（`appearance=dark → dark_mode=true`） |
| `ios_chat_background` | string | `classic`（未设置时） | **本卡新增**：聊天背景枚举，见 §2.3.1 |
| `ios_shell_font_size` | string | `standard`（未设置时） | **本卡新增**：`small` / `standard` / `large` |

#### 2.3.1 聊天背景枚举（`ChatBackgroundStyle`）

| rawValue | 名称 | 类型 | 浅色 | 深色 |
|---|---|---|---|---|
| `classic` | 经典灰 | 纯色 | `#EDEDED` | `#111111` |
| `paper` | 纯色 · 米白 | 纯色 | `#F7F1E6` | `#1B1A18` |
| `mint` | 纯色 · 薄荷 | 纯色 | `#E8F3EC` | `#12201A` |
| `sky` | 纯色 · 天青 | 纯色 | `#E9F0F7` | `#101823` |
| `dawn` | 渐变 · 晨曦 | 线性渐变 | `#FFE8D2 → #F3DDE9` | `#2A1B14 → #1E1520` |
| `dusk` | 渐变 · 暮色 | 线性渐变 | `#E6E9F7 → #E9F1F4` | `#141A2E → #101E24` |

- 低对比、深浅色自适应，参考微信 / Telegram；
- 生效方式：`GlassTokens.chatBackground`（聊天页背景 token）在渲染时调用 `ChatBackgroundStyle.stored()` 读 UserDefaults → `ChatBackgroundView` 渲染；**ChatView.swift 未改**。改完返回并重新进入聊天页生效；
- 群聊页仍用系统分组背景（本卡未改群聊页，见 §6 限制）。

#### 2.3.2 字体大小档位（`SettingsStore.FontSize`）

| rawValue | 名称 | 生效方式 |
|---|---|---|
| `small` | 小 | `RootTabView.dynamicTypeSize(.small)`（约 15pt 正文） |
| `standard` | 标准 | 不加强制 modifier → 跟随系统 Dynamic Type（默认 .large，尊重用户可访问性字号） |
| `large` | 大 | `RootTabView.dynamicTypeSize(.xxLarge)`（约 23pt 正文） |

全局缩放，聊天 / 会话列表 / 设置页同时生效（无需修改 Chat/List 页面）。

---

## 3. 桥 API

### 3.1 Kotlin（`SettingsExtraBridges.kt` 工厂，Swift 入口 `SettingsExtraBridgesKt.makeSettingsExtraBridges()`）

| 方法 | 返回 | 说明 |
|---|---|---|
| `DebugLogBridge.write(level,tag,message)` | Unit | 写一行 shared 文件日志 |
| `DebugLogBridge.snapshot(limit)` | `DebugLogSnapshotDto` | 主文件解析后的行（新的在前）+ 路径/大小/文件清单/总行数/是否截断 |
| `DebugLogBridge.exportToFile()` | `String` | 导出到 `Application Support/exports/`，返回路径（空=失败） |
| `DebugLogBridge.clearAll()` | `Boolean` | 清空主文件 + 全部归档 |
| `DebugLogBridge.pathsSummary()` | `String` | 目录/主文件/大小/归档数（诊断） |
| `NotificationBridge.readPrefs()` | `NotificationPrefsDto` | 4 个提醒开关 + 设置键清单 |
| `NotificationBridge.savePref(scope,enabled)` | `String` | 空=成功；scope=`private_reply`/`group_reply`/`moments`/`diary` |
| `NotificationBridge.authorizationStatus(onResult)` | `NotificationPermissionStatus`（回调，主队列） | 只读，不弹窗 |
| `NotificationBridge.requestFullAuthorization(onResult)` | 同上 | 完整授权（真机弹系统框） |
| `NotificationBridge.requestProvisionalAuthorization(onResult)` | 同上 | 静默 provisional（不弹窗） |
| `NotificationBridge.openSystemSettings()` | Unit | 打开系统通知设置 |
| `NotificationBridge.prefsSnapshot()` | `String` | `private=…;group=…;moments=…;diary=…` |

说明：工厂自带一份 `SettingsRepository`（NSUserDefaults 标准域），**未扩展 `IosAppGraph` / `SettingsBridge`**（避免与并行批次写同一文件）；与 graph 内实例读写同一批键，语义一致。

### 3.2 Swift（`Bridge/SettingsExtraClient.swift`）

`SettingsExtraClient.shared` 单例：`debugLogSnapshot(limit:)` / `debugLogExport()` / `debugLogClear()` / `debugLogWrite(...)` / `notificationPrefs()` / `saveNotificationPref(scope:enabled:)` / `notificationAuthorizationStatus()` async / `requestFullNotificationAuthorization()` async / `requestProvisionalNotificationAuthorization()` async / `openNotificationSystemSettings()`。

---

## 4. 页面行为

### 4.1 调试日志页

- 顶部状态卡：主文件绝对路径、大小（+归档数）、总行数、操作结果；
- 筛选卡：级别 segmented（全部/DEBUG/INFO/WARN/ERROR）+ 关键词搜索（正文/tag/级别，忽略大小写）+「生成导出文件」→ 出现 `ShareLink` 分享 + 「清空」（二次确认 confirmationDialog）；
- 列表：新日志在前，每条显示本地时间 `HH:mm:ss.SSS` / 级别徽标（ERROR 红、WARN 橙、INFO 蓝、DEBUG 灰）/ `[tag]` / 正文；默认 2 行截断，点按展开/收起；`textSelection` 可复制；
- 下拉刷新；无日志/无匹配有空态；演示钩子 `--settings-extra-demo-logs` 一次性写入 10 条多级别日志（截图证据）。

### 4.2 通知设置页

- 授权状态卡：真实状态文案（未决定/已拒绝/已授权/静默 provisional/ephemeral）+ `status/granted/provisional` 明细；
- 按钮：「请求完整授权」/「静默授权」/「打开系统通知设置」；
- 提醒开关卡：4 行 Toggle（私聊回复 / 群聊回复 / 动态 / 日记），每行显示 settings 键名；切换即写 + 立即读回并显示快照；
- 说明卡：前台（App 使用中：应用内实时展示，不走系统通知中心）vs 后台（本地通知，系统机会式调度，不承诺准点；打开 App 立即补算）vs 系统限制（权限/模拟器差异，引用 T13 实测）；
- 诊断卡：偏好快照 + 设置键 + 权限状态。

### 4.3 外观设置页

- 主题卡：分段 Picker（跟随系统/浅色/深色）→ 写 `ios_shell_appearance` + 同步 `dark_mode`，实时生效；
- 聊天背景卡：选中背景大预览（含收发示例气泡）+ 6 宫格色板（纯色 4 / 渐变 2，选中描边 + 对勾），点选即写；
- 字体大小卡：分段 Picker（小/标准/大）+ 预览文本 + 当前档位说明；
- 落盘读回卡：三个键的 `bridge.settingsString` 原始值 +「重新读回」按钮。

### 4.4 设置主页入口

`路由行卡片` 现为 8 行：外观设置 / **通知设置** / **调试日志** / 可访问性子页 / 模型供应商 / 聊天表现 / 提示词编辑器 / 数据与备份。
深链：`rhodes://settings/{appearance|notifications|notify|debug-logs|logs}`。

---

## 5. 验证（`--settings-extra-selftest`）

### 5.1 写入阶段（`--selftest --settings-extra-selftest`）

| 断言 | 判据 |
|---|---|
| `debug-logs.read-non-empty` | 打点后 `snapshot.entries` 非空且包含 marker，路径非空 |
| `debug-logs.parse-fields` | marker 行 level=INFO / tag=T53SELFTEST / 时间戳非空 |
| `debug-logs.files-listed` | 日志文件清单非空 |
| `debug-logs.export-file` | 导出路径存在、大小 >0、内容包含 ERROR 级 marker |
| `debug-logs.clear-and-rewrite` | 清空后 totalLines=0；再打点后能读到新行 |
| `notifications.permission-readable` | 权限状态 0–4 可读（只读，不弹窗） |
| `notifications.prefs-save-readback` | 4 键写入（false/true/false/true）后读回一致 |
| `notifications.keys-non-empty` | 4 个设置键名非空 |
| `appearance.keys-save-readback` | `ios_shell_appearance=dark` / `ios_chat_background=dusk` / `ios_shell_font_size=large` 写入并读回一致；`SettingsStore` 内存值一致 |
| flush | `flushDefaults()` 强制 NSUserDefaults 落盘 |

### 5.2 冷启动校验阶段（另起进程 `--selftest --settings-extra-verify`，只读）

| 断言 | 判据 |
|---|---|
| `persisted.debug-logs` | 日志文件跨进程仍在（含 marker） |
| `persisted.debug-logs-reexport` | 冷启动可再次导出且文件非空 |
| `persisted.notification-prefs` | 4 键值跨进程一致 |
| `persisted.appearance-keys` | 3 个原始键值一致 |
| `persisted.appearance-store-readback` | `SettingsStore` 构造时读取值与期望一致（App 层读回） |
| `persisted.dark-mode-synced` | `SettingsBridge.snapshot` 含 `dark=true`（shared 同步键） |

### 5.3 恢复阶段（`--selftest --settings-extra-restore`）

外观回 `system` / `classic` / `standard`；4 个通知开关回 true；flush。
调试日志**不清理**（保留证据）。

---

## 6. 已知限制 / 开放问题

1. **通知事件源未接线**：本卡只保证「权限状态真实 + 4 个偏好键落盘」，聊天/动态/日记管线尚未在回复落库后读取这些键并调 `IosNotificationScheduler` 发本地通知（T32 调度器已有，事件源属聊天/日记卡写域）。Android 原版也没有这 4 个开关键，属 iOS 先行定义。
2. **聊天背景只覆盖私聊**：通过 `GlassTokens.chatBackground` token 生效，`ChatView.swift` 未改；群聊页 `GroupChatView.swift` 仍用 `Color(uiColor: .systemGroupedBackground)`，本卡纪律不改 Chat/GroupChat 功能页 → 群聊背景待后续卡统一。
3. **字体档位覆盖系统 Dynamic Type**：小/大档会强制 `dynamicTypeSize`，覆盖用户在系统设置中的可访问性字号；标准档 = 跟随系统。如需「App 内档位 × 系统字号」叠加，需改设计（SwiftUI 无直接乘法缩放 API）。
4. **模拟器通知授权**：完整授权升级弹窗在模拟器不出现（T13 实测，provisional 后 request full 回调不触发）；真机才弹系统授权框。自检只读权限状态，不触发请求。
5. **导出分享**：`ShareLink(item: URL)` 分享 `Application Support/exports/` 下的日志副本；模拟器截图/app 内验证以文件存在为准，未驱动系统分享面板。
6. **日志全局开关**：Android 的 `debug_log_enabled` 未移植到 iOS（`IosDebugLogger` 是显式调用式）；本卡不新增日志开关。

---

## 7. 与其它批次的关系

- T32（平台服务）：`IosDebugLogger` / `IosNotificationScheduler` 在本卡复用，未修改；
- T46（设置页）：设置主页路由行卡片、`SettingsStore` 外观逻辑在本卡扩展，兼容原 `.settingsAppearance` 深链；
- T52/T54：`FeaturesView.swift` / 排行榜 / 印象页面不在本卡写域，未触碰；
- 并行纪律：本卡新增文件为主（仅对 `AppRouter.swift` / `RouteView.swift` / `SettingsView.swift` / `RhodesLinkApp.swift` / `AppEnvironment.swift` / `GlassTokens.swift` 做最小 patch）；`IosAppGraph.kt` / `SettingsBridge.kt` / `AppGraphClient.swift` 未改。
