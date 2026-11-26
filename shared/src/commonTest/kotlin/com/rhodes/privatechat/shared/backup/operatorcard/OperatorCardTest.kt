package com.rhodes.privatechat.shared.backup.operatorcard

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.chatimport.TestChatImportEnv
import com.rhodes.privatechat.shared.backup.chatimport.insertTestOperator
import com.rhodes.privatechat.shared.backup.chatimport.insertTestSession
import com.rhodes.privatechat.shared.backup.chatimport.patchChatExportManifest
import com.rhodes.privatechat.shared.backup.chatimport.rewriteChatExportZip
import com.rhodes.privatechat.shared.domain.media.InMemoryMediaStore
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.RelationshipExport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T30 角色卡验收（规格卡 §验收 #8-#11）：rhodes-operator v1 往返、坏包门禁、旧版 JSON 兼容、
 * 导入三模式（NEW / PERSONA_AND_APPEARANCE / FULL_REPLACE）+ prompt 槽位/关系映射。
 */
abstract class OperatorCardTest {

    protected abstract fun createEnv(): TestChatImportEnv

    private val writer = OperatorCardWriter("1.14.27", now = { 1750000000000L })
    private val reader = OperatorCardReader()

    private fun sampleOperator(id: String = "card_op", name: String = "角色卡金样") = Operator(
        id = id, name = name, title = "金样称号", description = "角色卡往返金样", gender = "女",
        avatarUri = "file:///data/user/0/pkg/files/images/card_1x1.png", location = "宿舍", activity = "休息",
        emotion = "平静", intimacy = 42, privatePrompt = "你是角色卡金样。", groupPrompt = "群聊人设。",
        memoryInjection = "记忆注入", userRelation = "朋友", lmb = 12345, attack = 0.6f, defense = 0.4f,
        meldPref = "high", activityLevel = 0.7f, voiceName = "voice1", voiceSpeed = "1.0", voicePitch = "1.1",
    )

    private val avatarBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    private fun samplePayload() = OperatorCardPayloadWire(
        operator = sampleOperator(),
        relationships = listOf(
            RelationshipExport("card_op", "friend", "朋友角色", "FRIEND", 12, false, "备注"),
        ),
        avatar = com.rhodes.privatechat.shared.backup.BackupMediaItemWire(
            mediaId = "operator-avatar", archivePath = "media/avatar.png",
            mimeType = "image/png", originalUri = "file:///data/user/0/pkg/files/images/card_1x1.png",
        ),
        promptSlots = OperatorPromptSlotsWire(
            privateSlots = listOf("槽1", "槽2", "槽3"),
            groupSlots = listOf("群1", "群2"),
            activePrivateSlot = 2,
            activeGroupSlot = 1,
        ),
    )

    @Test
    fun acceptance8_roundTripExact() {
        val written = writer.write(samplePayload(), avatarBytes)
        assertEquals("rhodes-operator", written.manifest.format)
        assertEquals(1, written.manifest.formatVersion)
        assertEquals("operator_card", written.manifest.scope)
        assertTrue(written.manifest.mediaIncluded)
        assertEquals(2, written.manifest.files.size) // operator.json + avatar

        val card = reader.read(written.bytes)
        val payload = card.payload
        assertEquals(sampleOperator(), payload.operator)
        assertEquals(1, payload.relationships.size)
        assertEquals("friend", payload.relationships[0].relatedOperatorId)
        assertEquals("media/avatar.png", payload.avatar?.archivePath)
        assertEquals("槽2", payload.promptSlots?.privateSlots?.get(1))
        assertEquals(2, payload.promptSlots?.activePrivateSlot)
        assertTrue(avatarBytes.contentEquals(card.avatarBytes), "头像字节往返必须一致")
        assertEquals(BackupCrypto.sha256Hex(avatarBytes), BackupCrypto.sha256Hex(card.avatarBytes!!))
    }

    @Test
    fun acceptance8b_noAvatarCardRoundTrip() {
        val payload = samplePayload().copy(avatar = null)
        val written = writer.write(payload, null)
        assertFalse(written.manifest.mediaIncluded)
        val card = reader.read(written.bytes)
        assertNull(card.payload.avatar)
        assertNull(card.avatarBytes)
    }

