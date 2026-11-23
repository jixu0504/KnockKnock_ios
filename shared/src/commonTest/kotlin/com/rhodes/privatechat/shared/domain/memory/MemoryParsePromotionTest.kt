package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收 #5：晋升/解析矩阵（oracle MemoryV2Pipeline.kt:862-952/:1126-1347）。
 * L1≤5 / L2≤2 / L3≤1；非数组/零有效/不足一半 → 抛错走重试；evidence_ids 校验；
 * 稳定性门（3 天跨度 / 4 条 / 90 分）；topicKey 分组与强信号阈值；SCHEMA 字段约束。
 */
class MemoryParsePromotionTest {

    private var now = 1767225600000L
    private val cleanJson: (String) -> String = { it }

    private fun rules(settings: SettingsRepository = SettingsRepository(PromptTestSettings())): MemoryParseRules =
        MemoryParseRules(settings, cleanJson, { now }, { "用户" })

    private fun item(
        id: Long,
        type: String = "event",
        content: String = "内容",
        importance: Int = 50,
        created: Long = now,
        privacy: String? = "private",
        level: MemoryLevel = MemoryLevel.L1,
    ) = MemoryItem(
        id = id, ownerType = "operator", ownerId = "op1", memoryLevel = level, memoryType = type,
        sourceKind = MemorySourceKind.PRIVATE_CHAT, sourceRefId = "src", sessionId = "s1", content = content,
        importance = importance, privacy = privacy, createdAt = created, updatedAt = created,
    )

    private fun json(vararg objects: String): String = objects.joinToString(",", "[", "]")

    @Test
    fun schemaFieldsAndForcedSourcePrivacyMatchOracle() {
        val r = rules()
        val parsed = r.parseMemoryItems(
            json(
                """{"type":"event","content":"用户答应周末一起去海边","importance":120,"privacy":"public","emotion_valence":"happy"}""",
                """{"type":"bogus","content":"非法类型应被丢弃"}""",
                """{"type":"agreement_commitment","content":"约定下个月看日出","importance":88}""",
            ),
            "operator", "op1", MemoryLevel.L1, MemorySourceKind.PRIVATE_CHAT, "src", "s1",
        )
        assertEquals(2, parsed.size)
        assertEquals("event", parsed[0].memoryType)
        assertEquals(100, parsed[0].importance, "importance 必须 clamp 到 0-100")
        assertEquals("private", parsed[0].privacy, "PRIVATE_CHAT 来源强制 private（模型不能改）")
        assertEquals("neutral", parsed[0].emotionValence, "非法 emotion_valence 回落 neutral")
        assertEquals("用户", parsed[0].nickname)
        assertTrue(parsed[0].content.startsWith("用户答应"))
        // MOMENT 来源强制 public
        val publicItems = r.parseMemoryItems(
            json("""{"type":"event","content":"用户发了动态","privacy":"private"}"""),
            "operator", "op1", MemoryLevel.L1, MemorySourceKind.MOMENT, "m1", "",
        )
        assertEquals("public", publicItems.single().privacy)
        // 内容清洗：机制词移除 + 截 120 字
        val cleaned = r.sanitizeContent("系统记录：记忆锚点摘要好感度affection " + "很".repeat(200))
        assertFalse(cleaned.contains("系统记录"))
        assertFalse(cleaned.contains("锚点"))
        assertFalse(cleaned.contains("affection"))
        assertEquals(120, cleaned.length)
    }

    @Test
    fun l1ParseFailureSemanticsDecideRetryVersusSave() {
        val r = rules()
        // 合法空数组 = 无值得保存的信息，正常完成
        assertEquals(emptyList(), r.parseExtractedL1("[]", "operator", "op1", MemorySourceKind.PRIVATE_CHAT, "ref", "s1"))
        // 非 JSON 数组 → 抛错进重试
        assertFailsWith<IllegalStateException> {
            r.parseExtractedL1("""{"type":"event"}""", "operator", "op1", MemorySourceKind.PRIVATE_CHAT, "ref", "s1")
        }
        // 返回了内容但零有效项 → 抛错
        assertFailsWith<IllegalStateException> {
            r.parseExtractedL1(json("""{"type":"bogus","content":"无效"}"""), "operator", "op1", MemorySourceKind.PRIVATE_CHAT, "ref", "s1")
        }
        // 对象数≥2 且有效项≤一半 → 抛错
        assertFailsWith<IllegalStateException> {
            r.parseExtractedL1(
                json("""{"type":"event","content":"有效内容"}""", """{"type":"bogus","content":"无效"}"""),
                "operator", "op1", MemorySourceKind.PRIVATE_CHAT, "ref", "s1",
            )
        }
        // 多数有效 → 正常保存并 take(5)（每窗口 L1 ≤ 5 条）
        val many = (1..7).joinToString(",", "[", "]") { """{"type":"event","content":"事件${it}内容"}""" }
        val saved = r.parseExtractedL1(many, "operator", "op1", MemorySourceKind.PRIVATE_CHAT, "ref", "s1")
        assertEquals(5, saved.size, "MAX_L1_ITEMS_PER_EXTRACTION=5")
        assertEquals(MemoryLevel.L1, saved.first().memoryLevel)
        assertEquals(now, saved.first().createdAt)
    }

