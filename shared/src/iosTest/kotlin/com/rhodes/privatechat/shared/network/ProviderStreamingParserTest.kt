package com.rhodes.privatechat.shared.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T37 SSE 解析纯逻辑测试（不联网，iOS 模拟器 K/N 测试运行器可跑）。
 *
 * 覆盖规格要求：`data: {...}` 行、`[DONE]`、半包累积、UTF-8 分块、keep-alive 注释、
 * 流内 error 事件、非 SSE JSON 回落、供应商目录/端点归一。
 */
class ProviderStreamingParserTest {

    // ------------------------------------------------------------------
    // 1. 行解码：半包 + \r\n
    // ------------------------------------------------------------------

    @Test
    fun decoder_accumulatesHalfPacketsAcrossFeeds() {
        val decoder = SseLineDecoder()
        val bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
            .encodeToByteArray()
        val split = bytes.size / 3
        val first = decoder.feed(bytes, split)
        assertTrue(first.isEmpty(), "第一段是半包，不应切出行；实际=$first")
        val second = decoder.feed(bytes.copyOfRange(split, bytes.size), bytes.size - split)
        // 补齐后共 4 行：data 行 / 空行 / data 行 / 空行
        assertEquals(4, second.size, "补齐后应得到 4 行（含 SSE 空行分隔）；实际=$second")
        assertEquals("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}", second[0])
        assertEquals("", second[1], "SSE 空行分隔")
        assertEquals("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}", second[2])
    }

    @Test
    fun decoder_crlfLinesStripCarriageReturn() {
        val decoder = SseLineDecoder()
        val lines = decoder.feed("data: alpha\r\ndata: beta\r\n".encodeToByteArray(), "data: alpha\r\ndata: beta\r\n".length)
        assertEquals(listOf("data: alpha", "data: beta"), lines)
    }

    @Test
    fun decoder_flushReturnsTrailingUnterminatedLine() {
        val decoder = SseLineDecoder()
        val payload = "data: tail".encodeToByteArray()
        assertTrue(decoder.feed(payload, payload.size).isEmpty())
        assertEquals("data: tail", decoder.flush())
        assertNull(decoder.flush())
    }

    // ------------------------------------------------------------------
    // 2. UTF-8 多字节字符跨 TCP 分片（半包累积的关键回归）
    // ------------------------------------------------------------------

    @Test
    fun utf8ChineseCharacterSplitAcrossTcpChunksIsNotCorrupted() {
        val line = "data: {\"choices\":[{\"delta\":{\"content\":\"你好世界\"}}]}\n"
        val bytes = line.encodeToByteArray()
        // 逐个字节喂入，模拟最坏情况：每个分片只到 1 字节
        val decoder = SseLineDecoder()
        var produced: String? = null
        for (i in bytes.indices) {
            val lines = decoder.feed(bytes.copyOfRange(i, i + 1), 1)
            if (lines.isNotEmpty()) produced = lines.first()
        }
        assertNotNull(produced, "逐字节喂入后应产出一行")
        val event = SseEventParser.parseLine(produced!!)
        assertEquals(SseEvent.Content("你好世界"), event, "跨分片 UTF-8 中文不应被截断/乱码")
    }

    // ------------------------------------------------------------------
    // 3. 事件解析
    // ------------------------------------------------------------------

    @Test
    fun parsesOpenAiStyleDeltaContent() {
        val event = SseEventParser.parseLine(
            "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回复\"},\"finish_reason\":null}]}",
        )
        assertEquals(SseEvent.Content("回复"), event)
    }

    @Test
    fun parsesDoneMarker() {
        assertEquals(SseEvent.Done, SseEventParser.parseLine("data: [DONE]"))
    }

