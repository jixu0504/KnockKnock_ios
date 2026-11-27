@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.OfflineModeResponse
import com.rhodes.privatechat.shared.model.Segment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }

/**
 * 可见回复契约（oracle ChatViewModel.kt:1122-1237）。
 * 唯一硬契约：至少一条非旁白且非空台词（isCompletePrivateReply :1122-1127）；
 * 协议漂移只记日志不拦截（logPrivateReplyStructure :1129-1144）。
 */
object VisibleReplyContract {

    /** oracle :1122-1127。 */
    fun isCompletePrivateReply(parsed: OfflineModeResponse, mode: String): Boolean =
        parsed.segments.orEmpty().any { !it.type.equals("narration", true) && it.content.isNotBlank() }

    /** oracle :1130-1144 —— 返回漂移问题清单（宿主记日志），不影响可见判定。 */
    fun privateReplyStructureIssues(parsed: OfflineModeResponse, mode: String, diaSegMin: Int, diaSegMax: Int, narSegMin: Int, narSegMax: Int): List<String> {
        val segments = parsed.segments.orEmpty().filter { it.content.isNotBlank() }
        val narration = segments.filter { it.type.equals("narration", true) }
        val dialogue = segments.filterNot { it.type.equals("narration", true) }
        val issues = mutableListOf<String>()
        if (mode == "online" && narration.isNotEmpty()) issues += "线上模式包含旁白"
        if (mode != "online") {
            if (segments.firstOrNull()?.type?.equals("narration", true) != true) issues += "首段不是旁白"
            if (segments.lastOrNull()?.type?.equals("narration", true) == true) issues += "末段不是台词"
            if (narration.any { containsFirstPersonNarration(it.content) }) issues += "旁白疑似第一人称"
        }
        if (dialogue.size !in diaSegMin..diaSegMax) issues += "台词段数=${dialogue.size}"
        if (mode != "online" && narration.size !in narSegMin..narSegMax) issues += "旁白段数=${narration.size}"
        return issues
    }

    /** oracle :1205-1223。 */
    fun ensureVisiblePrivateReply(parsed: OfflineModeResponse, mode: String): OfflineModeResponse {
        val source = parsed.segments.orEmpty().mapNotNull { segment ->
            val content = stripLeakedSegmentLabel(segment.content)
            if (content.isBlank()) null else Segment(
                type = if (segment.type.equals("narration", true) || segment.type == "旁白") "narration" else "dialogue",
                content = content,
            )
        }
        if (mode == "online") {
            val dialogue = source.filter { it.type == "dialogue" }
                .ifEmpty { parsed.dialogue.trim().takeIf { it.isNotBlank() }?.let { listOf(Segment("dialogue", it)) }.orEmpty() }
            return if (dialogue.isEmpty()) parsed else parsed.copy(dialogue = "", narration = "", segments = dialogue)
        }
        if (source.isEmpty() || source.none { it.type == "dialogue" }) return parsed.copy(segments = emptyList(), dialogue = "", narration = "")
        return parsed.copy(dialogue = "", narration = "", segments = source)
    }

    /** oracle :1225-1228。 */
    fun stripLeakedSegmentLabel(content: String): String = content.trimStart()
        .replaceFirst(Regex("^【(?:旁白|台词|台詞)(?:[：:])?】\\s*"), "")
        .trimStart()

    /** oracle :1230-1237。 */
    fun containsFirstPersonNarration(content: String): Boolean {
        val outsideQuotes = content
            .replace(Regex("[“\"](?:\\.|[^”\"])*[”\"]"), "")
            .replace(Regex("[‘'](?:\\.|[^’'])*[’']"), "")
            .trimStart()
        return Regex("""^(?:我|我们|咱们|咱|俺)(?:[，。！？、：:；;\s]|$)""").containsMatchIn(outsideQuotes) ||
            Regex("""^(?:我|我们|咱们|咱|俺)(?:正|正要|正准备|正朝|正向|正往|走|站|坐|看|听|拿|放|抬|低|转|靠|停|伸|推|拉|从|在|向|往)""").containsMatchIn(outsideQuotes)
    }

