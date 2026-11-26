package com.rhodes.privatechat.shared.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 变量替换矩阵与分层语义（规格卡验收 #3；契约 7/8/9/10/12）。
 * oracle：SharedUtils.kt applyTemplate :585 / withLegacyPromptPlaceholders :637 /
 * CachePromptLayering :16 / buildNaturalRuntimeContext :112 / compactTemplate :707 /
 * stripLegacyChatJsonInstructions :606。
 */
class PromptRenderTest {

    // ---- 契约 7：占位符语法仅 {{[A-Z0-9_]+}} ----

    @Test
    fun placeholderPatternOnlyMatchesUpperAlnumUnderscore() {
        val pattern = TEMPLATE_PLACEHOLDER_PATTERN
        assertTrue(pattern.containsMatchIn("{{NAME}}"))
        assertTrue(pattern.containsMatchIn("{{NAME_2}}"))
        assertFalse(pattern.containsMatchIn("{{name}}"))
        assertFalse(pattern.containsMatchIn("{{名前}}"))
        assertFalse(pattern.containsMatchIn("{{NAME-TAG}}"))
    }

    // ---- 契约 8：单次扫描替换，替换值不二次扫描 ----

    @Test
    fun replacementValuesAreNeverRescanned() {
        val template = "{{OPERATOR_NAME}} 说：{{USER_NAME}}"
        val rendered = PromptRender.applyTemplate(
            template,
            mapOf("OPERATOR_NAME" to "阿米娅", "USER_NAME" to "{{OPERATOR_NAME}}")
        )
        // 用户资料中的 {{OPERATOR_NAME}} 必须原样保留，不引发级联替换
        assertEquals("阿米娅 说：{{OPERATOR_NAME}}", rendered)
    }

    @Test
    fun definedTokensAreReplacedSingleScan() {
        val rendered = PromptRender.applyTemplate("{{A}}/{{A}}/{{B}}", mapOf("A" to "1", "B" to "2"))
        assertEquals("1/1/2", rendered)
    }

    @Test
    fun undefinedTokensStayVerbatimForTheModel() {
        // 未定义 token 保留原样送达模型（用户自定义占位符按字面指令合法），不报错、不空替
        val rendered = PromptRender.applyTemplate("{{UNKNOWN_FUTURE_KEY}}", emptyMap())
        assertEquals("{{UNKNOWN_FUTURE_KEY}}", rendered)
        assertTrue(PromptRender.unresolvedTemplateTokens(rendered) == setOf("UNKNOWN_FUTURE_KEY"))
        // oracle 空实现：不抛异常
        PromptRender.requireNoUnresolvedTemplateTokens(rendered, "private")
    }

    @Test
    fun emptyValuesRenderAsEmptyReplacement() {
        assertEquals("前置后置", PromptRender.applyTemplate("前置{{X}}后置", mapOf("X" to "")))
    }

    // ---- 契约 9：legacy 占位符合成 ----

    @Test
    fun legacyMemoryInjectionIsComposedFromChildKeys() {
        val values = PromptRender.withLegacyPromptPlaceholders(
            mapOf(
                "LONG_TERM_IMPRESSION" to "沉稳可靠",
                "MEMORY_ANCHORS" to "锚点A",
                "SHORT_TERM_SUMMARY" to "刚约好晚饭"
            )
        )
        assertTrue(values["MEMORY_INJECTION"]!!.contains("长期印象：沉稳可靠"))
        assertTrue(values["MEMORY_INJECTION"]!!.contains("最近记住的事：\n锚点A"))
        assertTrue(values["MEMORY_INJECTION"]!!.contains("近期摘要：刚约好晚饭"))
    }

    @Test
    fun legacyMemoryInjectionFallsBackToEmptyMarker() {
        assertEquals("无", PromptRender.withLegacyPromptPlaceholders(emptyMap())["MEMORY_INJECTION"])
    }

    @Test
    fun legacyPutIfAbsentFallbacksMatchOracle() {
        val values = PromptRender.withLegacyPromptPlaceholders(emptyMap())
        assertEquals("无", values["MEMORY_V2_CONTEXT"])
        assertEquals("无", values["GROUP_INJECTION"])
        assertEquals("无", values["INJECTION"])
        assertEquals("未知", values["OPERATOR_USER_RELATION"])
        assertEquals("关系信息只作为互动背景，相关时自然体现，不要提到系统记录或关系表。", values["RELATION_RULES"])
        assertEquals("", values["USER_CONTENT"])
        assertEquals("none", values["PROACTIVE_TRIGGER_TYPE"])
        assertEquals("无", values["PROACTIVE_TRIGGER_CONTEXT"])
        assertEquals("无", values["PROACTIVE_LAST_USER_MESSAGE"])
        assertEquals("无", values["RECENT_POSTS"])
        assertEquals("无", values["PRIVATE_SUMMARY"])
        assertEquals("无", values["GROUP_SUMMARIES"])
        assertEquals("无", values["RECENT_MEMORIES"])
        assertEquals("无", values["SELF_STATUS_CHANGES"])
        assertEquals("无", values["RELATION_EVENTS"])
        assertEquals("无", values["WORLD_TODAY_STATE"])
        assertEquals("无", values["PRIVATE_DAILY_SUMMARY"])
        assertEquals("无", values["RECENT_DAILY_SUMMARY"])
        assertEquals("manual", values["AUTO_REASON"])
        assertEquals("用户主动发言。", values["AUTO_REASON_TEXT"])
        assertEquals("manual", values["MOMENT_TRIGGER_TYPE"])
        assertEquals("", values["MODE_RULES"])
        assertEquals("请按当前运行时标签协议输出。", values["OUTPUT_FORMAT"])
    }