    @Test
    fun acceptance9_badCardsRejected() {
        val base = writer.write(samplePayload(), avatarBytes).bytes
        val cases = mapOf(
            "缺 manifest" to rewriteChatExportZip(base) { it - "manifest.json" },
            "篡改 operator.json" to rewriteChatExportZip(base) { entries ->
                val data = entries["data/operator.json"]!!.copyOf()
                data[data.size / 2] = (data[data.size / 2].toInt() xor 0xFF).toByte()
                entries + ("data/operator.json" to data)
            },
            "format 不符" to patchChatExportManifest(base) { m ->
                JsonObject(m + ("format" to JsonPrimitive("rhodes-backup")))
            },
            "formatVersion=2" to patchChatExportManifest(base) { m ->
                JsonObject(m + ("formatVersion" to JsonPrimitive(2)))
            },
            "不安全路径" to rewriteChatExportZip(base) { it + ("../evil.json" to byteArrayOf(1)) },
            "超条目上限" to rewriteChatExportZip(base) { entries ->
                entries + (1..20).associate { "data/extra_$it.json" to byteArrayOf(it.toByte()) }
            },
        )
        for ((label, bytes) in cases) {
            assertFailsWith<com.rhodes.privatechat.shared.backup.BackupFormatException>("$label 应被拒绝") {
                reader.read(bytes)
            }
        }
    }

    @Test
    fun acceptance9b_avatarManifestMismatchRejected() {
        val written = writer.write(samplePayload(), avatarBytes)
        val mismatched = rewriteChatExportZip(written.bytes) { it - "media/avatar.png" }
        assertFailsWith<com.rhodes.privatechat.shared.backup.BackupFormatException> { reader.read(mismatched) }
    }

    @Test
    fun acceptance11_legacySingleOperatorJsonAccepted() {
        val operator = sampleOperator("legacy_op", "旧版角色")
        val legacyJson = """
            {"type":"operators","timestamp":1750000000000,
             "operators":[${operatorJson(operator)}],
             "relationships":[{"operatorId":"legacy_op","relatedOperatorId":"friend","relatedOperatorName":"朋友","type":"FRIEND","intimacy":3,"isPreset":false,"note":""}]}
        """.trimIndent().encodeToByteArray()
        val card = OperatorCardReader().readCompatible(legacyJson)
        assertEquals("legacy_op", card.payload.operator.id)
        assertEquals("旧版角色", card.payload.operator.name)
        assertEquals(42, card.payload.operator.intimacy)
        assertEquals(1, card.payload.relationships.size)
        assertNull(card.avatarBytes)
    }

    @Test
    fun acceptance11b_legacyJsonWithMultipleOperatorsRejected() {
        val operator = sampleOperator("legacy_op", "旧版角色")
        val bad = """{"type":"operators","operators":[${operatorJson(operator)},${operatorJson(operator.copy(id = "op2"))}]}"""
            .encodeToByteArray()
        assertFailsWith<com.rhodes.privatechat.shared.backup.BackupFormatException> {
            OperatorCardReader().readCompatible(bad)
        }
    }

