package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 记忆解析/清洗/晋升元数据纯规则（规格卡契约 3、6-10、验收 #5/#6）。
 *
 * 由 MemoryV2Pipeline（oracle MemoryV2Pipeline.kt:862-952、:1126-1347）拆出的无存储依赖逻辑：
 * L1 解析失败语义、SCHEMA 字段约束、内容清洗、evidence_ids 校验、topicKey 分组、稳定性门、
 * withPromotionMeta 晋升元数据。行为与 oracle 逐行一致；只把 settings/Json/AI cleanJson/时间
 * 通过构造器注入，便于双路径矩阵测试。
 */
internal class MemoryParseRules(
    private val settings: SettingsRepository,
    private val cleanJson: (String) -> String,
    private val nowMillis: () -> Long,
    private val userNicknameProvider: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    internal fun parseMemoryItems(raw: String, ownerType: String, ownerId: String, level: MemoryLevel, sourceKind: MemorySourceKind, sourceRefId: String = "", sessionId: String = ""): List<MemoryItem> {
        return parseMemoryItemsIndexed(raw, ownerType, ownerId, level, sourceKind, sourceRefId, sessionId).map { it.second }
    }

    /**
     * 与 [parseMemoryItems] 相同，但保留「模型原始数组下标」。
     *
     * 晋升证据必须按原始下标对齐：解析会丢弃非法项并去重，若用「解析后列表的下标」去取
     * `evidence_ids`，一旦有丢弃就会整体错位，把证据挂到错误的父项上（审计 P1-5）。
     */
    internal fun parseMemoryItemsIndexed(raw: String, ownerType: String, ownerId: String, level: MemoryLevel, sourceKind: MemorySourceKind, sourceRefId: String = "", sessionId: String = ""): List<Pair<Int, MemoryItem>> {
        return try {
            val clean = cleanJson(raw)
            val arr = json.decodeFromString(ListSerializer(JsonObject.serializer()), clean)
            arr.mapIndexedNotNull { index, obj ->
                val type = textValue(obj["type"])?.takeIf { it in allowedTypes } ?: return@mapIndexedNotNull null
                val content = sanitizeContent(textValue(obj["content"]).orEmpty()).takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val nickname = textValue(obj["nickname"])?.takeIf { it.isNotBlank() } ?: userNicknameProvider()
                val importance = intValue(obj["importance"]).coerceIn(0, 100)
                val requestedPrivacy = textValue(obj["privacy"])?.takeIf { it in allowedPrivacy }
                val policy = MemoryPrivacyPolicy.forSource(sourceKind, ownerType, ownerId, content)
                // Source policy is authoritative: private chats never become public by model choice,
                // while public posts/comments cannot be downgraded into a private hidden record.
                val privacy = when (sourceKind) {
                    MemorySourceKind.PRIVATE_CHAT -> "private"
                    MemorySourceKind.MOMENT, MemorySourceKind.MOMENT_COMMENT, MemorySourceKind.GROUP_CHAT -> "public"
                    else -> requestedPrivacy ?: policy.privacy
                }
                val unmetNeed = booleanValue(obj["unmet_need"])
                val location = textValue(obj["location"])?.takeIf { it.isNotBlank() }
                val emotionValence = textValue(obj["emotion_valence"])?.takeIf { it in allowedEmotionValence } ?: "neutral"
                val eventTime = textValue(obj["event_time"])?.takeIf { it.isNotBlank() }
                val scheduledTime = textValue(obj["scheduled_time"])?.takeIf { it.isNotBlank() }
                val action = textValue(obj["action"]) ?: ""
                val careType = textValue(obj["care_type"])?.takeIf { it in allowedCareTypes } ?: "none"
                val structuredTopic = if (level == MemoryLevel.L1) structuredTopicKey(obj) else null
                index to MemoryItem(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    memoryLevel = level,
                    memoryType = type,
                    sourceKind = sourceKind,
                    sourceRefId = sourceRefId,
                    sessionId = sessionId,
                    content = content,
                    nickname = nickname,
                    importance = importance,
                    privacy = privacy,
                    unmetNeed = unmetNeed,
                    location = location,
                    emotionValence = emotionValence,
                    eventTime = eventTime,
                    scheduledTime = scheduledTime,
                    action = action,
                    careType = careType,
                    topicKey = structuredTopic ?: if (level == MemoryLevel.L1) topicKey(type, content) else "",
                    sourceActor = nickname,
                    sourceTarget = ownerId,
                    lastUsedAt = 0L,
                    usedCount = 0,
                    confidence = if (level == MemoryLevel.L1) 0.8 else 0.85,
                    rawJson = obj.toString(),
                    createdAt = nowMillis(),
                    updatedAt = nowMillis(),
                    expiresAt = expiresAtFor(type, level)
                )
            }.distinctBy { (_, item) -> normalizeForDedup(item.memoryType, item.content) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** A legal empty array means no durable fact; malformed or unusable output must be retried. */
    internal fun parseExtractedL1(
        raw: String,
        ownerType: String,
        ownerId: String,
        sourceKind: MemorySourceKind,
        sourceRefId: String,
        sessionId: String,
    ): List<MemoryItem> {
        val clean = cleanJson(raw)
        val objects = try {
            json.decodeFromString(ListSerializer(JsonObject.serializer()), clean)
        } catch (e: Exception) {
            throw IllegalStateException("记忆模型返回的 JSON 不是数组", e)
        }
        val parsed = parseMemoryItems(clean, ownerType, ownerId, MemoryLevel.L1, sourceKind, sourceRefId, sessionId)
        MemoryDiagnostics.log("Memory/Parse", "记忆内容解析完成：模型返回=${objects.size}条，有效记忆=${parsed.size}条")
        if (objects.isNotEmpty() && parsed.isEmpty()) {
            throw IllegalStateException("记忆模型返回了内容，但没有可用记忆项")
        }
        val discardedCount = objects.size - parsed.size
        if (discardedCount > 0) {
            MemoryDiagnostics.log("MemoryV2", "L1提取部分降级：总项=${objects.size}，有效=${parsed.size}，丢弃=$discardedCount")
        }
        if (objects.size >= 2 && parsed.size * 2 <= objects.size) {
            throw IllegalStateException("记忆模型有效项不足一半，等待重试（有效=${parsed.size}/${objects.size}）")
        }
        return parsed.take(MAX_L1_ITEMS_PER_EXTRACTION)
    }

    internal fun parsePromotionItems(
        raw: String,
        ownerType: String,
        ownerId: String,
        level: MemoryLevel,
        sourceKind: MemorySourceKind,
        topicKey: String,
        parents: List<MemoryItem>
    ): List<MemoryItem> {
        val parsed = parseMemoryItemsIndexed(raw, ownerType, ownerId, level, sourceKind)
        val objects = runCatching { json.decodeFromString(ListSerializer(JsonObject.serializer()), cleanJson(raw)) }.getOrDefault(emptyList())
        val allowedIds = parents.map { it.id }.filter { it > 0 }.toSet()
        return parsed.mapNotNull { (sourceIndex, item) ->
            val evidenceIds = objects.getOrNull(sourceIndex)?.let { obj: JsonObject -> evidenceIdsFrom(obj) }
                ?.filter { it in allowedIds }
                ?.distinct()
                .orEmpty()
            if (evidenceIds.isEmpty()) null else withPromotionMeta(item, topicKey, parents.filter { it.id in evidenceIds }, level)
        }.take(if (level == MemoryLevel.L2) MAX_L2_ITEMS_PER_PROMOTION else MAX_L3_ITEMS_PER_PROMOTION)
    }

    internal fun evidenceIdsFrom(jsonText: String): List<Long> = runCatching<List<Long>> {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        evidenceIdsFrom(obj)
    }.getOrDefault(emptyList())

    internal fun evidenceIdsFrom(obj: JsonObject): List<Long> {
        val value = obj["evidence_ids"] ?: return emptyList()
        return runCatching {
            value.jsonArray.mapNotNull { it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.contentOrNull?.toLongOrNull() }
        }.getOrElse {
            value.jsonPrimitive.contentOrNull.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        }
    }

    /**
     * 结构化事实（计划书 §4.1.3 的最小落地）：subject/predicate/object + 极性 + 限定词。
     *
     * [factKey] 即原 `topicKey` 事实键：`fact:<polarity>:<subject>|<predicate>|<object>[|q=…]`。
     * 同一事实的不同措辞才能落进同一组（P0-1），极性/限定词进入键本身，相反事实不会被合并；
     * 真正的 supersede/contradicts 语义仍属 T78。
     */
    internal data class StructuredFact(
        val subject: String,
        val predicate: String,
        val objectValue: String,
        val polarity: String,
        val qualifiers: List<String>,
        val factKey: String,
        /** 归并键：去掉限定词与客体尾部修饰，用于晋升分组与冲突判定。 */
        val groupKey: String = factKey,
    )

    internal fun structuredFactOf(rawJson: String): StructuredFact? = runCatching {
        structuredFactOf(json.parseToJsonElement(rawJson).jsonObject)
    }.getOrNull()

    internal fun structuredFactOf(obj: JsonObject): StructuredFact? {
        val subject = normalizeFactPart(textValue(obj["subject"]))
        val predicateText = textValue(obj["predicate"]).orEmpty()
        val objectText = normalizeFactPart(textValue(obj["object"]))
        if (subject.isBlank() || predicateText.isBlank() || objectText.isBlank()) return null
        val predicate = canonicalPredicate(predicateText)
        val explicit = textValue(obj["polarity"])?.lowercase()?.takeIf { it in setOf("positive", "negative", "unknown") }
        val inferred = when (predicate) {
            "dislike" -> "negative"
            "unknown" -> "unknown"
            else -> "positive"
        }
        val polarity = when (explicit) {
            "positive", "negative" -> explicit
            else -> inferred
        }
        val qualifiers = qualifiersOf(obj)
        val factKey = buildString {
            append("fact:").append(polarity).append(':').append(subject).append('|').append(predicate).append('|').append(objectText)
            if (qualifiers.isNotEmpty()) append("|q=").append(qualifiers.joinToString(","))
        }
        val groupKey = "$STRUCTURED_TOPIC_PREFIX$polarity:$subject|$predicate|${objectHeadOf(objectText)}"
        return StructuredFact(subject, predicate, objectText, polarity, qualifiers, factKey, groupKey)
    }

    private fun structuredTopicKey(obj: JsonObject): String? = structuredFactOf(obj)?.factKey

    private fun canonicalPredicate(raw: String): String {
        val text = raw.trim().lowercase()
        PREDICATE_CANONICAL[text]?.let { return it }
        PREDICATE_SYNONYMS.forEach { (canonical, words) ->
            if (words.any { text.contains(it) }) return canonical
        }
        if (text.matches(Regex("[a-z_]{2,20}"))) return text
        // 未知中文谓词保留原文，不塌缩成 other：否则「喜欢猫/养了猫」会被并成一组。
        return normalizeFactPart(text).ifBlank { "other" }
    }

    private fun qualifiersOf(obj: JsonObject): List<String> {
        val element = obj["qualifiers"] ?: return emptyList()
        val values = runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrElse {
            element.jsonPrimitive.contentOrNull.orEmpty().split('、', ',', '，').map { it.trim() }
        }
        return values
            .map { normalizeFactPart(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(3)
    }

    private fun textValue(element: JsonElement?): String? = element?.jsonPrimitive?.contentOrNull?.trim()

    private fun intValue(element: JsonElement?): Int {
        val primitive = element?.jsonPrimitive ?: return 0
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun booleanValue(element: JsonElement?): Boolean {
        val primitive = element?.jsonPrimitive ?: return false
        return primitive.booleanOrNull ?: primitive.contentOrNull?.equals("true", true) == true
    }

    internal fun sanitizeContent(value: String): String {
        return value
            .replace("系统记录", "")
            .replace("记忆锚点", "")
            .replace("锚点", "")
            .replace("摘要", "")
            .replace("好感度", "")
            .replace("affection", "", ignoreCase = true)
            .trim(' ', '，', '。', ',', ';', '；')
            .take(120)
    }

    internal fun normalizeForDedup(type: String, content: String): String = memoryNormalizeForDedup(type, content)

    internal fun defaultPrivacy(sourceKind: MemorySourceKind): String = when (sourceKind) {
        MemorySourceKind.PRIVATE_CHAT -> "private"
        MemorySourceKind.GROUP_CHAT, MemorySourceKind.MOMENT, MemorySourceKind.MOMENT_COMMENT -> "public"
        else -> "shared"
    }

    internal fun isHighValueComment(comment: MomentComment): Boolean {
        if (comment.operatorId == "user") return true
        val text = comment.content
        return HIGH_VALUE_COMMENT_TERMS.any { it in text }
    }


    internal fun expiresAtFor(type: String, level: MemoryLevel): Long {
        val configuredDays = settings.cleanDaysMemoryItems
        return if (configuredDays < 0) Long.MAX_VALUE else nowMillis() + configuredDays * 86_400_000L
    }

    internal data class PromotionTopic(val topicKey: String, val items: List<MemoryItem>)

    internal fun pickPromotionTopic(items: List<MemoryItem>, threshold: Int, requireStable: Boolean = false): PromotionTopic? {
        if (items.isEmpty()) return null
        val grouped = items
            .filter { it.content.isNotBlank() && it.importance >= 20 }
            .groupBy { groupKeyOf(it.topicKey.ifBlank { topicKey(it.memoryType, it.content) }) }
            .mapValues { (_, values) -> values.sortedByDescending { promotionItemScore(it) } }
            .filterValues { values -> values.size >= requiredGroupSize(threshold, values) }
        if (grouped.isEmpty()) return null
        val picked = grouped.maxByOrNull { (_, values) -> values.sumOf { it.importance } + values.size * 10 } ?: return null
        val selected = picked.value.take(12)
        if (requireStable && !isStableEnoughForL3(selected)) return null
        return PromotionTopic(picked.key, selected)
    }

    /**
     * 组门：结构化事实组按计划书 §4.4.1 的独立性要求（≥2 会话，强信号除外），组门 3 条；
     * 旧版内容指纹组保持原 20/2 门槛，避免无结构数据被误合并。
     */
    private fun requiredGroupSize(baseThreshold: Int, values: List<MemoryItem>): Int {
        val structured = values.any { it.topicKey.startsWith(STRUCTURED_TOPIC_PREFIX) }
        if (!structured) return topicThreshold(baseThreshold, values)
        val strong = values.any {
            it.importance >= 80 || it.unmetNeed ||
                it.memoryType == "agreement_commitment" || it.memoryType == "care_reminder"
        }
        val sessions = values.map { it.sessionId }.filter { it.isNotBlank() }.distinct().size
        if (sessions < 2 && !strong) return Int.MAX_VALUE
        return if (strong) settings.memoryV2ImportantPromotionThreshold else STRUCTURED_GROUP_THRESHOLD
    }

    internal fun topicThreshold(baseThreshold: Int, values: List<MemoryItem>): Int {
        val strong = values.any { it.importance >= 80 || it.unmetNeed || it.memoryType == "agreement_commitment" || it.memoryType == "care_reminder" }
        return if (strong) settings.memoryV2ImportantPromotionThreshold else baseThreshold
    }

    internal fun promotionItemScore(item: MemoryItem): Long {
        val ageDays = ((nowMillis() - item.createdAt).coerceAtLeast(0L) / 86_400_000L).coerceAtMost(30L)
        val recency = 30L - ageDays
        return item.importance * 100L + recency
    }

    internal fun isStableEnoughForL3(items: List<MemoryItem>): Boolean {
        if (items.size < 3 && items.none { it.importance >= 85 }) return false
        val times = items.map { it.createdAt }.filter { it > 0 }
        val span = if (times.size >= 2) (times.maxOrNull() ?: 0L) - (times.minOrNull() ?: 0L) else 0L
        val stableType = items.any { it.memoryType in stableMemoryTypes }
        return stableType && (span >= 3L * 86_400_000L || items.size >= 4 || items.any { it.importance >= 90 })
    }

    internal fun topicKey(memoryType: String, content: String): String {
        val typeGroup = when (memoryType) {
            "preference_expression" -> "preference"
            "agreement_commitment", "care_reminder", "intent_wish" -> "plan"
            "emotion_state", "behavior_state", "physiological_state" -> "state"
            "evaluation_opinion", "self_cognition_statement" -> "relation"
            "external_knowledge" -> "knowledge"
            else -> "event"
        }
        val tokens = content
            .replace(Regex("""[\s，。！？；,.!?;：:\"'“”‘’【】\[\]（）()]"""), "")
            .windowed(2, 1, partialWindows = false)
            .filterNot { token -> commonTopicNoise.any { token.contains(it) } }
            .take(8)
            .joinToString("")
            .take(24)
        return "$typeGroup:${tokens.ifBlank { memoryType }}"
    }

    internal fun withPromotionMeta(item: MemoryItem, topicKey: String, parents: List<MemoryItem>, targetLevel: MemoryLevel): MemoryItem {
        val primarySource = parents.firstOrNull()
        val meta = buildJsonObject {
            put("topic_key", topicKey)
            put("evidence_count", parents.size)
            put("evidence_ids", parents.map { it.id }.filter { it > 0 }.joinToString(","))
            put("promoted_to", targetLevel.name)
            put("source_levels", parents.map { it.memoryLevel.name }.distinct().joinToString(","))
            put("last_evidence_at", parents.maxOfOrNull { it.createdAt } ?: item.createdAt)
            put("source_kinds", parents.map { it.sourceKind.name }.distinct().joinToString(","))
        }.toString()
        val maxImportance = maxOf(item.importance, parents.maxOfOrNull { it.importance } ?: item.importance)
        val privacy = if (parents.any { it.privacy == "private" }) "private" else item.privacy
        return item.copy(
            importance = maxImportance.coerceIn(0, 100),
            privacy = privacy,
            // Derived memory must retain a deletable source/session identity.  Without this,
            // clearing a conversation could leave its promoted L2/L3 facts recallable.
            sourceRefId = primarySource?.sourceRefId.orEmpty(),
            sessionId = primarySource?.sessionId.orEmpty(),
            topicKey = topicKey,
            sourceActor = parents.firstOrNull()?.sourceActor ?: item.sourceActor,
            sourceTarget = parents.firstOrNull()?.sourceTarget ?: item.sourceTarget,
            lastUsedAt = 0L,
            usedCount = 0,
            confidence = (0.8 + parents.size * 0.02).coerceAtMost(0.95),
            rawJson = meta,
            createdAt = nowMillis(),
            updatedAt = nowMillis(),
        )
    }

    internal companion object {
        /** 事实各部分的归一化口径：小写、去标点空白、最多 24 字符。 */
        /**
         * 客体头部归一：剥掉「不加糖 / 不要辣 / 少冰」这类尾部修饰。
         *
         * 模型常把限定词写进客体（`美式咖啡不加糖`、`美式咖啡` + `q=不加糖`），不归一就永远凑不满
         * 晋升组门（实测 3 句「喜欢美式」被拆成 3 个键，L2/印象无法形成）。只剥尾部短修饰，
         * 主体名词保持原样；剥完不足 2 字则回退原文，避免把客体削空。
         */
        internal fun objectHeadOf(objectText: String): String {
            val match = TRAILING_MODIFIER.find(objectText) ?: return objectText
            val head = objectText.take(objectText.length - match.value.length)
            return if (head.length >= 2) head else objectText
        }

        /** `fact:<polarity>:<subject>|<predicate>|<object>[|q=…]` 的解析结果。 */
        internal data class TopicKeyParts(
            val polarity: String,
            val subject: String,
            val predicate: String,
            val objectHead: String,
        ) {
            /** 去掉极性的归并基座（同基座才能判定 supports / contradicts / supersedes）。 */
            val base: String get() = "$subject|$predicate|$objectHead"
            val groupKey: String get() = "$STRUCTURED_TOPIC_PREFIX$polarity:$base"
        }

        internal fun parseTopicKey(topicKey: String): TopicKeyParts? {
            if (!topicKey.startsWith(STRUCTURED_TOPIC_PREFIX)) return null
            val rest = topicKey.removePrefix(STRUCTURED_TOPIC_PREFIX)
            val colon = rest.indexOf(':')
            if (colon <= 0 || colon >= rest.length - 1) return null
            val polarity = rest.substring(0, colon).trim()
            val parts = rest.substring(colon + 1).split('|')
            if (polarity.isBlank()) return null
            if (parts.size < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) return null
            return TopicKeyParts(polarity, parts[0], parts[1], objectHeadOf(parts[2]))
        }

        /** 无论是否结构化都返回可用于分组的键；非结构化保持原样。 */
        internal fun groupKeyOf(topicKey: String): String = parseTopicKey(topicKey)?.groupKey ?: topicKey

        /** 归并基座；非结构化返回 null。 */
        internal fun factBaseOf(topicKey: String): String? = parseTopicKey(topicKey)?.base

        private val TRAILING_MODIFIER = Regex("(不加|不要|没有|加|少|多)[^不加不要没有]{0,4}$")

        internal fun normalizeFactPart(raw: String?): String = raw.orEmpty()
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(24)

        const val STRUCTURED_TOPIC_PREFIX = "fact:"
        const val STRUCTURED_GROUP_THRESHOLD = 3
        /** 谓词同义归一（顺序敏感：否定优先于肯定）。 */
        val PREDICATE_SYNONYMS: List<Pair<String, List<String>>> = listOf(
            "dislike" to listOf("讨厌", "不喜欢", "受不了", "反感", "戒了"),
            "prefer" to listOf("喜欢", "爱吃", "爱喝", "偏好", "偏爱"),
            "reside" to listOf("住在", "居住", "搬家", "搬到"),
            "work" to listOf("工作", "上班", "职业", "就职"),
            "allergy" to listOf("过敏"),
            "promise" to listOf("答应", "约定", "承诺"),
            "plan" to listOf("计划", "打算", "准备"),
            "own" to listOf("养", "拥有"),
            "study" to listOf("学", "学习"),
            "birthday" to listOf("生日", "出生"),
        )
        val PREDICATE_CANONICAL: Map<String, String> = PREDICATE_SYNONYMS.map { it.first }.associateWith { it }
        const val MAX_L1_ITEMS_PER_EXTRACTION = 5
        const val MAX_L2_ITEMS_PER_PROMOTION = 2
        const val MAX_L3_ITEMS_PER_PROMOTION = 1
        val HIGH_VALUE_COMMENT_TERMS = setOf("答应", "约定", "承诺", "计划", "决定", "以后", "生日", "搬家", "住在", "工作")
        val allowedTypes = setOf(
            "emotion_state", "behavior_state", "physiological_state", "event", "agreement_commitment",
            "intent_wish", "preference_expression", "evaluation_opinion", "self_cognition_statement",
            "external_knowledge", "care_reminder"
        )
        val allowedPrivacy = setOf("public", "private", "shared")
        val allowedEmotionValence = setOf("positive", "neutral", "negative", "mixed")
        val allowedCareTypes = setOf("comfort", "remind", "celebrate", "accompany", "none")
        val stableMemoryTypes = setOf("preference_expression", "agreement_commitment", "care_reminder", "evaluation_opinion", "self_cognition_statement")
        val commonTopicNoise = setOf("用户", "今天", "昨天", "最近", "表示", "觉得", "希望", "提到", "自己", "没有", "可以")
    }
}