    @Test
    fun explicitValuesAreNeverOverwrittenByFallbacks() {
        val values = PromptRender.withLegacyPromptPlaceholders(mapOf("MEMORY_V2_CONTEXT" to "真实记忆"))
        assertEquals("真实记忆", values["MEMORY_V2_CONTEXT"])
    }

    @Test
    fun relationAliasesComposeFromRelationHints() {
        val values = PromptRender.withLegacyPromptPlaceholders(mapOf("RELATION_HINTS" to "与阿米娅是同事"))
        assertEquals("与阿米娅是同事", values["GROUP_RELATION_HINTS"])
        assertEquals("与阿米娅是同事", values["RELATION_CONTEXT"])
        assertEquals("无", values["RELATION_SHARED_MEMORIES"])
        assertEquals("未知", values["OPERATOR_USER_RELATION"]) // USER_RELATION 缺省 → 未知
    }

    // ---- 契约 10：缓存分层 ----

    @Test
    fun dynamicKeysRenderAsPlaceholderInStableSystem() {
        val layers = PromptRender.buildCachePromptLayers(
            "你是{{OPERATOR_NAME}}。时间{{CURRENT_TIME}}",
            mapOf("OPERATOR_NAME" to "阿米娅", "CURRENT_TIME" to "08:00"),
            setOf("CURRENT_TIME")
        )
        assertTrue(layers.system.contains("阿米娅"))
        assertTrue(layers.system.contains("见本轮资料"))
        assertFalse(layers.system.contains("08:00"))
        assertTrue(layers.runtimeContext.contains("08:00"))
    }

    @Test
    fun runtimeContextIsSortedByKeyAndUsesChineseLabels() {
        val template = "{{CURRENT_TIME}} {{MEMORY_V2_CONTEXT}} {{SHORT_TERM_SUMMARY}}"
        val layers = PromptRender.buildCachePromptLayers(
            template,
            mapOf("CURRENT_TIME" to "09:30", "MEMORY_V2_CONTEXT" to "记忆", "SHORT_TERM_SUMMARY" to "摘要"),
            setOf("CURRENT_TIME", "MEMORY_V2_CONTEXT", "SHORT_TERM_SUMMARY")
        )
        val expected = listOf(
            "【当前时间】\n09:30",
            "【可能相关的过往经历】\n记忆",
            "【最近聊天进展】\n摘要"
        ).joinToString("\n")
        assertEquals(expected, layers.runtimeContext)
    }

    @Test
    fun runtimeContextExcludesTemplateUnreferencedKeysAndBlankValues() {
        val template = "{{CURRENT_TIME}}"
        val layers = PromptRender.buildCachePromptLayers(
            template,
            mapOf("CURRENT_TIME" to "09:30", "MEMORY_V2_CONTEXT" to "无关记忆", "RECENT_SOCIAL_CONTEXT" to ""),
            setOf("CURRENT_TIME", "MEMORY_V2_CONTEXT", "RECENT_SOCIAL_CONTEXT")
        )
        assertEquals("【当前时间】\n09:30", layers.runtimeContext)
    }

    @Test
    fun userContentAndUserMessageAreAlwaysBlankInBothLayers() {
        val template = "{{USER_CONTENT}} {{USER_MESSAGE}} {{OPERATOR_NAME}}"
        val layers = PromptRender.buildCachePromptLayers(
            template,
            mapOf("USER_CONTENT" to "用户输入", "USER_MESSAGE" to "用户消息", "OPERATOR_NAME" to "阿米娅"),
            setOf("USER_CONTENT", "USER_MESSAGE")
        )
        assertFalse(layers.system.contains("用户输入"))
        assertFalse(layers.runtimeContext.contains("用户输入"))
        assertTrue(layers.system.contains("阿米娅"))
    }

    @Test
    fun emptyRuntimeFallsBackToNoSupplementaryData() {
        val layers = PromptRender.buildCachePromptLayers(
            "{{OPERATOR_NAME}}",
            mapOf("OPERATOR_NAME" to "阿米娅", "CURRENT_TIME" to ""),
            setOf("CURRENT_TIME")
        )
        assertEquals("【本轮资料】\n暂无与本轮相关的补充资料。", layers.runtimeContext)
    }

