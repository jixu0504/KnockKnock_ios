package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B7：印象页展示过滤（L1 门槛/时间窗、L2/L3 有效性、同主题去重）。 */
class ImpressionFilterTest {

    private val now = 1_800_000_000_000L

    private fun item(
        id: Long,
        level: MemoryLevel,
        type: String = "preference_expression",
        importance: Int = 60,
        createdAt: Long = now - 1_000,
        expiresAt: Long = Long.MAX_VALUE,
        source: MemorySourceKind = MemorySourceKind.PRIVATE_CHAT,
        ownerId: String = "op1",
        topicKey: String = "",
        status: String = "active",
    ) = MemoryItem(
        id = id,
        ownerType = "operator",
        ownerId = ownerId,
        memoryLevel = level,
        memoryType = type,
        sourceKind = source,
        content = "印象$id",
        importance = importance,
        privacy = "private",
        createdAt = createdAt,
        updatedAt = createdAt,
        expiresAt = expiresAt,
        status = status,
        topicKey = topicKey,
    )

    @Test
    fun l1NeedsImportanceAndThirtyDayWindow() {
        val kept = item(1, MemoryLevel.L1, importance = 40, createdAt = now - 29 * 86_400_000L)
        val low = item(2, MemoryLevel.L1, importance = 39)
        val old = item(3, MemoryLevel.L1, importance = 90, createdAt = now - 31 * 86_400_000L)
        val wrongType = item(4, MemoryLevel.L1, type = "event", importance = 90)
        val selected = ImpressionFilter.selectCurrent(listOf(kept, low, old, wrongType), now)
        assertEquals(listOf(1L), selected.map { it.id })
    }

    @Test
    fun l2AndL3RequireValidityAndImportance() {
        val l2 = item(1, MemoryLevel.L2, importance = 60)
        val l2low = item(2, MemoryLevel.L2, importance = 59)
        val l2expired = item(3, MemoryLevel.L2, importance = 80, expiresAt = now - 1)
        val l3 = item(4, MemoryLevel.L3, type = "stable_impression", importance = 30)
        val l3expired = item(5, MemoryLevel.L3, type = "stable_impression", expiresAt = now - 1)
        val selected = ImpressionFilter.selectCurrent(listOf(l2, l2low, l2expired, l3, l3expired), now)
        assertEquals(setOf(1L, 4L), selected.map { it.id }.toSet())
    }

    @Test
    fun onlyPrivateChatSourcesAreShown() {
        val privateChat = item(1, MemoryLevel.L3, type = "stable_impression")
        val group = item(2, MemoryLevel.L3, type = "stable_impression", source = MemorySourceKind.GROUP_CHAT)
        val moment = item(3, MemoryLevel.L3, type = "stable_impression", source = MemorySourceKind.MOMENT)
        val selected = ImpressionFilter.selectCurrent(listOf(privateChat, group, moment), now)
        assertEquals(listOf(1L), selected.map { it.id })
    }

    @Test
    fun sameTopicKeepsHighestLevelFirst() {
        val l1 = item(1, MemoryLevel.L1, importance = 70, topicKey = "preference:吃辣")
        val l3 = item(2, MemoryLevel.L3, type = "stable_impression", topicKey = "preference:吃辣")
        val other = item(3, MemoryLevel.L1, importance = 70, topicKey = "preference:清淡")
        val selected = ImpressionFilter.selectCurrent(listOf(l1, l3, other), now)
        assertEquals(listOf(2L, 3L), selected.map { it.id }, "同主题保留 L3；不同主题各自保留")
        assertTrue(selected.first().memoryLevel == MemoryLevel.L3)
    }

    @Test
    fun sameTopicUnderDifferentCharactersIsKept() {
        val a = item(1, MemoryLevel.L3, type = "stable_impression", ownerId = "op1", topicKey = "relation:x")
        val b = item(2, MemoryLevel.L3, type = "stable_impression", ownerId = "op2", topicKey = "relation:x")
        assertEquals(2, ImpressionFilter.selectCurrent(listOf(a, b), now).size)
    }
}