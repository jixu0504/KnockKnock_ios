package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.model.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格卡验收 #7-#10（金样 → DB 记录等价 / 媒体落盘+URI 重写 / 失败无残留 / 重复导入幂等）。
 * 同一份 commonTest 跑两条路径：:shared:testDebugUnitTest（JVM 内存驱动）与
 * :shared:iosSimulatorArm64Test（NativeSqliteDriver，iOS 27.2 模拟器 runtime）。
 */
abstract class BackupRestoreGoldenTest {

    /** 平台源集提供：androidUnitTest = JdbcSqliteDriver 内存库 + MapSettings；iosTest = NativeSqliteDriver + NSUserDefaults。 */
    protected abstract fun createEnv(): TestBackupEnv

    private fun readOperators(env: TestBackupEnv) =
        env.database.operatorsQueries.getAllOperators { id, name, title, description, gender, avatarUri, location,
            activity, emotion, intimacy, privatePrompt, groupPrompt, memoryInjection, userRelation, lmb, attack,
            defense, meldPref, activityLevel, voiceName, voiceSpeed, voicePitch ->
            listOf(id, name, title, description, gender, avatarUri, location, activity, emotion,
                intimacy.toString(), privatePrompt, groupPrompt, memoryInjection, userRelation, lmb.toString(),
                attack.toString(), defense.toString(), meldPref, activityLevel.toString(), voiceName, voiceSpeed, voicePitch)
        }.executeAsList()

    private fun readSessions(env: TestBackupEnv) =
        env.database.chatSessionsQueries.getAllSessions { id, operatorId, operatorName, lastMessage, lastTime, mode,
            isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            listOf(id, operatorId, operatorName, lastMessage, lastTime.toString(), mode, isPinned.toString(),
                unreadCount.toString(), members, rules, avatarUri, mutedMembers)
        }.executeAsList()

