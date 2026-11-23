# 未读角标链路规格（T44）

> 代理：impl-unread（task-44）｜状态：实现 + 模拟器自检（见 `ios-port/handoffs/impl-unread.md`）
> 背景：T40 遗留缺陷——`EngineEvent.UnreadHint` 未被 iOS 桥消费，AI 回复不会自增 `unreadCount`；
> 角标只在 DB 预置未读时显示，打开会话也不会清零。

## 1. 原版（Android 1.14.27）语义

| 环节 | Android 实现 | 位置 |
|---|---|---|
| 引擎发未读提示 | 仅在成功生成**可见回复**后发 `EngineEvent.UnreadHint(sessionId, count)`；count = 可见段数（不是固定 1） | `shared/domain/chat/ChatEngine.kt:466`（私聊发送）、`:842`（重说）、`:1001`、`:1052` |
| 宿主决定是否应用 | `markUnreadIfNotCurrent(sessionId, count)`：`sessionId != _currentSession.id` 时才 `repository.incrementUnread`，并 `onUnhideSession` | `app/.../ChatViewModel.kt:1075-1080`、调用点 `:1674/:1995/:2420/:2491` |
| 当前会话 | `_currentSession`（聊天页打开时设置） | `ChatViewModel.kt` |
| 已读清零 | `markSessionRead` → `SessionRepository.markSessionRead`（写 `chat_sessions.unreadCount=0`） | `ChatViewModel.kt:1071`、`SessionRepository.kt` |
| 角标展示 | 会话列表行 `unreadCount > 0` 显示数字角标 | `SessionListScreen.kt` |

结论：Android 的未读计数是「**非当前会话**的 AI 回复才 +count」，当前会话由聊天页状态提供。

## 2. iOS 实现（本卡）

### 2.1 shared/iosMain

| 文件 | 变更 |
|---|---|
| `shared/src/iosMain/.../iosbridge/ChatEngineBridge.kt` | 新增 `viewingSessionId`（UI 当前查看会话，与发送用 `activeSessionId` 分离）；`onEngineEvent` 消费 `EngineEvent.UnreadHint` → `markUnreadIfNotCurrent`（非当前会话 → `chatRepo.sessions.incrementUnread(sessionId, count)`，失败静默）；新增公开 `setActiveSession(sessionId:)`；新增自检夹具 `debugIncrementUnread(sessionId:delta:onDone:)`（与真实 UnreadHint 写同一 DB 列） |
| `shared/src/iosMain/.../iosbridge/SessionListBridge.kt` | 仅 KDoc 补充（未读自增归 ChatEngineBridge；本桥继续负责 markRead/markAllRead/列表） |

### 2.2 Swift

| 文件 | 变更 |
|---|---|
| `Features/Chat/ChatViewModel.swift` | `load()` 打开会话时：先 `ChatEngineBridge.setActiveSession(sessionId)`，再 `AppGraphClient.markSessionRead` 清零并重读会话（日志 `chat unread active …`）。顺序保证「打开瞬间到达的回复」不被误计未读 |
| `Features/Chat/ChatListView.swift` | 返回列表（`chatPath.count == 0`）时 `setActiveSession("")` 清空当前查看会话 + reload 拿最新未读；新增 `--unread-selftest` 自检钩子（真实路由/真实引擎/真实 DB） |

### 2.3 语义取舍（与 Android 的差异，已知并接受）

1. **当前查看会话的来源**：Android 用 `_currentSession`（聊天页 ViewModel 状态）；iOS 由 Swift 显式告知桥（`setActiveSession`）。
   打开：`ChatViewModel.load()`；离开：`ChatListView` 监听 `router.chatPath.count == 0` 清空。
   （`ChatView.swift` 不在本卡写域，故用列表页的路径观察作为「离开会话页」信号；语义等价，见 handoff §限制。）
2. **未读自增时机**：引擎事件同步消费，DB 写异步（workScope）。与 Android 相同（协程写库）。
3. **count 语义**：沿用引擎 `aiResponseCount`（可见段数），不是固定 +1；自检中断言为 `>= 1`，另用 `debugIncrementUnread(+1)` 断言精确增量。
4. **无推送/后台通知**：原版 `notifyIfBackground` 未移植（属通知服务/T45+ 范围），本卡仅落 DB 未读。

## 3. 验收自检矩阵（`--unread-selftest`）

启动参数：`--selftest --unread-selftest`（`--selftest` 用于落盘 `Documents/ios-shell-selftest.log`）。

| # | 步骤 | 断言 | 日志标记 |
|---|---|---|---|
| a | DB 预置未读 2 | 列表 `unreadCount == 2`（角标显示） | `unread selftest seed … badge=2 PASS=true` |
| b | 打开该会话（真实路由 → ChatViewModel.load） | `markRead` 后 DB/列表未读 = 0 | `unread selftest openChat … unreadAfterOpen=0 PASS=true` |
| c | 当前会话内发送并收到 AI 回复 | 未读保持 0（UnreadHint 被忽略） | `unread selftest viewingSend … unread=0 PASS=true` |
| d | 返回列表 | 角标为 0（reload 最新未读数） | `unread selftest backToList … PASS=true` |
| e | 列表页经真实引擎发送（模拟「非当前会话收到 AI 回复」） | UnreadHint → 未读 ≥ 1，reload 后角标出现 | `unread selftest aiReplyWhileAway … PASS=true` |
| f | 桥 `debugIncrementUnread(+1)` | `unread == before+1`（角标 +1） | `unread selftest increment … incrementPASS=true` |

结束：`=== unread selftest done allPASS=true ===`，停在聊天列表页并保留未读角标供截图。

## 4. 证据

`ios-port/handoffs/evidence/t44-*`：framework link / xcodebuild 日志、自检日志、DB `unreadCount` 查询、
角标截图（AI 回复后）、打开会话清零截图、复跑命令见 handoff。
