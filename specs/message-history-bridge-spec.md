# 消息历史桥规格（T34：MessageHistoryBridge + 聊天时间线真实数据）

> 代理：impl-history-bridge（task-34）｜上游限制：`ios-port/handoffs/impl-ios-shell.md` §6 限制 #1（shared 门面无消息历史桥）
> 实现：`RhodesLink/shared/src/iosMain/kotlin/com/rhodes/privatechat/shared/iosbridge/MessageHistoryBridge.kt`
> 参考：`ios-port/specs/ios-bridge-spec.md`（窄门面纪律）、`ios-port/specs/private-chat-engine-spec.md`（引擎落库格式）、`ios-port/handoffs/impl-chat-engine.md`

## 1. 目标与边界

聊天页时间线从「本次发送流 + 会话 lastMessage 摘要」升级为**真实 `chat_messages` 历史**：

- 打开会话 → 首屏最新 50 条；
- 上滑 → 以 id 游标分页加载更早消息（前插、去重、保持阅读位置）；
- 发送 → 乐观插入 + `onDone` 后刷新最新一页（保留已加载的更早消息）；
- 深链 `rhodes://chat/{sessionId}` → 同一 `ChatView(sessionId)`，自动加载该会话历史。

**只读**：桥不写库、不导出 Flow/StateFlow/suspend；写入仍由 `ChatEngineBridge` → domain `ChatEngine` 负责。
不改 `shared/src/commonMain`（复用现有 `MessageRepository` 查询，游标语义在桥内封装）。

## 2. 文件与入口

