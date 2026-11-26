package com.rhodes.privatechat.shared.domain.chat

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase

/** JVM 路径（:shared:testDebugUnitTest）：sqldelight-sqlite 内存驱动。 */
class ReplyTurnStateMachineTestJvm : ReplyTurnStateMachineTest() {
    override fun createEnv(): TurnDbTestEnv {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        val store = SqlChatStore(database)
        return TurnDbTestEnv(driver = driver, database = database, store = store, machine = TurnStateMachine(store))
    }
}
