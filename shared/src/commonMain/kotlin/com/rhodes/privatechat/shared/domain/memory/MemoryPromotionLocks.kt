package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.platformSynchronized
import kotlinx.coroutines.sync.Mutex

/**
 * 晋升按 owner 互斥（契约 7）。
 *
 * Android oracle：`ConcurrentHashMap<String, Mutex>`（java.util.concurrent，JVM 专有）。
 * iOS-port 平台化：`MutableMap + platformSynchronized`（先例：shared/vector/MemoryVectorService.kt:20 的
 * AccessOrderLruMap 用 platformSynchronized 保护），锁表按 key 惰性创建，语义一致。
 */
internal object MemoryPromotionLocks {
    private val table = mutableMapOf<String, Mutex>()

    fun lockFor(key: String): Mutex = platformSynchronized(table) { table.getOrPut(key) { Mutex() } }
}
