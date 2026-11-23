package com.rhodes.privatechat.shared.backup.wire

/** JVM 路径金样资源读取（classpath：`shared/src/androidUnitTest/resources/golden/backup-full/`）。 */
internal actual fun readFullGoldenBytes(name: String): ByteArray {
    val stream = checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream(name)) {
        "JVM test resource missing: $name（先运行 ios-port/golden/backup-full/sync_golden.sh --sync-only）"
    }
    return stream.use { it.readBytes() }
}
