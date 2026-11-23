package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.GroupTurnState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 群聊提示词组装（验收 #8 域侧）：契约 10 注入顺序 + 契约 11 三模式/auto 维度 + 缓存分层。
 * 金样逐字节 diff 由 T18 GoldenPromptTest 承担（模板常量）；本套锁定编排顺序与注入内容。
 */
class GroupPromptAssemblyTest {

    private val g = GroupEngineHarness.GROUP_ID

    private suspend fun harness(): GroupEngineHarness {
        val h = GroupEngineHarness()
        h.seedGroup()
        return h
    }

    private suspend fun assemble(
        h: GroupEngineHarness,
        mode: String = "online",
        isAuto: Boolean = false,
        requestText: String = "你们在吗？",
        autoSpeak: Boolean = false,
    ) = h.assembler.assemble(
        GroupPromptBuildRequest(
            groupId = g,
            groupName = "测试群",
            mode = mode,
            isAuto = isAuto,
            autoSpeak = autoSpeak,
            requestText = requestText,
            originalUserText = requestText,
            userMessage = when {
                isAuto -> "（用户没有新发言。请只根据最近群聊自然延续话题，不要替用户发言。）"
                autoSpeak -> "（群聊已空闲一段时间，干员们自然地闲聊起来，无需等待用户发言。）"
                else -> requestText
            },
            batchIds = emptySet(),
            retryMessageId = null,
            sourceMessageId = null,
            userMessageAlreadyStored = false,
            turnElapsedMs = { 0L },
        )
    )

    @Test
    fun systemLayersAndMessageOrderFollowOracle() = runBlocking {
        val h = harness()
        val result = assemble(h)
        val messages = result.apiMessages
        assertEquals("system", messages.first().role)
        val system = messages.first().content
        // 四层：安全边界 → 行为模块 → 群聊回复原则 → 模板渲染 → 输出协议 → 旁白协议。
        assertTrue(system.contains("【应用保护规则】"), system)
        assertTrue(system.contains("【群聊回复原则】"), system)
        assertTrue(system.contains("【群聊回合状态】") || system.contains("【发言人"), "模板渲染应进入 system")
        assertTrue(system.contains("【群聊回复格式】"), "固定输出协议必须注入")
        // 用户本轮消息 + 输出检查清单压尾。
        val userMessages = messages.filter { it.role == "user" }
        assertTrue(userMessages.last().content.contains("【本轮输出检查清单】"), userMessages.last().content)
        assertTrue(userMessages.any { it.content.contains("【用户本轮消息】") })
        assertTrue(userMessages.any { it.content.contains("用户：你们在吗？") })
    }

    @Test
    fun onlineModeForbidsNarrationOfflineAllowsIt() = runBlocking {
        val h = harness()
        val online = assemble(h, mode = "online")
        val offline = assemble(h, mode = "offline")
        assertTrue(online.apiMessages.first().content.contains("不得输出旁白"))
        assertFalse(online.apiMessages.first().content.contains("【旁白硬规则】"))
        assertTrue(offline.apiMessages.first().content.contains("【旁白硬规则】"), "线下模式必须注入旁白硬规则")
        assertTrue(offline.apiMessages.first().content.contains("共享场景"))
    }

    @Test
    fun requiredReadsPopulateSessionAndMembers() = runBlocking {
        val h = harness()
        val result = assemble(h)
        assertEquals(listOf("amiya", "blaze"), result.activeMembers.map { it.id })
        assertEquals("测试群", result.session.operatorName)
        assertTrue(result.apiMessages.any { it.content.contains("名字：阿米娅") }, "成员人设块必须注入")
        assertTrue(result.apiMessages.any { it.content.contains("发言标识：amiya") || it.content.contains("amiya") })
    }

