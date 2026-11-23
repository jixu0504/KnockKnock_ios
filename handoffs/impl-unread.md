# T44 实现 handoff：未读角标链路修复（UnreadHint 消费 + 已读清零）

> 代理：impl-unread（task-44）｜规格：`ios-port/specs/unread-badge-spec.md`
> 状态：**实现完成；Shared.framework 重链 + xcodebuild 通过；`:shared:testDebugUnitTest` 524 例 0 失败；
> iPhone 17 / iOS 27.2 模拟器 `--unread-selftest` 六项全 PASS（含 DB + 截图证据）**
> git：**不提交**（lead 统一提交）；写域：`shared/iosMain`（ChatEngineBridge/SessionListBridge）+ `iosApp/Features/Chat`（ChatViewModel/ChatListView）+ `ios-port/{specs,handoffs}`

## 1. 写域确认 / 交付物清单

| 路径 | 类型 | 说明（关键行） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/ChatEngineBridge.kt` | 修改 | ① 新增 `viewingSessionId`（:96-107，「UI 当前查看会话」，与发送用 `activeSessionId` 分离）；② `onEngineEvent` 消费 `EngineEvent.UnreadHint` → `markUnreadIfNotCurrent`（:532-535）；③ 公开 `setActiveSession(sessionId:)`（:544）；④ `markUnreadIfNotCurrent`（:549-560）：非当前会话 → `chatRepo.sessions.incrementUnread(sessionId, count)`，当前会话忽略、失败静默；⑤ 自检夹具 `debugIncrementUnread(sessionId:delta:onDone:)`（:381-400） |
| `RhodesLink/shared/src/iosMain/.../iosbridge/SessionListBridge.kt` | 修改（仅 KDoc 1 行） | 注明未读自增归 ChatEngineBridge，本桥继续负责 markRead/markAllRead/列表 |
| `RhodesLink/iosApp/.../Features/Chat/ChatViewModel.swift` | 修改 | `load()` 打开会话：先 `setActiveSession` 再 `markSessionRead` 清零并重读会话（:100-114，日志 `chat unread active …`）；文件头补 T44 说明 |
| `RhodesLink/iosApp/.../Features/Chat/ChatListView.swift` | 修改 | ① `chatPath.count == 0`（返回列表）→ `setActiveSession("")` + reload（:136-146）；② `--unread-selftest` 自检钩子（:340-483：主流程 + 6 个 helper） |
| `ios-port/specs/unread-badge-spec.md` | 新增 | 原版语义对照 / iOS 取舍 / 自检矩阵 |
| `ios-port/handoffs/evidence/t44-*` | 新增 | 见 §5 |

**未改**：`ChatView.swift`（不在写域，故「离开会话页」的清理由 ChatListView 观察 `chatPath` 完成）、
`AppGraphClient.swift`（复用已有 `markSessionRead(id:)`；桥新方法直接经 `environment.bridge.chatEngineBridge` 调用）、
`shared/commonMain`（只复用 `SessionRepository.incrementUnread/markSessionRead`）、Android `app/`。

## 2. 未读语义选择（与 Android 对齐）

采用 **「非当前查看会话才 +count」**（对齐 Android `ChatViewModel.markUnreadIfNotCurrent`），而非
「统一 +1 再由打开会话清零」。理由与差异：

| 点 | Android 原版 | T44 iOS |
|---|---|---|
| 引擎发提示 | 成功生成可见回复后 `EngineEvent.UnreadHint(sessionId, count)`，count=可见段数（`ChatEngine.kt:466/842/1001/1052`） | 相同（复用同一 commonMain 引擎） |
| 当前会话来源 | `ChatViewModel._currentSession` | Swift 显式 `ChatEngineBridge.setActiveSession(sessionId)`：打开=`ChatViewModel.load()`，离开=`ChatListView` 的 `chatPath.count==0` |
| 未读自增 | 非当前会话才 `incrementUnread(count)` + `onUnhideSession` | 非当前会话才 `incrementUnread(count)`；Unhide 仍由既有 `EngineEvent.UnhideSession` 分支负责（T40 已有） |
| 已读清零 | `markSessionRead` | `load()` 内 `AppGraphClient.markSessionRead(id:)`（先 setActive 再 markRead，避免打开瞬间到达的回复被误计） |
| 列表角标 | Flow 自动刷新 | 已渲染；返回列表/`.refreshable` 时 reload（T40 已有的 `chatPath` 观察） |

**取舍说明**：没有采用「统一 +1 + 打开清零」，因为那会把「用户正看着的会话」的回复也计入未读，
与 Android 行为不一致，且会产生「角标在打开时闪一下」的观感问题。当前实现的行为与 Android 私聊一致。

## 3. 构建

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64       # BUILD SUCCESSFUL（evidence/t44-framework-link.log）
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t44" \
  CODE_SIGNING_ALLOWED=NO                                              # ** BUILD SUCCEEDED **（evidence/t44-build.log）
```

