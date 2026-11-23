# T34 实现 handoff：消息历史桥 + 聊天时间线真实数据

> 代理：impl-history-bridge（task-34，Phase 3 收尾，P0）｜规格：`ios-port/specs/message-history-bridge-spec.md`（本卡产出）
> 状态：**实现完成 + 编译/测试/模拟器实跑全部通过**（2026-11-23 08:02，iPhone 17 / iOS 27.2）。
> git：**不提交**（lead 统一提交）。本卡只改 `shared/src/iosMain`（新增桥）与 `RhodesLink/iosApp`，未改 `commonMain` / `app`。

## 1. 背景与目标

T33 handoff §6「已知限制 #1」：shared 门面没有消息历史桥，聊天页时间线只有本次发送流 + 会话 `lastMessage`。
本卡补齐（P0）：

1. shared `iosbridge` 新增 **MessageHistoryBridge**（只读分页读真实 `chat_messages`）；
2. Swift `AppGraphClient` 增加 history 查询 + 分页（首屏 50，上滑加载更多）；
3. 聊天页接真实历史（打开会话拉历史、发送后乐观插入 + onDone 刷新）；
4. 深链 `rhodes://chat/{sessionId}` 打开对应会话历史（复用 T33 路由，验证其自动加载）。

## 2. 写域与文件清单

| 类型 | 路径 | 说明 |
|---|---|---|
| 新增 Kotlin | `shared/src/iosMain/.../iosbridge/MessageHistoryBridge.kt`（238 行） | `page()` / `find()` + `MessageDto` / `MessagePageDto`（DTO 与桥同文件，避免与并行卡写 `IosDtos.kt` 冲突） |
| 修改 Kotlin | `.../iosbridge/IosAppGraph.kt` | 仅 3 处最小插入：`historyBridge` 字段、`fun history()`、KDoc 一行 |
| 新增 Swift | `iosApp/RhodesLink/Bridge/MessageHistoryClient.swift` | `MessageItem` / `MessagePage` + `AppGraphClient.loadMessagePage()` / `findMessage()`；独立新文件避免与并行卡改 `AppGraphClient.swift` 冲突 |
| 修改 Swift | `.../Bridge/AppGraphClient.swift` | 仅 1 个访问器 `var historyBridge: MessageHistoryBridge { graph.history() }` |
| 修改 Swift | `.../Features/Chat/ChatViewModel.swift` | 首屏/分页/乐观插入/发送后刷新/自动化钩子状态 |
| 修改 Swift | `.../Features/Chat/ChatView.swift` | 分页触发器（滚到底后开启）、前插锚点、`--history-load-more` 钩子、历史条数状态 |
| 修改 Swift | `.../Features/Chat/ChatModels.swift` | `ChatMessage` 稳定 **Int64** id + narration/timeText/imageId/isRecalled；`init(item:)` |
| 修改 Swift | `.../DesignSystem/MessageBubble.swift` | 旁白/撤回/时间展示（仍不用玻璃气泡） |
| 修改 Swift | `.../App/SelfTest.swift` | 新增 §2.5 历史分页断言 + §8.5 发送后历史刷新断言 |
| 文档 | `ios-port/specs/message-history-bridge-spec.md` | API 表 / 分页语义 / 解析规则 / 性能注意 |
| 证据 | `ios-port/handoffs/evidence/t34-*`（12 个文件，见 §5） | 构建/测试日志、4 张截图、DB 直查、运行日志、分页 fixture SQL |

> 并行卡（T36 impl-roles-pages）同改 `IosAppGraph.kt` / `AppGraphClient.swift`：采用「只追加 + 只用 edit」协作，
> 最终两卡改动共存；`git status` 中 `Features/Roles/*`、`AppRouter.swift`、`ChatListView.swift`、`RhodesLinkApp.swift`、
> `NewChatView.swift` 等为 T36 改动，非本卡。

## 3. 关键设计

### 3.1 分页 API（shared → Swift）

