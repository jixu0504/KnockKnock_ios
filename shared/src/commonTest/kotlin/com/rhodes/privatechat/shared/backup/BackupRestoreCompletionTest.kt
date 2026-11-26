package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T30 完整备份恢复补齐验收（规格卡 §验收 #12-#14）：
 *  - 分类选择恢复门禁（未选中类目不删也不插）；
 *  - 恢复后自检（manifest.recordCounts vs DB 计数）；
 *  - v1 单 payload 备份读取恢复等价；
 *  - 失败回滚无残留（复用 T10 三层回滚语义）。
 *
 * 依赖 T10 金样 `GoldenBackupFixture.zipBytes`（1 角色 / 1 会话 / 7 消息）。
 */
abstract class BackupRestoreCompletionTest {

    /** 平台源集提供：JVM = JdbcSqliteDriver 内存库；iOS = NativeSqliteDriver + NSUserDefaults。 */
    protected abstract fun createEnv(): TestBackupEnv

    private fun coordinator(env: TestBackupEnv) = BackupRestoreCoordinatorEx(env.repository, env.settings)

    @Test
    fun acceptance12_selfCheckPassesAfterGoldenRestore() = runBlocking {
        val env = createEnv()
        try {
            val result = coordinator(env).restore(GoldenBackupFixture.zipBytes, InMemoryMediaSink())
            assertTrue(result.success, "恢复失败：${result.reason}")
            assertEquals(BackupRestoreStage.COMPLETED, result.stage)
            val check = assertNotNull(result.selfCheck)
            assertTrue(check.passed, "自检必须通过：${check.mismatches}")
            assertEquals(1 to 1, check.checked["operators"])
            assertEquals(1 to 1, check.checked["sessions"])
            assertEquals(7 to 7, check.checked["messages"])
            assertEquals(0 to 0, check.checked["displayEvents"])
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance12b_selfCheckDetectsMismatch() = runBlocking {
        val env = createEnv()
        try {
            assertTrue(coordinator(env).restore(GoldenBackupFixture.zipBytes, InMemoryMediaSink()).success)
            env.database.chatMessagesQueries.deleteAllMessages()
            val manifest = (BackupFileReader().validate(GoldenBackupFixture.zipBytes) as BackupValidationResult.Valid).manifest
            val check = BackupRestoreSelfCheck().check(manifest, BackupContentSelection.All, env.repository)
            assertFalse(check.passed, "删库后自检必须报 mismatch")
            assertTrue(check.mismatches.any { it.startsWith("messages") }, "mismatch=${check.mismatches}")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance13_selectionGatesChatsRestore() = runBlocking {
        val env = createEnv()
        try {
            // 预置现有角色 + 会话 + 消息；恢复时选择 roles-only（chats=false 且 memories=false）。
            env.database.operatorsQueries.insertAllOperators(
                "existing", "既有角色", "", "", "", "", "宿舍", "休息", "平静", 0L, "", "", "", "",
                10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
            )
            env.database.chatSessionsQueries.insertSession(
                "session_existing", "existing", "既有角色", "保留我", 123L, "online", 0, 0, "", "", "", "",
            )
            env.database.chatMessagesQueries.insertMessage(
                7L, "session_existing", "", "既有角色", "保留我", "text", "online", "", "", "", "", "", 0L, 123L, 0L,
            )
            val selection = BackupContentSelection(
                roles = true, chats = false, memories = false, social = false,
                knowledgeBases = false, extras = false, settings = false, media = true,
            )
            val result = coordinator(env).restore(
                GoldenBackupFixture.zipBytes, InMemoryMediaSink(),
                options = BackupRestoreOptions(contentSelection = selection),
            )
            assertTrue(result.success, "恢复失败：${result.reason}")
            // 未选中 chats：既有会话/消息必须保留，且不得插入金样会话/消息。
            val sessions = env.database.chatSessionsQueries.getAllSessions { id, _, _, _, _, _, _, _, _, _, _, _ -> id }.executeAsList()
            assertEquals(listOf("session_existing"), sessions)
            val messageCount = env.database.chatMessagesQueries.getMessageCount().executeAsOne()
            assertEquals(1L, messageCount)
            // roles 选中但不含 chats：upsert 语义，既有人数保留 + 金样角色新增（不整体替换）。
            val operatorIds = env.database.operatorsQueries.getAllOperators { id, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> id }
                .executeAsList()
            assertTrue("existing" in operatorIds, "roles-only 不得删除现有角色")
            assertTrue("golden" in operatorIds, "roles-only 应插入备份内角色")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance13b_fullSelectionReplacesData() = runBlocking {
        val env = createEnv()
        try {
            env.database.operatorsQueries.insertAllOperators(
                "stale", "陈旧角色", "", "", "", "", "宿舍", "休息", "平静", 0L, "", "", "", "",
                10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
            )
            env.database.chatSessionsQueries.insertSession(
                "session_stale", "stale", "陈旧角色", "", 1L, "online", 0, 0, "", "", "", "",
            )
            val result = coordinator(env).restore(GoldenBackupFixture.zipBytes, InMemoryMediaSink())
            assertTrue(result.success)
            val operatorIds = env.database.operatorsQueries.getAllOperators { id, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> id }
                .executeAsList()
            assertEquals(listOf("golden"), operatorIds, "全选恢复应整体替换角色")
            assertEquals(1L, env.database.chatSessionsQueries.getSessionCount().executeAsOne(), "全选恢复后仅剩金样会话")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance14_v1SinglePayloadBackupRestores() = runBlocking {
        val env = createEnv()
        try {
            val v1 = buildV1Backup()
            val validation = BackupFileReader().validate(v1)
            assertTrue(validation is BackupValidationResult.Valid, "v1 单 payload 备份应可读取：$validation")
            val result = coordinator(env).restore(v1, InMemoryMediaSink())
            assertTrue(result.success, "v1 恢复失败：${result.reason}")
            assertEquals(1, env.repository.getAllOperatorsSync().size)
            assertEquals(1, env.repository.getAllSessionsSync().size)
            val messages = env.database.chatMessagesQueries.getAllMessagesForBackup { id, sessionId, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
                ChatMessage(id, sessionId, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
            }.executeAsList()
            assertEquals(listOf(1L), messages.map { it.id })
            assertEquals("v1 单 payload 消息", messages.single().content)
            val check = assertNotNull(result.selfCheck)
            assertTrue(check.passed, "v1 恢复后自检必须通过：${check.mismatches}")
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance_rollbackOnFailureLeavesNoResidue() = runBlocking {
        val env = createEnv()
        try {
            env.database.operatorsQueries.insertAllOperators(
                "preexisting", "既有角色", "", "", "", "", "宿舍", "休息", "平静", 0L, "", "", "", "",
                10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
            )
            val failingSink = object : BackupMediaSink {
                var cleaned = false
                override fun stage(media: List<BackupMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String> =
                    throw IllegalStateException("注入媒体失败")
                override fun cleanup() { cleaned = true }
            }
            val result = coordinator(env).restore(GoldenBackupFixture.zipBytes, failingSink)
            assertFalse(result.success)
            assertTrue(failingSink.cleaned, "staging 失败必须清理媒体目录")
            assertEquals(listOf("preexisting"), env.repository.getAllOperatorsSync().map { it.id }, "DB 必须无残留")
            assertEquals(0, env.repository.getAllSessionsSync().size)
        } finally {
            env.close()
        }
    }

    // ---- v1 单 payload 备份构造（用纯 Kotlin BackupZipWriter，确定性） ----

    private fun buildV1Backup(): ByteArray {
        val operator = Operator(
            id = "v1_op", name = "v1 角色", title = "", description = "v1", gender = "",
            avatarUri = "", location = "宿舍", activity = "休息", emotion = "平静", intimacy = 0,
            privatePrompt = "", groupPrompt = "", memoryInjection = "", userRelation = "",
            lmb = 10000, attack = 0.5f, defense = 0.5f, meldPref = "medium", activityLevel = 0.5f,
            voiceName = "", voiceSpeed = "", voicePitch = "",
        )
        val session = ChatSession(
            id = "session_v1_op", operatorId = "v1_op", operatorName = "v1 角色",
            lastMessage = "v1 单 payload 消息", lastTime = 1000L,
        )
        val message = ChatMessage(
            id = 1L, sessionId = session.id, senderId = "", senderName = "v1 角色",
            content = "v1 单 payload 消息", type = "text", timestamp = 1000L, isMe = false,
        )
        val payload = BackupPayloadWire(
            content = BackupContentWire(
                type = "full_backup", timestamp = 1750000000000L,
                operators = listOf(operator), sessions = listOf(session), messages = listOf(message),
            ),
        )
        val json = Json { encodeDefaults = true }
        val payloadBytes = json.encodeToString(payload).encodeToByteArray()
        val manifest = BackupManifestWire(
            format = "rhodes-backup", formatVersion = 1, scope = "full", appVersion = "1.14.27",
            schemaVersion = 1, createdAt = 1750000000000L, backupId = "v1-fixture",
            mediaIncluded = false,
            recordCounts = mapOf("operators" to 1, "sessions" to 1, "messages" to 1),
            files = listOf(BackupFileEntryWire("data/backup.json", payloadBytes.size.toLong(), BackupCrypto.sha256Hex(payloadBytes))),
            excludedCategories = emptyList(), layout = "single_payload", pages = emptyList(),
        )
        val manifestBytes = json.encodeToString(manifest).encodeToByteArray()
        return BackupZipWriter.write(
            listOf(
                BackupZipWriter.Entry("data/backup.json", payloadBytes),
                BackupZipWriter.Entry("manifest.json", manifestBytes),
            ),
        )
    }
}
