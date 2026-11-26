package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.GroupMsgResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图片/礼物 JSON 契约（验收 #10）：占位→回填(≤800)/status=failed 三态、群礼物 recipientNames、
 * giftPromptText 还原、分段撤回 JSON、历史格式化。
 * oracle：GVM:2281-2368 / :1861-1874 / :2071-2074 / :537-566 / :2009-2046。
 */
class GroupMediaJsonContractTest {

    @Test
    fun imagePlaceholderHasThreeFieldsAndNoStatus() {
        val json = GroupImageJsonContract.placeholder("file://a.png", "  看这个  ")
        val parsed = assertNotNull(GroupImageJsonContract.parse(json))
        assertEquals("file://a.png", parsed.imageUri)
        assertEquals("看这个", parsed.caption)
        assertEquals("", parsed.visionSummary)
        assertFalse(parsed.failed)
        assertFalse(json.contains("\"status\""))
    }

    @Test
    fun visionSummaryIsCappedAtEightHundredChars() {
        val json = GroupImageJsonContract.withVisionSummary("file://a.png", "附言", "识".repeat(1_000))
        val parsed = assertNotNull(GroupImageJsonContract.parse(json))
        assertEquals(800, parsed.visionSummary.length)
        assertFalse(parsed.failed)
    }

    @Test
    fun failedImageCarriesStatusFailed() {
        val json = GroupImageJsonContract.failed("file://a.png", "附言")
        val parsed = assertNotNull(GroupImageJsonContract.parse(json))
        assertTrue(parsed.failed)
        assertTrue(json.contains("\"status\":\"failed\""))
    }

    @Test
    fun garbageImageJsonParsesToNull() {
        assertNull(GroupImageJsonContract.parse("not json"))
        assertNull(GroupImageJsonContract.parse("[]"))
    }

    @Test
    fun imagePromptFormattingExtractsVisibleSummary() {
        val vision = """{"visibleSummary":"桌上有一只猫","sceneQuality":"clear"}"""
        val json = GroupImageJsonContract.withVisionSummary("file://a.png", "看这个", vision)
        val formatted = GroupImageJsonContract.formatForPrompt(json)
        assertTrue(formatted.contains("桌上有一只猫"), formatted)
        assertTrue(formatted.contains("附言：看这个"), formatted)
    }

    @Test
    fun giftPayloadCarriesRecipientsAndPromptTextRestores() {
        val payload = GroupReplyParser.buildGiftPayload("送你们的花", "file://gift.png", "花束", listOf("阿米娅", "煌"))
        assertTrue(payload.contains("\"event\":\"gift\""))
        assertTrue(payload.contains("\"recipientNames\":[\"阿米娅\",\"煌\"]"))
        assertEquals("送你们的花", GroupReplyParser.giftPromptText(payload))
        assertEquals("（用户送出了礼物）", GroupReplyParser.giftPromptText("not json"))
    }

    @Test
    fun segmentRecallMarksOnlyTargetSegmentAndFullRecallReturnsNull() {
        val content = GroupReplyJson.encodeResults(
            listOf(
                GroupMsgResult("阿米娅", "第一段", "dialogue"),
                GroupMsgResult("阿米娅", "第二段", "dialogue"),
            )
        )
        val recalled = assertNotNull(GroupReplyParser.markSegmentRecalled(content, 0))
        assertTrue(GroupReplyParser.isGroupSegmentRecalled(recalled, 0))
        assertFalse(GroupReplyParser.isGroupSegmentRecalled(recalled, 1))
        // 全部段已撤回 → null（调用方退化整条删除）
        assertNull(GroupReplyParser.markSegmentRecalled(recalled, 1))
        assertNull(GroupReplyParser.markSegmentRecalled(content, 9))
    }

    @Test
    fun wrappedJsonSegmentRecallStillWorks() {
        val wrapped = """{"messages":[{"speaker":"阿米娅","message":"台词","type":"dialogue"}]}"""
        assertNull(GroupReplyParser.markSegmentRecalled(wrapped, 0), "唯一段撤回后整条退化")
        assertFalse(GroupReplyParser.isGroupSegmentRecalled(wrapped, 0))
    }

    @Test
    fun historyFormattingSkipsRecalledGiftAndFailedRows() {
        val names = setOf("阿米娅", "煌")
        assertEquals("", GroupReplyParser.formatGroupHistoryForPrompt(message(type = "gift_reply_failed"), names))
        val gift = message(
            type = "gift_hidden",
            content = GroupReplyParser.buildGiftPayload("送你们的花", "file://g.png", "花束", listOf("阿米娅")),
        )
        assertEquals("送你们的花", GroupReplyParser.formatGroupHistoryForPrompt(gift, names))
        val ai = message(
            type = "ai_json",
            content = GroupReplyJson.encodeResults(
                listOf(
                    GroupMsgResult("阿米娅", "台词", "dialogue", "担忧", "amiya"),
                    GroupMsgResult("旁白", "场景。", "narration"),
                )
            ),
        )
        val formatted = GroupReplyParser.formatGroupHistoryForPrompt(ai, names)
        assertTrue(formatted.contains("阿米娅（心情：担忧）：台词"), formatted)
        assertTrue(formatted.contains("旁白：场景。"), formatted)
    }

    @Test
    fun historyFormattingMarksBrokenAiJson() {
        val broken = message(type = "ai_json", content = "不是 JSON")
        assertTrue(GroupReplyParser.formatGroupHistoryForPrompt(broken, setOf("阿米娅")).contains("格式异常"))
    }

    @Test
    fun messagePreviewUsesLastSpeaker() {
        val ai = message(
            type = "ai_json",
            content = GroupReplyJson.encodeResults(
                listOf(GroupMsgResult("阿米娅", "最后一句话", "dialogue"), GroupMsgResult("煌", "收尾", "dialogue"))
            ),
        )
        assertEquals("煌：收尾", GroupReplyParser.messagePreview(ai))
    }

    private fun message(type: String, content: String = "{}") = ChatMessage(
        id = 1L,
        sessionId = "group_test",
        senderName = "测试群",
        content = content,
        type = type,
        mode = "offline",
        timestamp = 1_700_000_000_000L,
        isMe = false,
    )
}
