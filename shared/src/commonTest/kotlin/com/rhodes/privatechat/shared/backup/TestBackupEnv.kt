package com.rhodes.privatechat.shared.backup

import app.cash.sqldelight.db.SqlDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.settings.SettingsRepository

/**
 * 测试环境抽象：commonTest 定义，平台源集（androidUnitTest / iosTest）以具体子类提供驱动与设置实现。
 * 平台无关 → 金样驱动的 #7-#10 测试同一份源码在两条路径上运行。
 */
class TestBackupEnv(
    val driver: SqlDriver,
    val database: RhodesDatabase,
    val repository: BackupRestoreRepository,
    val settings: SettingsRepository,
    private val cleanup: (() -> Unit)? = null,
) {
    fun close() {
        runCatching { driver.close() }
        cleanup?.invoke()
    }
}

/** 内存媒体 sink（测试用；生产 iosMain 用 BackupMediaSinkIos 落 Documents）。 */
class InMemoryMediaSink : BackupMediaSink {
    var cleaned = false
        private set
    val stagedBytes = linkedMapOf<String, ByteArray>()
    val urls = linkedMapOf<String, String>()

    override fun stage(media: List<BackupMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String> {
        for (item in media) {
            val bytes = entryBytes[item.archivePath] ?: throw BackupFormatException("缺少媒体文件：${item.archivePath}")
            stagedBytes[item.archivePath] = bytes
            urls[item.mediaId] = "file:///staged/" + item.archivePath
        }
        if (urls.keys != media.map { it.mediaId }.toSet()) throw BackupFormatException("备份媒体文件不完整，已取消恢复")
        return urls.toMap()
    }

    override fun cleanup() {
        cleaned = true
        stagedBytes.clear()
        urls.clear()
    }
}

/** 基于 RhodesDatabase 的仓储假实现（与生产 ChatRepositoryBackupAdapter 同接口）。 */
class TestBackupRepository(private val database: RhodesDatabase) : BackupRestoreRepository {

    override suspend fun getAllOperatorsSync(): List<Operator> =
        database.operatorsQueries.getAllOperators { id, name, title, description, gender, avatarUri, location, activity,
            emotion, intimacy, privatePrompt, groupPrompt, memoryInjection, userRelation, lmb, attack, defense,
            meldPref, activityLevel, voiceName, voiceSpeed, voicePitch ->
            Operator(
                id, name, title, description, gender, avatarUri, location, activity, emotion, intimacy.toInt(),
                privatePrompt, groupPrompt, memoryInjection, userRelation, lmb.toInt(), attack.toFloat(),
                defense.toFloat(), meldPref, activityLevel.toFloat(), voiceName, voiceSpeed, voicePitch,
            )
        }.executeAsList()

    override suspend fun getAllSessionsSync(): List<ChatSession> =
        database.chatSessionsQueries.getAllSessions { id, operatorId, operatorName, lastMessage, lastTime, mode,
            isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            ChatSession(
                id, operatorId, operatorName, lastMessage, lastTime, mode, isPinned != 0L,
                unreadCount.toInt(), members, rules, avatarUri, mutedMembers,
            )
        }.executeAsList()

    override suspend fun runRestoreTransaction(block: RhodesDatabase.() -> Unit) {
        database.transaction { block(database) }
    }
}
