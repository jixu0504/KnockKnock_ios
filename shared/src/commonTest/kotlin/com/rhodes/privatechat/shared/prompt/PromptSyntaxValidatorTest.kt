package com.rhodes.privatechat.shared.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编辑器校验矩阵（规格卡验收 #5；契约 6/7）。
 * oracle：MainViewModel.kt:436-480（promptSyntaxErrors / promptWarnings）。
 */
class PromptSyntaxValidatorTest {

    // ---- 阻断（畸形语法）----

    @Test
    fun strayCloseBracesBlockSave() {
        assertEquals(listOf("}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("正文 }} 结尾"))
        assertEquals(listOf("}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("}}"))
    }

    @Test
    fun unclosedOpenBracesBlockSave() {
        assertEquals(listOf("{{ 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("开头 {{NAME"))
    }

    @Test
    fun emptyBodyBlocksSave() {
        assertEquals(listOf("{{}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("{{}}"))
        assertEquals(listOf("{{  }} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("{{  }}"))
    }

    @Test
    fun bodyContainingBracesOrNewlinesBlocksSave() {
        assertEquals(listOf("{{A{B}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("{{A{B}}"))
        assertEquals(listOf("{{A}B}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("{{A}B}}"))
        assertEquals(listOf("{{A\nB}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("{{A\nB}}"))
        assertEquals(listOf("{{A\rB}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.promptSyntaxErrors("{{A\rB}}"))
    }

    @Test
    fun validSyntaxPasses() {
        assertEquals(emptyList(), PromptSyntaxValidator.promptSyntaxErrors("无占位符的普通文本"))
        assertEquals(emptyList(), PromptSyntaxValidator.promptSyntaxErrors("{{NAME}} 与 {{A_1}}"))
        assertEquals(emptyList(), PromptSyntaxValidator.promptSyntaxErrors("字面 { 与 } 花括号不是占位符"))
    }

    @Test
    fun multipleErrorsAreDeduplicatedAndSorted() {
        val errors = PromptSyntaxValidator.promptSyntaxErrors("}} x }}")
        assertEquals(listOf("}} 格式不正确，请使用 {{名称}}。"), errors)
    }

    // ---- 放行 + 警告（语义不拦截）----

    @Test
    fun unknownPlaceholderIsAllowedWithWarning() {
        val errors = PromptSyntaxValidator.validatePromptTemplate("private", "online", "{{MY_FUTURE_TOKEN}}")
        assertEquals(emptyList(), errors) // 白名单外占位符合法
        val warnings = PromptSyntaxValidator.promptWarnings("private", "online", "{{MY_FUTURE_TOKEN}}")
        assertEquals(listOf("{{MY_FUTURE_TOKEN}} 当前不会自动替换，发送给 AI 时会保留原样。"), warnings)
    }

    @Test
    fun legacyCompatibilityKeysGetDistinctWarning() {
        val warnings = PromptSyntaxValidator.promptWarnings("private", "online", "{{GROUP_SUMMARIES}}")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("旧版兼容字段"))
        val unknown = PromptSyntaxValidator.promptWarnings("private", "online", "{{TOTALLY_NEW}}")
        assertTrue(unknown.single().contains("保留原样"))
    }

    @Test
    fun reservedSystemLabelsWarnButDoNotBlock() {
        val warnings = PromptSyntaxValidator.promptWarnings("private", "online", "【状态】自定义")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("系统解析标签【状态】"))
        assertEquals(emptyList(), PromptSyntaxValidator.promptSyntaxErrors("【状态】自定义"))
    }

    @Test
    fun warningsAreSortedAndDistinct() {
        val warnings = PromptSyntaxValidator.promptWarnings(
            "private", "online",
            "{{B_TOKEN}} {{A_TOKEN}} {{B_TOKEN}} {{GROUP_SUMMARIES}}"
        )
        assertEquals(3, warnings.size)
        assertEquals(warnings.sorted(), warnings)
    }

    @Test
    fun moduleValidationUsesSameSyntaxGate() {
        assertEquals(listOf("}} 格式不正确，请使用 {{名称}}。"), PromptSyntaxValidator.validatePromptModule("坏了 }}"))
        assertEquals(emptyList(), PromptSyntaxValidator.validatePromptModule("{{GROUP_PLOT_SUMMARY}}"))
    }
}
