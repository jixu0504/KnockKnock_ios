package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.domain.chat.DefaultReplyCodec
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.GroupMsgResult
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.network.JsonBlockExtractor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 群聊消息 JSON 编解码（oracle `json = Json { ignoreUnknownKeys = true; encodeDefaults = true }` GVM:82）。 */
object GroupReplyJson {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeResults(results: List<GroupMsgResult>): String =
        runCatching { json.encodeToString(results) }.getOrDefault("[]")

    fun decodeResults(content: String): List<GroupMsgResult> =
        runCatching { json.decodeFromString<List<GroupMsgResult>>(content) }.getOrDefault(emptyList())
}

/** 群聊图片消息 JSON 三态（oracle GVM:2281-2302 / :2337-2361 / :2400-2413）。 */
data class GroupImageMessage(
    val imageUri: String,
    val caption: String,
    val visionSummary: String,
    val failed: Boolean,
)

object GroupImageJsonContract {
    fun placeholder(imageUri: String, caption: String): String = build(imageUri, caption, "", failed = false)

    fun failed(imageUri: String, caption: String): String = build(imageUri, caption, "", failed = true)

    fun withVisionSummary(imageUri: String, caption: String, visionSummary: String): String =
        build(imageUri, caption, visionSummary, failed = false)

    fun build(imageUri: String, caption: String, visionSummary: String, failed: Boolean): String =
        buildJsonObject {
            put("imageUri", imageUri)
            put("caption", caption.trim())
            put("visionSummary", visionSummary.take(800))
            if (failed) put("status", "failed")
        }.toString()

