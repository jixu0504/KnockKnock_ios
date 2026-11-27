package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 事实归一化（58 轮实测缺口）：模型把「不加糖 / 每天一杯 / 三岁」写进客体或限定词，
 * 同一件事被拆成 3 个键，晋升组门永远凑不满，L2/印象无法形成。
 */
class MemoryFactNormalizationTest {

    @Test
    fun qualifierVariantsCollapseIntoOneGroupKey() {
        val a = "fact:positive:user|prefer|美式咖啡不加糖"
        val b = "fact:positive:user|prefer|美式咖啡|q=每天一杯"
        val c = "fact:positive:user|prefer|美式咖啡|q=不加糖"
        val keys = setOf(
            MemoryParseRules.groupKeyOf(a),
            MemoryParseRules.groupKeyOf(b),
            MemoryParseRules.groupKeyOf(c),
        )
        assertEquals(1, keys.size, "同一偏好的三种措辞必须归到同一组：$keys")
        assertEquals("fact:positive:user|prefer|美式咖啡", keys.single())
        assertNotEquals(a, c, "精确事实键仍然保留差异（用于证据与冲突）")
    }

    @Test
    fun objectHeadStrippingIsConservative() {
        assertEquals("美式咖啡", MemoryParseRules.objectHeadOf("美式咖啡不加糖"))
        assertEquals("美式咖啡", MemoryParseRules.objectHeadOf("美式咖啡"))
        assertEquals("拉面", MemoryParseRules.objectHeadOf("拉面不要葱"))
        // 剥完不足 2 字就回退原文，避免把客体削空
        assertEquals("辣不要", MemoryParseRules.objectHeadOf("辣不要"))
        assertEquals("雨天", MemoryParseRules.objectHeadOf("雨天"), "没有修饰时不得改动")
    }

    @Test
    fun nonStructuredTopicKeysAreUntouched() {
        assertEquals("event|下班后一起走回家", MemoryParseRules.groupKeyOf("event|下班后一起走回家"))
        assertNull(MemoryParseRules.factBaseOf("event|下班后一起走回家"))
        assertNull(MemoryParseRules.parseTopicKey("legacy:abc"))
    }

    @Test
    fun livingPlaceChangeIsNotTheSameBase() {
        val old = "fact:positive:user|reside|杭州"
        val new = "fact:positive:user|reside|上海浦东|q=最近"
        assertNotEquals(MemoryParseRules.factBaseOf(old), MemoryParseRules.factBaseOf(new))
        assertEquals("reside", MemoryParseRules.parseTopicKey(new)?.predicate)
        assertTrue(MemoryRelationRules.isFunctional("reside"))
    }
}
