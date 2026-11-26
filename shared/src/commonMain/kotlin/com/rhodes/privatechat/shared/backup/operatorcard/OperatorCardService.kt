package com.rhodes.privatechat.shared.backup.operatorcard

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupMediaItemWire
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.media.MediaStore
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.Relationship
import com.rhodes.privatechat.shared.model.RelationshipExport
import com.rhodes.privatechat.shared.model.RelationshipType
import com.rhodes.privatechat.shared.settings.SettingsRepository

/** 角色卡导入模式（镜像 `app/.../data/backup/OperatorPackageService.kt:19` 的 `OperatorImportMode`）。 */
enum class OperatorCardImportMode { NEW, PERSONA_AND_APPEARANCE, FULL_REPLACE }

data class OperatorCardImportResult(
    val operatorId: String,
    val createdNew: Boolean,
    val importedRelationships: Int,
    val skippedRelationships: Int,
)

data class OperatorCardExport(
    val payload: OperatorCardPayloadWire,
    val avatarBytes: ByteArray? = null,
)

/**
 * 角色卡导入/导出的仓储依赖面。
 *
 * 写入口为 [upsertOperatorAndRelationships]：一次调用内完成「角色 upsert + （可选）清空旧关系 + 关系插入」，
 * 保证单事务（生产 = `ChatRepository.runRestoreTransaction`；测试 = `RhodesDatabase.transaction`）。
 */
interface OperatorCardRepository {
    suspend fun getOperator(id: String): Operator?
    suspend fun getAllOperatorsSync(): List<Operator>
    suspend fun getRelationships(operatorId: String): List<Relationship>

    /**
     * @param deleteExistingRelationships `FULL_REPLACE` 时先按 operatorId 清空旧关系（镜像 `:120`）。
     */
    suspend fun upsertOperatorAndRelationships(
        operator: Operator,
        deleteExistingRelationships: Boolean,
        relationships: List<Relationship>,
    )
}

/** 生产适配器：复用 `ChatRepository` 现有子仓储 + 恢复事务入口（`ChatRepository.kt:55`）。 */
class ChatRepositoryOperatorCardAdapter(
    private val repository: ChatRepository,
) : OperatorCardRepository {
    override suspend fun getOperator(id: String): Operator? = repository.getOperator(id)
    override suspend fun getAllOperatorsSync(): List<Operator> = repository.getAllOperatorsSync()
    override suspend fun getRelationships(operatorId: String): List<Relationship> =
        repository.getRelationships(operatorId)

    override suspend fun upsertOperatorAndRelationships(
        operator: Operator,
        deleteExistingRelationships: Boolean,
        relationships: List<Relationship>,
    ) = repository.runRestoreTransaction {
        operatorsQueries.insertAllOperators(
            operator.id, operator.name, operator.title, operator.description, operator.gender,
            operator.avatarUri, operator.location, operator.activity, operator.emotion,
            operator.intimacy.toLong(), operator.privatePrompt, operator.groupPrompt,
            operator.memoryInjection, operator.userRelation, operator.lmb.toLong(),
            operator.attack.toDouble(), operator.defense.toDouble(), operator.meldPref,
            operator.activityLevel.toDouble(), operator.voiceName, operator.voiceSpeed,
            operator.voicePitch,
        )
        if (deleteExistingRelationships) relationshipsQueries.deleteByOperator(operator.id)
        relationships.forEach { relationship ->
            relationshipsQueries.insertRelationship(
                relationship.operatorId, relationship.relatedOperatorId,
                relationship.relatedOperatorName, relationship.type.name,
                relationship.intimacy.toLong(), if (relationship.isPreset) 1L else 0L,
                relationship.note,
            )
        }
    }
}

/** 角色卡头像落盘（iOS：写运行时媒体库；返回可渲染的运行时媒体 ID）。 */
interface OperatorCardMediaSink {
    fun writeAvatar(bytes: ByteArray, extension: String): String?
    fun cleanup()
}

/** 生产实现：经 T17 [MediaStore] 内容寻址写 `images/media/card-avatar-<sha24>.<ext>`（幂等）。 */
class MediaStoreOperatorCardMediaSink(
    private val store: MediaStore,
) : OperatorCardMediaSink {

    private val createdPaths = mutableListOf<String>()

    override fun writeAvatar(bytes: ByteArray, extension: String): String? {
        if (bytes.isEmpty()) return null
        val ext = extension.lowercase().takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "jpg"
        val mediaId = "media/card-avatar-${BackupCrypto.sha256Hex(bytes).take(24)}.$ext"
        val relativePath = "images/$mediaId"
        val existed = store.exists(relativePath)
        if (!store.writeBytes(relativePath, bytes)) return null
        if (!existed) createdPaths += relativePath
        return mediaId
    }

    override fun cleanup() {
        createdPaths.forEach { store.delete(it) }
        createdPaths.clear()
    }
}