    /** oracle :1063-1067 —— 清洗可见段（剥内部状态卡标签）。 */
    fun sanitizePrivateResponse(response: OfflineModeResponse): OfflineModeResponse =
        response.copy(segments = response.segments.orEmpty().mapNotNull { segment ->
            sanitizeVisibleSegmentContent(segment.content).takeIf { it.isNotBlank() }
                ?.let { segment.copy(content = it) }
        })

    /** oracle :1087-1096。 */
    fun sanitizeVisibleSegmentContent(content: String): String {
        var result = content
        result = result.replace(
            Regex("[【\\[［](?:上一轮状态|当前对话进展|已验证的连续性状态|本轮参考资料|私聊回合状态|当前主线|用户本轮作用|本轮承接|本轮新增推进|主线状态|未收束事项|用户发言意图分析)[】\\]］][\\s\\S]*?(?=[【\\[［](?:状态|心情|位置|本轮简述|旁白|台词|台詞)[】\\]］]|$)"),
            " ",
        )
        result = result.replace(Regex("[【\\[［](?:状态|心情|位置|本轮简述|私聊回合状态|当前主线|用户本轮作用|本轮承接|本轮新增推进|主线状态|未收束事项)[】\\]］][^\\n]*\\n?"), "")
        result = result.replaceFirst(Regex("^\\s*[【\\[［](?:旁白|台词|台詞)(?:[：:])?[】\\]］]\\s*"), "")
        return result.trim()
    }

    /** oracle :1102-1106。 */
    fun visiblePrivateSegmentCount(parsed: OfflineModeResponse, mode: String): Int {
        val segments = parsed.segments.orEmpty().filter { it.content.isNotBlank() }
        return segments.count { !it.type.equals("narration", true) }
    }

    /** oracle :1108-1119。 */
    fun privateReplyFailureReason(parsed: OfflineModeResponse, mode: String): String {
        val segments = parsed.segments.orEmpty().filter { it.content.isNotBlank() }
        val dialogue = segments.count { !it.type.equals("narration", true) }
        val narration = segments.count { it.type.equals("narration", true) }
        return when {
            parsed.state.isBlank() || parsed.emotion.isBlank() || parsed.location.isBlank() || parsed.continuity.isBlank() -> "缺少状态卡四项"
            parsed.turnUserType.isBlank() || parsed.turnAnchor.isBlank() || parsed.turnAdvance.isBlank() || parsed.turnStatus.isBlank() || parsed.turnUnresolved.isBlank() -> "缺少私聊回合状态"
            dialogue == 0 -> "解析后没有可展示台词"
            mode != "online" && narration == 0 -> "线下/导演模式缺少旁白"
            else -> "格式完整：状态字段=完整，回合状态=完整，台词=$dialogue，旁白=$narration"
        }
    }

    /** oracle :2814-2820。 */
    fun replyPreview(parsed: OfflineModeResponse): String {
        if (parsed.dialogue.isNotBlank()) return parsed.dialogue
        return parsed.segments
            ?.filter { it.type == "dialogue" }
            ?.joinToString(" ") { it.content }
            .orEmpty()
    }
}

/**
 * 表情消息 JSON 契约。
 *
 * 与图片消息的区别是**没有 visionSummary**：表情的语义在发送时就已经是文字（情绪名 + 画面），
 * 落库只为回放渲染，提示词侧直接读 [EmojiCatalog.promptText]，全程不调用识图模型。
 */
object EmojiMessageContract {

    data class EmojiMessageJson(
        val emojiId: String,
        val label: String,
        val description: String,
    ) {
        /** 渲染用资源名（与打包资源同名）。 */
        val assetName: String get() = "emoji_$emojiId"
        val imageId: String get() = "${EmojiCatalog.IMAGE_ID_PREFIX}$emojiId"
    }

    fun encode(emojiId: String, label: String, description: String): String = buildJsonObject {
        put("emojiId", JsonPrimitive(emojiId))
        put("label", JsonPrimitive(label))
        put("description", JsonPrimitive(description))
    }.toString()

