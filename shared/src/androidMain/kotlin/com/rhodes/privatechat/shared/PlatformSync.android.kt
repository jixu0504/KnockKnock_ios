package com.rhodes.privatechat.shared

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual inline fun <T> platformSynchronized(lock: Any, block: () -> T): T =
    synchronized(lock, block)

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
