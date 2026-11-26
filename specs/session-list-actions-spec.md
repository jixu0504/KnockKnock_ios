# iOS 会话列表操作对齐规格（T40）

> 任务：task-40（impl-session-actions）｜状态：**实现完成（构建/模拟器验证见 handoff）**
> 原版 oracle（Android）：
> - `app/src/main/java/com/rhodes/privatechat/ui/sessions/SessionListScreen.kt:68-130`（工具栏「⋮」菜单、
>   长按操作弹窗、移除确认文案、会话行置顶底色 + 置顶图标 + 未读角标）
> - `app/src/main/java/com/rhodes/privatechat/viewmodel/SessionViewModel.kt:28-53`（clearAllMessages /
>   markAllRead / pinSession / markSessionRead）
> - `app/src/main/java/com/rhodes/privatechat/viewmodel/MainViewModel.kt:1763-1796`（unhideSession /
>   hideSession / clearAllMessages / markAllRead / pinSession 门面；:654 清理失效 hiddenIds）
> - `shared/src/commonMain/.../data/SessionRepository.kt:74-79`（updatePinned / markAllRead / markSessionRead）
> - `shared/src/commonMain/sqldelight/.../ChatSessions.sq`（`getAllSessions: ORDER BY isPinned DESC, lastTime DESC`；
>   `markAllRead` / `markSessionRead` / `updatePinned`）
> - `settings.hiddenIds`（`SettingsRepository.kt:1066`，存储键 `hidden_ids`，StringSet JSON）
> - 联系人 Tab 新建入口：`ContactsScreen`（新建干员/群聊在联系人 Tab，不在聊天 Tab）

## 1. 范围

| 交付 | 内容 |
|---|---|
| shared 桥（iosMain） | `SessionListBridge` 新增 `pin/togglePin/markRead/markAllRead/hide/hideAll`；`list` 过滤 `hiddenIds` + 清理失效 id；`SessionDto.isPinned` |
| shared 引擎钩子 | `ChatEngineBridge.onEngineEvent` 处理 `EngineEvent.UnhideSession` → `SessionListBridge.unhide`（新消息自动恢复主页显示） |
| iOS 聊天列表 | 工具栏「⋮」菜单（移除主页所有会话 / 全部标记已读）；移除铅笔（新建聊天）与刷新按钮；会话行长按 contextMenu（置顶/取消置顶、标记已读、从主页移除）；保留 `.refreshable` |
| iOS 联系人页 | 工具栏「+」新建聊天 → `Route.newChat`（contactsPath）→ 既有 NewChatView（`RoleBridge.startChat`） |
| 自动化验证 | `--sessions-selftest`（SessionsAutomation.swift）：置顶排序/已读/移除/新消息恢复/hideAll |
| 文档 | 本规格 + `ios-port/handoffs/impl-session-actions.md` + evidence |

不改 Android `app/`；不改 shared/commonMain（只读依赖，复用既有 `SessionRepository` / `SettingsRepository`）；
不删除任何会话与消息（「移除」只写 `settings.hiddenIds`，与原版一致）。

## 2. 操作矩阵（原版 ↔ iOS 实现位置）

| # | 原版行为（Android） | 原版位置 | iOS 实现位置 | 语义 |
|---|---|---|---|---|
| 1 | 聊天列表右上角唯一「⋮」溢出菜单 | `SessionListScreen.kt:82-92` | `ChatListView.swift` `.toolbar` → `Menu`（`ellipsis.circle`） | 位置/顺序一致 |
| 2 | 「移除主页所有会话」→ 确认弹窗（标题「移除主页所有会话」，正文「所有会话将暂时不在聊天主页显示。任一会话收到新的消息后，会自动重新出现在主页。」，按钮 取消/确认移除） | `SessionListScreen.kt:84,113-117` | `ChatListView.swift` `ConfirmAction.hideAll` + `.alert` | 确认后才执行；仅写 hiddenIds ∪ 全部会话 id，不删库 |
| 3 | 「全部标记已读」→ 直接执行（无确认） | `SessionListScreen.kt:85` | `ChatListView.swift` 菜单第二项 → `bridge.markAllSessionsRead()` | `UPDATE chat_sessions SET unreadCount=0` |
| 4 | 会话行**长按**弹操作：置顶/取消置顶、标记已读、从主页移除 | `SessionListScreen.kt:95-100,119-132` | `ChatListView.swift` `sessionRow` `.contextMenu` | iOS 用系统 contextMenu（等价长按） |
| 5 | 置顶 / 取消置顶 | `SessionViewModel.kt:45-49`；`SessionRepository.kt:74` | bridge `togglePin` / `pin` → `ChatSessionBridge.pinSession/togglePinSession` | `UPDATE chat_sessions SET isPinned=?`；列表顺序 SQL 已 pinned 在前 |
| 6 | 标记已读（单会话） | `SessionViewModel.kt:51-53`；`SessionRepository.kt:79` | bridge `markRead` → `markSessionRead` | `UPDATE chat_sessions SET unreadCount=0 WHERE id=?` |
| 7 | 从主页移除（单个）→ 确认弹窗（标题「从主页移除」，正文「会话将暂时不在聊天主页显示。收到新的消息后，会自动重新出现在主页。」，按钮 取消/确认移除） | `SessionListScreen.kt:104-111`；`MainViewModel.kt:1779-1784` | `ChatListView.swift` `ConfirmAction.hide` + `.alert` | 只写 `settings.hiddenIds`，DB 会话与消息保留 |
| 8 | 收到新消息 → 会话自动重新出现在主页 | `MainViewModel.kt:1763-1769` + `ChatEngine` `EngineEvent.UnhideSession` | `ChatEngineBridge.kt` `onEngineEvent` → `SessionListBridge.unhide` | 引擎发出/回复落库后移除 hiddenId |
| 9 | 列表顺序：置顶在前，其余 lastTime 倒序 | `ChatSessions.sq` `getAllSessions` | `SessionListBridge.list` 直接复用该查询（SQL 排序），仅过滤 hiddenIds | 完全一致，无二次排序 |
| 10 | 清理已删除会话的失效 hiddenIds | `MainViewModel.kt:654-655` | `SessionListBridge.list` | 读列表时顺手裁剪 |
| 11 | 置顶行视觉：置顶图标 + 品牌色底色；未读角标 | `SessionListScreen.kt:139-175` | `ChatListView.swift` 行内 `pin.fill`（品牌橙）+ 既有未读角标 | iOS 用玻璃卡片体系，保留图标语义；不引入新玻璃元素 |
| 12 | 新建聊天**不在聊天 Tab**；新建入口在联系人 Tab | `ContactsScreen`（新建干员/群聊） | `RolesView.swift` 工具栏「+」→ `router.openNewChatFromContacts()` → `Route.newChat`；`ChatListView` 工具栏铅笔已移除、空态无新建按钮 | 新建聊天入口从聊天 Tab 迁到联系人 Tab |
| 13 | 刷新 | 原版列表由 Flow 自动刷新（无手动刷新按钮） | 保留 `.refreshable` 下拉刷新（非工具栏按钮）；`onChange(chatPath.count)` 返回列表时刷新 | 工具栏不再有刷新按钮 |

