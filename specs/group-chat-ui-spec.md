# T43 群聊 UI 规格卡（群列表 / 群聊页 / 成员与权限 / 自动互动）

> 作者：impl-group-chat（task-43）｜日期：2026-11-23｜分支 ios-port（Kotlin 2.3.21 / Xcode 27.2 / iPhone 17 iOS 27.2）
> 上游依据：`ios-port/specs/group-chat-engine-spec.md`（T20 已批准，群聊轮次引擎 20 条契约）、
> `ios-port/handoffs/impl-groups.md`（T23 引擎实现，151 测试）、`ios-port/specs/ios-bridge-spec.md`（桥接纪律）。
> Android oracle：`app/.../viewmodel/GroupChatViewModel.kt`、`app/.../ui/group/GroupDetailScreen.kt`、
> `app/.../ui/group/GroupEditScreen.kt`、`app/.../ui/contacts/ContactsScreen.kt`、`app/.../navigation/AppNavigation.kt:170-205,277-291`。
> 结论先行：**群聊全链路已在 iOS 落地并模拟器实跑**：群列表（含预设群播种）、群消息分页（多发言人拆分）、
> 真实 `GroupChatEngine` 发送（用户消息 + `ai_json` 落库、surface=group）、成员/禁言/权限读取、自动互动开关、
> 分段撤回、`rhodes://group/{id}` 深链。**不实现**：群资料编辑（改名/成员增删/规则）、礼物、图片/语音、重启群聊
> （见 §5 限制与差异白名单）。

## 1. 页面结构

### 1.1 联系人页「群聊」分区（`Features/Roles/RolesView.swift`）

| 区块 | 内容 | 数据来源 |
|---|---|---|
| 分区头 | 「群聊」图标 + 群数量角标 + 「新建群聊」按钮（`plus.circle.fill`） | `GroupChatBridge.listGroups` |
| 群行 | 群头像占位（`person.3.fill` 品牌橙圆）× 群名 + 未读角标 + 末条消息时间；第二行「N 位成员 · 在线群聊[ · M 人禁言]」；第三行 lastMessage | `chat_sessions`（`operatorId LIKE 'group_%'`）+ `mutedMembers` |
| 空态 | 「还没有群聊。点「新建群聊」选择成员创建；首次打开会自动播种预设群。」 | — |
| 点击 | push `Route.groupChat(sessionId:)` 到联系人 Tab 的 NavigationStack | — |
| 新建群聊 | Sheet：群名 + 角色多选（≥2 人）→ `GroupChatBridge.createGroup` → 写 `chat_sessions` 一行 → 进入群聊页 | `operators` 名册 |

首次打开群列表时幂等播种：`ensurePresetOperators()` + 若群数=0 则 `initPresetGroups()`
（等价 Android `SessionRepository.initPresetGroups`：预设群「精英干员茶水间」= `group_elite`，成员
blaze/rosmontis/logos/misery/ascalon/arc）。

### 1.2 群聊页（`Features/GroupChat/GroupChatView.swift`）

布局沿用私聊 `ChatView`（导航 chrome + LazyVStack 时间线 + `GlassInputBar`，聊天区无玻璃、进入后隐藏 Tab Bar）。

| 区域 | 内容 |
|---|---|
| 导航标题 | 群名（headline）／副标题「N 位成员[ · M 人禁言] · 在线群聊」+ 演示模式标记 |
| 导航右侧 | `person.2.fill` → 成员 Sheet |
| 时间线 | 时间分隔条（首条或间隔 ≥5 分钟，复用 `TimeSeparator`）；用户消息右侧气泡；AI 回复**按发言人拆成多个左侧气泡**（气泡上方 `senderName`，含「（心情）」后缀）；旁白居中斜体灰字（不套气泡）；系统消息居中灰胶囊；发送中显示「群成员」流式占位气泡（4 字/25ms 打字机） |
| 气泡长按 | 复制；AI 气泡「撤回这一段」（二次确认）；`send_failed`/`gift_reply_failed`「重试」 |
| 输入栏 | 复用 `GlassInputBar`（placeholder「在群里说点什么…」；生成中变停止按钮；`+` 附件按钮本卡未接图片，见 §5） |
| 贴底滚动 | 新行出现/流式文本变化时滚到底（减弱动态效果时无动画） |
| 未读 | 打开群聊即 `markSessionRead` 清零（与 Android 进入群聊同语义）；`UnreadHint` 仅在非当前查看群时自增 |

