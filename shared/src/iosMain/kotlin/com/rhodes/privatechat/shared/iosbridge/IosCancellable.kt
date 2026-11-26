package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.platformSynchronized
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Flow/Cancellable 桥：Kotlin 侧回调式 API 的取消句柄。
 *
 * 由 [ChatEngineBridge.send] 返回；Swift 侧可包成 AsyncStream 的 onTermination，
 * 也可直接在「停止生成」按钮里 cancel()。cancel 幂等。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "IosCancellable")
class IosCancellable internal constructor(
    private val onCancel: () -> Unit,
) {
    private val lock = Any()
    private var cancelled = false

    fun cancel() {
        val shouldRun = platformSynchronized(lock) {
            if (cancelled) false else {
                cancelled = true
                true
            }
        }
        if (shouldRun) onCancel()
    }

    fun isCancelled(): Boolean = platformSynchronized(lock) { cancelled }
}
