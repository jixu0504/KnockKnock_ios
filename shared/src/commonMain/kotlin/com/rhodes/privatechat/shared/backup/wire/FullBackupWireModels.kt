package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupCategory
import com.rhodes.privatechat.shared.backup.BackupIssue
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupMediaItemWire
import com.rhodes.privatechat.shared.data.BackupChatDisplayEvent
import com.rhodes.privatechat.shared.data.SharedExperienceParticipant
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Diary
import com.rhodes.privatechat.shared.model.DispatchRecord
import com.rhodes.privatechat.shared.model.GiftRecord
import com.rhodes.privatechat.shared.model.MahjongSave
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.model.MemoryAnchor
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.model.MomentLike
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.SharedExperience
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 备份 v2「全类目」完整 wire 模型（T31；oracle：app/.../data/ExportPayload.kt:22-43 + BackupModels.kt:87-98）。
 *
 * 与 T10/T30 `BackupWireModels.kt` 的关系：
 *  - T10 `BackupPayloadWire` 是「校验字段级」镜像（memories/moments/diaries/KB 等只声明引用键与少数字段），
 *    足以做引用完整性校验但不足以字段级重放；本文件是恢复侧**完整字段**模型。
 *  - Android 序列化直接复用 shared @Serializable 模型的类目（operators/sessions/messages/memories/anchors/
 *    moments/likes/comments/diaries/memoryItems/archives/history/gifts/dispatches/mahjong/experiences/participants/
 *    displayEvents），iOS 同样直接复用 shared 模型（零 schema 漂移）。
 *  - Android 用专用 Export 类的类目（relationships/knowledgeBases/knowledgeBaseChunks/assignments）在本文件
 *    新建完整 wire 类，字段序与 `ExportPayload.kt:41-43,71-84` 逐字一致。
 *  - 解码统一 `ignoreUnknownKeys=true`（与 T10 `BackupFileReader.kt:15` 同配置）。
 */
@Serializable
data class FullBackupContentWire(
    val version: Int = 1,
    val type: String,
    val timestamp: Long = 0L,
    val operators: List<Operator>? = null,
    val relationships: List<FullBackupRelationshipWire>? = null,
    val sessions: List<ChatSession>? = null,
    /** v1 遗留单会话字段（ExportPayload.kt:25）；恢复不消费，仅容忍。 */
    val session: JsonElement? = null,
    val messages: List<ChatMessage>? = null,
    val memories: List<Memory>? = null,
    val anchors: List<MemoryAnchor>? = null,
    val moments: List<Moment>? = null,
    val momentLikes: List<MomentLike>? = null,
    val momentComments: List<MomentComment>? = null,
    val diaries: List<Diary>? = null,
    val memoryItems: List<MemoryItem>? = null,
    val knowledgeBases: List<FullBackupKnowledgeBaseWire>? = null,
    val knowledgeBaseChunks: List<FullBackupKnowledgeBaseChunkWire>? = null,
    val operatorKnowledgeBaseAssignments: List<FullBackupKnowledgeBaseAssignmentWire>? = null,
    val settings: Map<String, String>? = null,
)

@Serializable
data class FullBackupPayloadWire(
    val content: FullBackupContentWire,
    val displayEvents: List<BackupChatDisplayEvent> = emptyList(),
    val chatArchives: List<ChatArchive> = emptyList(),
    val chatHistorySegments: List<ChatHistorySegment> = emptyList(),
    val giftRecords: List<GiftRecord> = emptyList(),
    val dispatchRecords: List<DispatchRecord> = emptyList(),
    val mahjongSave: MahjongSave? = null,
    val sharedExperiences: List<SharedExperience> = emptyList(),
    val sharedExperienceParticipants: List<SharedExperienceParticipant> = emptyList(),
    val media: List<BackupMediaItemWire> = emptyList(),
)

@Serializable
data class FullBackupPayloadPageWire(
    val category: BackupCategory,
    val pageIndex: Int,
    val payload: FullBackupPayloadWire,
)

/** 完整解码后的备份档案（manifest/issues 复用 T10 类型；payload 为完整字段）。 */
data class FullBackupArchiveWire(
    val manifest: BackupManifestWire,
    val payload: FullBackupPayloadWire,
    val issues: List<BackupIssue> = emptyList(),
)

/** 角色关系（oracle：`ExportPayload.kt:71-84`；type 为枚举名字符串，恢复时容错降级）。 */
@Serializable
data class FullBackupRelationshipWire(
    val operatorId: String,
    val relatedOperatorId: String,
    val relatedOperatorName: String = "",
    val type: String = "",
    val intimacy: Int = 0,
    val isPreset: Boolean = false,
    val note: String = "",
)

/** 知识库（oracle：`ExportPayload.kt:41`；不含派生索引字段 indexStatus/indexedEmbeddingSignature）。 */
@Serializable
data class FullBackupKnowledgeBaseWire(
    val id: String,
    val name: String,
    val rawContent: String = "",
    val sourceFileName: String = "",
    val sourceFormat: String = "",
    val sourceType: String = "user",
    val chunkingMode: String = "smart",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 知识库切块（oracle：`ExportPayload.kt:42`；不含派生索引字段 indexedAt/indexError）。 */
@Serializable
data class FullBackupKnowledgeBaseChunkWire(
    val id: String,
    val knowledgeBaseId: String,
    val ordinal: Int = 0,
    val sourceHeading: String = "",
    val content: String = "",
    val userKeywords: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 角色-知识库关联（oracle：`ExportPayload.kt:43`）。 */
@Serializable
data class FullBackupKnowledgeBaseAssignmentWire(
    val operatorId: String,
    val knowledgeBaseId: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)