### 1.3 成员 Sheet（`GroupMembersSheet`，同文件）

| Section | 内容 |
|---|---|
| 群资料 | 群名 / 模式（在线群聊/面对面/导演）/ 成员数 / 禁言数 / 群规则（`chat_sessions.rules`） |
| 自动互动 | `Toggle`「自动互动」（读 `group_auto_{id}`，写走引擎 `setAutoGroupChatEnabled`）+ 当前状态文案 + 总自动内容开关（`autoAiEnabled`）+ 轮数上限（`groupAutoMaxRounds`）+ 关键提示：总开关关闭时群开关打开也不会自动轮次（Android 同语义） |
| 成员 | 每位成员：头像（operators.avatarUri→Bundle 头像）/ 名字 / 身份 / 权限标签「可发言（绿）/已禁言（红）」/ 名册未同步标记 |

**成员权限维度 = 仅「禁言 / 可发言」**：Android 群聊唯一成员权限就是 `chat_sessions.mutedMembers`
（`GroupEditScreen.kt:111-118,227-241` 的 Switch 即「未禁言」），没有独立权限表；iOS 如实映射并在
Sheet footer 标注。

## 2. 交互矩阵（Android oracle ↔ iOS）

| # | 交互 | Android 原版（file:line） | iOS 实现 | 结论 |
|---|---|---|---|---|
| 1 | 群聊入口（会话列表点群会话） | `AppNavigation.kt:170-175`：`operatorId.startsWith("group_")` → `GroupChatRoute` | 聊天主页会话行 → `Route.chat(sessionId:)`（`ChatView` 用私聊 UI 渲染群会话的前提不存在——群会话 row 仍可点开但内容是群消息；**推荐入口为联系人页群聊分区/深链**，见 §5 限制 L1） | ⚠️ 部分（入口矩阵见 L1） |
| 2 | 群聊入口（联系人页） | `ContactsScreen`「群聊」分区 + `onGroupClick`（`AppNavigation.kt:198-205`） | 联系人页「群聊」分区：列表 + 点击进入 + 新建群聊 Sheet | ✅ 实现 |
| 3 | 群聊页头部 | `GroupDetailScreen.kt:253-345`：群名/「N条消息」副标题/模式菜单/更多菜单 | 群名 + 成员数/模式副标题 + 成员 Sheet；模式切换菜单未接（L2） | ✅ 头部；模式菜单 ⚠️（L2） |
| 4 | 多发言人气泡 | `MessageList` + `MessageParser`（群 AI 行拆发言人、senderName、头像、旁白样式；`GroupDetailScreen.kt:186-200`） | `GroupChatViewModel.rebuildRows`：`ai_json` → `GroupReplyParser` 段数组 → 每段一个 `GroupBubbleRow`（senderName/心情/旁白/撤回跳过） | ✅ 实现 |
| 5 | 群发消息 | `GroupDetailScreen.kt:380-400` → `GroupChatViewModel.sendGroupMessage` | `GroupChatBridge.send` → 真实 `GroupChatEngine.sendGroupMessage`（用户消息同步落库 + `ai_json` 回复落库） | ✅ 实现（模拟器实跑 + DB 复核） |
| 6 | 发送中取消 | 输入栏停止按钮 → `cancelGroupRequests` | `GroupChatViewModel.cancel` → 桥 `cancel` → `engine.cancelGroupRequests`（generation+1 + job cancel） | ✅ 实现 |
| 7 | 成员/禁言/权限展示 | `GroupEditScreen`（编辑群）成员列表 + Switch；查看态无独立成员页 | 成员 Sheet 只读展示（成员/禁言/权限/名册未同步）+ 自动互动开关 | ✅ 实现（只读；编辑见 L2） |
| 8 | 自动互动开关 | `GroupEditScreen.kt:90,153` → `setAutoGroupChatEnabled` | Sheet `Toggle` → 桥 → 引擎 `setAutoGroupChatEnabled`；状态文案来自 `autoGroupChatStatus` | ✅ 实现（开关+计划状态；自动轮次触发通道见 L3） |
| 9 | 分段撤回 | 群聊独有 `recallMessageSegment`（`GroupDetailScreen.kt:430` `onRecall`） | AI 气泡长按「撤回这一段」→ 桥 `recallSegment` → 引擎（改 content/全段删整条 + 情绪快照重算 + 上下文重建意图） | ✅ 实现 |
| 10 | 失败消息重试 | `retryGroupFailedMessage`（`GroupDetailScreen.kt:431`） | `send_failed` 气泡长按「重试」→ 桥 `retryMessage` → `engine.retryFailedMessage` | ✅ 实现 |
| 11 | 群历史分页 | `loadOlderGroupMessages`（50 条/页） | `GroupChatBridge.page`（游标 `(timestamp,id)`，50 条/页；上滑加载） | ✅ 实现 |
| 12 | 新建群聊 | `NewGroupScreen` → `saveGroup`（`SessionViewModel.kt:79-121`） | `NewGroupView`（群名 + 成员多选）→ `createGroup` 写 `chat_sessions`（id=operatorId=`group_<hex>`，members 逗号分隔） | ✅ 最小实现（规则/头像/模式编辑见 L2） |
| 13 | 深链 | —（Android 无群深链） | `rhodes://group/{id}` → `Route.groupChat(sessionId:)`；`rhodes://group` → 聊天 Tab | ✅ 实现 |
| 14 | 群礼物 / 图片 / 语音 | `GroupDetailScreen.kt:405-445`（GiftDialog/相册/拍照/录音） | 未接（群桥未暴露；引擎有 `sendGroupImageMessage`/`sendHiddenGiftMessage`，见 L4） | ❌ 本卡范围外 |
| 15 | 编辑群聊 / 重启群聊 / 换背景 / 聊天记录导出 / TTS | `GroupDetailScreen.kt:284-345, 452-499` | 未接（引擎有 `restartGroupSession`/`deleteGroup`，群资料编辑归 A2 后续） | ❌ 本卡范围外（L2/L4） |
| 16 | 重说 / 继续说 / 存档 | Android 群聊无这些入口（GroupChatViewModel 无对应方法） | 不实现（与引擎差异白名单一致） | ✅ 差异白名单 |

