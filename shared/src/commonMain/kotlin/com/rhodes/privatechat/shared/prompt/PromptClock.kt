package com.rhodes.privatechat.shared.prompt

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 时间注入（契约 19，平台差异白名单）。
 *
 * Android oracle：SharedUtils.kt:765-769 `beijingPromptTime(timestamp)` 用
 * java.util.Calendar + SimpleDateFormat（JVM 专有），格式 `yyyy-MM-dd HH:mm（时段）`，
 * 时区 Asia/Shanghai。iOS 用 kotlinx-datetime 重写（先例：SettingsRepository.kt:1396-1399），
 * Clock 构造器注入供测试固定时间（lead 裁决）。
 */
object PromptClock {

    fun beijingTimeZone(): TimeZone = TimeZone.of("Asia/Shanghai")

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')

    /** 等价 oracle：`yyyy-MM-dd HH:mm（时段）`，Asia/Shanghai；timestamp 为空时取 clock.now()。 */
    fun beijingPromptTime(clock: Clock = Clock.System, timestamp: Instant? = null): String {
        val instant = timestamp ?: clock.now()
        val dt = instant.toLocalDateTime(beijingTimeZone())
        val month = dt.monthNumber
        return "${dt.year}-${pad2(month)}-${pad2(dt.dayOfMonth)} ${pad2(dt.hour)}:${pad2(dt.minute)}（${PromptRender.getTimeOfDay(dt.hour)}）"
    }
}
