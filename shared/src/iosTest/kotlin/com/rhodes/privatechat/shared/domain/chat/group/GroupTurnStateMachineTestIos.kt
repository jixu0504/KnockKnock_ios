package com.rhodes.privatechat.shared.domain.chat.group

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.chat.TurnDbTestEnv

/** iOS 路径（:shared:iosSimulatorArm64Test，iPhone 17 模拟器）：NativeSqliteDriver（唯一文件名）。 */
class GroupTurnStateMachineTestIos : GroupTurnStateMachineTest() {

    companion object {
        private var counter = 0
    }

    override fun createEnv(): TurnDbTestEnv {
        val dbName = "group_turn_state_machine_test_${platform.posix.getpid()}_${counter++}.db"
        val driver = NativeSqliteDriver(RhodesDatabase.Schema, dbName)
        RhodesDatabase.Schema.create(driver)
        return emptyEnv(driver)
    }
}
