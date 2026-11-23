package com.rhodes.privatechat.shared

import kotlinx.coroutines.CoroutineDispatcher

/**
 * iOS-port 平台化同步原语：
 * - platformSynchronized：Android=JVM 内置 synchronized（inline，非局部 return 语义不变）；iOS=全局 NSRecursiveLock（Phase 0 粗粒度实现）。
 * - ioDispatcher：Android=Dispatchers.IO；iOS=Dispatchers.Default（K/N 无 IO 调度器，IO 为 internal）。
 */
expect inline fun <T> platformSynchronized(lock: Any, block: () -> T): T

expect val ioDispatcher: CoroutineDispatcher