    @Test
    fun optionalContextBlocksFollowFixedOrderAndAreSkippedOnFailure() = runBlocking {
        val h = harness()
        h.settings.setMemoryInjectionAllowed("group_chat", "RELATIONSHIP", true)
        h.settings.setMemoryInjectionAllowed("group_chat", "MEMBER_PRIVATE_CHAT", true)
        val calls = mutableListOf<GroupOptionalContext>()
        h.context.blocks[GroupOptionalContext.RELATIONSHIP] = "关系背景_UNIQUE"
        h.context.blocks[GroupOptionalContext.GROUP_MEMORY] = "群记忆_UNIQUE"
        h.context.blocks[GroupOptionalContext.PUBLIC_MEMORY] = "公开记忆_UNIQUE"
        h.context.blocks[GroupOptionalContext.RECENT_SOCIAL] = "动态背景_UNIQUE"
        h.context.blocks[GroupOptionalContext.KNOWLEDGE_BINDINGS] = "book-1"
        h.context.blocks[GroupOptionalContext.KNOWLEDGE_RECALL] = "知识库召回_UNIQUE"
        h.extras.shortTerm[g] = groupMemory(g, "滚动摘要_UNIQUE")
        val recording = object : GroupContextSource {
            override suspend fun inMemoryOperators() = h.context.operators
            override suspend fun optionalBlock(
                block: GroupOptionalContext,
                requestText: String,
                members: List<com.rhodes.privatechat.shared.model.Operator>,
                groupPlotSummary: String,
                groupSummary: String,
            ): String {
                calls += block
                return h.context.blocks[block].orEmpty()
            }
        }
        val assembler = DefaultGroupPromptAssembler(
            store = h.store, extras = h.extras, contextSource = recording, promptSource = h.promptSource,
            settings = h.settings, config = h.config, clock = h.clock,
        )
        val result = assembler.assemble(
            GroupPromptBuildRequest(
                groupId = g, groupName = "测试群", mode = "online", isAuto = false, autoSpeak = false,
                requestText = "阿米娅在吗？", originalUserText = "阿米娅在吗？", userMessage = "阿米娅在吗？",
                batchIds = emptySet(), retryMessageId = null, sourceMessageId = null,
                userMessageAlreadyStored = false, turnElapsedMs = { 0L },
            )
        )
        assertEquals(
            listOf(
                GroupOptionalContext.RELATIONSHIP,
                GroupOptionalContext.MEMBER_PRIVATE,
                GroupOptionalContext.GROUP_MEMORY,
                GroupOptionalContext.PUBLIC_MEMORY,
                GroupOptionalContext.RECENT_SOCIAL,
                GroupOptionalContext.KNOWLEDGE_BINDINGS,
                GroupOptionalContext.KNOWLEDGE_RECALL,
            ),
            calls,
            "可选上下文必须按固定顺序读取（oracle :1180-1288）",
        )
        val merged = result.apiMessages.joinToString("\n") { it.content }
        listOf("关系背景_UNIQUE", "群记忆_UNIQUE", "公开记忆_UNIQUE", "动态背景_UNIQUE", "知识库召回_UNIQUE", "滚动摘要_UNIQUE")
            .forEach { assertTrue(merged.contains(it), "缺少上下文块：$it") }
    }

    @Test
    fun failingOptionalBlockDoesNotBreakReplyAssembly() = runBlocking {
        val h = harness()
        val throwing = object : GroupContextSource {
            override suspend fun inMemoryOperators() = h.context.operators
            override suspend fun optionalBlock(
                block: GroupOptionalContext,
                requestText: String,
                members: List<com.rhodes.privatechat.shared.model.Operator>,
                groupPlotSummary: String,
                groupSummary: String,
            ): String = throw IllegalStateException("memory read failed ${block.name}")
        }
        val assembler = DefaultGroupPromptAssembler(
            store = h.store, extras = h.extras, contextSource = throwing, promptSource = h.promptSource,
            settings = h.settings, config = h.config, clock = h.clock,
        )
        val result = assembler.assemble(
            GroupPromptBuildRequest(
                groupId = g, groupName = "测试群", mode = "online", isAuto = false, autoSpeak = false,
                requestText = "你们在吗？", originalUserText = "你们在吗？", userMessage = "你们在吗？",
                batchIds = emptySet(), retryMessageId = null, sourceMessageId = null,
                userMessageAlreadyStored = false, turnElapsedMs = { 0L },
            )
        )
        assertTrue(result.apiMessages.isNotEmpty(), "可选块失败不得阻断正常回复组装")
    }