| 层 | 文件 | 内容 |
|---|---|---|
| Kotlin | `shared/src/iosMain/.../iosbridge/MessageHistoryBridge.kt` | 桥 + `MessageDto` / `MessagePageDto`（DTO 与桥同文件，避免并行写域冲突） |
| Kotlin | `.../iosbridge/IosAppGraph.kt` | `historyBridge` 字段 + `fun history(): MessageHistoryBridge`（最小插入） |
| Swift | `iosApp/RhodesLink/Bridge/MessageHistoryClient.swift` | `MessageItem` / `MessagePage` + `AppGraphClient.loadMessagePage` / `findMessage`（独立新文件，避免与并行卡改同一文件） |
| Swift | `iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 仅 1 行访问器 `var historyBridge: MessageHistoryBridge { graph.history() }` |
| Swift | `iosApp/RhodesLink/Features/Chat/ChatViewModel.swift` | 首屏/分页/乐观插入/发送后刷新 |
| Swift | `.../Features/Chat/ChatView.swift` | 分页触发器、`--history-load-more` 自动化钩子、历史条数状态 |
| Swift | `.../Features/Chat/ChatModels.swift` | `ChatMessage` 改 Int64 稳定 id + narration/timeText/imageId/isRecalled |
| Swift | `.../DesignSystem/MessageBubble.swift` | 旁白/撤回/时间展示（仍不用玻璃气泡） |

## 3. API 表

### 3.1 `MessageHistoryBridge`（Swift `MessageHistoryBridge`）

| Kotlin | Swift | 线程 | 说明 |
|---|---|---|---|
| `page(sessionId: String, beforeId: Long, limit: Int, onResult: (MessagePageDto, String?) -> Unit)` | `page(sessionId:beforeId:limit:onResult:)` | 查询 Default；回调 Main | 分页查询；`error == nil` 表示成功 |
| `find(messageId: Long, onResult: (MessageDto?, String?) -> Unit)` | `find(messageId:onResult:)` | 同上 | 单条消息（诊断/自检） |

- `limit <= 0` → 默认 50；上限 200。
- **不导出 Flow**：Swift 侧包装成 `async -> Result<MessagePage, BridgeError>`（`MessageHistoryClient.swift`）。

### 3.2 DTO（字段顺序稳定，改名前先改本文档）

`MessageDto`
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Int64 | `chat_messages.id`（稳定、单调） |
| `sessionId` | String | 所属会话 |
| `senderName` | String | 发送者名（AI 为角色名，用户为「博士」） |
| `isMe` | Bool | 是否用户消息（`isMe != 0`） |
| `text` | String | 可见正文：`text`=原文；`ai_json`=台词段拼接（已剥状态卡标签）；`image`=附言；`gift/system`=占位文案 |
| `narration` | String | 旁白（仅 `ai_json` 线下/导演模式的 narration 段） |
| `type` | String | `text` / `ai_json` / `image` / `gift_hidden` / `gift_reply_failed` / `system` |
| `imageId` | String | `image` 消息的 `imageUri`（T31 ImageMessageContract），其它为空串 |
| `timePayload` | String | **展示用时间**：今天 `HH:mm`，更早 `M/d HH:mm`（shared 侧按本机时区格式化，Swift 不再重复格式化） |
| `timestamp` | Int64 | 原始毫秒时间戳（排序/诊断） |
| `mode` | String | 消息落库时的会话模式（online/offline） |
| `isRecalled` | Bool | `ai_json` 全部段落 `recalled=true`（整条撤回） |
| `segments` | List<MessageSegmentDto> | **T59 新增（末位字段）**：`ai_json` 可见段按原始交错顺序展开（撤回/空段过滤；整条撤回为空数组）；其它 type 为空数组。UI 每段一个气泡，`index` 为稳定段索引。详见 `ios-port/specs/bubble-split-spec.md` §2 |

`MessageSegmentDto`（T59）
| 字段 | 类型 | 说明 |
|---|---|---|
| `index` | Int | 原始 segments 数组下标（过滤后不回填） |
| `type` | String | `dialogue` / `narration`（小写归一化） |
| `text` | String | 已剥内部状态卡标签的可见段文本 |

`MessagePageDto`
| 字段 | 类型 | 说明 |
|---|---|---|
| `items` | List<MessageDto> | 本页消息，**按 id 倒序**（最新在前） |
| `hasMore` | Bool | 是否还有更早消息 |
| `nextBeforeId` | Long | 下一页游标（本页最旧一条 id；0 = 无） |
| `totalCount` | Int | 该会话消息总数（`countMessagesBySession`，状态条/证据用） |

## 4. 分页语义（关键契约）

1. **首屏**：`beforeId = 0` → `MessageRepository.getRecentMessagesSync(sessionId, limit+1)`，
   实际返回 `ORDER BY timestamp DESC, id DESC LIMIT limit`；多取 1 条判定 `hasMore`，避免额外 COUNT。
2. **下一页**：`beforeId = 上一页 nextBeforeId` → 桥内先 `getMessageById(beforeId)` 取锚点 `timestamp`，
   再走现有 `getMessagesBefore(sessionId, timestamp, id, limit+1)`（`(timestamp,id)` 严格递减）。
   id 与 timestamp 同向单调（引擎分配 id 递增、timestamp 取当前毫秒），因此对外表现为「按 id 倒序分页」。
3. **锚点不存在**（消息被删除/清理）：退化为最新一页返回，保证调用方不陷入死循环；客户端按 id 去重。
4. **稳定性**：分页键是 `(timestamp,id)`，同 timestamp 的多条消息不会漏/重；客户端用 `Set<id>` 二次去重。
5. **翻转**：Swift 侧把 items `reversed()` 成时间正序后插入 `messages`；前插时记录原最早一条 id 作为
   `ScrollViewReader` 锚点，避免时间线跳动。
6. **发送后刷新**：`beforeId = 0` 重新拉最新一页，`messages = 更早已加载段(id < 最新页最旧 id) + 新页`，
   保留用户已加载的历史，不把时间线重置成 50 条。

## 5. content 解析（与 T19 ChatEngine 写入格式对齐）

| `type` | 写入方 | 解析（复用 shared 逻辑） |
|---|---|---|
| `text` | ChatEngine 用户消息 | 原文直出 |
| `ai_json` | ChatEngine AI 回复（`OfflineModeResponse` JSON） | `DefaultReplyCodec.normalize` → `VisibleReplyContract.sanitizeVisibleSegmentContent` 逐段清洗；`PrivateReplyFormat.isPrivateSegmentRecalled` 过滤撤回段；旁白与台词分开；整条全部撤回 → `isRecalled=true` |
| `image` | 图片消息（T31） | `ImageMessageContract.parse` → `imageUri`→`imageId`、`caption`→`text`；`status=failed` → `[图片发送失败]` |
| `gift_hidden` / `gift_reply_failed` | 礼物 | `[送出了礼物]` / `[礼物回复失败]` |
| `system` | 系统提示 | 原文（时间线居中胶囊） |

解析失败兜底：`ai_json` 解析异常 → content 前 300 字，绝不抛给 Swift（单条脏数据不阻塞整页）。

## 6. 性能注意

1. **只查窗口**：禁止 `getMessagesSync`（全量）；每页固定 `limit=50`，多取 1 条判 hasMore。
2. **索引**：查询命中 `chat_messages_session_timestamp_id(sessionId, timestamp, id)`；按会话分页无全表扫。
3. **LazyVStack 结论保持**（T12）：稳定 Int64 id + 惰性实例化；**新增历史后仍不用玻璃气泡**（一屏 ≤5 玻璃不变）。
4. **前插不重置滚动**：只对「末尾新增（发送/首屏）」滚到底，前插以原最早 id 为锚点 `scrollTo(anchor:.top)`。
5. **不整页重载**：发送后只刷新最新一页并与已加载段合并；乐观占位（负数临时 id）在刷新后由真实 DB id 替换。
6. **主线程零 DB**：查询在 `Dispatchers.Default`（桥 workScope），回调回 Main；Swift await 不阻塞 UI。

## 7. 验证口径

| 验收项 | 方法 | 实测结果（2026-11-23） | 证据 |
|---|---|---|---|
| Kotlin 编译 | `:shared:linkDebugFrameworkIosSimulatorArm64` | **BUILD SUCCESSFUL** | `evidence/t34-framework-link.log`、`t34-ios-tests.log` |
| iOS 全量单测 | `:shared:iosSimulatorArm64Test` | **563 tests / 0 failures**（62 suites） | `evidence/t34-ios-tests.log` |
| 编译 | `xcodebuild build`（工作区 DerivedData） | **BUILD SUCCEEDED** | `evidence/t34-build.log` |
| 历史来自 shared DB | 模拟器截图 + `sqlite3` 直查容器 `rhodes.db` 对照 id/content | ✅ DB 124 条（fixture 120 + 自检发送 4），最新 id 1128 与日志逐条对应 | `evidence/t34-chat-history.png`、`t34-db-query.txt` |
| 分页加载 | `--history-load-more`：50 → 100 → 120；深链首屏严格 50 条 | ✅ pageMore prepended=50/20，loaded=120/120 hasMore=false | `evidence/t34-chat-pagination.png`、`t34-simulator-logs.txt` B 段 |
| 发送后刷新 | `--selftest` 发送 → `history after send` + 截图新 AI 气泡 | ✅ 122 → 124，refreshed loaded=52/124 newestId=1128 newestIsMe=false | `evidence/t34-chat-after-send.png`、`t34-selftest.log` |
| 深链 | `--open-url rhodes://chat/session_amiya` → 打开对应会话历史 | ✅ `handled=true`，聊天页自动加载 50 条历史 | `evidence/t34-simulator-logs.txt` A 段、`t34-chat-history.png` |
| 2,000 条基线不回归 | `--stress-2000` 截图 | ✅ messages=2000 stress=true，LazyVStack 正常 | `evidence/t34-chat-stress-2000.png` |

> 分页 fixture：`ios-port/handoffs/evidence/t34-seed-history.sql`（向模拟器容器 DB 写入 120 条确定性历史消息，
> ids 1001..1120，覆盖 text/ai_json(含旁白)/image/system/撤回；App 内只用桥只读读取）。
> 真机/正式环境由真实聊天自然积累，无需该 fixture。实现记录见 `ios-port/handoffs/impl-history-bridge.md`。

## 8. 已知限制

1. `MessageRepository` 现有查询按 `(timestamp,id)` 游标；桥对外暴露 id 游标，锚点被删时退化为最新页（见 §4.3）。
2. 无增量/观察通道（不导出 Flow）：新消息经发送回调 + 刷新触发；其它端写入后需重新打开会话或调用刷新。
3. `isRecalled` 只对 `ai_json` 有效（text/image 的撤回无独立落库字段，Phase 3 未接）。
4. 头像/立绘仍为占位（RoleBridge 属 T36）；图片消息只展示 `imageId` 文本，本地图片渲染属 T31 后续。
5. `timePayload` 由 shared 侧格式化，若后续要跟随 Dynamic Type / 相对时间，再扩展字段（当前固定 `HH:mm` / `M/d HH:mm`）。
