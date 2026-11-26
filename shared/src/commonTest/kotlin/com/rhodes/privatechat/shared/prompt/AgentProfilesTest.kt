package com.rhodes.privatechat.shared.prompt

import com.rhodes.privatechat.shared.settings.AgentProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 迁移自 app/src/test/.../AgentProfilesTest.kt（2 例；AgentProfiles 已在 shared/settings 原地）。 */
class AgentProfilesTest {
    @Test
    fun supportAgentsHaveDistinctFixedRoutines() {
        assertEquals("商业街", AgentProfiles.routineAt("nuan", 18, 2).location)
        assertEquals("资料室", AgentProfiles.routineAt("yu", 10, 2).location)
        assertEquals("健身房", AgentProfiles.routineAt("chuan", 6, 2).location)
        assertEquals("房间", AgentProfiles.routineAt("tuan", 2, 2).location)
    }

    @Test
    fun weekendRoutineCanDifferFromWeekdayRoutine() {
        val weekday = AgentProfiles.routineAt("tuan", 19, 2)
        val weekend = AgentProfiles.routineAt("tuan", 19, 7)

        assertTrue(weekday.location != weekend.location)
    }
}