    fun parse(content: String): GroupImageMessage? = runCatching {
        val root = GroupReplyJson.json.parseToJsonElement(content).jsonObject
        GroupImageMessage(
            imageUri = root["imageUri"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            caption = root["caption"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            visionSummary = root["visionSummary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            failed = root["status"]?.jsonPrimitive?.contentOrNull.equals("failed", true),
        )
    }.getOrNull()

    /** 图片段落到提示词的格式化（oracle :2400-2413）。 */
    fun formatForPrompt(content: String): String {
        val parsed = parse(content) ?: return "用户发送了一张图片"
        val rawSummary = parsed.visionSummary.trim()
        val cleanSummary = rawSummary.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
        val summary = runCatching {
            GroupReplyJson.json.parseToJsonElement(cleanSummary).jsonObject["visibleSummary"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: rawSummary.take(200)
        return buildString {
            append("用户发送图片")
            if (summary.isNotBlank()) append("：$summary")
            if (parsed.caption.isNotBlank()) append("；附言：${parsed.caption}")
        }
    }
}

/**
 * 群聊多发言人 tagged 协议解析（逐字迁移 `app/viewmodel/shared/GroupTaggedReplyParser.kt`）。
 *
 * 归属规则：
 * - 【心情】属于最近的前一个【发言人】；
 * - 出现在【旁白】块里的心情一律丢弃（旁白块里心情标签后面的正文仍算旁白）；
 * - 给玩家（“我”）写的、或没有【发言人】归属的心情一律丢弃；
 * - 心情与发言人同行、心情后紧跟台词、心情写在台词之后、【情绪】与【心情】并存（取第一个非空）、
 *   标签重复（取第一个非空）都能识别；
 * - 心情写成句子或超长时截到第一个标点前，再截断到最多 10 字。
 */
object GroupTaggedReplyParser {

    data class SpeakerIdentity(val name: String, val id: String)

    /** 群聊回合状态的内部字段标签：只做索引，不进入台词与旁白。 */
    val TURN_STATE_LABELS = setOf(
        "群聊回合状态", "当前主线", "用户本轮作用", "本轮承接", "本轮新增推进",
        "主线状态", "下轮焦点", "本轮剧情简述", "成员心情"
    )

    val EMOTION_LABELS = setOf("心情", "情绪")

    /** 所有已定义标签，供标签正则和“下一个标签”定位共用。 */
    val LABEL_PATTERN: String = buildList {
        addAll(TURN_STATE_LABELS)
        add("旁白")
        add("发言人")
        addAll(EMOTION_LABELS)
    }.joinToString("|")

    private val tag = Regex(
        """[【\[［]\s*($LABEL_PATTERN)\s*(?:[：:]\s*([^】\]］]*))?[】\]］]"""
    )

    val NEXT_TAG_PATTERN: String = """[【\[［]\s*(?:$LABEL_PATTERN)"""

    /**
     * 解析整段输出。resolveSpeaker 返回 null 表示该标签不属于当前成员（含玩家“我”和名单外成员），
     * 该发言块与其中的心情都会被丢弃。
     */
    fun extractTagged(raw: String, resolveSpeaker: (String) -> SpeakerIdentity?): List<GroupMsgResult> {
        val matches = tag.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList()
        val results = mutableListOf<GroupMsgResult>()
        var dialogueIndex = -1
        var narrationIndex = -1
        matches.forEachIndexed { index, match ->
            val label = match.groupValues[1]
            val inlineValue = match.groupValues[2].trim()
            val content = raw.substring(
                match.range.last + 1,
                matches.getOrNull(index + 1)?.range?.first ?: raw.length
            )
            when {
                label in TURN_STATE_LABELS -> Unit
                label == "旁白" -> {
                    dialogueIndex = -1
                    narrationIndex = -1
                    val text = content.trim()
                    if (text.isNotBlank()) {
                        results += GroupMsgResult(speaker = "旁白", message = text, type = "narration")
                        narrationIndex = results.lastIndex
                    }
                }
                label == "发言人" -> {
                    narrationIndex = -1
                    dialogueIndex = -1
                    val identity = resolveSpeaker(inlineValue) ?: return@forEachIndexed
                    results += GroupMsgResult(
                        speaker = identity.name,
                        message = content.trim(),
                        type = "dialogue",
                        speakerId = identity.id
                    )
                    dialogueIndex = results.lastIndex
                }
                label in EMOTION_LABELS -> {
                    val (emotion, remainder) = splitEmotion(content, inlineValue)
                    if (dialogueIndex >= 0) {
                        val slot = results[dialogueIndex]
                        val nextEmotion = if (slot.emotion.isBlank()) emotion else slot.emotion
                        results[dialogueIndex] = slot.copy(
                            message = joinDialogue(slot.message, remainder),
                            emotion = nextEmotion
                        )
                    } else if (narrationIndex >= 0) {
                        // 旁白里的心情一律丢弃，但标签后面的正文仍属于旁白。
                        val slot = results[narrationIndex]
                        results[narrationIndex] = slot.copy(message = joinDialogue(slot.message, remainder))
                    }
                }
            }
        }
        return results.filter { it.type.equals("narration", true) || it.message.isNotBlank() }
    }

    /** 从心情标签块里切出心情值与仍然属于台词的剩余正文。 */
    fun splitEmotion(content: String, inlineValue: String): Pair<String, String> {
        val inline = inlineValue.trim()
        if (inline.isNotBlank()) {
            return GroupEmotionRules.cleanEmotion(inline) to content.trim()
        }
        val lines = content.split('\n')
        val firstIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstIndex < 0) return "" to ""
        val firstLine = lines[firstIndex].trim()
        // 心情后紧跟台词时（【心情】不悦 台词），心情取第一个空白前的短词，其余仍算台词。
        val separator = firstLine.indexOfFirst { it == ' ' || it == '\t' || it == '　' }
        val head = if (separator > 0) firstLine.substring(0, separator) else firstLine
        val headTail = if (separator > 0) firstLine.substring(separator + 1).trim() else ""
        val rest = lines.drop(firstIndex + 1).joinToString("\n").trim()
        val remainder = listOf(headTail, rest).filter { it.isNotBlank() }.joinToString("\n")
        return GroupEmotionRules.cleanEmotion(head) to remainder
    }

    private fun joinDialogue(current: String, extra: String): String {
        if (extra.isBlank()) return current
        if (current.isBlank()) return extra
        return "$current\n$extra"
    }
}

/**
 * 群聊回复解析与格式化（oracle GVM:2099-2680 的域形态）：
 * tagged 协议 → JSON 数组兜底 → 裸发言人行兜底；归一化白名单过滤；历史/礼物/图片/分段撤回。
 */
object GroupReplyParser {

    private val codec = DefaultReplyCodec()
    private val json = GroupReplyJson.json

    // === 三级解析回退链（契约 7） ===

    /** ① tagged：名单外成员与玩家「我」整体丢弃（oracle :2537-2547）。 */
    fun extractTaggedResults(
        raw: String,
        membersById: Map<String, Operator>,
        membersByName: Map<String, List<Operator>>,
        groupName: String,
    ): List<GroupMsgResult> = GroupTaggedReplyParser.extractTagged(raw) { reference ->
        // Stable IDs are preferred. A display name is accepted only when unique.
        val member = membersById[reference]
            ?: membersByName[reference]?.singleOrNull()?.takeIf { reference != groupName }
        member?.let { GroupTaggedReplyParser.SpeakerIdentity(it.name, it.id) }
    }

    /** ② JSON 数组兜底（oracle :2504-2526）：cleanJson → List<GroupMsgResult>，含 messages|segments 包裹。 */
    fun extractGroupResults(raw: String): List<GroupMsgResult> {
        runCatching {
            val arr = json.decodeFromString<List<GroupMsgResult>>(codec.cleanJson(raw))
            if (arr.isNotEmpty()) return arr.filter { it.message.isNotBlank() }
        }
        val recovered = JsonBlockExtractor.extract(raw).flatMap { block ->
            runCatching { json.decodeFromString<List<GroupMsgResult>>(block) }.getOrNull()
                ?: runCatching {
                    // 包裹形态 messages|segments 必须优先于单条解码：GroupMsgResult 全字段有默认值，
                    // 单条解码未知对象不会抛错而会得到空条目。
                    val root = json.parseToJsonElement(block).jsonObject
                    val items = (root["messages"] as? JsonArray) ?: (root["segments"] as? JsonArray)
                        ?: return@runCatching emptyList()
                    items.mapNotNull { item -> runCatching { json.decodeFromString<GroupMsgResult>(item.toString()) }.getOrNull() }
                }.getOrNull()
                ?: runCatching { listOf(json.decodeFromString<GroupMsgResult>(block)) }.getOrNull()
                ?: emptyList()
        }.filter { it.message.isNotBlank() }
        return recovered
    }

    /** ③ 裸发言人行兜底（oracle :2647-2680）：标准名行/稳定标识行/【旁白】行。 */
    fun extractSpeakerLines(raw: String, activeMembers: List<Operator>, groupName: String): List<GroupMsgResult> {
        if (raw.isBlank()) return emptyList()
        val byId = activeMembers.associateBy { it.id }
        val byUniqueName = activeMembers.groupBy { it.name }.mapValues { (_, members) -> members.singleOrNull() }
        val references = (byId.keys + byUniqueName.keys + "旁白").filter { it.isNotBlank() }
            .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val linePattern = Regex("""(?m)^\s*(?:[-*]\s*)?($references)\s*[：:]\s*(.+?)\s*$""")
        val results = mutableListOf<GroupMsgResult>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("-").removePrefix("*").trim()
            val narrationMatch = Regex("""^【旁白(?:[：:])?】\s*(.+)$""").matchEntire(line)
            if (narrationMatch != null) {
                results += GroupMsgResult("旁白", narrationMatch.groupValues[1].trim(), "narration")
                return@forEach
            }
            val dialogueLine = line.replaceFirst(Regex("""^【(?:台词|台詞)(?:[：:])?】\s*"""), "")
            val match = linePattern.matchEntire(dialogueLine) ?: return@forEach
            val reference = match.groupValues[1]
            val message = match.groupValues[2]
            val speaker = when {
                reference == "旁白" -> "旁白"
                reference == groupName -> ""
                else -> byId[reference]?.name ?: byUniqueName[reference]?.name.orEmpty()
            }
            if (speaker.isNotBlank() && message.isNotBlank()) {
                results += GroupMsgResult(
                    speaker = speaker,
                    message = message.trim().trim('"', '“', '”'),
                    type = if (speaker == "旁白") "narration" else "dialogue"
                )
            }
        }
        return results
    }

    /** 完整三级回退 + 归一化 + 可展示 gate（oracle normalizeReply :1624-1644）。 */
    fun parseGroupReply(
        raw: String,
        activeMembers: List<Operator>,
        groupName: String,
        mode: String,
    ): List<GroupMsgResult> {
        val membersById = activeMembers.associateBy { it.id }
        val membersByName = activeMembers.groupBy { it.name }
        val validSpeakers = activeMembers.map { it.name }.toSet() + GroupStageBudget.NARRATION_SPEAKER
        val extracted = extractTaggedResults(raw, membersById, membersByName, groupName)
            .ifEmpty { extractGroupResults(raw) }
        val normalized = normalizeGroupResults(extracted, validSpeakers, mode)
        if (normalized.isNotEmpty()) return normalized.takeIf { isDisplayableGroupReply(it) }.orEmpty()
        val fallback = normalizeGroupResults(extractSpeakerLines(raw, activeMembers, groupName), validSpeakers, mode)
        return fallback.takeIf { isDisplayableGroupReply(it) }.orEmpty()
    }

    /** oracle :2099-2121。online 禁旁白、第一人称旁白丢弃、空消息/名单外丢弃、旁白清空心情与标识。 */
    fun normalizeGroupResults(
        results: List<GroupMsgResult>,
        validSpeakers: Set<String>,
        mode: String,
    ): List<GroupMsgResult> = results.mapNotNull { raw ->
        val stripped = stripSpeakerPrefix(raw.message)
        var speaker = raw.speaker.trim().ifBlank { stripped.first.ifBlank { "旁白" } }
        var message = stripLeakedSegmentLabel(stripped.second.ifBlank { raw.message }).trim()
        var type = if (raw.type.equals("narration", true) || raw.type == "旁白") "narration" else "dialogue"
        if (stripped.first.isNotBlank() && stripped.first in validSpeakers) speaker = stripped.first
        if (speaker == "旁白") type = "narration"
        if (type == "narration" && containsFirstPersonNarration(message)) return@mapNotNull null
        if (mode == "online" && type == "narration") return@mapNotNull null
        if (message.isBlank() || speaker !in validSpeakers) return@mapNotNull null
        // 心情只跟随它原本归属的成员；发言人被改名或纠正时清空标识，稍后按显示名重新补齐。
        val speakerId = if (speaker == raw.speaker.trim()) raw.speakerId else ""
        GroupMsgResult(
            speaker = speaker,
            message = message,
            type = type,
            emotion = if (type == "narration") "" else GroupEmotionRules.cleanEmotion(raw.emotion),
            speakerId = if (type == "narration") "" else speakerId,
        )
    }

    /** oracle :2149-2150。至少一条当前成员非旁白非空台词才可展示。 */
    fun isDisplayableGroupReply(results: List<GroupMsgResult>): Boolean =
        results.any { it.type == "dialogue" && it.message.isNotBlank() && it.speaker != "旁白" }

    /** 为落库条目补齐 speakerId（oracle :2581-2587）。 */
    fun enrichGroupSpeakerIds(results: List<GroupMsgResult>, membersByName: Map<String, List<Operator>>): List<GroupMsgResult> =
        results.map { result ->
            if (result.type.equals("narration", true) || result.speakerId.isNotBlank() || result.speaker == "旁白") {
                result
            } else {
                result.copy(speakerId = membersByName[result.speaker]?.singleOrNull()?.id.orEmpty())
            }
        }

    // === 归一化工具（单测暴露） ===

    /** oracle :2211-2214。返回（前缀, 剩余正文）；无可识别前缀时前缀为空。 */
    fun stripSpeakerPrefix(content: String): Pair<String, String> {
        val idx = listOf(content.indexOf('：'), content.indexOf(':')).filter { it in 1..12 }.minOrNull()
            ?: return "" to content
        return content.substring(0, idx).trim(' ', '“', '”', '"') to content.substring(idx + 1).trim()
    }

    /** oracle :2216-2219。去掉模型泄漏的结构化台词标签。 */
    fun stripLeakedSegmentLabel(content: String): String = content.trimStart()
        .replaceFirst(Regex("^【(?:旁白|台词|台詞)(?:[：:])?】\\s*"), "")
        .trimStart()

    /** oracle :2221-2228。第一人称旁白判定（引号内内容不参与）。 */
    fun containsFirstPersonNarration(content: String): Boolean {
        val outsideQuotes = content
            .replace(Regex("[“\"](?:\\.|[^”\"])*[”\"]"), "")
            .replace(Regex("[‘'](?:\\.|[^’'])*[’']"), "")
            .trimStart()
        return Regex("""^(?:我|我们|咱们|咱|俺)(?:[，。！？、：:；;\s]|$)""").containsMatchIn(outsideQuotes) ||
            Regex("""^(?:我|我们|咱们|咱|俺)(?:正|正要|正准备|正朝|正向|正往|走|站|坐|看|听|拿|放|抬|低|转|靠|停|伸|推|拉|从|在|向|往)""")
                .containsMatchIn(outsideQuotes)
    }

    /** oracle :2489-2490。可见条数（online 不算旁白）。 */
    fun visibleGroupMessageCount(items: List<GroupMsgResult>, mode: String): Int =
        items.count { mode != "online" || (it.type != "narration" && it.speaker != "旁白") }

    /** oracle :2492-2502 群聊错误文案分类。 */
    fun classifyGroupError(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains("401") || message.contains("api key", true) -> "API Key 无效或已过期，请在设置中检查"
            message.contains("402") || message.contains("insufficient", true) || message.contains("quota") -> "API 余额不足，请充值后重试"
            message.contains("429") -> "AI 服务请求太频繁，请稍后重试"
            Regex("""\b50[0-4]\b""").containsMatchIn(message) -> "AI 服务暂时不可用，请稍后重试"
            message.contains("timeout", true) -> "AI 服务响应超时，请稍后重试"
            Regex("""unknownhost|dns""", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "无法解析 AI 接口域名，请检查网络、DNS 或 API 地址"
            message.contains("ssl", true) -> "AI 接口的 SSL 证书或 HTTPS 配置异常"
            Regex("""connect|network|socket""", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "无法连接 AI 服务，请检查网络、接口地址或服务状态"
            else -> "发送失败：${message.take(50).ifBlank { "未知错误" }}"
        }
    }

    /** oracle :2155-2180。失败原因诊断（测试用）。 */
    fun groupReplyFailureReason(raw: String, activeMembers: List<Operator>, groupName: String, mode: String): String {
        val activeNames = activeMembers.map { it.name }.toSet()
        val extracted = extractGroupResults(raw)
        if (extracted.isEmpty()) {
            val bareReferences = Regex("""(?m)^\s*(?:[-*]\s*)?([^：:\s]{1,40})\s*[：:]\s*.+$""")
                .findAll(raw).map { it.groupValues[1] }.toList()
            val knownIds = activeMembers.map { it.id }.toSet()
            val unknown = bareReferences.filter { it != "旁白" && it !in knownIds && it !in activeNames && it != groupName }.distinct()
            return when {
                bareReferences.any { it in knownIds } ->
                    "模型使用了裸发言标识，但本轮未能恢复为有效成员台词：${bareReferences.filter { it in knownIds }.distinct().joinToString("、")}"
                bareReferences.any { it == groupName } -> "模型将群名“$groupName”当作发言人"
                unknown.isNotEmpty() -> "发言标识不在当前成员中：${unknown.joinToString("、")}"
                else -> "解析失败：未识别到规定标签或可解析 JSON 条目"
            }
        }
        val invalid = extracted.map { it.speaker.trim() }
            .filter { it.isNotBlank() && it != "旁白" && it !in activeNames }
            .distinct()
        val validDialogue = extracted.any { it.message.isNotBlank() && it.speaker in activeNames && !it.type.equals("narration", true) }
        return when {
            invalid.isNotEmpty() -> "发言人不在当前成员中：${invalid.joinToString("、")}"
            !validDialogue -> "解析后没有当前成员的可展示台词"
            mode == "online" && extracted.any { it.type.equals("narration", true) || it.speaker == "旁白" } ->
                "线上模式输出了旁白，且没有可接受台词"
            else -> "输出不符合当前群聊展示要求"
        }
    }

    // === 历史/媒体格式化 ===

    /** oracle :2009-2046。单条历史消息 → 提示词行；不可用返回空串。 */
    fun formatGroupHistoryForPrompt(msg: ChatMessage, activeNames: Set<String>): String {
        if (msg.type == "gift_reply_failed") return ""
        if (msg.type == "gift_hidden") return giftPromptText(msg.content)
        if (msg.type == "image" && msg.isMe) return GroupImageJsonContract.formatForPrompt(msg.content)
        if (msg.isMe) return "用户：${msg.content}"
        if (msg.type == "system") return "系统：${msg.content}"
        if (msg.type != "ai_json") return "${msg.senderName}：${msg.content}"
        return try {
            val items = extractGroupResults(msg.content)
            if (items.isNotEmpty()) {
                items.filterIndexed { index, item ->
                    !isGroupSegmentRecalled(msg.content, index) &&
                        (item.type.equals("narration", true) || item.speaker == "旁白" || item.speaker in activeNames)
                }.joinToString("\n") { r ->
                    // 只有本轮真实解析到心情的历史行才带（心情：x）；回退值不写进行内，避免模型把陈旧情绪当成本轮事实。
                    if (r.type == "narration" || r.speaker == "旁白") {
                        "旁白：${r.message}"
                    } else {
                        val emotion = GroupEmotionRules.cleanEmotion(r.emotion)
                        if (emotion.isBlank()) "${r.speaker}：${r.message}" else "${r.speaker}（心情：$emotion）：${r.message}"
                    }
                }.ifBlank { "群聊回复：[上一条消息格式异常]" }
            } else {
                "群聊回复：[上一条消息格式异常]"
            }
        } catch (_: Exception) {
            "群聊回复：[上一条消息格式异常]"
        }
    }

    /** oracle :2071-2074。礼物隐藏消息还原为 prompt 文本。 */
    fun giftPromptText(content: String): String = runCatching {
        json.parseToJsonElement(content).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("（用户送出了礼物）")

    /** oracle :2057-2069。该分段是否已撤回。 */
    fun isGroupSegmentRecalled(content: String, index: Int): Boolean = runCatching {
        val root = json.parseToJsonElement(content)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["messages"] as? JsonArray) ?: (root["segments"] as? JsonArray) ?: return@runCatching false
            else -> return@runCatching false
        }
        (array.getOrNull(index) as? JsonObject)?.get("recalled")?.jsonPrimitive?.contentOrNull.equals("true", true)
    }.getOrDefault(false)

    /** oracle GVM:537-566。标记单段撤回；全部段已撤回或参数非法返回 null（调用方退化整条删除）。 */
    fun markSegmentRecalled(content: String, segmentIndex: Int): String? = try {
        val root = json.parseToJsonElement(content)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["messages"] as? JsonArray) ?: (root["segments"] as? JsonArray) ?: return null
            else -> return null
        }
        if (segmentIndex !in array.indices) return null
        val list = array.toMutableList()
        val target = list[segmentIndex] as? JsonObject ?: return null
        list[segmentIndex] = buildJsonObject {
            target.forEach { (key, value) -> put(key, value) }
            put("recalled", true)
        }
        if (list.all { (it as? JsonObject)?.get("recalled")?.jsonPrimitive?.contentOrNull.equals("true", true) }) return null
        val replaced = list.joinToString(",", "[", "]") { it.toString() }
        when (root) {
            is JsonArray -> replaced
            is JsonObject -> {
                val segmentKey = if (root["messages"] is JsonArray) "messages" else "segments"
                buildJsonObject {
                    root.forEach { (key, value) ->
                        put(key, if (key == segmentKey) json.parseToJsonElement(replaced) else value)
                    }
                }.toString()
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** oracle GVM:218-240 礼物图标（数组内 recalled 过滤用）。 */
    fun decodeGroupResultsOrNull(content: String): List<GroupMsgResult>? =
        runCatching { json.decodeFromString<List<GroupMsgResult>>(content) }.getOrNull()

    /** oracle :2113-2120 的落库 JSON（encodeDefaults，空值也写空串）。 */
    fun encodeStoredResults(results: List<GroupMsgResult>): String = GroupReplyJson.encodeResults(results)

    /** 历史里最近若干轮的心情（oracle :2549-2578）。 */
    fun trackedEmotionsFromHistory(
        messages: List<ChatMessage>,
        restartAt: Long,
        excludeMessageId: Long = -1L,
        roundLimit: Int = 20,
    ): List<GroupEmotionRules.TrackedEmotion> = messages
        .filter {
            it.type == "ai_json" && !it.isMe && it.id != excludeMessageId &&
                (restartAt <= 0L || it.timestamp >= restartAt)
        }
        .takeLast(roundLimit)
        .flatMap { msg ->
            extractGroupResults(msg.content)
                .filter { !it.type.equals("narration", true) && it.speaker != "旁白" }
                .mapNotNull { result ->
                    val emotion = GroupEmotionRules.cleanEmotion(result.emotion)
                    if (emotion.isBlank()) null
                    else GroupEmotionRules.TrackedEmotion(
                        speakerId = result.speakerId,
                        speakerName = result.speaker,
                        emotion = emotion,
                        spokenAtMs = msg.timestamp,
                    )
                }
        }

    /** oracle :1982-1994。按轮次截取（群聊一轮从用户消息或首条 AI 开始）。 */
    fun recentGroupRounds(messages: List<ChatMessage>, roundLimit: Int): List<ChatMessage> {
        val dialogueMessages = messages.filter { it.type != "system" && it.type != "send_failed" && it.type != "gift_reply_failed" }
        if (roundLimit <= 0) return dialogueMessages
        val roundStarts = mutableListOf<Int>()
        dialogueMessages.forEachIndexed { index, message ->
            if (message.isMe || (index == 0 && !message.isMe) ||
                (!message.isMe && !dialogueMessages[index - 1].isMe && message.type == "ai_json")
            ) {
                roundStarts += index
            }
        }
        if (roundStarts.isEmpty()) return dialogueMessages.takeLast(roundLimit)
        val startIndex = roundStarts.getOrElse((roundStarts.size - roundLimit).coerceAtLeast(0)) { 0 }
        return dialogueMessages.drop(startIndex)
    }

    /** oracle :1982-2007 的轮次切分（统计/测试用）。 */
    fun chunkedByGroupRound(messages: List<ChatMessage>): List<List<ChatMessage>> {
        if (messages.isEmpty()) return emptyList()
        val rounds = mutableListOf<MutableList<ChatMessage>>()
        messages.forEachIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            val startsRound = message.isMe || rounds.isEmpty() || (!message.isMe && previous?.isMe == false && message.type == "ai_json")
            if (startsRound) rounds.add(mutableListOf())
            rounds.last().add(message)
        }
        return rounds
    }

    /** 消息预览（会话列表 lastMessage 更新失败时的兜底，oracle :1782）。 */
    fun messagePreview(message: ChatMessage): String {
        val results = decodeGroupResultsOrNull(message.content) ?: return message.content.take(50)
        val last = results.lastOrNull() ?: return message.content.take(50)
        return "${last.speaker}：${last.message.take(50)}"
    }

    /** 构造群礼物 payload（oracle GVM:1861-1867）。 */
    fun buildGiftPayload(
        prompt: String,
        imageUri: String,
        giftName: String,
        recipientNames: List<String>,
    ): String = buildJsonObject {
        put("event", "gift")
        put("prompt", prompt)
        put("imageUri", imageUri)
        put("giftName", giftName)
        put("recipientNames", buildJsonArray { recipientNames.forEach { add(JsonPrimitive(it)) } })
    }.toString()
}
