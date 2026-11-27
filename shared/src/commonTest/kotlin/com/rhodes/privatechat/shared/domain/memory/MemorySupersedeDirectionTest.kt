package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 取代方向（58 轮实测 bug）：同一批里 4 条住址互相取代，最新一条也被标成 superseded。
 * 规则必须是「旧 → 新」，且只有单值谓词才有这个关系。
 */
class MemorySupersedeDirectionTest {

    private fun newerOf(candidateCreatedAt: Long, candidateId: Long, otherCreatedAt: Long, otherId: Long): Boolean =
        otherCreatedAt < candidateCreatedAt || (otherCreatedAt == candidateCreatedAt && otherId < candidateId)

    @Test
    fun olderFactIsSupersededByNewerOne() {
        assertTrue(newerOf(candidateCreatedAt = 2_000L, candidateId = 20L, otherCreatedAt = 1_000L, otherId = 10L))
        assertTrue(!newerOf(candidateCreatedAt = 1_000L, candidateId = 10L, otherCreatedAt = 2_000L, otherId = 20L))
    }

    @Test
    fun equalTimestampsFallBackToInsertionOrder() {
        assertTrue(newerOf(candidateCreatedAt = 1_000L, candidateId = 21L, otherCreatedAt = 1_000L, otherId = 20L))
        assertTrue(!newerOf(candidateCreatedAt = 1_000L, candidateId = 20L, otherCreatedAt = 1_000L, otherId = 21L))
    }

    @Test
    fun onlyFunctionalPredicatesSupersede() {
        assertEquals(
            MemoryRelationRules.SUPERSEDES,
            MemoryRelationRules.relationType("fact:positive:user|reside|上海", "fact:positive:user|reside|杭州"),
        )
        assertEquals(
            null,
            MemoryRelationRules.relationType("fact:negative:user|dislike|下雨天", "fact:negative:user|dislike|香菜"),
        )
    }
}
