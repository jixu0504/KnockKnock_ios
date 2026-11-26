# 规格卡：message-id-media（MessageIdAllocator + MediaRepository）

> 模板：`ios-port/specs/规格卡模板-v1.md` ｜ 任务：task-16（T16，P0-3）｜ 状态：**已批准（PASS-WITH-FIXES，2026-11-23）**
> 黄金法则：Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`）是行为 oracle；每条行为契约必须指向 Android 源码 file:line。
> 本卡覆盖两块耦合的 P0 收口点：**消息 ID 分配器**（T10 恢复管线的隐式依赖，backup-restore-minimal-spec.md「风险 4」已裁决必须复刻）与 **媒体 ID 化运行时层**（paths.md §0/§7 P0 结论：运行时媒体引用全部 file:// 绝对路径，iOS 必须改为相对 ID + 按 ID 落盘）。
> 实现代码在 lead 批准本卡前不写入 `RhodesLink/shared/src`。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | MessageIdAllocator（消息 ID 分配器）+ MediaRepository（媒体 ID 化运行时层） |
| 所属阶段 | Phase 1（规格卡先行；实现归 A1 聊天写入路径 / A2 头像与资料 / A5 数据与备份迁移） |
| 范围映射 | A1（消息发送/图片消息/语音消息的写入与渲染路径）、A2（角色/会话/用户头像、聊天背景的存取路径）、A5（备份媒体 mediaId 直映射 + 恢复后 ID 自愈）。排除：galgame_full 子系统（paths.md §8，P1 单独评估）、AiSupportViewModel 客服对话内存 id 空间（独立计数器，不进 chat_messages 表） |
| 对应 Android 源码 | `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/MessageRepository.kt`（:46-47 idMutex+nextMessageId、:167-187 sendMessage、:190-216 sendMessageAndCreateReplyTurn、:219-239 sendReplyAndCompleteTurn、:242-252 restoreMessage、:285-294 persisted 最大 id、:296-305 getNextMessageId、:307-312 deleteSessionMessages、:318-324 deleteMessage）；`shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/ChatMessages.sq`（:40-41 getMaxId、:75-77 insertMessage、:99-101 insertMessageIfAbsent）；`app/.../data/backup/BackupMediaCollector.kt`（:17-36 collect、:43-57 referencedUris、:59-69 ownedImageFile/ownedRoots、:71-72 stableId、:74-80 mimeType、:86 imageUriPattern）；`app/.../util/ImageUtils.kt`（:13-33 copyToInternalStorage、:39-53 copyToCache）；`app/.../MainActivity.kt`（:170-176 launchTakePhoto、:178-193 copyChatImageToFiles、:197-203 prepareImageForModel）；`app/.../audio/LocalAudioController.kt`（:44 voice 落盘、:129,135,140-142 路径解析/删除）；`app/.../ui/common/AvatarImage.kt`（:24-64 内置头像解析）；调用点清单见「Android 调用点改动面」 |
| 依赖模块 | 上游：T9 ios-data-base（`shared/files/MediaDirectory`：Application Support/media/{images,voice,chat_images,newspapers} 幂等创建 + 相对 ID 校验/映射，契约 11）；T10 备份恢复管线（BackupMediaStager 的 mediaId 直映射、BackupRestorePayloadRewriter 替换、RestoreDatabaseExecutor 原样 id 插入）；T15 聊天引擎（getNextMessageId 的唯一合法消费者）；shared/data/MessageRepository（getNextMessageId 改委托本分配器）、SQLDelight（getMaxId query）、SettingsRepository（`max_used_message_id` 持久化） |

## 输入 / 输出 / 边界

- 输入：
  - `allocate()`：无参；读取三来源（内存高水位 / DB `MAX(id)` / settings 键 `max_used_message_id`）。
  - `observeUsed(id)`：外部已定 id（发送方自带 id、恢复导入的 id）上报分配器。
  - MediaRepository：来源枚举 `MediaSource`（picker/camera/remote/builtinAvatar/backupImport/voice）+ 原始引用（`file://`/`content://`/`http(s)`/`data:`/数字字符串 1..147/`local-image/<sha24>`）。
