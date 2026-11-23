# T40 实现 handoff：会话列表操作对齐 Android 原版（置顶 / 已读 / 移除 / 溢出菜单）

> 代理：impl-session-actions（task-40）｜规格：`ios-port/specs/session-list-actions-spec.md`（本卡产出）
> 状态：**实现完成；Shared.framework 重链 + xcodebuild 通过；iPhone 17 / iOS 27.2 模拟器实跑通过（含截图与 DB/NSUserDefaults 盘查）**
> 验收：⋮ 菜单（移除主页所有会话 / 全部标记已读）✓ / 长按菜单（置顶·取消置顶 / 标记已读 / 从主页移除）✓ /
> 置顶排序变化 ✓ / 标记已读角标消失 ✓ / 移除后列表消失 ✓ / 新消息后自动恢复 ✓ / 联系人「+」新建聊天 ✓ / 原铅笔+刷新按钮已移除 ✓
> git：**不提交**（lead 统一提交）；与 T37/T38 写域交叉文件只做追加/局部替换（见 §6）。

## 1. 写域确认 / 交付物清单

| 路径 | 类型 | 说明（file:line / 关键点） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/SessionListBridge.kt` | 修改 | 构造注入 `SettingsRepository` :38；`list` 过滤 `hiddenIds` + 清理失效 id :52-62；`pin`/`togglePin`/`markRead`/`markAllRead`/`hide`/`hideAll` :90-138；引擎钩子 `unhide` :145；`toDto` 补 `isPinned` :183 |
| `.../iosbridge/IosDtos.kt` | 修改（追加 1 字段） | `SessionDto.isPinned: Boolean` :20 |
| `.../iosbridge/IosAppGraph.kt` | 修改（1 行） | `SessionListBridge(chatRepo, settingsRepo, workScope, mainScope)` :55 |
| `.../iosbridge/ChatEngineBridge.kt` | 修改（1 分支） | `onEngineEvent` 处理 `EngineEvent.UnhideSession` → `sessionProvider.unhide(sessionId)` :145-149（对齐 Android `MainViewModel` 引擎事件取消隐藏） |
| `RhodesLink/iosApp/RhodesLink/Features/Chat/ChatListView.swift` | 重写 | 工具栏只剩「⋮」`Menu`（移除主页所有会话 / 全部标记已读）；删铅笔+刷新；行 `.contextMenu`（置顶/取消置顶、标记已读、从主页移除）；`ConfirmAction` 二次确认（文案逐字对齐原版）；保留 `.refreshable`；置顶图标 + 未读角标；空态「暂无聊天记录」 |
| `.../Bridge/AppGraphClient.swift` | 修改（追加） | `SessionItem.isPinned` :23/:35；`pinSession`/`togglePinSession`/`markSessionRead`/`markAllSessionsRead`/`hideSession`/`hideAllSessions` :196-263（async，返回 nil=成功） |
| `.../App/AppRouter.swift` | 修改（追加） | `openNewChatFromContacts()`（contactsPath 上 push `Route.newChat`）:160-165 |
| `.../Features/Roles/RolesView.swift` | 修改（追加 toolbar） | 联系人 Tab 右上「+」→ `router.openNewChatFromContacts()`（对齐 Android ContactsScreen 新建入口）:96-108 |
| `.../Features/Chat/SessionsAutomation.swift` | 新增 | `--sessions-selftest` 自动化：置顶排序/取消置顶/markRead/markAllRead/hide→send 恢复/hideAll→恢复 |
| `.../RhodesLinkApp.swift` | 修改（1 行 hook） | `.task` 中 `SessionsAutomation.runIfNeeded`（在 SelfTestDriver 前，打点进自检落盘日志） |
| `ios-port/specs/session-list-actions-spec.md` | 新增 | 操作矩阵（原版 ↔ iOS 实现位置）+ 桥接契约 + UI 取舍 |
| `ios-port/specs/ios-bridge-spec.md` | 修改（追加行） | §3.3 SessionListBridge 6 个新方法、§4 `SessionDto.isPinned` |
| `ios-port/handoffs/evidence/t40-*` | 新增 | 12 张截图 + 6 份日志/数据证据（见 §5） |

**不改** Android `app/`、不改 `shared/commonMain`（只读复用 `SessionRepository` / `SettingsRepository.hiddenIds`）、
不删除任何会话/消息（「移除」= 只写 `settings.hidden_ids`，与原版完全一致）。

## 2. 原版对照（详见 spec §2 操作矩阵）

| 原版（Android） | iOS 实现 | 语义一致性 |
|---|---|---|
| 聊天列表右上角唯一「⋮」菜单 | `ChatListView` `.toolbar` → `Menu(ellipsis.circle)` | ✓ 位置/唯一性/顺序一致 |
| 移除主页所有会话 → 确认弹窗 | `ConfirmAction.hideAll`（标题/正文原版逐字） | ✓ 确认后才执行；hiddenIds ∪ 全部 id |
| 全部标记已读 → 直接执行 | 菜单第二项 → `markAllRead()` | ✓ 无确认，DB `unreadCount=0` |
| 长按行 → 置顶/取消置顶、标记已读、从主页移除 | `.contextMenu` 同三项、同顺序 | ✓（iOS 用系统 contextMenu 等价长按） |
| 从主页移除 → 确认弹窗（原版文案） | `ConfirmAction.hide`（标题「从主页移除」+ 原版正文） | ✓ 文案逐字一致 |
| 新消息 → 会话自动回主页 | `EngineEvent.UnhideSession` → `SessionListBridge.unhide` | ✓ 引擎事件钩子 |
| 排序 pinned 在前、其余 lastTime 倒序 | 复用 SQL `getAllSessions ORDER BY isPinned DESC,lastTime DESC` | ✓ 同一查询 |
| 清理失效 hiddenIds | `list` 内裁剪 | ✓（MainViewModel:654） |
| 新建聊天不在聊天 Tab，在联系人 Tab | 删除聊天工具栏铅笔；联系人工具栏「+」→ NewChatView | ✓ |
| 刷新（原版 Flow 自动） | 保留下拉 `.refreshable`（非工具栏按钮） | ✓ 工具栏已无刷新按钮 |

## 3. 构建

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64        # BUILD SUCCESSFUL in 2m 17s
# → evidence/t40-framework-link.log（含 SessionListBridge 新方法 / SessionDto.isPinned 的 ObjC 头验证）
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t40" CODE_SIGNING_ALLOWED=NO
# → ** BUILD SUCCEEDED **（evidence/t40-build.log；独立 DerivedData-t40，不与 T37/T38 的 DerivedData 冲突）
```

