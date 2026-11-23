# handoffs/impl-md-chat-import.md —— T58 Markdown 聊天记录导入

> 代理：impl-md-chat-import ｜ 任务：task-58 ｜ 状态：**实现完成并验证**（2026-11-23）
> - Kotlin 单测 **21/21 PASS**（JVM `:shared:testDebugUnitTest`）
> - `:shared:linkDebugFrameworkIosSimulatorArm64` **BUILD SUCCESSFUL**
> - `xcodebuild build`（iOS Simulator）**BUILD SUCCEEDED**
> - iPhone 17 模拟器 `--markdown-import-selftest` **RESULT PASS**（37 项断言全 PASS，含 sqlite 核对与清理）
> - UI 截图 2 张（数据与备份页新入口 / 目标角色选择 Sheet）
> - 仓库：`RhodesLink/` 分支 `ios-port`，HEAD `cf22383`（**git 未提交**，工作树含 T57 并行改动）

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/backup/chatimport/MarkdownChatImporter.kt` | **新增**：`MarkdownChatParser`（4 格式解析 + 反转义 + 日期分段 + 错误语义）、`MarkdownChatDocument/MarkdownChatMessage` DTO、`MarkdownChatImporter`（会话创建/复用 + 去重 + id 分配 + 单事务 + 自检）、`MarkdownChatImportRepository` 接口 + `ChatRepositoryMarkdownImportAdapter` |
| `shared/src/iosMain/kotlin/com/rhodes/privatechat/shared/iosbridge/MarkdownChatImportBridge.kt` | **新增**：`parseMarkdownFile/parseMarkdownText/importMarkdown/currentUserName` 回调桥、`ParsedMarkdownHandle`、`--markdown-import-selftest` 自检（含 DB 快照证据） |
| `shared/src/commonTest/kotlin/.../chatimport/MarkdownChatImportTest.kt` | **新增**：`MarkdownChatParserTest`（13）+ `MarkdownChatImportExecutionTest`（抽象 8）+ `TestMarkdownChatImportRepository` |
| `shared/src/androidUnitTest/kotlin/.../MarkdownChatImportExecutionTestJvm.kt` | **新增**：JVM 内存 SQLite 路径 |
| `shared/src/iosMain/kotlin/.../iosbridge/IosAppGraph.kt` | **修改**：`markdownImportBridge` 字段、`markdownImport()`、`--markdown-import-selftest` 启动钩子（+25 行） |
| `iosApp/RhodesLink/Bridge/AppGraphClient.swift` | **修改**：`markdownImportBridge` 访问器（+3 行） |
| `iosApp/RhodesLink/Bridge/DataBackupClient.swift` | **修改**：`MarkdownParsedDocument`/`MarkdownChatPreview`/`MarkdownChatImportInfo` Swift 模型 + 3 个 async 方法（不向上暴露 Kotlin 类型） |
| `iosApp/RhodesLink/Features/Settings/BackupSettingsView.swift` | **修改**：「导入聊天记录（Markdown）」卡片（置于页首第 2 块）、解析预览、目标角色 Sheet（147 角色 + 搜索 + 默认名匹配）、结果/警告/跳转会话、错误态；`--markdown-demo` 截图钩子 |
| `ios-port/specs/markdown-chat-import-spec.md` | 规格卡（格式/导入语义/桥契约/验收/限制） |
| `ios-port/handoffs/evidence/t58-*` | 证据（见 §4） |

## 2. 格式支持（解析器白名单）

| # | 格式 | 示例 | 判定要点 |
|---|---|---|---|
| 1 | RhodesLink Markdown 导出（oracle `ReadableChatExporter.markdown`） | `# 与阿米娅的聊天记录` / `导出时间：…` / `## 2025-06-15` / `阿米娅：博士，今天辛苦了。` | 标题提取角色名；`## yyyy-MM-dd` 日期分段；空行忽略；`导出时间` 忽略 |
| 2 | 加粗称呼变体 | `**阿米娅**：你好，博士。` | 称呼两侧 `**` 剥离 |
| 3 | 冒号变体（半角/全角） | `阿米娅: 早上好` / `阿米娅：早上好` | 称呼 1–40 字符、不含冒号 |
| 4 | text 导出格式（oracle `ReadableChatExporter.text`） | `[2025-06-15 10:30] 阿米娅：上午好` | 精确时间（UTC 毫秒） |

- **反转义**：单遍扫描还原 `markdownSafe` 的 8 个转义字符（`\\ \` \* \_ \[ \] \# \>`）；未知转义原样保留。
- **isMe**：称呼 == userName（默认「博士」，桥默认取设置 `user_name`）或字面「我」。
- **时间戳**：格式 4 用显式时间；格式 1/2/3 用「日期段 UTC 零点 + 段内序号 × 60s」**确定性合成**（保证重复导入同文件零新增）；无日期段 → 0（保序靠 id）。
- **多行消息**：不匹配消息行/标题/日期段的非空行并入上一条（`\n`）。
- **错误态**：空文件 / 无可识别消息（文案列格式要求）/ >20MB / >100,000 条 → `MarkdownChatImportException`，桥转中文错误，UI 红条，不写库。

## 3. 导入执行语义

