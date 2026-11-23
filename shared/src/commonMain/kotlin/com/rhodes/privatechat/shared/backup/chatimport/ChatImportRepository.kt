package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator

/**
 * 聊天导入的仓储依赖面（规格卡契约 B12/B13/B20）。
 *
 * 读操作在事务外（用于合并决策/去重键构建），写操作走 [runImportTransaction] 单事务
 * （生产 = `ChatRepository.runRestoreTransaction`，`shared/data/ChatRepository.kt:55`；测试 = `RhodesDatabase.transaction`）。
 */
interface ChatImportRepository {
    suspend fun getSession(id: String): ChatSession?
    suspend fun getSessionByOperator(operatorId: String): ChatSession?
    suspend fun getMessages(sessionId: String): List<ChatMessage>
    suspend fun getOperator(id: String): Operator?

    /** `chat_messages MAX(id)`（空表 null）；oracle `MessageRepository.kt:296-305` 三源之一的 DB 分量。 */
    suspend fun maxMessageId(): Long?

    suspend fun getArchiveIds(sessionId: String): Set<String>
    suspend fun getHistorySegmentIds(sessionId: String): Set<String>

    suspend fun runImportTransaction(block: RhodesDatabase.() -> Unit)
}

/** 生产适配器：复用 `ChatRepository` 现有子仓储与恢复事务入口。 */
class ChatRepositoryChatImportAdapter(
    private val repository: ChatRepository,
) : ChatImportRepository {

    override suspend fun getSession(id: String): ChatSession? = repository.getSession(id)

    override suspend fun getSessionByOperator(operatorId: String): ChatSession? =
        repository.getSessionByOperator(operatorId)

    override suspend fun getMessages(sessionId: String): List<ChatMessage> =
        repository.getMessagesSync(sessionId)

    override suspend fun getOperator(id: String): Operator? = repository.getOperator(id)

    override suspend fun maxMessageId(): Long? {
        var max: Long? = null
        repository.runRestoreTransaction {
            max = chatMessagesQueries.getMaxId().executeAsOne().MAX
        }
        return max
    }

    override suspend fun getArchiveIds(sessionId: String): Set<String> =
        repository.archives.getArchives(sessionId).mapTo(mutableSetOf()) { it.id }

    override suspend fun getHistorySegmentIds(sessionId: String): Set<String> =
        repository.archives.getHistorySegments(sessionId).mapTo(mutableSetOf()) { it.id }

    override suspend fun runImportTransaction(block: RhodesDatabase.() -> Unit) =
        repository.runRestoreTransaction(block)
}
