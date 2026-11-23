package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.GroupMsgResult
import com.rhodes.privatechat.shared.model.GroupTurnState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群聊成员【心情】：解析归属、清洗截断、快照与“本轮缺心情就沿用上一轮”的 6 小时回退。
 *
 * 迁移自 `app/src/test/java/com/rhodes/privatechat/GroupEmotionTest.kt`（同一批断言，仅调整 import/包名）；
 * 其中 3 例依赖 app UI 层 `MessageParser`（气泡展示），按「app 侧原用例不动」保留在 app。
 */
class GroupEmotionTest {

    private val members = mapOf(
        "amiya" to GroupTaggedReplyParser.SpeakerIdentity("阿米娅", "amiya"),
        "blaze" to GroupTaggedReplyParser.SpeakerIdentity("煌", "blaze")
    )

    private fun parse(raw: String) = GroupTaggedReplyParser.extractTagged(raw) { members[it] }

    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    // === 一、解析与归属 ===

    @Test
    fun emotionIsAttributedToTheNearestSpeaker() {
        val results = parse(
            """
            【发言人: amiya】
            【心情】担忧
            今天也一起走吧。

            【发言人: blaze】
            【心情】不耐烦
            随便。
            """.trimIndent()
        )
        assertEquals(2, results.size)
        assertEquals("阿米娅", results[0].speaker)
        assertEquals("amiya", results[0].speakerId)
        assertEquals("担忧", results[0].emotion)
        assertEquals("今天也一起走吧。", results[0].message)
        assertEquals("煌", results[1].speaker)
        assertEquals("blaze", results[1].speakerId)
        assertEquals("不耐烦", results[1].emotion)
        assertEquals("随便。", results[1].message)
    }

    @Test
    fun missingEmotionLeavesDialogueIntact() {
        val results = parse(
            """
            【发言人: amiya】
            只有台词，没有心情标签。
            """.trimIndent()
        )
        assertEquals(1, results.size)
        assertEquals("", results[0].emotion)
        assertEquals("只有台词，没有心情标签。", results[0].message)
        assertEquals("amiya", results[0].speakerId)
    }

    @Test
    fun emotionInsideNarrationIsDroppedAndNarrationKeepsItsText() {
        val results = parse(
            """
            【旁白】
            他把杯子放下。
            【心情】烦躁
            然后转过身。

            【发言人: amiya】
            【心情】担忧
            台词。
            """.trimIndent()
        )
        assertEquals(2, results.size)
        assertEquals("narration", results[0].type)
        assertEquals("", results[0].emotion)
        assertEquals("他把杯子放下。\n然后转过身。", results[0].message)
        assertEquals("担忧", results[1].emotion)
    }

