package com.rhodes.privatechat.data.backup

import com.rhodes.privatechat.data.MessageExport
import com.rhodes.privatechat.data.OperatorExport
import com.rhodes.privatechat.data.SessionExport
import com.rhodes.privatechat.shared.data.BackupChatDisplayEvent
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import kotlinx.serialization.Serializable

/**
 * RhodesLinkChatExport v1 wire 模型（T30；规格卡 `ios-port/specs/chat-import-impl-spec.md` §A3）。
 *
 * 容器：manifest.json + conversation.json + media/images/<sha256(content)[:24]>.<ext>
 *  - manifest 复用 [BackupManifest]（BackupModels.kt:36-51），仅 format/formatVersion/scope 取值不同；
 *  - 本文件是 conversation.json 载荷：operator/session/messages 直接投影 app/data/ExportPayload.kt:46-113
 *    （OperatorExport 22 字段 / SessionExport 12 字段 / MessageExport 15 字段），避免 schema 漂移；
 *  - displayEvents / chatArchives / chatHistorySegments 复用 shared 模型（@Serializable，备份页同形）。
 *
 * iOS 侧对应：`shared/.../backup/chatimport/ChatExportWireModels.kt`（字段逐一同名，ignoreUnknownKeys 前向兼容）。
 */
@Serializable
data class ChatExportConversation(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    /** 单会话迁移自包含角色人设（OperatorExport 22 字段，与 shared/model/Operator.kt 同形）。 */
    val operator: OperatorExport? = null,
    val session: SessionExport,
    val messages: List<MessageExport>,
    /** 每个 ai_json segment 一条揭示事件（MessageRepository.kt:34-40 形状）。 */
    val displayEvents: List<BackupChatDisplayEvent> = emptyList(),
    /** 存档（messagesJson 内消息 id 在 iOS 导入时按 old→new 重映射）。 */
    val chatArchives: List<ChatArchive> = emptyList(),
    val chatHistorySegments: List<ChatHistorySegment> = emptyList(),
    val mediaIndex: List<ChatExportMediaItem> = emptyList(),
)

/**
 * 会话媒体清单项：mediaId 内容派生（T6 裁决 `image/<sha256(content)>`），archivePath 沿用
 * BackupMediaCollector.kt:24-30 的 24 hex 约定；size/sha256 与 manifest.files + 实际字节三方互校。
 */
@Serializable
data class ChatExportMediaItem(
    val mediaId: String,
    val archivePath: String,
    val mimeType: String = "application/octet-stream",
    val originalUri: String = "",
    val size: Long,
    val sha256: String,
)

/** 导出结果（UI 提示 + 测试对账用）。 */
data class ChatExportResult(
    val manifest: BackupManifest,
    val messageCount: Int,
    val mediaCount: Int,
    val skippedUris: List<String> = emptyList(),
)
