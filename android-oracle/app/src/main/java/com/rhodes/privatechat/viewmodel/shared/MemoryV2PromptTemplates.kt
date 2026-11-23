package com.rhodes.privatechat.viewmodel.shared

import com.rhodes.privatechat.shared.prompt.MemoryV2PromptTemplates as SharedMemoryV2PromptTemplates

/**
 * App 侧记忆 V2 提示词模板入口（T26 接线批 A）。
 *
 * 字面量与 `getL1` 分发单一来源已迁至 `shared/prompt/MemoryV2PromptTemplates.kt`（T24）；本对象仅保留
 * app 侧公开签名（`getL1` / `L2` / `L3`）的薄委托，输出与原实现逐字节一致（对照见 handoff）。
 */
object MemoryV2PromptTemplates {
    fun getL1(sourceKind: String): String = SharedMemoryV2PromptTemplates.getL1(sourceKind)

    val L2: String get() = SharedMemoryV2PromptTemplates.L2

    val L3: String get() = SharedMemoryV2PromptTemplates.L3
}