```
MessageHistoryBridge.page(sessionId, beforeId, limit, onResult: (MessagePageDto, String?) -> Unit)
  beforeId = 0        → 首屏（最新 limit 条，items 按 id 倒序）
  beforeId = 上一页 nextBeforeId → 更早一页
  limit ≤ 0 → 50，上限 200；多取 1 条判 hasMore（免 COUNT）
MessagePageDto(items, hasMore, nextBeforeId, totalCount)
```

内部复用现有 `MessageRepository`（不改 commonMain）：
`beforeId=0` → `getRecentMessagesSync(sessionId, limit+1)`；否则 `getMessageById(beforeId).timestamp` +
`getMessagesBefore(sessionId, timestamp, id, limit+1)`（`(timestamp,id)` 严格递减）。锚点被删时退化为最新一页防死循环。
错误经第二参数回传；**不导出 Flow/suspend**，Swift 包装为 async。

### 3.2 content 解析（对齐 T19 ChatEngine 写入格式）

| type | 解析 |
|---|---|
| `text` | 原文 |
| `ai_json` | `DefaultReplyCodec.normalize` + `VisibleReplyContract.sanitizeVisibleSegmentContent` 逐段清洗 → `text`（台词）/ `narration`（旁白）；`PrivateReplyFormat.isPrivateSegmentRecalled` 过滤撤回段；整条全撤回 → `isRecalled=true`；脏 JSON 兜底前 300 字不抛 |
| `image` | `ImageMessageContract.parse` → `imageId=imageUri`、`text=caption`；`status=failed` → `[图片发送失败]` |
| `gift_hidden` / `gift_reply_failed` | 占位文案 |
| `system` | 原文（居中胶囊） |

`timePayload` 由 shared 侧按本机时区格式化（今天 `HH:mm`、更早 `M/d HH:mm`），Swift 不再重复格式化。

### 3.3 聊天页行为

- **打开会话**：`findSession` + 首屏 50 条（倒序返回 → `reversed()` 正序插入 LazyVStack）；首屏滚到底后**延迟 600ms**
  开启分页（规避 LazyVStack 初始 onAppear 误触发预加载，实跑已验证首屏严格 50 条）。
- **上滑分页**：列表首个真实消息 `onAppear` / 顶部 header 触发 `loadMore()`；前插后以「原最早一条 id」为
  `ScrollViewReader` 锚点 `scrollTo(anchor:.top)`，不跳变；`Set<id>` 去重。
- **发送**：乐观插入（负数临时 id + 流式占位）→ onDone 后拉最新一页，与「已加载更早段」合并
  （`messages = 更早已加载(id < 最新页最旧 id) + 新页`），保留历史不重置；失败保留乐观气泡 + 错误提示。
- **深链**：`rhodes://chat/{sessionId}` → `AppRouter.handle` → `ChatView(sessionId)` 自动加载该会话历史（T33 路由复用）。
- **性能**：LazyVStack + 稳定 Int64 id + 非玻璃气泡结论保持；`--stress-2000` 基线不回归。

## 4. 构建与测试

```bash
cd RhodesLink
source ../ios-port/toolchain/env.sh
pgrep -fl gradle; pgrep -fl xcodebuild          # 并发纪律
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64 :shared:iosSimulatorArm64Test
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO
```

| 项 | 结果 | 证据 |
|---|---|---|
| Shared.framework link（含本卡 MessageHistoryBridge + T36 RoleBridge） | **BUILD SUCCESSFUL**（36s；07:57 复跑 UP-TO-DATE） | `evidence/t34-framework-link.log`、`t34-ios-tests.log` |
| `:shared:iosSimulatorArm64Test`（全量，iPhone 17 模拟器） | **563 tests / 0 failures / 0 errors**（62 suites） | `evidence/t34-ios-tests.log` |
| `xcodebuild build`（generic iOS Simulator，工作区内 DerivedData） | **BUILD SUCCEEDED**（无 error） | `evidence/t34-build.log` |

## 5. 模拟器实跑验证（iPhone 17 / iOS 27.2；`xcrun simctl` 全程，**未使用 dsh-ios 工具**）

安装/启动：