    fun parse(content: String): EmojiMessageJson? = runCatching {
        val root = json.parseToJsonElement(content).jsonObject
        EmojiMessageJson(
            emojiId = root["emojiId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            label = root["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            description = root["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

    /** 提示词文本：只有文字语义，不含任何图片数据。 */
    fun promptText(content: String): String {
        val parsed = parse(content) ?: return "用户发送了一个表情"
        return "用户发送了一个表情：${parsed.label}（${parsed.description}）"
    }
}

/**
 * 图片消息 JSON 契约（oracle ChatViewModel.kt:1859-1942 + MessageParser.kt:137-157）。
 * 三态：占位（visionSummary=""）→ 回填（visionSummary≤800 字）→ status="failed"（isSendFailed）。
 */
object ImageMessageContract {

    data class ImageMessageJson(
        val imageUri: String,
        val caption: String,
        val visionSummary: String,
        val status: String = "",
    ) {
        val isSendFailed: Boolean get() = status == "failed"
    }

    /** oracle :1859-1863 —— 占位 JSON。 */
    fun placeholder(imageUri: String, caption: String): String = encode(ImageMessageJson(imageUri, caption, ""))

    /** oracle :1922-1927 —— 识图失败标记。 */
    fun failed(imageUri: String, caption: String): String = encode(ImageMessageJson(imageUri, caption, "", status = "failed"))

    /** oracle :1937-1942 —— visionSummary 回填（≤800 字）。 */
    fun withVisionSummary(imageUri: String, caption: String, visionSummary: String): String =
        encode(ImageMessageJson(imageUri, caption, visionSummary.take(800)))

    /** 解析（oracle MessageParser.kt:137-157 的 imageMsg 读取侧）。 */
    fun parse(content: String): ImageMessageJson? = runCatching {
        val root = json.parseToJsonElement(content).jsonObject
        ImageMessageJson(
            imageUri = root["imageUri"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            caption = root["caption"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            visionSummary = root["visionSummary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

    private fun encode(image: ImageMessageJson): String = buildJsonObject {
        put("imageUri", JsonPrimitive(image.imageUri))
        put("caption", JsonPrimitive(image.caption.trim()))
        put("visionSummary", JsonPrimitive(image.visionSummary))
        if (image.status.isNotBlank()) put("status", JsonPrimitive(image.status))
    }.toString()
}

/**
 * 历史/提示词格式化纯函数（oracle ChatViewModel.kt:2822-2923）。
 */
object PrivateReplyFormat {

    /** oracle :2906-2910 —— 礼物载荷还原。 */
    fun giftPromptText(content: String): String = runCatching {
        val root = json.parseToJsonElement(content).jsonObject
        root["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("（用户送出了礼物）")

    /** oracle :2911-2923 —— 图片消息 → 提示词文本。 */
    fun formatImageMessageForPrompt(msg: ChatMessage): String = try {
        val obj = json.parseToJsonElement(msg.content).jsonObject
        val caption = obj["caption"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawSummary = obj["visionSummary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val summary = runCatching {
            json.parseToJsonElement(rawSummary).jsonObject["visibleSummary"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: rawSummary
        buildString {
            append("用户发送图片")
            if (summary.isNotBlank()) append("：${summary.take(200)}")
            if (caption.isNotBlank()) append("；附言：$caption")
        }
    } catch (_: Exception) {
        "用户发送了一张图片"
    }

    /** oracle :2900-2904 —— 分段是否已撤回。 */
    fun isPrivateSegmentRecalled(content: String, index: Int): Boolean = runCatching {
        val root = json.parseToJsonElement(content).jsonObject
        val segments = root["segments"] as? JsonArray ?: return@runCatching false
        segments.getOrNull(index)?.jsonObject?.get("recalled")?.jsonPrimitive?.contentOrNull.equals("true", true)
    }.getOrDefault(false)

    /**
     * oracle :2822-2863 —— 历史消息 → 提示词文本（含撤回过滤、状态卡与台词标签）。
     */
    fun formatPrivateHistoryForPrompt(msg: ChatMessage, codec: ModelOutputCodec): String {
        if (msg.type == "gift_reply_failed") return ""
        if (msg.type == "gift_hidden") return giftPromptText(msg.content)
        if (msg.type == "image" && msg.isMe) return formatImageMessageForPrompt(msg)
        // 表情消息：提示词里只出现文字语义（情绪 + 画面），模型据此理解，不需要看图。
        if (msg.type == EmojiCatalog.MESSAGE_TYPE && msg.isMe) return EmojiMessageContract.promptText(msg.content)
        if (msg.isMe) return "用户：${msg.content.take(500)}"
        if (msg.type != "ai_json") return msg.content.take(500)
        return try {
            val parsed = codec.normalize(msg.content)
            val rawSegments = parsed.segments.orEmpty()
            val segments = rawSegments
                .filterIndexed { index, _ -> !isPrivateSegmentRecalled(msg.content, index) }
                .mapNotNull { segment ->
                    val content = VisibleReplyContract.sanitizeVisibleSegmentContent(segment.content).take(500)
                    content.takeIf { it.isNotBlank() }?.let {
                        if (segment.type.equals("narration", true)) "【旁白】$it" else "【台词】$it"
                    }
                }
            if (rawSegments.isNotEmpty() && segments.isEmpty()) return ""
            segments.joinToString("\n").ifBlank {
                VisibleReplyContract.sanitizeVisibleSegmentContent(parsed.dialogue).take(500)
                    .takeIf { it.isNotBlank() }?.let { "【台词】$it" }
                    ?: "[上一条回复不可用]"
            }
        } catch (_: Exception) {
            "[上一条回复不可用]"
        }
    }

    /** oracle :2116-2122 —— 会话预览。 */
    fun messagePreviewForSession(message: ChatMessage, codec: ModelOutputCodec): String = when {
        message.type == "image" -> "[图片]"
        message.type == "gift_hidden" || message.type == "gift_reply_failed" -> "[礼物]"
        message.type == "ai_json" -> runCatching { VisibleReplyContract.replyPreview(codec.normalize(message.content)) }
            .getOrDefault(message.content.take(50))
        message.type == "system" -> ""
        else -> message.content.take(50)
    }
}

/**
 * 分段撤回改写（oracle ChatViewModel.kt:2269-2321）。
 * 非法参数（非 ai_json / 越界 / 全部已撤回）返回 null → 调用方退化整条撤回。
 */
object ReplySegmentMutation {

    /** oracle :2269-2309。 */
    fun markSegmentRecalled(content: String, segmentIndex: Int): String? = try {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .replace("，", ",").replace("：", ":")
        val element = json.parseToJsonElement(cleaned)
        when (element) {
            is JsonArray -> {
                val list = element.toMutableList()
                if (segmentIndex !in list.indices) return null
                list[segmentIndex] = markRecalled(list[segmentIndex]) ?: return null
                if (list.all { isRecalled(it) }) null
                else list.joinToString(",", "[", "]") { it.toString() }
            }
            is JsonObject -> {
                val segments = element["segments"] as? JsonArray ?: return null
                val list = segments.toMutableList()
                if (segmentIndex !in list.indices) return null
                list[segmentIndex] = markRecalled(list[segmentIndex]) ?: return null
                if (list.all { isRecalled(it) }) null
                else {
                    val newSegments = list.joinToString(",", "[", "]") { it.toString() }
                    val keys = element.keys.filter { it != "segments" }
                    buildString {
                        append("{")
                        for ((i, k) in keys.withIndex()) {
                            if (i > 0) append(",")
                            append("\"$k\":${element[k]}")
                        }
                        if (keys.isNotEmpty()) append(",")
                        append("\"segments\":$newSegments")
                        append("}")
                    }
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** oracle :2311-2317。 */
    private fun markRecalled(element: JsonElement): JsonObject? {
        val obj = element as? JsonObject ?: return null
        return buildJsonObject {
            obj.forEach { (key, value) -> put(key, value) }
            put("recalled", true)
        }
    }

    /** oracle :2319-2320。 */
    fun isRecalled(element: JsonElement): Boolean =
        (element as? JsonObject)?.get("recalled")?.jsonPrimitive?.contentOrNull.equals("true", true)
}

/**
 * 默认模型输出编解码：严格 JSON → 标签脚本解析 → 可读文本兜底。
 * 与 AIService.normalizeOfflineResponse/cleanJson（AIService.kt:108-330）行为对齐的最小纯函数实现；
 * 生产接线可改为包装 AIService 实例（引擎只依赖 ModelOutputCodec 接口）。
 */
class DefaultReplyCodec : ModelOutputCodec {

    private val strictJson = Json { ignoreUnknownKeys = true }

    override fun normalize(raw: String): OfflineModeResponse {
        val candidates = listOf(raw, cleanJson(raw)).distinct().filter { it.isNotBlank() }
        for (candidate in candidates) {
            parseStrict(candidate)?.let { return it }
        }
        parseScriptResponse(raw).takeIf { !it.segments.isNullOrEmpty() || it.dialogue.isNotBlank() }?.let { return it }
        raw.trim().takeIf { it.isNotBlank() && !looksLikeJson(it) }?.let { text ->
            return OfflineModeResponse(segments = listOf(Segment(type = "dialogue", content = text)))
        }
        return OfflineModeResponse()
    }

    private fun parseStrict(raw: String): OfflineModeResponse? = runCatching {
        strictJson.decodeFromString(OfflineModeResponse.serializer(), raw)
    }.getOrNull()

    /** 【状态】…【台词】… 标签脚本解析（AIService.parseScriptResponse 的最小等价）。 */
    private fun parseScriptResponse(raw: String): OfflineModeResponse {
        val text = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val state = captureTag(text, "状态")
        val emotion = captureTag(text, "心情")
        val location = captureTag(text, "位置")
        val continuity = captureTag(text, "本轮简述")
        val segments = mutableListOf<Segment>()
        Regex("【(旁白|台词|台詞)】(?:[：:])?([\\s\\S]*?)(?=【(?:旁白|台词|台詞)】(?:[：:])?|$)")
            .findAll(text)
            .forEach { match ->
                val type = if (match.groupValues[1] == "旁白") "narration" else "dialogue"
                val content = match.groupValues[2].trim()
                if (content.isNotBlank()) segments += Segment(type = type, content = content)
            }
        if (state.isBlank() && emotion.isBlank() && location.isBlank() && segments.isEmpty()) {
            return OfflineModeResponse()
        }
        return OfflineModeResponse(
            emotion = emotion,
            state = state,
            location = location,
            continuity = continuity,
            segments = segments,
        )
    }

    private fun captureTag(text: String, label: String): String =
        Regex("【$label】(?:[：:])?([^\\n]*?)(?=【|$)").find(text)?.groupValues?.get(1)?.trim().orEmpty()

    private fun looksLikeJson(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("{") || t.startsWith("[")) &&
            (t.contains("segments") || t.contains("dialogue") || t.contains("content") || t.contains("message"))
    }

    /** oracle AIService.kt:298-330 等价：JSON 清洗（围栏/控制字符/截断修复）。 */
    override fun cleanJson(raw: String): String {
        var s = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .replace("　", " ")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        try {
            val obj = strictJson.parseToJsonElement(s)
            return obj.toString()
        } catch (_: Exception) {
        }
        s = s.replace(Regex(",\\s*([}\\]])"), "$1")
        val firstBrace = s.indexOf('{')
        val firstBracket = s.indexOf('[')
        val start = when {
            firstBrace < 0 && firstBracket < 0 -> -1
            firstBrace < 0 -> firstBracket
            firstBracket < 0 -> firstBrace
            else -> minOf(firstBrace, firstBracket)
        }
        if (start > 0) s = s.substring(start)
        if (start >= 0 && s.isNotEmpty()) {
            val openChar = s[0]
            val closeChar = if (openChar == '{') '}' else ']'
            var depth = 0
            var end = -1
            var inStr = false
            var esc = false
            for (i in s.indices) {
                val c = s[i]
                if (esc) { esc = false; continue }
                if (c == '\\') { esc = true; continue }
                if (c == '"') { inStr = !inStr; continue }
                if (inStr) continue
                if (c == openChar) depth++
                else if (c == closeChar) {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
            if (end > 0) s = s.substring(0, end + 1)
        }
        return try {
            strictJson.parseToJsonElement(s).toString()
        } catch (_: Exception) {
            s
        }
    }
}
