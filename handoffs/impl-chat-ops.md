# T42 实现 handoff：聊天消息操作（撤回 / 重试 / 重说 / 继续说 / 图片 / 引用占位 / 本地删除）

> 代理：impl-chat-ops（task-42）｜规格：`ios-port/specs/chat-ops-spec.md`（本卡产出）
> 状态：**实现完成；Shared.framework 重链 + xcodebuild 通过；iPhone 17 / iOS 27.2 模拟器实跑：7 项真实引擎操作全部 PASS（含 DB 复核与截图）**
> git：**不提交**（lead 统一提交）；写域仅 `shared/src/iosMain` + `iosApp/.../Features/Chat`、`DesignSystem`、`Bridge`（见 §1）。未改 AppRouter/RouteView。

## 1. 写域确认 / 交付物清单

| 路径 | 类型 | 说明（关键行号） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/ChatEngineBridge.kt` | 修改（+356 行） | 构造注入 `MediaRepository(MediaStoreIos)` :81-84；引擎接识图三个钩子 :92-94；**T42 操作 API**：`recallMessage` :168、`recallMessageSegment` :179、`retryMessage` :192、`regenerateMessage` :202、`continueMessage` :215、`retryImageMessage` :229、`deleteMessageLocal` :236、`sendImage` :251、`ingestImage` :306、`imagePath` :329、`debugSeedFailedMessage` :347（仅自检）；内部：`runOperation` :370 区、`updateSessionPreview`、`imageForModelDataUrl` :432、`visionConfiguration` :453、`analyzeVision` :465；常量 `VISION_ANALYZE_PROMPT` :568、`DEMO_VISION_JSON` :579 |
| `RhodesLink/iosApp/RhodesLink/Bridge/ChatOpsClient.swift` | 新增 | 桥的 Swift 包装（async/await，nil=成功）：撤回/分段/重试/重说/继续说/图片重发/本地删除/图片入库/图片发送/图片路径/自检夹具 |
| `RhodesLink/iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 修改（2 行） | `assertMainQueue` 改 internal :125；新增 `chatEngineBridge` accessor :158（extension 用，不导出 Kotlin 类型） |
| `RhodesLink/iosApp/RhodesLink/Features/Chat/ChatViewModel.swift` | 修改 | 操作状态 `quotedMessage` :31 / `fullscreenImage` :37；`refresh()` :159；引用占位发送日志 :230-237；`imageURL` :275；`copyText` :292、`quote` :299、`recall` :310、`recallSegment` :317、`retry` :328、`regenerate` :338、`continueGenerating` :345、`deleteLocal` :352；统一 `runOperation` :360；图片 `sendImage` :392 + 缩放 `prepareChatImage` :446 |
| `RhodesLink/iosApp/RhodesLink/Features/Chat/ChatView.swift` | 修改（+~180 行） | 长按 `messageMenu` :144；引用条 `quoteBar` :195；PhotosPicker :82-101；删除二次确认 :105；全屏 `fullScreenCover` :115 + `FullScreenImageView` :393；操作轻提示 overlay；气泡传图片参数 |
| `RhodesLink/iosApp/RhodesLink/Features/Chat/ChatModels.swift` | 修改 | `ChatMessage.type` + `isImage/isAIMessage/isSendFailed/isFailedImage` 菜单可用性判定 |
| `RhodesLink/iosApp/RhodesLink/Features/Chat/ChatOpsAutomation.swift` | 新增 | `--chat-ops-selftest` 自检：真实引擎逐项执行 + 前后消息数/字段记录 + 日志落盘；`--chat-ops-fullscreen` 展示全屏图（截图证据） |
| `RhodesLink/iosApp/RhodesLink/DesignSystem/MessageBubble.swift` | 修改 | `imageURL/isImageMessage/onImageTap` :30-36；图片缩略图 `LocalMessageImage` :145（本地 UIImage / 远程 AsyncImage / 失败占位）；图片消息正文仅显示附言 :96 |
| `RhodesLink/iosApp/RhodesLink/DesignSystem/GlassInputBar.swift` | 修改（2 行） | `onAddAttachment` :24；`+` 按钮接线 :42（原 Phase 4 TODO） |
| `ios-port/specs/chat-ops-spec.md` | 新增 | 操作矩阵（原版 ↔ iOS ↔ 引擎能力）、桥接契约、验证口径、未支持项 |
| `ios-port/handoffs/evidence/t42-*` | 新增 | 2 张截图 + 自检日志 + DB 复核 + framework/build 日志 + 单测汇总 |

