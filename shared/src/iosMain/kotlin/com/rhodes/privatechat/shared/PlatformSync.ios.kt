package com.rhodes.privatechat.shared

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSRecursiveLock

// Phase 0 简化：所有平台锁共用一个可重入全局锁。对共享内存的互斥语义正确，
// 粒度比 JVM 的 per-object 锁粗；若 iOS 侧实测出现锁争用再细化。
@PublishedApi
internal val globalPlatformLock = NSRecursiveLock()

actual inline fun <T> platformSynchronized(lock: Any, block: () -> T): T {
    globalPlatformLock.lock()
    try {
        return block()
    } finally {
        globalPlatformLock.unlock()
    }
}

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
