package com.rhodes.privatechat.shared.prompt

/**
 * 编辑器语法校验与警告（契约 4/6/7，纯函数化移植自 MainViewModel.kt:436-480）。
 *
 * 校验只拦「畸形语法」，不拦语义：白名单外的占位符允许保存（留作未来运行时值/字面指令）。
 */
object PromptSyntaxValidator {

    /** 孤立 `}}`、未闭合 `{{`、空 body、body 含 `{ } \r \n` → 阻断保存（oracle MainViewModel.kt:436-460）。 */
    fun promptSyntaxErrors(template: String): List<String> {
        val errors = mutableListOf<String>()
        var index = 0
        while (index < template.length) {
            val open = template.indexOf("{{", index)
            val close = template.indexOf("}}", index)
            if (close >= 0 && (open < 0 || close < open)) {
                errors += "}} 格式不正确，请使用 {{名称}}。"
                index = close + 2
                continue
            }
            if (open < 0) break
            val end = template.indexOf("}}", open + 2)
            if (end < 0) {
                errors += "{{ 格式不正确，请使用 {{名称}}。"
                break
            }
            val body = template.substring(open + 2, end)
            if (body.isBlank() || body.any { it == '{' || it == '}' || it == '\r' || it == '\n' }) {
                errors += template.substring(open, end + 2) + " 格式不正确，请使用 {{名称}}。"
            }
            index = end + 2
        }
        return errors.distinct().sorted()
    }

    fun validatePromptTemplate(type: String, mode: String, template: String): List<String> = promptSyntaxErrors(template)

    fun validatePromptModule(template: String): List<String> = promptSyntaxErrors(template)

    /**
     * 保存后警告（不阻断）：未知占位符（区分 legacy 兼容键 vs 完全不识别，契约 6）
     * + 系统保留标签（oracle MainViewModel.kt:462-480）。
     */
    fun promptWarnings(type: String, mode: String, template: String): List<String> {
        val allowed = PromptPlaceholderRegistry.allowed(type, mode)
        val unknown = Regex("\\{\\{([^{}\\r\\n]+)\\}\\}").findAll(template)
            .map { it.groupValues[1].trim() }
            .filter { it !in allowed }
            .distinct().sorted()
            .map { PromptPlaceholderRegistry.unsupportedWarning(it) }
        val reserved = when (type) {
            "private" -> setOf("状态", "心情", "位置", "私聊回合状态", "当前主线", "用户本轮作用", "本轮承接", "本轮新增推进", "主线状态", "未收束事项", "本轮简述", "旁白", "台词", "台詞")
            "group" -> setOf("群聊回合状态", "当前主线", "用户本轮作用", "本轮承接", "本轮新增推进", "主线状态", "下轮焦点", "本轮剧情简述", "旁白", "发言人", "台词", "台詞")
            else -> emptySet()
        }
        val usedReserved = Regex("[【\\[［]\\s*([^】\\]］:：]+)").findAll(template)
            .map { it.groupValues[1].trim() }
            .filter { it in reserved }
            .distinct().sorted()
            .map { "模板包含系统解析标签【$it】，这是当前场景的正常协议；不要把它当作自定义状态标签重复使用。自定义状态建议使用（名称：数值）。" }
        return (unknown + usedReserved).toList()
    }
}
