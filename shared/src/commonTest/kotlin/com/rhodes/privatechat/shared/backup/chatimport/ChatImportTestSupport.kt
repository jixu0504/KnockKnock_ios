package com.rhodes.privatechat.shared.backup.chatimport

import app.cash.sqldelight.db.SqlDriver
import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupZip
import com.rhodes.privatechat.shared.backup.BackupZipWriter
import com.rhodes.privatechat.shared.backup.operatorcard.OperatorCardRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.Relationship
import com.rhodes.privatechat.shared.model.RelationshipType
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 聊天导入测试公共设施（T30）：
 *  - `readTestResourceBytes`：expect/actual 读取 `golden/chat-import/` 金样（JVM classpath / iOS test bundle）；
 *  - [ChatImportGoldens]：4 个金样包名 + 冻结 SHA-256（与 `ios-port/golden/chat-import/README.md` §1 一致）；
 *  - 平台无关的 [TestChatImportRepository] / [TestOperatorCardRepository]（双路径共用一份断言）；
 *  - zip 变体改写工具（坏包/篡改用例）——复用 commonMain [BackupZip] + [BackupZipWriter]。
 */
internal expect fun readTestResourceBytes(path: String): ByteArray

internal object ChatImportGoldens {
    private const val DIR = "golden/chat-import"

    val FROZEN_SHA256 = mapOf(
        "chat-text-v1.zip" to "178e7abbd6308299be319920f3e764dd5dda6f94e9e2a6ba9d3f868619d8150d",
        "chat-image-v1.zip" to "0872d3df81847c43120d9fde58ad76147ed4bb752e4884df28e779246717a612",
        "chat-ai-json-v1.zip" to "629c61b5db0aa52264b545acfa5a56f0cbfc2b80bf6ee89a4993f512d20d1c91",
        "chat-2000-v1.zip" to "19c5f63c89a162cc76633f33074694445b71c6b4548104eb8701d6d33d26f981",
    )

    fun load(name: String): ByteArray {
        val bytes = readTestResourceBytes("$DIR/$name")
        val expected = FROZEN_SHA256[name] ?: error("未登记的聊天金样：$name")
        val actual = BackupCrypto.sha256Hex(bytes)
        check(actual == expected) { "金样 $name SHA-256 与冻结表不一致（资源同步过期？实际 $actual）" }
        return bytes
    }
}

/** 平台无关的导入测试环境。 */
class TestChatImportEnv(
    val driver: SqlDriver,
    val database: RhodesDatabase,
    val settings: SettingsRepository,
    private val cleanup: (() -> Unit)? = null,
) {
    val repository = TestChatImportRepository(database)
    val operatorCardRepository = TestOperatorCardRepository(database)

    fun close() {
        runCatching { driver.close() }
        cleanup?.invoke()
    }
}

/** 基于 RhodesDatabase 的 [ChatImportRepository] 假实现（与生产 ChatRepository 同语义）。 */
class TestChatImportRepository(private val database: RhodesDatabase) : ChatImportRepository {

    override suspend fun getSession(id: String): ChatSession? =
        database.chatSessionsQueries.getSession(id) { id_, operatorId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            ChatSession(id_, operatorId, operatorName, lastMessage, lastTime, mode, isPinned != 0L, unreadCount.toInt(), members, rules, avatarUri, mutedMembers)
        }.executeAsOneOrNull()

    override suspend fun getSessionByOperator(operatorId: String): ChatSession? =
        database.chatSessionsQueries.getSessionByOperator(operatorId, operatorId) { id_, opId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            ChatSession(id_, opId, operatorName, lastMessage, lastTime, mode, isPinned != 0L, unreadCount.toInt(), members, rules, avatarUri, mutedMembers)
        }.executeAsOneOrNull()

    override suspend fun getMessages(sessionId: String): List<ChatMessage> =
        database.chatMessagesQueries.getMessages(sessionId) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()

    override suspend fun getOperator(id: String): Operator? =
        database.operatorsQueries.getOperator(id, ::mapOperatorRow).executeAsOneOrNull()

    override suspend fun maxMessageId(): Long? = database.chatMessagesQueries.getMaxId().executeAsOne().MAX

