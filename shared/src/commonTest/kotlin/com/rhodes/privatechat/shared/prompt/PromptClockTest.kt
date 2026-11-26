package com.rhodes.privatechat.shared.prompt

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 契约 19：时间注入格式 `yyyy-MM-dd HH:mm（时段）`，Asia/Shanghai，Clock 构造器注入。
 */
class PromptClockTest {

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    @Test
    fun beijingTimeUsesShanghaiZoneAndPeriodLabel() {
        // 2026-06-15T09:30:00Z = 北京时间 17:30（下午）
        val instant = Instant.parse("2026-06-15T09:30:00Z")
        assertEquals("2026-06-15 17:30（下午）", PromptClock.beijingPromptTime(FixedClock(instant), instant))
    }

    @Test
    fun fixedClockInjectionsAreReproducible() {
        val instant = Instant.parse("2026-01-05T01:05:00Z") // 北京时间 09:05（上午）
        val a = PromptClock.beijingPromptTime(FixedClock(instant))
        val b = PromptClock.beijingPromptTime(FixedClock(instant))
        assertEquals(a, b)
        assertEquals("2026-01-05 09:05（上午）", a) // 月份/日期零填充
    }

    @Test
    fun timestampOverridesClockValue() {
        val clock = FixedClock(Instant.parse("2026-06-15T09:30:00Z"))
        val pinned = Instant.parse("2026-12-31T16:00:00Z") // 北京时间 2027-01-01 00:00（凌晨）
        assertEquals("2027-01-01 00:00（凌晨）", PromptClock.beijingPromptTime(clock, pinned))
    }

    @Test
    fun clockNowIsUsedWhenTimestampAbsent() {
        val clock = FixedClock(Instant.parse("2026-03-09T22:59:00Z")) // 北京时间 06:59（清晨）
        assertEquals("2026-03-10 06:59（清晨）", PromptClock.beijingPromptTime(clock))
    }
}