    private fun readMessages(env: TestBackupEnv): List<ChatMessage> =
        env.database.chatMessagesQueries.getAllMessagesForBackup { id, sessionId, senderId, senderName, content, type,
            mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sessionId, senderId, senderName, content, type, mode, emotion, activity, location,
                narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()

    @Test
    fun acceptance7and8_goldenRestoreWritesEquivalentRowsAndRewritesMedia() = runBlocking {
        val env = createEnv()
        try {
            val coordinator = BackupRestoreCoordinator(env.repository, env.settings)
            val sink = InMemoryMediaSink()
            val progress = mutableListOf<BackupRestoreStage>()
            val result = coordinator.restore(GoldenBackupFixture.zipBytes, sink, onProgress = { progress += it.stage })
            assertTrue(result.success, "恢复失败: ${result.reason}")
            assertEquals(BackupRestoreStage.COMPLETED, result.stage)
            assertTrue(BackupRestoreStage.RESTORING_MEDIA in progress)
            assertTrue(BackupRestoreStage.RESTORING_DATABASE in progress)

            // #7 恢复前后数据等价（1 角色 / 1 会话 / 7 消息，逐字段金样期望）
            val ops = readOperators(env)
            assertEquals(1, ops.size)
            assertEquals("golden", ops[0][0])
            assertEquals("金样演示员", ops[0][1])
            assertEquals("你是金样演示员，用于备份恢复验证。", ops[0][10])
            val sessions = readSessions(env)
            assertEquals(1, sessions.size)
            assertEquals("session_golden", sessions[0][0])
            assertEquals("golden", sessions[0][1])
            val messages = readMessages(env)
            assertEquals(7, messages.size)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), messages.map { it.id })
            assertEquals(listOf("text", "ai_json", "text", "image", "ai_json", "text", "text"), messages.map { it.type })

            // #8 媒体落盘 + URI 重写（msg4 image 与 msg5 ai_json 内嵌引用均被替换为落盘 URL，不再含 Android 路径）
            assertEquals(1, sink.urls.size)
            val mediaId = "local-image/8b9e940c3c9d6d39c4a1dd8c"
            assertEquals("file:///staged/media/images/8b9e940c3c9d6d39c4a1dd8c.png", sink.urls[mediaId])
            assertEquals(GoldenBackupFixture.MEDIA_SHA256, BackupCrypto.sha256Hex(sink.stagedBytes.values.single()))
            val imageContent = messages[3].content
            val aiContent = messages[4].content
            assertTrue(imageContent.contains("file:///staged/media/images/"), imageContent)
            assertFalse(imageContent.contains("data/user/0"), imageContent)
            assertTrue(aiContent.contains("file:///staged/media/images/"), aiContent)
            assertFalse(aiContent.contains("data/user/0"), aiContent)
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance10_repeatedImportIsIdempotent() = runBlocking {
        val env = createEnv()
        try {
            val coordinator = BackupRestoreCoordinator(env.repository, env.settings)
            val first = coordinator.restore(GoldenBackupFixture.zipBytes, InMemoryMediaSink())
            assertTrue(first.success, "首次恢复失败: ${first.reason}")
            val snapshot = Triple(readOperators(env), readSessions(env), readMessages(env))
            val second = coordinator.restore(GoldenBackupFixture.zipBytes, InMemoryMediaSink())
            assertTrue(second.success, "二次恢复失败: ${second.reason}")
            assertEquals(snapshot.first, readOperators(env))
            assertEquals(snapshot.second, readSessions(env))
            assertEquals(snapshot.third, readMessages(env))
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance9a_transactionRollbackLeavesNoResidue() = runBlocking {
        val env = createEnv()
        try {
            assertFailsTransactionRollback(env)
            assertEquals(0, readOperators(env).size)
            assertEquals(0, readSessions(env).size)
            assertEquals(0, readMessages(env).size)
        } finally {
            env.close()
        }
    }

    private fun assertFailsTransactionRollback(env: TestBackupEnv) {
        var threw = false
        try {
            env.database.transaction {
                env.database.operatorsQueries.insertAllOperators(
                    "rollback_probe", "回滚探针", "", "", "", "", "宿舍", "休息", "平静", 0L, "", "", "", "",
                    10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
                )
                throw IllegalStateException("注入失败")
            }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun acceptance9b_mediaStagingFailureLeavesDbUntouchedAndCleansSink() = runBlocking {
        val env = createEnv()
        try {
            // 预置一条角色，验证恢复失败后原数据原样保留
            env.database.operatorsQueries.insertAllOperators(
                "preexisting", "既有角色", "", "", "", "", "宿舍", "休息", "平静", 0L, "", "", "", "",
                10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
            )
            val coordinator = BackupRestoreCoordinator(env.repository, env.settings)
            val failingSink = object : BackupMediaSink {
                var cleaned = false
                override fun stage(media: List<BackupMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String> {
                    throw IllegalStateException("注入 staging 失败")
                }
                override fun cleanup() { cleaned = true }
            }
            val result = coordinator.restore(GoldenBackupFixture.zipBytes, failingSink)
            assertFalse(result.success)
            assertEquals(BackupRestoreStage.FAILED, result.stage)
            assertTrue(failingSink.cleaned, "P2 补强：staging 失败也必须清理部分解出目录")
            val ops = readOperators(env)
            assertEquals(1, ops.size)
            assertEquals("preexisting", ops[0][0])
            assertEquals(0, readSessions(env).size)
            assertEquals(0, readMessages(env).size)
        } finally {
            env.close()
        }
    }

    @Test
    fun acceptance9c_dbFailureAfterStagingCleansMediaAndReplaysSettings() = runBlocking {
        val env = createEnv()
        try {
            env.settings.putString("user_name", "恢复前")
            env.settings.putInt("ai_temperature", 42)
            val failingExecutor = BackupRestoreExecutor { throw IllegalStateException("注入 DB 恢复失败") }
            val coordinator = BackupRestoreCoordinator(env.repository, env.settings, restoreExecutor = failingExecutor)
            val sink = InMemoryMediaSink()
            val result = coordinator.restore(GoldenBackupFixture.zipBytes, sink)
            assertFalse(result.success)
            assertEquals(BackupRestoreStage.FAILED, result.stage)
            assertTrue(sink.cleaned, "DB 失败后媒体目录必须删除（三层回滚第 2 层）")
            assertEquals("恢复前", env.settings.getString("user_name"), "设置快照必须回放（三层回滚第 3 层）")
            assertEquals(42, env.settings.getInt("ai_temperature"))
            assertEquals(0, readOperators(env).size, "DB 必须无残留（三层回滚第 1 层：单事务）")
        } finally {
            env.close()
        }
    }
}
