# T43 实现 handoff：群聊完整流程（桥接 + 群聊页 + 成员/权限 + 自动互动 + 深链）

> 代理：impl-group-chat（task-43）｜规格：`ios-port/specs/group-chat-ui-spec.md`（本卡产出）
> 上游：`ios-port/specs/group-chat-engine-spec.md`（T20 批准）、`ios-port/handoffs/impl-groups.md`（T23 引擎，151 测试）
> 状态：**实现完成；Shared.framework 重链 + xcodebuild BUILD SUCCEEDED；:shared:testDebugUnitTest 524/0 零回归；iPhone 17 / iOS 27.2 模拟器实跑：群列表/详情/成员/分页/真实发送/自动开关/分段撤回/深链 9 项全 PASS（sqlite3 DB 复核 + 3 张截图）**
> git：**不提交**（lead 统一提交）；写域见 §1。未改 T44 正在改的 `ChatEngineBridge.kt` / `SessionListBridge.kt` / `ChatListView.swift` / `ChatViewModel.swift`。

## 1. 写域确认 / 交付物清单

| 路径 | 类型 | 说明 |
|---|---|---|
| `shared/src/iosMain/.../iosbridge/GroupChatBridge.kt` | **新增**（~800 行） | 群列表 / 群详情+成员名册 / 群消息分页（多发言人 segments）/ `send`（真实 `GroupChatEngine`）/ `cancel` / `setActiveGroup` / `setAutoGroupChatEnabled` / `autoGroupChatStatus` / `recallSegment` / `retryMessage` / `createGroup` / `isDemoMode`；DTO：`GroupSummaryDto`/`GroupDetailDto`/`GroupMemberDto`/`GroupMessageDto`/`GroupMessageSegmentDto`/`GroupMessagePageDto`/`GroupSendResultDto`；内部：`GroupRoutingStreamingClient`（真实供应商/演示路由，surface="group"）+ `DemoGroupStreamingClient`（tagged 协议演示桩） |
| `shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 修改（+15 行） | 新增 `groupChatBridge` 实例 + `fun groups(): GroupChatBridge` 访问器 |
| `iosApp/RhodesLink/Bridge/GroupChatClient.swift` | **新增** | 桥的 Swift 包装（async/await + Result + GroupSendHandle）：Swift 值类型 `GroupItem`/`GroupMemberItem`/`GroupDetailItem`/`GroupMessageItem`/`GroupMessageSegmentItem`/`GroupMessagePage`/`GroupSendResultItem`；`AppGraphClient` 扩展：loadGroups/loadGroupDetail/loadGroupMessagePage/sendGroupChat/cancelGroupChat/setActiveGroup/isGroupDemoMode/setGroupAutoChat/groupAutoChatStatus/recallGroupSegment/retryGroupMessage/createGroup |
| `iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 修改（+4 行） | `groupBridge` accessor（`graph.groups()`），只供 extension 使用，不向上导出 Kotlin 类型 |
| `iosApp/RhodesLink/Features/GroupChat/GroupChatViewModel.swift` | **新增** | 群详情/成员 + 分页 + 发送/取消 + 多发言人行展开（`GroupBubbleRow`：incoming/outgoing/system/narration）+ 自动开关 + 分段撤回/重试 + 乐观气泡 + 未读排除（setActiveGroup） |
| `iosApp/RhodesLink/Features/GroupChat/GroupChatView.swift` | **新增** | 群聊页（时间线/多发言人气泡/旁白行/系统行/时间分隔/输入栏复用/长按菜单/成员 Sheet `GroupMembersSheet`：成员/禁言/权限/自动互动 Toggle/总开关/轮数上限/规则）；`--group-open-members` 证据钩子 |
| `iosApp/RhodesLink/Features/GroupChat/NewGroupView.swift` | **新增** | 新建群聊 Sheet（群名 + 角色多选 ≥2）→ `createGroup` |
| `iosApp/RhodesLink/Features/GroupChat/GroupChatAutomation.swift` | **新增** | `--group-selftest` 全链路自检 + 独立日志 `GroupSelfTestLog`（Documents/ios-group-selftest.log） |
| `iosApp/RhodesLink/DesignSystem/MessageBubble.swift` | 修改（+10 行） | `senderName` 参数：接收方向气泡上方显示发送者名（私聊不传 → 行为不变） |
| `iosApp/RhodesLink/Features/Roles/RolesView.swift` | 修改（+~120 行） | 联系人页「群聊」分区（数量角标 + 群行 + 未读/成员数/禁言/lastMessage）+「新建群聊」Sheet + 点击进入群聊；`--group-selftest` 触发点 |
| `iosApp/RhodesLink/App/AppRouter.swift` | 修改 | `Route.groupChat(sessionId:)` + `openGroupChat` + 深链 `rhodes://group/{id}` → 群聊页（原占位路由替换） |
| `iosApp/RhodesLink/Features/Common/RouteView.swift` | 修改（+3 行） | `case .groupChat` → `GroupChatView` |
| `ios-port/specs/group-chat-ui-spec.md` | **新增** | 页面结构/交互矩阵（16 项）/桥接契约/与私聊差异/限制/验收 |
| `ios-port/handoffs/evidence/t43-*` | **新增** | 3 张截图 + 自检日志 + DB 复核 + framework/build 日志 + shared 测试汇总 |