    // ---- 契约 12：天然运行时上下文 ----

    @Test
    fun naturalContextTreatsNullLiteralAsEmpty() {
        val context = PromptRender.buildNaturalRuntimeContext(
            "private",
            mapOf("CURRENT_TIME" to "null", "USER_NAME" to "NULL", "USER_BIO" to "   ")
        )
        // oracle 保真：时间块的 empty copy 因前缀恒非空不可达，输出「现在是北京时间：」+空值
        assertTrue(context.contains("现在是北京时间："))
        assertFalse(context.contains("当前时间未提供。"))
        assertTrue(context.contains("姓名：未设置"))
        assertTrue(context.contains("身份设定：未设置"))
    }

    @Test
    fun naturalContextHasBoundaryDeclarationAndIsNotUserInstruction() {
        val context = PromptRender.buildNaturalRuntimeContext("private", mapOf("CURRENT_TIME" to "2026-06-15 09:30（上午）"))
        assertTrue(context.contains("【本轮背景资料】"))
        assertTrue(context.contains("不是用户本轮指令"))
        assertTrue(context.contains("现在是北京时间：2026-06-15 09:30（上午）"))
        assertTrue(context.contains("系统、数据库、向量、知识库"))
    }

    @Test
    fun naturalContextSurfaceSpecificEmptyCopy() {
        val diary = PromptRender.buildNaturalRuntimeContext("diary", emptyMap())
        // oracle 保真：日记日期块的 empty copy 因「今天：/昨天：」前缀恒非空不可达
        assertTrue(diary.contains("今天："))
        assertTrue(diary.contains("昨天："))
        assertFalse(diary.contains("日期未提供。"))
        assertFalse(diary.contains("关系尚未明确。")) // 关系块使用「未知」
        assertTrue(diary.contains("与角色的关系：未知"))
        val moment = PromptRender.buildNaturalRuntimeContext("moment", emptyMap())
        assertTrue(moment.contains("日期："))
        assertTrue(moment.contains("时段："))
        assertFalse(moment.contains("日期和时段未提供。"))
        val group = PromptRender.buildNaturalRuntimeContext("group", emptyMap())
        assertTrue(group.contains("当前没有额外群聊约定。"))
        assertTrue(group.contains("当前没有已记录的群聊主线。"))
        assertTrue(group.contains("没有检索到与当前话题直接相关的群聊经历。"))
    }

    @Test
    fun unknownSurfaceProducesNoContext() {
        assertEquals("", PromptRender.buildNaturalRuntimeContext("galgame", mapOf("CURRENT_TIME" to "x")))
    }

    // ---- 工具函数 ----

    @Test
    fun compactTemplateRemovesPlaceholderOnlyLinesAndSections() {
        val text = "【规则】\n固定规则\n无\n\n【空节】\n\n结尾"
        val compacted = PromptRender.compactTemplate(text)
        assertFalse(compacted.contains("无"))
        assertFalse(compacted.contains("【空节】"))
        assertTrue(compacted.contains("固定规则"))
        assertTrue(compacted.contains("结尾"))
    }

    @Test
    fun stripLegacyChatJsonInstructionsKeepsPersonaText() {
        val template = "【角色身份】\n{\n\\\"segments\\\": 1\n}\n保留：人设说明"
        val stripped = PromptRender.stripLegacyChatJsonInstructions(template)
        assertFalse(stripped.contains("{"))
        assertFalse(stripped.contains("segments"))
        assertTrue(stripped.contains("【角色身份】"))
        assertTrue(stripped.contains("保留：人设说明"))
    }

    @Test
    fun getTimeOfDayBoundaries() {
        assertEquals("凌晨", PromptRender.getTimeOfDay(0))
        assertEquals("清晨", PromptRender.getTimeOfDay(5))
        assertEquals("上午", PromptRender.getTimeOfDay(8))
        assertEquals("中午", PromptRender.getTimeOfDay(12))
        assertEquals("下午", PromptRender.getTimeOfDay(14))
        assertEquals("晚上", PromptRender.getTimeOfDay(18))
        assertEquals("深夜", PromptRender.getTimeOfDay(22))
        assertEquals("深夜", PromptRender.getTimeOfDay(23))
    }

    @Test
    fun groupAliasFamiliesShareOwnershipOnlyWithinKnownFamilies() {
        val seen = mutableSetOf<Pair<String, String>>()
        assertTrue(GroupRuntimeContext.claimAlias(seen, "MEMORY_V2_CONTEXT", "同一记忆"))
        assertFalse(GroupRuntimeContext.claimAlias(seen, "MEMORY_ANCHORS", "同一记忆"))
        // 无关字段同值不共享所有权
        assertTrue(GroupRuntimeContext.claimAlias(seen, "GROUP_RULES", "同一记忆"))
        // 无意义值不占用
        assertTrue(GroupRuntimeContext.claimAlias(seen, "SHORT_TERM_SUMMARY", "无"))
    }
}