**不改**：Android `app/`、`shared/commonMain`（零改动，纯 iosMain 桥 + Swift）、AppRouter/RouteView、其他代理文件（T40 的 SessionList/隐藏逻辑保持原样）。

## 2. 操作实现矩阵（简要，详见 spec §1）

| 操作 | 引擎入口 | iOS 入口 | 结果 |
|---|---|---|---|
| 撤回整条 | `ChatEngine.recallMessage`（:919） | 长按「撤回」（仅自己消息）+ 自检 | ✅ PASS |
| 撤回分段 | `ChatEngine.recallMessageSegment`（:946） | 桥已暴露（UI 气泡不拆段，未暴露菜单项） | ✅ 桥/引擎实现（自检 PASS，全段撤回→删整条符合 oracle） |
| 重试未送达 | `ChatEngine.retryFailedMessage`（:880） | 长按「重试」（send_failed/失败图片）+ 自检 | ✅ PASS（type send_failed→text） |
| 重说 | `ChatEngine.regenerateAiMessage`（:962） | 长按「重说」（AI 消息）+ 自检 | ✅ PASS（内容变化） |
| 继续说 | `ChatEngine.continueAiMessage`（:1019） | 长按「继续说」+ 自检 | ✅ PASS（消息数 +1） |
| 图片发送 | `ChatEngine.sendImageMessage`（:685） | `+` → PhotosPicker → 缩放 → `ingestImage`（媒体 ID）→ `sendImage` + 自检 | ✅ PASS（image 行 + 媒体文件 + 角色回复） |
| 图片查看 | 纯 UI | 点击气泡 → 全屏黑底等比缩放 | ✅ 截图证据 |
| 本地删除 | `ChatRepository.deleteMessage` | 长按「删除（本地）」+ 二次确认 + 自检 | ✅ PASS（行消失、预览刷新） |
| 复制 | 纯 UI | 长按「复制」→ UIPasteboard | ✅ 实现（无 DB 断言） |
| 引用 | 引擎无 replyTo | 长按「引用」→ 输入栏引用条（可取消）；发送按普通消息并记录日志 | ⚠️ 占位不发送（如实标注） |

识图接线：复用 commonMain `modelgateway`（`effectiveVisionConfiguration` / `createVisionGateway`，与 Android `ChatEngineFactory.currentVisionGateway` 同一实现）+ Keychain 密钥回填（T37 清空 settings.apiKey）；prompt 逐字复用 Android `ChatViewModel.kt:1921-1935`；演示模式（未配置供应商）走本地确定性识图桩，不联网。

## 3. 验证证据（`ios-port/handoffs/evidence/`）

### 3.1 自检（`--selftest --chat-ops-selftest --open-url rhodes://chat/session_amiya`，iPhone 17 / iOS 27.2，全新安装）

`t42-selftest.log`（原始摘录，两轮复跑均全 PASS；下为最终轮）：

```
chat-ops provider=demo session=session_amiya
chat-ops baseline total=14 newestId=46
chat-ops send ok total=16 user=47 reply=48 replyChars=85
chat-ops recall messageId=47 error=- gone=true total=15 (before=16) PASS=true
chat-ops retry failedId=49 error=- type=text total=17 PASS=true
chat-ops deleteLocal messageId=49 error=- gone=true total=16 PASS=true
chat-ops regenerate messageId=48 error=- changed=true newChars=125 PASS=true
chat-ops continue messageId=48 error=- total=16->17 PASS=true
chat-ops image id=media/5885f3c5-…png ok=true demo=true messageId=52 total=17->19 error= PASS=true
chat-ops image resolve path=5885f3c5-…png PASS=true
chat-ops recallSegment messageId=48 index=0 error=- removedWholeMessage=true total=19->18 PASS=true
chat-ops fullscreen image opened messageId=52 imageId=media/5885f3c5-…png
chat-ops quote placeholder：引擎无 replyTo 字段，引用条仅 UI 展示，发送按普通消息（记录一次）
```

### 3.2 DB 复核（`sqlite3` 真实 `chat_messages` / `chat_sessions`）

`t42-db-check.txt` 关键结论：
- 被撤回（38/47）与被本地删除（40/49）的消息行 **absent_count=0**（即确实不存在）；
- `type=image` 行 content = `{"imageUri":"media/5885f3c5-…png","caption":"T42自检图片","visionSummary":"…"}`（媒体 ID 化 + 识图回填）；
- 图片消息后紧跟 `type=ai_json` 角色回复；`chat_sessions.lastMessage` 同步刷新为图片回复预览；
- 媒体落盘：`Library/Application Support/media/chat_images/media/5885f3c5-…png`（617,021 bytes）。