构建纪律：gradle/xcodebuild 前 `pgrep -fl gradle|xcodebuild`；本次在 T38 的 framework link 结束后开始（未并发），
xcodebuild 在 T37 E2E 期间启动（其使用 DerivedData-t37 + iPhone 17e，本卡使用 DerivedData-t40 + iPhone 17，互不干扰）。

## 4. 验证

### 4.1 Bridge 层自动化（`--selftest --sessions-selftest`，iPhone 17 / iOS 27.2，全新安装）

`evidence/t40-selftest.log`（两次复跑均全 PASS，以下为最终一次原文摘录）：

```
sessions selftest baseline count=4 ids=["session_misery","session_scout","session_mon3tr","session_amiya"] pinned=[]
sessions selftest pin session=session_amiya ... head=session_amiya headIsPinned=true PASS=true
sessions selftest unpin session=session_amiya ... tail=session_amiya PASS=true
sessions selftest markRead session=session_misery before=1 after=0 error=- PASS=true
sessions selftest markAllRead error=- totalUnread=0 PASS=true
sessions selftest hide session=session_misery error=- count=4->3 gone=true PASS=true
sessions selftest hiddenIds afterHide=["session_misery"]
sessions selftest unhideByNewMessage session=session_misery sendOk=true demo=true restored=true PASS=true
sessions selftest hideAll error=- count=4->0 empty=true PASS=true
sessions selftest hiddenIds afterHideAll=["session_misery","session_scout","session_mon3tr","session_amiya"]
sessions selftest hideAllRestore count=4 restored=true PASS=true
```

覆盖：置顶排序生效（末位置顶 → 第 0 位）、取消置顶复位、markRead（unread 1→0）、markAllRead（总和 0）、
hide（列表 4→3 且该会话消失 + hidden_ids 写入）、新消息恢复（send → 引擎 UnhideSession → restored=true）、
hideAll（4→0 空列表）、恢复 hidden_ids（0→4）。

### 4.2 iOS UI 实跑（iPhone 17 / iOS 27.2，截图证据见 §5）

1. 工具栏只有「⋮」（`t40-toolbar-menu.png`；AXe 树中唯一 toolBar 元素 label=「会话菜单」，无铅笔/刷新）。
2. 「⋮ → 全部标记已读」：预置未读 2/1 的会话角标消失（`t40-chat-list-badges.png` → `t40-mark-all-read.png`），
   DB 核对 `t40-mark-all-read-db.txt`：4 个会话 unreadCount 全 0。
3. 长按会话 → contextMenu 三项（`t40-context-menu.png`：置顶 / 标记已读 / 从主页移除）。
4. 长按 → 置顶：会话移入置顶区（`t40-pinned.png`），DB 核对 `t40-db-verify.txt`：`isPinned=1` 的两条排在最前
   （SQL `ORDER BY isPinned DESC,lastTime DESC`；UI 顺序与 DB 一致）。
5. 长按 → 从主页移除：确认弹窗（`t40-hide-confirm.png`）→ 确认后列表少一条（`t40-after-hide.png`），
   DB/plist 核对 `t40-db-verify.txt`：`hidden_ids` 持久化含被移除 id，**chat_sessions 4 行原样保留**（未删库/未删消息）。
