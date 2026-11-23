package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.Operator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * tagged 多发言人协议解析矩阵（验收 #2）：
 * 发言人/旁白/心情归属（同行、行后、台词后、与情绪并存、重复取首个、旁白内丢弃、名单外丢弃、
 * 玩家丢弃、裸标识恢复）+ 三级回退 + 归一化白名单。
 *
 * oracle：GroupTaggedReplyParser.kt:48-128、GVM:2099-2121 / :2504-2547 / :2647-2680。
 */
class GroupTaggedReplyParserTest {

    private val amiya = Operator(id = "amiya", name = "阿米娅")
    private val blaze = Operator(id = "blaze", name = "煌")
    private val members = listOf(amiya, blaze)
    private val byId = members.associateBy { it.id }
    private val byName = members.groupBy { it.name }

    private fun tagged(raw: String) = GroupReplyParser.extractTaggedResults(raw, byId, byName, "测试群")

    @Test
    fun taggedBlocksAreParsedPerSpeaker() {
        val results = tagged(
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
    }

    @Test
    fun turnStateLabelsNeverEnterDialogue() {
        val results = tagged(
            """
            【群聊回合状态】
            【当前主线】守在门口
            【用户本轮作用】提问
            【本轮承接】上一轮的约定
            【本轮新增推进】解释了原因
            【主线状态】继续
            【下轮焦点】用户回应
            【成员心情】amiya=担忧
            【发言人: amiya】
            我在听。
            """.trimIndent()
        )
        assertEquals(1, results.size)
        assertEquals("我在听。", results[0].message)
        assertEquals("amiya", results[0].speakerId)
        assertEquals("", results[0].emotion)
    }

    @Test
    fun narrationBlockKeepsTextButDropsEmotion() {
        val results = tagged(
            """
            【旁白】
            他把杯子放下。
            【心情】烦躁
            然后转过身。
            """.trimIndent()
        )
        assertEquals(1, results.size)
        assertEquals("narration", results[0].type)
        assertEquals("", results[0].emotion)
        assertEquals("他把杯子放下。\n然后转过身。", results[0].message)
    }

    @Test
    fun playerAndUnknownMemberBlocksAreDropped() {
        val results = tagged(
            """
            【发言人: 我】
            玩家的台词。
            【发言人: 不明角色】
            名单外台词。
            【发言人: amiya】
            我的台词。
            """.trimIndent()
        )
        assertEquals(1, results.size)
        assertEquals("阿米娅", results[0].speaker)
        assertTrue(results.none { it.speaker == "我" || it.speaker == "不明角色" })
    }

    @Test
    fun emotionOnSameLineAsSpeakerIsRead() {
        val results = tagged("【发言人: amiya】【心情】不悦 你到底想干什么")
        assertEquals(1, results.size)
        assertEquals("不悦", results[0].emotion)
        assertEquals("你到底想干什么", results[0].message)
    }

    @Test
    fun emotionAfterDialogueIsRead() {
        val results = tagged(
            """
            【发言人: blaze】
            我不想说话。
            【心情】敷衍
            """.trimIndent()
        )
        assertEquals("我不想说话。", results[0].message)
        assertEquals("敷衍", results[0].emotion)
    }

    @Test
    fun firstNonEmptyEmotionWinsAcrossMoodAndEmotionTags() {
        val results = tagged(
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
    fun displayNameIsAcceptedOnlyWhenUniqueAndNotGroupName() {
        // 显示名唯一 → 可接受；显示名等于群名 → 拒绝。
        val viaName = tagged("【发言人: 煌】\n台词。")
        assertEquals("blaze", viaName.single().speakerId)
        val groupNamed = listOf(Operator(id = "g1", name = "测试群"), Operator(id = "g2", name = "测试群"))
        val viaAmbiguousGroup = GroupReplyParser.extractTaggedResults(
            "【发言人: 测试群】\n台词。",
            groupNamed.associateBy { it.id },
            groupNamed.groupBy { it.name },
            "测试群",
        )
        assertTrue(viaAmbiguousGroup.isEmpty())
    }

    @Test
    fun bareSpeakerLinesAreRecoveredByName() {
        val recovered = GroupReplyParser.extractSpeakerLines(
            """
            【旁白】场景描述。
            阿米娅：我在听。
            煌：随便。
            """.trimIndent(),
            members,
            "测试群",
        )
        assertEquals(3, recovered.size)
        assertEquals("narration", recovered[0].type)
        assertEquals("阿米娅", recovered[1].speaker)
        assertEquals("我在听。", recovered[1].message)
        assertEquals("煌", recovered[2].speaker)
    }

    @Test
    fun bareStableIdLinesAreRecoveredButGroupNameIsNotASpeaker() {
        val recovered = GroupReplyParser.extractSpeakerLines("amiya：我在听。\n测试群：群名不是发言人", members, "测试群")
        assertEquals(1, recovered.size)
        assertEquals("阿米娅", recovered[0].speaker)
    }

    @Test
    fun normalizeDropsOnlineNarrationAndFirstPersonNarration() {
        val valid = members.map { it.name }.toSet() + "旁白"
        val normalized = GroupReplyParser.normalizeGroupResults(
            listOf(
                com.rhodes.privatechat.shared.model.GroupMsgResult("旁白", "我走进房间。", "narration"),
                com.rhodes.privatechat.shared.model.GroupMsgResult("旁白", "窗外下着雨。", "narration"),
                com.rhodes.privatechat.shared.model.GroupMsgResult("阿米娅", "我在听。", "dialogue"),
                com.rhodes.privatechat.shared.model.GroupMsgResult("不明角色", "台词。", "dialogue"),
            ),
            valid,
            "online",
        )
        assertEquals(1, normalized.size)
        assertEquals("阿米娅", normalized.single().speaker)
    }

    @Test
    fun offlineNarrationKeepsThirdPersonButNotFirstPerson() {
        val valid = members.map { it.name }.toSet() + "旁白"
        val normalized = GroupReplyParser.normalizeGroupResults(
            listOf(
                com.rhodes.privatechat.shared.model.GroupMsgResult("旁白", "我走进房间。", "narration"),
                com.rhodes.privatechat.shared.model.GroupMsgResult("旁白", "窗外下着雨。", "narration"),
            ),
            valid,
            "offline",
        )
        assertEquals(1, normalized.size)
        assertEquals("窗外下着雨。", normalized.single().message)
        assertEquals("", normalized.single().emotion)
    }

    @Test
    fun leakedPrefixesAreStripped() {
        assertEquals("台词内容", GroupReplyParser.stripLeakedSegmentLabel("【台词】台词内容"))
        assertEquals("", GroupReplyParser.stripSpeakerPrefix("没有冒号").first)
        assertEquals("阿米娅", GroupReplyParser.stripSpeakerPrefix("阿米娅：台词").first)
        assertTrue(GroupReplyParser.containsFirstPersonNarration("我推开门。"))
        assertFalse(GroupReplyParser.containsFirstPersonNarration("她说：“我推开门。”"))
    }

    @Test
    fun displayableGateRequiresCurrentMemberDialogue() {
        assertFalse(GroupReplyParser.isDisplayableGroupReply(emptyList()))
        assertFalse(
            GroupReplyParser.isDisplayableGroupReply(
                listOf(com.rhodes.privatechat.shared.model.GroupMsgResult("旁白", "场景。", "narration"))
            )
        )
        assertTrue(
            GroupReplyParser.isDisplayableGroupReply(
                listOf(com.rhodes.privatechat.shared.model.GroupMsgResult("阿米娅", "台词。", "dialogue"))
            )
        )
    }

    @Test
    fun jsonArrayFallbackParsesPlainAndWrappedForms() {
        val plain = """[{"speaker":"阿米娅","message":"台词。","type":"dialogue"}]"""
        assertEquals(1, GroupReplyParser.extractGroupResults(plain).size)

        val wrapped = """{"messages":[{"speaker":"阿米娅","message":"台词。","type":"dialogue"}]}"""
        assertEquals(1, GroupReplyParser.extractGroupResults(wrapped).size)

        val fenced = "```json\n[{\"speaker\":\"阿米娅\",\"message\":\"台词。\",\"type\":\"dialogue\"}]\n```"
        assertEquals(1, GroupReplyParser.extractGroupResults(fenced).size)
    }

    @Test
    fun threeLevelFallbackKeepsWorkingWhenTagsAreMissing() {
        val bare = """
            【群聊回合状态】
            【当前主线】测试
            amiya：我在听。
            煌：随便。
        """.trimIndent()
        val parsed = GroupReplyParser.parseGroupReply(bare, members, "测试群", "online")
        assertEquals(2, parsed.size)
        assertEquals(listOf("阿米娅", "煌"), parsed.map { it.speaker })
    }

    @Test
    fun replayOfRealisticGroupOutputProducesInlineEmotionsAndNarration() {
        val raw = """
            【群聊回合状态】
            【当前主线】雨夜门口
            【用户本轮作用】提问
            【本轮承接】昨晚的约定
            【本轮新增推进】阿米娅解释迟到原因
            【主线状态】继续
            【下轮焦点】用户决定是否进门
            【成员心情】amiya=担忧
            【旁白】
            雨点敲在屋檐上。
            【发言人: amiya】
            【心情】担忧
            你等很久了吧。
            【发言人: blaze】
            我无所谓。
        """.trimIndent()
        val parsed = GroupReplyParser.parseGroupReply(raw, members, "测试群", "offline")
        assertEquals(3, parsed.size)
        assertEquals("narration", parsed[0].type)
        assertEquals("雨点敲在屋檐上。", parsed[0].message)
        assertEquals("担忧", parsed[1].emotion)
        assertEquals("amiya", parsed[1].speakerId)
        assertEquals("", parsed[2].emotion)
        assertEquals(2, GroupReplyParser.visibleGroupMessageCount(parsed, "online"))
    }

    @Test
    fun enrichSpeakerIdsFillsByIdWhenNameIsUnique() {
        val enriched = GroupReplyParser.enrichGroupSpeakerIds(
            listOf(
                com.rhodes.privatechat.shared.model.GroupMsgResult("阿米娅", "台词。", "dialogue"),
                com.rhodes.privatechat.shared.model.GroupMsgResult("旁白", "场景。", "narration"),
            ),
            byName,
        )
        assertEquals("amiya", enriched[0].speakerId)
        assertEquals("", enriched[1].speakerId)
    }

    @Test
    fun failureReasonClassifiesUnknownSpeaker() {
        val reason = GroupReplyParser.groupReplyFailureReason(
            "【发言人: 不明角色】\n台词。",
            members,
            "测试群",
            "online",
        )
        assertTrue(reason.contains("名单外") || reason.contains("不在当前成员中") || reason.contains("解析"), reason)
        assertNull(GroupTurnStatePolicy.parseGroupTurnState("【发言人: amiya】\n台词。"))
    }
}
