# 规格卡：T58 Markdown 聊天记录导入（iOS）

> 任务卡：`task-58`（impl-md-chat-import）。工作区：`RhodesLink/` 分支 `ios-port`，基线 HEAD `cf22383`（Kotlin 2.3.21）。
> 定位：iOS 侧补齐「只有 .md/.markdown/.txt 可读聊天记录」时的导入能力；**不是** rhodes-chat v1 容器（T30 已做），
> 也不改 `ChatSettings/Memory/Knowledge` 页面。Android oracle：`app/.../data/backup/ReadableChatExporter.kt`（可读导出格式生产者）。

## 1. 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | Markdown 聊天记录导入（解析 + 落库 + iOS 设置入口 + 自检） |
| 阶段 | Phase 4（T58）；iOS 设置「数据与备份」第 5 块能力 |
| 对应 Android oracle | `app/src/main/java/com/rhodes/privatechat/data/backup/ReadableChatExporter.kt:12-27`（`markdown()`/`text()`）、`:44-52`（`markdownSafe` 转义）、`:38`（`## yyyy-MM-dd` 日期分段）、`ui/settings/BackupRestoreScreen.kt:247-248`（标题 `与<角色名>的聊天记录`） |
| 新文件（commonMain） | `shared/.../backup/chatimport/MarkdownChatImporter.kt`（解析器 + DTO + 导入执行 + 仓储接口/适配器） |
| 新文件（iosMain） | `shared/.../iosbridge/MarkdownChatImportBridge.kt`（回调桥 + ParsedMarkdownHandle + `--markdown-import-selftest`） |
| 修改文件 | `IosAppGraph.kt`（桥字段 / `markdownImport()` / 自检钩子）、`iosApp/Bridge/AppGraphClient.swift`（桥访问器）、`iosApp/Bridge/DataBackupClient.swift`（Swift 模型 + async 包装）、`iosApp/Features/Settings/BackupSettingsView.swift`（入口卡片 / 解析预览 / 目标角色 Sheet / 结果与错误态） |
| 测试 | `shared/src/commonTest/.../chatimport/MarkdownChatImportTest.kt`（13 解析用例 + 8 导入执行用例）、`shared/src/androidUnitTest/.../MarkdownChatImportExecutionTestJvm.kt` |
| 依赖 | `ChatRepository`（会话/消息/角色/恢复事务）、`RhodesDatabase`（chat_sessions/chat_messages/operators）、`IosFileSupport`、`MarkdownChatParser`（纯函数） |
| 不依赖 | rhodes-chat v1 容器、媒体、displayEvents、存档/历史片段（Markdown 是纯文本，无这些语义） |

## 2. 输入 / 输出 / 边界

- **输入**：UTF-8 文本（`.md` / `.markdown` / `.txt`，≤20MB）；目标角色 id（可选）；`userName`（默认「博士」，
  iOS 桥默认取设置 `user_name`）。
- **输出**：
  - 解析：`MarkdownChatDocument(title, operatorName, userName, messages[{senderName,isMe,text,timestamp}], dayCount, sourceFormat)`；
  - 导入：`MarkdownChatImportOutcome(success, sessionId, operatorId, operatorName, insertedMessages, skippedDuplicates, totalMessages, createdSession, warning, reason)`；
  - iOS DTO：`ParsedMarkdownHandle`（title/operatorName/userName/messageCount/dayCount/sourceFormat/samples/first/last）+
    `MarkdownChatImportInfoDto`。
- **边界**：
  - 文件不存在/不可读 → 「文件不存在或不可读 / 文件读取失败」；空文件 → 「文件为空」；>20MB → 「文件过大：…超过 20MB 上限」；
  - 无可识别消息 → 明确列出支持的格式（不是崩溃、不写库）；>100,000 条 → 上限错误；
  - 没有角色 → 「当前没有可用角色，请先在「联系人」中创建角色后再导入」；
  - 显式目标角色不存在 → 「找不到目标角色（id=…）」；
  - 导入单事务失败（含 ID 冲突/自检失败）→ 整体回滚，桥回传中文原因，UI 红色错误条（「单事务已回滚，现有聊天数据未受影响」）。

