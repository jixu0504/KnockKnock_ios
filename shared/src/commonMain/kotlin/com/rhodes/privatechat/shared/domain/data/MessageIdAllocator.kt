package com.rhodes.privatechat.shared.domain.data

import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 消息 ID 分配器（规格卡 message-id-media-spec.md 行为契约 A.1-A.11 的 iOS 复刻）。
 *
 * Android oracle：`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/MessageRepository.kt`
 *   - :46-47   idMutex + 内存高水位 nextMessageId
 *   - :285-289 persisted `max_used_message_id` 的动机 doc comment（防 MAX(id)+1 回退）
 *   - :290-294 persistedMaxUsedMessageId / rememberMaxUsedMessageId
 *   - :296-305 getNextMessageId（三源 max 自愈公式）
 *   - :168/:194/:225/:243-244 发送/恢复携带显式 id 时抬高内存高水位（恢复同时持久化）
 *
 * 契约摘要：
 *   - 分配公式 `next = maxOf(内存高水位 ?: 0, DB MAX(id)+1, persisted+1)`；随后内存高水位 = next+1；
 *     next > persisted 时写回 persisted（:292-294 只在更大时写）。
 *   - DB 查询失败向上抛（oracle :297-304 无 catch），调用方超时语义归调用方（本类无内部超时）。
 *   - 分配由单 Mutex 串行（:296）；分配与插入是两个独立锁区，崩溃可跳号——ID 无稠密性要求，接受。
 *   - 删除消息不回退：deleteSessionMessages/deleteMessage（:307-324）不动 persisted，下次 allocate 仍 > 已删最大值。
 *   - 恢复导入自愈：恢复执行器原样插 id（insertMessageIfAbsent），分配器不感知恢复；DB max 变化即完成对齐。
 *   - settings 为 null 时（Android 构造参数默认 null，:290 `settings?.getLong(...) ?: 0L`）persisted 分量取 0。
 *
 * 与 Android 的一处保守差异（写入 platform 差异白名单「无差异」方向）：
 * `observeUsed(id)` 同时覆盖 oracle :168/:194/:225（仅抬高内存）与 :243-244（抬高内存 + 持久化），
 * 即发送路径比 Android 更早持久化高水位。该差异只会让 persisted 更大，任何 allocate 结果不受影响，
 * 且恢复路径（:244 必须持久化）语义完整。
 *
 * 接口预留 T15 聊天引擎消费形态：`allocate()` / `observeUsed(id)`。客服对话（AiSupportViewModel）
 * 是独立内存计数器空间，不得混用本分配器（契约 11 排除项）。
 */
class MessageIdAllocator(
    private val maxIdSource: MessageMaxIdSource,
    settings: SettingsRepository? = null,
) {

    private val maxIdStore: MaxIdStore = SettingsMaxIdStore(settings)
    private val idMutex = Mutex()
    private var nextMessageId: Long? = null

    /**
     * 分配下一个消息 id，严格递增。oracle：MessageRepository.kt:296-305。
     * DB 查询失败向上抛（Android 同款，无兜底）；无内部超时。
     */
    suspend fun allocate(): Long = idMutex.withLock {
        // oracle :298 —— MAX(id) 为 NULL（空表）时 fromDatabase = 1
        val fromDatabase = (maxIdSource.getMaxId() ?: 0L) + 1
        // oracle :299 —— settings 不可用时 fromPersisted = 1（分量 0）
        val fromPersisted = maxIdStore.get() + 1
        // oracle :300 —— 三源 max 自愈
        val next = maxOf(nextMessageId ?: 0L, fromDatabase, fromPersisted)
        // oracle :301 —— 内存高水位存「下一个未分配 id」（next+1）
        nextMessageId = next + 1
        // oracle :302 —— 只在更大时写回 persisted（rememberMaxUsedMessageId :292-294）
        rememberMaxUsedMessageId(next)
        next
    }

    /**
     * 外部已定 id（发送方自带 id、恢复导入 id）上报分配器，抬高内存高水位。
     * oracle：MessageRepository.kt:168（sendMessage）、:194（sendMessageAndCreateReplyTurn）、
     * :225（sendReplyAndCompleteTurn）、:243-244（restoreMessage，含持久化）。
     * 发送与恢复管线必须调用；不调用只影响安全性（可能回退复用），不影响正确性。
     */
    suspend fun observeUsed(id: Long) = idMutex.withLock {
        if (id <= 0L) return
        nextMessageId = maxOf(nextMessageId ?: 0L, id + 1)
        rememberMaxUsedMessageId(id)
    }

    /** oracle :292-294 —— persisted 只增不减，仅在 id 更大时写。 */
    private fun rememberMaxUsedMessageId(id: Long) {
        if (id > maxIdStore.get()) maxIdStore.put(id)
    }

    companion object {
        /** oracle :290 —— settings 键名 `max_used_message_id`，经 SettingsRepository.getLong/putLong 存取。 */
        const val KEY_MAX_USED_MESSAGE_ID = "max_used_message_id"
    }
}

/** DB `SELECT MAX(id) FROM chat_messages` 抽象。oracle：ChatMessages.sq:40-41。 */
fun interface MessageMaxIdSource {
    /**
     * 当前 chat_messages 最大 id；空表返回 null。
     * DB 查询失败必须向上抛（oracle MessageRepository.kt:296-305 无 catch，无兜底）。
     */
    fun getMaxId(): Long?
}

/** `max_used_message_id` 持久化抽象（settings 层）。 */
interface MaxIdStore {
    /** 读取 persisted 高水位；缺省 0。 */
    fun get(): Long

    /** 写入 persisted 高水位。调用方保证只增不减（oracle :292-294 的判断在 rememberMaxUsedMessageId）。 */
    fun put(value: Long)
}

/** 基于 RhodesDatabase 的 MAX(id) 来源。 */
class DatabaseMessageMaxIdSource(private val db: RhodesDatabase) : MessageMaxIdSource {
    override fun getMaxId(): Long? = db.chatMessagesQueries.getMaxId().executeAsOne().MAX
}

/** 基于 SettingsRepository 的 persisted 高水位存取（settings 为 null 时分量恒为 0，oracle :290）。 */
class SettingsMaxIdStore(private val settings: SettingsRepository?) : MaxIdStore {
    override fun get(): Long =
        settings?.getLong(MessageIdAllocator.KEY_MAX_USED_MESSAGE_ID, 0L) ?: 0L

    override fun put(value: Long) {
        settings?.putLong(MessageIdAllocator.KEY_MAX_USED_MESSAGE_ID, value)
    }
}