**未改动**：Android `app/`、`shared/commonMain`（零改动，纯 iosMain 桥 + Swift）、`ChatEngineBridge.kt`/`SessionListBridge.kt`/`ChatView*`（T44 写域）、其他代理文件。

## 2. 桥接契约（`GroupChatBridge`，全部回调 Main 队列 / 不导出 Flow）

| 方法 | 回调 | 语义 |
|---|---|---|
| `listGroups(onResult)` | `(List<GroupSummaryDto>, String?)` | 过滤 `operatorId LIKE 'group_%'`；**首次调用幂等播种**（`ensurePresetOperators` + 群数=0 时 `initPresetGroups`，等价 Android SessionRepository:102-118） |
| `groupDetail(sessionId, onResult)` | `(GroupDetailDto?, String?)` | 群名/mode/rules/成员名册/禁言（`mutedMembers` 命中 id 或名字）/`isResolved`/`permissionText`/autoEnabled/autoMasterEnabled/autoStatus/autoMaxRounds |
| `page(sessionId, beforeId, limit, onResult)` | `(GroupMessagePageDto, String?)` | 与 MessageHistoryBridge 同游标语义（`(timestamp,id)`、多取 1 判 hasMore、50/页，上限 200）；AI 行 → `GroupReplyParser.decodeGroupResultsOrNull` 拆 segments + `isGroupSegmentRecalled` 标记 |
| `send(sessionId, text, onDelta, onDone)` | `(String)` / `(GroupSendResultDto)` | 真实 `GroupChatEngine.sendGroupMessage`：用户消息同事务落 reply_turn（surface=group）→ 模型/演示通道 → `ai_json` 整包落库；onDelta 落库后 4 字/25ms 打字机（「成员名：台词」逐行） |
| `cancel()` | — | `engine.cancelGroupRequests` + job cancel |
| `setActiveGroup(sessionId)` | — | 未读自增排除当前查看群（`UnreadHint` → 非当前群 `incrementUnread`） |
| `setAutoGroupChatEnabled(sessionId, enabled, onDone)` | `(String?, String?)` | 引擎开关 → 回调（新状态文案, 错误）；状态文案 = `autoGroupChatStatus` |
| `autoGroupChatStatus(sessionId)` | 同步 String | 本群已关闭/总开关关闭/达上限/正在生成/等待下一轮（约 N 秒） |
| `recallSegment(sessionId, messageId, segmentIndex, onDone)` | `(String?)` | 群聊独有分段撤回（全段撤回→引擎删整条；成功路径含情绪快照重算 + 上下文重建意图） |
| `retryMessage(sessionId, messageId, onDone)` | `(String?)` | 引擎 `retryFailedMessage`（仅 send_failed/gift_reply_failed） |
| `createGroup(name, memberIds, onDone)` | `(String?, String?)` | 写 `chat_sessions`（id=operatorId=`group_<hex>`，members 逗号分隔，≥2 位真实 operator） |
| `isDemoMode()` / `imagePath(imageId)` | 同步 | 演示通道标记 / 媒体 ID→绝对路径（群图片消息预留） |

