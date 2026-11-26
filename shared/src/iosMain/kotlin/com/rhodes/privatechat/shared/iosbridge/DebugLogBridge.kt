package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.platform.IosDebugLogger
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * 调试日志桥（T53）：把 [IosDebugLogger] 的文件日志暴露给 Swift 调试日志页。
 *
 * 数据源：`Application Support/logs/rhodes.log`（主文件）+ 轮转归档 `rhodes-<epochMs>.log`。
 * 只读主文件正文（列表/搜索/导出基于主文件；归档文件只列路径），解析行格式：
 * `2026-11-23T05:44:00.123Z [INFO] [tag] message`。
 *
 * Swift 侧经 `SettingsExtraBridgesKt.makeSettingsExtraBridges()` 拿到本对象（不暴露 Kotlin 集合语义：
 * 明细行以 [DebugLogEntryDto] 列表返回，文件清单以 `\n` 分隔字符串返回）。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "DebugLogBridge")
class DebugLogBridge internal constructor() {

    /** 写一行日志（页面/自检打点用；与 shared 其它模块共用同一份日志文件）。 */
    fun write(level: String, tag: String, message: String) {
        IosDebugLogger.log(level = level, tag = tag, message = message)
    }

    /**
     * 当前日志快照（新的在前）。
     * @param limit 最多返回多少行；<=0 时按 500 处理。
     */
    fun snapshot(limit: Int): DebugLogSnapshotDto {
        val resolvedLimit = if (limit <= 0) 500 else limit
        val text = IosDebugLogger.readCurrentLog()
        val rawLines = if (text.isBlank()) emptyList() else text.split('\n').filter { it.isNotBlank() }
        val parsed = rawLines.map(::parseLine)
        val newestFirst = parsed.asReversed().take(resolvedLimit)
        return DebugLogSnapshotDto(
            entries = newestFirst,
            path = IosDebugLogger.currentLogPath(),
            filesCsv = IosDebugLogger.listLogFiles().joinToString("\n"),
            bytes = IosDebugLogger.currentLogBytes(),
            totalLines = parsed.size,
            truncated = parsed.size > newestFirst.size,
        )
    }

    /** 导出当前日志副本到 `Application Support/exports/`，返回目标绝对路径（无日志/失败返回空串）。 */
    fun exportToFile(): String = IosDebugLogger.exportToFile()

    /** 清空全部日志文件（主文件 + 归档）。返回是否全部删除成功。 */
    fun clearAll(): Boolean = IosDebugLogger.clearAll()

    /** 日志目录与主文件路径（诊断行）。 */
    fun pathsSummary(): String =
        "dir=${IosDebugLogger.logsDirectory()}\ncurrent=${IosDebugLogger.currentLogPath()}\n" +
            "bytes=${IosDebugLogger.currentLogBytes()}\narchives=${IosDebugLogger.listLogFiles().size}"

    // ---------------------------------------------------------------- 行解析

    private fun parseLine(line: String): DebugLogEntryDto {
        // `2026-11-23T05:44:00.123Z [INFO] [tag] message`（缺字段时降级 RAW，保证不丢行）
        val firstSpace = line.indexOf(' ')
        if (firstSpace <= 0) return DebugLogEntryDto(time = "", level = "RAW", tag = "", message = line)
        val time = line.substring(0, firstSpace)
        var rest = line.substring(firstSpace + 1)
        if (!rest.startsWith("[")) return DebugLogEntryDto(time = time, level = "RAW", tag = "", message = rest)
        val levelEnd = rest.indexOf(']')
        if (levelEnd < 0) return DebugLogEntryDto(time = time, level = "RAW", tag = "", message = rest)
        val level = rest.substring(1, levelEnd)
        rest = rest.substring(levelEnd + 1).trimStart()
        if (!rest.startsWith("[")) return DebugLogEntryDto(time = time, level = level, tag = "", message = rest)
        val tagEnd = rest.indexOf(']')
        if (tagEnd < 0) return DebugLogEntryDto(time = time, level = level, tag = "", message = rest)
        val tag = rest.substring(1, tagEnd)
        val message = rest.substring(tagEnd + 1).trimStart()
        return DebugLogEntryDto(time = time, level = level.uppercase(), tag = tag, message = message)
    }
}

/** 单条日志（时间戳 / 级别 / 来源 tag / 正文）。 */
class DebugLogEntryDto(
    val time: String,
    val level: String,
    val tag: String,
    val message: String,
)

/** 日志快照（新的在前；[filesCsv] 为全部日志文件路径，`\n` 分隔）。 */
class DebugLogSnapshotDto(
    val entries: List<DebugLogEntryDto>,
    val path: String,
    val filesCsv: String,
    val bytes: Long,
    val totalLines: Int,
    val truncated: Boolean,
)
