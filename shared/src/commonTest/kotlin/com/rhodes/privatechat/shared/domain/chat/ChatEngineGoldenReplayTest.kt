@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.backup.BackupCrypto
import com.rhodes.privatechat.shared.backup.BackupFileReader
import com.rhodes.privatechat.shared.backup.GoldenBackupFixture
import com.rhodes.privatechat.shared.model.ChatSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 金样会话回放（验收 #6）：种子 = ios-port/golden/backup/minimal-1.14.27.zip 的 7 条消息
 * （id=1..7，text/ai_json/image 混合，含图片 JSON 与内嵌图引用）；引擎跑确定性 fake AI
 * （lead 裁决）回放「输入序列 → 消息序列」，diff 白名单 = 消息 id/timestamp/租约字段。
 */
class ChatEngineGoldenReplayTest {

    /** 金样 7 条消息的 (type, senderName, content, isMe) —— 与 zip 内 CHATS/010000.json 逐条一致。 */
    private data class SeedRow(val type: String, val senderName: String, val content: String, val isMe: Boolean)

    private val goldenSeed = listOf(
        SeedRow("text", "我", "今天天气不错，想出去走走。", true),
        SeedRow("ai_json", "金样演示员", """{"emotion": "平静", "location": "宿舍", "narration": "", "segments": [{"type": "dialogue", "content": "是啊，风很舒服。要一起去甲板上透透气吗？", "speaker": ""}, {"type": "narration", "content": "她朝你笑了笑", "speaker": ""}]}""", false),
        SeedRow("text", "我", "对了，昨天拍的那张照片你还记得吗？", true),
        SeedRow("image", "我", """{"imageUri": "file:///data/user/0/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png", "caption": "我拍的 1×1 测试图", "visionSummary": "一张纯色的极小图片"}""", true),
        SeedRow("ai_json", "金样演示员", """{"emotion": "开心", "segments": [{"type": "dialogue", "content": "记得呀，虽然它很小，但颜色很特别。", "speaker": ""}, {"type": "image", "content": "[图片]", "speaker": "", "imageUri": "file:///data/user/0/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png"}]}""", false),
        SeedRow("text", "金样演示员", "周末要不要一起出去逛逛？我知道一家不错的甜品店。", false),
        SeedRow("text", "我", "嗯，周末一起去吧。", true),
    )

    /** 回放输入：第 7 条用户消息的原文；fake AI 脚本输出（确定性「输入序列 → 消息序列」对）。 */
    private val replayInput = "嗯，周末一起去吧。"
    private val replayOutput =
        """{"emotion":"开心","state":"点头","location":"甜品店门口","continuity":"约定周末见面","segments":[{"type":"dialogue","content":"好呀，那周末见！","speaker":""}]}"""

    private fun loadGoldenSeed(): Pair<InMemoryChatStore, ChatSession> = runBlocking {
        // 金样绑定：zip 字节 sha256 必须与金样记录一致（GoldenBackupFixture.ZIP_SHA256）。
        assertEquals(GoldenBackupFixture.ZIP_SHA256, BackupCrypto.sha256Hex(GoldenBackupFixture.zipBytes))
        val archive = BackupFileReader().read(GoldenBackupFixture.zipBytes)
        val payload = archive.payload
        val messages = payload.content.messages.orEmpty()
        assertEquals(7, messages.size)
        assertEquals(goldenSeed.map { it.type }, messages.map { it.type })
        val goldenSession = payload.content.sessions.orEmpty().single()
        val goldenOperator = payload.content.operators.orEmpty().single()
        val store = InMemoryChatStore()
        store.seedSession(goldenSession)
        store.seedOperator(goldenOperator)
        messages.forEach { store.seedMessage(it) }
        store to goldenSession
    }

    @Test
    fun goldenSeedParsesExactlySevenMessages() {
        val (store, session) = loadGoldenSeed()
        runBlocking {
            assertEquals("session_golden", session.id)
            val rows = store.getMessagesSync("session_golden")
            assertEquals(7, rows.size)
            rows.forEachIndexed { index, row ->
                assertEquals(index + 1L, row.id)
                assertEquals(goldenSeed[index].type, row.type)
                assertEquals(goldenSeed[index].senderName, row.senderName)
                assertEquals(goldenSeed[index].content, row.content)
                assertEquals(goldenSeed[index].isMe, row.isMe)
            }
        }
    }

