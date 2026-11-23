package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.security.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS 路径金样资源读取（test bundle resourcePath；KGP 可能把 commonTest resources 放 bundle 根或
 * compose-resources 子目录，与 T18/T30 同款双候选 + bundle 查找兜底）。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun readFullGoldenBytes(name: String): ByteArray {
    val root = NSBundle.mainBundle.resourcePath ?: error("iOS test bundle resourcePath is null")
    val candidates = listOf("$root/$name", "$root/compose-resources/$name")
    for (full in candidates) {
        NSData.dataWithContentsOfFile(full)?.let { return it.toByteArray() }
    }
    val file = name.substringAfterLast('/')
    val dir = name.substringBeforeLast('/', "")
    NSBundle.mainBundle.pathForResource(
        name = file.substringBeforeLast('.', file),
        ofType = file.substringAfterLast('.', "").ifEmpty { null },
        inDirectory = dir.ifEmpty { null },
    )?.let { found -> NSData.dataWithContentsOfFile(found)?.let { return it.toByteArray() } }
    error("iOS test resource missing: $name (tried $candidates + bundle lookup)")
}
