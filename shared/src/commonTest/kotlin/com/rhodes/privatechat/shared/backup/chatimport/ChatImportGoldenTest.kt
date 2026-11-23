package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.backup.BackupFileReader
import com.rhodes.privatechat.shared.backup.BackupValidationResult
import com.rhodes.privatechat.shared.domain.media.InMemoryMediaStore
import com.rhodes.privatechat.shared.model.ChatSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * T30 聊天导入验收（规格卡 §验收 #1-#7）：4 类金样校验/往返、重复导入零重复、坏包无残留、
 * formatVersion 门禁、与 T10 完整备份读取器互斥、2000 条规模、会话合并。
 *
 * 同一份 commonTest 跑两条路径：`:shared:testDebugUnitTest`（JVM 内存驱动）与
 * `:shared:iosSimulatorArm64Test`（NativeSqliteDriver，iPhone 17 模拟器）。
 */
abstract class ChatImportGoldenTest {

    /** 平台源集提供：JVM = JdbcSqliteDriver 内存库；iOS = NativeSqliteDriver + NSUserDefaults suite。 */
    protected abstract fun createEnv(): TestChatImportEnv

    private val reader = ChatExportReader()

    private val expectedMessages = mapOf(
        "chat-text-v1.zip" to 8,
        "chat-image-v1.zip" to 6,
        "chat-ai-json-v1.zip" to 5,
        "chat-2000-v1.zip" to 2000,
    )

    private val expectedMedia = mapOf(
        "chat-text-v1.zip" to 0,
        "chat-image-v1.zip" to 2,
        "chat-ai-json-v1.zip" to 1,
        "chat-2000-v1.zip" to 0,
    )

    private fun mediaSink(store: InMemoryMediaStore): ChatImportMediaSink = MediaStoreChatImportMediaSink(store)

    private fun restorer(env: TestChatImportEnv, sink: ChatImportMediaSink) =
        ChatMergeRestorer(env.repository, sink, env.settings)

    @Test
    fun acceptance1_allGoldenArchivesValidate() = runBlocking {
        for (name in expectedMessages.keys) {
            val bytes = ChatImportGoldens.load(name)
            when (val result = reader.validate(bytes)) {
                is ChatExportValidationResult.Valid -> {
                    assertEquals(expectedMessages[name], result.messageCount, "$name 消息数")
                    assertEquals(expectedMedia[name], result.mediaCount, "$name 媒体数")
                    assertEquals(1, result.conversation.messages.map { it.sessionId }.distinct().size)
                }
                is ChatExportValidationResult.Invalid -> throw AssertionError("$name 金样校验失败：${result.reason}")
            }
        }
    }

    @Test
    fun acceptance1b_chatExportIsRejectedByFullBackupReader() {
        val bytes = ChatImportGoldens.load("chat-text-v1.zip")
        val result = BackupFileReader().validate(bytes)
        assertTrue(result is BackupValidationResult.Invalid, "RhodesLinkChatExport 不是 rhodes-backup 子集，必须被 T10 读取器拒绝")
        assertTrue(result.reason.contains("不支持的备份类型"), "实际 reason=${result.reason}")
    }