**AI 通道**：`ProviderConfigResolver` 每次发送重新解析 → 已配置走 `RealProviderStreamingClient`（Ktor Darwin SSE，surface="group"）；
未配置（本环境）走 `DemoGroupStreamingClient`：从提示词「发言标识：id」解析真实成员，输出 tagged 协议
（合法【群聊回合状态】块 + `【发言人: id】/【心情】/台词` ×2），引擎解析/归一化/落库/结算与真实模型完全一致。

## 3. 验证证据（`ios-port/handoffs/evidence/`）

### 3.1 `--group-selftest` 自检（iPhone 17 / iOS 27.2，`t43-selftest.log`）

启动：`xcrun simctl launch $DEV com.rhodeslink.app --tab contacts --group-selftest`
（`--tab contacts` 落到联系人页触发自动化；日志独立写 Documents/ios-group-selftest.log，不依赖 `--selftest`）

```
=== group selftest start ===
group list count=1 ids=["group_elite"] names=["精英干员茶水间"]
group list first id=group_elite name=精英干员茶水间 members=6 muted=0 lastMessage=阿斯卡纶：… unread=0
group detail id=group_elite name=精英干员茶水间 mode=online members=6 muted=0 auto=false master=true maxRounds=20 rules=精英干员的非正式聊天群…
group member id=blaze name=煌 muted=false permission=可发言 resolved=true
group member id=rosmontis name=迷迭香 muted=false permission=可发言 resolved=true
group member id=logos name=逻各斯 muted=false permission=可发言 resolved=true
group member id=misery name=Misery muted=false permission=可发言 resolved=true
group member id=ascalon name=阿斯卡纶 muted=false permission=可发言 resolved=true
group member id=arc name=电弧 muted=false permission=可发言 resolved=true
group history baseline total=10 count=10 hasMore=false newestId=63
group auto toggle ON ok status=等待下一轮自动聊天（约118秒）
group auto toggle OFF ok status=本群已关闭
group send ok=true user=64 ai=65 speakers=电弧、阿斯卡纶 narration=0 demo=true chars=120 error=
group send text=电弧：… | 阿斯卡纶：…
group history after send total=12 (baseline=10) newestId=65 newestType=ai_json aiRows=6 newestAi=65
group history newest id=65 isMe=false type=ai_json sender=精英干员茶水间 time=10:33 segments=2
group ai segment idx=0 speaker=电弧 emotion=平静 narration=false text=…
group ai segment idx=1 speaker=阿斯卡纶 emotion=温和 narration=false text=…
group recallSegment messageId=65 index=0 error=- segmentsBefore=2/visible=2/recalled=0 segmentsAfter=2/visible=1/recalled=1 removedWhole=false PASS=true
group deeplink url=rhodes://group/group_elite handled=true route=deeplink group group_elite
=== group selftest done ===
group view page1 session=group_elite count=12 hasMore=false total=12 newestId=65
group view loaded session=group_elite title=精英干员茶水间 members=6 auto=false status=本群已关闭 demo=true
```

（同一代码多轮复跑：baseline 0→2→4→10，total 每次 +2，证明真实 DB 持久化；每一轮 `recallSegment PASS=true`。）