## 3. 支持格式（白名单）

| # | 格式 | 判定 | 示例 |
|---|---|---|---|
| 1 | RhodesLink Markdown 导出（`ReadableChatExporter.markdown`） | 首行 `# 与<角色名>的聊天记录`；`导出时间：…` 忽略；`## yyyy-MM-dd` 日期分段；消息行 `<称呼>：<内容>`；空行忽略 | `# 与阿米娅的聊天记录` / `## 2025-06-15` / `阿米娅：博士，今天辛苦了。` |
| 2 | 加粗称呼变体 | `**名字**：内容` | `**阿米娅**：你好，博士。` |
| 3 | 半角/全角冒号变体 | `名字: 内容` / `名字：内容`（称呼 1–40 字符、不含冒号/首字符非空白） | `阿米娅: 早上好` |
| 4 | text 导出格式（`ReadableChatExporter.text`） | `[yyyy-MM-dd HH:mm] 名字：内容`（时间精确到分钟，UTC 解析） | `[2025-06-15 10:30] 阿米娅：上午好` |

**反转义**（解析时单遍扫描，仅还原 `\` + 白名单字符）：`\\`→`\`、`` \` ``→`` ` ``、`\*`→`*`、`\_`→`_`、`\[`→`[`、
`\]`→`]`、`\#`→`#`、`\>`→`>`。未知转义（如 `\n`）保持原样（与 `markdownSafe` 只转义上述 8 字符一致）。

**isMe 判定**：`senderName == userName`（默认「博士」）或字面「我」。

**时间戳**：
- 格式 4 采用显式 `yyyy-MM-dd HH:mm` → UTC 毫秒（`daysFromCivil`，无时区依赖，确定性）；
- 格式 1/2/3 在 `## yyyy-MM-dd` 分段内无时间信息 → **确定性合成**：`UTC 零点 + 段内序号 × 60_000ms`；
- 无日期段且无显式时间 → `0`（同段消息仍按 id 保持顺序）。

**多行消息**：不匹配任何消息行/标题/日期段的非空行视为上一条消息的续行（`\n` 连接）；空行仅作分隔。
已知限制：正文行若自身形如 `名字：内容` 会被识别为新消息（可读文本格式固有歧义，v1 记录）。

## 4. 导入执行语义

1. **目标角色**：显式 `targetOperatorId`（UI Sheet 选择，147 角色列表）→ 否则按解析出的 `operatorName`
   精确匹配 `operators.name` → 其次包含匹配 → 否则回退列表第一个非群聊角色。
2. **会话**：稳定私聊 id `session_<operatorId>`（oracle `SessionRepository.kt:42-43`）→ 命中复用（
   保留置顶/未读/成员/规则/头像），否则新建；`lastMessage/lastTime` 仅在导入消息更新时覆盖
   （镜像 `ChatSessions.sq:45 updateLastMessageIfNotNewer`）。
3. **幂等去重**：键 `timestamp\u0000isMe\u0000senderName\u0000text`；重复导入同文件 → `inserted=0`、
   `skippedDuplicates=N`、`warning="该聊天记录已导入过：N 条消息全部命中已有记录，未新增消息"`；
   部分重复 → 只插入新增消息 + `warning="跳过 N 条重复消息…"`。时间戳确定性保证「同文件重复导入零新增」。
4. **消息 id**：从 `chat_messages MAX(id)+1` 起严格递增分配（不复用 Markdown 序号）；事务内二次断言
   `dbMax < firstNewId` 防并发冲突。
5. **消息字段**：`content=解析文本`、`type="text"`、`mode="online"`、`senderName/时间/isMe` 保留、
   `senderId=""`、其余默认；**不**写 ai_json/displayEvents/存档/历史片段。
6. **不触发**：模型调用、记忆写入、未读自增（会话 `unreadCount` 原样保留；新建会话 0）。
7. **单事务 + 自检**：`chat_sessions` upsert + 批量 `chat_messages` 插入同一事务；提交前校验目标会话
   消息数 == 导入前 + 计划数，失败抛错回滚。
