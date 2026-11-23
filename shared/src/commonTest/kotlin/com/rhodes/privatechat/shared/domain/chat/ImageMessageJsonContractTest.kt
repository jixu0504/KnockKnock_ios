@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图片消息 JSON 契约（验收 #7）：占位 → visionSummary 回填 → status=failed 三态解析
 * （对照 oracle MessageParser.kt:137-157 与 ChatViewModel.kt:1859-1942）+ 图片管线行为。
 */
class ImageMessageJsonContractTest {

    private fun session() = ChatSession(id = "s1", operatorId = "op1", operatorName = "角色")

    @Test
    fun placeholderState_roundTrips() {
        val json = ImageMessageContract.placeholder("file:///img/a.png", "看这张图")
        val parsed = ImageMessageContract.parse(json)!!
        assertEquals("file:///img/a.png", parsed.imageUri)
        assertEquals("看这张图", parsed.caption)
        assertEquals("", parsed.visionSummary)
        assertFalse(parsed.isSendFailed)
    }

    @Test
    fun visionSummaryFill_truncatesTo800AndRoundTrips() {
        val long = "图".repeat(900)
        val json = ImageMessageContract.withVisionSummary("uri", "c", long)
        val parsed = ImageMessageContract.parse(json)!!
        assertEquals(800, parsed.visionSummary.length) // oracle :1940 ≤800 字
        assertFalse(parsed.isSendFailed)
    }

    @Test
    fun failedState_isSendFailedAndRoundTrips() {
        val json = ImageMessageContract.failed("uri", "c")
        val parsed = ImageMessageContract.parse(json)!!
        assertTrue(parsed.isSendFailed)
        assertEquals("", parsed.visionSummary)
    }

    @Test
    fun parseGoldenImageMessage_matchesBackupShape() {
        // ios-port/golden/backup/minimal-1.14.27.zip 消息 id=4 的 content。
        val golden = """{"imageUri": "file:///data/user/0/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png", "caption": "我拍的 1×1 测试图", "visionSummary": "一张纯色的极小图片"}"""
        val parsed = ImageMessageContract.parse(golden)!!
        assertEquals("file:///data/user/0/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png", parsed.imageUri)
        assertEquals("我拍的 1×1 测试图", parsed.caption)
        assertEquals("一张纯色的极小图片", parsed.visionSummary)
        assertFalse(parsed.isSendFailed)
    }

    @Test
    fun parseGarbage_returnsNull() {
        assertNull(ImageMessageContract.parse("not json"))
        assertNull(ImageMessageContract.parse(""))
    }

    @Test
    fun sendImageMessage_fullPipeline_placeholderThenSummaryThenReply() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        val engine = harness.engine(
            visionAnalyze = { """{"visibleSummary":"一张纯色小图","sceneQuality":"clear","confidence":0.9}""" },
        )
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendImageMessage(
            imageUri = "file:///img/a.png",
            imageForModel = "base64data",
            caption = "我拍的图",
            session = session(),
            mode = "online",
            onReplyComplete = { deferred.complete(it) },
        )!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == true)
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(15_000L) { job.join() } }
        val imageMsg = harness.store.getMessageById(1)!!
        assertEquals("image", imageMsg.type)
        val parsed = ImageMessageContract.parse(imageMsg.content)!!
        assertTrue(parsed.visionSummary.contains("visibleSummary")) // 回填成功
        assertFalse(parsed.isSendFailed)
        val reply = harness.store.getMessageById(2)!!
        assertEquals("ai_json", reply.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
        // 图片回复走正常管线但按 oracle 图片路径不结算亲密/龙门币。
        assertTrue(harness.settled().isEmpty())
        assertTrue(harness.replyStored().isNotEmpty())
    }

    @Test
    fun sendImageMessage_visionFailure_marksFailedJsonAndReleasesTurn() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        val engine = harness.engine(visionAnalyze = { null })
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendImageMessage(
            imageUri = "file:///img/a.png", imageForModel = "x", caption = "c", session = session(), mode = "online",
            onReplyComplete = { deferred.complete(it) },
        )!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == false)
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(15_000L) { job.join() } }
        val imageMsg = harness.store.getMessageById(1)!!
        assertTrue(ImageMessageContract.parse(imageMsg.content)!!.isSendFailed)
        assertTrue(harness.toastMessages().any { "识别失败" in it })
        // 租约释放回 pending：前台补算可重试。
        assertEquals(ReplyTurnStatus.PENDING, harness.store.getTurn("private:s1:1")!!.status)
        assertTrue(harness.replyStored().isEmpty())
    }

    @Test
    fun sendImageMessage_guardRejectsMissingVisionConfiguration() = runBlocking {
        val harness = ChatEngineTestHarness()
        val engine = harness.engine(visionConfigured = false)
        assertNull(engine.sendImageMessage("u", "x", session = session(), mode = "online"))
        assertTrue(harness.toastMessages().any { "识图模型" in it })
        // imageForModel 空 → 拒绝。
        val engine2 = harness.engine()
        assertNull(engine2.sendImageMessage("u", null, session = session(), mode = "online"))
        assertTrue(harness.toastMessages().any { "无法读取这张图片" in it })
    }

    @Test
    fun resumeImageReply_rebuildsFromPersistedJson() = runBlocking {
        val harness = ChatEngineTestHarness(config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.store.seedSession(session())
        val placeholder = ImageMessageContract.placeholder("file:///img/b.png", "旧图")
        harness.store.seedMessage(
            com.rhodes.privatechat.shared.model.ChatMessage(
                id = 1L, sessionId = "s1", senderName = "我", content = placeholder, type = "image", isMe = true,
            ),
        )
        harness.store.seedTurn(
            com.rhodes.privatechat.shared.model.ReplyTurn(
                "private:s1:1", "s1", "private", "image", 1L, "", "online", ReplyTurnStatus.PENDING,
                0, 0L, "", 0L, null, "", 1_000L, 1_000L, 0L,
            ),
        )
        val engine = harness.engine(imageForModelResolver = { "resolved-for-model" })
        val ok = engine.resumeImageReply("s1", 1L)
        assertTrue(ok)
        assertEquals("ai_json", harness.store.getMessageById(2)!!.type)
        assertEquals(ReplyTurnStatus.SUCCEEDED, harness.store.getTurn("private:s1:1")!!.status)
        // 缺失消息 / 非图片消息 → false。
        assertFalse(engine.resumeImageReply("s1", 99L))
        harness.store.seedMessage(
            com.rhodes.privatechat.shared.model.ChatMessage(
                id = 9L, sessionId = "s1", senderName = "我", content = "文本", type = "text", isMe = true,
            ),
        )
        assertFalse(engine.resumeImageReply("s1", 9L))
    }
}
