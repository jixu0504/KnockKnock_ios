package com.rhodes.privatechat.shared.domain.media

/**
 * 平台真实临时目录 MediaStore（commonTest 声明；androidUnitTest = java.io 临时目录、
 * iosTest = NSTemporaryDirectory 实现）。目录清理/幂等创建类测试用它跑真文件系统。
 */
interface TestDirMediaStore : MediaStore {

    /** media 根下所有文件（含子目录内）的相对路径列表。 */
    fun relativeFiles(): List<String>

    /** 在 media 根之外创建可读源文件，返回绝对路径（供 readSourceBytes 读取）。 */
    fun createSourceFile(name: String, bytes: ByteArray): String

    /** 清理整个临时基目录。 */
    fun cleanup()
}
