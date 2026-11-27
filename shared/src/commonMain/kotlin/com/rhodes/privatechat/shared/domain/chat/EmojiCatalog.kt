package com.rhodes.privatechat.shared.domain.chat

/**
 * 聊天表情目录（「敲敲」绳网表情，19 个）。
 *
 * 设计要点：表情**不进入识图管线**。每个表情自带 [label]（情绪名）与 [description]（画面描述），
 * 发送时只用这两段文字告诉模型「用户发了一个什么表情」，模型按文字语义理解即可，
 * 既不需要视觉模型，也不消耗图片 token。
 *
 * [assetName] 与 iOS 打包资源一一对应（`ios/KnockKnock/Resources/Emoji/emoji_SMS-xx.png`）。
 */
data class ChatEmoji(
    val id: String,
    val assetName: String,
    val label: String,
    val description: String,
)

object EmojiCatalog {

    const val MESSAGE_TYPE = "emoji"
    /** 消息内容里的表情 id 前缀（`emoji:SMS-06`），渲染层据此走本地资源而不是媒体文件。 */
    const val IMAGE_ID_PREFIX = "emoji:"

    val all: List<ChatEmoji> = listOf(
        ChatEmoji("SMS-02", "emoji_SMS-02", "心动", "爱心眼 + 张嘴"),
        ChatEmoji("SMS-03", "emoji_SMS-03", "调皮", "单眼眨眼 + 微笑"),
        ChatEmoji("SMS-04", "emoji_SMS-04", "大笑", "XX 眼 + 咧嘴吐舌"),
        ChatEmoji("SMS-06", "emoji_SMS-06", "生气", "深红底 + 皱眉"),
        ChatEmoji("SMS-07", "emoji_SMS-07", "惊慌", "流泪 + 感叹号"),
        ChatEmoji("SMS-08", "emoji_SMS-08", "无语", "双眼半闭"),
        ChatEmoji("SMS-10", "emoji_SMS-10", "嫌弃", "斜眼眯缝"),
        ChatEmoji("SMS-12", "emoji_SMS-12", "害羞", "鼓腮 + 双手捂脸"),
        ChatEmoji("SMS-13", "emoji_SMS-13", "滑稽", "大白眼眶 + 坏笑"),
        ChatEmoji("SMS-14", "emoji_SMS-14", "疲惫", "闭眼吐舌 + 汗"),
        ChatEmoji("SMS-15", "emoji_SMS-15", "疑惑", "张大嘴 + 问号"),
        ChatEmoji("SMS-16", "emoji_SMS-16", "封口", "大眼 + 叉嘴"),
        ChatEmoji("SMS-17", "emoji_SMS-17", "心虚", "泪眼 + 汗珠"),
        ChatEmoji("SMS-18", "emoji_SMS-18", "得意", "眯眼吐舌"),
        ChatEmoji("SMS-19", "emoji_SMS-19", "惊讶", "圆眼 + 小嘴"),
        ChatEmoji("SMS-21", "emoji_SMS-21", "呆滞", "歪嘴 + 张嘴"),
        ChatEmoji("SMS-22", "emoji_SMS-22", "开心", "微笑眨眼"),
        ChatEmoji("SMS-23", "emoji_SMS-23", "失落", "皱眉 + 下撇嘴"),
        ChatEmoji("SMS-24", "emoji_SMS-24", "崩溃", "灰白脸"),
    )

    private val byId: Map<String, ChatEmoji> = all.associateBy { it.id }

    fun find(id: String): ChatEmoji? = byId[id.trim().uppercase()]

    /** 给模型的文本：只给情绪与画面，不给图片。——「用户发送了一个表情：生气（深红底 + 皱眉）」 */
    fun promptText(emoji: ChatEmoji): String =
        "用户发送了一个表情：${emoji.label}（${emoji.description}）"
}