- 输出：
  - `allocate()` → `Long`（新消息 id，严格递增）；副作用：内存高水位 +1、`max_used_message_id` 可能写入 settings。
  - `MediaRepository.ingest(source, ref)` → `MediaId`（`media/<uuid>.<ext>`）；副作用：文件落 `Application Support/media/<category>/`。`resolve(mediaId)` → 本地文件 URL 或 nil。
  - 失败语义：ingest 复制/解码失败返回原引用字符串（镜像 ImageUtils.kt:24-32 回退），不残留空文件；allocate 在 DB 查询失败时**向上抛**（Android 同款，无兜底）。
- 边界（逐项，全部带 oracle）：
  - **DB 空（fresh 冷开）**：`getMaxId()==NULL → fromDatabase=1`，首个 id = 1（MessageRepository.kt:298-300 语义）。
  - **DB 损坏/查询失败**：`getMaxId` 抛错 → allocate 抛错 → 发送管线失败。Android 无特殊处理（:297-304 无 catch）；iOS 等价，**平台差异白名单：无**。
  - **删除最新消息后重启（防回退）**：persisted `max_used_message_id` 防 MAX(id)+1 回退（doc comment :285-289）；`deleteSessionMessages`/`deleteMessage` 清 reply_turns 后仍不回退（:307-312、:318-324）。
  - **恢复导入后**：DB max 变为备份内 max → allocate 取三源 max 自愈（详见契约 8）。
  - **并发**：idMutex 串行分配（:296）；发送三入口（sendMessage :167 / sendMessageAndCreateReplyTurn :190 / sendReplyAndCompleteTurn :219）与恢复（restoreMessage :242）同锁抬高内存高水位。分配与插入是**两个独立锁区** → 崩溃可跳号，接受（ID 无稠密性要求）。
  - **重入/连续调用**：每次 allocate 严格递增；同一协程连续调用得连续 id。
  - **settings 不可用（MessageRepository 构造参数 `settings: SettingsRepository? = null`）**：persisted 分量取 0（:290），退化为 max(内存, DB+1)。
  - **分配超时**：调用方 `withTimeout(MESSAGE_WRITE_TIMEOUT_MS)`（ChatViewModel.kt:1391,1465；GroupChatViewModel.kt:863,1722）——超时语义归调用方，分配器自身无超时。
  - **媒体 ID 校验**：拒绝空 / `..` / 绝对路径 / `://`（T9 契约 11）；未知 category 前缀落 media 根。
  - **远程引用**：`http(s)://` / `data:` 原样透传不落盘、不进 ID 化（MainActivity.kt:197-198；BackupMediaCollector.kt:61 `uri.scheme != "file"` 拒绝收集）——iOS 同语义，远程图不进备份。

## 行为契约（逐条，每条带 Android oracle 位置）

### A. MessageIdAllocator

