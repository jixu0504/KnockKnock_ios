# T59 实现 handoff：聊天气泡按段拆分（旁白/台词交错独立气泡）

> 代理：impl-bubble-split（task-59）｜规格：`ios-port/specs/bubble-split-spec.md`（本卡产出）
> 状态：**实现完成；Shared.framework 重链 + xcodebuild 通过；iPhone 17 模拟器 `--bubble-split-selftest` 19 项全 PASS（含真实 DB 端到端 + 流式链路）；截图确认旁白/台词交错独立气泡**
> git：**不提交**（lead 统一提交）；分支 `ios-port`，基线 HEAD `74ce7d1` + 并行代理工作区改动

## 1. 写域与交付物

| 路径 | 类型 | 说明（关键行号） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/MessageHistoryBridge.kt` | 修改（+70/-15） | `MessageSegmentDto` :245；`MessageDto.segments` :238；`parseAiJson` 复用统一解析 :165-195；包内 `visibleReplySegments(raw, codec, parsed?)` :258（历史/流式共用口径） |
| `RhodesLink/shared/src/iosMain/.../iosbridge/ChatEngineBridge.kt` | 修改（+68/-6） | `send` / `sendImage` 新增 `onReplySegments` 回调 :144 / :283；`completeSend` 先回调段列表再按「全部可见段拼接文本」打字机 :1014-1049；自检夹具 `debugSeedAiJsonMessage` :411 |
| `RhodesLink/iosApp/.../Bridge/MessageHistoryClient.swift` | 修改（+27） | `MessageSegmentItem`（Swift 值类型，不向上导出 Kotlin 类型）:21；`MessageItem.segments` :62 |
| `RhodesLink/iosApp/.../Bridge/AppGraphClient.swift` | 修改（+9） | `sendChat(... onReplySegments: = { _ in } ...)` :350-370（默认参数，保证 Settings/Backup/Provider 等既有调用点零改动） |
| `RhodesLink/iosApp/.../Bridge/ChatOpsClient.swift` | 修改（+30） | `sendImageChat(... onReplySegments ...)` :136-160；`debugSeedAiJsonMessage` 包装 :173 |
| `RhodesLink/iosApp/.../Features/Chat/ChatModels.swift` | 修改（+195） | `ChatMessage` 新增 `mode/segments/typedCharacters` :34-80；`ChatDisplayType/ChatDisplayItem` :177-207；纯函数派生器 `ChatDisplayItemBuilder`（`visibleSegments` :222、`build` :231） |
| `RhodesLink/iosApp/.../Features/Chat/ChatViewModel.swift` | 修改（+85） | `displayItems` :87、`scrollTargetID` :90、`baseMessage(for:)` :95、`roleAvatarId` :100、`copyText(item)`/`copyText(text:messageId:)` :389-403、流式段应用 `applyStreamingSegments` :634、`appendDelta` 累计 typedCharacters :620、`finish` 收尾 :648 |
| `RhodesLink/iosApp/.../Features/Chat/ChatView.swift` | 修改（+128/-40） | 时间线渲染 displayItems :370-380；派生行 `displayRow` :426（旁白/系统/图片/台词）、`imageRow` :461、长按菜单 `displayMenu` :482；滚动观察 :393/:405；前插锚点 :412；自检钩子 :145 |
| `RhodesLink/iosApp/.../DesignSystem/MessageBubble.swift` | 修改（+57） | `NarrationBubble`（居中浅色卡片 + 斜体 + 流式光标，无头像）:255 |
| `RhodesLink/iosApp/.../Features/Chat/BubbleSplitAutomation.swift` | **新增** | `--bubble-split-selftest`：纯派生层 10 项 + 真实发送（流式）2 项 + 真实 DB 端到端 7 项断言 |
| `ios-port/specs/bubble-split-spec.md` | 新增 | 规格卡（契约/规则/验收/限制） |
| `ios-port/specs/message-history-bridge-spec.md` | 修改 | DTO 表补 `segments` / `MessageSegmentDto`（遵守「改 DTO 先改 spec」纪律） |
| `ios-port/handoffs/evidence/t59-*` | 新增 | 见 §4 |

**未改**：GroupChat（`Features/GroupChat`、`GroupChatBridge`）、Settings、Moments、Diary、AppRouter/RouteView、群聊渲染；无 `commonMain`、无 `.sq` 迁移、无 DB schema 改动。

### 1.1 关于写域越界的说明（请 lead 复核）

任务卡写域列的是 `iosApp/Features/Chat/**`、`DesignSystem`、`Bridge/**`，但任务正文明确允许「shared MessageHistoryBridge ... 可在 Swift 侧解析或用桥扩展」。由于历史 DTO 不暴露 `content` 原文、Swift 侧无法还原段顺序，**必须扩展 iosMain 桥**，因此实际改动落入 `shared/src/iosMain/.../iosbridge/{MessageHistoryBridge.kt,ChatEngineBridge.kt}`（与 T60/T61 修改的 A7Glue/MomentsBridge/DiaryBridge/IosAppGraph 不同文件，无同行冲突）。framework 重链时已包含 T60/T61 当时工作区改动。

## 2. 实现要点

1. **数据层不变、展示层展开**：`messages` 仍是「一条 DB 消息 = 一个 ChatMessage」；`displayItems` 由纯函数 `ChatDisplayItemBuilder.build(messages:)` 派生，`@Published messages` 变化即重建（无额外状态，LazyVStack 仍用稳定 Int64 id）。
2. **id 稳定**：`messageId * 1000 + segmentIndex`（非分段消息 segmentIndex=0），对齐 Android `msg.id * 1000 + segIdx`；段索引取原始 segments 下标（撤回/空段过滤后不回填）。
3. **时间分隔**：派生时按消息时间算一次（首条或与上一条间隔 ≥5 分钟），只挂在该消息首段 `showsTimeSeparator`；ChatView 直接消费，不再对 `messages` 下标判断。
4. **线上/线下旁白**：`mode == "online"` 隐藏旁白段（Android `MessageParser.parsePrivateAiJson` 同口径）；offline/director 才展示。
5. **流式逐段出现**：`ChatEngineBridge.completeSend` 整包落库后解析可见段 → 先 `onReplySegments` 回调、再打字机（文本 = 全部可见段按顺序拼接，此前只打台词）。Swift 用「累计已到字符数 ÷ 段长度」切分：前面的段先完整出现、最后一段随 delta 增长，仅末段显示打字机光标；取消生成时保留已到进度。
6. **交互兼容**：长按复制复制**当前段**文本；引用/撤回/重试/重说/继续说/删除统一按 `item.messageId` 回落整条消息（与 T42 语义一致）；图片点击全屏走原 `baseMessage` 路径；旁白段与 Android 一致只给「复制」。
7. **头像**：台词段用 `RoleAvatar(avatarId: session.avatarId, size: 34)`（复用 T52 内置/上传头像解析），旁白无头像；系统/图片行保持原样。
8. **流式段回退**：段回调到达前/非 ai_json 通道，`appendDelta` 仍累积 `text`，占位气泡显示「…」，不会闪空。

## 3. 自检（`--bubble-split-selftest`）断言清单

前置：`--selftest --bubble-split-selftest --open-url rhodes://chat/session_amiya`（iPhone 17 / iOS 27.2）。

| 组 | 断言 | 说明 |
|---|---|---|
| 纯派生 | `synthetic.count/types/timeSeparator.firstOnly/ids/idsStable` | 人工构造「旁白+台词+旁白+台词」→ 4 items、类型顺序、id=base+[0..3]、多次派生一致；构造 20 分钟间隔 → 仅首段 `showsTimeSeparator=true` |
| 流式纯派生 | `stream.count/firstFull/lastPrefix/lastOnlyStreaming` | typedCharacters=7 → 首段完整、次段前缀「台词」、仅末段 isStreaming |
| 线上模式 | `online.narrationHidden` | online 消息 4 段 → 仅 2 个 dialogue item |
| 真实发送 | `stream.bridgeSegmentsCallback` / `stream.displayExpanded` | 真实 `model.send` → 日志出现 `chat stream segments messageId=...` → 最新 ai_json 回复 displayItems 数 = 可见段数 |
| 真实 DB 端到端 | `db.count/types/texts/ids/idsStable/timeSeparator.firstOnly/interleaved` | `debugSeedAiJsonMessage` 写入真实 `chat_messages`（mode=offline）→ `refresh()` → MessageHistoryBridge.segments → displayItems 4 项且文本逐字匹配 |

## 4. 验证证据（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t59-framework-link.log` | `:shared:linkDebugFrameworkIosSimulatorArm64` **BUILD SUCCESSFUL in 1m 18s**（含 MessageSegmentDto/onReplySegments 导出；`Shared.h:15758/15811/17382` 可复核） |
| `t59-build.log` | `xcodebuild build`（DerivedData-t59）**BUILD SUCCEEDED** |
| `t59-bubble-split-selftest.log` | **19/19 PASS**；关键行：`db.count count=4`、`db.ids ids=[…000, …001, …002, …003]`、`db.timeSeparator.firstOnly separators=[false,false,false,false]`、`stream.displayExpanded segments=1 visible=1 items=1`、`=== bubble-split selftest done PASS=true failures=0 ===` |
| `t59-chat-bubble-split.png` | 聊天页截图：`（夜色落在舷窗上…）` 旁白卡片 → `博士，这边。` 台词（头像）→ `（她把杯子推过来…）` 旁白 → `小心烫。` 台词，四段清晰交错；上方历史消息仍正常 |
| `t59-db-check.txt` | `sqlite3` 复核：夹具 id 76/90/95 均 `type=ai_json, mode=offline, len=256`，content 含 4 segments |
| `t59-chat-ops-regression.log` | T42 消息操作自检复跑（全新安装 T54-sim）：10/11 PASS，唯一 `regenerate changed=false`（见 §5） |
| `t59-unread-regression.log` | T44 未读自检复跑：`=== unread selftest done allPASS=true ===` |
| `t59-history-pagination.log` | 历史分页复跑：`loadMore start loaded=50/59` → `prepended=9 loaded=59/59`；第二轮 61/61，滚到底/前插锚点正常 |

### 复跑命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink && ./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64   # 产出 framework
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedData-t59 CODE_SIGNING_ALLOWED=NO

DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
xcrun simctl install $DEV iosApp/DerivedData-t59/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --selftest --bubble-split-selftest --open-url rhodes://chat/session_amiya
xcrun simctl io $DEV screenshot /tmp/t59.png
C=$(xcrun simctl get_app_container $DEV com.rhodeslink.app data)
grep bubble-split "$C/Documents/ios-shell-selftest.log"
sqlite3 "$C/Library/Application Support/rhodes.db" "select id,type,mode,length(content) from chat_messages where content like '%夜色落在舷窗%';"
```

## 5. 回归与既有问题（重要）

| 项 | 结果 | 归因 |
|---|---|---|
| `--chat-ops-selftest` | **10/11 PASS**；`chat-ops regenerate messageId=4 changed=false newChars=24 PASS=false` | **既有问题，与 T59 无关**：T42 之后（T45 真实提示词接线起）演示通道回复变成确定性的 24 字（回显提示词规则片段「（演示模式）我听见了：【输出要求】第一行必须是」）；重说同一提示词 → 同字节输出 → `changed=false`。证据：T55 截图（T59 之前）已显示同一条 24 字回复；T59 未改 `regenerate`/提示词/演示通道任一代码路径。若要让该断言重新 PASS，需要调整演示通道回复（让其包含轮次随机项）或放宽 chat-ops 断言（建议 lead 另立小任务，不在 T59 内改他卡自检口径） |
| `--unread-selftest`（不带 `--selftest`） | allPASS=true | 未读链路未受影响 |
| `--unread-selftest` 带 `--selftest` | openChat/viewingSend/backToList 3 项 false | **既有测试时序问题**：shell selftest 先打开过同一会话，日志 marker 已存在 → `waitForLogMarker` 立即返回，打开清零尚未完成即断言（T44 自检自身的 marker 去重缺陷，非产品行为）。不带 shell selftest 时全 PASS |
| `--history-load-more` | PASS（50→59） | displayItems 后 `onAppear` 传 `messageId`，分页契约未变 |
| 群聊 | 未改、未跑 | 写域隔离 |

## 6. 限制与给 lead 的注意点

1. **shared 写域**：见 §1.1；framework 已重链。若 lead 后续再改 shared 需重新 link（本卡只在模拟器 arm64 debug framework 上验证；真机 `iosArm64` release framework 需 lead 统一重链）。
2. **分段撤回入口未暴露**：桥 `recallMessageSegment` 已存在（T42），本卡长按菜单仍按整条消息撤回；若产品要「撤回这一段」可复用 `ChatDisplayItem.segmentIndex`（id 已带段索引）。
3. **流式粒度**：引擎不产生 token 流，桥仍是「整包落库后模拟打字机」；「逐段出现」按段落文本长度推进，不是模型真实 token 边界。旁白段现在也进入流式文本（此前只打台词），因此流式期间总时长可能略增（旁白文本长度）。
4. **旁白样式**：iOS 采用「居中 90% 浅色卡片 + 斜体」，与 Android 旁白卡片视觉对齐；深色模式用 `Color.primary.opacity` 自适应。
5. **自检夹具会写真实 DB**：`--bubble-split-selftest` 会向 `session_amiya` 插一条 `ai_json` 夹具消息 + 一条流式自检消息（与 T42/T44 自检同一惯例），生产 DB 不受影响（仅启动参数触发）。
6. **既有 fail 不在本卡修复**：chat-ops `regenerate` 断言、unread selftest 的 `--selftest` 时序缺陷均为 T59 之前已存在，见 §5，建议 lead 决定是否另立卡。
