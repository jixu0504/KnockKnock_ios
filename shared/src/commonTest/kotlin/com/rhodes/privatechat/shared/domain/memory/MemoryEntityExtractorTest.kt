package com.rhodes.privatechat.shared.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T69 实体抽取：主体/客体、归一、空值与停用词过滤。
 *
 * 抽取是纯函数，覆盖正常事实、空对象、否定事实三类输入；名字归一必须与
 * `MemoryParseRules.normalizeFactPart` 同口径（小写/去标点/24 字符上限）。
 */
class MemoryEntityExtractorTest {

    private fun fact(
        subject: String,
        objectValue: String,
        polarity: String = "positive",
    ) = MemoryParseRules.StructuredFact(
        subject = subject,
        predicate = "prefer",
        objectValue = objectValue,
        polarity = polarity,
        qualifiers = emptyList(),
        factKey = "fact:$polarity:$subject|prefer|$objectValue",
    )

    @Test
    fun normalFactYieldsSubjectAndObjectDrafts() {
        // 「用户」是抽取提示词里的说话人写法，属代词类，不能建成实体枢纽（否则几乎每条记忆都挂它）。
        val drafts = MemoryEntityExtractor.extract(fact("能天使", "川菜"), "operator", "op1")

        assertEquals(listOf("subject", "object"), drafts.map { it.role })
        assertEquals(listOf("能天使", "川菜"), drafts.map { it.normalizedName })
        assertTrue(drafts.all { it.kind == "entity" })
        assertTrue(drafts.all { it.ownerType == "operator" && it.ownerId == "op1" })
        assertTrue(drafts.all { it.confidence == 0.8 })
    }

    @Test
    fun blankObjectOnlyYieldsSubjectDraft() {
        val drafts = MemoryEntityExtractor.extract(fact("能天使", ""), "operator", "op1")

        assertEquals(listOf("subject"), drafts.map { it.role })
        assertEquals("能天使", drafts.single().normalizedName)

        val blankSubject = MemoryEntityExtractor.extract(fact("", "川菜"), "operator", "op1")
        assertEquals(listOf("object"), blankSubject.map { it.role })

        assertEquals(
            emptyList(),
            MemoryEntityExtractor.extract(fact("用户", ""), "operator", "op1").map { it.role },
            "「用户」是代词，单独出现时不应产生实体",
        )
    }

    @Test
    fun negativeFactStillIndexesMentionedEntities() {
        val drafts = MemoryEntityExtractor.extract(fact("用户", "香菜", polarity = "negative"), "operator", "op1")

        assertEquals(listOf("object"), drafts.map { it.role }, "「用户」被过滤，客体仍要索引")
        assertEquals("香菜", drafts.single().normalizedName, "否定的是关系，不是实体是否被提到")
    }

    @Test
    fun namesAreNormalizedAndJunkNamesSkipped() {
        val drafts = MemoryEntityExtractor.extract(
            fact(" 川菜 ", "Rhodes Link!"),
            "operator",
            "op1",
        )
        assertEquals(listOf("川菜", "rhodeslink"), drafts.map { it.normalizedName })
        assertEquals("川菜", drafts.first().name, "展示名保留原样（去首尾空白），归一值用于查重")
        assertEquals("Rhodes Link!", drafts.last().name)

        assertEquals(emptyList(), MemoryEntityExtractor.extract(fact("猫", "我们"), "operator", "op1").map { it.role })
        assertEquals(emptyList(), MemoryEntityExtractor.extract(fact("自己", "什么"), "operator", "op1").map { it.role })
    }
}