构建纪律：gradle/xcodebuild 前 `pgrep`；本卡与 T43（群聊）并行，Shared.framework/xcodebuild 多次排队（见 §6）。
**注意**：本机 Xcode 27.2 命令行不要传 `EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64`（会被解析成非法值导致尝试链接 x86_64）；
项目 pbxproj 已内置该排除。

## 4. 验证

### 4.1 `:shared:testDebugUnitTest` 不回归

`./gradlew --no-daemon --rerun-tasks :shared:testDebugUnitTest` → **BUILD SUCCESSFUL**
（`evidence/t44-shared-tests.txt`；test-results 汇总：**524 tests / 0 failures / 0 errors / 0 skipped**，
见 `evidence/t44-shared-tests-summary.txt`）。本卡未改 commonMain，测试数与基线一致。

### 4.2 模拟器自检（`--unread-selftest`，iPhone 17 / iOS 27.2，真实 DB）

复跑命令：

```bash
DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
xcrun simctl install $DEV RhodesLink/iosApp/DerivedData-t44/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --unread-selftest     # 约 15s 后结束，停在列表且保留角标
# 会话内打开 → 清零 + 截图
xcrun simctl terminate $DEV com.rhodeslink.app; xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://chat/session_amiya
# DB 复核
C=$(xcrun simctl get_app_container $DEV com.rhodeslink.app data)
sqlite3 "$C/Library/Application Support/rhodes.db" "select id,operatorId,unreadCount from chat_sessions order by lastTime desc;"
```

证据日志（`evidence/t44-selftest-logs.txt`，`simctl spawn log show` 摘录；本卡只用 `--unread-selftest`
（不带 `--selftest`），避免整壳自检的深链发送干扰未读断言，见 §6）：

```
=== unread selftest start ===
unread selftest baseline session=session_amiya title=阿米娅 unread=0
unread selftest seed session=session_amiya delta=2 error=- badge=2 PASS=true
chat unread active session=session_amiya unreadBefore=2 markReadError=- unreadAfter=0   ← ChatViewModel.load 清零
unread selftest openChat session=session_amiya activeReady=false unreadAfterOpen=0 PASS=true
unread selftest probe reply ok=true aiId=71 chars=86 error=
unread selftest viewingSend session=session_amiya sendOk=true unread=0 PASS=true        ← 当前会话内回复不增未读
chat unread active cleared (back to list)
unread selftest backToList session=session_amiya badge=0 PASS=true
unread selftest probe reply ok=true aiId=73 chars=87 error=
unread selftest aiReplyWhileAway session=session_amiya sendOk=true unread=1 PASS=true  ← 真实 UnreadHint 生效
unread selftest increment session=session_amiya before=1 after=2 error=- incrementPASS=true
=== unread selftest done allPASS=true ===
```

