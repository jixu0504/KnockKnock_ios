package com.rhodes.privatechat.shared.domain.chat.group

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.chat.TurnDbTestEnv

/** JVM 路径（:shared:testDebugUnitTest）：sqldelight-sqlite 内存驱动。 */
class GroupTurnStateMachineTestJvm : GroupTurnStateMachineTest() {
    override fun createEnv(): TurnDbTestEnv {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RhodesDatabase.Schema.create(driver)
        return emptyEnv(driver)
    }
}