结论：群列表（含播种）、群详情/成员/禁言/权限、分页、自动互动 ON/OFF、真实发送、AI 多发言人 segments、分段撤回、深链 9 项全 PASS；多轮复跑一致（baseline 0→2→4→10，每轮 total +2）。

### 3.2 DB 复核（`t43-db-check.txt`，sqlite3 直查运行中容器 `Library/Application Support/rhodes.db`；容器 UUID 随每次 install 变化，复跑请用 `xcrun simctl get_app_container` 取当前路径）

- `chat_sessions`：`group_elite | group_elite | 精英干员茶水间 | blaze,rosmontis,logos,misery,ascalon,arc | (muted 空) | unreadCount=0 | online | lastMessage=阿斯卡纶：…`（引擎写回 lastMessage；打开群聊 markSessionRead 清零未读）；
- `chat_messages`：`type=text isMe=1`（用户消息 id=54/56/58/62/64…）；`type=ai_json isMe=0 senderName=精英干员茶水间`（id=55/57/59/63/65…，content 为 GroupReplyJson 数组），最新一条：
  `[{"speaker":"电弧",…,"speakerId":"arc","recalled":true},{"speaker":"阿斯卡纶",…,"emotion":"温和","speakerId":"ascalon"}]`（多发言人 JSON + 分段撤回 recalled 标记）；
- `reply_turns`：`group:group_elite:54 / :56 / :58 / :62 / :64` 全部 **surface=group / triggerKind=manual / status=succeeded**（群轮次租约真实落库）；
- settings（NSUserDefaults）：`group_auto_group_elite` / `group_auto_plan_complete_group_elite` 键存在（自动开关 ON→OFF 落盘）。
- **T44 收尾后复核（sim 交还时，同一 DB 数据跨 install 容器迁移仍在）**：`group_elite` 12 条群消息（max id=65）、`reply_turns` surface=group 5 行（54/56/58/62/64，全部 succeeded）、`session_amiya unread=0`（T44 markRead 自检）——T43 证据与当前模拟器 DB 一致，可直接复核。

> 跨任务并行备注（已写入 `t43-db-check.txt`）：`chat_messages id=60/61` 是 T44（impl-unread）未读自检在
> `group_elite` 上经**私聊 ChatEngineBridge** 写入的私聊格式消息（id=61 content 为 private `OfflineModeResponse` JSON），
> 不是 T43 群聊引擎产物（T43 行为 GroupReplyJson 数组 + reply_turns surface=group 可区分）。该现象暴露一个真实风险：
> 私聊 `ChatEngineBridge.send` 未拒绝 `group_` 前缀会话，当「最新会话」是群会话时会把私聊轮次写进群 session——
> 建议 T44/T45 在 `ChatEngineBridge.send` 加群会话守卫，或 Swift 侧按前缀路由到 `GroupChatView`（T43 桥无需改）。

### 3.3 截图

| 文件 | 内容 |
|---|---|
| `t43-group-chat.png` | 群聊页：群名「精英干员茶水间」+ 副标题「6 位成员 · 在线群聊 + 演示标记」+ 用户气泡 + 多发言人气泡「阿斯卡纶（温和）」+ 输入栏「在群里说点什么…」。**注意**：这是分段撤回后的状态（`电弧` 段已撤回，视觉上只剩 `阿斯卡纶` 段），证明撤回对 UI 生效 |
| `t43-group-members.png` | 成员 Sheet（large detent）：群资料（群名/模式/成员 6 人/禁言 无/群规则）+「自动互动」Toggle；下方为成员列表（可滚动） |
| `t43-contacts-groups.png` | 联系人页「群聊」分区：群数量角标 1 + 「新建群聊」按钮 + 群行（精英干员茶水间 / 6 位成员 · 在线 / lastMessage 预览）；未读角标在另一次运行中为 2（`UnreadHint` 自增），进入群聊后 `markSessionRead` 清零，故最终截图无角标 |

### 3.4 构建 / 回归