    @Test
    fun ignoresKeepAliveAndControlLines() {
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine(": keep-alive"))
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine(""))
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine("event: message"))
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine("id: 42"))
    }

    @Test
    fun ignoresRoleOnlyAndEmptyContentChunks() {
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}"))
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine("data: {\"choices\":[]}"))
    }

    @Test
    fun parsesStreamErrorObject() {
        val event = SseEventParser.parseLine("data: {\"error\":{\"message\":\"quota exceeded\",\"type\":\"rate_limit\"}}")
        assertTrue(event is SseEvent.Failure && event.message.contains("quota exceeded"), "实际=$event")
    }

    @Test
    fun parsesStreamErrorString() {
        val event = SseEventParser.parseLine("data: {\"error\":\"model_not_found\"}")
        assertEquals(SseEvent.Failure("model_not_found"), event)
    }

    @Test
    fun parsesNonStreamJsonMessageContent() {
        val event = SseEventParser.parseLine(
            "data: {\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"非流式回复\"}}]}",
        )
        assertEquals(SseEvent.Content("非流式回复"), event)
    }

    @Test
    fun ignoresNonJsonDataLines() {
        assertEquals(SseEvent.Ignore, SseEventParser.parseLine("data: ping"))
    }

    // ------------------------------------------------------------------
    // 4. 非 SSE 响应回落
    // ------------------------------------------------------------------

    @Test
    fun extractsContentFromNonStreamResponseBody() {
        val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完整 JSON 回复\"}}]}"
        assertEquals("完整 JSON 回复", SseEventParser.extractFirstContent(body))
    }

    @Test
    fun extractsServerErrorMessageFromBody() {
        val body = "{\"error\":{\"message\":\"invalid api key\",\"type\":\"auth_error\"}}"
        assertEquals("invalid api key", SseEventParser.extractErrorMessage(body))
    }

    // ------------------------------------------------------------------
    // 5. 供应商目录 / 端点 / 超时预算
    // ------------------------------------------------------------------

    @Test
    fun providerCatalogHasAllElevenProvidersInAndroidOrder() {
        assertEquals(11, IosProviderCatalog.ids.size)
        assertEquals(
            listOf(
                "deepseek", "minimax", "byte", "google", "anthropic", "ali",
                "zhipu", "siliconflow", "xiaomi", "openai_compat", "custom",
            ),
            IosProviderCatalog.ids,
        )
        IosProviderCatalog.ids.forEach { id ->
            assertNotNull(IosProviderCatalog.config(id), "缺少供应商配置：$id")
        }
    }

    @Test
    fun endpointsForNonOpenAiCompatProvidersUseOfficialOpenAiCompatPaths() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            IosProviderCatalog.endpoint("google", "gemini-1.5-flash", ""),
        )
        assertEquals(
            "https://api.anthropic.com/v1/chat/completions",
            IosProviderCatalog.endpoint("anthropic", "claude-sonnet-4-20250514", ""),
        )
    }

    @Test
    fun customEndpointNormalizationAcceptsBaseOrFullUrl() {
        assertEquals("https://x/v1/chat/completions", IosProviderCatalog.normalizeCustomUrl("https://x/v1"))
        assertEquals("https://x/v1/chat/completions", IosProviderCatalog.normalizeCustomUrl("https://x/v1/chat/completions/"))
        assertEquals("https://x/v1/chat/completions", IosProviderCatalog.normalizeCustomUrl("https://x/v1/chat/completions"))
        assertEquals("", IosProviderCatalog.normalizeCustomUrl("   "))
    }

    @Test
    fun timeoutsFollowEngineBudgetAndThinkingBonus() {
        val normal = ProviderRuntimeConfig(
            providerId = "deepseek", providerName = "DeepSeek", model = "deepseek-v4-flash",
            baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "k", temperature = 0.95,
            thinkingEnabled = false,
        )
        val thinking = normal.copy(thinkingEnabled = true)
        val normalTimeouts = ProviderTimeouts.forConfig(normal)
        val thinkingTimeouts = ProviderTimeouts.forConfig(thinking)
        assertEquals(com.rhodes.privatechat.shared.domain.chat.ChatStageBudget.MODEL_BUDGET_MS, normalTimeouts.totalMs)
        assertEquals(
            com.rhodes.privatechat.shared.domain.chat.ChatStageBudget.MODEL_BUDGET_MS +
                com.rhodes.privatechat.shared.domain.chat.ChatStageBudget.THINKING_BUDGET_BONUS_MS,
            thinkingTimeouts.totalMs,
        )
        assertEquals(ProviderTimeouts.DEFAULT_FIRST_TOKEN_MS, normalTimeouts.firstTokenMs)
    }

    @Test
    fun missingConfigIsReportedWithActionableChineseReason() {
        val missingKey = ProviderRuntimeConfig(
            providerId = "deepseek", providerName = "DeepSeek", model = "deepseek-v4-flash",
            baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "", temperature = 0.95,
            thinkingEnabled = false,
        )
        assertTrue(!missingKey.isConfigured)
        assertTrue(missingKey.missingReason.contains("API Key"), "实际=${missingKey.missingReason}")

        val missingUrl = missingKey.copy(providerId = "custom", baseUrl = "", apiKey = "k")
        assertTrue(missingUrl.missingReason.contains("端点"), "实际=${missingUrl.missingReason}")
    }
}