### 3.3 截图

- `t42-chat-timeline.png`：聊天时间线真实 DB 消息 + 图片气泡（T42 图片自检）+ 输入栏上方引用条「引用 阿米娅：…」+ `+` 附件按钮。
- `t42-image-fullscreen.png`：点击图片后的全屏查看（黑底、等比缩放、关闭按钮、底部附言）。

### 3.4 构建 / 回归

- `t42-framework-link.log`：`:shared:linkDebugFrameworkIosSimulatorArm64` **BUILD SUCCESSFUL**（gradle --no-daemon）。
- `t42-build.log`：`xcodebuild build -scheme RhodesLink -destination 'generic/platform=iOS Simulator'` **BUILD SUCCEEDED**（DerivedData-t42，CODE_SIGNING_ALLOWED=NO）。
- `t42-shared-tests.txt`：`:shared:testDebugUnitTest` **524 tests / 0 failures / 0 errors**（与基线一致，无回归；本卡未改 commonMain）。
- 复跑命令（证据可复核）：
  ```bash
  DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
  xcrun simctl install $DEV RhodesLink/iosApp/DerivedData-t42/Build/Products/Debug-iphonesimulator/RhodesLink.app
  xcrun simctl launch $DEV com.rhodeslink.app --selftest --chat-ops-selftest --open-url rhodes://chat/session_amiya
  xcrun simctl get_app_container $DEV com.rhodeslink.app data   # → Documents/ios-shell-selftest.log
  sqlite3 "<container>/Library/Application Support/rhodes.db" "select id,type,substr(content,1,80) from chat_messages where sessionId='session_amiya' order by id desc limit 12;"
  ```

## 4. 未支持项与原因

| 项 | 状态 | 原因 |
|---|---|---|
| 引用发送（replyTo） | 占位不发送 | `ChatMessage`/引擎无 replyTo 字段；Android 私聊同样不支持（仅动态评论有）。UI 提供引用条展示/取消，发送不伪造字段并记录日志 |
| 分段撤回 UI 入口 | 桥/引擎已实现，菜单未暴露 | iOS 气泡按整条渲染（无 Android 的逐段卡片），无自然分段手势；`ChatEngineBridge.recallMessageSegment` 已交付，自检 PASS，供群聊/后续段渲染复用 |
| TTS 播放 / 分享 | 不实现 | 项目范围排除（语音/TTS/ASR）；无分享桥 |
| 真实识图模型 | 已接线（复用 commonMain VisionGateway + Keychain Key），本环境未端到端验证 | 模拟器为演示模式（未配置供应商），自检走本地识图桩；真实模式需用户在设置填写识图 Key 后验证（prompt/网关与 Android 同源） |
| 图片 `content://` | 不支持 | iOS 无 ContentResolver；PhotosPicker 已在 Swift 侧落为本地临时文件再入库 |
| 长按菜单的自动化点击 | 未做 | 本环境无 UI 自动化工具（dsh-ios 已禁用）；菜单项存在性与动作路径由编译 + 桥/引擎自检覆盖，真机需人工长按确认 |

## 5. 给 lead 的注意点

1. **构建**：本次只重链了 `iosSimulatorArm64`（模拟器验证）；真机安装需要 lead 统一执行 `:shared:linkDebugFrameworkIosArm64` 或 release framework + 真机构建（本次未跑 device target，无模拟器运行时问题）。
2. **行为变更**：聊天页 `+` 按钮从占位变为真实 PhotosPicker；`MessageBubble` 新增图片渲染与点击全屏；长按菜单为新增（复制/引用/撤回/重试/重说/继续说/删除本地）。`AppGraphClient.swift` 的 `assertMainQueue` 由 `private` 改为 internal（跨文件 extension 需要）。
3. **不动点**：未改 AppRouter/RouteView/群聊；T40 的会话隐藏逻辑只经 `EngineEvent.UnhideSession` 既有分支，未触碰。
4. **演示模式**：图片聊天在 demo 模式可完整跑通（本地识图桩）；真实供应商模式下若未配置识图模型，引擎会提示「图片聊天需要先设置识图模型」，图片消息不会写失败脏数据（引擎 guard 直接拒绝）。