- `t43-framework-link.log`：`:shared:linkDebugFrameworkIosSimulatorArm64` **BUILD SUCCESSFUL**（gradle --no-daemon，52s；Shared.h 已导出 `groups()`/`GroupChatBridge`/全部 DTO）。
- `t43-build.log`：`xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedData-t43 CODE_SIGNING_ALLOWED=NO` **BUILD SUCCEEDED**（0 error）。
- `t43-shared-tests.txt`：`:shared:testDebugUnitTest --rerun-tasks` → **524 tests / 0 failures / 0 errors / 0 skipped**（56 个 test-results XML；与 T42 基线 524 一致，本卡未改 commonMain，零回归）。
- 复跑命令：
  ```bash
  DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
  xcrun simctl install $DEV RhodesLink/iosApp/DerivedData-t43/Build/Products/Debug-iphonesimulator/RhodesLink.app
  xcrun simctl launch $DEV com.rhodeslink.app --tab contacts --group-selftest
  xcrun simctl get_app_container $DEV com.rhodeslink.app data   # → Documents/ios-group-selftest.log
  sqlite3 "<container>/Library/Application Support/rhodes.db" \
    "select id,sessionId,surface,triggerKind,status from reply_turns where surface='group';"
  sqlite3 "<container>/Library/Application Support/rhodes.db" \
    "select id,isMe,type,substr(content,1,80) from chat_messages where sessionId='group_elite' order by id;"
  # 成员 Sheet 截图：--tab contacts --open-url rhodes://group/group_elite --group-open-members
  ```

## 4. 与 Android oracle 的能力矩阵（摘要，详见 spec §2）

| 能力 | 引擎（T23） | iOS 本卡 |
|---|---|---|
| 群列表 / 群详情 / 成员 / 禁言 | `GroupChatExtras`/ChatSession | ✅ 真实 DB 投影（成员权限=禁言，与原版同维度） |
| 发送（用户消息 + ai_json 多发言人 + 租约） | `sendGroupMessage` | ✅ 真实引擎 + DB 复核（reply_turns surface=group） |
| 分页（50/页） | `ChatStore.getRecentMessagesSync/getMessagesBefore` | ✅ 游标分页 + segments 拆分 |
| 自动互动开关 / 计划状态 | `setAutoGroupChatEnabled`/`autoGroupChatStatus` | ✅ ON/OFF + 状态文案落库（触发通道见 L3） |
| 分段撤回（情绪快照重算 + 上下文重建意图） | `recallMessageSegment` | ✅ 长按「撤回这一段」+ 自检 PASS |
| 重试未送达 | `retryFailedMessage` | ✅ 气泡长按「重试」 |
| 新建群聊（写 chat_sessions） | 引擎不负责（Android NewGroup/saveGroup） | ✅ 最小实现 `createGroup`（≥2 成员、group_<hex>） |
| 群图片/礼物/重启/删群/编辑群资料 | 引擎有 sendGroupImageMessage/sendHiddenGiftMessage/restartGroupSession/deleteGroup | ❌ 本卡范围外（L2/L4） |

## 5. 限制项与差异白名单