    @Test
    fun promotionEvidenceIdsAreValidatedAgainstParentIds() {
        val r = rules()
        val parents = listOf(item(11, "agreement_commitment", "约定看日出"), item(12, "agreement_commitment", "约定看海"))
        val raw = json(
            """{"type":"agreement_commitment","content":"用户和角色约定一起看日出","evidence_ids":[11]}""",
            """{"type":"agreement_commitment","content":"无效证据的项","evidence_ids":[99]}""",
            """{"type":"agreement_commitment","content":"没有证据字段"}""",
        )
        val l2 = r.parsePromotionItems(raw, "operator", "op1", MemoryLevel.L2, MemorySourceKind.PRIVATE_CHAT, "plan:x", parents)
        assertEquals(1, l2.size, "evidence_ids 缺失或引用本次输入之外的 id → 整条丢弃")
        assertEquals(MemoryLevel.L2, l2.single().memoryLevel)
        assertEquals("plan:x", l2.single().topicKey)
    }

    @Test
    fun promotionOutputCapsAndMetaMatchOracle() {
        val r = rules()
        val parents = (1..3).map { item(it.toLong(), "agreement_commitment", "约定${it}", privacy = if (it == 2) "private" else "shared") }
        val three = (1..3).joinToString(",", "[", "]") {
            """{"type":"agreement_commitment","content":"合并内容${it}","importance":70,"evidence_ids":[1,2,3]}"""
        }
        assertEquals(2, r.parsePromotionItems(three, "operator", "op1", MemoryLevel.L2, MemorySourceKind.PRIVATE_CHAT, "plan:x", parents).size, "MAX_L2_ITEMS_PER_PROMOTION=2")
        assertEquals(1, r.parsePromotionItems(three, "operator", "op1", MemoryLevel.L3, MemorySourceKind.PRIVATE_CHAT, "plan:x", parents).size, "MAX_L3_ITEMS_PER_PROMOTION=1")
        val promoted = r.parsePromotionItems(three, "operator", "op1", MemoryLevel.L2, MemorySourceKind.PRIVATE_CHAT, "plan:x", parents).first()
        assertEquals("private", promoted.privacy, "任一父为 private → 子为 private")
        assertEquals(parents.first().sourceRefId, promoted.sourceRefId, "继承主父 sourceRefId（删除传播）")
        assertEquals(parents.first().sessionId, promoted.sessionId, "继承主父 sessionId")
        assertEquals(0.86, promoted.confidence, 1e-9) // 0.8 + 3*0.02
        assertTrue(promoted.rawJson.contains("evidence_ids"))
        assertTrue(promoted.rawJson.contains("promoted_to"))
        assertTrue(promoted.rawJson.contains("source_kinds"))
        assertEquals(now, promoted.createdAt)
    }

    @Test
    fun l2PromotionThresholdAndStrongSignalOverride() {
        val settings = SettingsRepository(PromptTestSettings())
        settings.memoryV2PromoteL1Threshold = 20
        settings.memoryV2ImportantPromotionThreshold = 2
        val r = rules(settings)
        val normal = (1..4).map { item(it.toLong(), "event", "普通事件内容${it}", importance = 40, created = now - it * 86_400_000L) }
        assertNull(r.pickPromotionTopic(normal, threshold = 20), "普通事件需达 20 条阈值")
        val strong = (1..3).map { item(it.toLong(), "agreement_commitment", "答应一起去海边看日出", importance = 40, created = now - it * 86_400_000L) }
        // oracle：外层 items.size < threshold 仍按基准阈值拦；强信号改的是组内阈值 topicThreshold
        assertEquals(2, r.topicThreshold(20, strong), "含承诺/重要度≥80/unmetNeed → 用 importantPromotionThreshold=2")
        assertEquals(20, r.topicThreshold(20, (1..3).map { item(it.toLong(), "event", "普通事件", importance = 40) }))
        val picked = r.pickPromotionTopic(strong, threshold = 3)
        assertNotNull(picked, "3 条承诺同主题 → 达到 importantPromotionThreshold=2")
        assertEquals(3, picked.items.size)
        assertEquals(12, r.pickPromotionTopic((1..20).map { item(it.toLong(), "event", "同一主题内容", importance = 40) }, threshold = 20)!!.items.size)
    }

