# 规格卡：聊天气泡按段拆分（T59）

> 模块：Phase 5 聊天体验修复（用户反馈）｜任务卡：task-59 ｜实现代理：impl-bubble-split（2026-11-23）
> Android oracle：`app/.../ui/chat/util/MessageParser.kt:265-311`（私聊 ai_json 展开为多条 `ChatUiMessage`）、`app/.../ui/chat/component/MessageList.kt:338`（按 `originalMessageId_segmentIndex` 渲染行）、`:397-425`（旁白卡片样式）
> 上游：`shared/src/iosMain/.../iosbridge/MessageHistoryBridge.kt`（T34 历史桥）、`ChatEngineBridge.kt`（T19/T42 发送桥）

## 1. 目标与边界

**用户问题**：一条 `ai_json` 回复的所有段落（旁白 + 台词）此前被合并进同一个 iOS 气泡，用户要求「旁白和台词间错分布，每一段都单独一个气泡」。

| 项 | 内容 |
|---|---|
| 数据层 | `messages: [ChatMessage]` 保持不变：**一条 DB 消息 = 一个 ChatMessage**（撤回/重说/继续说/删除仍按整条消息语义） |
| 展示层 | 新增 `displayItems: [ChatDisplayItem]`：`ai_json` 按 `segments` 顺序展开成多条；时间线只渲染 displayItems |
| 台词 | 角色侧（接收方向）头像 + 气泡（T52 头像解析复用 `RoleAvatar`），每条台词段一个气泡 |
| 旁白 | 独立旁白气泡：居中、浅色卡片、斜体、无头像（Android 旁白分支同款），与台词按段顺序交错 |
| 纯文本/图片/系统/撤回 | 仍 1 个 item，渲染与交互保持原样（图片点击全屏、系统居中） |
| 不在范围 | 群聊渲染（GroupChat 本就按 speaker 分段，未改）；`GroupChat`/`Settings`/`Moments`/`Diary` 一律不动；分段撤回 UI 入口（引擎/桥已有，本卡只做展示拆分） |

## 2. 桥契约扩展（shared/iosMain）

### 2.1 `MessageSegmentDto`（新，`MessageHistoryBridge.kt:245`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `index` | Int | 原始 `segments` 数组下标（撤回/空段过滤后**不回填**，保证 `recalled` 语义可追溯；也是稳定 id 的段索引） |
| `type` | String | `dialogue`（台词）/ `narration`（旁白），已归一化小写 |
| `text` | String | 已剥内部状态卡标签的可见文本（`VisibleReplyContract.sanitizeVisibleSegmentContent`） |

### 2.2 `MessageDto.segments`（新增末位字段，`MessageHistoryBridge.kt:238`）

- `ai_json`：按原始顺序的可见段列表（撤回段/空段过滤；整条全撤回 → 空数组 + `isRecalled=true`）；
- 其它 `type`：空数组（Swift 回落到整条消息单气泡）。

解析口径抽为包内 `visibleReplySegments(raw, codec, parsed?)`（`MessageHistoryBridge.kt:258`），**历史桥与流式桥共用**，避免两处展示不一致。

### 2.3 流式段回调（`ChatEngineBridge.kt:144 / 283 / 1018-1040`）

`send(sessionId:text:onDelta:onReplySegments:onDone:)` 与 `sendImage(...)` 增加 `onReplySegments`：

1. 引擎整包落库后，桥读取 `chat_messages.content`，解析可见段；
2. 打字机开始前回调一次 `onReplySegments([MessageSegmentDto])`；
3. 打字机文本改为**全部可见段按顺序拼接**（此前只打台词，旁白不进入流），每 2 字/20ms 回调 `onDelta`；
4. `onDone.text` 语义不变（仍是台词可见文本，供旧路径/复制兜底）。

Swift 侧用「累计字符数 + 段长度」在**不改变桥打字机节奏**的前提下切分每段前缀：前面的段先完整出现，最后一段随 delta 增长。

### 2.4 自检夹具（仅 `--bubble-split-selftest`）

`ChatEngineBridge.debugSeedAiJsonMessage(sessionId:content:mode:timestamp:onDone:)`（`:411`）：真实写一条 `ai_json` 行（可指定 mode/timestamp），用于端到端断言「DB → 历史桥 → displayItems」。

## 3. 显示项派生契约（`ChatModels.swift:177-...`）

`ChatDisplayItem` 字段：`id / messageId / segmentIndex / type(dialogue|narration|text|image|system) / text / isStreaming / isRecalled / timestamp / timeText / isMe / side / imageId / showsTimeSeparator`。

