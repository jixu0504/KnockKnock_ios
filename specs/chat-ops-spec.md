# T42 聊天消息操作规格卡（撤回 / 重试 / 重说 / 继续说 / 图片 / 引用占位 / 删除本地）

> 作者：impl-chat-ops（task-42）｜日期：2026-11-23｜分支 ios-port（Kotlin 2.3.21 / Xcode 27.2）
> 上游依据：`ios-port/specs/private-chat-engine-spec.md`（私聊轮次引擎 20 条契约）、
> `ios-port/checklist/功能对照清单-v1.md` §2.1 消息操作、Android oracle `app/.../ui/chat/component/MessageList.kt` + `viewmodel/ChatViewModel.kt`。
> 结论先行：**7 项操作已接真实引擎并落 DB 验证**（撤回整条/撤回分段/重试/重说/继续说/图片发送/本地删除），
> **1 项如实标注为占位不发送**（引用回复：引擎与 Android 私聊均无 replyTo 字段）。

## 1. 操作矩阵（原版行为 ↔ iOS 实现 ↔ 引擎能力）

| # | 操作 | Android 原版行为（file:line） | 引擎能力（shared/domain/chat） | iOS 实现位置 | 结论 |
|---|---|---|---|---|---|
| 1 | 复制 | 消息菜单「复制」→ 剪贴板 + Toast「已复制」（`MessageList.kt:413-417`，旁白 :543-547） | 无（纯 UI） | `ChatViewModel.copyText` → `UIPasteboard` + 轻提示 | ✅ 实现 |
| 2 | 引用 | **私聊消息菜单无引用**；仅动态评论可带 replyTo（`AppScreens.kt:432`） | 无 replyTo 字段（`ChatMessage` 无该列，引擎无引用发送入口） | `MessageBubble.contextMenu`「引用」→ 输入栏上方「引用 xxx」条（可取消）；发送时按普通消息发送并在日志记录未支持 | ⚠️ 占位不发送（如实标注） |
| 3 | 撤回整条 | 菜单「撤回」→ `recallMessage`：取消在飞 job、turn→cancelled、删行、刷新会话预览（`MessageList.kt:420-423`；`ChatViewModel.kt:2083-2114`） | `ChatEngine.recallMessage(sessionId,msgId)`（`ChatEngine.kt:919-943`） | `ChatEngineBridge.recallMessage` → `ChatViewModel.recall` → 菜单「撤回」（仅自己消息） | ✅ 实现 |
| 4 | 撤回分段 | 旁白/分段菜单按段撤回（`recallMessageSegment :2173-2190`；`markSegmentRecalled :2269-2317`） | `ChatEngine.recallMessageSegment(...)`（`:946-959`；非法参数退化整条；全段撤回删整条） | 桥 `recallMessageSegment`（回调式）+ `ChatViewModel.recallSegment`（iOS 气泡不拆段渲染，菜单只暴露整条撤回） | ✅ 桥/引擎实现；UI 仅整条（说明见 §5） |
| 5 | 重试失败消息 | 失败气泡「未送达，点击重试」→ `retryFailedMessage`（`MessageList.kt:520`；`ChatViewModel.kt:2150-2170`） | `ChatEngine.retryFailedMessage`（`:880-916`；仅 `send_failed` / `gift_reply_failed`，防抖去重） | 桥 `retryMessage` + 菜单「重试」（`message.isSendFailed`） | ✅ 实现 |
| 6 | 重说（重新生成） | AI 消息菜单「重说」→ `regenerateAiMessage`：注入重说指令、乐观锁替换、失败保留原回复（`MessageList.kt:552-556`；`ChatViewModel.kt:2363-2421`） | `ChatEngine.regenerateAiMessage`（`:962-1016`；`replaceReplyIfUnchanged`） | 桥 `regenerateMessage` + 菜单「重说」（AI 消息） | ✅ 实现 |
| 7 | 继续说（续写） | AI 消息菜单「继续说」→ `continueAiMessage`：追加新 ai_json（`MessageList.kt:557-561`；`ChatViewModel.kt:2443-2482`） | `ChatEngine.continueAiMessage`（`:1019-1069`） | 桥 `continueMessage` + 菜单「继续说」 | ✅ 实现 |
| 8 | 图片发送 | + 按钮 → 相册/相机 → 落盘 → `sendImageMessage`（`ChatViewModel.kt:1819-2006`；入库 `ImageUtils.kt`/`MainActivity.kt:170-215`） | `ChatEngine.sendImageMessage`（`:685-719`；识图 `:721-872`）；`resumeImageReply`（`:219-240`） | `ChatViewModel.sendImage` → `bridge.ingestImage`（媒体 ID 化）→ `bridge.sendImage`；`+` 按钮 `PhotosPicker` | ✅ 实现（演示模式识图桩见 §5） |
| 9 | 图片重发 | 识别失败图「图片识别失败，点击重试」→ `resumeImageReply`（`MessageList.kt:501`；`ChatViewModel.kt:2742`） | `ChatEngine.resumeImageReply`（`:219-240`） | 桥 `retryImageMessage`；菜单「重试」对 `isFailedImage` 生效 | ✅ 引擎路径已接 |
| 10 | 图片查看 | 气泡内 AsyncImage 展示（`MessageList.kt:499`） | 无（纯 UI） | `MessageBubble.LocalMessageImage` + 点击 `FullScreenImageView`（黑底等比缩放） | ✅ 实现（增强） |
| 11 | 删除（本地） | 私聊消息菜单**无**删除项（仅群/会话级删除） | `ChatStore.deleteMessage` + `ChatRepository.deleteMessage`（本地行 + display events；`MessageRepository.kt:332-337`） | 桥 `deleteMessageLocal`：删行 + 级联删 turn 引用 + 重算会话预览；菜单「删除（本地）」+ 二次确认 | ✅ 实现（T42 新增，超出原版菜单） |
| 12 | 播放（TTS） | 菜单「播放」→ 朗读（`MessageList.kt:563`） | iOS 无 TTS（范围排除清单：语音/ASR/TTS） | — | ❌ 不支持（项目范围排除） |
| 13 | 分享 | checklist 提及分享 | iOS 无分享桥 | — | ❌ 不实现（非本卡范围） |