    @Test
    fun acceptance10_importNewCreatesDeduplicatedNameAndMapsRelationships() = runBlocking {
        val env = createEnv()
        try {
            // 目标端已有关系目标 friend；card_op 不存在 → NEW 导入
            env.database.insertTestOperator(operator("friend", "朋友角色"))
            val card = reader.read(writer.write(samplePayload(), avatarBytes).bytes)
            val store = InMemoryMediaStore()
            val importer = OperatorCardImporter(env.operatorCardRepository, env.settings, MediaStoreOperatorCardMediaSink(store))
            val result = importer.import(card, OperatorCardImportMode.NEW)
            assertTrue(result.createdNew)
            val imported = env.repository.getOperator(result.operatorId)
            assertNotNull(imported)
            assertEquals("角色卡金样（导入）", imported.name)
            assertEquals(1, result.importedRelationships)
            assertEquals(1, env.operatorCardRepository.getRelationships(result.operatorId).size)
            // 头像落运行时媒体库并写回 operator.avatarUri
            assertTrue(imported.avatarUri.startsWith("media/card-avatar-"), imported.avatarUri)
            assertEquals(1, store.fileCount())
            // prompt 槽位回填（活跃槽 2 的 private prompt）
            assertEquals("槽2", imported.privatePrompt)
            assertEquals("槽2", env.settings.getString("operator_prompt_slot_${result.operatorId}_private_2"))
            assertEquals(2, env.settings.getInt("operator_prompt_slot_${result.operatorId}_private"))
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance10b_personaModeOnlyTouchesPersonaFields() = runBlocking {
        val env = createEnv()
        try {
            env.database.insertTestOperator(
                operator("target", "目标角色").copy(intimacy = 77, lmb = 999, attack = 0.9f),
            )
            val cardPayload = samplePayload().copy(
                operator = sampleOperator("ignored", "新人设").copy(id = "ignored"),
                relationships = emptyList(),
                avatar = null,
            )
            val card = reader.read(writer.write(cardPayload, null).bytes)
            val result = OperatorCardImporter(
                env.operatorCardRepository, env.settings, MediaStoreOperatorCardMediaSink(InMemoryMediaStore()),
            ).import(card, OperatorCardImportMode.PERSONA_AND_APPEARANCE, targetOperatorId = "target")
            val updated = env.repository.getOperator("target")
            assertNotNull(updated)
            assertEquals("新人设", updated.name)
            assertEquals(77, updated.intimacy, "人设模式不得修改好感度")
            assertEquals(999, updated.lmb, "人设模式不得修改龙门币")
            assertEquals(0.9f, updated.attack)
            assertEquals(0, result.importedRelationships)
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance10c_fullReplaceReplacesFieldsAndRelationships() = runBlocking {
        val env = createEnv()
        try {
            env.database.insertTestOperator(operator("target", "目标角色").copy(intimacy = 1, lmb = 1))
            env.database.insertTestOperator(operator("friend", "朋友角色"))
            env.operatorCardRepository.upsertOperatorAndRelationships(
                operator("target", "目标角色"), deleteExistingRelationships = true,
                relationships = listOf(
                    com.rhodes.privatechat.shared.model.Relationship(
                        operatorId = "target", relatedOperatorId = "friend", relatedOperatorName = "朋友角色",
                        type = com.rhodes.privatechat.shared.model.RelationshipType.entries.first(), intimacy = 1,
                    ),
                ),
            )
            val card = reader.read(writer.write(samplePayload(), avatarBytes).bytes)
            val result = OperatorCardImporter(
                env.operatorCardRepository, env.settings, MediaStoreOperatorCardMediaSink(InMemoryMediaStore()),
            ).import(card, OperatorCardImportMode.FULL_REPLACE, targetOperatorId = "target")
            val updated = env.repository.getOperator("target")
            assertNotNull(updated)
            assertEquals(42, updated.intimacy, "覆盖模式应替换角色数值")
            assertEquals("朋友角色", env.operatorCardRepository.getRelationships("target").single().relatedOperatorName)
            assertEquals(1, result.importedRelationships)
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance_exporterAssemblesPayload() = runBlocking {
        val env = createEnv()
        try {
            env.database.insertTestOperator(sampleOperator("export_op", "导出角色"))
            env.database.insertTestSession(ChatSession("session_export_op", "export_op", "导出角色", "", 0, "online", false, 0, "", "", "", ""))
            env.settings.putString("operator_prompt_slot_export_op_private_1", "偏好槽1")
            env.settings.putString("operator_prompt_slot_export_op_private_2", "偏好槽2")
            env.settings.putInt("operator_prompt_slot_export_op_private", 2)
            val export = OperatorCardExporter(env.operatorCardRepository, env.settings)
                .export("export_op") { avatarBytes }
            assertEquals("偏好槽2", export.payload.operator.privatePrompt, "活跃槽位 prompt 必须对齐运行时字段")
            assertEquals(3, export.payload.promptSlots?.privateSlots?.size)
            assertEquals(2, export.payload.promptSlots?.activePrivateSlot)
            assertTrue(avatarBytes.contentEquals(export.avatarBytes))
            assertEquals(2, export.payload.promptSlots?.activePrivateSlot)
        } finally {
            env.close()
        }
    }

    private fun operator(id: String, name: String) = sampleOperator(id, name)

    private fun operatorJson(operator: Operator): String = """
        {"id":"${operator.id}","name":"${operator.name}","title":"${operator.title}","description":"${operator.description}","gender":"${operator.gender}","avatarUri":"${operator.avatarUri}","location":"${operator.location}","activity":"${operator.activity}","emotion":"${operator.emotion}","intimacy":${operator.intimacy},"privatePrompt":"${operator.privatePrompt}","groupPrompt":"${operator.groupPrompt}","memoryInjection":"${operator.memoryInjection}","userRelation":"${operator.userRelation}","lmb":${operator.lmb},"attack":${operator.attack},"defense":${operator.defense},"meldPref":"${operator.meldPref}","activityLevel":${operator.activityLevel},"voiceName":"${operator.voiceName}","voiceSpeed":"${operator.voiceSpeed}","voicePitch":"${operator.voicePitch}"}
    """.trimIndent()
}
