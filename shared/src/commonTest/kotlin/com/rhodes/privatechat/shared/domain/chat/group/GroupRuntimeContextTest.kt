package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.prompt.PromptTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 群运行时上下文别名去重（接口化版，规格卡 R5）：迁移自 app 侧 GroupRuntimeContextTest 的核心断言。
 * 契约：只有已知别名族共享所有权；无关字段即使文字相同也彼此独立；成员人设恒提供且只提供一份。
 *
 * 完整 20 例 prompt 域版本见 `shared/.../prompt/GroupRuntimeContextTest.kt`（T18 迁移，唯一幸存副本）。
 */
class GroupRuntimeContextTest {

    private val profile = "名字：夕\n发言标识：dusk\n人设：完整卡片_UNIQUE_PROFILE"
    private val values = mapOf(
        "MEMBER_PROFILES" to profile,
        "MEMBER_NAMES" to "夕",
        "GROUP_SUMMARY" to "摘要_UNIQUE_SUMMARY",
        "SHORT_TERM_SUMMARY" to "摘要_UNIQUE_SUMMARY",
        "GROUP_CONTEXT" to "摘要_UNIQUE_SUMMARY",
        "MEMORY_V2_CONTEXT" to "记忆_UNIQUE_MEMORY",
        "MEMORY_ANCHORS" to "记忆_UNIQUE_MEMORY",
        "SHARED_MEMORIES" to "记忆_UNIQUE_MEMORY",
        "GROUP_RULES" to "独立规则_UNIQUE_RULE",
        "USER_BIO" to "用户设定",
    )

    private fun render(template: String, data: Map<String, String>) =
        Regex("\\{\\{([A-Z0-9_]+)\\}\\}").replace(template) { data[it.groupValues[1]] ?: it.value }

    private fun build(
        custom: String? = null,
        template: String = "{{MEMBER_PROFILES}}\n{{GROUP_SUMMARY}}\n{{MEMORY_V2_CONTEXT}}\n{{GROUP_RULES}}",
        data: Map<String, String> = values,
        natural: String = "自然资料",
        continuity: String = "",
        runtimeKeys: Set<String> = GroupPromptKeysPort.DEFAULT.runtimeKeys("director"),
    ): String = GroupRuntimeContext(runtimeKeys).build(
        continuity = continuity,
        customRuntime = custom,
        naturalRuntime = natural,
        replacements = data,
        render = { render(it, data) },
        templateRuntime = { excluded ->
            // 与生产组装器等价的最简补充：被引用、且未被 provided 的键。
            val referenced = Regex("\\{\\{([A-Z0-9_]+)\\}\\}").findAll(template).map { it.groupValues[1] }.toSet()
            (referenced - excluded).sorted()
                .filter { key -> data[key]?.isNotBlank() == true }
                .joinToString("\n") { "【$it】\n${data[it]}" }
        },
        memberIdentifiers = "夕=dusk",
    )

    private fun occurrences(text: String, needle: String) = text.windowed(needle.length).count { it == needle }

    @Test
    fun defaultGroupTemplateSuppliesExactlyOneFullPersona() {
        for (mode in listOf("online", "offline", "director", "auto")) {
            val result = build(template = PromptTemplates.get("group", mode), natural = "")
            assertEquals(1, occurrences(result, profile), mode)
        }
    }

    @Test
    fun customRuntimeAndMainTemplateDoNotTripleThePersona() {
        val result = build(custom = "用户自定义要求\n{{MEMBER_PROFILES}}")
        assertEquals(1, occurrences(result, profile))
        assertTrue(result.contains("用户自定义要求"))
        assertTrue(result.contains("独立规则_UNIQUE_RULE"))
    }

    @Test
    fun blankCustomRuntimeDoesNotRemoveRequiredMemberData() {
        val result = build(custom = "")
        assertEquals(1, occurrences(result, profile))
        assertTrue(result.contains("独立规则_UNIQUE_RULE"))
        assertFalse(result.contains("自然资料"))
    }