    override suspend fun getArchiveIds(sessionId: String): Set<String> =
        database.chatArchivesQueries.getArchivesBySession(sessionId) { id, _, _, _, _, _, _, _, _, _, _, _ -> id }
            .executeAsList().toSet()

    override suspend fun getHistorySegmentIds(sessionId: String): Set<String> =
        database.chatArchivesQueries.getHistorySegmentsBySession(sessionId) { id, _, _, _, _, _ -> id }
            .executeAsList().toSet()

    override suspend fun runImportTransaction(block: RhodesDatabase.() -> Unit) {
        database.transaction { block(database) }
    }

}

/** 基于 RhodesDatabase 的 [OperatorCardRepository] 假实现。 */
class TestOperatorCardRepository(private val database: RhodesDatabase) : OperatorCardRepository {

    override suspend fun getOperator(id: String): Operator? =
        database.operatorsQueries.getOperator(id, ::mapOperatorRow).executeAsOneOrNull()

    override suspend fun getAllOperatorsSync(): List<Operator> =
        database.operatorsQueries.getAllOperators(::mapOperatorRow).executeAsList()

    override suspend fun getRelationships(operatorId: String): List<Relationship> =
        database.relationshipsQueries.getRelationships(operatorId) { id, opId, relatedId, relatedName, type, intimacy, isPreset, note ->
            Relationship(
                id, opId, relatedId, relatedName,
                runCatching { RelationshipType.valueOf(type) }.getOrDefault(RelationshipType.entries.first()),
                intimacy.toInt(), isPreset != 0L, note,
            )
        }.executeAsList()

    override suspend fun upsertOperatorAndRelationships(
        operator: Operator,
        deleteExistingRelationships: Boolean,
        relationships: List<Relationship>,
    ) {
        database.transaction {
            database.operatorsQueries.insertAllOperators(
                operator.id, operator.name, operator.title, operator.description, operator.gender,
                operator.avatarUri, operator.location, operator.activity, operator.emotion,
                operator.intimacy.toLong(), operator.privatePrompt, operator.groupPrompt,
                operator.memoryInjection, operator.userRelation, operator.lmb.toLong(),
                operator.attack.toDouble(), operator.defense.toDouble(), operator.meldPref,
                operator.activityLevel.toDouble(), operator.voiceName, operator.voiceSpeed, operator.voicePitch,
            )
            if (deleteExistingRelationships) database.relationshipsQueries.deleteByOperator(operator.id)
            relationships.forEach { relationship ->
                database.relationshipsQueries.insertRelationship(
                    relationship.operatorId, relationship.relatedOperatorId,
                    relationship.relatedOperatorName, relationship.type.name,
                    relationship.intimacy.toLong(), if (relationship.isPreset) 1L else 0L, relationship.note,
                )
            }
        }
    }
}

internal fun mapOperatorRow(
    id: String, name: String, title: String, description: String, gender: String, avatarUri: String,
    location: String, activity: String, emotion: String, intimacy: Long, privatePrompt: String,
    groupPrompt: String, memoryInjection: String, userRelation: String, lmb: Long, attack: Double,
    defense: Double, meldPref: String, activityLevel: Double, voiceName: String, voiceSpeed: String,
    voicePitch: String,
): Operator = Operator(
    id, name, title, description, gender, avatarUri, location, activity, emotion,
    intimacy.toInt(), privatePrompt, groupPrompt, memoryInjection, userRelation,
    lmb.toInt(), attack.toFloat(), defense.toFloat(), meldPref, activityLevel.toFloat(),
    voiceName, voiceSpeed, voicePitch,
)

// ---- 测试夹具读写工具 ----

internal fun RhodesDatabase.insertTestOperator(operator: Operator) {
    operatorsQueries.insertAllOperators(
        operator.id, operator.name, operator.title, operator.description, operator.gender,
        operator.avatarUri, operator.location, operator.activity, operator.emotion,
        operator.intimacy.toLong(), operator.privatePrompt, operator.groupPrompt, operator.memoryInjection,
        operator.userRelation, operator.lmb.toLong(), operator.attack.toDouble(), operator.defense.toDouble(),
        operator.meldPref, operator.activityLevel.toDouble(), operator.voiceName, operator.voiceSpeed,
        operator.voicePitch,
    )
}

