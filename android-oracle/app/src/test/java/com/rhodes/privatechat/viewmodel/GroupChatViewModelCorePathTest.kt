package com.rhodes.privatechat.viewmodel

import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T28 批 C 步骤 3 前置：GroupChatViewModel 核心路径测试替身（先建回归网，再谈替换）。
 *
 * 与 [ChatViewModelCorePathTest] 同一套宿主环境（真实 DB + MockEngine 模型替身），
 * 断言群聊的「用户行 / ai_json 条目 / 租约」三层可见结果。
 */
class GroupChatViewModelCorePathTest : RobolectricChatTest() {

    private fun tagged(vararg items: Pair<String, String>): String = items.joinToString(",", "[", "]") { (speaker, message) ->
        """{"speaker":"$speaker","message":"$message","type":"dialogue","emotion":"平静","speakerId":"op1"}"""
    }

    private suspend fun sendAndWait(vm: GroupChatViewModel, text: String = "大家好", groupId: String = "g1"): Boolean? {
        val completed = CompletableDeferred<Boolean>()
        vm.sendGroupMessage(groupId, "测试群", text, "online", onResponseComplete = { completed.complete(it) })
        return withTimeoutOrNull(90_000L) { completed.await() }
    }

    @Test
    fun sendGroupMessage_success_persistsTaggedReply() = runBlocking {
        env.seedGroup()
        env.model.reply = tagged("角色op1" to "你好呀")
        val vm = env.createGroupViewModel()
        assertEquals(true, sendAndWait(vm))
        val messages = env.repository.getMessagesSync("g1")
        val user = messages.single { it.isMe }
        assertEquals("大家好", user.content)
        val ai = messages.single { !it.isMe && it.type == "ai_json" }
        assertTrue("群回复条目应落库", ai.content.contains("你好呀"))
        assertEquals(ReplyTurnStatus.SUCCEEDED, env.repository.replyTurns.get("group:g1:${user.id}")!!.status)
    }

    @Test
    fun sendGroupMessage_missingConfig_blocksBeforePersisting() = runBlocking {
        env.seedGroup()
        env.settings.apiKey = ""
        val vm = env.createGroupViewModel()
        assertEquals(false, sendAndWait(vm))
        assertTrue("应给出配置错误", vm.lastSendError.value.isNotBlank())
        assertTrue(env.repository.getMessagesSync("g1").isEmpty())
    }

    @Test
    fun retryFailedMessage_afterGroupFailure_recovers() = runBlocking {
        env.seedGroup()
        env.model.reply = "不是可分发言人的格式"
        val vm = env.createGroupViewModel()
        assertEquals(false, sendAndWait(vm))
        val failed = env.repository.getMessagesSync("g1").single { it.isMe }
        assertEquals("send_failed", failed.type)
        env.model.reply = tagged("角色op1" to "重试后的群回复")
        vm.retryFailedMessage("g1", "测试群", failed.id, "online")
        assertTrue("重试应补上群回复", env.waitUntil(90_000L) {
            env.repository.getMessagesSync("g1").any { !it.isMe && it.type == "ai_json" }
        })
        val messages = env.repository.getMessagesSync("g1")
        assertEquals("重试不应新增用户行", 1, messages.count { it.isMe })
        assertTrue(messages.single { !it.isMe }.content.contains("重试后的群回复"))
    }

    @Test
    fun resumePersistedReply_completesInterruptedGroupTurn() = runBlocking {
        env.seedGroup()
        val userId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            "g1",
            ChatMessage(
                id = userId, sessionId = "g1", senderName = "我", content = "中断的群消息",
                type = "send_failed", mode = "online", timestamp = System.currentTimeMillis(), isMe = true,
            ),
        )
        env.seedReplyTurn(turnId = "group:g1:$userId", sessionId = "g1", sourceMessageId = userId)
        env.model.reply = tagged("角色op1" to "恢复后的群回复")
        val vm = env.createGroupViewModel()
        val completed = CompletableDeferred<Boolean>()
        vm.resumePersistedReply("g1", userId, onComplete = { completed.complete(it) })
        assertEquals(true, withTimeoutOrNull(90_000L) { completed.await() })
        val messages = env.repository.getMessagesSync("g1")
        assertTrue("恢复后应有群回复", messages.any { !it.isMe && it.type == "ai_json" && it.content.contains("恢复后的群回复") })
        assertEquals("失败消息类型应被重置", "text", messages.single { it.isMe }.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, env.repository.replyTurns.get("group:g1:$userId")!!.status)
    }

    @Test
    fun recallMessageSegment_marksTargetSegmentRecalled() = runBlocking {
        env.seedGroup()
        val userId = env.repository.getNextMessageId()
        val now = System.currentTimeMillis()
        env.repository.sendMessage(
            "g1",
            ChatMessage(id = userId, sessionId = "g1", senderName = "我", content = "看这段", type = "text", mode = "online", timestamp = now, isMe = true),
        )
        val aiId = env.repository.getNextMessageId()
        env.repository.sendMessage(
            "g1",
            ChatMessage(id = aiId, sessionId = "g1", senderName = "测试群", content = tagged("角色op1" to "第一句", "角色op1" to "第二句"), type = "ai_json", mode = "online", timestamp = now + 1, isMe = false),
        )
        val vm = env.createGroupViewModel()
        vm.setCurrentGroup("g1")
        assertTrue("群消息应加载到 VM", env.waitUntil(20_000L) { vm.groupMessages.value.isNotEmpty() })
        vm.recallMessageSegment(aiId, 1)
        assertTrue("分段撤回应落库 recalled 标记", env.waitUntil(20_000L) {
            env.repository.getMessageById(aiId)?.content?.contains("recalled") == true
        })
        val content = env.repository.getMessageById(aiId)!!.content
        assertTrue("首段应保留", content.contains("第一句"))
        assertTrue("次段应被标记", content.substringAfter("第一句").contains("recalled"))
    }

    @Test
    fun sendGroupMessage_allMembersMuted_returnsSystemMessageWithoutModelCall() = runBlocking {
        env.seedGroup(mutedIds = listOf("op1"))
        env.model.reply = tagged("角色op1" to "不该出现的回复")
        val vm = env.createGroupViewModel()
        // D3（T35 步骤 2 冻结）：oracle 基线 = 全员禁言路径把轮次标记为已处理（responseStored=true），回调 true。
        // 引擎默认返回 false；GroupChatSemantics(allMutedReportsStored = true) 复刻基线（见 ChatEngineSemanticsTest）。
        assertEquals("D3 基线：全员禁言回调 true", true, sendAndWait(vm))
        val messages = env.repository.getMessagesSync("g1")
        assertTrue("应落一条系统消息", messages.any { it.type == "system" && it.content.contains("禁言") })
        assertEquals("不应调用模型", 0, env.model.requests.size)
    }

    @Test
    fun sendGroupMessage_unparseableReply_marksSendFailed() = runBlocking {
        env.seedGroup()
        env.model.reply = "这不是任何可分发言人的格式"
        val vm = env.createGroupViewModel()
        assertEquals(false, sendAndWait(vm))
        val user = env.repository.getMessagesSync("g1").single { it.isMe }
        assertEquals("无法解析的回复应置 send_failed", "send_failed", user.type)
        assertEquals(0, env.repository.getMessagesSync("g1").count { it.type == "ai_json" })
    }
}