    @Test
    fun acceptance2_goldenRoundTrip() = runBlocking {
        for (name in listOf("chat-text-v1.zip", "chat-image-v1.zip", "chat-ai-json-v1.zip")) {
            val env = createEnv()
            val store = InMemoryMediaStore()
            try {
                // 预置一条 id=100 的历史消息，证明导入消息 id 被重分配（不复用源 id 1..N）。
                val baseOperator = mapOperatorRow(
                    "seed", "种子角色", "", "", "", "", "宿舍", "休息", "平静", 0L, "", "", "", "",
                    10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
                )
                env.database.insertTestOperator(baseOperator)
                env.database.insertTestSession(
                    ChatSession("session_seed", "seed", "种子角色", "旧消息", 1L, "online", false, 0, "", "", "", ""),
                )
                env.database.insertTestMessage(
                    com.rhodes.privatechat.shared.model.ChatMessage(
                        100L, "session_seed", "", "种子角色", "旧消息", "text", "online", "", "", "", "", "", 0, 1L, false,
                    ),
                )

                val bytes = ChatImportGoldens.load(name)
                val archive = reader.read(bytes)
                val source = archive.conversation
                val result = restorer(env, mediaSink(store)).import(bytes)
                assertTrue(result.success, "$name 导入失败：${result.reason}")
                assertEquals(expectedMessages[name], result.importedMessages, "$name 导入消息数")
                assertEquals(0, result.dedupedMessages)
                assertEquals(expectedMedia[name], result.importedMedia)
                assertEquals("session_${source.session!!.operatorId}", result.sessionId)

                // 消息：数量/顺序/type/isMe/timestamp 与源一致；id 全部重分配为 >100 且严格递增。
                val imported = env.database.readAllMessages().filter { it.sessionId == result.sessionId }
                    .sortedWith(compareBy({ it.timestamp }, { it.id }))
                assertEquals(expectedMessages[name], imported.size)
                assertEquals(source.messages.map { it.type }, imported.map { it.type })
                assertEquals(source.messages.map { it.isMe }, imported.map { it.isMe })
                assertEquals(source.messages.map { it.timestamp }, imported.map { it.timestamp })
                assertTrue(imported.all { it.id > 100L }, "导入消息 id 必须重分配：${imported.map { it.id }}")
                assertEquals(imported.map { it.id }.sorted(), imported.map { it.id })

                // 会话：按确定性 id 新建，角色补齐。
                val session = env.database.readAllSessions().singleOrNull { it.id == result.sessionId }
                assertNotNull(session, "$name 未创建会话")
                assertEquals(source.session.operatorId, session.operatorId)
                assertNotNull(env.repository.getOperator(source.session.operatorId), "$name 未补齐角色")

                // displayEvents：数量与源一致，引用重映射到新 id 且揭示序保留。
                val events = env.database.readDisplayEvents()
                assertEquals(source.displayEvents.size, events.size)
                val newIds = imported.map { it.id }.toSet()
                events.forEach { (messageId, _, _) -> assertTrue(messageId in newIds, "displayEvent 引用了未重映射的消息 id=$messageId") }
                assertEquals(
                    source.displayEvents.map { it.revealOrder }.sorted(),
                    events.map { it.third }.sorted(),
                )

                if (name == "chat-text-v1.zip") {
                    // 存档/历史片段：messagesJson 内消息 id 必须重映射为大 id，旧 id 1..4 不得残留。
                    val archives = env.database.readArchives(result.sessionId)
                    assertEquals(1, archives.size)
                    val archiveIds = regexIds(archives.single().messagesJson)
                    assertTrue(archiveIds.isNotEmpty(), "存档 messagesJson 应含消息 id")
                    assertTrue(archiveIds.none { it <= 100L }, "存档仍引用旧 id：$archiveIds")
                    assertTrue(archiveIds.all { it in newIds }, "存档引用未重映射：$archiveIds")
                }
                if (expectedMedia[name]!! > 0) {
                    // 媒体：落运行时媒体库、内容寻址；消息 content 内 imageUri 重写为运行时媒体 ID。
                    assertEquals(expectedMedia[name], store.fileCount(), "$name 媒体文件数")
                    val rewritten = imported.filter { it.content.contains("media/import-") }
                    assertTrue(rewritten.isNotEmpty(), "$name 应至少一条消息内嵌图被重写")
                    assertTrue(imported.none { it.content.contains("data/user/0") }, "$name 不应残留 Android 绝对路径")
                    source.mediaIndex.forEach { item ->
                        val sha24 = item.sha256.take(24)
                        val staged = store.readBytes("images/media/import-$sha24.png")
                        assertNotNull(staged, "$name 缺少落盘媒体 $sha24")
                        assertEquals(item.sha256, com.rhodes.privatechat.shared.backup.BackupCrypto.sha256Hex(staged))
                    }
                } else {
                    assertEquals(0, store.fileCount())
                }
            } finally {
                env.close()
            }
        }
    }