    @Test
    fun autoTurnUsesAutoTemplateAndObservingTextWithoutUserMessage() = runBlocking {
        val h = harness()
        val result = assemble(h, isAuto = true, requestText = "")
        val merged = result.apiMessages.joinToString("\n") { it.content }
        assertFalse(merged.contains("【用户本轮消息】"), "auto 轮次没有用户发言")
        assertTrue(result.apiMessages.last().content.contains("【本轮输出检查清单】"))
        assertTrue(merged.contains("【本轮输出检查清单】"))
    }

    @Test
    fun groupTurnStateContinuityAndInheritedEmotionsAreInjected() = runBlocking {
        val h = harness()
        val recent = h.clock.now - 60_000L
        h.seedAiReply(
            id = 100L,
            timestamp = recent,
            results = listOf(
                com.rhodes.privatechat.shared.model.GroupMsgResult("阿米娅", "上一轮", "dialogue", "担忧", "amiya"),
                com.rhodes.privatechat.shared.model.GroupMsgResult("煌", "上一轮", "dialogue", "不耐烦", "blaze"),
            ),
        )
        h.settings.putGroupTurnState(
            g,
            GroupTurnState(
                currentTopic = "雨夜门口",
                userTurnType = "提问",
                currentAnchor = "昨晚的约定",
                turnAdvance = "解释迟到",
                threadStatus = "继续",
                nextFocus = "用户决定",
                memberEmotions = "amiya=担忧;blaze=不耐烦",
                emotionFallbackKeys = "blaze",
                updatedAt = recent,
            ),
        )
        val merged = assemble(h).apiMessages.joinToString("\n") { it.content }
        assertTrue(merged.contains("【已验证的群聊连续性状态】"), merged)
        assertTrue(merged.contains("雨夜门口"))
        assertTrue(merged.contains("【上轮成员情绪】"), merged)
        assertTrue(merged.contains("（沿用上一轮）"), "沿用值必须标注")
    }

    @Test
    fun customProtocolIsAppendedAfterFixedProtocolWithFixedPriority() = runBlocking {
        val h = harness()
        h.settings.savePromptModule("protocol", "group", "online", "用户自定义协议_UNIQUE", 39)
        val system = assemble(h).apiMessages.first().content
        assertTrue(system.contains("【用户自定义表达补充】"), system)
        assertTrue(system.contains("用户自定义协议_UNIQUE"))
        val customIndex = system.indexOf("【用户自定义表达补充】")
        val fixedIndex = system.indexOf("【应用固定输出协议，必须优先遵守】")
        assertTrue(customIndex in 0 until fixedIndex, "固定协议必须排在自定义之后（oracle :1483-1487）")
    }

    @Test
    fun pendingModeTransitionIsInjectedOnceInUserRuntimeMessage() = runBlocking {
        val h = harness()
        h.settings.putPendingGroupModeTransition(g, "用户把互动模式改成了面对面。")
        val result = assemble(h, mode = "offline")
        assertTrue(result.pendingModeTransition.contains("面对面"))
        val merged = result.apiMessages.joinToString("\n") { it.content }
        assertTrue(merged.contains("【本轮互动变化】"))
        assertTrue(merged.contains("用户把互动模式改成了面对面。"))
    }

    @Test
    fun historyExcludesCurrentBatchAndKeepsRecentSystemEvents() = runBlocking {
        val h = harness()
        h.seedAiReply(id = 100L, timestamp = 1_000L)
        h.store.seedMessage(
            com.rhodes.privatechat.shared.model.ChatMessage(
                id = 101L, sessionId = g, senderName = "系统", content = "旧系统消息",
                type = "system", timestamp = 2_000L, isMe = false,
            )
        )
        h.store.seedMessage(
            com.rhodes.privatechat.shared.model.ChatMessage(
                id = 102L, sessionId = g, senderName = "我", content = "历史用户消息",
                type = "text", timestamp = 500L, isMe = true,
            )
        )
        val result = assemble(h)
        val history = result.apiMessages.drop(1).dropLast(2)
        assertTrue(history.any { it.content.contains("上一轮") }, "历史 AI 回复应进入提示词")
        assertTrue(history.any { it.content.contains("系统：旧系统消息") }, "近期系统事件应进入提示词")
        assertTrue(history.any { it.content.contains("用户：历史用户消息") })
    }
}
