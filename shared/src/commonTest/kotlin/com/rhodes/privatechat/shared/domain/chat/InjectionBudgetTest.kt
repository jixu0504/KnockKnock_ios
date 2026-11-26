package com.rhodes.privatechat.shared.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** X-04：注入预算按整行裁剪，不输出半句、不留空标题。 */
class InjectionBudgetTest {

    @Test
    fun oversizedBlockIsDroppedByWholeLines() {
        val block = "【与当前话题相关的经历】\n- 用户喜欢吃辣\n- 用户养了一只猫"
        // 只够放标题 + 第一条（不够第二条）
        val budget = "【与当前话题相关的经历】".length + 1 + "- 用户喜欢吃辣".length
        val merged = mergeContextBlocksWithinBudget(budget, block)
        assertEquals("【与当前话题相关的经历】\n- 用户喜欢吃辣", merged)
        assertFalse(merged.endsWith("-"))
    }

    @Test
    fun headerWithoutAnyContentLineIsDropped() {
        val body = "【长期印象】\n- " + "很长的印象".repeat(20)
        val merged = mergeContextBlocksWithinBudget("【长期印象】".length + 1, body)
        assertEquals("", merged, "只剩标题时整块丢弃，避免空标题占预算")
    }

    @Test
    fun budgetIsSharedAcrossBlocks() {
        val first = "【相关经历】\n- 甲\n- 乙"
        val second = "【公开动态】\n- 丙"
        val budget = first.length + 1 + "【公开动态】".length
        val merged = mergeContextBlocksWithinBudget(budget, first, second)
        assertEquals(first, merged, "第二个块只剩标题，应整块丢弃")
        assertTrue(merged.contains("- 乙"))
    }

    @Test
    fun blankAndNoneBlocksAreIgnored() {
        assertEquals("", mergeContextBlocksWithinBudget(100, "", "无", "   "))
        assertEquals("- 甲", mergeContextBlocksWithinBudget(100, "无", "- 甲"))
    }
}