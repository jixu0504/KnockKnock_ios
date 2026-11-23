@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalObjCName::class)

package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.chatimport.ChatExportReader
import com.rhodes.privatechat.shared.backup.chatimport.ChatExportValidationResult
import com.rhodes.privatechat.shared.backup.chatimport.ChatExportWriter
import com.rhodes.privatechat.shared.backup.chatimport.ChatImportResult
import com.rhodes.privatechat.shared.backup.chatimport.ChatImportStage
import com.rhodes.privatechat.shared.backup.chatimport.ChatMergeRestorer
import com.rhodes.privatechat.shared.backup.chatimport.ChatRepositoryChatImportAdapter
import com.rhodes.privatechat.shared.backup.chatimport.MediaStoreChatImportMediaSink
import com.rhodes.privatechat.shared.backup.operatorcard.ChatRepositoryOperatorCardAdapter
import com.rhodes.privatechat.shared.backup.operatorcard.MediaStoreOperatorCardMediaSink
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardImportMode
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardImporter
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardReader
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardWriter
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseLocations
import com.rhodes.privatechat.shared.domain.media.MediaRepository
import com.rhodes.privatechat.shared.files.MediaStoreIos
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import kotlin.time.Clock

/**
 * 数据与备份桥（T38「可用软件」里程碑 2）：把 A5 已就绪的域层能力接到 iOS 设置 UI。
 *
 * 覆盖范围（完整备份/两阶段恢复复用 [BackupBridge]，本桥补四个文件型能力）：
 * 1. **角色卡导出**（rhodes-operator v1）：`OperatorCardExporter` + `OperatorCardWriter`，
 *    内置头像数字串（1..147）从 App Bundle `avatar_<N>.png` 读字节，运行时媒体 ID 经 [MediaRepository] 解析；
 * 2. **角色卡导入**：`OperatorCardReader.readCompatible`（兼容旧版单角色 JSON）+ `OperatorCardImporter`
 *    （NEW / PERSONA_AND_APPEARANCE / FULL_REPLACE 三模式，UI 默认 NEW）；头像落运行时媒体库；
 * 3. **聊天记录导出**（rhodes-chat v1）：`ChatExportWriter`（T38 新增 commonMain 纯函数 writer），
 *    单会话 + displayEvents + 存档 + 历史片段 + 内容派生媒体；
 * 4. **聊天记录导入**：`ChatMergeRestorer`（合并语义：会话匹配 / 内容哈希去重 / 消息 ID 重分配 / 单事务回滚），
 *    返回导入条数/去重条数/媒体数/存档数 + 告警。
 *
 * 纪律（同 `ios-port/specs/ios-bridge-spec.md`）：
 * - 只导出简单类型 DTO（普通 class，无 data class / sealed / Flow）；
 * - 回调一律 Main 队列；错误经第二参数回传（error != null 为失败，文案为面向用户的中文原因）；
 * - 文件读写走 [IosFileSupport]（与 [BackupBridge] 同一套 Foundation 工具）；
 * - Kotlin/Native 侧捕获所有 Throwable（含 `BackupFormatException`），**不让坏包导致崩溃**。
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@ObjCName(swiftName = "DataBackupBridge")
class DataBackupBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    private val mediaStore: MediaStoreIos = MediaStoreIos()
    private val mediaRepository: MediaRepository = MediaRepository(mediaStore)
    private val chatExportReader = ChatExportReader()

    // ------------------------------------------------------------------ 角色卡导出

    /**
     * 导出单个角色为 rhodes-operator v1 角色卡 zip。
     * `onDone(info, error)`：成功 info 非空（含字节数/SHA-256/关系数/是否含头像）。
     */
    fun exportOperatorCard(
        operatorId: String,
        destinationPath: String,
        onDone: (OperatorCardExportInfoDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val exporter = com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardExporter(
                    ChatRepositoryOperatorCardAdapter(chatRepo),
                    settingsRepo,
                )
                val export = exporter.export(operatorId) { uri -> resolveMediaBytes(uri) }
                val writer = OperatorCardWriter(
                    appVersion = APP_VERSION,
                    now = { Clock.System.now().toEpochMilliseconds() },
                )
                val written = writer.write(export.payload, export.avatarBytes)
                IosFileSupport.ensureParentDirectory(destinationPath)
                if (!IosFileSupport.writeBytes(destinationPath, written.bytes)) {
                    throw BackupFormatException("角色卡写入失败（磁盘空间不足或没有写入权限）")
                }
                val info = OperatorCardExportInfoDto(
                    destinationPath = destinationPath,
                    sizeBytes = written.bytes.size.toLong(),
                    sha256 = BackupCrypto.sha256Hex(written.bytes),
                    operatorId = export.payload.operator.id,
                    operatorName = export.payload.operator.name,
                    relationshipCount = export.payload.relationships.size,
                    hasAvatar = export.avatarBytes != null,
                    format = written.manifest.format,
                    formatVersion = written.manifest.formatVersion,
                )
                mainScope.launch { onDone(info, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t, "角色卡导出失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 角色卡检视 / 导入

    /** 选择文件后先校验并预览（不写库）；`onDone(preview, error)`。 */
    fun inspectOperatorCard(sourcePath: String, onDone: (OperatorCardPreviewDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val bytes = readFileOrThrow(sourcePath, "角色卡文件不存在或不可读")
                val card = OperatorCardReader().readCompatible(bytes)
                val preview = OperatorCardPreviewDto(
                    operatorId = card.payload.operator.id,
                    operatorName = card.payload.operator.name,
                    title = card.payload.operator.title,
                    relationshipCount = card.payload.relationships.size,
                    hasAvatar = card.avatarBytes != null,
                    promptSlotCount = card.payload.promptSlots?.privateSlots?.size ?: 0,
                    formatVersion = 1,
                    summary = "rhodes-operator v1 · 角色「${card.payload.operator.name}」· 关系 ${card.payload.relationships.size} 条",
                )
                mainScope.launch { onDone(preview, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t, "角色包无效")) }
            }
        }
    }

    /**
     * 导入角色卡。
     * @param mode "NEW"（默认，新建角色）/ "PERSONA_AND_APPEARANCE" / "FULL_REPLACE"
     * @param targetOperatorId 后两种模式必填（要更新的现有角色 id）
     */
    fun importOperatorCard(
        sourcePath: String,
        mode: String,
        targetOperatorId: String,
        onDone: (OperatorCardImportInfoDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val bytes = readFileOrThrow(sourcePath, "角色卡文件不存在或不可读")
                val card = OperatorCardReader().readCompatible(bytes)
                val parsedMode = runCatching { OperatorCardImportMode.valueOf(mode) }
                    .getOrDefault(OperatorCardImportMode.NEW)
                val importer = OperatorCardImporter(
                    repository = ChatRepositoryOperatorCardAdapter(chatRepo),
                    settings = settingsRepo,
                    mediaSink = MediaStoreOperatorCardMediaSink(mediaStore),
                )
                val result = importer.import(
                    card = card,
                    mode = parsedMode,
                    targetOperatorId = targetOperatorId.ifBlank { null },
                )
                val info = OperatorCardImportInfoDto(
                    operatorId = result.operatorId,
                    operatorName = card.payload.operator.name,
                    createdNew = result.createdNew,
                    importedRelationships = result.importedRelationships,
                    skippedRelationships = result.skippedRelationships,
                    mode = parsedMode.name,
                )
                mainScope.launch { onDone(info, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t, "角色卡导入失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 聊天记录导出

    /**
     * 导出单会话聊天记录（RhodesLinkChatExport v1 zip）。
     * 无消息 / 找不到会话 → error 为明确中文原因。
     */
    fun exportChat(
        sessionId: String,
        destinationPath: String,
        onDone: (ChatExportInfoDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val session = chatRepo.getSession(sessionId)
                    ?: throw BackupFormatException("找不到要导出的会话")
                val operator = chatRepo.getOperator(session.operatorId)
                val messages = chatRepo.getMessagesSync(session.id)
                if (messages.isEmpty()) throw BackupFormatException("该会话没有消息，无法导出聊天记录")
                val displayEvents = chatRepo.messages.getAllDisplayEvents()
                    .filter { it.sessionId == session.id }
                val archives = chatRepo.archives.getArchives(session.id)
                val historySegments = chatRepo.archives.getHistorySegments(session.id)
                val writer = ChatExportWriter(appVersion = APP_VERSION)
                val written = writer.write(
                    operator = operator,
                    session = session,
                    messages = messages,
                    displayEvents = displayEvents,
                    chatArchives = archives,
                    chatHistorySegments = historySegments,
                    resolveMedia = { ref -> resolveMediaBytes(ref) },
                )
                IosFileSupport.ensureParentDirectory(destinationPath)
                if (!IosFileSupport.writeBytes(destinationPath, written.bytes)) {
                    throw BackupFormatException("聊天记录写入失败（磁盘空间不足或没有写入权限）")
                }
                val info = ChatExportInfoDto(
                    destinationPath = destinationPath,
                    sizeBytes = written.bytes.size.toLong(),
                    sha256 = BackupCrypto.sha256Hex(written.bytes),
                    sessionId = session.id,
                    sessionTitle = session.operatorName.ifBlank { session.id },
                    messageCount = written.messageCount,
                    mediaCount = written.mediaCount,
                    archiveCount = written.archiveCount,
                    historySegmentCount = written.historySegmentCount,
                    format = written.manifest.format,
                    formatVersion = written.manifest.formatVersion,
                )
                mainScope.launch { onDone(info, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t, "聊天记录导出失败")) }
            }
        }
    }

    // ------------------------------------------------------------------ 聊天记录检视 / 导入

    /** 选择文件后先校验并预览（不写库）：会话/消息数/媒体数/告警。 */
    fun inspectChatExport(sourcePath: String, onDone: (ChatExportPreviewDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val bytes = readFileOrThrow(sourcePath, "聊天记录文件不存在或不可读")
                when (val result = chatExportReader.validate(bytes)) {
                    is ChatExportValidationResult.Valid -> {
                        val session = result.conversation.session
                        val preview = ChatExportPreviewDto(
                            sessionId = session?.id ?: "",
                            sessionTitle = session?.operatorName?.ifBlank { session.id } ?: "",
                            operatorName = result.conversation.operator?.name ?: "",
                            messageCount = result.messageCount,
                            mediaCount = result.mediaCount,
                            archiveCount = result.conversation.chatArchives.size,
                            historySegmentCount = result.conversation.chatHistorySegments.size,
                            alerts = result.alerts,
                            formatVersion = result.manifest.formatVersion,
                        )
                        mainScope.launch { onDone(preview, null) }
                    }
                    is ChatExportValidationResult.Invalid -> {
                        mainScope.launch { onDone(null, "聊天包无效：${result.reason}") }
                    }
                }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t, "聊天记录校验失败")) }
            }
        }
    }

    /**
     * 导入并按「合并」语义写库（会话匹配 / 幂等去重 / 消息 ID 重分配 / 单事务回滚）。
     * 进度经 [onProgress] 回调（VALIDATING → STAGING_MEDIA → RESTORING_DATABASE → COMPLETED/FAILED）。
     */
    fun importChat(
        sourcePath: String,
        onProgress: (ChatImportProgressDto) -> Unit,
        onDone: (ChatImportResultInfoDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val bytes = readFileOrThrow(sourcePath, "聊天记录文件不存在或不可读")
                val restorer = ChatMergeRestorer(
                    repository = ChatRepositoryChatImportAdapter(chatRepo),
                    mediaSink = MediaStoreChatImportMediaSink(mediaStore),
                    settings = settingsRepo,
                    reader = chatExportReader,
                )
                val result = restorer.import(bytes) { progress ->
                    val dto = ChatImportProgressDto(
                        stage = progress.stage.name,
                        detail = progress.detail,
                        completed = progress.completed ?: -1,
                        total = progress.total ?: -1,
                    )
                    mainScope.launch { runCatching { onProgress(dto) } }
                }
                mainScope.launch { onDone(result.toDto(), null) }
            } catch (t: Throwable) {
                val stage = (t as? BackupFormatException)?.let { ChatImportStage.FAILED } ?: ChatImportStage.FAILED
                mainScope.launch {
                    onDone(
                        ChatImportResultInfoDto(
                            success = false,
                            stage = stage.name,
                            sessionId = "",
                            importedMessages = 0,
                            dedupedMessages = 0,
                            importedMedia = 0,
                            importedArchives = 0,
                            importedHistorySegments = 0,
                            reason = userMessage(t, "聊天记录导入失败"),
                            alerts = emptyList(),
                        ),
                        null,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 两阶段恢复（撤销）

    /**
     * 撤销已暂存、尚未换入的两阶段恢复：删除 `rhodes.db.restore-pending`。
     * 返回 true = 已删除（或本就不存在）；当前运行中的数据库从未被修改。
     * （暂存/冷启动换入仍由 [BackupBridge] 负责；这里只补 UI 需要的“撤销”能力。）
     */
    fun cancelPendingRestore(): Boolean {
        val dbPath = DatabaseLocations.defaultDatabasePath()
        return runCatching {
            IosFileSupport.deleteIfExists("$dbPath.restore-pending") || true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 内部工具

    private fun readFileOrThrow(path: String, missingMessage: String): ByteArray =
        IosFileSupport.readBytes(path)?.takeIf { it.isNotEmpty() }
            ?: throw BackupFormatException(missingMessage)

    /** 面向用户的错误文案：`BackupFormatException` 直接带出中文原因，其余兜底前缀。 */
    private fun userMessage(t: Throwable, prefix: String): String {
        val raw = t.message ?: ""
        return when {
            t is BackupFormatException && raw.isNotBlank() -> "$prefix：$raw"
            raw.isBlank() -> prefix
            else -> "$prefix：$raw"
        }
    }

    /**
     * 媒体引用 → 本地字节（导出用）：内置头像数字串走 Bundle `avatar_<N>.png`；
     * 运行时媒体 ID 走 [MediaRepository]；`file://`/绝对路径直接读；远程/内置缺失返回 null。
     */
    private fun resolveMediaBytes(ref: String): ByteArray? {
        if (ref.isBlank()) return null
        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:")) return null
        ref.toIntOrNull()?.let { number ->
            if (number in 1..147) return bundledAvatarBytes(number)
            return null
        }
        if (ref.startsWith("file://") || ref.startsWith("/")) {
            return IosFileSupport.readBytes(ref.removePrefix("file://"))
        }
        mediaRepository.resolve(ref)?.let { url ->
            IosFileSupport.readBytes(url.removePrefix("file://"))?.let { return it }
        }
        return mediaStore.readBytes("images/$ref") ?: mediaStore.readBytes("chat_images/$ref")
    }

    private fun bundledAvatarBytes(number: Int): ByteArray? {
        val path = NSBundle.mainBundle.pathForResource("avatar_$number", ofType = "png") ?: return null
        return IosFileSupport.readBytes(path)
    }

    private fun ChatImportResult.toDto(): ChatImportResultInfoDto = ChatImportResultInfoDto(
        success = success,
        stage = stage.name,
        sessionId = sessionId,
        importedMessages = importedMessages,
        dedupedMessages = dedupedMessages,
        importedMedia = importedMedia,
        importedArchives = importedArchives,
        importedHistorySegments = importedHistorySegments,
        reason = reason,
        alerts = alerts,
    )

    private companion object {
        const val APP_VERSION: String = "RhodesLink-iOS"
    }
}

// ---------------------------------------------------------------------------
// DTO（普通 class；字段只读，Swift 侧不接触 Kotlin 类型）
// ---------------------------------------------------------------------------

class OperatorCardExportInfoDto(
    val destinationPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val operatorId: String,
    val operatorName: String,
    val relationshipCount: Int,
    val hasAvatar: Boolean,
    val format: String,
    val formatVersion: Int,
)

class OperatorCardPreviewDto(
    val operatorId: String,
    val operatorName: String,
    val title: String,
    val relationshipCount: Int,
    val hasAvatar: Boolean,
    val promptSlotCount: Int,
    val formatVersion: Int,
    val summary: String,
)

class OperatorCardImportInfoDto(
    val operatorId: String,
    val operatorName: String,
    val createdNew: Boolean,
    val importedRelationships: Int,
    val skippedRelationships: Int,
    val mode: String,
)

class ChatExportInfoDto(
    val destinationPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val sessionId: String,
    val sessionTitle: String,
    val messageCount: Int,
    val mediaCount: Int,
    val archiveCount: Int,
    val historySegmentCount: Int,
    val format: String,
    val formatVersion: Int,
)

class ChatExportPreviewDto(
    val sessionId: String,
    val sessionTitle: String,
    val operatorName: String,
    val messageCount: Int,
    val mediaCount: Int,
    val archiveCount: Int,
    val historySegmentCount: Int,
    val alerts: List<String>,
    val formatVersion: Int,
)

class ChatImportProgressDto(
    /** VALIDATING / STAGING_MEDIA / RESTORING_DATABASE / COMPLETED / FAILED */
    val stage: String,
    val detail: String,
    /** -1 = 未知 */
    val completed: Int,
    /** -1 = 未知 */
    val total: Int,
)

class ChatImportResultInfoDto(
    val success: Boolean,
    val stage: String,
    val sessionId: String,
    val importedMessages: Int,
    val dedupedMessages: Int,
    val importedMedia: Int,
    val importedArchives: Int,
    val importedHistorySegments: Int,
    val reason: String,
    val alerts: List<String>,
)
