package com.rhodes.privatechat.shared.db

actual class DatabaseWrapper {
    private var injectedDatabase: RhodesDatabase? = null

    /** 测试/诊断注入（仅 iosTest 使用；须在首次访问 [database] 前调用）：绕过默认 Application Support 路径。 */
    internal fun injectForTest(database: RhodesDatabase) {
        injectedDatabase = database
    }

    actual val database: RhodesDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        injectedDatabase ?: RhodesDatabase(DatabaseLocations.createDefaultDriver())
    }
}