```bash
xcrun simctl install booted iosApp/DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://chat/session_amiya"
xcrun simctl io booted screenshot ios-port/handoffs/evidence/t34-chat-history.png
```

### 5.1 数据 fixture（仅模拟器，用于让分页/解析可复核）

`evidence/t34-seed-history.sql`：向模拟器容器 `rhodes.db` 写入 120 条确定性历史消息（ids 1001..1120，
session_amiya，时间戳 = 生成时刻往前 125 分钟起每条 +1 分钟）。覆盖 `text`（用户/角色）、`ai_json`（含旁白段）、
`image`、`system`、以及一条全撤回 `ai_json`（id=1106）。**App 内只用 MessageHistoryBridge 只读读取**；
真机/正式环境由真实聊天自然积累，无需 fixture。另把 `session_amiya.lastTime` 置为最新，使 `--selftest`
的首个会话为 `session_amiya`（T36 在模拟器里新建了 `session_kaltsit` / `session_mon3tr` 两个会话）。

### 5.2 验收矩阵

| 验收项 | 结果 | 证据 |
|---|---|---|
| **历史消息来自 shared DB**（非 mock） | ✅ 首屏 `history page1 count=50 hasMore=true nextBeforeId=1071 total=120 oldestId=1071 newestId=1120`；`sqlite3` 直查同一容器 DB：`session_amiya` 124 条、最新 ids 1128/1127/… 与自检日志逐条对应 | `t34-chat-history.png`、`t34-db-query.txt`、`t34-simulator-logs.txt` A 段 |
| **分页加载**（首屏 50，上滑加载更多） | ✅ 运行 `--history-load-more`：50 → pageMore prepended=50 loaded=100/120 → pageMore prepended=20 loaded=120/120 hasMore=false；深链打开首屏严格 50 条、无预加载 | `t34-chat-pagination.png`、`t34-simulator-logs.txt` B 段 |
| **发送后刷新** | ✅ `--selftest`：发送前 total=122 → 发送 ok aiId=1128 → `refreshed after send page=50 olderKept=2 loaded=52/124 newestId=1128 newestIsMe=false`；`history after send total=124 newestText=（iOS 桥接演示）我听见了：自检…`；`chat_sessions.lastMessage` 已更新 | `t34-chat-after-send.png`、`t34-selftest.log` |
| **深链打开会话历史** | ✅ `deeplink rhodes://chat/session_amiya handled=true` → 聊天页自动加载该会话 50 条历史（见 A 段日志 + `t34-chat-history.png` 即深链启动后的聊天页） | `t34-simulator-logs.txt` A 段 |
| **LazyVStack 2,000 条基线不回归** | ✅ `--stress-2000`：`chat loaded messages=2000 total=2000 stress=true`，LazyVStack 正常渲染 | `t34-chat-stress-2000.png` |
| **content 解析**（ai_json 旁白/台词、image、撤回） | ✅ DB 原文见 `t34-db-query.txt` §4/§5/§6；解析逻辑由桥内复用 shared codec（见 `MessageHistoryBridge.parseContent/parseAiJson`），首屏截图中可见旁白/图片附言/撤回占位 | `t34-db-query.txt`、`t34-chat-history.png` |
| 模拟器启动无崩溃 | ✅ 4 次启动（深链/分页/stress/selftest）均正常，日志无 error/crash 行 | `t34-simulator-logs.txt`、`t34-selftest.log` |

截图清单（`ios-port/handoffs/evidence/`）：

| 文件 | 内容 |
|---|---|
| `t34-chat-history.png` | 深链 `rhodes://chat/session_amiya` 打开聊天页：真实 DB 历史 50 条、状态条「历史 · 50/120 条（真实 DB）」 |
| `t34-chat-pagination.png` | `--history-load-more` 后：加载全部 120 条（50→100→120），状态条「历史 · 120/120 条」 |
| `t34-chat-after-send.png` | `--selftest` 发送后：真实历史 + 新用户消息 + 真实落库的 AI 回复（刷新后 52/124） |
| `t34-chat-stress-2000.png` | `--stress-2000`：2,000 条 LazyVStack 基线不回归 |
| `t34-selftest.log` | App Documents 自检日志（含 history page1/page2、发送后刷新断言） |
| `t34-db-query.txt` | 模拟器容器 `rhodes.db` 直查（总量/最新/最早/ai_json/image/撤回原文/session 摘要） |
| `t34-simulator-logs.txt` | 四类实跑场景的 NSLog 摘录（A 首屏 / B 分页 / C stress / D 发送后刷新） |
| `t34-seed-history.sql` | 分页 fixture（120 条，可复现） |
| `t34-build.log` / `t34-framework-link.log` / `t34-ios-tests.log` | 构建 / framework link / iOS 全量测试日志 |