8. **串行**：桥内导入与解析共用 `workScope` 串行执行；Schema 层无跨会话锁（导入命令由 UI `busy` 防重入）。

## 5. iOS 桥契约（`MarkdownChatImportBridge`）

| 方法 | 说明 |
|---|---|
| `parseMarkdownFile(path:userName:onDone:)` | 校验存在/非空/≤20MB → 回调 `ParsedMarkdownHandle?` + 中文错误 |
| `parseMarkdownText(text:userName:onDone:)` | 同上（调试/自动化） |
| `importMarkdown(handle:targetOperatorId:onDone:)` | 目标角色空串 = 自动匹配；回调 `MarkdownChatImportInfoDto?` |
| `currentUserName()` | 设置 `user_name`（默认「博士」），UI 解析时传入 |
| `debugRunSelfTest(onDone:)` | `--markdown-import-selftest` 端到端自检（§6.2） |

桥约定：只导出简单 DTO/句柄；回调一律 Main 队列；错误第二参数为中文字符串；捕获所有 Throwable 不崩溃。

## 6. 验收

### 6.1 Kotlin 单测（全绿）

`./gradlew :shared:testDebugUnitTest --tests "*MarkdownChat*" --no-daemon` → **21/21 PASS**
（`MarkdownChatParserTest` 13 + `MarkdownChatImportExecutionTestJvm` 8，02:xx 运行，证据 `handoffs/evidence/t58-unit-tests.log`）：
4 种格式解析、markdown 反转义、日期分段时间戳与跨天差值、text 时间精确时间、多行续行、全/半角冒号、
自定义 userName、空文件/格式不符/>20MB 报错；导入执行：新建会话与消息顺序/内容/senderName/isMe/type、
重复导入零新增+警告、增量补充、显式目标角色、名称不匹配回退、会话复用保留置顶/未读、消息 id 从全局 MAX+1 分配、
无角色明确报错。

### 6.2 模拟器端到端自检 `--markdown-import-selftest`

启动：`xcrun simctl launch <udid> com.rhodeslink.app --markdown-import-selftest`（外加 `--open-url rhodes://settings/backup` 截图用）。
步骤与断言（日志逐行 PASS/FAIL）：
1. 写入 `Documents/t58-markdown-fixture.md`（RhodesLink md 格式：2 日期段 / 5 消息 / 转义内容 / 多行内容）；
2. 文件解析：title、operatorName、messageCount=5、dayCount=2、sourceFormat=rhodes-markdown、反转义、续行、isMe；
3. 导入真实 DB（目标角色=夹具角色）：inserted=5、sessionId=`session_<operatorId>`；
4. sqlite 核对（`chat_messages ORDER BY timestamp ASC, id ASC`）：条数增量、id 严格递增、内容顺序、senderName、
   isMe、type=text、unread 不变、lastMessage 非空；
5. 重复导入：inserted=0、skipped=5、warning 非空、DB 消息数不变；
6. 清理：删除本次插入消息、恢复/删除会话行、删除夹具文件。
证据：`Application Support/t58-markdown-import-selftest.log`（+ 控制台 NSLog `[RhodesLink][T58SELFTEST]`）。

### 6.3 UI 证据

- `--open-url rhodes://settings/backup` 启动后截图：数据与备份页顶部新增「导入聊天记录（Markdown）」卡片；
- `--markdown-demo`：解析内置示例并弹出「选择目标角色」Sheet（147 角色 + 解析预览 + 导入按钮）→ 截图。

## 7. 显式不做 / 限制

- 不逆向做「可读文本 → rhodes-chat v1」容器；媒体/存档/displayEvents 不随 Markdown 导入；
- Markdown 无消息时间（格式 1/2/3）→ 采用日期段内合成时间，展示为当天 00:00、00:01…（保序，不保真实时刻）；
- 正文行形如 `名字：内容` 时会被误判为新消息（可读文本歧义）；多行内容续行已在 v1 支持；
- 同一会话内重复导入依赖「时间戳+称呼+内容」精确匹配；若用户改动 Markdown 内容则视为新消息（与 T30 收敛键精神一致）；
- 导入不合并已完成/在途 AI 轮次状态（Markdown 不含该信息）。