## 2. 桥接契约（`shared/src/iosMain/.../iosbridge/ChatEngineBridge.kt`，T42 扩展）

纪律沿用 ios-bridge-spec：只导出简单类型；回调一律 Main 队列；`error == null` 表示成功。

| 方法 | 参数 | 回调 | 语义 |
|---|---|---|---|
| `recallMessage(sessionId, messageId, onDone)` | String, Long | `(String?) -> Unit` | 撤回整条（引擎删行 + turn 终态 + 会话预览） |
| `recallMessageSegment(sessionId, messageId, segmentIndex, onDone)` | 第 3 参 Int | 同上 | 分段撤回；非法/全段 → 引擎退化整条 |
| `retryMessage(sessionId, messageId, onDone)` | | 同上 | 仅 `send_failed`/`gift_reply_failed`；否则返回中文原因 |
| `regenerateMessage(sessionId, messageId, onDone)` | | 同上 | 重说；以「内容是否变化」判定成功，失败保留原回复 |
| `continueMessage(sessionId, messageId, onDone)` | | 同上 | 继续说；以「消息数增加且无失败系统消息」判定成功 |
| `retryImageMessage(sessionId, messageId, onDone)` | | 同上 | 图片重发（`resumeImageReply`） |
| `deleteMessageLocal(sessionId, messageId, onDone)` | | 同上 | 本地删除 + 会话预览重算（不产生撤回语义） |
| `sendImage(sessionId, imageId, caption, onDelta, onDone)` | imageId 为媒体 ID | `(ChatResultDto) -> Unit` | 与 `send` 同风格：onDelta 打字机、onDone 一次 |
| `ingestImage(sourcePath, onDone)` | 本地文件绝对路径 | `(mediaId, String?) -> Unit` | 复制到 `Application Support/media/chat_images/media/<uuid>.<ext>`，返回媒体 ID |
| `imagePath(imageId)` | 媒体 ID | 同步 `String` | 渲染用绝对路径（http(s) 原样、不存在返回 ""） |
| `debugSeedFailedMessage(sessionId, text, onDone)` | 仅自检 | `(Long, String?) -> Unit` | 构造 `send_failed` 前置状态（真实重试路径的唯一触发手段） |

实现要点（与 Android oracle 对齐）：

- **识图**：复用 commonMain `modelgateway`（`effectiveVisionConfiguration` / `createVisionGateway`，与 Android `ChatEngineFactory.currentVisionGateway` 同一实现）；prompt 逐字复用 `ChatViewModel.kt:1921-1935`；Keychain 密钥经 `ProviderConfigResolver.apiKeyFor` 回填（T37 保存后 `settings.apiKey` 已清空）。
- **imageForModel**：本地文件 → `data:image/<mime>;base64,…`，>4MB 拒绝（对齐 `MainActivity.prepareImageForModel:197-215`）。
- **演示模式**（未配置任何供应商）：AI 与识图都走本地确定性桩（`DEMO_VISION_JSON`），不联网；真实模式下未配置识图模型时引擎返回「图片聊天需要先设置识图模型」Toast。
- **会话预览刷新**：撤回/删除后按最新一条消息重算（`[图片]` / `[礼物]` / 文本前 50 字），与引擎 `messagePreviewForSession` 同口径。
- **单次操作错误捕获**：`EngineEvent.Toast` + `EngineEvent.FailureMessageStored` 进入 `actionError`，用于重说/继续说判定失败原因。