    @Test
    fun replayInputSequence_producesExpectedMessageSequence() = runBlocking {
        val (store, goldenSession) = loadGoldenSeed()
        val harness = ChatEngineTestHarness(store = store, config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine = FakeAiEngine(emptyList(), byUserContent = mapOf(replayInput to replayOutput))
        val engine = harness.engine()
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage(replayInput, goldenSession, "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == true)
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(15_000L) { job.join() } }

        // —— 消息序列 diff（白名单：id/timestamp/租约字段）——
        val rows = store.getMessagesSync("session_golden")
        assertEquals(9, rows.size)
        // 前 7 条：金样种子原样保留（内容/类型/顺序逐条一致）。
        rows.take(7).forEachIndexed { index, row ->
            assertEquals(goldenSeed[index].type, row.type)
            assertEquals(goldenSeed[index].senderName, row.senderName)
            assertEquals(goldenSeed[index].content, row.content)
            assertEquals(goldenSeed[index].isMe, row.isMe)
        }
        // 第 8 条：回放用户消息。
        assertEquals(8L, rows[7].id)
        assertEquals("text", rows[7].type)
        assertEquals(replayInput, rows[7].content)
        assertTrue(rows[7].isMe)
        // 第 9 条：确定性 fake AI 回复（脚本输出，JSON 语义比对）。
        assertEquals(9L, rows[8].id)
        assertEquals("ai_json", rows[8].type)
        assertTrue(!rows[8].isMe)
        val parsed = DefaultReplyCodec().normalize(rows[8].content)
        assertEquals("好呀，那周末见！", parsed.segments.orEmpty().firstOrNull()?.content)
        assertEquals("开心", parsed.emotion)
        // 租约闭环：turn succeeded。
        val turn = store.getTurn("private:session_golden:8")!!
        assertEquals("succeeded", turn.status)
        assertEquals(9L, turn.responseMessageId)
        // display events 不在回放管线内（UI 层 reveal，oracle MessageList.kt:140）。
        assertTrue(store.getDisplayEvents("session_golden").isEmpty())
    }

    @Test
    fun replayInputSnapshot_containsGoldenHistoryAndOrderedSystemLayers() = runBlocking {
        val (store, goldenSession) = loadGoldenSeed()
        val harness = ChatEngineTestHarness(store = store, config = ChatEngineConfig(debounceBeforeAiMillis = 10))
        harness.aiEngine = FakeAiEngine(emptyList(), byUserContent = mapOf(replayInput to replayOutput))
        // 金样回放必须跑真实提示词组装器（harness 默认 stub 只用于行为测试）。
        val assembler = PrivatePromptAssembler(store, harness.settingsRepository, DefaultReplyCodec(), harness.clock)
        val engine = ChatEngine(
            scope = harness.scope, store = store,
            allocator = com.rhodes.privatechat.shared.domain.data.MessageIdAllocator(
                com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource { store.blockingMaxId() }, harness.settingsRepository,
            ),
            settings = harness.settingsRepository, assembler = assembler, aiClient = harness.aiEngine,
            clock = harness.clock, timeSource = harness.timeSource, config = harness.config,
            events = EngineEventSink { harness.events += it },
            promptBuildDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2),
        )
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendMessage(replayInput, goldenSession, "online", onResponseComplete = { deferred.complete(it) })!!
        assertTrue(withTimeoutOrNull(15_000L) { deferred.await() } == true)
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(15_000L) { job.join() } }

        // —— 输入序列断言：fake AI 收到的提示词快照 ——
        val recorded = harness.aiEngine.requests.single()
        assertEquals("model_primary_request", recorded.stage)
        val system = recorded.messages.first { it.role == "system" }.content
        val markers = listOf("【应用保护规则】", "【角色身份】", "【回复格式】")
        val positions = markers.map { system.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        val joined = recorded.messages.joinToString("\n") { it.content }
        // 金样历史以提示词形态进入输入序列（台词/图片/纯文本三种）。
        assertTrue(joined.contains("【台词】是啊，风很舒服。要一起去甲板上透透气吗？"))
        assertTrue(joined.contains("【旁白】她朝你笑了笑"))
        assertTrue(joined.contains("用户发送图片：一张纯色的极小图片；附言：我拍的 1×1 测试图"))
        assertTrue(joined.contains("周末要不要一起出去逛逛？我知道一家不错的甜品店。"))
        // 回放输入以【用户本轮消息】压尾。
        assertTrue(recorded.messages.last { it.content.startsWith("【用户本轮消息】") }.content.contains("用户：嗯，周末一起去吧。"))
        assertTrue(recorded.messages.last().content.startsWith("【输出要求】"))
        // 内嵌图引用段（金样消息 5 的 image 段）按 oracle 段规则格式化为【台词】[图片]（MessageParser 忽略未知键）。
        assertTrue(joined.contains("【台词】[图片]"))
    }
}
