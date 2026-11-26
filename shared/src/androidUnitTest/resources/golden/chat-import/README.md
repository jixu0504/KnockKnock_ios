# chat-import —— RhodesLinkChatExport v1 聊天导出金样

- 生成器：`ios-port/golden/chat-import/make_chat_exports.py`（可复跑、确定性输出；内置镜像 iOS `ChatExportReader` 的自校验）
- 规格卡：`ios-port/specs/chat-import-impl-spec.md`（v1 格式在此冻结）
- 基线：RhodesLink Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`，分支 ios-port）
- 用途：T30「Android 聊天记录导出 → iOS 导入」的验收载体（金样往返 / 重复导入零重复 / 坏包无残留 / formatVersion 门禁）

## 1. 产物与 SHA-256

| 文件 | 字节 | SHA-256 | 内容 |
|---|---|---|---|
| `chat-text-v1.zip` | 2207 | `178e7abbd6308299be319920f3e764dd5dda6f94e9e2a6ba9d3f868619d8150d` | 纯文本会话：8 消息（4×`text` + 4×`ai_json` 无图）、6 displayEvents、1 存档、1 历史片段、0 媒体 |
| `chat-image-v1.zip` | 2472 | `0872d3df81847c43120d9fde58ad76147ed4bb752e4884df28e779246717a612` | 图片消息：6 消息（含 1 条 `type=image` content JSON 内嵌 `imageUri`）、5 displayEvents、2 媒体（头像 + 聊天图） |
| `chat-ai-json-v1.zip` | 2183 | `629c61b5db0aa52264b545acfa5a56f0cbfc2b80bf6ee89a4993f512d20d1c91` | ai_json 内嵌图：5 消息（1 条 `ai_json` 的 `segments[image].imageUri`）、4 displayEvents、1 媒体 |
| `chat-2000-v1.zip` | 25663 | `19c5f63c89a162cc76633f33074694445b71c6b4548104eb8701d6d33d26f981` | 2000 条长会话：1000×`text` + 1000×`ai_json`、1000 displayEvents、0 媒体 |

生成时打印同号 SHA-256；`python3 make_chat_exports.py` 连续两次输出逐字节一致（确定性）。

## 2. v1 容器格式（冻结）

```
RhodesLinkChatExport.zip
├── manifest.json
├── conversation.json
└── media/
    └── images/<sha256(content)[:24]>.<ext>