    @Test
    fun emotionWrittenForThePlayerIsDropped() {
        val results = parse(
            """
            【发言人: 我】
            【心情】得意
            我说了一句话。
            """.trimIndent()
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun emotionWithoutAnySpeakerIsDropped() {
        val results = parse(
            """
            【心情】不安

            【旁白】
            场景描述。
            """.trimIndent()
        )
        assertEquals(1, results.size)
        assertEquals("", results[0].emotion)
        assertEquals("场景描述。", results[0].message)
    }

    // === 二、容错写法 ===

    @Test
    fun emotionOnTheSameLineAsSpeakerIsRead() {
        val results = parse("【发言人: amiya】【心情】不悦 你到底想干什么")
        assertEquals(1, results.size)
        assertEquals("不悦", results[0].emotion)
        assertEquals("你到底想干什么", results[0].message)
    }

    @Test
    fun emotionWrittenAfterTheDialogueIsRead() {
        val results = parse(
            """
            【发言人: blaze】
            我不想说话。
            【心情】敷衍
            """.trimIndent()
        )
        assertEquals(1, results.size)
        assertEquals("我不想说话。", results[0].message)
        assertEquals("敷衍", results[0].emotion)
    }

    @Test
    fun firstNonEmptyEmotionWinsAcrossMoodAndEmotionTags() {
        val results = parse(
            """
            【发言人: amiya】
            【情绪】烦躁
            【心情】担忧
            台词。
            """.trimIndent()
        )
        assertEquals("烦躁", results[0].emotion)
        assertEquals("台词。", results[0].message)
    }

    @Test
    fun duplicateEmotionTagsKeepTheFirst() {
        val results = parse(
            """
            【发言人: amiya】
            【心情】烦躁
            【心情】担忧
            台词。
            """.trimIndent()
        )
        assertEquals("烦躁", results[0].emotion)
        assertEquals("台词。", results[0].message)
    }

    @Test
    fun longEmotionIsCutAtPunctuationAndCappedAtTenChars() {
        val punctuated = parse(
            """
            【发言人: amiya】
            【心情】他现在真的很烦躁啊，因为被无视了
            台词。
            """.trimIndent()
        )
        assertEquals("他现在真的很烦躁啊", punctuated[0].emotion)
        assertFalse(punctuated[0].emotion.contains("，"))

        val endless = parse(
            """
            【发言人: amiya】
            【心情】这真的是一个非常非常漫长的情绪描述没有任何标点
            台词。
            """.trimIndent()
        )
        assertTrue(endless[0].emotion.length <= GroupEmotionRules.EMOTION_MAX_CHARS, "超长心情必须截到 10 字")
        assertFalse(endless[0].emotion.any { it in "。！？，；、" })
    }

    @Test
    fun blankAndPunctuationOnlyEmotionMeansMissing() {
        assertEquals("", GroupEmotionRules.cleanEmotion(""))
        assertEquals("", GroupEmotionRules.cleanEmotion("   "))
        assertEquals("", GroupEmotionRules.cleanEmotion("【】"))
        assertEquals("", GroupEmotionRules.cleanEmotion("。"))
        assertEquals("不悦", GroupEmotionRules.cleanEmotion("“不悦”"))
    }

    // === 三、状态快照与回退 ===

    @Test
    fun snapshotTakesTheLastSegmentOfTheSameMember() {
        val results = listOf(
            GroupMsgResult("阿米娅", "第一段", "dialogue", "担忧", "amiya"),
            GroupMsgResult("阿米娅", "第二段", "dialogue", "放松", "amiya")
        )
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = results,
            previousSnapshot = "",
            previousSnapshotAtMs = 0L,
            previousInheritedKeys = "",
            history = emptyList()
        )
        assertEquals("放松", round.emotions["amiya"])
        assertTrue(round.inheritedKeys.isEmpty())
    }

    @Test
    fun declaredMemberEmotionFieldIsUsedForSpeakingMembersOnly() {
        val results = listOf(GroupMsgResult("阿米娅", "台词", "dialogue", "", "amiya"))
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = results,
            declaredSnapshot = "amiya=担忧;blaze=不耐烦",
            previousSnapshot = "",
            previousSnapshotAtMs = 0L,
            previousInheritedKeys = "",
            history = emptyList()
        )
        assertEquals("担忧", round.emotions["amiya"])
        assertTrue(round.inheritedKeys.isEmpty(), "【成员心情】声明的本轮值不算回退")
        assertFalse(round.emotions.containsKey("blaze"), "本轮没发言的成员不写入快照")
    }

