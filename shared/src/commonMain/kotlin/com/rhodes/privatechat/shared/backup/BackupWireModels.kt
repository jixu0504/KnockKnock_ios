package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 备份 wire 格式镜像（Android oracle：app/data/ExportPayload.kt + app/data/backup/BackupModels.kt）。
 *
 * 约定：
 *  - 字段名与 Android 1.14.27 序列化产物逐字节同名；decode 用 ignoreUnknownKeys=true，
 *    因此本镜像只声明最小闭环（读取+校验+roles/chats 恢复）需要的字段，未知字段一律容忍；
 *  - operators/sessions/messages 直接解码为 shared 模型（与 wire 同形，恢复执行器免转换）；
 *  - 其余类别声明为「校验字段级」镜像（引用完整性检查只需要 id/引用键，恢复不消费）。
 */

const val BACKUP_FULL_FORMAT = "rhodes-backup"
const val BACKUP_FORMAT_VERSION = 2
const val BACKUP_LEGACY_FORMAT_VERSION = 1
const val BACKUP_MANIFEST_PATH = "manifest.json"
const val BACKUP_PAYLOAD_PATH = "data/backup.json"
const val BACKUP_PAGES_ROOT = "data/pages"
const val BACKUP_MAX_PAGE_BYTES = 16L * 1024 * 1024

@Serializable
data class BackupManifestWire(
    val format: String,
    val formatVersion: Int,
    val scope: String,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val backupId: String,
    val mediaIncluded: Boolean,
    val recordCounts: Map<String, Int>,
    val files: List<BackupFileEntryWire>,
    val excludedCategories: List<String>,
    val layout: String = "single_payload",
    val pages: List<BackupPageEntryWire> = emptyList(),
)

@Serializable
data class BackupFileEntryWire(val path: String, val size: Long, val sha256: String)

@Serializable
data class BackupPageEntryWire(val category: BackupCategory, val pageIndex: Int, val path: String, val recordCount: Int)

@Serializable
data class BackupPayloadPageWire(val category: BackupCategory, val pageIndex: Int, val payload: BackupPayloadWire)

@Serializable
data class BackupPayloadWire(
    val content: BackupContentWire,
    val displayEvents: List<BackupDisplayEventWire> = emptyList(),
    val chatArchives: List<BackupChatArchiveWire> = emptyList(),
    val chatHistorySegments: List<BackupHistorySegmentWire> = emptyList(),
    val giftRecords: List<BackupGiftWire> = emptyList(),
    val dispatchRecords: List<BackupDispatchWire> = emptyList(),
    val mahjongSave: BackupMahjongWire? = null,
    val sharedExperiences: List<BackupExperienceWire> = emptyList(),
    val sharedExperienceParticipants: List<BackupParticipantWire> = emptyList(),
    val media: List<BackupMediaItemWire> = emptyList(),
)

@Serializable
data class BackupContentWire(
    val version: Int = 1,
    val type: String,
    val timestamp: Long = 0L,
    val operators: List<Operator>? = null,
    val relationships: List<BackupRelationshipWire>? = null,
    val sessions: List<ChatSession>? = null,
    val session: JsonElement? = null,
    val messages: List<ChatMessage>? = null,
    val memories: List<BackupMemoryWire>? = null,
    val anchors: List<BackupAnchorWire>? = null,
    val moments: List<BackupMomentWire>? = null,
    val momentLikes: List<BackupMomentLikeWire>? = null,
    val momentComments: List<BackupMomentCommentWire>? = null,
    val diaries: List<BackupDiaryWire>? = null,
    val memoryItems: List<BackupMemoryItemWire>? = null,
    val knowledgeBases: List<BackupKnowledgeBaseWire>? = null,
    val knowledgeBaseChunks: List<BackupKnowledgeBaseChunkWire>? = null,
    val operatorKnowledgeBaseAssignments: List<BackupKnowledgeBaseAssignmentWire>? = null,
    val settings: Map<String, String>? = null,
)