## 3. 桥接契约（`shared/src/iosMain/.../iosbridge/GroupChatBridge.kt`，T43 新增）

纪律沿用 ios-bridge-spec：只导出简单类型（String/Long/Int/Boolean/List/DTO）；回调一律 Main 队列；
`error == null` 表示成功；不导出 Flow/StateFlow/suspend。

| 方法 | 参数 | 回调 | 语义 |
|---|---|---|---|
| `listGroups(onResult)` | — | `(List<GroupSummaryDto>, String?)` | 群列表（过滤 `group_%`；首次调用幂等播种预设群） |
| `groupDetail(sessionId, onResult)` | String | `(GroupDetailDto?, String?)` | 群详情 + 成员名册（禁言/名册未同步/自动状态） |
| `page(sessionId, beforeId, limit, onResult)` | Long/Int | `(GroupMessagePageDto, String?)` | 群消息分页；AI 行拆 segments；`nextBeforeId` 游标 |
| `send(sessionId, text, onDelta, onDone)` | String | `(String) -> Unit` / `(GroupSendResultDto) -> Unit` | 真实引擎发送；onDelta 落库后打字机；onDone 一次 |
| `cancel()` | — | — | 取消在飞轮次（generation 失效 + job cancel） |
| `setActiveGroup(sessionId)` | String | — | 当前查看群（未读自增排除当前群） |
| `setAutoGroupChatEnabled(sessionId, enabled, onDone)` | Boolean | `(String?, String?)` | 自动互动开关；回调（新状态文案, 错误） |
| `autoGroupChatStatus(sessionId)` | String | 同步 String | 状态文案（本群已关闭/等待下一轮…/正在生成/达上限） |
| `recallSegment(sessionId, messageId, segmentIndex, onDone)` | Long/Int | `(String?)` | 分段撤回；全段撤回→引擎删整条 |
| `retryMessage(sessionId, messageId, onDone)` | Long | `(String?)` | 重试未送达群消息 |
| `createGroup(name, memberIds, onDone)` | String, List\<String\> | `(String?, String?)` | 新建群聊（`chat_sessions` 一行；≥2 位真实成员） |
| `isDemoMode()` | — | 同步 Boolean | 是否演示通道（未配置供应商） |