## 3. Swift UI 接线

| 文件 | 改动 |
|---|---|
| `iosApp/RhodesLink/DesignSystem/MessageBubble.swift` | 新增 `imageURL` / `isImageMessage` / `onImageTap`；图片缩略图 `LocalMessageImage`（本地 UIImage / 远程 AsyncImage / 失败占位）；图片消息正文只显示附言（占位串不重复） |
| `iosApp/RhodesLink/DesignSystem/GlassInputBar.swift` | `+` 按钮 → `onAddAttachment`（原为 Phase 4 TODO 占位） |
| `iosApp/RhodesLink/Features/Chat/ChatView.swift` | 气泡 `.contextMenu`（复制/引用/撤回/重试/重说/继续说/删除本地，条件见操作矩阵）；输入栏上方引用条（可取消）；`PhotosPicker` + `onChange` 发送图片；`FullScreenImageView` 全屏查看；操作轻提示；删除二次确认 |
| `iosApp/RhodesLink/Features/Chat/ChatViewModel.swift` | 操作状态机（`isActionBusy` / `actionFeedback` / `quotedMessage` / `fullscreenImage`）、`runOperation` 统一回调 + 刷新、图片缩放（≤1920px JPEG 0.8）+ 临时文件 + ingest + sendImage、`imageURL` 缓存 |
| `iosApp/RhodesLink/Features/Chat/ChatModels.swift` | `ChatMessage.type` + `isImage/isAIMessage/isSendFailed/isFailedImage` 菜单可用性判定 |
| `iosApp/RhodesLink/Bridge/ChatOpsClient.swift` | 新增（桥的 Swift 包装，async/await；不向上暴露 Kotlin 类型） |
| `iosApp/RhodesLink/Features/Chat/ChatOpsAutomation.swift` | 新增 `--chat-ops-selftest` 自动化（真实引擎逐项执行 + 前后消息数/字段记录 + 日志落盘） |

交互细节：
- 撤回只在**自己且 id>0 的真实消息**上出现；重试只在 `send_failed`/`gift_reply_failed`/失败图片；重说/继续说只在 AI 消息；删除仅在本地真实消息（含二次确认）。
- 图片消息点击 → 全屏（黑底、等比缩放、点击/按钮关闭）。
- 引用条：`引用 我/角色名：前 40 字` + ✕ 取消；发送时清除引用按普通消息发送（日志记录 `quote send placeholder`）。

## 4. 验证口径

1. **自动自检**（模拟器，真实引擎 + 真实 DB）：
   ```bash
   xcrun simctl launch <UDID> com.rhodeslink.app --selftest --chat-ops-selftest --open-url rhodes://chat/<sessionId>
   ```
   日志每项输出 `PASS=true/false`；证据：`Documents/ios-shell-selftest.log`（`simctl get_app_container` 取出）。
2. **DB 复核**：`sqlite3 <container>/Library/Application Support/rhodes.db "select id,type,substr(content,1,60) from chat_messages where session_id='<id>' order by id desc limit 20;"`；
   撤回后行消失、重试后 `type` 从 `send_failed` 变 `text`、重说后 AI 内容变化、继续说/图片后行数增加、图片行 `type='image'` 且 content 含 `imageUri=media/...`。
3. **回归**：`:shared:testDebugUnitTest` 不回归（T42 只加 iosMain 桥接与 Swift UI，不动 commonMain/引擎）。
4. 截图：`ios-port/handoffs/evidence/t42-*`。

## 5. 未支持项与原因（如实标注）

| 项 | 状态 | 原因 |
|---|---|---|
| 引用发送（replyTo） | 占位不发送 | `ChatMessage`/`ChatReplyTurn` 无 replyTo 字段；Android 私聊同样无引用发送（仅动态评论支持）。UI 提供引用条展示与取消，发送不伪造字段 |
| 分段撤回的 UI 入口 | 引擎/桥已实现，UI 未暴露 | iOS 气泡按整条渲染（不渲染 Android 的逐段 display-event 卡片），无自然的分段选择手势；桥保留 `recallMessageSegment` 供群聊/后续段渲染复用 |
| TTS 播放 / 分享 | 不实现 | 项目范围排除（语音/TTS/ASR）；分享无桥 |
| 真实识图模型 | 已接线，未在无 Key 环境端到端验证 | 复用 commonMain VisionGateway；本次模拟器为演示模式，走本地识图桩；真实供应商需用户在「设置→模型供应商」配置识图 Key 后验证 |
| 图片 `content://` | 不支持 | iOS 无 ContentResolver；PhotosPicker 已在 Swift 侧落成本地临时文件再入库 |