1. **分配公式（自愈语义）**：`next = maxOf(内存高水位 ?: 0, DB MAX(id)+1, persistedMaxUsedMessageId+1)`；随后内存高水位 = next+1；若 next > persisted 则写回 persisted。oracle：MessageRepository.kt:296-305。iOS 复刻同公式，三来源缺一不可。
2. **persisted 持久键**：键名 `max_used_message_id`，经 `SettingsRepository.getLong/putLong` 存取，只增不减（`rememberMaxUsedMessageId` 仅在更大时写，:292-294）。oracle：MessageRepository.kt:290-294。
3. **内存高水位在每次外部 id 插入时抬高**：发送/恢复携带显式 id 时 `nextMessageId = maxOf(nextMessageId ?: 0L, message.id + 1)`。oracle：MessageRepository.kt:168（sendMessage）、:194（sendMessageAndCreateReplyTurn）、:225（sendReplyAndCompleteTurn）、:243（restoreMessage）。iOS：`observeUsed(id)` 等价语义，发送与恢复管线必须调用。
4. **恢复导入原样插入 id**：恢复用 `insertMessageIfAbsent`（INSERT OR IGNORE）原样写备份 id，不重新分配、不覆盖较新本地行。oracle：ChatMessages.sq:99-101；RestoreDatabaseExecutor 经 T10 契约 10 的插入顺序执行（backup-restore-minimal-spec.md §行为契约 10，RestoreDatabaseExecutor.kt:77-101）。iOS 恢复执行器**不感知**分配器，仅 DB 中 max 变化即完成对齐（见契约 8）。
5. **删除不回退**：`deleteSessionMessages` 删除 display_events + messages + reply_turns（:307-312）；`deleteMessage` 单事务删 display_events + reply_turns(bySource) + message（:318-324）。两者都不动 persisted —— 下次 allocate 仍 > 已删最大值。oracle 依据（原因）：MessageRepository.kt:285-289 doc comment（"MAX(id)+1 rewind after a restart … can reuse an id whose reply_turns row still exists - which silently blocks its reply"）。
6. **首 id 与空库**：fresh 冷开（DB 无行、persisted=0、内存 null）→ allocate 返回 1。oracle：MessageRepository.kt:298-300 + ChatMessages.sq:40-41（`MAX(id)` NULL → `?: 0L`）。iOS commonTest 用内存 SQLDelight driver 断言。
7. **并发唯一性**：分配由单 Mutex 串行（:296 `idMutex.withLock`），任何并发数下不重复；iOS 用同一 Mutex + DatabaseDispatcher 等价物（或 actor），禁止无锁读-改-写。
8. **恢复导入后自愈（T10 依赖点）**：
   - 冷启动恢复（恢复后进程重启）：内存 null、persisted=0（`max_used_message_id` 不进备份白名单也不回填，backup-restore-minimal-spec.md §数据与迁移「消息 id 与分配器」）→ allocate = 备份内 max(id)+1，不撞 id。oracle：MessageRepository.kt:296-305（三源 max 天然覆盖该场景）。
   - 同进程热恢复：内存高水位残留恢复前值。若恢复前本地 max > 备份 max → 继续用内存高水位（安全方向，绝不撞 id）；若恢复前本地 max < 备份 max → DB max 胜出。iOS 与 Android 语义一致（Android 恢复同样不重置 nextMessageId），**无平台差异**。
9. **与 T10 恢复执行器的关系**：分配器是恢复管线的**下游自愈依赖**，不是被调用方。恢复执行器只负责「原样插 id」（契约 4）；恢复完成后无需任何分配器重置动作。T10 验收 #10（重复导入幂等）与本金样（1 角色/1 会话/7 消息）即为回归载体。
10. **调用方超时**：allocate 不设内部超时；发送路径以 `withTimeout` 包裹（ChatViewModel.kt:1391,1465）。iOS 保留调用方超时（T15 聊天引擎负责），分配器接口保持 `suspend fun allocate(): Long`。
11. **客服对话独立 id 空间（排除）**：`AiSupportViewModel` 用自身内存计数器（:85,215,311,328，恢复修复 :419-421），不进 chat_messages 表、不经本分配器。iOS 客服模块照搬其自身计数器，不得混用 allocate。

### B. MediaRepository

