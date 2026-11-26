package com.rhodes.privatechat.shared.domain.memory

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 记忆域时间平台化（iOS-port，平台差异白名单）。
 *
 * Android oracle 用 `java.text.SimpleDateFormat` + `java.util.TimeZone.getTimeZone("Asia/Shanghai")`
 * （MemoryV2Pipeline.kt:747-749/808-810/841-843/1112-1115、MemoryVectorFormatter.kt:8-10），
 * K/N 无 java.text，按 T18 PromptClock 先例用 kotlinx-datetime 重写；输出格式逐字节一致：
 * `yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd HH:mm` / `yyyy-MM-dd`（Asia/Shanghai，零填充）。
 * Clock 注入由 MemoryV2Pipeline 构造器提供（测试固定时间）；无注入点的纯函数用 [currentTimeMillis]。
 */
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')

internal fun beijingTimestamp(millis: Long, withSeconds: Boolean = true): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
    val base = "${pad(dt.year, 4)}-${pad(dt.monthNumber)}-${pad(dt.dayOfMonth)} ${pad(dt.hour)}:${pad(dt.minute)}"
    return if (withSeconds) "$base:${pad(dt.second)}" else base
}

internal fun beijingDate(millis: Long): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
    return "${pad(dt.year, 4)}-${pad(dt.monthNumber)}-${pad(dt.dayOfMonth)}"
}
