package com.rhodes.privatechat.shared.testutil

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/** 在 NSTemporaryDirectory 下建唯一临时目录，block 结束后整体清理。 */
@OptIn(ExperimentalForeignApi::class)
fun withTempDir(prefix: String, block: (String) -> Unit) {
    val dir = NSTemporaryDirectory() + prefix + "-" + NSUUID().UUIDString + "/"
    val fm = NSFileManager.defaultManager
    fm.createDirectoryAtPath(dir, true, null, null)
    try {
        block(dir)
    } finally {
        fm.removeItemAtPath(dir, null)
    }
}