12. **运行时媒体 ID 规则**：新写入文件 ID = `media/<uuid>.<ext>`（UUID 随机、扩展名保真、小写）；落盘文件名与 ID 后缀一致。oracle（替代物）：Android 现行为 `img_<ts>_<hash>.jpg` / `image_<ts>.jpg`（ImageUtils.kt:17、MainActivity.kt:181）——iOS 以相对 ID 替代时间戳文件名，**平台差异白名单（P0）**：理由 paths.md §0 结论（file:// 绝对路径不可移植），行为差异仅文件名形式，目录与生命周期语义不变。
13. **来源枚举与落点**（对照 audit/paths.md §4 目录映射）：
    | 来源 | Android oracle | Android 落点 | iOS 落点（T9 契约 11 已建目录） |
    |---|---|---|---|
    | picker（相册/文件选择器 → 头像/背景） | ImageUtils.kt:13-28（copyToInternalStorage） | filesDir/images | media/images/ |
    | camera（相机）→ 聊天图 | MainActivity.kt:170-176（cacheDir/camera）→ :178-193（copyChatImageToFiles，1920 缩放 JPEG 80） | filesDir/chat_images | media/chat_images/ |
    | voice（录音/TTS） | LocalAudioController.kt:44（voice_<UUID>.pcm）、:135（tts_<UUID>.mp3） | filesDir/voice | media/voice/ |
    | remote（下载/AI 生成 http(s)/data:） | MainActivity.kt:197-198 透传 | 不落盘 | 不落盘，透传原引用 |
    | builtinAvatar（内置头像 1..147） | AvatarImage.kt:31-34 数字解析 drawable | res/drawable/avatar_N.png | app bundle 资源（见风险 1），不落 media 目录 |
    | backupImport（备份导入） | BackupMediaCollector.kt:24-30 + BackupMediaStager 解出（backup-restore-minimal-spec.md 契约 8） | filesDir/restored_media/<backupId>/media/images/ | Application Support/restored_media/<backupId>/media/images/（T10 mediaId 直映射，lead 已裁决 2026-11-22） |
14. **备份媒体 ID（原样保留 Android 语义）**：`mediaId = "local-image/<sha256(canonicalPath)[:24]>"`、`archivePath = "media/images/<stableId>.<ext>"`、`originalUri = file://…`。oracle：BackupMediaCollector.kt:24-30、:71-72。lead 已裁决「mediaId 路径派生语义原样保留」（backup-restore-minimal-spec.md 风险 3）——iOS 导出器复刻该公式，不得改为内容派生。
15. **备份收集白名单与跳过规则**：仅收集 `images`/`chat_images`/`restored_media` 三根下可读非空文件（:68-69、:59-66）；非 file scheme（http/https/data/空）进 skippedUris（:20-23,:61）。iOS 导出器等价：media ID 引用进包，远程引用跳过。
16. **备份引用扫描面（= iOS 运行时 ID 化的全部收口点）**：`user_avatar_uri`、operators/sessions 的 `avatarUri`、giftRecords 的 `imageUri`、messages.content 内嵌 `"imageUri"` JSON、settings 的 `support_conversation`。oracle：BackupMediaCollector.kt:43-57 + 正则 :86。iOS 同扫描面，但扫到的是 media ID（或远程 URL）而非 file://。
17. **引用完整性（resolve 契约）**：`resolve(mediaId)` → 本地文件 URL；文件缺失/ID 非法 → nil（调用方渲染占位）。`ingest` 失败（源不可读/复制后为空）→ 删除空文件并返回原引用（镜像 ImageUtils.kt:24-32「Never leave an empty owned image」）。oracle：ImageUtils.kt:24-27,30-31。
18. **清理策略（Android 现状即契约）**：
    - 会话/消息/角色删除**不删除**图片与语音文件（孤儿残留是 Android 现状：deleteSessionMessages :307-312、deleteMessage :318-324 均不动文件系统；OperatorEditScreen.kt:365 删的只是裁剪临时文件）。iOS 同语义，**平台差异白名单（P2 补强）**：iOS 可增孤儿文件 GC（引用扫描），默认关闭，随 A5。
    - 取消录音/未发送语音 → 删除 voice 文件（ChatScreen.kt:383、GroupDetailScreen.kt:122、SleepModeScreen.kt:182,210,260,354,368,427 调用 LocalAudioController.deleteAudio :140-142）。iOS 必须复刻（语音文件会快速膨胀）。
    - 备份恢复失败 → 删除 `restored_media/<backupId>`（BackupRestoreCoordinator.kt:108-111）+ T10 补强：任何失败路径（含 staging 失败）清理部分解出媒体（backup-restore-minimal-spec.md 风险 7「失败清理补强」已采纳）。
    - 备份恢复成功 → 旧 images/chat_images 文件**不清理**（Android 全量替换语义只清 DB，不动旧自有媒体目录）；恢复媒体保留在 restored_media/ 且重写后的引用指向它。
