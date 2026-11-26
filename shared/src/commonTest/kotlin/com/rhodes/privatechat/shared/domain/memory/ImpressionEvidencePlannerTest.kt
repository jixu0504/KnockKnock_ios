package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** T81a：印象证据规划规则（层级权重、排序、8 条上限、同 id 去重）。 */
class ImpressionEvidencePlannerTest {

    private fun item(
        id: Long,
        level: MemoryLevel,
        importance: Int = 50,
    ) = MemoryItem(
        id = id,
        ownerType = "operator",
        ownerId = "op1",
        memoryLevel = level,
        memoryType = "preference_expression",
        sourceKind = MemorySourceKind.PRIVATE_CHAT,
        content = "候选$id",
        importance = importance,
        topicKey = "taste:coffee",
    )

    @Test
    fun weightFollowsMemoryLevelAndHigherLevelComesFirst() {
        val planned = ImpressionEvidencePlanner.plan(
            listOf(
                item(1, MemoryLevel.L1, importance = 90),
                item(2, MemoryLevel.L2, importance = 40),
                item(3, MemoryLevel.L3, importance = 10),
            ),
        )

        assertEquals(listOf(3L, 2L, 1L), planned.map { it.memoryId }, "层级优先于 importance")
        assertEquals(listOf(0.9, 0.7, 0.5), planned.map { it.weight })
        assertEquals(0.9, ImpressionEvidencePlanner.weightFor(MemoryLevel.L3))
        assertEquals(0.7, ImpressionEvidencePlanner.weightFor(MemoryLevel.L2))
        assertEquals(0.5, ImpressionEvidencePlanner.weightFor(MemoryLevel.L1))
    }

    @Test
    fun lowerImportanceLosesWithinSameLevel() {
        val planned = ImpressionEvidencePlanner.plan(
            listOf(
                item(1, MemoryLevel.L2, importance = 30),
                item(2, MemoryLevel.L2, importance = 80),
                item(3, MemoryLevel.L2, importance = 55),
            ),
        )

        assertEquals(listOf(2L, 3L, 1L), planned.map { it.memoryId })
        assertEquals(listOf(0.7, 0.7, 0.7), planned.map { it.weight })
    }

    @Test
    fun capsAtEightEvidenceKeepingTheStrongest() {
        val l1s = (1..9).map { item(it.toLong(), MemoryLevel.L1, importance = it) }
        val candidates = l1s + item(100, MemoryLevel.L3, importance = 1)

        val planned = ImpressionEvidencePlanner.plan(candidates)

        assertEquals(8, planned.size, "证据最多 8 条")
        assertEquals(100L, planned.first().memoryId, "最高层级必须入选")
        assertEquals(
            listOf(9L, 8L, 7L, 6L, 5L, 4L, 3L),
            planned.drop(1).map { it.memoryId },
            "剩余名额按 importance 降序填满",
        )
        assertEquals(
            listOf(100L, 9L, 8L),
            ImpressionEvidencePlanner.plan(candidates, limit = 3).map { it.memoryId },
            "limit 参数可收紧上限且仍按强度取",
        )
    }

    @Test
    fun dedupesSameMemoryIdWithoutEatingSlots() {
        val duplicated = (1..4).map { item(1, MemoryLevel.L1, importance = 10 + it) }
        val others = (2..8).map { item(it.toLong(), MemoryLevel.L1, importance = 50) }

        val planned = ImpressionEvidencePlanner.plan(duplicated + others)

        assertEquals(planned.map { it.memoryId }.distinct(), planned.map { it.memoryId }, "同 id 只能出现一次")
        assertEquals(8, planned.size, "重复条目不得占用名额")
        assertEquals(listOf(1L), planned.filter { it.memoryId == 1L }.map { it.memoryId })
        assertEquals(0.5, planned.single { it.memoryId == 1L }.weight)
    }

    @Test
    fun unsavedItemsAreNotEvidence() {
        val planned = ImpressionEvidencePlanner.plan(
            listOf(
                item(0, MemoryLevel.L3, importance = 99),
                item(-1, MemoryLevel.L2),
                item(7, MemoryLevel.L1),
            ),
        )

        assertEquals(listOf(7L), planned.map { it.memoryId }, "未落库条目没有可引用的 memoryId")
        assertEquals(emptyList(), ImpressionEvidencePlanner.plan(emptyList()))
    }
}