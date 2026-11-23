@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.domain.chat.ChatStore
import com.rhodes.privatechat.shared.domain.chat.FakeAiEngine
import com.rhodes.privatechat.shared.domain.chat.InMemoryChatStore
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.domain.chat.TestChatClock
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.domain.data.MessageMaxIdSource
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.GroupMsgResult
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.model.MemoryType
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.prompt.PromptTestSettings
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TestTimeSource

/** 群聊引擎测试环境：fake store + 确定性 fake AI + 可注入时钟/配置/事件。 */
class GroupEngineHarness(
    val store: InMemoryChatStore = InMemoryChatStore(),
    var aiClient: StreamingClient = FakeAiEngine(emptyList()),
    val settingsStore: PromptTestSettings = PromptTestSettings(),
    val config: GroupChatEngineConfig = GroupChatEngineConfig(),
    val timeSource: TestTimeSource = TestTimeSource(),
    val context: FakeGroupContextSource = FakeGroupContextSource(),
    val semantics: GroupChatSemantics = GroupChatSemantics(),
) {
    val settings = SettingsRepository(settingsStore)
    val clock = TestChatClock()
    val events = mutableListOf<GroupEngineEvent>()
    val extras = FakeGroupExtras()
    val promptSource = SettingsGroupPromptSource(settings, kotlinx.datetime.Clock.System)
    val scope = CoroutineScope(Dispatchers.Default)
    var aiError: Throwable? = null

    val assembler = DefaultGroupPromptAssembler(
        store = store,
        extras = extras,
        contextSource = context,
        promptSource = promptSource,
        settings = settings,
        config = config,
        clock = clock,
    )

    val engine = GroupChatEngine(
        scope = scope,
        store = store,
        extras = extras,
        allocator = MessageIdAllocator(MessageMaxIdSource { store.blockingMaxId() }, settings),
        settings = settings,
        assembler = assembler,
        aiClient = object : StreamingClient {
            override suspend fun complete(messages: List<AiMessage>, stage: String): String {
                aiError?.let { throw it }
                return aiClient.complete(messages, stage)
            }
        },
        contextSource = context,
        promptSource = promptSource,
        clock = clock,
        timeSource = timeSource,
        config = config,
        semantics = semantics,
        events = { events += it },
        visionConfigured = { true },
        visionAnalyze = { """{"visibleSummary":"桌上有一只猫","sceneQuality":"clear"}""" },
        imageForModelResolver = { it },
        chatConfigError = { null },
    )

    suspend fun seedGroup(
        groupId: String = GROUP_ID,
        groupName: String = "测试群",
        members: List<Operator> = defaultOperators(),
        memberIds: List<String>? = null,
        mutedMembers: String = "",
        rules: String = "群规：保持角色",
    ): ChatSession {
        context.operators = members
        extras.operators = members
        val session = ChatSession(
            id = groupId,
            operatorId = "group_$groupId",
            operatorName = groupName,
            members = (memberIds ?: members.map { it.id }).joinToString(","),
            rules = rules,
            mutedMembers = mutedMembers,
            mode = "online",
        )
        store.seedSession(session)
        extras.sessions = listOf(session)
        return session
    }

    suspend fun seedAiReply(
        groupId: String = GROUP_ID,
        id: Long = 100L,
        timestamp: Long = 1_000L,
        results: List<GroupMsgResult> = listOf(GroupMsgResult("阿米娅", "上一轮台词。", "dialogue", "担忧", "amiya")),
    ): ChatMessage {
        val message = ChatMessage(
            id = id, sessionId = groupId, senderName = "测试群",
            content = GroupReplyJson.encodeResults(results), type = "ai_json", mode = "online",
            timestamp = timestamp, isMe = false,
        )
        store.seedMessage(message)
        return message
    }

    suspend fun seedUserMessage(
        groupId: String = GROUP_ID,
        id: Long = 1L,
        type: String = "text",
        content: String = "你好",
        timestamp: Long = 1_000L,
    ): ChatMessage {
        val message = ChatMessage(
            id = id, sessionId = groupId, senderName = "我", content = content,
            type = type, mode = "online", timestamp = timestamp, isMe = true,
        )
        store.seedMessage(message)
        return message
    }

    /** 轮询等待离线性条件成立（图片/礼物管线为 fire-and-forget 后续轮次）。 */
    fun awaitUntil(timeoutMs: Long = 10_000L, condition: suspend () -> Boolean): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) kotlinx.coroutines.delay(10)
            true
        } == true
    }

    fun awaitJob(job: Job?, timeoutMs: Long = 15_000L): Boolean = runBlocking {
        if (job == null) return@runBlocking false
        withTimeoutOrNull(timeoutMs) { job.join() } != null
    }

    fun send(
        groupId: String = GROUP_ID,
        text: String = "你们在吗？",
        mode: String = "online",
        isAuto: Boolean = false,
        autoGeneration: Long? = null,
        autoPlanToken: String? = null,
        autoPlanRevision: Long? = null,
        userMessageAlreadyStored: Boolean = false,
        sourceMessageId: Long? = null,
        retryMessageId: Long? = null,
        replyTurnIdOverride: String? = null,
        replyLeaseTokenOverride: String? = null,
        onReplyTurnClaimed: suspend () -> Unit = {},
    ): Pair<Job?, CompletableDeferred<Boolean>> {
        val deferred = CompletableDeferred<Boolean>()
        val job = engine.sendGroupMessage(
            groupId = groupId,
            groupName = "测试群",
            text = text,
            mode = mode,
            isAuto = isAuto,
            autoGeneration = autoGeneration,
            autoPlanToken = autoPlanToken,
            autoPlanRevision = autoPlanRevision,
            userMessageAlreadyStored = userMessageAlreadyStored,
            sourceMessageId = sourceMessageId,
            retryMessageId = retryMessageId,
            replyTurnIdOverride = replyTurnIdOverride,
            replyLeaseTokenOverride = replyLeaseTokenOverride,
            onReplyTurnClaimed = onReplyTurnClaimed,
            onResponseComplete = { deferred.complete(it) },
        )
        return job to deferred
    }

    fun toastMessages(): List<String> = events.filterIsInstance<GroupEngineEvent.Toast>().map { it.message }
    fun undelivered(): List<GroupEngineEvent.Undelivered> = events.filterIsInstance<GroupEngineEvent.Undelivered>()
    fun settled(): List<GroupEngineEvent.Settled> = events.filterIsInstance<GroupEngineEvent.Settled>()
    fun replies(): List<GroupEngineEvent.ReplyStored> = events.filterIsInstance<GroupEngineEvent.ReplyStored>()
    fun maintenance(): List<GroupEngineEvent.MaintenanceRequested> =
        events.filterIsInstance<GroupEngineEvent.MaintenanceRequested>()

    suspend fun messages(groupId: String = GROUP_ID): List<ChatMessage> = store.getMessagesSync(groupId)

    companion object {
        const val GROUP_ID = "group_test"

        fun defaultOperators(): List<Operator> = listOf(
            Operator(id = "amiya", name = "阿米娅"),
            Operator(id = "blaze", name = "煌"),
        )

        /** 合法 tagged 群回复（含六字段状态 + 两位成员）。 */
        fun taggedReply(
            topic: String = "测试主线",
            userTurnType: String = "提问",
            threadStatus: String = "继续",
            nextFocus: String = "用户回应",
            amiyaEmotion: String = "担忧",
            blazeEmotion: String = "不耐烦",
            amiyaLine: String = "我在听。",
            blazeLine: String = "随便。",
            narration: String? = null,
        ): String = buildString {
            appendLine("【群聊回合状态】")
            appendLine("【当前主线】$topic")
            appendLine("【用户本轮作用】$userTurnType")
            appendLine("【本轮承接】上一轮的约定")
            appendLine("【本轮新增推进】成员们作出回应")
            appendLine("【主线状态】$threadStatus")
            appendLine("【下轮焦点】$nextFocus")
            appendLine("【成员心情】amiya=$amiyaEmotion;blaze=$blazeEmotion")
            if (narration != null) {
                appendLine("【旁白】")
                appendLine(narration)
            }
            appendLine("【发言人: amiya】")
            appendLine("【心情】$amiyaEmotion")
            appendLine(amiyaLine)
            appendLine("【发言人: blaze】")
            appendLine("【心情】$blazeEmotion")
            appendLine(blazeLine)
        }.trim()
    }
}