19. **绝对路径 → ID 迁移路径（按 audit/paths.md 逐类调用点）**：
    | 调用点（paths.md 条目） | Android oracle | iOS 改动 |
    |---|---|---|
    | `Operator.avatarUri` / `ChatSession.avatarUri`（DB，§1 Operator.kt:12、ChatSession.kt:17） | file:// 或数字 1..147 | 写入经 ingest 得 media ID；读取 resolve；数字字符串原样保留（内置头像）；空串原样 |
    | `user_avatar_uri`（settings，§1 SettingsRepository.kt:853-854） | file:// 或数字 | 同上 |
    | `bg_<op.id>` / `gbg_<groupId>` / `bg_uri`（settings 动态键，§6 ChatScreen.kt:327,755、GroupDetailScreen.kt:71、SettingsRepository.kt:124-125） | file:// | 值域改 media ID；键名不变；备份导出 `PortableSettings.kt:70,75` 的 `s:` 前缀保留（fields.md §a2 编码不变） |
    | 消息内嵌 `"imageUri"`（messages.content JSON，ChatViewModel.kt:1860 等） | file:// | 发送侧写 media ID 或远程 URL；渲染侧 resolve；备份扫描正则（BackupMediaCollector.kt:86）改为匹配 ID/URL |
    | 语音路径（§2 LocalAudioController.kt:129 读、:141 删、VoiceCallScreen.kt:179） | file://+removePrefix | 消息内容写 `media/<uuid>.pcm`，播放/删除经 resolve |
    | `GiftRecord.imageUri`（golden/db/schema/GiftRecords.sq:4；MainViewModel.kt:1478,1505） | file:// 或远程 | 同消息 imageUri |
    | `support_conversation`（settings JSON 内嵌 imageUri，BackupMediaCollector.kt:53-56） | file:// | 同上 |
    | 备份恢复重写器（§7 BackupRestorePayloadRewriter.kt:3-42,48-58） | 三层路径匹配 | **已裁决替换为 mediaId 直映射**（backup-restore-minimal-spec.md 契约 9、风险 2），本卡不重复 |
20. **内置头像语义**：avatarUri 为纯数字 `N∈1..147` → 渲染 bundle 资源 `avatar_N`；非数字非空 → 远程/本地（经 resolve）；空/越界 → 首字符色块回退。oracle：AvatarImage.kt:24-64（:31-34 数字解析、:46-52 AsyncImage、:53-64 回退）。iOS 渲染层逐分支复刻；`avatar:N` 前缀**不引入落库值域**（与 Android 一致存原始字符串），T9 契约 11 的 `avatar:x → images/avatar_x` 示例仅作为解析映射内部形态，正式值域以本卡为准（见风险 5）。

## 数据与迁移

