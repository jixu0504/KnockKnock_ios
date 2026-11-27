package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 表情消息契约：语义必须是**纯文字**，不含任何图片数据。
 *
 * 这是「表情不需要识图」的技术保证：模型看到的是「用户发送了一个表情：生气（深红底 + 皱眉）」，
 * 而不是 base64 或图片 URL。
 */
class EmojiMessageContractTest {

    @Test
    fun catalogCoversAllNineteenEmoji() {
        assertEquals(19, EmojiCatalog.all.size)
        assertEquals(19, EmojiCatalog.all.map { it.id }.distinct().size)
        assertTrue(EmojiCatalog.all.all { it.label.isNotBlank() && it.description.isNotBlank() })
        assertEquals("emoji_SMS-06", EmojiCatalog.find("SMS-06")?.assetName)
        assertEquals("生气", EmojiCatalog.find("SMS-06")?.label)
    }

    @Test
    fun promptTextContainsOnlyWords() {
        val emoji = requireNotNull(EmojiCatalog.find("SMS-06"))
        val text = EmojiCatalog.promptText(emoji)
        assertEquals("用户发送了一个表情：生气（深红底 + 皱眉）", text)
        assertFalse(text.contains("base64"), "不得包含图片编码")
        assertFalse(text.contains("data:image"), "不得包含图片 data URL")
        assertFalse(text.contains("http"), "不得包含图片地址")
    }

    @Test
    fun payloadRoundTripsAndCarriesNoVisionSummary() {
        val payload = EmojiMessageContract.encode("SMS-24", "崩溃", "灰白脸")
        val parsed = requireNotNull(EmojiMessageContract.parse(payload))
        assertEquals("SMS-24", parsed.emojiId)
        assertEquals("崩溃", parsed.label)
        assertEquals("emoji:SMS-24", parsed.imageId)
        assertEquals("emoji_SMS-24", parsed.assetName)
        assertFalse(payload.contains("visionSummary"), "表情没有识图摘要字段")
        assertEquals("用户发送了一个表情：崩溃（灰白脸）", EmojiMessageContract.promptText(payload))
    }

    @Test
    fun historyFormatsEmojiAsMeaningNotImage() {
        val message = ChatMessage(
            id = 1L,
            sessionId = "s1",
            senderName = "我",
            content = EmojiMessageContract.encode("SMS-22", "开心", "微笑眨眼"),
            type = EmojiCatalog.MESSAGE_TYPE,
            isMe = true,
        )
        val text = PrivateReplyFormat.formatPrivateHistoryForPrompt(message, DefaultReplyCodec())
        assertEquals("用户发送了一个表情：开心（微笑眨眼）", text)
        assertFalse(text.contains("图片"), "表情不应被当成图片描述")
    }
}
