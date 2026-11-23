package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.prompt.PromptPlaceholderRegistry

/**
 * 群聊运行时上下文组装（别名家族去重），迁移自 `app/viewmodel/shared/GroupRuntimeContext.kt`。
 *
 * 平台化改动（规格卡 R5）：`PromptPlaceholderRegistry.runtimeKeys("group")` 原属 app 层 internal，
 * 本包通过 [GroupPromptKeysPort] 接口化注入；默认实现仍取 prompt 包注册表，宿主可替换。
 *
 * 只有已知别名族共享所有权；无关字段即使文字相同也彼此独立。
 */
class GroupRuntimeContext(private val runtimeKeys: Set<String>) {

    private val token = Regex("\\{\\{([A-Z0-9_]+)\\}\\}")
    private val families = listOf(
        setOf("MEMBER_PROFILES"),
        setOf("MEMBER_NAMES"),
        setOf("SHORT_TERM_SUMMARY", "GROUP_SUMMARY", "GROUP_CONTEXT"),
        setOf("MEMORY_V2_CONTEXT", "SHARED_MEMORIES", "MEMORY_ANCHORS"),
        setOf("RELATION_HINTS", "GROUP_RELATION_HINTS"),
    )

    private fun family(key: String) = families.firstOrNull { key in it }
        ?: key.takeIf { it in runtimeKeys }?.let { setOf(it) }

    private fun meaningful(value: String) = value.isNotBlank() && value.trim() !in setOf("无", "暂无", "null")

    fun claimAlias(seen: MutableSet<Pair<String, String>>, key: String, value: String): Boolean {
        val keys = family(key) ?: return true
        if (!meaningful(value)) return true
        return seen.add(keys.sorted().joinToString("|") to value)
    }

    fun build(
        continuity: String,
        customRuntime: String?,
        naturalRuntime: String,
        replacements: Map<String, String>,
        render: (String) -> String,
        templateRuntime: (excludedKeys: Set<String>) -> String,
        memberIdentifiers: String = "",
    ): String {
        val provided = mutableMapOf<Pair<Set<String>, String>, String>()
        val runtime = if (customRuntime == null) {
            // Natural context is app-owned. Detect only exact known alias values it supplied.
            families.forEach { keys ->
                keys.forEach { key ->
                    val value = replacements[key].orEmpty()
                    if (key in setOf("SHORT_TERM_SUMMARY", "MEMORY_V2_CONTEXT", "RELATION_HINTS") &&
                        meaningful(value) && naturalRuntime.contains(value)
                    ) {
                        provided.getOrPut(keys to value) { key }
                    }
                }
            }
            naturalRuntime
        } else {
            // Scan the original template, never persona/recall text inserted by rendering.
            val ownedTemplate = token.replace(customRuntime) { match ->
                val key = match.groupValues[1]
                val keys = family(key)
                val value = replacements[key].orEmpty()
                if (keys == null || !meaningful(value)) {
                    match.value
                } else {
                    // 先判存在再 getOrPut，保持「插入返回 null」语义（oracle putIfAbsent）。
                    val inserted = (keys to value) !in provided
                    val previous = provided.getOrPut(keys to value) { key }
                    if (inserted) match.value else "（同本轮已提供的${label(previous)}）"
                }
            }
            render(ownedTemplate)
        }
        val profiles = replacements["MEMBER_PROFILES"].orEmpty()
        val profileFamily = family("MEMBER_PROFILES")!!
        val roster = if (profiles.isNotBlank() && (profileFamily to profiles) !in provided) {
            provided[profileFamily to profiles] = "MEMBER_PROFILES"
            "【当前成员与发言标识】\n$profiles"
        } else if (memberIdentifiers.isNotBlank()) {
            "【当前成员与发言标识】\n$memberIdentifiers\n完整人设见本轮已提供的成员资料。"
        } else ""
        val excluded = replacements.keys.filter { key ->
            family(key)?.let { (it to replacements[key].orEmpty()) in provided } == true
        }.toSet()
        return listOf(continuity, runtime, roster, templateRuntime(excluded))
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun label(key: String): String = when (key) {
        "MEMBER_PROFILES" -> "成员人设"
        "MEMBER_NAMES" -> "成员名单"
        "SHORT_TERM_SUMMARY", "GROUP_SUMMARY", "GROUP_CONTEXT" -> "群聊摘要"
        "MEMORY_V2_CONTEXT", "SHARED_MEMORIES", "MEMORY_ANCHORS" -> "相关记忆"
        "RELATION_HINTS", "GROUP_RELATION_HINTS" -> "成员关系背景"
        else -> "资料（$key）"
    }

    companion object {
        /** 生产默认：prompt 包注册表（与 app 层 internal 语义一致）。 */
        fun forGroupTemplate(templateMode: String): GroupRuntimeContext =
            GroupRuntimeContext(PromptPlaceholderRegistry.runtimeKeys("group", templateMode))
    }
}

/** runtimeKeys 注入端口（规格卡 R5：PromptPlaceholderRegistry 接口化）。 */
fun interface GroupPromptKeysPort {
    fun runtimeKeys(templateMode: String): Set<String>

    companion object {
        /** 默认实现：prompt 包注册表。 */
        val DEFAULT: GroupPromptKeysPort = GroupPromptKeysPort { templateMode ->
            PromptPlaceholderRegistry.runtimeKeys("group", templateMode)
        }
    }
}