- 涉及表/字段：`chat_messages.id`（主键，golden/db/schema/ChatMessages.sq:1-17；getMaxId :40-41）；`reply_turns.sourceMessageId/responseMessageId`（id 重用后果载体，golden/db/schema/ReplyTurns.sq）；`gift_records.imageUri`（GiftRecords.sq:4）；`operators.avatarUri`、`chat_sessions.avatarUri`；settings 键 `max_used_message_id`（新增写入，非表字段）、`user_avatar_uri`、`bg_*`/`gbg_*`/`bg_uri`。**无任何表结构变更，无新迁移版本号**（iOS 无存量 file:// 数据需要就地迁移——iOS 新装，唯一「存量路径」是 Android 备份导入，已由 T10 mediaId 直映射覆盖）。
- 备份/恢复中的表现：`max_used_message_id` **不进备份也不回填**（PortableSettings.kt 白名单无此键；fields.md §a3 动态键清单无此项），恢复后靠三源 max 自愈；媒体字段（avatarUri/imageUri/bg_*）随备份导出的值域 = media ID（fields.md §a2 各字段位置不变）；备份内 mediaId/archivePath 公式不变（契约 14）。
- 抽取落点（实现期，不在本卡写代码）：
  - `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/data/MessageIdAllocator.kt`：接口 `allocate()` / `observeUsed(id)` + 默认实现（依赖 `MessageMaxIdSource`：DB getMaxId query 与 `MaxIdStore`（settings 抽象））。
  - `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/media/`：`MediaId.kt`（值类+校验）、`MediaSource.kt`（枚举）、`MediaRepository.kt`（ingest/resolve/deleteAudio）、`MediaStore.kt`（平台文件落盘接口）。
  - iosMain actual：`shared/src/iosMain/kotlin/.../shared/domain/media/MediaStore.ios.kt`（复用 T9 `shared/files/MediaDirectory.ios.kt` 的 Application Support/media 路径与幂等创建；`MaxIdStoreIos` 落 settings JSON）。
  - `MessageRepository.getNextMessageId()` 改为委托 allocator（签名与语义不变），Android 侧 testDebugUnitTest 回归原语义。
- Android 调用点改动面（实现期同步改，保证 Android 端仍可编译+测试）：发送侧图片消息（ChatViewModel.kt:1858-1860 占位 JSON 写 imageUri）、头像/背景保存（ProfileSettingsScreen.kt:172、OperatorEditScreen.kt:593、GroupEditScreen.kt:287、GroupDetailScreen.kt:495、ChatScreen.kt:755）、语音写入与渲染（LocalAudioController.kt、VoiceCallScreen.kt:179）、渲染侧（AvatarImage.kt:46-52、ChatScreen.kt:2749）、备份侧（BackupMediaCollector.kt:44-58 收集、BackupContentFilter.kt:56,71-74 未选媒体时清空本地 bg/gbg）。全部经 MediaRepository 收口，禁止调用点直拼路径。

## 验收（必须可自动判定）

