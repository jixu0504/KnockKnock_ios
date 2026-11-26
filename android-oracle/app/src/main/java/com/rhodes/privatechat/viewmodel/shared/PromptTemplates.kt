package com.rhodes.privatechat.viewmodel.shared

import com.rhodes.privatechat.shared.prompt.PromptTemplates as SharedPromptTemplates

/**
 * App 侧提示词模板入口（T26 接线批 A）。
 *
 * 字面量单一来源已迁至 `shared/prompt/PromptTemplates.kt`（T18）；本对象仅保留 app 侧公开签名的
 * 薄委托，`VERSION` 与 `get(type, mode)` 的分发/文案与原实现逐字节一致（对照见 handoff）。
 */
object PromptTemplates {
    const val VERSION = SharedPromptTemplates.VERSION

    fun get(type: String, mode: String = ""): String = SharedPromptTemplates.get(type, mode)
}
