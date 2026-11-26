package com.rhodes.privatechat.shared.domain.memory

/**
 * 私聊记忆/知识库上下文注入编排（规格卡契约 16）。
 *
 * Android oracle 的组装点在 ChatViewModel.kt:3113-3200（顺序）与 :3236-3264（replacements），
 * 并调用 SharedUtils.trimContextBlock/contextBlockLimit（同文件 :720-745）与
 * UnifiedMemoryContext.mergeBlocks（本包）。本对象把「纯函数构造」部分固化到 shared：
 * 输入各候选块，输出合并后的 MEMORY_V2_CONTEXT 与 __KNOWLEDGE_BASE_CONTEXT 等 replacements 值。
 * 真正的模板渲染归 T18、system prompt 最终组装归 T15；本对象不接触模板字面量。
 *
 * 注入顺序（oracle）：群聊近况 → 稳定印象 → 个人向量记忆 → 关系网传递 → 公开动态/评论 → 知识库。
 * 其中群聊近况/稳定印象/知识库各自占独立占位符，个人+关系网先行合并为 memoryV2Context，
 * 再与公开块按 2×limit 合并为最终 MEMORY_V2_CONTEXT（ChatViewModel.kt:3163-3172）。
 */
object MemoryInjection {

    /** 时间回顾问句判定（oracle UnifiedMemoryContext.shouldIncludeTimeSummary，契约 13）。 */
    fun shouldIncludeTimeSummary(userContent: String): Boolean = UnifiedMemoryContext.shouldIncludeTimeSummary(userContent)

    /**
     * 背景块字符上限（oracle SharedUtils.contextBlockLimit:734-745）：
     * economy=1000 / full=5000 / 其他=2500，按 weight 放大，下限 200。
     */
    fun contextBlockLimit(contextMode: String, weight: Int = 1): Int {
        val base = when (contextMode) {
            "economy" -> 1_000
            "full" -> 5_000
            else -> 2_500
        }
        return (base * weight).coerceAtLeast(200)
    }

    /** 背景块按行截断（oracle SharedUtils.trimContextBlock:720-732）。 */
    fun trimContextBlock(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val lines = text.lines().filter { it.isNotBlank() }
        val picked = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            val next = line.take(maxChars)
            if (used + next.length + 1 > maxChars) break
            picked.add(next)
            used += next.length + 1
        }
        return picked.joinToString("\n").ifBlank { text.take(maxChars) }
    }

    data class PrivateBlocks(
        /** 群聊近况（buildPrivateGroupContext，T15/T23 组装层提供）。 */
        val groupContext: String = "无",
        /** 滚动摘要（SHORT_TERM_SUMMARY）。 */
        val shortTermSummary: String = "无",
        /** buildPrivateStableImpression 输出（LONG_TERM_IMPRESSION）。 */
        val stableImpression: String = "",
        /** buildPrivateChatMemoryContext 输出。 */
        val personalMemory: String = "",
        /** buildRelationshipPrivateMemoryContext 输出。 */
        val relationshipMemory: String = "",
        /** buildPublicMemoryContext 输出（未包裹）。 */
        val publicMemory: String = "",
        /** 知识库召回 text（KnowledgeBaseContextBuilder.Recall.text）。 */
        val knowledgeBase: String = "无",
    )

    data class PrivateContext(
        /** 个人 + 关系网合并后、再与公开块合并的最终 MEMORY_V2_CONTEXT（"无" 兜底）。 */
        val memoryV2Context: String,
        /** 个人 + 关系网合并块（trim 后），供诊断/来源标记使用。 */
        val mergedRecallBlock: String,
        /** __KNOWLEDGE_BASE_CONTEXT（"无" 兜底）。 */
        val knowledgeBaseContext: String,
        /** 按 oracle 顺序排列的注入块（群聊近况 → 稳定印象 → 个人 → 关系网 → 公开 → 知识库）。 */
        val orderedBlocks: List<Pair<String, String>>,
        /** oracle ChatViewModel.kt:3236-3264 中记忆/知识库相关 replacements 键值。 */
        val replacements: Map<String, String>,
    )

    /**
     * 组装私聊记忆注入（oracle ChatViewModel.kt:3113-3172 的纯函数化）：
     * - memoryV2Context = trim(mergeBlocks(limit, 个人, 关系网) 兜底"无", limit)
     * - recallMemoryContext = mergeBlocks(2×limit, memoryV2Context, "【公开动态与评论】\n"+公开)
     * - replacements：LONG_TERM_IMPRESSION / MEMORY_V2_CONTEXT / __KNOWLEDGE_BASE_CONTEXT /
     *   SHORT_TERM_SUMMARY / GROUP_CONTEXT
     */
    fun assemblePrivate(blocks: PrivateBlocks, contextBlockLimit: Int): PrivateContext {
        val mergedRecallBlock = trimContextBlock(
            UnifiedMemoryContext.mergeBlocks(
                maxChars = contextBlockLimit,
                blocks.personalMemory,
                blocks.relationshipMemory,
            ).ifBlank { "无" },
            contextBlockLimit,
        )
        val publicBlock = blocks.publicMemory.takeIf { it != "无" }?.let { "【公开动态与评论】\n$it" }.orEmpty()
        val memoryV2Context = UnifiedMemoryContext.mergeBlocks(
            maxChars = contextBlockLimit * 2,
            mergedRecallBlock,
            publicBlock,
        )
        val knowledgeBaseContext = blocks.knowledgeBase.ifBlank { "无" }
        val stableImpression = blocks.stableImpression.ifBlank { "无" }
        val shortTermSummary = blocks.shortTermSummary.ifBlank { "无" }
        val groupContext = blocks.groupContext.ifBlank { "无" }
        val orderedBlocks = listOf(
            "GROUP_CONTEXT" to groupContext,
            "LONG_TERM_IMPRESSION" to stableImpression,
            "PERSONAL_MEMORY" to blocks.personalMemory.ifBlank { "无" },
            "RELATIONSHIP_MEMORY" to blocks.relationshipMemory.ifBlank { "无" },
            "PUBLIC_MEMORY" to publicBlock.ifBlank { "无" },
            "__KNOWLEDGE_BASE_CONTEXT" to knowledgeBaseContext,
        )
        return PrivateContext(
            memoryV2Context = memoryV2Context,
            mergedRecallBlock = mergedRecallBlock,
            knowledgeBaseContext = knowledgeBaseContext,
            orderedBlocks = orderedBlocks,
            replacements = mapOf(
                "LONG_TERM_IMPRESSION" to stableImpression,
                "MEMORY_V2_CONTEXT" to memoryV2Context,
                "__KNOWLEDGE_BASE_CONTEXT" to knowledgeBaseContext,
                "SHORT_TERM_SUMMARY" to shortTermSummary,
                "GROUP_CONTEXT" to groupContext,
            ),
        )
    }

    /** 群聊知识库上下文（oracle GroupChatViewModel 用 KnowledgeBaseContextBuilder.forOperatorsRecall，契约 24）。 */
    fun groupKnowledgeContext(recallText: String): String = recallText.ifBlank { "无" }
}
