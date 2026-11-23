package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 段数设置纠错重试（T58 后修：设置不生效问题）。 */
class ChatEngineSegmentRetryTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "测试干员")

    private fun reply(vararg dialogue: String): String {
        val segments = dialogue.joinToString(",") { "{\"type\":\"dialogue\",\"content\":\"$it\"}" }
        return "{\"emotion\":\"平静\",\"state\":\"坐着\",\"location\":\"宿舍\",\"continuity\":\"继续\"," +
            "\"segments\":[$segments]}"
    }

    @Test
    fun wrongSegmentCount_triggersOneStructureRetry_andUsesCorrectedReply() = runBlocking {
        // 设置：本轮必须 2 段台词
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.settingsRepository.diaSegMin = 2
        harness.settingsRepository.diaSegMax = 2
        runBlocking {
            harness.store.seedSession(session())
            harness.store.seedOperator(Operator(id = "op1", name = "测试干员", privatePrompt = "测试人设"))
        }
        // 第一次 1 段（不符合设置）→ 纠错重试；第二次 2 段（符合）
        harness.aiEngine = FakeAiEngine(listOf(reply("第一段"), reply("第一段", "第二段")))
        val engine = harness.engine()

        engine.sendMessage("你好", session(), "online") {}
        withTimeoutOrNull(15_000L) {
            while (!harness.events.any { it is EngineEvent.ReplyStored }) kotlinx.coroutines.delay(10)
        }
        val stages = harness.aiEngine.requests.map { it.stage }
        assertTrue(stages.contains("model_primary_request"), "缺首次请求: $stages")
        assertTrue(stages.contains("model_structure_retry"), "段数不符应触发纠错重试: $stages")

        // 纠错请求里包含段数要求
        val retrySystem = harness.aiEngine.requests.first { it.stage == "model_structure_retry" }
            .messages.first { it.role == "system" }.content
        assertTrue(retrySystem.contains("段数纠错要求"), "纠错请求缺指令")

        // 最终落库的是第二次（2 段台词）的回复
        val stored = harness.store.getMessagesSync("s1").last { !it.isMe && it.type == "ai_json" }
        val parsed = DefaultReplyCodec().normalize(stored.content)
        val dialogueCount = parsed.segments.orEmpty().count { !it.type.equals("narration", true) && it.content.isNotBlank() }
        assertEquals(2, dialogueCount, "应采用纠错后的回复（2 段台词），实际=$dialogueCount")
    }

    @Test
    fun correctSegmentCount_doesNotRetry() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.settingsRepository.diaSegMin = 2
        harness.settingsRepository.diaSegMax = 2
        runBlocking {
            harness.store.seedSession(session())
            harness.store.seedOperator(Operator(id = "op1", name = "测试干员", privatePrompt = "测试人设"))
        }
        harness.aiEngine = FakeAiEngine(listOf(reply("第一段", "第二段"), reply("多余的第二次")))
        val engine = harness.engine()
        engine.sendMessage("你好", session(), "online") {}
        withTimeoutOrNull(15_000L) {
            while (!harness.events.any { it is EngineEvent.ReplyStored }) kotlinx.coroutines.delay(10)
        }
        val stages = harness.aiEngine.requests.map { it.stage }
        assertEquals(listOf("model_primary_request"), stages, "段数正确时不应重试")
    }
}
