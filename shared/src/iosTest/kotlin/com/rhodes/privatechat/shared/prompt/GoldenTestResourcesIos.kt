package com.rhodes.privatechat.shared.prompt

import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile

internal actual fun readTestResource(path: String): String {
    val root = NSBundle.mainBundle.resourcePath ?: error("iOS test bundle resourcePath is null")
    // 候选布局：bundle 根直接放资源，或 Compose/KGP 把 commonTest resources 放在 compose-resources 子目录。
    val candidates = listOf("$root/$path", "$root/compose-resources/$path")
    for (full in candidates) {
        NSData.dataWithContentsOfFile(full)?.let { data ->
            return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                ?: error("iOS test resource not UTF-8: $full")
        }
    }
    // 兜底：按 bundle 资源目录查找（子目录 inDirectory）
    val file = path.substringAfterLast('/')
    val dir = path.substringBeforeLast('/', "")
    NSBundle.mainBundle.pathForResource(
        name = file.substringBeforeLast('.', file),
        ofType = file.substringAfterLast('.', "").ifEmpty { null },
        inDirectory = dir.ifEmpty { null },
    )?.let { p ->
        NSData.dataWithContentsOfFile(p)?.let { data ->
            return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                ?: error("iOS test resource not UTF-8: $p")
        }
    }
    error("iOS test resource missing: $path (tried $candidates + bundle lookup)")
}