```

约定来源：T6 裁决（`ios-port/decisions/chat-import-可行性.md:161`）冻结 `manifest.json + conversation.json + media/`；
`mediaId = "image/<sha256(content) 全 64 hex>"`（内容派生，区别于 `BackupMediaCollector` 的路径派生；后者在 iOS 无意义）。

### 2.1 manifest.json（复用 `BackupManifest` 形状，`app/.../data/backup/BackupModels.kt:36-51`）

| 字段 | 值 | 依据 |
|---|---|---|
| `format` | `"rhodes-chat"` | 新增常量（T6 裁决） |
| `formatVersion` | `1` | 门禁：≠1 明确拒绝 |
| `scope` | `"conversation"` | 门禁：≠conversation 拒绝 |
| `appVersion` / `schemaVersion` | `"1.14.27"` / `1` | 同完整备份 |
| `createdAt` / `backupId` | 固定值（可复现） | 同 T10 金样约定 |
| `mediaIncluded` | 媒体非空 | 与 `mediaIndex` 一致性校验 |
| `recordCounts` | operators/sessions/messages/displayEvents/archives/historySegments/mediaFiles | 导入后自检基线 |
| `files` | `[{path,size,sha256}]`：`conversation.json` + 每条媒体（**不含 manifest.json 自身**） | 与 ZIP 条目严格一致（`BackupFileReader.kt:58-68` 同口径） |
| `excludedCategories` | 固定清单（memories/diaries/moments/knowledge_bases/relationships/settings/credentials…） | 单会话导出范围声明 |
| `layout` | `"single_payload"` | v1 不分页（超大会话上限见规格卡边界） |
| `pages` | `[]` | 同上 |

写入序（`BackupFileWriter.kt:46-97` 同口径）：`conversation.json` → `media/**`（按 archivePath 排序）→ `manifest.json`（最后）。

### 2.2 conversation.json（字段序 = Android `app/data/ExportPayload.kt:86-113`）

| 字段 | 说明 |
|---|---|
| `formatVersion` | `1`（与 manifest 双重门禁） |
| `exportedAt` | 固定值 |
| `operator` | `OperatorExport` 22 字段（`ExportPayload.kt:46-59`；`shared/model/Operator.kt` 同形）。单会话迁移自包含角色人设；缺失时导入方要求目标端已存在同 id 角色 |
| `session` | `SessionExport` 12 字段（`:86-92`）；私聊 id 规则 `session_<operatorId>`（`SessionRepository.kt:42-43`） |
| `messages` | `MessageExport` 15 字段（`:102-109`），按 `(timestamp, id)` 升序（`ChatMessages.sq:24` 同序） |
| `displayEvents` | `{messageId, segmentIndex, sessionId, revealOrder}`（`MessageRepository.kt:34-40`）；每个 ai_json segment 一条，`revealOrder` 为正数（Android `revealedAt` 时间戳语义） |
| `chatArchives` | 存档：`messagesJson`=List\<ChatMessage\> JSON（`ChatViewModel.kt:349`）、`stateJson`=ChatArchiveContext 形状；导入时按旧→新 id 重映射 |
| `chatHistorySegments` | 历史片段：`messagesJson` 同上重映射 |
| `mediaIndex` | `[{mediaId, archivePath, mimeType, originalUri, size, sha256}]`；与 manifest.files、ZIP `media/` 条目、实际字节三方一致 |

### 2.3 媒体

- 白名单与扫描对象复用完整备份口径（`BackupMediaCollector.kt:59-69` owned roots `images/chat_images/restored_media` + `imageUriPattern` :86 正则）：
  单会话导出扫描 `operator.avatarUri`、`session.avatarUri`、`messages.content` 内 `"imageUri":"…"`（含 `ai_json` segments 内嵌图）。
- `originalUri` 保留 Android `file:///data/user/0/<pkg>/files/...` 原样（`Uri.fromFile` 形态）；iOS 只按内容哈希映射，不解析 Android 路径。
- 非自有/远程 URI 不进包，原引用原样保留在 `content` 中（导入端媒体映射缺失时保留原值，镜像 `BackupRestorePayloadRewriter`「缺失保留」语义）。

## 3. 四类夹具的确定性取值

- 时间：`T0 = 1749999000000`，消息按分钟/秒递增；`createdAt = 1750000000000`；zip 条目时间 `2026-01-01 00:00`。
- JSON：`ensure_ascii=False`、紧凑分隔符；字段顺序 = Kotlin data class 声明序；确定性字段全量写出（对应 `encodeDefaults=true`）。
- 图片：1×1 合法 PNG 由 `zlib` 确定性构造（红/绿/蓝三种内容哈希不同，覆盖头像/聊天图/内嵌图三类引用）。
- 会话 id：`session_<operatorId>`（`chat_text` / `chat_image` / `chat_aijson` / `chat_2000`）。
- 存档/历史片段仅 `chat-text-v1.zip` 携带（用小包覆盖 id 重映射逻辑，避免 2000 包膨胀）。

## 4. 复跑与校验

```bash
cd ios-port/golden/chat-import
python3 make_chat_exports.py                 # 重新生成 4 包 + 逐包自校验 + 打印 sha256（退出码 0 = 全 PASS）
python3 make_chat_exports.py --verify chat-text-v1.zip   # 单包校验
shasum -a 256 *.zip                          # 与 §1 表比对（两次生成逐字节一致）
```

生成器自校验镜像 iOS `ChatExportReader` 契约（规格卡 §行为契约 C1-C8）：路径安全/重复/上限 → manifest 三元组门禁 →
`files` 与 ZIP 严格一致 + 逐条目 SHA-256 → conversation 结构/消息 id 唯一且升序/单会话约束 →
displayEvents 引用完整 → 存档/历史 messagesJson 引用可重映射 → mediaIndex 三方一致 + 内容派生 mediaId →
recordCounts 自检。

## 5. 与 T10 完整备份读取器（`BackupFileReader`）的关系

- **不兼容，且必须不兼容**：T10 的 `BackupFileReader.read(bytes)` 首道门禁是 `format=="rhodes-backup"`、`scope=="full"`
  （`shared/.../backup/BackupFileReader.kt:53-57`），本格式 `rhodes-chat` / `conversation` 会被明确拒绝。
  规格卡验收要求 commonTest 断言该拒绝（`BackupFileReader.validate(chat-text-v1.zip)` → `Invalid`，reason 含「不支持的备份类型」），
  防止两种容器语义混淆。
- **复用其校验思路**：`ChatExportReader`（iOS 侧新增）镜像同样的 ZIP 路径安全/上限（`BackupZip`）、
  `manifest.files` 与 ZIP 严格一致、逐条目 `size+sha256`、媒体清单一致性检查；差异仅在三元组取值与
  `conversation.json`/单会话载荷层（新增）。

## 6. 同步进测试 resources

`sync_golden.sh` 把本目录 4 个 zip 同步到：

- `RhodesLink/shared/src/commonTest/resources/golden/chat-import/`
- `RhodesLink/shared/src/androidUnitTest/resources/golden/chat-import/`

供 commonTest（双路径 JVM + iOS 模拟器）用 `expect/actual readTestResourceBytes` 读取；并校验 zip SHA-256 与 §1 表一致（防手改）。
