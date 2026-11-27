package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.ChatMessage

/**
 * 抽取前的窗口过滤（58 轮实测缺口）。
 *
 * 用户只是在核对「我住在哪 / 团子几岁」时，模型会把提问本身记成事实
 *（实测生成 4 条「用户连续询问…」的元记忆，污染记忆列表）。纯提问窗口直接跳过：
 * 既省调用，也不留噪声。判定刻意保守——任何一条消息像陈述就照常抽取。
 */
object MemoryIngestFilter {

    private val QUESTION_MARKERS = listOf("什么", "哪", "几", "多少", "吗", "谁", "怎么", "是不是", "有没有")
    /**
     * 陈述性连接词：出现就按正常窗口处理。刻意不收「我喜欢/我住」这类主语短语——
     * 「我喜欢喝什么咖啡？」是标准纯提问，收进来会让过滤失效（实测 6 问窗口未能跳过）。
     */
    private val DECLARATIVE_MARKERS = listOf(
        "其实", "但是", "因为", "所以", "一般", "平时", "最近", "昨天", "明天", "说实话", "顺便",
    )
    const val MAX_QUESTION_LENGTH = 40

    /** 窗口内所有用户消息都是「纯提问」时返回 true。 */
    fun isQuestionOnlyWindow(messages: List<ChatMessage>): Boolean {
        val userMessages = messages.filter { it.isMe }.map { it.content.trim() }.filter { it.isNotBlank() }
        if (userMessages.isEmpty()) return false
        return userMessages.all { looksLikeQuestion(it) }
    }

    fun looksLikeQuestion(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length > MAX_QUESTION_LENGTH) return false
        if (!trimmed.endsWith("？") && !trimmed.endsWith("?")) return false
        // 句中出现句号/叹号说明混入了陈述（「我住在哪？其实在浦东。」这类不算纯提问）。
        if (trimmed.contains("。") || trimmed.contains("！") || trimmed.contains("!")) return false
        if (DECLARATIVE_MARKERS.any { trimmed.contains(it) }) return false
        return QUESTION_MARKERS.any { trimmed.contains(it) }
    }
}
