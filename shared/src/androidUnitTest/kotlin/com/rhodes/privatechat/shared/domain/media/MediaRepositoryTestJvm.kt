package com.rhodes.privatechat.shared.domain.media

/** JVM 路径具体测试（:shared:testDebugUnitTest）：java.io 临时目录。 */
class MediaRepositoryTestJvm : MediaRepositoryTest() {
    override fun createStore(): TestDirMediaStore = JvmTempDirMediaStore()
}
