package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.GroupMsgResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 6 小时回退窗专项（验收 #3 新增矩阵）：窗口边界、声明字段优先、沿用链可溯源、快照格式上限、撤回后重算输入。
 * oracle：GroupEmotionRules.kt:19-28, :183-233, :250-305。
 */
class GroupEmotionFallbackWindowTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun windowBoundaryIsInclusiveAtSixHours() {
        assertTrue(GroupEmotionRules.isFallbackWithinWindow(now, now - GroupEmotionRules.FALLBACK_WINDOW_MS))
        assertFalse(GroupEmotionRules.isFallbackWithinWindow(now, now - GroupEmotionRules.FALLBACK_WINDOW_MS - 1L))
        assertTrue(GroupEmotionRules.isFallbackWithinWindow(now, now + hour), "oracle 无未来时间护栏：负差值 <= 窗口仍可用")
        assertFalse(GroupEmotionRules.isFallbackWithinWindow(now, 0L), "未知时间不可用")
    }

    @Test
    fun declaredFieldBeatsSnapshotAndIsNotMarkedInherited() {
        val results = listOf(GroupMsgResult("阿米娅", "台词", "dialogue", "", "amiya"))
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = results,
            declaredSnapshot = "amiya=担忧",
            previousSnapshot = "amiya=不耐烦",
            previousSnapshotAtMs = now - hour,
            previousInheritedKeys = "",
            history = emptyList(),
        )
        assertEquals("担忧", round.emotions["amiya"])
        assertTrue(round.inheritedKeys.isEmpty())
    }

    @Test
    fun inheritedSnapshotRequiresTraceableOriginInsideWindow() {
        val snapshot = mapOf("amiya" to "不悦")
        val originOutside = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "不悦", now - 7 * hour))
        assertEquals(
            "",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, snapshot, now - hour, setOf("amiya"), originOutside),
        )
        val originInside = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "不悦", now - 6 * hour))
        assertEquals(
            "不悦",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, snapshot, now - hour, setOf("amiya"), originInside),
        )
    }

    @Test
    fun snapshotFormatDropsEntriesThatWouldExceedBudget() {
        val emotions = linkedMapOf<String, String>()
        repeat(GroupEmotionRules.MAX_SNAPSHOT_ENTRIES + 4) { emotions["member_identifier_$it"] = "担忧" }
        val text = GroupEmotionRules.formatEmotionSnapshot(emotions)
        assertTrue(text.length <= GroupEmotionRules.SNAPSHOT_MAX_CHARS)
        assertTrue(text.split(';').size <= GroupEmotionRules.MAX_SNAPSHOT_ENTRIES)
        assertTrue(text.split(';').all { it.contains('=') })
    }

    @Test
    fun parseSnapshotDropsMalformedEntriesAndKeepsFirst() {
        val parsed = GroupEmotionRules.parseEmotionSnapshot("amiya=担忧;bad;blaze:不耐烦;amiya=放松;;=空")
        assertEquals(mapOf("amiya" to "担忧", "blaze" to "不耐烦"), parsed)
    }

    @Test
    fun promptEmotionsUseRealOriginForInheritedValues() {
        val entries = GroupEmotionRules.promptEmotions(
            nowMs = now,
            snapshot = "amiya=担忧;blaze=不耐烦",
            snapshotAtMs = now - hour,
            inheritedKeys = "amiya",
            history = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "担忧", now - 5 * hour)),
        )
        assertEquals(listOf("amiya" to "担忧" to true, "blaze" to "不耐烦" to false), entries.map { it.key to it.emotion to it.inherited })
    }

    @Test
    fun keyListRoundTripsAndCaps() {
        val text = GroupEmotionRules.formatKeyList(listOf("amiya", "blaze"))
        assertEquals(setOf("amiya", "blaze"), GroupEmotionRules.parseKeyList(text))
        val longKeys = (1..20).map { "operator_identifier_$it" }
        assertTrue(GroupEmotionRules.formatKeyList(longKeys).length <= GroupEmotionRules.SNAPSHOT_MAX_CHARS)
    }

    @Test
    fun recallRecomputeDropsRecalledSegmentsFromSnapshot() {
        // 撤回后重算只喂未撤回段（oracle :440-448）：被撤回段的心情不再进快照。
        val content = GroupReplyJson.encodeResults(
            listOf(
                GroupMsgResult("阿米娅", "第一段", "dialogue", "担忧", "amiya"),
                GroupMsgResult("阿米娅", "第二段", "dialogue", "放松", "amiya"),
            ),
        )
        val recalled = GroupReplyParser.markSegmentRecalled(content, 1)
        val remaining = GroupReplyParser.extractGroupResults(recalled.orEmpty())
            .filterIndexed { index, _ -> !GroupReplyParser.isGroupSegmentRecalled(recalled.orEmpty(), index) }
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = remaining,
            previousSnapshot = "",
            previousSnapshotAtMs = 0L,
            previousInheritedKeys = "",
            history = emptyList(),
        )
        assertEquals("担忧", round.emotions["amiya"])
        assertTrue(round.inheritedKeys.isEmpty())
    }
}