测试载体：commonTest（SQLDelight 内存 driver + 假 settings store + 临时目录 MediaStore），跑 `:shared:iosSimulatorArm64Test`（真目标）与 `:shared:testDebugUnitTest`（与 Android oracle 同 JVM 对照）。金样：`ios-port/golden/backup/minimal-1.14.27.zip`（T10）。

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | **金样路径转换矩阵**（下表 9 行逐行断言） | commonTest `MediaPathGoldenMatrixTest`：输入引用 → 期望 ID/落点/字节一致 | `./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*MediaPathGoldenMatrixTest*"` |
| 2 | 备份 mediaId 公式复刻（与 T10 验收 #6 同断言，Kotlin 版） | `sha256(canonicalPath)[:24]` 前缀 + archivePath 拼接断言 | 同 #1 用例内断言 |
| 3 | **并发分配测试**：多协程 1000 次 allocate 唯一且严格递增 | 1000 协程 launch → set 大小 1000、min=首值、max-min=999 | `--tests "*MessageIdAllocatorTest.concurrent1000*"`（iosSimulatorArm64Test + testDebugUnitTest 双跑） |
| 4 | 删除不回退：插到 id=N → 删最新行 → 新建分配器实例（persisted 保留）→ allocate = N+1 | 模拟进程重启（新实例、同 settings 键） | `--tests "*MessageIdAllocatorTest.noRewindAfterDelete*"` |
| 5 | **恢复后自检测试**：插入备份行 {1..5,100,999}（走 insertMessageIfAbsent 原样 id）→ fresh 分配器 allocate == 1000；同进程高水位 5000 + 恢复后 DB max=999 → allocate == 5001（不撞 id） | 两个场景各一断言 | `--tests "*MessageIdAllocatorTest.restoreSelfHeal*"` |
| 6 | observeUsed 抬高：observeUsed(42) 后连续 allocate 均 > 42 | 断言 | `--tests "*MessageIdAllocatorTest.observeUsed*"` |
| 7 | 恢复金样包 → 新消息不撞 ID | 复用 T10 恢复用例后调 allocate，断言 > 金样最大消息 id（金样 7 条消息） | `--tests "*BackupRestoreGoldenTestIos*"` 内追加断言（实现期） |
| 8 | **目录清理测试**：ingest 失败（空源/不可读）→ 无残留文件；deleteAudio → voice 文件消失；媒体目录幂等创建 | 断言目录树 | `--tests "*MediaRepositoryTest.cleanup*"` |
| 9 | 媒体 ID 校验拒绝：空/`..`/绝对路径/`file://`/`://`（T9 契约 11 复验） | 5 个非法输入各断言拒绝 | `--tests "*MediaRepositoryTest.rejectInvalidIds*"`（与 T9 MediaDirectoryTest 同断言集） |
| 10 | resolve 往返：ingest(picker 样例 PNG) → resolve(id) 字节 sha256 一致 | 断言 | `--tests "*MediaRepositoryTest.roundtrip*"` |

金样路径转换矩阵（验收 #1 用例表；「期望 ID」中 `<u>` 为运行时 UUID，断言形态而非值）：

| # | 输入引用（audit/paths.md 典型形态） | 来源 | 期望 ID / 结果 | 期望落点 |
|---|---|---|---|---|
| 1 | `file:///data/data/<pkg>/files/images/golden_1x1.png`（备份包内 originalUri） | backupImport | `local-image/<sha256(canonicalPath)[:24]>` | `restored_media/<backupId>/media/images/<sha24>.png` |
| 2 | 相册 `content://media/.../123`（头像裁剪确认） | picker | `media/<u>.jpg` | `media/images/<u>.jpg` |
| 3 | 相机 `cacheDir/camera/photo_<ts>.jpg` | camera | `media/<u>.jpg` | `media/chat_images/<u>.jpg`（1920 缩放 JPEG 后落盘） |
| 4 | 裁剪缓存 `cacheDir/crop_<ts>`（背景确认） | picker | `media/<u>.jpg` | `media/images/<u>.jpg` |
| 5 | 语音 `filesDir/voice/voice_<UUID>.pcm` | voice | `media/<u>.pcm` | `media/voice/<u>.pcm` |
| 6 | TTS `filesDir/voice/tts_<UUID>.mp3` | voice | `media/<u>.mp3` | `media/voice/<u>.mp3` |
| 7 | 内置头像 `"42"`（avatarUri 数字串） | builtinAvatar | 原样 `"42"`（不 ID 化） | bundle `avatar_42.png`（resolve 分支） |
| 8 | 远程 `https://cdn.example/x.png`（AI 生成图） | remote | 原样透传（不落盘、不进备份收集） | 无 |
| 9 | 非法：`"../evil.png"`、`"/abs/x.png"`、`"file:///x"` | — | 拒绝（T9 校验） | 无 |

- 金样 diff 允许差异白名单：本卡无 diff 型金样（断言型）；`<u>` UUID 与 `<sha24>` 为确定性外唯一变量。
- 性能：allocate 单次 <5ms（内存 driver 基准，模拟器主机侧）；1000 次并发分配总时长 <2s（Mutex 串行下线性）。真机数据 Phase 2 补。
- 边界用例清单：空库、删除最新、恢复冷开/热恢复、DB 查询抛错、settings null、1000 并发、连续重入、ingest 空源/超大源（>100MB 拒绝或流式复制，随 A5 定上限）、resolve 缺失文件、重复 ingest 同一源（两次得不同 ID，Android img_<ts> 同语义）。