    @Test
    fun l3StabilityGateMatchesOracle() {
        val r = rules()
        fun stableItems(size: Int, spanDays: Long, maxImportance: Int = 60) =
            (1..size).map {
                item(
                    it.toLong(), "preference_expression", "用户喜欢清淡口味${it}",
                    importance = if (it == size) maxImportance else 50,
                    created = now - ((size - it) * spanDays / maxOf(1, size - 1)) * 86_400_000L,
                )
            }
        assertFalse(r.isStableEnoughForL3(stableItems(2, 0, maxImportance = 60)), "不足 3 条且无 importance≥85 → 拒绝")
        assertTrue(r.isStableEnoughForL3(stableItems(2, 0, maxImportance = 90)), "2 条但含 importance≥85 可通过")
        assertTrue(r.isStableEnoughForL3(stableItems(3, 4)), "3 天跨度（size=3 → span=4 天）")
        assertFalse(r.isStableEnoughForL3(stableItems(3, 1)), "3 条、跨度 1 天、无 90 分 → 拒绝")
        assertTrue(r.isStableEnoughForL3(stableItems(4, 1)), "≥4 条")
        assertTrue(r.isStableEnoughForL3(stableItems(3, 1, maxImportance = 95)), "含 importance≥90")
        val eventOnly = (1..5).map { item(it.toLong(), "event", "事件内容${it}", created = now - it * 86_400_000L) }
        assertFalse(r.isStableEnoughForL3(eventOnly), "必须含稳定类型")
    }

    @Test
    fun topicKeyGroupingAndNoiseFilteringMatchOracle() {
        val r = rules()
        assertEquals("preference", r.topicKey("preference_expression", "用户喜欢清淡口味").substringBefore(':'))
        assertEquals("plan", r.topicKey("agreement_commitment", "答应一起看日出").substringBefore(':'))
        assertEquals("plan", r.topicKey("care_reminder", "提醒带伞").substringBefore(':'))
        assertEquals("state", r.topicKey("emotion_state", "最近情绪低落").substringBefore(':'))
        assertEquals("relation", r.topicKey("evaluation_opinion", "认为用户嘴硬心软").substringBefore(':'))
        assertEquals("knowledge", r.topicKey("external_knowledge", "罗德岛的历史").substringBefore(':'))
        assertEquals("event", r.topicKey("event", "用户去了海边").substringBefore(':'))
        // 2-gram 去噪：常见噪音词不进入 topic token
        val key = r.topicKey("event", "用户今天觉得希望没有可以去看海边日落")
        assertTrue(key.startsWith("event:"))
        assertTrue(key.length <= "event:".length + 24, "topic token 上限 24 字")
        // oracle 噪音过滤只剔除「包含噪音词的 2-gram」（用户/今天/觉得/希望/没有/可以），
        // 重叠 gram 仍可能拼接出原词，因此只锁定分组前缀与 token 上限（与 oracle 行为一致）。
        assertTrue(key.removePrefix("event:").isNotBlank())
    }

    @Test
    fun promotionItemScorePrefersImportanceThenRecency() {
        val r = rules()
        assertEquals(50 * 100L + 30L, r.promotionItemScore(item(1, importance = 50, created = now)))
        assertEquals(50 * 100L + 29L, r.promotionItemScore(item(1, importance = 50, created = now - 86_400_000L)))
        assertTrue(r.promotionItemScore(item(1, importance = 51, created = now - 10 * 86_400_000L)) >
            r.promotionItemScore(item(1, importance = 50, created = now)))
    }

    @Test
    fun expiryPrivacyAndCommentRulesMatchOracle() {
        val settings = SettingsRepository(PromptTestSettings())
        settings.cleanDaysMemoryItems = 30
        now = 1767225600000L
        val r = rules(settings)
        assertEquals(now + 30L * 86_400_000L, r.expiresAtFor("event", MemoryLevel.L1))
        settings.cleanDaysMemoryItems = -1
        assertEquals(Long.MAX_VALUE, r.expiresAtFor("event", MemoryLevel.L1))
        assertEquals("private", r.defaultPrivacy(MemorySourceKind.PRIVATE_CHAT))
        assertEquals("public", r.defaultPrivacy(MemorySourceKind.GROUP_CHAT))
        assertEquals("public", r.defaultPrivacy(MemorySourceKind.MOMENT))
        assertEquals("shared", r.defaultPrivacy(MemorySourceKind.DIARY))
        assertEquals("shared", r.defaultPrivacy(MemorySourceKind.MANUAL_MEMORY))
        assertTrue(r.isHighValueComment(MomentComment(momentId = 1, operatorId = "user", operatorName = "用户", content = "随便看看")))
        assertTrue(r.isHighValueComment(MomentComment(momentId = 1, operatorId = "op1", operatorName = "角色", content = "约定周末见")))
        assertFalse(r.isHighValueComment(MomentComment(momentId = 1, operatorId = "op1", operatorName = "角色", content = "哈哈")))
    }
}