internal fun RhodesDatabase.insertTestSession(session: ChatSession) {
    chatSessionsQueries.insertSession(
        session.id, session.operatorId, session.operatorName, session.lastMessage, session.lastTime,
        session.mode, if (session.isPinned) 1L else 0L, session.unreadCount.toLong(), session.members,
        session.rules, session.avatarUri, session.mutedMembers,
    )
}

internal fun RhodesDatabase.insertTestMessage(message: ChatMessage) {
    chatMessagesQueries.insertMessage(
        message.id, message.sessionId, message.senderId, message.senderName, message.content,
        message.type, message.mode, message.emotion, message.activity, message.location,
        message.narration, message.segmentGroup, message.intimacyChange.toLong(), message.timestamp,
        if (message.isMe) 1L else 0L,
    )
}

internal fun RhodesDatabase.readAllMessages(): List<ChatMessage> =
    chatMessagesQueries.getAllMessagesForBackup { id, sessionId, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
        ChatMessage(id, sessionId, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
    }.executeAsList()

internal fun RhodesDatabase.readAllSessions(): List<ChatSession> =
    chatSessionsQueries.getAllSessions { id, operatorId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
        ChatSession(id, operatorId, operatorName, lastMessage, lastTime, mode, isPinned != 0L, unreadCount.toInt(), members, rules, avatarUri, mutedMembers)
    }.executeAsList()

internal fun RhodesDatabase.readDisplayEvents(): List<Triple<Long, Long, Long>> =
    chatDisplayEventsQueries.getAllDisplayEvents { messageId, segmentIndex, _, revealOrder -> Triple(messageId, segmentIndex, revealOrder) }
        .executeAsList()

internal fun RhodesDatabase.readArchives(sessionId: String) =
    chatArchivesQueries.getArchivesBySession(sessionId) { id, sid, operatorId, title, note, mode, messagesJson, summary, stateJson, status, createdAt, updatedAt ->
        com.rhodes.privatechat.shared.model.ChatArchive(id, sid, operatorId, title, note, mode, messagesJson, summary, stateJson, status, createdAt, updatedAt)
    }.executeAsList()

/** 读取 zip 全部条目 → 变换 → 用 STORED 写入器重建（坏包/篡改变体用；产物仍可被 BackupZip 读取）。 */
internal fun rewriteChatExportZip(source: ByteArray, transform: (Map<String, ByteArray>) -> Map<String, ByteArray>): ByteArray {
    val entries = BackupZip.read(source, 1_000, 100L * 1024 * 1024, 500L * 1024 * 1024)
    val original = entries.associate { it.name to it.data }
    val transformed = transform(original)
    return BackupZipWriter.write(transformed.map { (name, data) -> BackupZipWriter.Entry(name, data) }, allowUnsafePaths = true)
}

/** 改写 manifest.json（保留其余条目；坏包/门禁变体用）。 */
internal fun patchChatExportManifest(source: ByteArray, transform: (JsonObject) -> JsonObject): ByteArray =
    rewriteChatExportZip(source) { entries ->
        val manifest = Json.parseToJsonElement(entries["manifest.json"]!!.decodeToString()) as JsonObject
        entries + ("manifest.json" to Json.encodeToString(JsonElement.serializer(), transform(manifest)).encodeToByteArray())
    }

/** 改写 conversation.json 并同步修正 manifest.files 的 size/sha256（门禁变体用）。 */
internal fun patchChatExportConversation(source: ByteArray, transform: (String) -> String): ByteArray =
    rewriteChatExportZip(source) { entries ->
        val newConversation = transform(entries["conversation.json"]!!.decodeToString()).encodeToByteArray()
        val manifest = Json.parseToJsonElement(entries["manifest.json"]!!.decodeToString()) as JsonObject
        val files = (manifest["files"] as JsonArray).map { element ->
            val file = element as JsonObject
            if (file["path"]!!.jsonPrimitive.content == "conversation.json") {
                JsonObject(
                    file + ("size" to JsonPrimitive(newConversation.size)) +
                        ("sha256" to JsonPrimitive(BackupCrypto.sha256Hex(newConversation))),
                )
            } else {
                file
            }
        }
        val newManifest = JsonObject(manifest + ("files" to JsonArray(files)))
        entries + ("conversation.json" to newConversation) +
            ("manifest.json" to Json.encodeToString(JsonElement.serializer(), newManifest).encodeToByteArray())
    }
