package com.rhodes.privatechat.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `rhodes://` 深链解析单测（纯 Kotlin 逻辑，不依赖 UI）。
 *
 * 与 `IosServicesSelfTest` 的运行时自检互补：这里是逐条金样断言，
 * 覆盖合法路由、query/fragment、大小写、percent 解码与全部拒绝路径。
 */
class DeepLinkParserTest {

    private fun assertRoute(url: String, expectedRoute: String, kind: DeepLinkKind) {
        val parsed = DeepLinkParser.parse(url)
        assertTrue(parsed.ok, "应解析成功: $url -> ${parsed.error}")
        assertEquals(expectedRoute, parsed.route, "route 不符: $url")
        assertEquals(kind, parsed.kind, "kind 不符: $url")
        assertEquals("", parsed.error)
    }

    @Test
    fun parsesChatDeepLinkWithSessionId() {
        val parsed = DeepLinkParser.parse("rhodes://chat/session-1")
        assertTrue(parsed.ok)
        assertEquals("chat/session-1", parsed.route)
        assertEquals("session-1", parsed.id)
        assertEquals(DeepLinkKind.CHAT, parsed.kind)
        assertEquals("chat", parsed.kindName)
        assertEquals(emptyMap(), parsed.query)
    }

    @Test
    fun parsesOperatorAndSettingsRoutes() {
        assertRoute("rhodes://operator/42", "operator/42", DeepLinkKind.OPERATOR)
        assertRoute("rhodes://settings", "settings", DeepLinkKind.SETTINGS)
        assertRoute("rhodes://settings/notifications", "settings/notifications", DeepLinkKind.SETTINGS)
        assertEquals("notifications", DeepLinkParser.parse("rhodes://settings/notifications").section)
    }

    @Test
    fun parsesSingleSegmentAndBareRoutes() {
        assertRoute("rhodes://timeline", "timeline", DeepLinkKind.TIMELINE)
        assertRoute("rhodes://memories", "memories", DeepLinkKind.MEMORIES)
        assertRoute("rhodes://home", "home", DeepLinkKind.HOME)
        assertRoute("rhodes://", "home", DeepLinkKind.HOME)
        assertRoute("rhodes:", "home", DeepLinkKind.HOME)
    }

    @Test
    fun parsesQueryAndFragment() {
        val parsed = DeepLinkParser.parse("rhodes://chat/s1?from=push&lang=zh#section")
        assertTrue(parsed.ok)
        assertEquals("chat/s1", parsed.route)
        assertEquals(mapOf("from" to "push", "lang" to "zh"), parsed.query)
    }

    @Test
    fun decodesPercentEncodedSegments() {
        val parsed = DeepLinkParser.parse("rhodes://operator/%E5%B0%8F%E6%9E%97")
        assertTrue(parsed.ok)
        assertEquals("小林", parsed.id, "percent 解码应还原中文 id")
        assertEquals("operator/小林", parsed.route)
    }

    @Test
    fun schemeAndActionAreCaseInsensitiveButIdPreserved() {
        val parsed = DeepLinkParser.parse("RHODES://CHAT/Case-Id")
        assertTrue(parsed.ok)
        assertEquals("chat", parsed.kindName)
        assertEquals("Case-Id", parsed.id)
        assertEquals("chat/Case-Id", parsed.route)
    }

    @Test
    fun rejectsForeignScheme() {
        for (url in listOf("http://example.com/chat/1", "https://rhodes.example/chat", "rhodesx://chat/1", "file:///tmp/x")) {
            val parsed = DeepLinkParser.parse(url)
            assertFalse(parsed.ok, "应拒绝: $url")
            assertTrue(parsed.error.isNotEmpty())
            assertEquals("unknown", parsed.kindName)
        }
    }

    @Test
    fun rejectsEmptyAndMalformed() {
        assertFalse(DeepLinkParser.parse("").ok)
        assertFalse(DeepLinkParser.parse("   ").ok)
        assertFalse(DeepLinkParser.parse(null).ok)
        assertEquals("empty url", DeepLinkParser.parse("").error)
    }

    @Test
    fun rejectsMissingIdAndExtraSegments() {
        val missingChat = DeepLinkParser.parse("rhodes://chat")
        assertFalse(missingChat.ok)
        assertEquals("missing sessionId", missingChat.error)

        val missingOperator = DeepLinkParser.parse("rhodes://operator/")
        assertFalse(missingOperator.ok)
        assertEquals("missing operatorId", missingOperator.error)

        for (url in listOf("rhodes://chat/a/b", "rhodes://timeline/x", "rhodes://settings/a/b", "rhodes://home/x")) {
            assertFalse(DeepLinkParser.parse(url).ok, "多余路径段应拒绝: $url")
        }
    }

    @Test
    fun rejectsUnknownRoute() {
        val parsed = DeepLinkParser.parse("rhodes://unknown/x")
        assertFalse(parsed.ok)
        assertEquals("unknown route 'unknown'", parsed.error)
    }

    @Test
    fun isRhodesUrlMatchesParseResult() {
        assertTrue(DeepLinkParser.isRhodesUrl("rhodes://chat/1"))
        assertFalse(DeepLinkParser.isRhodesUrl("http://x"))
        assertFalse(DeepLinkParser.isRhodesUrl(null))
    }
}