/**
 * 角色卡导入执行器（规格卡契约 C26；逐条镜像 `app/.../data/backup/OperatorPackageService.kt:58-186`）：
 *  - `NEW`：新随机 id + 同名去重后缀「（导入）」；
 *  - `PERSONA_AND_APPEARANCE`：仅更新名称/外观/人设/语音/提示词（不动数值、关系、聊天）；
 *  - `FULL_REPLACE`：整条替换（含关系重映射，头像空值回退现有）。
 * prompt 槽位回填 `operator_prompt_slot_<id>_{private,group}_{1..3}`（`:146-176` 同款）；DB 写在单事务内。
 */
class OperatorCardImporter(
    private val repository: OperatorCardRepository,
    private val settings: SettingsRepository,
    private val mediaSink: OperatorCardMediaSink,
) {
    suspend fun import(
        card: OperatorCard,
        mode: OperatorCardImportMode,
        targetOperatorId: String? = null,
        relationshipMappings: Map<String, String> = emptyMap(),
    ): OperatorCardImportResult {
        val targetId: String = when (mode) {
            OperatorCardImportMode.PERSONA_AND_APPEARANCE, OperatorCardImportMode.FULL_REPLACE ->
                targetOperatorId?.takeIf { repository.getOperator(it) != null }
                    ?: throw BackupFormatException("请选择要更新的现有角色")
            OperatorCardImportMode.NEW -> OperatorCardWriter.randomUuid()
        }
        val existing = if (mode == OperatorCardImportMode.NEW) null else repository.getOperator(targetId)
        if (mode != OperatorCardImportMode.NEW && existing == null) {
            throw BackupFormatException("找不到要更新的现有角色")
        }
        val cardOperator = card.payload.operator.copy(
            id = targetId,
            name = if (mode == OperatorCardImportMode.NEW) uniqueImportedName(card.payload.operator.name) else card.payload.operator.name,
            avatarUri = restoreAvatar(card) ?: portableAvatarUri(card.payload.operator.avatarUri),
        )
        val imported = when (mode) {
            OperatorCardImportMode.PERSONA_AND_APPEARANCE -> existing!!.copy(
                name = cardOperator.name,
                title = cardOperator.title,
                description = cardOperator.description,
                gender = cardOperator.gender,
                avatarUri = cardOperator.avatarUri.ifBlank { existing.avatarUri },
                privatePrompt = cardOperator.privatePrompt,
                groupPrompt = cardOperator.groupPrompt,
                memoryInjection = cardOperator.memoryInjection,
                userRelation = cardOperator.userRelation,
                voiceName = cardOperator.voiceName,
                voiceSpeed = cardOperator.voiceSpeed,
                voicePitch = cardOperator.voicePitch,
            )
            OperatorCardImportMode.FULL_REPLACE -> cardOperator.copy(
                avatarUri = cardOperator.avatarUri.ifBlank { existing!!.avatarUri },
            )
            OperatorCardImportMode.NEW -> cardOperator
        }
        val activePrivatePrompt = card.payload.promptSlots?.let { slots ->
            slots.privateSlots.getOrNull(slots.activePrivateSlot.coerceIn(1, 3) - 1)
        }
        val activeGroupPrompt = card.payload.promptSlots?.let { slots ->
            slots.groupSlots.getOrNull(slots.activeGroupSlot.coerceIn(1, 3) - 1)
        }
        val runtimeAligned = imported.copy(
            privatePrompt = activePrivatePrompt ?: imported.privatePrompt,
            groupPrompt = activeGroupPrompt ?: imported.groupPrompt,
        )

        // ---- 关系映射（镜像 :112-130；PERSONA 模式不导入关系）----
        var importedRelations = 0
        var skippedRelations = 0
        val relationshipsToInsert = mutableListOf<Relationship>()
        if (mode != OperatorCardImportMode.PERSONA_AND_APPEARANCE) {
            card.payload.relationships.forEach { relationship ->
                val mappedId = relationshipMappings[relationship.relatedOperatorId]
                    ?: relationship.relatedOperatorId.takeIf { repository.getOperator(it) != null }
                val related = if (mappedId == null) null else repository.getOperator(mappedId)
                if (related == null || related.id == targetId) {
                    skippedRelations++
                } else {
                    relationshipsToInsert += Relationship(
                        operatorId = targetId,
                        relatedOperatorId = related.id,
                        relatedOperatorName = related.name,
                        type = runCatching { RelationshipType.valueOf(relationship.type) }
                            .getOrDefault(RelationshipType.entries.first()),
                        intimacy = relationship.intimacy,
                        isPreset = relationship.isPreset,
                        note = relationship.note,
                    )
                    importedRelations++
                }
            }
        } else {
            skippedRelations = card.payload.relationships.size
        }

        try {
            repository.upsertOperatorAndRelationships(
                operator = runtimeAligned,
                deleteExistingRelationships = mode == OperatorCardImportMode.FULL_REPLACE,
                relationships = relationshipsToInsert,
            )
        } catch (error: Exception) {
            runCatching { mediaSink.cleanup() }
            throw error
        }
        restorePromptSlots(card.payload.promptSlots, runtimeAligned)

        return OperatorCardImportResult(
            operatorId = targetId,
            createdNew = mode == OperatorCardImportMode.NEW,
            importedRelationships = importedRelations,
            skippedRelationships = skippedRelations,
        )
    }

    // ---- 头像与兼容（镜像 OperatorPackageService.kt:135-186）----

    private fun restoreAvatar(card: OperatorCard): String? {
        val bytes = card.avatarBytes ?: return null
        val extension = card.payload.avatar?.archivePath?.substringAfterLast('.', "jpg") ?: "jpg"
        return mediaSink.writeAvatar(bytes, extension)
    }

    private fun portableAvatarUri(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value else ""

    private fun restorePromptSlots(saved: OperatorPromptSlotsWire?, operator: Operator) {
        // 旧卡只带数据库里的当前 prompt：放进当前选中槽，避免编辑器用偏好值遮住导入 prompt。
        if (saved == null) {
            val privateSlot = settings.getInt("operator_prompt_slot_${operator.id}_private", 1).coerceIn(1, 3)
            val groupSlot = settings.getInt("operator_prompt_slot_${operator.id}_group", 1).coerceIn(1, 3)
            settings.putString("operator_prompt_slot_${operator.id}_private_$privateSlot", operator.privatePrompt)
            settings.putString("operator_prompt_slot_${operator.id}_group_$groupSlot", operator.groupPrompt)
            return
        }
        val privateSlots = saved.privateSlots.take(3).padEnd(3, "")
        val groupSlots = saved.groupSlots.take(3).padEnd(3, "")
        (1..3).forEach { slot ->
            settings.putString("operator_prompt_slot_${operator.id}_private_$slot", privateSlots[slot - 1])
            settings.putString("operator_prompt_slot_${operator.id}_group_$slot", groupSlots[slot - 1])
        }
        settings.putInt("operator_prompt_slot_${operator.id}_private", saved.activePrivateSlot.coerceIn(1, 3))
        settings.putInt("operator_prompt_slot_${operator.id}_group", saved.activeGroupSlot.coerceIn(1, 3))
    }

    private fun List<String>.padEnd(size: Int, value: String): List<String> =
        take(size) + List((size - this.size).coerceAtLeast(0)) { value }

    private suspend fun uniqueImportedName(source: String): String {
        val base = "$source（导入）"
        val names = repository.getAllOperatorsSync().map { it.name }.toSet()
        if (base !in names) return base
        var index = 2
        while ("$base $index" in names) index++
        return "$base $index"
    }
}

/**
 * 角色卡导出执行器（规格卡契约 C27；镜像 `OperatorPackageService.kt:29-53`）：
 * 组装 `OperatorCardPayloadWire`（operator 22 字段 + 关系 + 头像字节 + prompt 槽位），
 * `operator.privatePrompt/groupPrompt` 用活跃槽位值对齐；写包由调用方交 [OperatorCardWriter]。
 */
class OperatorCardExporter(
    private val repository: OperatorCardRepository,
    private val settings: SettingsRepository,
) {
    suspend fun export(
        operatorId: String,
        resolveAvatarBytes: (String) -> ByteArray? = { null },
    ): OperatorCardExport {
        val operator = repository.getOperator(operatorId) ?: throw BackupFormatException("找不到要导出的角色")
        val privateSlots = promptSlots(operatorId, "private", operator.privatePrompt)
        val groupSlots = promptSlots(operatorId, "group", operator.groupPrompt)
        val activePrivateSlot = settings.getInt("operator_prompt_slot_${operatorId}_private", 1).coerceIn(1, 3)
        val activeGroupSlot = settings.getInt("operator_prompt_slot_${operatorId}_group", 1).coerceIn(1, 3)
        val avatarBytes = resolveAvatarBytes(operator.avatarUri)
        val extension = operator.avatarUri.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
        val avatar = if (avatarBytes == null) null else BackupMediaItemWire(
            mediaId = "operator-avatar",
            archivePath = "media/avatar.$extension",
            mimeType = when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "application/octet-stream"
            },
            originalUri = operator.avatarUri,
        )
        val payload = OperatorCardPayloadWire(
            operator = operator.copy(
                privatePrompt = privateSlots[activePrivateSlot - 1],
                groupPrompt = groupSlots[activeGroupSlot - 1],
            ),
            relationships = repository.getRelationships(operatorId).map { it.toExport() },
            avatar = avatar,
            promptSlots = OperatorPromptSlotsWire(
                privateSlots = privateSlots,
                groupSlots = groupSlots,
                activePrivateSlot = activePrivateSlot,
                activeGroupSlot = activeGroupSlot,
            ),
        )
        return OperatorCardExport(payload, avatarBytes)
    }

    private fun promptSlots(operatorId: String, type: String, fallback: String): List<String> =
        (1..3).map { slot ->
            settings.getString("operator_prompt_slot_${operatorId}_${type}_$slot", "")
                .ifBlank { if (slot == 1) fallback else "" }
        }

    private fun Relationship.toExport(): RelationshipExport = RelationshipExport(
        operatorId = operatorId,
        relatedOperatorId = relatedOperatorId,
        relatedOperatorName = relatedOperatorName,
        type = type.name,
        intimacy = intimacy,
        isPreset = isPreset,
        note = note,
    )
}
