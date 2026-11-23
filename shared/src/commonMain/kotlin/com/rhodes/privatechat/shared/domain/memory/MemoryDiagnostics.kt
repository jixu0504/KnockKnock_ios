package com.rhodes.privatechat.shared.domain.memory

/**
 * 记忆域诊断日志平台化（iOS-port，平台差异白名单）。
 *
 * Android oracle 直接调 `com.rhodes.privatechat.util.DebugLogger`（android.util.Log + SharedPreferences，
 * JVM/Android 专有）。shared 层无 UI/存储依赖，此处提供同名最小接口：默认 no-op，app 侧可注入实现
 * （install / installOperations）把记忆管线日志与 operation 时间线接回 DebugLogger，行为与 Android 一致；
 * 调用点与字段语义逐字保留。
 *
 * operation 时间线（T27 接线批 B 恢复）：`beginOperation` / `conversationStep` 原为 no-op 占位，
 * 导致诊断页缺少「私聊/群聊记忆提取」卡片。现扩为可注入的 [MemoryOperationSink]（见 `impl-wiring-b.md`），
 * app 适配器把两个回调原样转发 `DebugLogger.beginOperation` / `DebugLogger.conversationStep`；
 * 未安装 sink 时保持原 no-op 语义（返回空 id、不记录 step），共享层测试路径行为不变。
 */
object MemoryDiagnostics {
    var enabled: Boolean = false
    var allowSensitiveTrace: Boolean = false
    private var sink: ((tag: String, message: String) -> Unit)? = null
    private var operationSink: MemoryOperationSink? = null

    fun install(logSink: ((tag: String, message: String) -> Unit)?) {
        sink = logSink
    }

    /** 安装 operation 时间线宿主持久化端口；传 null 恢复 no-op（测试/未接线宿主）。 */
    fun installOperations(operations: MemoryOperationSink?) {
        operationSink = operations
    }

    fun log(tag: String, message: String) {
        sink?.invoke(tag, message)
    }

    fun trace(tag: String, message: String) {
        if (!allowSensitiveTrace) return
        sink?.invoke(tag, message)
    }

    /** oracle：`DebugLogger.beginOperation(surface, target, mode)`；未安装 sink 时返回空 id（no-op）。 */
    fun beginOperation(surface: String, target: String, mode: String = ""): String =
        operationSink?.beginOperation(surface, target, mode) ?: ""

    /** oracle：`DebugLogger.conversationStep(roundId, surface, stage, status, details)`。 */
    fun conversationStep(roundId: String, surface: String, stage: String, status: String, details: String = "") {
        operationSink?.conversationStep(roundId, surface, stage, status, details)
    }
}

/**
 * 诊断 operation 时间线端口（宿主适配器实现）。
 *
 * 契约与 `DebugLogger` 同名方法一致：
 * - [beginOperation] 创建一个 operation 并返回其 id（同一 id 后续用于 [conversationStep]）；
 * - [conversationStep] 向该 operation 追加阶段记录；`stage="本轮总览"` 时宿主负责结单（result/summary）。
 */
interface MemoryOperationSink {
    fun beginOperation(surface: String, target: String, mode: String = ""): String

    fun conversationStep(roundId: String, surface: String, stage: String, status: String, details: String = "")
}