`activeReady=false` 是因为该轮未开 `--selftest`（`SelfTestLog` 落盘关闭，`waitForLogMarker` 退化短等待）；
该断言本身不依赖 marker，真实证据是 `chat unread active session=session_amiya unreadBefore=2 … unreadAfter=0` 行 + DB。

### 4.3 DB 复核（`evidence/t44-db-check.txt`）

| 阶段 | `chat_sessions.unreadCount` |
|---|---|
| 自检结束（预置 2 → 打开清零 → 当前会话不增 → 非当前 AI 回复 +1 → 桥 +1） | `session_amiya = 2`（其余 0） |
| 深链打开会话（`markRead`） | `session_amiya = 0` |
| 重启回列表 | `session_amiya = 0`（持久化，角标消失） |

### 4.4 截图（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t44-badge-visible.png` | 聊天列表：阿米娅行右上角未读角标「2」（真实 DB `unreadCount=2`） |
| `t44-chat-opened.png` | 深链打开阿米娅会话（聊天页）：两条 T44 探针消息 + 真实 AI 回复；打开即 markRead |
| `t44-badge-cleared.png` | 重启回列表：该会话角标消失（DB 已清零） |
| `t44-selftest-logs.txt` / `t44-db-check.txt` | 自检日志（6 项 allPASS）与三阶段 DB 值 |
| `t44-framework-link.log` / `t44-build.log` / `t44-shared-tests*.txt` | 构建与测试证据 |

## 5. 已知限制 / 后续建议

1. **离开会话页的清理位置**：`ChatView.swift` 不在 T44 写域，故「离开会话页」用 `ChatListView` 的
   `chatPath.count == 0` 观察；若后续 T45 改 ChatView，建议改为 `ChatView.onDisappear { model.deactivate() }`
   更直接（语义等价，当前已验证有效）。
2. **无本地通知/后台提醒**：Android `notifyIfBackground` 未移植（属通知服务批次）；T44 只落 DB 未读数。
3. **群聊会话**：`UnreadHint` 为私聊引擎事件，`GroupChatEngine` 的 `GroupEngineEvent.UnreadHint` 未接线；
   群聊未读语义建议在 T43 群聊桥后续任务处理。另：`--selftest` 整壳自检会用「列表第一条会话」做深链+发送，
   若第一条是 `group_elite`，私聊 `ChatEngineBridge.send` 会把私聊轮次写进群 session（T43 发现并记录给 lead）；
   T44 证据轮次只用 `--unread-selftest`（不带整壳自检）避开了该问题。建议后续给 `ChatEngineBridge.send`
   加 `group_` 前缀守卫或按会话类型路由。
4. **count 语义**：未读增量 = 可见段数（引擎 `aiResponseCount`），不是固定 +1；UI 角标显示 DB 原值。
5. **自检时序**：`--selftest` + `--unread-selftest` 同时开启时，本自检会等 `=== selftest done ===` 后再跑
   （避免整壳自检的深链/发送互相干扰）。

## 6. 协作记录

- 与 T43（群聊）并行：T43 先占用 iPhone 17；本卡按 pgrep 排队，先后经历「T43 Kotlin 未链入 → Swift cannot find
  GroupChatBridge」「T43 Swift 编译错误」两次整包构建阻塞，均通过等待 T43 修复后重跑解决（`t44-build.log` 最终 BUILD SUCCEEDED）。
- 模拟器证据轮次曾受外部脚本重复 `--open-url rhodes://chat/session_amiya` launch 干扰（未读被 markRead 清零），
  已重跑并即时抓取 DB + 截图，证据以 `t44-db-check.txt` 为准；T43 已确认非其脚本。
- 容器：`iPhone 17` 的应用数据容器在本轮 install 后为 `…/Application/239DB451-B912-42E3-BEDC-3CC74D1E3C0B`（DB `Library/Application Support/rhodes.db`）。
