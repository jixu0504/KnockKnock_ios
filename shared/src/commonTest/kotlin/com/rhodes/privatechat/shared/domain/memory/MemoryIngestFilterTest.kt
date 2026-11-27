package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 纯提问窗口不抽取（58 轮实测：查询轮生成了 4 条元记忆）。 */
class MemoryIngestFilterTest {

    private fun me(id: Long, text: String) = ChatMessage(
        id = id, sessionId = "s1", senderId = "user", senderName = "我",
        content = text, timestamp = 1_000L, isMe = true,
    )

    private fun them(id: Long, text: String) = ChatMessage(
        id = id, sessionId = "s1", senderId = "op", senderName = "11号",
        content = text, timestamp = 1_000L, isMe = false,
    )

    @Test
    fun questionOnlyWindowIsSkipped() {
        val window = listOf(
            me(1, "我喜欢喝什么咖啡？"),
            them(2, "美式，不加糖。"),
            me(3, "团子几岁了？"),
            them(4, "档案里没这个名字。"),
            me(5, "我住在哪？"),
        )
        assertTrue(MemoryIngestFilter.isQuestionOnlyWindow(window))
    }

    @Test
    fun declarativeMessageKeepsWindow() {
        val window = listOf(
            me(1, "我住在哪？"),
            me(2, "其实我搬到上海了。"),
        )
        assertFalse(MemoryIngestFilter.isQuestionOnlyWindow(window))
        assertFalse(MemoryIngestFilter.looksLikeQuestion("其实我搬到上海了。"))
    }

    @Test
    fun interrogativeWithoutDeclarativeConnectorIsQuestion() {
        assertTrue(MemoryIngestFilter.looksLikeQuestion("我不吃什么？"))
        assertTrue(MemoryIngestFilter.isQuestionOnlyWindow(listOf(me(1, "我不吃什么？"))))
        // 混入陈述句就要抽取，避免漏掉真信息
        assertFalse(MemoryIngestFilter.looksLikeQuestion("我住在哪？其实在浦东。"))
    }

    @Test
    fun longOrNonInterrogativeMessagesAreNotQuestions() {
        assertFalse(MemoryIngestFilter.looksLikeQuestion("我喜欢喝美式咖啡，不加糖。"))
        assertFalse(MemoryIngestFilter.looksLikeQuestion("我" + "很".repeat(60) + "什么？"))
        assertTrue(MemoryIngestFilter.looksLikeQuestion("我答应过你什么？"))
        assertFalse(
            MemoryIngestFilter.isQuestionOnlyWindow(listOf(them(1, "在吗？"))),
            "只有角色发言的窗口没有用户信息可抽",
        )
    }
}
