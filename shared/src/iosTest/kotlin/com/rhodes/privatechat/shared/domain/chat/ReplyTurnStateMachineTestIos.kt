package com.rhodes.privatechat.shared.domain.chat

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase

/** iOS 路径（:shared:iosSimulatorArm64Test，iPhone 17 模拟器）：NativeSqliteDriver（唯一文件名）。 */
class ReplyTurnStateMachineTestIos : ReplyTurnStateMachineTest() {

    companion object {
        private var counter = 0
    }

    override fun createEnv(): TurnDbTestEnv {
        val dbName = "turn_state_machine_test_${platform.posix.getpid()}_${counter++}.db"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        RhodesDatabase.Schema.create(driver)
        val database = RhodesDatabase(driver)
        val store = SqlChatStore(database)
        return TurnDbTestEnv(driver = driver, database = database, store = store, machine = TurnStateMachine(store))
    }
}