    @Test
    fun acceptance3_duplicateImportIsIdempotent() = runBlocking {
        val env = createEnv()
        val store = InMemoryMediaStore()
        try {
            val imageBytes = ChatImportGoldens.load("chat-image-v1.zip")
            val first = restorer(env, mediaSink(store)).import(imageBytes)
            assertTrue(first.success)
            val snapshotMessages = env.database.readAllMessages()
            val snapshotSessions = env.database.readAllSessions()
            val snapshotEvents = env.database.readDisplayEvents()
            val filesAfterFirst = store.fileCount()

            val second = restorer(env, mediaSink(store)).import(imageBytes)
            assertTrue(second.success, "二次导入失败：${second.reason}")
            assertEquals(0, second.importedMessages, "重复导入必须零新增")
            assertEquals(snapshotMessages.size, second.dedupedMessages)
            assertEquals(snapshotMessages, env.database.readAllMessages())
            assertEquals(snapshotSessions, env.database.readAllSessions())
            assertEquals(snapshotEvents, env.database.readDisplayEvents())
            assertEquals(filesAfterFirst, store.fileCount(), "重复导入不应新增媒体文件")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance4_invalidArchivesLeaveNoResidue() = runBlocking {
        val base = ChatImportGoldens.load("chat-text-v1.zip")
        val variants = mapOf(
            "missing-manifest" to rewriteChatExportZip(base) { entries -> entries - "manifest.json" },
            "formatVersion=2" to patchChatExportManifest(base) { manifest ->
                kotlinx.serialization.json.JsonObject(manifest + ("formatVersion" to kotlinx.serialization.json.JsonPrimitive(2)))
            },
            "unsafe-path" to rewriteChatExportZip(base) { entries -> entries + ("../evil.json" to "{}".encodeToByteArray()) },
            "tampered-conversation" to rewriteChatExportZip(base) { entries ->
                val data = entries["conversation.json"]!!.copyOf()
                data[data.size / 2] = (data[data.size / 2].toInt() xor 0xFF).toByte()
                entries + ("conversation.json" to data)
            },
        )
        for ((label, bytes) in variants) {
            val env = createEnv()
            val store = InMemoryMediaStore()
            try {
                val before = env.database.readAllMessages().size to env.database.readAllSessions().size
                val validation = reader.validate(bytes)
                assertTrue(validation is ChatExportValidationResult.Invalid, "$label 应被拒绝")
                val result = restorer(env, mediaSink(store)).import(bytes)
                assertFalse(result.success, "$label 导入必须失败")
                assertEquals(before, env.database.readAllMessages().size to env.database.readAllSessions().size, "$label 不得污染 DB")
                assertEquals(0, store.fileCount(), "$label 不得残留媒体")
            } finally {
                env.close()
            }
        }
    }

    @Test
    fun acceptance5_formatVersionGate() = runBlocking {
        val base = ChatImportGoldens.load("chat-text-v1.zip")
        fun reasonOf(bytes: ByteArray): String {
            val result = reader.validate(bytes)
            return (result as? ChatExportValidationResult.Invalid)?.reason ?: throw AssertionError("应被拒绝但通过了")
        }
        assertTrue(
            reasonOf(patchChatExportManifest(base) { m ->
                kotlinx.serialization.json.JsonObject(m + ("formatVersion" to kotlinx.serialization.json.JsonPrimitive(2)))
            }).contains("不支持的聊天导出格式版本"),
        )
        assertTrue(
            reasonOf(patchChatExportManifest(base) { m ->
                kotlinx.serialization.json.JsonObject(m + ("scope" to kotlinx.serialization.json.JsonPrimitive("full")))
            }).contains("不是单会话聊天导出包"),
        )
        assertTrue(
            reasonOf(patchChatExportManifest(base) { m ->
                kotlinx.serialization.json.JsonObject(m + ("format" to kotlinx.serialization.json.JsonPrimitive("rhodes-backup")))
            }).contains("不支持的聊天导出类型"),
        )
        assertTrue(reasonOf(patchChatExportConversation(base) { raw -> raw.replace("\"formatVersion\":1", "\"formatVersion\":2") })
            .contains("不支持的聊天会话格式版本"))
    }

    @Test
    fun acceptance7_2000MessagesImport() = runBlocking {
        val env = createEnv()
        try {
            val bytes = ChatImportGoldens.load("chat-2000-v1.zip")
            val started = TimeSource.Monotonic.markNow()
            val result = restorer(env, mediaSink(InMemoryMediaStore())).import(bytes)
            val elapsedMs = started.elapsedNow().inWholeMilliseconds
            assertTrue(result.success, "2000 条导入失败：${result.reason}")
            assertEquals(2000, result.importedMessages)
            val imported = env.database.readAllMessages().sortedBy { it.id }
            assertEquals(2000, imported.size)
            assertEquals((1L..2000L).toList(), imported.map { it.id })
            assertEquals(1000, env.database.readDisplayEvents().size)
            // 2000 条夹具的往返内容/角色/会话断言（补齐验收 #2 的第 4 类）
            val source = reader.read(bytes).conversation
            assertEquals(source.messages.map { it.type }, imported.map { it.type })
            assertEquals(source.messages.first().content, imported.first().content)
            assertEquals(source.messages.last().content, imported.last().content)
            assertNotNull(env.database.readAllSessions().singleOrNull { it.id == result.sessionId })
            assertNotNull(env.repository.getOperator(source.session!!.operatorId))
            println("2000条导入耗时 ${elapsedMs}ms")
            assertTrue(elapsedMs < 10_000, "2000 条导入耗时 ${elapsedMs}ms 超过 10s 软线")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance_sessionMergePreservesExistingChat() = runBlocking {
        val env = createEnv()
        try {
            val bytes = ChatImportGoldens.load("chat-text-v1.zip")
            val source = reader.read(bytes).conversation
            // 预置与导出会话同 id 的现有会话 + 一条与导出 msg1 完全相同的消息（应被去重）。
            val operator = mapOperatorRow(
                source.session!!.operatorId, "旧名字", "", "", "", "", "宿舍", "休息", "平静", 7L, "", "", "", "",
                123L, 0.5, 0.5, "medium", 0.5, "", "", "",
            )
            env.database.insertTestOperator(operator)
            env.database.insertTestSession(
                ChatSession(
                    source.session.id, source.session.operatorId, "旧名字", "旧的最后一条", 10L, "online",
                    isPinned = true, unreadCount = 3, members = "", rules = "", avatarUri = "media/keep.png", mutedMembers = "",
                ),
            )
            env.database.insertTestMessage(
                com.rhodes.privatechat.shared.model.ChatMessage(
                    7L, source.session.id, source.messages[0].senderId, source.messages[0].senderName,
                    source.messages[0].content, source.messages[0].type, "online", "", "", "", "", "", 0,
                    source.messages[0].timestamp, source.messages[0].isMe,
                ),
            )

            val result = restorer(env, mediaSink(InMemoryMediaStore())).import(bytes)
            assertTrue(result.success, "合并导入失败：${result.reason}")
            assertEquals(7, result.importedMessages)
            assertEquals(1, result.dedupedMessages)
            val sessions = env.database.readAllSessions()
            assertEquals(1, sessions.size, "同一私聊不得新增会话")
            val merged = sessions.single()
            assertEquals(source.session.id, merged.id)
            assertTrue(merged.isPinned, "合并必须保留现有置顶")
            assertEquals("media/keep.png", merged.avatarUri, "合并必须保留现有头像")
            // 导出会话 lastTime 更新 → lastMessage/lastTime 采用导出值
            assertEquals(source.session.lastTime, merged.lastTime)
            assertEquals(source.session.lastMessage, merged.lastMessage)
            assertEquals(8, env.database.readAllMessages().count { it.sessionId == source.session.id })
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance_rollbackOnDbFailureLeavesNoResidue() = runBlocking {
        val env = createEnv()
        val store = InMemoryMediaStore()
        try {
            val failingRepository = object : ChatImportRepository by env.repository {
                override suspend fun runImportTransaction(block: com.rhodes.privatechat.shared.db.RhodesDatabase.() -> Unit) {
                    env.database.transaction {
                        block(env.database)
                        throw IllegalStateException("注入 DB 失败")
                    }
                }
            }
            val sink = mediaSink(store)
            val restorer = ChatMergeRestorer(failingRepository, sink, env.settings)
            val result = restorer.import(ChatImportGoldens.load("chat-image-v1.zip"))
            assertFalse(result.success)
            assertEquals(0, env.database.readAllMessages().size, "DB 必须回滚")
            assertEquals(0, env.database.readAllSessions().size)
            assertEquals(0, env.database.readAllSessions().size)
            assertEquals(0, store.fileCount(), "新增媒体必须清理")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance_mediaStagingFailureLeavesDbUntouched() = runBlocking {
        val env = createEnv()
        try {
            val failingSink = object : ChatImportMediaSink {
                var cleaned = false
                override fun stage(media: List<ChatExportMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String> =
                    throw IllegalStateException("注入媒体写入失败")
                override fun cleanup() { cleaned = true }
            }
            val result = restorer(env, failingSink).import(ChatImportGoldens.load("chat-image-v1.zip"))
            assertFalse(result.success)
            assertTrue(failingSink.cleaned, "staging 失败也必须 cleanup")
            assertEquals(0, env.database.readAllMessages().size)
            assertEquals(0, env.database.readAllSessions().size)
        } finally {
            env.close()
        }
    }

    private fun regexIds(raw: String): List<Long> =
        Regex("\"(?:id|messageId)\":(\\d+)").findAll(raw).map { it.groupValues[1].toLong() }.toList()
}