| 规则 | 行为 |
|---|---|
| 展开 | `visibleSegments(for:)`：非撤回且 `segments` 非空 → 逐段生成；否则整条 1 个 item |
| 线上模式 | `mode == "online"` 时旁白段不展示（Android `parsePrivateAiJson` 同口径；引擎 online 本就只落 dialogue） |
| 稳定 id | `messageId * 1000 + segmentIndex`（对齐 Android `msg.id * 1000 + segIdx`；非分段消息 segmentIndex=0） |
| 类型映射 | 段 → narration/dialogue；图片 → image；`side == .system` → system；我方纯文本 → text；其它接收文本 → dialogue |
| 时间分隔 | 按所属消息时间只在该消息**首段**显示；规则沿用 ≥5 分钟（`timeSeparatorInterval`），首条消息显示 |
| 流式 | 已到字符数 `typedCharacters` 按段长度分配前缀：已满段完整显示，当前段为前缀，仅**最后一个**可见段 `isStreaming=true`（打字机光标） |
| 取消生成 | `isStreaming=false` 但 `typedCharacters` 保留部分进度 → 已到达的段保留展示，未到达段不出现 |
| 撤回 | 整条撤回不展开；占位文字「（此消息已撤回）」单气泡（`isRecalled=true` 斜体） |

派生器 `ChatDisplayItemBuilder.build(messages:)` 为纯函数（无状态、无 IO），自检/单测可直接断言。

## 4. 渲染与交互（`ChatView.swift`）

| 项 | 行为 |
|---|---|
| 时间线 | `ForEach(model.displayItems)`；`item.showsTimeSeparator` 时先插 `TimeSeparator` |
| 台词 | `HStack { RoleAvatar(avatarId: model.roleAvatarId, name: model.title, size: 34); MessageBubble(.incoming) }` |
| 旁白 | `NarrationBubble`（`MessageBubble.swift:255`）：居中浅色卡片 + 斜体 + 流式光标，无头像 |
| 系统/图片 | `MessageBubble` 原路径（图片 URL/点击全屏不变） |
| 长按菜单 | 旁白段：仅「复制」（复制旁白文本）；其它段：完整菜单（复制当前段文本 + 引用/撤回/重试/重说/继续说/删除，操作统一按所属 `messageId` 落到整条消息） |
| 滚动 | 末尾新增/流式跟随用 `displayItems.last.id / last.text`；前插锚点 = 首段 item id（`scrollTargetID(forMessageID:)`） |
| 分页 | `onAppear` 传 `item.messageId`，与 `loadMoreIfNeeded(currentID:)` 契约不变 |
| 空态/头部/未读/键盘 | 不变（`ChatView` 导航栏、模式徽标、输入栏、safeAreaInset 均未改） |

## 5. 验收

| # | 验收项 | 判定 | 证据 |
|---|---|---|---|
| 1 | shared framework 编译/导出 | `:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL；`grep MessageSegmentDto Shared.h` 存在 | `evidence/t59-framework-link.log` |
| 2 | iOS 编译 | `xcodebuild build`（DerivedData-t59）BUILD SUCCEEDED | `evidence/t59-build.log` |
| 3 | 端到端 4 段展开 | `--bubble-split-selftest`：DB 预置「旁白+台词+旁白+台词」→ 4 items / 顺序类型文本 / id 稳定 / 时间分隔仅首段 | `evidence/t59-bubble-split-selftest.log`（19 项 PASS） |
| 4 | 流式逐段 | 真实发送 → 桥 `onReplySegments` 回调 + displayItems 展开口径一致 | 同上 `stream.*` 断言 |
| 5 | 视觉 | 截图：旁白卡片与台词气泡交错，台词带角色头像 | `evidence/t59-chat-bubble-split.png` |
| 6 | DB 复核 | 夹具行 `type=ai_json / mode=offline / 4 segments` | `evidence/t59-db-check.txt` |
| 7 | 回归：消息操作 | `--chat-ops-selftest` 10/11 PASS；唯一失败 `regenerate changed=false` 为**既有**问题（详见 handoff §5） | `evidence/t59-chat-ops-regression.log` |
| 8 | 回归：未读链路 | `--unread-selftest`（不带 `--selftest`）allPASS=true | `evidence/t59-unread-regression.log` |
| 9 | 回归：历史分页 | `--history-load-more`：50 → 59（再开一轮 61/61），前插/滚动锚点正常 | `evidence/t59-history-pagination.log` |

## 6. 限制与注意

1. **群聊未改**：GroupChat 走自己的 `GroupBubbleRow`（本就按 speaker 分段），本卡不触碰；如后续要统一旁白样式可复用 `NarrationBubble`。
2. **分段撤回入口未暴露**：引擎/桥 `recallMessageSegment` 已存在（T42），本卡长按菜单仍按整条消息操作（与需求「按所属 messageId」一致）。
3. **流式段切换粒度**：桥仍是「整包落库后模拟打字机」（引擎不产生 token 流），因此「逐段出现」的时间点取决于段落文本长度，而非模型真实 token 边界。
4. **旁白展示与模式相关**：线上模式不展示旁白（Android 同口径）；若历史脏数据在 online 消息里带 narration 段，按规则隐藏。
5. **占位文字**：`ChatMessage.text` 仍保留整条可见文本（台词优先），用于复制兜底/诊断；渲染以 displayItems 为准。
