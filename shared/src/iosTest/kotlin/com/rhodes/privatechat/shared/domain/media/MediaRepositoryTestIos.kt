package com.rhodes.privatechat.shared.domain.media

/** iOS 路径具体测试（:shared:iosSimulatorArm64Test）：NSTemporaryDirectory 临时目录。 */
class MediaRepositoryTestIos : MediaRepositoryTest() {
    override fun createStore(): TestDirMediaStore = IosTempDirMediaStore()
}
