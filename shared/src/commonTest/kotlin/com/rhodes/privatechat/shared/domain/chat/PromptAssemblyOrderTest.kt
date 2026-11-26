package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 提示词组装顺序（T18 建议随本卡落地，契约 13/14）：
 * system = foundation(保护边界+behavior) → protocol(模板 system 层) → tagProtocol(输出协议) →
 * narrationProtocol(旁白协议) 依次拼接；信任上下文以【本轮参考资料】边界块（【资料开始】/【资料结束】）
 * 包裹；用户消息与【输出要求】固定押尾。
 */
class PromptAssemblyOrderTest {

    private val session = ChatSession(id = "s1", operatorId = "op1", operatorName = "金样演示员")

    private fun harness(): Pair<ChatEngineTestHarness, PrivatePromptAssembler> {
        val harness = ChatEngineTestHarness()
        val store = harness.store
        runBlocking {
            store.seedSession(session)
            store.seedOperator(Operator(id = "op1", name = "金样演示员", privatePrompt = "温柔的人设"))
            store.seedMessage(ChatMessage(id = 1, sessionId = "s1", senderName = "我", content = "今天天气不错，想出去走走。", type = "text", timestamp = 100L, isMe = true))
            store.seedMessage(
                ChatMessage(
                    id = 2, sessionId = "s1", senderName = "金样演示员",
                    content = """{"emotion":"平静","location":"宿舍","segments":[{"type":"dialogue","content":"是呀，风很舒服。","speaker":""},{"type":"narration","content":"她朝你笑了笑","speaker":""}]}""",
                    type = "ai_json", timestamp = 200L, isMe = false,
                ),
            )
            store.seedMessage(
                ChatMessage(
                    id = 3, sessionId = "s1", senderName = "我",
                    content = """{"imageUri":"file:///img/a.png","caption":"我拍的图","visionSummary":"一张纯色小图"}""",
                    type = "image", timestamp = 300L, isMe = true,
                ),
            )
            store.seedMessage(ChatMessage(id = 4, sessionId = "s1", senderName = "金样演示员", content = "周末一起出去逛逛？", type = "text", timestamp = 400L, isMe = false))
        }
        val assembler = PrivatePromptAssembler(
            store = store,
            settings = harness.settingsRepository,
            codec = DefaultReplyCodec(),
            clock = harness.clock,
        )
        return harness to assembler
    }

