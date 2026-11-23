package com.rhodes.privatechat.shared.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 金样资源读取（跨平台 expect/actual）与门禁辅助函数。
 * JVM actual：classloader resources；iOS actual：test bundle resourcePath。
 */
internal expect fun readTestResource(path: String): String

internal const val GOLDEN_DIR = "golden/prompts"
internal const val GOLDEN_ALLOWLIST = "$GOLDEN_DIR/allowlist.json"

@Serializable
internal data class GoldenAllowlistFile(val comment: String = "", val items: List<GoldenAllowlistItem> = emptyList())

@Serializable
internal data class GoldenAllowlistItem(val raw: String, val field: String, val reason: String = "")

internal object GoldenGate {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadAllowlist(): List<GoldenAllowlistItem> =
        json.decodeFromString<GoldenAllowlistFile>(readTestResource(GOLDEN_ALLOWLIST)).items

    /** 读取 raw 金样正文：去掉 HTML 注释头（含其后一个换行），其余逐字节保留。 */
    fun goldenBody(fileName: String): String {
        val raw = readTestResource("$GOLDEN_DIR/raw/$fileName")
        val stripped = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(raw, "")
        return stripped.removePrefix("\n")
    }

    /** 抽取脚本保留了字面量首/尾空行；Kotlin trimIndent 会移除——两侧对称去掉后严格比对。 */
    fun stripOuterBlankLines(text: String): String {
        val lines = text.split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    /** 白名单第③类（leading-whitespace）豁免路径：仅比较内容字节（去掉行首空白），逐行严格。 */
    fun canonical(text: String): String {
        val lines = stripOuterBlankLines(text).split("\n")
        return lines.joinToString("\n") { it.trimStart(' ', '\t') }
    }

    fun resolveDollarRefs(text: String, refs: Map<String, String>): String {
        var result = text
        for ((name, value) in refs) result = result.replace("\$" + name, value)
        return result
    }
}
