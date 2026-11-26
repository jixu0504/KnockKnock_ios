package com.rhodes.privatechat.viewmodel

import com.rhodes.privatechat.shared.prompt.PromptModuleDefaults as SharedPromptModuleDefaults

/**
 * App 侧提示词模块默认值入口（T26 接线批 A）。
 *
 * 函数体与字面量单一来源已迁至 `shared/prompt/PromptModuleDefaults.kt`（T18）；本对象仅保留 app 侧
 * 公开签名（`behavior` / `narrationProtocol` / `outputProtocol` / `runtime`）的薄委托，输出与原实现
 * 逐字节一致（对照见 handoff）。
 */
object PromptModuleDefaults {
    fun behavior(type: String, mode: String): String = SharedPromptModuleDefaults.behavior(type, mode)

    fun narrationProtocol(mode: String): String = SharedPromptModuleDefaults.narrationProtocol(mode)

    fun outputProtocol(type: String, mode: String): String =
        SharedPromptModuleDefaults.outputProtocol(type, mode)

    fun runtime(type: String, mode: String): String = SharedPromptModuleDefaults.runtime(type, mode)
}