    @Test
    fun lastSegmentWithoutEmotionFallsBackToThePreviousSnapshot() {
        val results = listOf(
            GroupMsgResult("阿米娅", "第一段", "dialogue", "担忧", "amiya"),
            GroupMsgResult("阿米娅", "第二段", "dialogue", "", "amiya")
        )
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = results,
            previousSnapshot = "amiya=不悦",
            previousSnapshotAtMs = now - hour,
            previousInheritedKeys = "",
            history = emptyList()
        )
        assertEquals("不悦", round.emotions["amiya"])
        assertTrue(round.inheritedKeys.contains("amiya"))
    }

    @Test
    fun fallbackWindowIsSixHours() {
        assertEquals(6L * 60L * 60L * 1000L, GroupEmotionRules.FALLBACK_WINDOW_MS)
        assertTrue(GroupEmotionRules.isFallbackWithinWindow(now, now - 5 * hour))
        assertTrue(GroupEmotionRules.isFallbackWithinWindow(now, now - 6 * hour))
        assertFalse(GroupEmotionRules.isFallbackWithinWindow(now, now - 7 * hour))
        assertFalse(GroupEmotionRules.isFallbackWithinWindow(now, 0L))
    }

    @Test
    fun historyFallbackRespectsTheSixHourWindow() {
        val recent = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "担忧", now - 5 * hour))
        assertEquals(
            "担忧",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, emptyMap(), 0L, emptySet(), recent)
        )
        val stale = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "担忧", now - 7 * hour))
        assertEquals(
            "",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, emptyMap(), 0L, emptySet(), stale)
        )
    }

    @Test
    fun snapshotFallbackUsesTheSnapshotTimestamp() {
        val snapshot = mapOf("amiya" to "不悦")
        assertEquals(
            "不悦",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, snapshot, now - hour, emptySet(), emptyList())
        )
        assertEquals(
            "",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, snapshot, now - 8 * hour, emptySet(), emptyList())
        )
    }

    @Test
    fun inheritedSnapshotValueIsDroppedWhenItsRealOriginIsTooOld() {
        val snapshot = mapOf("amiya" to "不悦")
        val stale = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "不悦", now - 7 * hour))
        assertEquals(
            "",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, snapshot, now - hour, setOf("amiya"), stale)
        )
        val stillValid = listOf(GroupEmotionRules.TrackedEmotion("amiya", "阿米娅", "不悦", now - 2 * hour))
        assertEquals(
            "不悦",
            GroupEmotionRules.resolveFallbackEmotion("amiya", "阿米娅", now, snapshot, now - hour, setOf("amiya"), stillValid)
        )
    }

    @Test
    fun membersWithoutSpeechInThisRoundNeverFallBack() {
        val results = listOf(GroupMsgResult("阿米娅", "台词", "dialogue", "", "amiya"))
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = results,
            previousSnapshot = "amiya=担忧;blaze=不耐烦",
            previousSnapshotAtMs = now - hour,
            previousInheritedKeys = "",
            history = emptyList()
        )
        assertEquals("担忧", round.emotions["amiya"])
        assertFalse(round.emotions.containsKey("blaze"), "本轮没发言的成员不参与回退")
    }

    @Test
    fun narrationOnlyRoundHasNoEmotionSnapshot() {
        val results = listOf(GroupMsgResult("旁白", "场景描述。", "narration", "紧张", ""))
        val round = GroupEmotionRules.resolveRoundEmotions(
            nowMs = now,
            results = results,
            declaredSnapshot = "amiya=担忧",
            previousSnapshot = "",
            previousSnapshotAtMs = 0L,
            previousInheritedKeys = "",
            history = emptyList()
        )
        assertTrue(round.emotions.isEmpty())
        assertTrue(round.inheritedKeys.isEmpty())
    }

    @Test
    fun legacyRowsWithoutSpeakerIdMatchByDisplayName() {
        val legacy = listOf(GroupEmotionRules.TrackedEmotion("", "阿米娅", "担忧", now - hour))
        assertEquals("担忧", GroupEmotionRules.findRecentEmotion(legacy, "", "阿米娅", now)?.emotion)
        assertEquals("担忧", GroupEmotionRules.findRecentEmotion(legacy, "amiya", "阿米娅", now)?.emotion)
        // 双方都有标识时只按标识匹配，避免同名误归属
        val other = listOf(GroupEmotionRules.TrackedEmotion("blaze", "阿米娅", "担忧", now - hour))
        assertNull(GroupEmotionRules.findRecentEmotion(other, "amiya", "阿米娅", now))
    }

    @Test
    fun emotionSnapshotRoundTripsWithinSixtyChars() {
        val emotions = linkedMapOf("amiya" to "担忧", "blaze" to "不耐烦")
        val text = GroupEmotionRules.formatEmotionSnapshot(emotions)
        assertEquals("amiya=担忧;blaze=不耐烦", text)
        assertEquals(emotions, GroupEmotionRules.parseEmotionSnapshot(text))

        val longKeys = linkedMapOf(
            "operator_alpha_identifier" to "担忧",
            "operator_beta_identifier" to "不耐烦",
            "operator_gamma_identifier" to "放松"
        )
        val longText = GroupEmotionRules.formatEmotionSnapshot(longKeys)
        assertTrue(longText.length <= GroupEmotionRules.SNAPSHOT_MAX_CHARS, "【成员心情】不超过 60 字")
        longText.split(';').forEach { part -> assertTrue(part.contains('=') && part.substringAfter('=').isNotBlank(), "不能写半截键值对：$part") }
        assertEquals("", GroupEmotionRules.formatEmotionSnapshot(emptyMap()))
    }

    @Test
    fun promptEmotionsMarkInheritedValuesAndHideExpiredOnes() {
        val entries = GroupEmotionRules.promptEmotions(
            nowMs = now,
            snapshot = "amiya=担忧;blaze=不耐烦",
            snapshotAtMs = now - hour,
            inheritedKeys = "blaze",
            history = listOf(GroupEmotionRules.TrackedEmotion("blaze", "煌", "不耐烦", now - 2 * hour))
        )
        assertEquals(listOf("amiya" to false, "blaze" to true), entries.map { it.key to it.inherited })

        val expired = GroupEmotionRules.promptEmotions(
            nowMs = now,
            snapshot = "blaze=不耐烦",
            snapshotAtMs = now - hour,
            inheritedKeys = "blaze",
            history = listOf(GroupEmotionRules.TrackedEmotion("blaze", "煌", "不耐烦", now - 7 * hour))
        )
        assertTrue(expired.isEmpty(), "超过 6 小时的沿用值不再注入")
    }

    // === 四、序列化与兼容 ===

    @Test
    fun legacyGroupMessageJsonWithoutEmotionFieldsStillDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """[{"speaker":"阿米娅","message":"台词","type":"dialogue"}]"""
        val decoded = json.decodeFromString<List<GroupMsgResult>>(legacy)
        assertEquals(1, decoded.size)
        assertEquals("", decoded[0].emotion)
        assertEquals("", decoded[0].speakerId)
        assertEquals("台词", decoded[0].message)
    }

    @Test
    fun groupMessageJsonCarriesEmotionAndSpeakerId() {
        val json = GroupReplyJson.json
        val stored = json.encodeToString(
            listOf(
                GroupMsgResult("阿米娅", "台词", "dialogue", "担忧", "amiya"),
                GroupMsgResult("旁白", "场景描述。", "narration")
            )
        )
        assertTrue(stored.contains("\"emotion\":\"担忧\""))
        assertTrue(stored.contains("\"speakerId\":\"amiya\""))
        assertTrue(stored.contains("\"speakerId\":\"\""), "旁白也带字段但值为空")
        val decoded = json.decodeFromString<List<GroupMsgResult>>(stored)
        assertEquals("担忧", decoded[0].emotion)
        assertEquals("amiya", decoded[0].speakerId)
        assertEquals("", decoded[1].emotion)
    }

    @Test
    fun legacyGroupTurnStateJsonWithoutEmotionFieldsStillDecodes() {
        val state = Json.decodeFromString<GroupTurnState>("""{"currentTopic":"主线","threadStatus":"继续","updatedAt":123}""")
        assertEquals("主线", state.currentTopic)
        assertEquals("", state.memberEmotions)
        assertEquals("", state.emotionFallbackKeys)
    }

    // === 五、输出预算 ===

    @Test
    fun groupOutputBudgetRaisesTheDefaultForRoomyProviders() {
        assertEquals(5_000, GroupOutputBudget.groupMaxOutputTokens("deepseek"))
        assertEquals(5_000, GroupOutputBudget.groupMaxOutputTokens("anthropic"))
        assertNull(GroupOutputBudget.groupMaxOutputTokens("zhipu"), "输出上限接近 4096 的厂商沿用服务端默认值")
        assertNull(GroupOutputBudget.groupMaxOutputTokens("custom"))
    }
}