@Serializable
enum class BackupCategory { ROLES, CHATS, MEMORIES, SOCIAL, KNOWLEDGE_BASES, EXTRAS, SETTINGS }

@Serializable
data class BackupMediaItemWire(
    val mediaId: String,
    val archivePath: String,
    val mimeType: String = "application/octet-stream",
    val originalUri: String = "",
)

@Serializable data class BackupRelationshipWire(
    val operatorId: String,
    val relatedOperatorId: String,
    val relatedOperatorName: String = "",
    val type: String = "",
    val intimacy: Int = 0,
    val isPreset: Boolean = false,
    val note: String = "",
)

@Serializable data class BackupMemoryWire(
    val id: Long = 0,
    val sessionId: String = "",
    val operatorId: String = "",
    val type: String = "",
    val content: String = "",
    val keywords: String = "",
    val preferences: String = "",
    val taboos: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = Long.MAX_VALUE,
)

@Serializable data class BackupAnchorWire(val sessionId: String = "", val operatorId: String = "")

@Serializable data class BackupMomentWire(
    val id: Long = 0,
    val operatorId: String = "",
    val operatorName: String = "",
    val content: String = "",
    val isUserPost: Boolean = false,
    val mentionedOperatorIds: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: Long = 0L,
)

@Serializable data class BackupMomentCommentWire(val id: Long = 0, val momentId: Long = 0, val parentCommentId: Long = 0)

@Serializable data class BackupMomentLikeWire(val momentId: Long = 0)

@Serializable class BackupDiaryWire

@Serializable class BackupMemoryItemWire

@Serializable data class BackupKnowledgeBaseWire(val id: String = "")

@Serializable data class BackupKnowledgeBaseChunkWire(val id: String = "", val knowledgeBaseId: String = "", val ordinal: Int = 0)

@Serializable data class BackupKnowledgeBaseAssignmentWire(val operatorId: String = "", val knowledgeBaseId: String = "")

@Serializable data class BackupDisplayEventWire(
    val messageId: Long,
    val segmentIndex: Int,
    val sessionId: String = "",
    val revealOrder: Long = 0,
)

@Serializable data class BackupChatArchiveWire(
    val id: String = "",
    val sessionId: String = "",
    val operatorId: String = "",
    val messagesJson: String = "",
    val stateJson: String = "",
)

@Serializable data class BackupHistorySegmentWire(
    val id: String = "",
    val sessionId: String = "",
    val messagesJson: String = "",
)

@Serializable data class BackupGiftWire(val id: Long = 0, val operatorId: String = "", val imageUri: String = "")

@Serializable class BackupDispatchWire

@Serializable class BackupMahjongWire

@Serializable data class BackupExperienceWire(val id: Long = 0)

@Serializable data class BackupParticipantWire(val experienceId: Long = 0, val operatorId: String = "")

data class BackupContentSelection(
    val roles: Boolean = true,
    val chats: Boolean = true,
    val memories: Boolean = true,
    val social: Boolean = true,
    val knowledgeBases: Boolean = true,
    val extras: Boolean = true,
    val settings: Boolean = true,
    val media: Boolean = true,
) {
    fun normalized(): BackupContentSelection = copy(
        roles = roles || chats || memories || social || knowledgeBases || extras,
        chats = chats || memories,
    )

    companion object { val All = BackupContentSelection() }
}

data class BackupRestoreOptions(
    val skipIssueCodes: Set<String> = emptySet(),
    val contentSelection: BackupContentSelection = BackupContentSelection.All,
)

data class BackupIssue(
    val code: String,
    val title: String,
    val detail: String,
    val count: Int,
    val skippable: Boolean = true,
)

sealed interface BackupValidationResult {
    data class Valid(val manifest: BackupManifestWire, val issues: List<BackupIssue> = emptyList()) : BackupValidationResult
    data class Invalid(val reason: String) : BackupValidationResult
}

data class BackupArchiveWire(
    val manifest: BackupManifestWire,
    val payload: BackupPayloadWire,
    val issues: List<BackupIssue> = emptyList(),
)

class BackupFormatException(message: String) : IllegalArgumentException(message)