6. 「⋮ → 移除主页所有会话」：确认弹窗（`t40-hideall-confirm.png`，原版文案）→ 空列表「暂无聊天记录」
   （`t40-empty-after-hideall.png`）；重启后仍空（`t40-persisted-hidden-list.png`，hidden_ids 持久化）。
7. 新消息恢复：向已隐藏会话发消息（真实 ChatEngineBridge → ChatEngine 落库 id=5/6），引擎 `UnhideSession`
   → `hidden_ids` 从 4 个减少到 3 个，重启后聊天主页重新出现该会话（`t40-unhidden-after-new-message.png`，
   `t40-unhide-verify.txt`）；其余仍隐藏（收到各自新消息后分别恢复，与原版「任一会话收到新消息后自动重新出现」一致）。
8. 联系人 Tab：「+」按钮（`t40-contacts-tab.png`）→ NewChatView「选择一位角色开始对话」（`t40-contacts-newchat.png`）。

## 5. 证据清单（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t40-framework-link.log` | `:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL（2m17s） |
| `t40-build.log` | xcodebuild ** BUILD SUCCEEDED ** |
| `t40-selftest.log` / `t40-selftest-end.png` | `--selftest --sessions-selftest` 全链路自动化日志（全 PASS）+ 结束画面 |
| `t40-chat-list-badges.png` | 聊天列表：4 会话、2 个未读角标、工具栏仅「⋮」 |
| `t40-toolbar-menu.png` | 「⋮」菜单：移除主页所有会话 / 全部标记已读 |
| `t40-mark-all-read.png` + `t40-mark-all-read-db.txt` | 全部标记已读后角标消失 + DB unreadCount 全 0 |
| `t40-context-menu.png` | 长按 contextMenu：置顶 / 标记已读 / 从主页移除 |
| `t40-pinned.png` | 置顶后行内 pin 图标 + 置顶区排前 |
| `t40-hide-confirm.png` | 「从主页移除」确认弹窗（原版文案逐字） |
| `t40-after-hide.png` | 确认移除后该会话从列表消失 |
| `t40-hideall-confirm.png` / `t40-empty-after-hideall.png` / `t40-persisted-hidden-list.png` | 移除所有会话确认弹窗 → 空态 → 重启仍空（持久化） |
| `t40-unhidden-after-new-message.png` / `t40-unhide-verify.txt` | 新消息后该会话自动恢复（hidden_ids 4→3，DB 消息落库） |
| `t40-db-verify.txt` | 移除后 `hidden_ids` 持久化 + `chat_sessions` 完整（未删库）+ isPinned 排前 |
| `t40-contacts-tab.png` / `t40-contacts-newchat.png` | 联系人 Tab「+」→ 新建聊天选角页 |
| `t40-unread-seeded.txt` | 未读角标截图轮次的 DB 预置值（unreadCount 2/1） |

## 6. 协作与已知限制

1. **写域交叉**：T37/T38 同时在 `RhodesLink/iosApp`、`shared/src/iosMain/iosbridge`、`ios-port/{specs,handoffs}` 写。
   T40 只做「追加」或局部替换（AppGraphClient 追加会话操作方法段、IosAppGraph 改 1 行、ChatEngineBridge 加 1 分支、
   ios-bridge-spec 追加行），最终工作树构建通过；未触碰 T37/T38 的 Provider/Backup/DataBackup 代码。
2. **已知限制（非 T40 引入，建议后续任务）**：`ChatEngineBridge.send()` 无 `sessionId` 参数，内部用
   `ensureSession()`（DB 第一条会话）——即聊天页固定向「DB 排序第一条」发送。UI 验证轮次从阿米娅聊天页发送时
   实际落到了 Misery；这仍完整验证了「收到新消息的会话自动恢复」语义（恢复的是 Misery）。若要支持
   「在任意会话页发送」，需要给 `ChatEngineBridge.send` 增加 sessionId（属 T37/聊天发送链路改造，牵扯 ChatView/ChatViewModel，
   建议单独排期）。
3. **未读计数来源**：iOS 桥当前不处理 `EngineEvent.UnreadHint`（T29 起既有限制），因此 AI 回复不会自增 `unreadCount`；
   角标显示已在 DB 预置未读时验证通过（`t40-chat-list-badges.png`），「标记已读 / 全部标记已读」归零语义已验证。
   若要 iOS 未读角标自动增长，需在 `ChatEngineBridge` 消费 `UnreadHint` → `incrementUnread`（建议后续任务）。
4. `hiddenIds` 与 Android 使用同一 settings 键 `hidden_ids`（StringSet JSON），备份/恢复互通
   （`BackupPortableSettings.hidden_ids`）。
5. 移除后无「恢复主页显示」的显式 UI 操作（与原版一致：只能等新消息；或数据恢复/重装）。