说明：原版长按弹窗使用 `AlertDialog`，iOS 用系统 `.contextMenu` + 二次确认 `.alert`；交互形态按 iOS 平台惯例，
**操作集合、顺序、确认文案、数据语义**与 Android 对齐。

## 3. 桥接契约（T40 新增）

`SessionListBridge`（`@ObjCName(swiftName = "SessionListBridge")`），回调式（无 Flow），回调 Main 队列，
`onDone(error)` 的 error 为 null 表示成功：

| Kotlin 方法 | Swift 名 | 底层 |
|---|---|---|
| `pin(sessionId: String, pinned: Boolean, onDone)` | `pinSession(id:pinned:)` | `SessionRepository.updatePinned` |
| `togglePin(sessionId: String, onDone)` | `togglePinSession(id:)` | 读 `getSession` 后 `updatePinned(!isPinned)` |
| `markRead(sessionId: String, onDone)` | `markSessionRead(id:)` | `SessionRepository.markSessionRead` |
| `markAllRead(onDone)` | `markAllSessionsRead()` | `SessionRepository.markAllRead` |
| `hide(sessionId: String, onDone)` | `hideSession(id:)` | `settings.hiddenIds += id` |
| `hideAll(onDone)` | `hideAllSessions()` | `settings.hiddenIds ∪ 全部会话 id` |
| `list(onResult)`（变更） | `loadSessions()` | 过滤 hiddenIds + 裁剪失效 id；SQL 排序 pinned/lastTime |
| `internal unhide(sessionId)` | （不导出） | 引擎 `EngineEvent.UnhideSession` 钩子 |

`SessionDto` 字段新增 `isPinned: Boolean`（对齐 `ChatSessionEntity.isPinned`）；Swift `SessionItem.isPinned`。
`hidden_ids` 存储格式不变（`SettingsRepository.hiddenIds` 的 StringSet JSON），与 Android 同一键、同一语义，
备份/恢复（`BackupPortableSettings.hidden_ids`）互通。

## 4. iOS UI 取舍

1. 工具栏仅一个「⋮」`Menu`；「新建聊天」铅笔与「刷新」按钮删除（避免与原版操作集合不一致）。
2. 空态文案改为「暂无聊天记录」（对齐原版 `SessionListScreen.kt:94`），新建入口提示指向「联系人」Tab。
3. 长按 contextMenu 的「从主页移除」为 destructive 样式，仍走二次确认弹窗（与原版一致）。
4. `actionError` 行内错误提示（桥接失败时可见），不影响正常路径。
5. 不新增玻璃元素：工具栏菜单、contextMenu、alert 均为系统控件。

## 5. 验证

1. `./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64`（BUILD SUCCESSFUL）。
2. `xcodebuild build -project KnockKnock.xcodeproj -scheme KnockKnock -destination 'generic/platform=iOS Simulator'`
   （BUILD SUCCEEDED）。
3. 模拟器自动化（`--selftest --sessions-selftest`）：置顶排序、取消置顶、markRead/markAllRead、
   hide→列表消失、send（引擎 UnhideSession）→恢复、hideAll→空、恢复 hiddenIds→列表恢复。
4. 模拟器 UI 实跑 + 截图：长按菜单三项、移除确认弹窗、⋮ 菜单、全部标记已读、角标消失、置顶行、联系人「+」。
5. 证据目录：`ios-port/handoffs/evidence/t40-*`。
