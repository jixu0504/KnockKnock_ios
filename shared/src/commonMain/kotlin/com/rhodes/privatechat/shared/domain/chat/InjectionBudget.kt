package com.rhodes.privatechat.shared.domain.chat

/**
 * 记忆注入预算裁剪（审计 X-04 / 计划书 §4.7）：
 * 按**整行**填充，超预算时整行丢弃，绝不输出半句话；若一个块只剩标题行能放下，
 * 则整块丢弃（避免出现「【长期印象】」下面没有任何内容的空标题）。
 */
internal fun mergeContextBlocksWithinBudget(maxChars: Int, vararg blocks: String): String {
    if (maxChars <= 0) return ""
    val builder = StringBuilder()
    for (block in blocks) {
        val trimmed = block.trim()
        if (trimmed.isEmpty() || trimmed == "无") continue
        val lines = trimmed.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) continue
        val accepted = mutableListOf<String>()
        var length = builder.length
        for (line in lines) {
            val extra = if (builder.isEmpty() && accepted.isEmpty()) line.length else line.length + 1
            if (length + extra > maxChars) break
            accepted += line
            length += extra
        }
        if (accepted.isEmpty()) continue
        val headerOnly = accepted.size == 1 && lines.size > 1 && lines.first().startsWith("【")
        if (headerOnly) continue
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(accepted.joinToString("\n"))
    }
    return builder.toString().trim()
}