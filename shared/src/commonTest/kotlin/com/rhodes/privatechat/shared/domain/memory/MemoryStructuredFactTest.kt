package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * T69：结构化事实解析（否定极性、谓词归一、限定词进键、缺失字段回退）。
 *
 * 解析结果必须与原 `structuredTopicKey` 逐字一致——事实键同时用于 `memory_items.topicKey`
 * 与关系判定，行为回归会直接改变晋升分组。
 */
class MemoryStructuredFactTest {

    private val now = 1_800_000_000_000L

    private val rules = MemoryParseRules(
        settings = SettingsRepository(PromptTestSettings()),
        cleanJson = { it },
        nowMillis = { now },
        userNicknameProvider = { "用户" },
    )

    @Test
    fun negatedPredicateInfersNegativePolarity() {
        val fact = rules.structuredFactOf("""{"subject":"用户","predicate":"不喜欢","object":"香菜"}""")
        assertEquals("用户", fact?.subject)
        assertEquals("dislike", fact?.predicate)
        assertEquals("香菜", fact?.objectValue)
        assertEquals("negative", fact?.polarity)
        assertEquals(emptyList(), fact?.qualifiers)
        assertEquals("fact:negative:用户|dislike|香菜", fact?.factKey)
    }

    @Test
    fun explicitPolarityOverridesInferenceButUnknownFallsBack() {
        val explicit = rules.structuredFactOf(
            """{"subject":"用户","predicate":"讨厌","object":"香菜","polarity":"positive"}"""
        )
        assertEquals("positive", explicit?.polarity)

        // 显式 "unknown" 与原实现一致：不覆盖推断结果（prefer → positive）
        val unknown = rules.structuredFactOf(
            """{"subject":"用户","predicate":"喜欢","object":"川菜","polarity":"unknown"}"""
        )
        assertEquals("positive", unknown?.polarity)
    }

    @Test
    fun predicateAndPartsAreCanonicalized() {
        val fact = rules.structuredFactOf("""{"subject":" 用户 ","predicate":"Work","object":"Rhodes Link!"}""")
        assertEquals("用户", fact?.subject)
        assertEquals("work", fact?.predicate)
        assertEquals("rhodeslink", fact?.objectValue)
        assertEquals("fact:positive:用户|work|rhodeslink", fact?.factKey)

        assertEquals("own", rules.structuredFactOf("""{"subject":"用户","predicate":"养了猫","object":"猫"}""")?.predicate)
        assertEquals("allergy", rules.structuredFactOf("""{"subject":"用户","predicate":"对花生过敏","object":"花生"}""")?.predicate)
        assertEquals("negative", rules.structuredFactOf("""{"subject":"用户","predicate":"戒了","object":"咖啡"}""")?.polarity)
    }

    @Test
    fun qualifiersAreNormalizedDedupedSortedAndEnterKey() {
        val fact = rules.structuredFactOf(
            """{"subject":"用户","predicate":"喜欢","object":"川菜","qualifiers":["周末","聚餐","周末","辣"]}"""
        )
        assertEquals(listOf("周末", "聚餐", "辣"), fact?.qualifiers)
        assertEquals("fact:positive:用户|prefer|川菜|q=周末,聚餐,辣", fact?.factKey)

        // 字符串形式的限定词按既有分隔符拆分
        val text = rules.structuredFactOf(
            """{"subject":"用户","predicate":"喜欢","object":"川菜","qualifiers":"周末，聚餐"}"""
        )
        assertEquals(listOf("周末", "聚餐"), text?.qualifiers)
    }

    @Test
    fun missingPartsAndMalformedJsonReturnNull() {
        assertNull(rules.structuredFactOf("""{"subject":"","predicate":"喜欢","object":"辣"}"""))
        assertNull(rules.structuredFactOf("""{"subject":"用户","predicate":"","object":"辣"}"""))
        assertNull(rules.structuredFactOf("""{"subject":"用户","predicate":"喜欢","object":"  "}"""))
        assertNull(rules.structuredFactOf("not-json"))
        assertNull(rules.structuredFactOf("[]"))
    }

    @Test
    fun topicKeyBehaviourIsUnchanged() {
        val structured = rules.parseMemoryItems(
            """[{"type":"preference_expression","content":"用户讨厌香菜","subject":"用户","predicate":"讨厌","object":"香菜"}]""",
            "operator", "op1", MemoryLevel.L1, MemorySourceKind.PRIVATE_CHAT, "src", "s1",
        ).single()
        assertEquals("fact:negative:用户|dislike|香菜", structured.topicKey)

        val legacy = rules.parseMemoryItems(
            """[{"type":"preference_expression","content":"用户喜欢吃辣"}]""",
            "operator", "op1", MemoryLevel.L1, MemorySourceKind.PRIVATE_CHAT, "src", "s1",
        ).single()
        assertFalse(legacy.topicKey.startsWith(MemoryRelationRules.FACT_PREFIX), "无结构化字段仍回退内容指纹")

        // 只有 L1 用事实键作 topicKey；L2 保持空 topicKey（由晋升元数据写入）
        val l2 = rules.parseMemoryItems(
            """[{"type":"preference_expression","content":"用户长期喜欢川菜","subject":"用户","predicate":"喜欢","object":"川菜"}]""",
            "operator", "op1", MemoryLevel.L2, MemorySourceKind.PRIVATE_CHAT, "src", "s1",
        ).single()
        assertEquals("", l2.topicKey)
    }

    @Test
    fun rawJsonWithEvidenceFieldsStillParses() {
        // withMessageEvidence 会在原对象上追加 sourceMessageIds，解析必须不受影响
        val fact = rules.structuredFactOf(
            """{"type":"event","content":"用户对花生过敏","subject":"用户","predicate":"过敏","object":"花生","sourceMessageIds":[1,2],"importance":80}"""
        )
        assertEquals("fact:positive:用户|allergy|花生", fact?.factKey)
    }
}