package com.rhodes.privatechat.util

/**
 * 金样生成专用 DebugLogger 替身（仅 JVM 一次性 harness，不进入产品代码）。
 * baseline oracle MemoryV2Pipeline.kt import `com.rhodes.privatechat.util.DebugLogger`（Android 专有）；
 * 日志对记忆/召回输出无行为影响，这里提供同名最小 no-op 接口让 oracle 源码保持逐字节不变。
 */
object DebugLogger {
    var enabled: Boolean = false
    var allowSensitiveTrace: Boolean = false

    fun log(tag: String, message: String) = Unit
    fun trace(tag: String, message: String) = Unit
    fun beginOperation(surface: String, target: String, mode: String = ""): String = ""
    fun conversationStep(roundId: String, surface: String, stage: String, status: String, details: String = "") = Unit
}