    @Test
    fun summaryAliasesKeepOneBodyAndMarkTheRest() {
        val custom = "摘要={{GROUP_SUMMARY}}｜回顾={{GROUP_CONTEXT}}｜短期={{SHORT_TERM_SUMMARY}}\n" +
            "相关={{MEMORY_V2_CONTEXT}}｜共享={{SHARED_MEMORIES}}"
        val result = build(custom = custom, natural = "")
        assertEquals(1, occurrences(result, "摘要_UNIQUE_SUMMARY"))
        assertEquals(1, occurrences(result, "记忆_UNIQUE_MEMORY"))
        assertTrue(result.contains("同本轮已提供的群聊摘要"))
        assertTrue(result.contains("同本轮已提供的相关记忆"))
    }

    @Test
    fun distinctAliasValuesAreNeverDiscarded() {
        val data = values + ("MEMORY_ANCHORS" to "不同锚点") + ("GROUP_CONTEXT" to "不同回顾")
        val result = build(
            custom = "{{MEMORY_V2_CONTEXT}}\n{{MEMORY_ANCHORS}}\n{{GROUP_SUMMARY}}\n{{GROUP_CONTEXT}}",
            data = data,
            natural = "",
        )
        assertTrue(result.contains("不同锚点"))
        assertTrue(result.contains("不同回顾"))
        assertTrue(result.contains("记忆_UNIQUE_MEMORY"))
    }

    @Test
    fun unrelatedFieldsWithIdenticalTextAreNotTreatedAsAliases() {
        val data = values + ("GROUP_RULES" to "相同文字") + ("USER_BIO" to "相同文字")
        val result = build(custom = "{{GROUP_RULES}}\n{{USER_BIO}}", template = "{{MEMBER_PROFILES}}", data = data, natural = "")
        assertEquals(2, occurrences(result, "相同文字"))
    }

    @Test
    fun naturalContextPreventsRepeatingItsSameSummaryInSupplement() {
        val result = build(natural = "【回顾】\n摘要_UNIQUE_SUMMARY\n【记忆】\n记忆_UNIQUE_MEMORY")
        assertEquals(1, occurrences(result, "摘要_UNIQUE_SUMMARY"))
        assertEquals(1, occurrences(result, "记忆_UNIQUE_MEMORY"))
    }

    @Test
    fun noProfilesFallsBackToMemberIdentifiersHeader() {
        val result = build(custom = "只写当前场景", data = values - "MEMBER_PROFILES", natural = "")
        assertTrue(result.contains("【当前成员与发言标识】"))
        assertTrue(result.contains("夕=dusk"))
    }

    @Test
    fun continuityWithoutMembersStillRenders() {
        val result = build(custom = "", data = emptyMap(), continuity = "已验证连续性", natural = "")
        assertTrue(result.contains("已验证连续性"))
        assertFalse(result.contains("人设："))
    }

    @Test
    fun runtimeKeysPortIsInjectableAndKnownFamiliesStayFixed() {
        // 接口化（R5）：注入空 runtimeKeys 不改变已知别名族（固定族仍去重）。
        val data = mapOf("RELATION_HINTS" to "关系_UNIQUE", "GROUP_RELATION_HINTS" to "关系_UNIQUE")
        val deduped = build(custom = "{{RELATION_HINTS}}\n{{GROUP_RELATION_HINTS}}", data = data, natural = "", runtimeKeys = emptySet())
        assertEquals(1, occurrences(deduped, "关系_UNIQUE"))
        // 默认端口来自 prompt 注册表，可被宿主替换；群聊必需键必须包含。
        assertTrue("GROUP_SUMMARY" in GroupPromptKeysPort.DEFAULT.runtimeKeys("director"))
        assertTrue("MEMBER_PROFILES" in GroupPromptKeysPort.DEFAULT.runtimeKeys("online"))
        assertEquals(setOf("ONLY_KEY"), GroupPromptKeysPort { setOf("ONLY_KEY") }.runtimeKeys("online"))
    }
}