| 编号 | 限制 | 原因 / 归属 |
|---|---|---|
| L1 | 聊天主页点「群会话」仍进私聊 `ChatView`（不拆发言人）；且私聊 `ChatEngineBridge.send` 不拒绝 `group_` 前缀会话（T44 自检曾在 `group_elite` 写入私聊格式消息 id=60/61） | 群/私聊共用 `Route.chat` 且桥层无 surface 校验。路由分流/守卫需改 T44 写域 `ChatListView/ChatViewModel/ChatEngineBridge.kt`，本卡不越界（建议：Swift 侧按 `operatorId.hasPrefix("group_")` 路由 `openGroupChat`，或 `ChatEngineBridge.send` 开头 `require(!session.operatorId.startsWith("group_"))`）。已在本 handoff §3.2 记录为给 lead 的注意点 |
| L2 | 群资料编辑（改名/成员增删/禁言编辑/规则/模式切换/重启/删群/换背景/聊天记录导出/TTS） | Android 对应 `GroupEditScreen`/`GroupDetailScreen` 菜单；归 A2 群资料卡后续波次。桥已留好数据（`groupDetail` 返回 rules/mode/members）与引擎入口 |
| L3 | 自动互动开关只落库计划状态，不自动产生轮次 | 到期触发依赖通用 `DelayedTaskLayer` 的 Timer/BGTask 通道适配（T22/A7 胶水批）；本卡只做开关+状态展示，与 T22 §A-12 冻结契约一致 |
| L4 | 群图片/礼物消息未暴露 UI | 引擎具备 `sendGroupImageMessage`/`sendHiddenGiftMessage`；需 PhotosPicker→媒体入库→识图与礼物选择 UI（私聊 T42 已有管线），本卡聚焦文本群聊 |
| L5 | 未读语义 | `UnreadHint` 仅在「非当前查看群」时 `incrementUnread`（`setActiveGroup` 排除当前群）；返回会话列表刷新由列表页自身 reload 承担（T44 未读链路独立） |
| L6 | 自动互动总开关（`autoAiEnabled`）只读展示 | 与 Android 一致：群开关 × 全局总开关；本卡不改全局设置 |
| L7 | 长按菜单/成员 Sheet 的自动化触控 | 本环境无 UI 自动化工具（dsh-ios 已禁用）；菜单动作路径由 bridge/引擎自检 + 编译保证，Sheet 截图经 `--group-open-members` 启动参数打开；真机需人工长按确认 |

## 6. 给 lead 的注意点

1. **共享工作区事件**：本卡新增 Swift 文件曾在 T44 的 xcodebuild 期间被编入、因其时 Shared.framework 未含 `GroupChatBridge` 导致 T44 一次构建失败（3 error，均为 cannot find type）；framework 重链后已恢复，已与 impl-unread 同步并确认其可正常构建。后续若再改 Kotlin，需先重链 framework 再让 xcodebuild 跑。
2. **构建命令**：`xcodebuild` 不要加 `EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64` 命令行覆盖（Xcode 27.2 解析成畸形设置会同时编 x86_64；pbxproj 已内置该设置）。
3. **首次群列表播种**：`listGroups` 在群数=0 时会写预设角色+预设群（幂等）。这会让全新安装的联系人页立刻出现「精英干员茶水间」。若 lead 不希望读接口带写副作用，可移到 `IosAppGraph.bootstrap`（本卡选择读时兜底，保证升级容器也有群可进）。
4. **演示 AI 的发言人是按 id 排序的前两位成员**（arc/ascalon → 电弧/阿斯卡纶），真实供应商配置后由模型决定；所有解析/落库路径与真实模型相同。
5. **群聊打开即已读**：`GroupChatViewModel.load()` 调 `markSessionRead`（与 Android 进入群聊清零未读同语义）；未读数由 `GroupChatBridge` 消费 `UnreadHint`：仅当群不是当前查看群（`setActiveGroup`）时 `incrementUnread`。
6. **跨任务风险（建议后续修）**：私聊 `ChatEngineBridge.send` 未拒绝 `group_` 前缀会话——T44 自检因「最新会话=group_elite」把私聊轮次写进群 session（chat_messages id=60/61，私聊 JSON 进群 session，群页面渲染为原始 JSON 文本）。修复建议：桥层守卫（`ChatEngineBridge.kt`，T44 写域）或 Swift 路由分流（`ChatListView`，T44 写域）；T43 的 `GroupChatBridge` 侧已按 `operatorId LIKE 'group_%'` 严格取群，不受影响。
7. **本轮未改**：`ChatEngineBridge.kt`/`SessionListBridge.kt`/`ChatView*`（T44）、`commonMain`（零改动）、Android app（零改动）。