DTO 面（全部普通 class，字段稳定）：`GroupSummaryDto`（sessionId/name/memberCount/mutedCount/lastMessage/
lastTime/unreadCount/isPinned/mode/autoEnabled/autoStatus）、`GroupDetailDto`（+avatarId/rules/members/
autoMasterEnabled/autoMaxRounds）、`GroupMemberDto`（id/name/title/avatarId/isMuted/isResolved/permissionText）、
`GroupMessageDto`（id/senderName/isMe/type/text/imageId/timePayload/timestamp/mode/isRecalled/segments）、
`GroupMessageSegmentDto`（index/speakerName/speakerId/avatarId/text/emotion/isNarration/isRecalled）、
`GroupMessagePageDto`、`GroupSendResultDto`（ok/userMessageId/aiMessageId/text/speakersCsv/narrationCount/error/demo）。

**AI 通道**：已配置真实供应商 → `RealProviderStreamingClient`（Ktor Darwin SSE，surface="group"）；
未配置 → 本地确定性演示通道（从提示词「发言标识：id」解析真实成员，输出符合 tagged 协议的 2 位成员回复 +
合法【群聊回合状态】块），引擎解析/归一化/落库全链路与真实模型完全一致。

## 4. 与私聊的差异（实现视角）

| 维度 | 私聊 `ChatView/ChatViewModel` | 群聊 `GroupChatView/GroupChatViewModel` |
|---|---|---|
| 数据桥 | `MessageHistoryBridge`（一行=一条消息） | `GroupChatBridge.page`（一行 AI 消息 → 多发言人 segments） |
| 发送桥 | `ChatEngineBridge.send` | `GroupChatBridge.send`（真实 `GroupChatEngine`） |
| 气泡 | 单发言人（incoming/outgoing/system） | 多发言人（senderName+心情）/旁白行/系统行 |
| 消息操作 | 撤回整条/分段/重试/重说/继续说/删除本地 | 只读+复制+分段撤回+重试（重说/继续说/存档为群聊引擎差异白名单） |
| 头部 | 角色名 + 在线状态 | 群名 + 成员数/禁言数/模式 + 成员按钮 |
| 未读 | T44 链路（当前会话排除） | `setActiveGroup` 排除当前群后 `incrementUnread` |
| 自动互动 | 无 | 群开关 + 计划状态（通道适配归 A7 胶水批） |

## 5. 限制项与差异白名单

