package com.rhodes.privatechat.shared.backup.operatorcard

import com.rhodes.privatechat.shared.backup.BackupMediaItemWire
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.RelationshipExport
import kotlinx.serialization.Serializable

/**
 * rhodes-operator v1 角色卡 wire 模型（iOS 复用；规格卡契约 C23-C27）。
 *
 * 格式 oracle：`app/.../data/backup/OperatorPackage.kt:18-33`（`OperatorPackagePayload`）与
 * `:37-66`（Writer 容器）。`operator` 复用 `shared/model/Operator`（22 字段，与 Android
 * `app/data/ExportPayload.kt:46-59` 的 `OperatorExport` 同形）；`relationships` 复用
 * `shared/model/RelationshipExport`；`avatar` 复用备份媒体项；`promptSlots` 镜像 `OperatorPromptSlots`。
 *
 * 角色卡不含聊天/记忆/日记/动态/密钥（`OperatorPackage.kt:57` excludedCategories 同口径）。
 */
@Serializable
data class OperatorCardPayloadWire(
    val operator: Operator,
    val relationships: List<RelationshipExport> = emptyList(),
    val avatar: BackupMediaItemWire? = null,
    val promptSlots: OperatorPromptSlotsWire? = null,
)

@Serializable
data class OperatorPromptSlotsWire(
    val privateSlots: List<String> = emptyList(),
    val groupSlots: List<String> = emptyList(),
    val activePrivateSlot: Int = 1,
    val activeGroupSlot: Int = 1,
)

/** 解析后的角色卡：payload + 头像字节（可选）。 */
data class OperatorCard(
    val payload: OperatorCardPayloadWire,
    val avatarBytes: ByteArray? = null,
)

/** 旧版单角色 JSON（`ExportHelper.importFromString` 兼容目标；镜像 OperatorPackage.kt:103-119）。 */
@Serializable
internal data class LegacyOperatorJson(
    val type: String = "",
    val operators: List<Operator> = emptyList(),
    val relationships: List<RelationshipExport> = emptyList(),
)
