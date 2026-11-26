package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T78 数据基础：事实关系的确定性判定。 */
class MemoryRelationRulesTest {

    private val positive = "fact:positive:用户|prefer|川菜"
    private val negative = "fact:negative:用户|prefer|川菜"
    private val otherObject = "fact:positive:用户|prefer|香菜"

    @Test
    fun samePolaritySupportsOppositeContradicts() {
        assertEquals(MemoryRelationRules.SUPPORTS, MemoryRelationRules.relationType(positive, positive))
        assertEquals(MemoryRelationRules.CONTRADICTS, MemoryRelationRules.relationType(positive, negative))
        assertEquals(MemoryRelationRules.CONTRADICTS, MemoryRelationRules.relationType(negative, positive))
    }

    @Test
    fun differentFactBaseHasNoDeterministicRelation() {
        assertNull(MemoryRelationRules.relationType(positive, otherObject))
        assertNull(MemoryRelationRules.relationType("preference:辣", positive))
        assertNull(MemoryRelationRules.relationType("", positive))
    }

    @Test
    fun oppositeKeyKeepsBaseAndFlipsPolarity() {
        assertEquals(negative, MemoryRelationRules.oppositeKey(positive))
        assertEquals(positive, MemoryRelationRules.oppositeKey(negative))
        assertNull(MemoryRelationRules.oppositeKey("legacy:topic"))
        assertEquals("用户|prefer|川菜", MemoryRelationRules.baseOf(positive))
    }
}