/** 可配置上下文源（默认空注入，测试按需喂块）。 */
class FakeGroupContextSource : GroupContextSource {
    var operators: List<Operator> = emptyList()
    val blocks = mutableMapOf<GroupOptionalContext, String>()
    var inMemoryRoster: List<Operator>? = null

    override suspend fun inMemoryOperators(): List<Operator> = inMemoryRoster ?: operators

    override suspend fun optionalBlock(
        block: GroupOptionalContext,
        requestText: String,
        members: List<Operator>,
        groupPlotSummary: String,
        groupSummary: String,
    ): String = blocks[block].orEmpty()
}

/** 可配置 extras（会话清单/角色名册/摘要）。 */
class FakeGroupExtras : GroupChatExtras {
    var sessions: List<ChatSession> = emptyList()
    var operators: List<Operator> = emptyList()
    val shortTerm = mutableMapOf<String, Memory>()
    val daily = mutableMapOf<String, Memory>()
    var deletedTurnsFor: String? = null

    override suspend fun allSessions(): List<ChatSession> = sessions
    override suspend fun allOperators(): List<Operator> = operators
    override suspend fun shortTermSummary(sessionId: String): Memory? = shortTerm[sessionId]
    override suspend fun latestDailySummary(sessionId: String): Memory? = daily[sessionId]
    override suspend fun deleteReplyTurnsBySession(sessionId: String) {
        deletedTurnsFor = sessionId
    }
}

/** 构造测试用记忆（摘要块）。 */
fun groupMemory(sessionId: String, content: String, createdAt: Long = 1_000L) = Memory(
    id = 0, sessionId = sessionId, operatorId = "", type = MemoryType.SHORT_TERM,
    content = content, createdAt = createdAt,
)

/** 记录完整请求文本的 fake AI（供上下文降级/内容重试断言输入序列）。 */
class RecordingAiClient(
    private val handler: suspend (List<AiMessage>, String) -> String,
) : StreamingClient {
    val requests = mutableListOf<Pair<String, List<AiMessage>>>()
    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        requests += stage to messages.map { it.copy() }
        return handler(messages, stage)
    }
}