| 编号 | 限制 | 原因 / 归属 |
|---|---|---|
| L1 | 聊天主页点「群会话」仍进入私聊 `ChatView`（显示为普通气泡，不拆发言人）；私聊 `ChatEngineBridge.send` 也不拒绝 `group_` 前缀会话（T44 自检曾在 `group_elite` 写入私聊格式消息，见 handoff §3.2/§6.6） | 群会话与私聊共用 `Route.chat` 且桥层无 surface 守卫；路由分流/守卫需改 T44 写域（`ChatListView/ChatViewModel/ChatEngineBridge.kt`），本卡不越界。建议：Swift 侧按 `operatorId.hasPrefix("group_")` 路由 `openGroupChat`，或 `ChatEngineBridge.send` 对群会话 require 拒绝 |
| L2 | 群资料编辑（改名/成员增删/禁言编辑/规则）/ 模式切换 / 重启群聊 / 删除群 / 换背景 / 聊天记录导出 / TTS / 礼物 / 语音 | Android 对应页面为 `GroupEditScreen`/`GroupDetailScreen` 菜单；本卡交付「查看 + 日常聊天 + 新建」，编辑类归 A2 群资料卡后续波次。桥已把坑位留好（`groupDetail` 返回 rules/mode/mutedMembers；`restartGroupSession`/`deleteGroup` 引擎已具备） |
| L3 | 自动互动开关只落库计划状态，**不会自动产生轮次** | 引擎 `runScheduledAutoTurn` 的到期触发依赖通用 `DelayedTaskLayer` 的前台 Timer/BGTask 通道适配（T22/A7 胶水批，`impl-a7-glue` 负责）；本卡只做开关与状态展示，与 T22 §A-12 契约一致 |
| L4 | 群图片/礼物消息 | 引擎有 `sendGroupImageMessage`/`sendHiddenGiftMessage`，但需 PhotosPicker→媒体入库→识图（私聊 T42 已做）与礼物选择 UI；本卡聚焦文本群聊，桥未暴露 |
| L5 | 群「未读」语义 | `UnreadHint` 在非当前查看群时 `incrementUnread`（`setActiveGroup` 排除当前群），与 T44 私聊未读链路独立；返回列表刷新由会话列表自身 reload 负责 |
| L6 | 自动互动总开关（`autoAiEnabled`）为全局设置，成员 Sheet 只读展示 | 与 Android 一致：群开关 × 总开关，本卡不改全局设置 |
| L7 | 长按菜单的自动化点击 | 本环境无 UI 自动化工具（dsh-ios 已禁用）；菜单动作路径由桥/引擎自检覆盖 + 编译保证，真机需人工长按确认 |

## 6. 验收（可自动判定）

| # | 验收 | 判定方式 | 结果 |
|---|---|---|---|
| 1 | 群列表来自真实 DB（含预设群） | `--group-selftest` 日志 `group list count/ids/names` + 重启 App 后仍在 | PASS（见 handoff §3） |
| 2 | 群消息真实落库（用户 text + AI ai_json） | 发送后 `page` total +1~2；`sqlite3 chat_messages` 查 `sessionId=group_elite`；`reply_turns` surface='group' | PASS |
| 3 | 多发言人拆分 | `page` newest AI 行 `segments` ≥1，每段 speakerName/emotion/旁白标记与 `GroupMsgResult` 一致 | PASS |
| 4 | 成员/禁言/权限读取 | `groupDetail` 成员数 = `members.split(',')` 数；禁言数 = `mutedMembers` 命中数；`permissionText` 与 isMuted 一致 | PASS |
| 5 | 自动互动开关切换 | `setAutoGroupChatEnabled(on/off)` 返回状态文案；`settings group_auto_group_elite` 落盘 | PASS |
| 6 | 分段撤回 | AI 行 `segments` 数量减少（或全段撤回后整行消失）；DB content 变化 | PASS |
| 7 | 深链 | `AppRouter.handle(rhodes://group/{id})` handled=true + `Route.groupChat` 入栈 + 群聊页加载日志 | PASS |
| 8 | 构建/回归 | `:shared:linkDebugFrameworkIosSimulatorArm64` + `xcodebuild build` 0 error；`:shared:testDebugUnitTest` 不回归 | PASS（见 handoff §3.4） |