## 风险与开放问题

1. **内置头像打包方案**【lead 已裁决 2026-11-23】：147 个 PNG（avatar_1..avatar_147，36KB~445KB，golden/media/avatars.md §映射规则）进 iOS **app bundle**，保持「编号字符串 → 资源」动态解析（镜像 AvatarImage.kt:31-34 运行时编号语义，无编译期资源表），不落 media 目录；asset catalog vs Bundle Resources 具体形态与无损压缩由实现代理在 T1/T15 阶段确认，不影响本卡契约。
2. **bg_* 背景存储形态**【lead 已裁决 2026-11-23】：**保持 settings 动态键** `bg_<opId>`/`gbg_<groupId>`/`bg_uri`（平移 Android，值域改 media ID，备份导出 `s:` 前缀不变，PortableSettings.kt:70,75 白名单已覆盖），不迁 DB。
3. **落点口径**【lead 已裁决 2026-11-23】：**Application Support/media（T9 口径胜出）**。task-16 任务描述中的「iOS Documents/media 目录」废弃（Documents 面向用户可见，App 自有媒体不适用；Application Support ≈ Android filesDir，ios-data-base-spec.md 契约 2）。
4. **孤儿媒体文件**：Android 删除会话/消息/角色不删图片语音文件（契约 18），iOS 同语义会持续累积。P2 补强项：引用扫描 GC（扫描 operators/sessions/settings/messages JSON 的媒体引用，删除无引用文件），默认关闭，随 A5。
5. **`avatar:N` 前缀与 T9 示例的差异**【lead 已裁决 2026-11-23】：本卡正式值域 = Android 原样（数字串/空/ID/URL，AvatarImage.kt:24-64），内置头像不落 media 目录、不引入 `avatar:` 前缀；T9 契约 11 示例 `avatar:x → images/avatar_x` 的文案由 lead 另行通知 T9 卡维护者修正。
6. **`getNextMessageId()` 调用点跨层**：分配器在 shared，调用方含 app 层 ViewModel（26 处，见契约）与 T15 引擎。实现期需保持 `ChatRepository.getNextMessageId()`（app/data/repository/ChatRepository.kt:50、shared ChatRepository.kt:330）公开签名不变，否则 Android 侧调用点连锁改动。iOS 新 UI 层（SwiftUI）经 T15 引擎取 id，不得自建计数器。
7. **分配与插入非原子**（Android 现状，:296-305 与 :167-187 是两个锁区）：崩溃可跳号。接受（ID 无稠密性要求），写入平台差异白名单「无差异」。

## 评审结论

- reviewer：**PASS-WITH-FIXES（2026-11-23）**——0 个 P1，1 个 P2，非阻塞；21 条契约 oracle 逐条复核通过。修正清单已全部落笔：
  1. [P2] 所有 gradle 任务名 `:shared:androidUnitTest` 更正为 `:shared:testDebugUnitTest`（规格卡验收表 + handoff 验收命令）。
  2. lead 裁决回填（见下）。
  3. T9 契约 11 的 `avatar:x → images/avatar_x` 示例文案由 lead 另行通知 T9 卡维护者修正，本卡不动。
- lead：**批准（2026-11-23）**——裁决 a) 落点 = **Application Support/media**（T9 口径胜出，Documents/media 废弃）；b) 内置头像 = **Bundle 资源按数字解析，不落 media 目录**（147 个 PNG 进 app bundle，保持 AvatarImage.kt:31-34 动态编号语义）；c) **bg_* 保持 settings 动态键**（值域改 media ID，`s:` 前缀不变，不迁 DB）。实现前提：本修正落笔。