    @Test
    fun offlineSystemMessage_layersInFixedOrder() = runBlocking {
        val (_, assembler) = harness()
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "好呀，一起。", mode = "offline"))
        val system = messages.first { it.role == "system" }.content
        val markers = listOf(
            "【应用保护规则】", // foundation 首层（oracle :3628）
            "【每轮开口前先过一遍（内部，不要写出来）】", // behavior 模块（oracle :3334-3336）
            "【角色身份】", // protocol = 模板 system 层（TAG_PRIVATE_OFFLINE）
            "【回复格式】", // tagProtocol = outputProtocol 模块（oracle :3341）
            "【旁白硬规则】", // narrationProtocol（oracle :3391）
        )
        val positions = markers.map { system.indexOf(it) }
        assertTrue(positions.all { it >= 0 }, "缺层：$markers / $positions")
        assertEquals(positions.sorted(), positions) // 顺序契约
    }

    @Test
    fun onlineSystemMessage_omitsNarrationProtocolButKeepsOrder() = runBlocking {
        val (_, assembler) = harness()
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "嗯。", mode = "online"))
        val system = messages.first { it.role == "system" }.content
        assertTrue(!system.contains("【旁白硬规则】")) // online 无旁白协议（oracle :185 返回空串）
        val positions = listOf("【应用保护规则】", "【角色身份】", "【回复格式】").map { system.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun segmentBudgetSettings_reachPromptRules() = runBlocking {
        val (harness, assembler) = harness()
        // 先写 max 再写 min：避免 repo 的 coerceIn(min<=max) 把 min 压到旧 max
        harness.settingsRepository.diaSegMax = 6
        harness.settingsRepository.diaSegMin = 4
        harness.settingsRepository.narSegMax = 5
        harness.settingsRepository.narSegMin = 2
        harness.settingsRepository.diaMax = 321
        harness.settingsRepository.diaMin = 12
        harness.settingsRepository.narMax = 222
        harness.settingsRepository.narMin = 55

        val online = assembler.build(PromptBuildRequest(session = session, userContent = "嗯。", mode = "online"))
        val onlineSystem = online.first { it.role == "system" }.content
        assertTrue(onlineSystem.contains("4 到 6 段台词") || onlineSystem.contains("4~6段台词"), "线上模式缺少台词段数规则")
        assertTrue(onlineSystem.contains("12 到 321 字") || onlineSystem.contains("12~321字"), "线上模式缺少台词字数规则")

        val offline = assembler.build(PromptBuildRequest(session = session, userContent = "好呀。", mode = "offline"))
        val offlineSystem = offline.first { it.role == "system" }.content
        assertTrue((offlineSystem.contains("2 到 5 段【旁白】") && offlineSystem.contains("55 到 222 字")) || offlineSystem.contains("2~5段【旁白】，每段55~222字"), "线下模式缺少旁白预算规则")
        assertTrue((offlineSystem.contains("4 到 6 段【台词】") && offlineSystem.contains("12 到 321 字")) || offlineSystem.contains("4~6段【台词】，每段12~321字"), "线下模式缺少台词预算规则")
    }

    @Test
    fun trustedContextBlock_isWrappedWithBoundaries() = runBlocking {
        val (_, assembler) = harness()
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "继续", mode = "offline"))
        val trusted = messages.firstOrNull { it.role == "user" && it.content.contains("【本轮参考资料】") }
        assertTrue(trusted != null, "缺少【本轮参考资料】边界块")
        val content = trusted!!.content
        assertTrue(content.indexOf("【资料开始】") in 1 until content.indexOf("【资料结束】"))
        assertTrue(content.contains("以下内容由应用提供"))
    }

    @Test
    fun historyMessages_formattedAsScriptAndImage() = runBlocking {
        val (_, assembler) = harness()
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "嗯，周末一起去吧。", mode = "online"))
        val joined = messages.joinToString("\n") { it.content }
        // ai_json 历史 → 【台词】/【旁白】标签；图片历史 → 用户发送图片；纯文本 AI → 原文。
        assertTrue(joined.contains("【台词】是呀，风很舒服。"))
        assertTrue(joined.contains("【旁白】她朝你笑了笑"))
        assertTrue(joined.contains("用户发送图片：一张纯色小图；附言：我拍的图"))
        assertTrue(joined.contains("周末一起出去逛逛？"))
    }

    @Test
    fun userContentAndOutputRequirements_tailInFixedOrder() = runBlocking {
        val (_, assembler) = harness()
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "嗯，周末一起去吧。", mode = "online"))
        val last = messages.last()
        val secondLast = messages[messages.size - 2]
        assertTrue(secondLast.content.startsWith("【用户本轮消息】"))
        assertTrue(secondLast.content.contains("用户：嗯，周末一起去吧。"))
        assertTrue(last.content.startsWith("【输出要求】"))
        assertTrue(last.content.contains("第一行必须是【状态】"))
    }

    @Test
    fun userContentPlaceholder_renderedIntoCustomTemplate() = runBlocking {
        val (harness, _) = harness()
        harness.settingsRepository.saveCustomPromptTemplate("private", "online", "你是角色。用户说：{{USER_CONTENT}}", 39)
        val assembler = PrivatePromptAssembler(
            store = harness.store,
            settings = harness.settingsRepository,
            codec = DefaultReplyCodec(),
            clock = harness.clock,
        )
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "测试占位符", mode = "online"))
        val system = messages.first { it.role == "system" }.content
        // USER_CONTENT 在 system 层恒空（T18 PromptRender.CachePromptLayering 契约），内容押尾于【用户本轮消息】。
        assertTrue(system.contains("用户说："))
        assertTrue(!system.contains("用户说：测试占位符"))
        assertTrue(system.contains("【应用保护规则】")) // foundation 恒在最前
        // 【用户本轮消息】在【输出要求】之前（输出要求恒压尾）。
        assertTrue(messages[messages.size - 2].content.contains("用户：测试占位符"))
        assertTrue(messages.last().content.startsWith("【输出要求】"))
    }

    @Test
    fun userLastMessageDuplicate_removedFromHistory() = runBlocking {
        val (_, assembler) = harness()
        // 历史末条与 USER_CONTENT 相同 → 从历史剔除（oracle :3322-3324）。
        val messages = assembler.build(
            PromptBuildRequest(session = session, userContent = "周末一起出去逛逛？", mode = "online"),
        )
        val userRoleContents = messages.filter { it.role == "user" }.map { it.content }
        // 历史里的那条 "周末一起出去逛逛？" 不作为独立 history 行出现（仅【用户本轮消息】）。
        assertTrue(userRoleContents.none { it == "周末一起出去逛逛？" })
    }

    @Test
    fun settingsDrivenSegmentCounts_renderIntoTagProtocol() = runBlocking {
        val (harness, _) = harness()
        harness.settingsRepository.diaSegMin = 3
        harness.settingsRepository.diaSegMax = 5
        val assembler = PrivatePromptAssembler(
            store = harness.store, settings = harness.settingsRepository, codec = DefaultReplyCodec(), clock = harness.clock,
        )
        val messages = assembler.build(PromptBuildRequest(session = session, userContent = "你好", mode = "online"))
        val system = messages.first { it.role == "system" }.content
        assertTrue(system.contains("本轮必须输出3~5段台词"))
    }
}