1. **目标角色**：UI Sheet 显式选择（147 角色）→ 否则解析名精确匹配 `operators.name` → 包含匹配 → 回退第一个非群聊角色；无角色明确报错。
2. **会话**：`session_<operatorId>` 命中复用（保留置顶/未读/成员/规则/头像），否则新建；`lastMessage/lastTime` 仅更新时覆盖。
3. **幂等去重**：键 `(timestamp,isMe,senderName,text)`；重复导入同文件 → `inserted=0/skipped=N` + warning「该聊天记录已导入过…」；部分重复 → 只插新增 + 「跳过 N 条重复消息」。
4. **消息 id**：`chat_messages MAX(id)+1` 起严格递增（不复用文件序号），事务内断言 `dbMax < firstNewId`。
5. **消息字段**：`content=解析文本`、`type="text"`、`mode="online"`、`senderName/时间/isMe` 保留、`senderId=""`；无 ai_json/媒体/存档/displayEvents。
6. **不触发**：模型调用、记忆写入、未读自增（`unreadCount` 原样；新建为 0）。
7. **单事务 + 自检**：会话 upsert + 批量插入同一事务；提交前校验目标会话消息数 = 导入前 + 计划数，失败回滚。

## 4. 验证证据（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t58-unit-tests.log` | Kotlin 单测 **21/21 PASS**（13 解析 + 8 导入执行；4 格式/转义/日期分段/空文件/变体/重复导入/角色匹配/id 分配/未读） |
| `t58-framework-link.log` | `:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL |
| `t58-xcodebuild.log` | `xcodebuild build` BUILD SUCCEEDED（DerivedData-t58，CODE_SIGNING_ALLOWED=NO） |
| `t58-markdown-import-selftest.log` | **37 项 PASS / RESULT PASS**：写 Documents 夹具 → 文件解析（title/角色/条数 5/日期段 2/格式/转义/多行/isMe）→ 导入（inserted=5, `session_amiya`）→ **sqlite 核对**（id 69–73 严格递增、内容顺序、senderName=阿米娅/博士、isMe=false/true、type=text、unread 不变）→ 重复导入（0 新增/5 去重/警告）→ DB 快照 → **清理**（消息 0 残留、会话行恢复、夹具文件删除） |
| `t58-db-snapshot/rhodes.db*` | 导入完成/清理前的运行库快照（供宿主 sqlite3 复核） |
| `t58-sqlite-verify.log` | 宿主 `sqlite3` 对快照核对：`69|阿米娅|0|text|博士，今天的任务辛苦了。` … `73|阿米娅|0|text|第一行…`；会话条数 40；**清理后** live 库 0 条夹具消息、会话恢复 35 条、夹具文件不存在 |
| `t58-backup-page.png` | 设置 → 数据与备份：页首新增「导入聊天记录（Markdown）」卡片（说明 + 「选择 Markdown 文件」按钮） |
| `t58-role-sheet.png` | `--markdown-demo`：解析预览（标题/格式/角色/3 条/2 日期段/首尾样例）+ 147 角色列表 + 搜索 + 「导入」（默认匹配阿米娅，按钮已可用） |
| `t58-markdown-fixture.md` | 自检夹具副本（2 日期段 / 5 消息 / 转义 / 多行） |

复现命令：

```bash
source ios-port/toolchain/env.sh
cd RhodesLink
./gradlew --no-daemon :shared:testDebugUnitTest --tests "*MarkdownChat*"
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t58" CODE_SIGNING_ALLOWED=NO
DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
xcrun simctl install $DEV DerivedData-t58/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --markdown-import-selftest   # 报告：Application Support/t58-markdown-import-selftest.log
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://settings/backup        # 截图 1
xcrun simctl launch $DEV com.rhodeslink.app --markdown-demo --open-url rhodes://settings/backup  # 截图 2
```

## 5. 限制 / 已知问题

1. **时间只有日期时不可恢复真实时刻**：格式 1/2/3 无时间信息，合成时间显示为当天 00:00/00:01…（保序，不保真实时刻）。
2. **格式固有歧义**：正文行若自身形如 `名字：内容` 会被识别为新消息；多行续行已支持。
3. **目标角色与 senderName 可能不一致**：UI 可把「与阿米娅的聊天记录」导入凯尔希会话（保留原文 senderName=阿米娅）——需求允许「选择目标角色」，v1 不做名字重写。
4. Markdown 不含媒体/存档/displayEvents/AI 轮次状态，导入不生成这些行（纯文本消息）；`rhodes-chat v1` zip 导入仍走原 T30 路径，两者互不影响。
5. 同一会话重复导入依赖「时间戳+称呼+内容」精确匹配：用户编辑过的 Markdown 内容视为新消息（与 T30 收敛键精神一致）。
6. 自检会在模拟器 Documents 留下 `t58-evidence/`（运行库快照，供 sqlite3 复核）；夹具消息/会话/夹具 md 均已清理（见 `t58-sqlite-verify.log`）。

## 6. 纪律与协作

- git **未提交**（工作树同时含 T57 并行改动：`ChatPerformanceSettingsView/SettingsAutomation/SettingsBridge/AppGraphClient` 的 T57 区域；本卡仅在自己的区域追加，`xcodebuild` 在合并工作树上 BUILD SUCCEEDED）。
- 未改 `ChatSettings/Memory/Knowledge` 页面；未改 `golden/chat-import` wire 格式（T30 路径零改动）。
- 构建纪律：gradle/xcodebuild 前 `pgrep` 检查、`--no-daemon`；iCloud 偶发缓慢按 >15min 阈值重试。