### 5.3 复现命令

```bash
# fixture（App 未运行时）
CONTAINER=$(xcrun simctl get_app_container booted com.rhodeslink.app data)
sqlite3 "$CONTAINER/Library/Application Support/rhodes.db" < ios-port/handoffs/evidence/t34-seed-history.sql

# A 首屏历史（深链）
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://chat/session_amiya"
# B 分页
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://chat/session_amiya" --history-load-more
# C 2,000 条基线
xcrun simctl launch booted com.rhodeslink.app --open-url "rhodes://chat/session_amiya" --stress-2000
# D 发送后刷新 + 自检日志落盘
xcrun simctl launch booted com.rhodeslink.app --selftest
CONTAINER=$(xcrun simctl get_app_container booted com.rhodeslink.app data)
cat "$CONTAINER/Documents/ios-shell-selftest.log"
```

## 6. 已知限制 / 后续

1. **id 游标是对外语义，内部走 `(timestamp,id)`**：`MessageRepository` 现有查询按时间戳游标；桥用
   `getMessageById(beforeId).timestamp` 换算。锚点消息被删除时退化为最新一页（客户端按 id 去重，不会死循环）。
2. **无增量观察通道**：不导出 Flow；新消息靠发送回调 + 刷新触发。别的端/后台写入后，重进会话或下拉前需重新 `loadFirstPage`。
   后续若要「新消息自动追加」，可在桥加 `pageAfter(afterId)` 轮询/回调（复用 `getMessagesAfterCursor`）。
3. **撤回只有 ai_json 段级语义**（`recalled` 段/整条），text/image 撤回无独立落库字段 → 未接（Phase 3 后续）。
4. **图片只解析 imageId/caption**，本地图片渲染（T31 MediaStore 路径）未接，时间线显示附言文本。
5. **群聊历史**：当前桥按 `sessionId` 查 `chat_messages`，群聊会话（`group_*`）同样可读，但群聊页面/轮次引擎未接（T20）。
6. **首屏 50 + 上滑分页**：无「跳到最早/按日期跳转」；`totalCount` 用于状态条，不做全量预载（性能结论保持）。
7. 模拟器 fixture 会改变演示数据（`chat_messages` 重置为 120 条 + 后续发送）；**仅模拟器容器**，不影响仓库/CI。
8. 本卡未改 `commonMain`：若后续要给 `MessageRepository` 加「纯 id 游标」查询（`WHERE id < ?`），需另开卡并改 `.sq`
   （本卡为满足并行写域纪律，用现有查询组合实现，行为等价于 id 倒序分页）。

## 7. 对后续卡的建议

| 优先级 | 项 | 落点 |
|---|---|---|
| P0 | 真实 AI 供应商 + 提示词管线（替换 DemoStreamingClient / DemoPromptAssembler） | `ChatEngineBridge.kt` |
| P1 | 新消息增量通道（`pageAfter` 轮询或 SQLDelight 监听 → 回调） | `MessageHistoryBridge` + `ChatViewModel` |
| P1 | 图片消息渲染（imageId → MediaStore 本地文件） | `MessageBubble` + `IosFileTransfer` |
| P1 | text/image 消息撤回落库字段 + UI | `chat_messages` schema（commonMain，另开卡） |
| P2 | 群聊历史页（`group_*` sessionId 已可读） | `Route.group` 正式页 |
| P2 | 历史搜索（`searchMessagesInSession` 已在仓库，可加桥方法） | `MessageHistoryBridge.search` |
