package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 实体通道（T72）：抽取规则与「查询命中实体名」的判定口径。 */
class MemoryEntityRecallTest {

    private val fact = MemoryParseRules.StructuredFact(
        subject = "用户",
        predicate = "adopt",
        objectValue = "布偶猫",
        polarity = "positive",
        qualifiers = emptyList(),
        factKey = "fact:positive:用户|adopt|布偶猫",
    )

    @Test
    fun subjectAndObjectBothExtractedButPronounsSkipped() {
        val drafts = MemoryEntityExtractor.extract(fact, "operator", "op1")
        assertEquals(listOf("object"), drafts.map { it.role }, "「用户」是代词，只留客体实体")
        assertEquals("布偶猫", drafts.single().normalizedName)
    }

    @Test
    fun singleCharacterNamesAreRejected() {
        val short = fact.copy(objectValue = "猫")
        assertTrue(MemoryEntityExtractor.extract(short, "operator", "op1").isEmpty())
    }

    @Test
    fun queryMentionIsDecidedByNormalizedSubstring() {
        val entities = listOf("布偶猫", "美式咖啡")
        val query = "我养了一只布偶猫吗"
        assertEquals(listOf("布偶猫"), entities.filter { it.length >= 2 && query.contains(it) })
        assertTrue(entities.none { "我喜欢喝什么" .contains(it) })
    }
}
