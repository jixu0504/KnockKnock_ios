package com.rhodes.privatechat.shared.platform

/**
 * 深链目标类型（`rhodes://` scheme）。
 *
 * 纯 Kotlin、无平台依赖：iOS Swift 壳在 `onOpenURL` 里调用 [DeepLinkParser.parse]，
 * 然后把 [DeepLinkParseResult.route] / [DeepLinkParseResult.id] 交给路由层。
 * 后续 Android 若复用可整体搬迁到 commonMain。
 */
enum class DeepLinkKind {
    HOME,
    CHAT,
    OPERATOR,
    SETTINGS,
    TIMELINE,
    MEMORIES,
    UNKNOWN,
}

/**
 * 一条深链的解析结果（全部字段都是简单类型，便于 Swift 直接消费）。
 *
 * @param ok 是否解析成功
 * @param raw 原始 URL
 * @param kind 目标类型
 * @param kindName 目标类型的小写名（Swift 侧无需处理 K/N enum）
 * @param id chat 的 sessionId / operator 的 operatorId；其余为空
 * @param section settings 的子分区（可空字符串）；其余为空
 * @param route 规范化路由串（`chat/<id>`、`operator/<id>`、`settings/<section>`、`timeline`…）；失败为空
 * @param query 查询参数（已做最小 percent 解码）
 * @param error 失败原因；空串表示成功
 */
class DeepLinkParseResult(
    val ok: Boolean,
    val raw: String,
    val kind: DeepLinkKind,
    val kindName: String,
    val id: String,
    val section: String,
    val route: String,
    val query: Map<String, String>,
    val error: String,
) {
    override fun toString(): String =
        if (ok) "DeepLinkParseResult(ok, route=$route, query=$query)" else "DeepLinkParseResult(err=$error, raw=$raw)"
}

/**
 * `rhodes://` 深链解析器。
 *
 * 支持的路由（大小写不敏感 scheme/action，id 保留原样）：
 * - `rhodes://chat/{sessionId}`             → chat
 * - `rhodes://operator/{operatorId}`        → operator
 * - `rhodes://settings` / `settings/{section}` → settings（section 缺省为空）
 * - `rhodes://timeline` / `rhodes://memories` / `rhodes://home` / `rhodes://`
 *
 * 容忍 `rhodes:chat/x`（无 `//`）与末尾 `#fragment`；query 支持 `?a=b&c=d`。
 * 拒绝未知 scheme、未知 action、缺 id、多余路径段。
 */
object DeepLinkParser {

    const val SCHEME = "rhodes"

    private const val SCHEME_SLASH = "rhodes://"
    private const val SCHEME_BARE = "rhodes:"

    fun parse(url: String?): DeepLinkParseResult {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return failure(raw, unknown(), "", "", "empty url")

        val lower = raw.lowercase()
        val rest: String = when {
            lower.startsWith(SCHEME_SLASH) -> raw.substring(SCHEME_SLASH.length)
            lower.startsWith(SCHEME_BARE) -> raw.substring(SCHEME_BARE.length)
            else -> return failure(raw, unknown(), "", "", "unsupported scheme: expected rhodes://")
        }

        val withoutFragment = rest.substringBefore('#')
        val pathPart = withoutFragment.substringBefore('?')
        val queryPart = if (withoutFragment.contains('?')) withoutFragment.substringAfter('?') else ""
        val query = parseQuery(queryPart)
        val segments = pathPart.split('/').filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            return success(raw, DeepLinkKind.HOME, "home", "", "", query)
        }

        val head = segments[0].lowercase()
        val id = if (segments.size >= 2) percentDecode(segments[1]) else ""
        return when (head) {
            "home" -> {
                if (segments.size != 1) return extraSegment(raw, head, segments)
                success(raw, DeepLinkKind.HOME, "home", "", "", query)
            }

            "timeline" -> {
                if (segments.size != 1) return extraSegment(raw, head, segments)
                success(raw, DeepLinkKind.TIMELINE, "timeline", "", "", query)
            }

            "memories" -> {
                if (segments.size != 1) return extraSegment(raw, head, segments)
                success(raw, DeepLinkKind.MEMORIES, "memories", "", "", query)
            }

            "chat" -> {
                if (id.isEmpty()) return failure(raw, DeepLinkKind.CHAT, "chat", "", "missing sessionId")
                if (segments.size > 2) return extraSegment(raw, head, segments)
                success(raw, DeepLinkKind.CHAT, "chat/$id", id, "", query)
            }

            "operator" -> {
                if (id.isEmpty()) return failure(raw, DeepLinkKind.OPERATOR, "operator", "", "missing operatorId")
                if (segments.size > 2) return extraSegment(raw, head, segments)
                success(raw, DeepLinkKind.OPERATOR, "operator/$id", id, "", query)
            }

            "settings" -> {
                if (segments.size > 2) return extraSegment(raw, head, segments)
                val section = if (segments.size == 2) id.lowercase() else ""
                val route = if (section.isEmpty()) "settings" else "settings/$section"
                success(raw, DeepLinkKind.SETTINGS, route, "", section, query)
            }

            else -> failure(raw, unknown(), "", "", "unknown route '$head'")
        }
    }

    /** 是否为合法的 `rhodes://` 深链（Swift 侧判断是否交给本解析器）。 */
    fun isRhodesUrl(url: String?): Boolean = parse(url).ok

    private fun extraSegment(raw: String, head: String, segments: List<String>): DeepLinkParseResult =
        failure(raw, unknown(), head, "", "unexpected extra path segment in '${segments.joinToString("/")}'")

    private fun unknown(): DeepLinkKind = DeepLinkKind.UNKNOWN

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('='))
            if (key.isEmpty()) continue
            val value = percentDecode(if (pair.contains('=')) pair.substringAfter('=') else "")
            result[key] = value
        }
        return result
    }

    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        // 按字节收集（%XX 是 UTF-8 字节），最后统一按 UTF-8 解码，避免中文 id 乱码
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.add(code.toByte())
                    i += 3
                    continue
                }
            }
            val encoded = c.toString().encodeToByteArray()
            for (b in encoded) bytes.add(b)
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun success(
        raw: String,
        kind: DeepLinkKind,
        route: String,
        id: String,
        section: String,
        query: Map<String, String>,
    ): DeepLinkParseResult = DeepLinkParseResult(
        ok = true,
        raw = raw,
        kind = kind,
        kindName = kind.name.lowercase(),
        id = id,
        section = section,
        route = route,
        query = query,
        error = "",
    )

    private fun failure(
        raw: String,
        kind: DeepLinkKind,
        route: String,
        id: String,
        error: String,
    ): DeepLinkParseResult = DeepLinkParseResult(
        ok = false,
        raw = raw,
        kind = kind,
        kindName = "unknown",
        id = id,
        section = "",
        route = route,
        query = emptyMap(),
        error = error,
    )
}
