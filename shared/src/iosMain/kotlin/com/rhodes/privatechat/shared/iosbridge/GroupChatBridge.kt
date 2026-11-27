package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.chat.RepositoryChatStore
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.domain.chat.group.DefaultGroupPromptAssembler
import com.rhodes.privatechat.shared.domain.chat.group.GroupChatEngine
import com.rhodes.privatechat.shared.domain.chat.group.GroupChatEngineConfig
import com.rhodes.privatechat.shared.domain.chat.group.GroupContextSource
import com.rhodes.privatechat.shared.domain.chat.group.GroupEngineEvent
import com.rhodes.privatechat.shared.domain.chat.group.GroupImageJsonContract
import com.rhodes.privatechat.shared.domain.chat.group.GroupReplyParser
import com.rhodes.privatechat.shared.domain.chat.group.RepositoryGroupChatExtras
import com.rhodes.privatechat.shared.domain.chat.group.SettingsGroupPromptSource
import com.rhodes.privatechat.shared.domain.data.DatabaseMessageMaxIdSource
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.GroupMsgResult
import com.rhodes.privatechat.shared.network.ProviderConfigResolver
import com.rhodes.privatechat.shared.network.RealProviderStreamingClient
import com.rhodes.privatechat.shared.platformSynchronized
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 群聊桥（T43）：真实 [GroupChatEngine] + 真实 DB + 真实模型/演示回退。
 *
 * 与私聊桥（[ChatEngineBridge]）的分工完全一致：
 * - 引擎负责群聊轮次状态机、租约、成员权限、提示词组装、解析与落库（`ai_json` 整包）；
 * - 桥负责把回调式 API 暴露给 Swift（onDelta 为落库成功后对可见文本的打字机模拟；
 *   引擎不产生 token 流），并负责群列表/群详情/成员/群消息分页的只读投影；
 * - AI 通道：已配置真实供应商走 [RealProviderStreamingClient]（Ktor Darwin SSE，surface="group"），
 *   否则回退本地确定性演示通道（输出符合群聊 tagged 协议的回复）并以 demo 标记。
 *
 * 群会话语义（Android 原版）：`chat_sessions.operatorId LIKE 'group_%'`，`members` 逗号分隔
 * operatorId，`mutedMembers` 逗号分隔 id 或名字，`rules` 为群规则；群消息 `type=ai_json`
 * 内容为 `List<GroupMsgResult>` JSON（多发言人 + 旁白 + 心情）。
 *
 * 线程：查询/发送在 [workScope]（Default），回调一律在 [mainScope]（Main）。
 * 不导出 Flow/StateFlow/suspend。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "GroupChatBridge")
class GroupChatBridge internal constructor(
    private val chatRepo: ChatRepository,
    dbWrapper: DatabaseWrapper,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
    private val sessionProvider: SessionListBridge,
) {
    private val lock = Any()

    private val store = RepositoryChatStore(chatRepo)
    private val extras = RepositoryGroupChatExtras(chatRepo)
    private val config = GroupChatEngineConfig().apply {
        deepseekThinkingActive = isDeepseekThinking()
    }

    private val providerResolver = ProviderConfigResolver(settingsRepo)
    private val realProviderClient = RealProviderStreamingClient(providerResolver, surface = "group")
    private val aiRouter = GroupRoutingStreamingClient(
        resolver = providerResolver,
        demo = DemoGroupStreamingClient(),
        real = realProviderClient,
    )

    private val engine = GroupChatEngine(
        scope = workScope,
        store = store,
        extras = extras,
        allocator = MessageIdAllocator(DatabaseMessageMaxIdSource(dbWrapper.database), settingsRepo),
        settings = settingsRepo,
        assembler = DefaultGroupPromptAssembler(
            store = store,
            extras = extras,
            contextSource = EmptyGroupContextSource,
            promptSource = SettingsGroupPromptSource(settingsRepo),
            settings = settingsRepo,
            config = config,
        ),
        aiClient = aiRouter,
        config = config,
        events = { event -> onEngineEvent(event) },
    )

    // MARK: - T56 A7 自动化接线钩子（计划状态 → DelayTaskLayer；默认 no-op，不改变既有行为）

    /**
     * 群自动互动计划变化监听（T56）：A7Glue 设置后，引擎每次 `AutoPlanChanged` 都会回调
     * `(groupId, plan)`，宿主据此重排 `DelayedTaskLayer`。
     * 计划取消时 plan 可能为 null（`state == "cancelled"` 时仍带 plan，由 A7Glue 统一判空）。
     */
    internal var autoPlanListener: ((String, SettingsRepository.GroupAutoChatPlan?) -> Unit)? = null

    internal fun setAutoPlanListener(listener: ((String, SettingsRepository.GroupAutoChatPlan?) -> Unit)?) {
        autoPlanListener = listener
    }

    /** 供 A7Glue 直接驱动自动轮次（到期执行体）/计划重建的唯一入口。 */
    internal fun domainEngine(): GroupChatEngine = engine

    // MARK: - 发送捕获状态（与 ChatEngineBridge 同款单飞语义）

    private var activeJob: Job? = null
    private var activeSessionId: String = ""
    private var activeViewingGroup: String = ""
    private var capturedReplyId: Long = 0L
    private var capturedUserIds: Set<Long> = emptySet()
    private var capturedError: String = ""

    /** 群聊回复落库后的维护触发（T62：群聊摘要/记忆维护此前无调用点）。 */
    private var replyMaintenanceHook: ((String) -> Unit)? = null

    internal fun setReplyMaintenanceHook(hook: ((String) -> Unit)?) {
        replyMaintenanceHook = hook
    }
    private var capturedSpeakers: List<String> = emptyList()
    private var capturedNarrationCount: Int = 0

    // ==================================================================
    // 群列表 / 群详情 / 成员（只读，真实 DB）
    // ==================================================================

    /**
     * 群列表：`chat_sessions` 过滤 `operatorId LIKE 'group_%'`，含群名 / 成员数 / 禁言数 /
     * 最后消息 / 未读 / 置顶 / 自动互动开关状态。
     *
     * 首次调用若一个群都没有，会幂等播种预设角色 + 预设阵营群（`group_f01`~`group_f21`，Android
     * SessionRepository.initPresetGroups 等价语义），保证全新安装也能进入群聊。
     */
    fun listGroups(onResult: (List<GroupSummaryDto>, String?) -> Unit) {
        workScope.launch {
            try {
                ensureGroupsSeeded()
                val operators = chatRepo.getAllOperatorsSync().associateBy { it.id }
                val groups = chatRepo.getAllSessionsSync()
                    .filter { it.operatorId.startsWith("group_") || it.id.startsWith("group_") }
                    .map { it.toSummaryDto(operators) }
                mainScope.launch { onResult(groups, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取群列表失败") }
            }
        }
    }

    /** 群详情 + 成员名册（成员 / 禁言 / 权限 / 自动互动状态）。 */
    fun groupDetail(sessionId: String, onResult: (GroupDetailDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val session = chatRepo.getSession(sessionId)
                    ?: throw IllegalStateException("群聊不存在：$sessionId")
                val operators = chatRepo.getAllOperatorsSync().associateBy { it.id }
                val dto = session.toDetailDto(operators)
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取群详情失败") }
            }
        }
    }

    // ==================================================================
    // 群消息分页（复用 MessageHistoryBridge 的游标语义，群聊专属解析）
    // ==================================================================

    /**
     * 分页查询群消息。`beforeId <= 0` 取首屏；否则传上一页 `nextBeforeId`。
     * items 按 id 倒序（最新在前）。AI 行被拆成多发言人 segments（speakerName/emotion/旁白/撤回）。
     */
    fun page(sessionId: String, beforeId: Long, limit: Int, onResult: (GroupMessagePageDto, String?) -> Unit) {
        val safeLimit = if (limit <= 0) 50 else limit.coerceAtMost(200)
        workScope.launch {
            try {
                val page = loadPage(sessionId, beforeId, safeLimit)
                mainScope.launch { onResult(page, null) }
            } catch (t: Throwable) {
                mainScope.launch {
                    onResult(GroupMessagePageDto(emptyList(), false, 0L, 0), t.message ?: "读取群消息失败")
                }
            }
        }
    }

    private suspend fun loadPage(sessionId: String, beforeId: Long, limit: Int): GroupMessagePageDto {
        val session = chatRepo.getSession(sessionId)
            ?: throw IllegalStateException("群聊不存在：$sessionId")
        val operators = chatRepo.getAllOperatorsSync().associateBy { it.id }
        val fetch = limit + 1
        val rows: List<ChatMessage> = if (beforeId <= 0L) {
            chatRepo.messages.getRecentMessagesSync(sessionId, fetch.toLong())
        } else {
            val anchorTimestamp = chatRepo.messages.getMessageById(beforeId)?.timestamp
            if (anchorTimestamp != null) {
                chatRepo.messages.getMessagesBefore(sessionId, anchorTimestamp, beforeId, fetch.toLong())
            } else {
                chatRepo.messages.getRecentMessagesSync(sessionId, fetch.toLong())
            }
        }
        val hasMore = rows.size > limit
        val pageRows = if (hasMore) rows.subList(0, limit) else rows
        val items = pageRows.map { it.toGroupMessageDto(session, operators) }
        val totalCount = runCatching { chatRepo.messages.countMessages(sessionId).toInt() }.getOrDefault(0)
        return GroupMessagePageDto(
            items = items,
            hasMore = hasMore,
            nextBeforeId = items.lastOrNull()?.id ?: 0L,
            totalCount = totalCount,
        )
    }

    // ==================================================================
    // 发送（真实 GroupChatEngine）
    // ==================================================================

    /**
     * 发送一条群消息并返回取消句柄。
     *
     * - 引擎在请求模型前同步落库用户消息（`type=text`）+ reply_turn（surface="group"）；
     * - onDelta 在 AI 回复整包落库后按 4 字/25ms 分片回调（可见文本为「成员名：台词」逐行）；
     * - onDone 在整轮结束后回调一次（成功/失败均回调，error 非空表示失败）。
     */
    fun send(
        sessionId: String,
        text: String,
        onDelta: (String) -> Unit,
        onDone: (GroupSendResultDto) -> Unit,
    ): IosCancellable {
        val cancellable = IosCancellable { cancelActive() }
        val job = workScope.launch {
            try {
                val session = chatRepo.getSession(sessionId)
                    ?: throw IllegalStateException("群聊不存在：$sessionId")
                platformSynchronized(lock) {
                    activeSessionId = session.id
                    capturedReplyId = 0L
                    capturedUserIds = emptySet()
                    capturedError = ""
                    capturedSpeakers = emptyList()
                    capturedNarrationCount = 0
                }
                val engineJob = engine.sendGroupMessage(
                    groupId = session.id,
                    groupName = session.operatorName,
                    text = text,
                    mode = session.mode,
                    onResponseComplete = { stored -> completeSend(session, stored, onDelta, onDone) },
                )
                if (engineJob == null) {
                    val reason = platformSynchronized(lock) { capturedError }
                        .ifBlank { "群消息未发送（引擎拒绝）" }
                    mainScope.launch {
                        onDone(GroupSendResultDto(false, 0L, 0L, "", "", 0, reason, aiRouter.lastWasDemo))
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                mainScope.launch {
                    onDone(GroupSendResultDto(false, 0L, 0L, "", "", 0, t.message ?: "群消息发送失败", aiRouter.lastWasDemo))
                }
            }
        }
        val previous = platformSynchronized(lock) {
            val old = activeJob
            activeJob = job
            old
        }
        previous?.cancel()
        return cancellable
    }

    /** 取消在飞群聊轮次（引擎 generation 失效 + job 取消），幂等。 */
    fun cancel() = cancelActive()

    /** 当前查看的群（未读自增排除当前群；Swift 进入/退出群聊页时更新）。 */
    fun setActiveGroup(sessionId: String) {
        platformSynchronized(lock) { activeViewingGroup = sessionId }
    }

    // ==================================================================
    // 群操作：自动互动开关 / 分段撤回 / 重试 / 新建群聊
    // ==================================================================

    /** 自动互动开关（Android GroupChatViewModel.setAutoGroupChatEnabled 等价）。回调 (状态文案, 错误)。 */
    /**
     * 切换群聊模式（online / offline / director）→ 写 `chat_sessions.mode`，并让引擎失效该群缓存，
     * 使下一轮 [send] 按新模式的群回复规则（线上纯文字 / 线下·导演带【旁白】）组装提示词。
     *
     * 与私聊 [SessionListBridge.updateMode] 的差异：群聊不写 `settings.last_mode_<operatorId>`
     * （该键只用于私聊恢复上次模式，写成 last_mode_group_xx 是无意义的脏数据）。
     */
    /**
     * 撤回自己发的群消息（群聊没有「对方」概念：撤回＝从本机群记录中移除该条并向所有成员隐藏）。
     * 与私聊 `recallMessage` 同语义：删行 + 清理该消息触发的回复轮次 + 重算会话预览。
     */
    fun recallGroupMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) { removeGroupMessage(sessionId, messageId) }

    /** 本地删除群消息（不撤回语义；仅删本机 DB 行并重算会话预览）。 */
    fun deleteGroupMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) { removeGroupMessage(sessionId, messageId) }

    /** 撤回/删除共用实现：删消息行 → 清理关联回复轮次 → 重算会话预览 → 校验是否真的没了。 */
    private suspend fun removeGroupMessage(sessionId: String, messageId: Long): String? {
        val message = chatRepo.getMessageById(messageId) ?: return null // 已不存在视作成功
        chatRepo.deleteMessage(messageId)
        runCatching { chatRepo.deleteReplyTurnsBySourceMessage(messageId) }
        runCatching { chatRepo.deleteMessageDisplayEvents(messageId) }
        refreshGroupPreview(sessionId)
        engine.invalidateGroup(sessionId)
        return if (chatRepo.getMessageById(messageId) != null) "删除失败（消息仍在）" else null
    }

    /** 删消息后重算会话预览（把最后一条消息的文案/时间写回 chat_sessions）。 */
    private suspend fun refreshGroupPreview(sessionId: String) {
        val latest = chatRepo.getRecentMessagesSync(sessionId, 1).firstOrNull()
        chatRepo.updateLastMessage(
            sessionId,
            latest?.let { groupPreviewText(it) }.orEmpty(),
            latest?.timestamp ?: Clock.System.now().toEpochMilliseconds(),
        )
    }

    /** 群会话预览文案：图片/礼物/系统/ai_json 分别取合适的摘要。 */
    private fun groupPreviewText(message: ChatMessage): String = when {
        message.type == "image" -> "[图片]"
        message.type == "gift_hidden" || message.type == "gift_reply_failed" -> "[礼物]"
        message.type == "system" -> ""
        message.type == "ai_json" -> stripAiJsonForPreview(message.content).take(50)
        else -> message.content.take(50)
    }

    /**
     * ai_json 内容的可读摘要：按行扫描取出 `"speech": "..."` 的台词，去掉【标签】与转义换行。
     * 不用正则、不用 JSON 解析器（预览文案只需粗略可读，避免引入依赖与转义风险）。
     */
    private fun stripAiJsonForPreview(raw: String): String {
        val speeches = mutableListOf<String>()
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim()
            val keyIndex = line.indexOf("\"speech\"")
            if (keyIndex < 0) continue
            val colon = line.indexOf(':', keyIndex + 8)
            if (colon < 0) continue
            var value = line.substring(colon + 1).trim()
            if (!value.startsWith("\"")) continue
            value = value.removePrefix("\"")
            val close = value.indexOf("\"")
            if (close >= 0) value = value.take(close)
            value = value.replace("\\n", " ").replace("\\\"", "\"").trim()
            if (value.isNotEmpty()) speeches.add(value)
        }
        val text = if (speeches.isNotEmpty()) speeches.joinToString(" ") else raw
        val sb = StringBuilder()
        var depth = 0
        for (ch in text) {
            when (ch) {
                '【' -> depth++
                '】' -> if (depth > 0) depth-- else sb.append(ch)
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString().replace("\\n", " ").trim()
    }

    fun setGroupMode(sessionId: String, mode: String, onDone: (String?) -> Unit) = runOperation(onDone) {
        val normalized = mode.trim().lowercase()
        require(normalized in CHAT_MODES) { "不支持的群聊模式：$mode" }
        val session = chatRepo.getSession(sessionId)
            ?: throw IllegalStateException("群聊不存在：$sessionId")
        if (session.mode != normalized) {
            chatRepo.sessions.updateSessionMode(sessionId, normalized)
        }
        engine.invalidateGroup(sessionId)
        null
    }

    fun setAutoGroupChatEnabled(sessionId: String, enabled: Boolean, onDone: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                engine.setAutoGroupChatEnabled(sessionId, enabled)
                val status = engine.autoGroupChatStatus(sessionId)
                mainScope.launch { onDone(status, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, t.message ?: "切换自动互动失败") }
            }
        }
    }

    /** 自动互动状态文案（同步读 settings；"本群已关闭" / "等待下一轮自动聊天（约N秒）" 等）。 */
    fun autoGroupChatStatus(sessionId: String): String = engine.autoGroupChatStatus(sessionId)

    /** 撤回 AI 消息的单个分段（群聊独有；全段撤回 → 引擎删整条，与 oracle 一致）。 */
    fun recallSegment(sessionId: String, messageId: Long, segmentIndex: Int, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            val before = chatRepo.messages.getMessageById(messageId) ?: return@runOperation "消息不存在"
            engine.recallMessageSegment(sessionId, messageId, segmentIndex)?.join()
            val after = chatRepo.messages.getMessageById(messageId)
            when {
                after == null -> null // 全部段已撤回 → 引擎删整条
                after.content != before.content -> null
                else -> "该分段撤回未生效"
            }
        }

    /** 重试未送达群消息（引擎仅接受 send_failed / gift_reply_failed）。 */
    fun retryMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            if (engine.retryFailedMessage(sessionId, messageId)) null else "该消息当前不可重试（仅「未送达」消息可重试）"
        }

    /**
     * 新建群聊（Android NewGroup 的最小可用等价：写 `chat_sessions` 一行，id=operatorId=`group_<hex>`）。
     * 成员只能选真实存在的 operator；成功后回调 (新群 chatId, 错误)。
     */
    fun createGroup(name: String, memberIds: List<String>, onDone: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                val groupName = name.trim()
                if (groupName.isEmpty()) throw IllegalArgumentException("群名不能为空")
                val operators = chatRepo.getAllOperatorsSync()
                val validIds = memberIds.map(String::trim).filter(String::isNotBlank).distinct()
                    .filter { id -> operators.any { it.id == id } }
                if (validIds.size < 2) throw IllegalArgumentException("至少选择 2 位群成员")
                val now = Clock.System.now().toEpochMilliseconds()
                val groupId = "group_" + now.toString(16) + Random.nextInt(0x1000, 0xFFFF).toString(16)
                chatRepo.insertSession(
                    ChatSession(
                        id = groupId,
                        operatorId = groupId,
                        operatorName = groupName,
                        lastMessage = "",
                        lastTime = now,
                        mode = "online",
                        members = validIds.joinToString(","),
                        rules = "",
                    ),
                )
                mainScope.launch { onDone(groupId, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, t.message ?: "新建群聊失败") }
            }
        }
    }

    /** 群聊当前是否演示通道（未配置模型供应商/无 Key）；UI 用于「演示」标记。 */
    fun isDemoMode(): Boolean = !providerResolver.resolve().isConfigured

    /** 媒体 ID → 本机绝对路径（群图片消息渲染用；与私聊桥同语义）。 */
    fun imagePath(imageId: String): String {
        if (imageId.isBlank()) return ""
        if (imageId.startsWith("http://") || imageId.startsWith("https://") || imageId.startsWith("data:")) return imageId
        val direct = imageId.removePrefix("file://")
        if (direct.startsWith("/") && com.rhodes.privatechat.shared.files.FileIo.exists(direct)) return direct
        return ""
    }

    // ==================================================================
    // 内部：分组捕获 / 回包 / 事件
    // ==================================================================

    private fun cancelActive() {
        val sessionId = platformSynchronized(lock) { activeSessionId }
        if (sessionId.isNotEmpty()) engine.cancelGroupRequests(sessionId)
        platformSynchronized(lock) { activeJob }?.cancel()
    }

    private fun runOperation(onDone: (String?) -> Unit, block: suspend () -> String?) {
        workScope.launch {
            val outcome = try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                t.message ?: "操作失败"
            }
            mainScope.launch { onDone(outcome) }
        }
    }

    private fun onEngineEvent(event: GroupEngineEvent) {
        when (event) {
            is GroupEngineEvent.ReplyStored -> {
                platformSynchronized(lock) {
                    if (activeSessionId.isEmpty() || event.groupId == activeSessionId) {
                        capturedReplyId = event.aiMessageId
                        capturedUserIds = event.userMessageIds
                        capturedSpeakers = event.speakers
                        capturedNarrationCount = event.narrationCount
                    }
                }
                // T62：群聊回复后触发上下文维护（摘要/记忆重试）。
                runCatching { replyMaintenanceHook?.invoke(event.groupId) }
            }
            is GroupEngineEvent.Toast -> platformSynchronized(lock) {
                if (capturedError.isEmpty()) capturedError = event.message
            }
            is GroupEngineEvent.Undelivered -> platformSynchronized(lock) {
                if (capturedError.isEmpty()) capturedError = "本轮未生成可见回复"
            }
            is GroupEngineEvent.AutoPlanChanged -> {
                val plan = if (event.state == "cancelled") null else runCatching {
                    runCatching { settingsRepo.getGroupAutoChatPlan(event.groupId) }.getOrNull()
                }.getOrNull()
                autoPlanListener?.invoke(event.groupId, plan)
            }
            is GroupEngineEvent.UnhideSession -> sessionProvider.unhide(event.groupId)
            is GroupEngineEvent.UnreadHint -> {
                val viewing = platformSynchronized(lock) { activeViewingGroup }
                if (event.groupId != viewing) {
                    workScope.launch { runCatching { chatRepo.incrementUnread(event.groupId, event.count) } }
                }
            }
            else -> Unit
        }
    }

    private fun completeSend(
        session: ChatSession,
        stored: Boolean,
        onDelta: (String) -> Unit,
        onDone: (GroupSendResultDto) -> Unit,
    ) {
        val (replyId, userIds, speakers, narrationCount, captured) = platformSynchronized(lock) {
            CapturedSend(capturedReplyId, capturedUserIds, capturedSpeakers, capturedNarrationCount, capturedError)
        }
        val demoRound = aiRouter.lastWasDemo
        val providerFailure = realProviderClient.lastError
        mainScope.launch {
            val message = if (replyId > 0L) runCatching { chatRepo.messages.getMessageById(replyId) }.getOrNull() else null
            val segments = message?.let { parseSegments(it) }.orEmpty()
            val text = segments.filter { it.text.isNotBlank() }
                .joinToString("\n") { "${it.speakerName}：${it.text}" }
            if (text.isNotEmpty()) {
                var index = 0
                while (index < text.length) {
                    val end = minOf(index + 4, text.length)
                    runCatching { onDelta(text.substring(index, end)) }
                    index = end
                    delay(25)
                }
            }
            var error = when {
                captured.isNotEmpty() -> captured
                stored && text.isNotEmpty() -> ""
                else -> "本轮未生成可见回复"
            }
            if (error.isNotEmpty() && providerFailure.isNotEmpty()) error = providerFailure
            runCatching {
                onDone(
                    GroupSendResultDto(
                        ok = stored && text.isNotEmpty(),
                        userMessageId = userIds.maxOrNull() ?: 0L,
                        aiMessageId = replyId,
                        text = text,
                        speakersCsv = speakers.joinToString("、"),
                        narrationCount = narrationCount,
                        error = error,
                        demo = demoRound,
                    ),
                )
            }
        }
    }

    private data class CapturedSend(
        val replyId: Long,
        val userIds: Set<Long>,
        val speakers: List<String>,
        val narrationCount: Int,
        val error: String,
    )

    // ==================================================================
    // 内部：DTO 映射
    // ==================================================================

    private suspend fun ensureGroupsSeeded() {
        chatRepo.ensurePresetOperators()
        if (chatRepo.getGroupCount() == 0) {
            chatRepo.initPresetGroups()
        }
    }

    private fun ChatSession.toSummaryDto(operators: Map<String, com.rhodes.privatechat.shared.model.Operator>): GroupSummaryDto {
        val memberIds = members.split(',').map(String::trim).filter(String::isNotBlank)
        val mutedIds = mutedMembers.split(',').map(String::trim).filter(String::isNotBlank)
        return GroupSummaryDto(
            sessionId = id,
            name = operatorName.ifBlank { operatorId },
            memberCount = memberIds.size,
            mutedCount = memberIds.count { memberId -> memberId in mutedIds || operators[memberId]?.name?.let { it in mutedIds } == true },
            lastMessage = lastMessage,
            lastTime = lastTime,
            unreadCount = unreadCount,
            isPinned = isPinned,
            mode = mode,
            autoEnabled = settingsRepo.getGroupAuto(id),
            autoStatus = runCatching { engine.autoGroupChatStatus(id) }.getOrDefault(""),
        )
    }

    private fun ChatSession.toDetailDto(
        operators: Map<String, com.rhodes.privatechat.shared.model.Operator>,
    ): GroupDetailDto {
        val memberIds = members.split(',').map(String::trim).filter(String::isNotBlank).distinct()
        val mutedIds = mutedMembers.split(',').map(String::trim).filter(String::isNotBlank)
        val memberDtos = memberIds.map { memberId ->
            val operator = operators[memberId]
            val muted = memberId in mutedIds || (operator != null && operator.name in mutedIds)
            GroupMemberDto(
                id = memberId,
                name = operator?.name ?: memberId,
                title = operator?.title.orEmpty(),
                avatarId = operator?.avatarUri.orEmpty(),
                isMuted = muted,
                isResolved = operator != null,
            )
        }
        return GroupDetailDto(
            sessionId = id,
            name = operatorName.ifBlank { operatorId },
            avatarId = avatarUri,
            mode = mode,
            rules = rules,
            memberCount = memberDtos.size,
            mutedCount = memberDtos.count { it.isMuted },
            members = memberDtos,
            lastMessage = lastMessage,
            lastTime = lastTime,
            unreadCount = unreadCount,
            isPinned = isPinned,
            autoEnabled = settingsRepo.getGroupAuto(id),
            autoMasterEnabled = settingsRepo.autoAiEnabled,
            autoStatus = runCatching { engine.autoGroupChatStatus(id) }.getOrDefault(""),
            autoMaxRounds = settingsRepo.groupAutoMaxRounds,
        )
    }

    private fun ChatMessage.toGroupMessageDto(
        session: ChatSession,
        operators: Map<String, com.rhodes.privatechat.shared.model.Operator>,
    ): GroupMessageDto {
        val segments = parseSegments(this).mapIndexed { index, segment ->
            GroupMessageSegmentDto(
                index = index,
                speakerName = segment.speakerName,
                speakerId = segment.speakerId,
                avatarId = operators[segment.speakerId]?.avatarUri.orEmpty(),
                text = segment.text,
                emotion = segment.emotion,
                isNarration = segment.isNarration,
                isRecalled = segment.isRecalled(index),
            )
        }
        return GroupMessageDto(
            id = id,
            sessionId = sessionId,
            senderName = when {
                isMe -> "我"
                type == "system" -> "系统"
                senderName.isNotBlank() -> senderName
                else -> session.operatorName
            },
            isMe = isMe,
            type = type,
            text = segments.filter { it.text.isNotBlank() }.joinToString("\n") { it.text },
            imageId = if (type == "image") GroupImageJsonContract.parse(content)?.imageUri.orEmpty() else "",
            timePayload = timePayload(timestamp),
            timestamp = timestamp,
            mode = mode,
            isRecalled = segments.isNotEmpty() && segments.all { it.isRecalled },
            segments = segments,
        )
    }

    private data class ParsedSegment(
        val speakerName: String,
        val speakerId: String,
        val text: String,
        val emotion: String,
        val isNarration: Boolean,
        val rawContent: String? = null,
    ) {
        fun isRecalled(index: Int): Boolean =
            rawContent?.let { GroupReplyParser.isGroupSegmentRecalled(it, index) } ?: false
    }

    /** 群消息解析：`ai_json` → 多发言人分组；其它类型按单段展示（与 Android MessageParser 对齐）。 */
    private fun parseSegments(message: ChatMessage): List<ParsedSegment> {
        if (message.type != "ai_json") {
            val text = when (message.type) {
                "image" -> GroupImageJsonContract.parse(message.content)?.let { image ->
                    if (image.failed) "[图片发送失败]" else image.caption.ifBlank { "[图片]" }
                } ?: "[图片]"
                "gift_hidden" -> "[送出了礼物]"
                "gift_reply_failed" -> "[礼物回复失败]"
                "send_failed" -> message.content
                else -> message.content.trim()
            }
            return listOf(
                ParsedSegment(
                    speakerName = if (message.isMe) "我" else if (message.type == "system") "系统" else message.senderName,
                    speakerId = "",
                    text = text,
                    emotion = "",
                    isNarration = false,
                ),
            )
        }
        val results: List<GroupMsgResult> = GroupReplyParser.decodeGroupResultsOrNull(message.content).orEmpty()
        if (results.isEmpty()) {
            return listOf(
                ParsedSegment(
                    speakerName = message.senderName,
                    speakerId = "",
                    text = message.content.take(300),
                    emotion = "",
                    isNarration = false,
                ),
            )
        }
        return results.map { result ->
            val narration = result.type.equals("narration", ignoreCase = true) || result.speaker == "旁白"
            ParsedSegment(
                speakerName = if (narration) "旁白" else result.speaker.ifBlank { message.senderName },
                speakerId = if (narration) "" else result.speakerId,
                text = result.message,
                emotion = result.emotion,
                isNarration = narration,
                rawContent = message.content,
            )
        }
    }

    private fun timePayload(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return runCatching {
            val local = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
            val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val hour = local.hour.toString().padStart(2, '0')
            val minute = local.minute.toString().padStart(2, '0')
            if (local.date == now.date) "$hour:$minute" else "${local.monthNumber}/${local.dayOfMonth} $hour:$minute"
        }.getOrDefault("")
    }

    private fun isDeepseekThinking(): Boolean =
        runCatching { settingsRepo.provider == "deepseek" && settingsRepo.deepseekThinkingEnabled }.getOrDefault(false)
}

/** 群列表行（真实 chat_sessions + 成员/禁言计数 + 自动互动开关状态）。 */
class GroupSummaryDto(
    val sessionId: String,
    val name: String,
    val memberCount: Int,
    val mutedCount: Int,
    val lastMessage: String,
    val lastTime: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val mode: String,
    val autoEnabled: Boolean,
    val autoStatus: String,
)

/** 群成员（operators 名册解析结果 + 禁言状态 = Android 群聊唯一成员权限维度）。 */
class GroupMemberDto(
    val id: String,
    val name: String,
    val title: String,
    val avatarId: String,
    /** mutedMembers 命中（id 或名字）→ 禁言。 */
    val isMuted: Boolean,
    /** operators 表中是否存在；false = 名册未同步（引擎会 release 该轮并提示重试）。 */
    val isResolved: Boolean,
) {
    /** 权限文案（成员权限 = 是否禁言；与 Android GroupEditScreen 的 Switch 语义一致）。 */
    val permissionText: String get() = if (isMuted) "已禁言" else "可发言"
}

/** 群详情（头部：群名/成员数/模式；成员名册；自动互动开关）。 */
class GroupDetailDto(
    val sessionId: String,
    val name: String,
    val avatarId: String,
    val mode: String,
    val rules: String,
    val memberCount: Int,
    val mutedCount: Int,
    val members: List<GroupMemberDto>,
    val lastMessage: String,
    val lastTime: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val autoEnabled: Boolean,
    /** 总自动内容开关（settings.autoAiEnabled）；false 时群开关打开也不会自动轮次。 */
    val autoMasterEnabled: Boolean,
    val autoStatus: String,
    val autoMaxRounds: Int,
)

/** 群消息的一个发言段（AI 行按发言人拆分；旁白 isNarration=true）。 */
class GroupMessageSegmentDto(
    val index: Int,
    val speakerName: String,
    val speakerId: String,
    val avatarId: String,
    val text: String,
    val emotion: String,
    val isNarration: Boolean,
    val isRecalled: Boolean,
)

/** 群消息行（一条 DB 行；ai_json 行含多段 segments）。 */
class GroupMessageDto(
    val id: Long,
    val sessionId: String,
    val senderName: String,
    val isMe: Boolean,
    val type: String,
    val text: String,
    val imageId: String,
    val timePayload: String,
    val timestamp: Long,
    val mode: String,
    val isRecalled: Boolean,
    val segments: List<GroupMessageSegmentDto>,
)

/** 群消息分页结果（items 按 id 倒序）。 */
class GroupMessagePageDto(
    val items: List<GroupMessageDto>,
    val hasMore: Boolean,
    val nextBeforeId: Long,
    val totalCount: Int,
)

/** 群聊发送结果（onDone 一次回调）。 */
class GroupSendResultDto(
    val ok: Boolean,
    val userMessageId: Long,
    val aiMessageId: Long,
    val text: String,
    /** 本轮发言人（「、」连接，便于自检日志）。 */
    val speakersCsv: String,
    val narrationCount: Int,
    val error: String,
    val demo: Boolean,
)

/** 群聊可选上下文端口占位（记忆/知识库/关系注入归后续记忆波次；域层缺失时一律按「跳过」）。 */
private object EmptyGroupContextSource : GroupContextSource

/**
 * 真实/演示路由客户端（与 ChatEngineBridge 的 RoutingStreamingClient 同语义，独立一份避免改动 T44 文件）：
 * 每次 complete 重新解析配置 → 已配置走 Ktor Darwin SSE，否则走本地确定性演示通道。
 */
private class GroupRoutingStreamingClient(
    private val resolver: ProviderConfigResolver,
    private val demo: StreamingClient,
    private val real: RealProviderStreamingClient,
) : StreamingClient {
    var lastWasDemo: Boolean = true
        private set

    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        val config = resolver.resolve()
        if (config.isConfigured) {
            lastWasDemo = false
            return real.complete(messages, stage)
        }
        lastWasDemo = true
        return demo.complete(messages, stage)
    }
}

/**
 * 演示群聊通道：不联网，输出符合群聊 tagged 协议的确定性回复。
 *
 * 从提示词里解析「发言标识：<id>」拿到真实成员，按 tagged 协议输出 2 位成员台词 +
 * 合法【群聊回合状态】块 → 引擎的解析/归一化/落库/结算全链路与真实模型完全一致。
 */
private class DemoGroupStreamingClient : StreamingClient {
    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        delay(150)
        val promptText = messages.joinToString("\n") { it.content }
        val members = parseMembers(promptText)
        val userText = Regex("【用户本轮消息】\\s*\\n?\\s*用户[：:]\\s*([^\\n]*)")
            .find(promptText)?.groupValues?.get(1)?.trim()?.take(80)?.ifBlank { null }
            ?: "大家好啊"
        val picked = members.take(2).ifEmpty { listOf("妮可" to "zzz058") }
        return buildString {
            append("【群聊回合状态】\n")
            append("【当前主线】演示模式下的群聊寒暄\n")
            append("【用户本轮作用】提问\n")
            append("【本轮承接】用户在群里发了消息\n")
            append("【本轮新增推进】成员们依次回应了用户的问候\n")
            append("【主线状态】继续\n")
            append("【下轮焦点】用户回应成员们的态度\n")
            picked.forEachIndexed { index, (name, id) ->
                append("【发言人: $id】\n")
                append("【心情】").append(if (index == 0) "平静" else "温和").append("\n")
                append(
                    if (index == 0) {
                        "（演示模式，本地群聊桩）$name 收到了「$userText」。当前未配置模型供应商，" +
                            "在「设置 → 模型供应商」填写 API Key 后，群聊会切换到真实模型。"
                    } else {
                        "（演示模式）$name 也在，大家继续聊。"
                    },
                )
                append("\n")
            }
        }
    }

    /** 从成员资料块解析「名字：X / 发言标识：id」对（按发言标识顺序）。 */
    private fun parseMembers(promptText: String): List<Pair<String, String>> {
        val names = Regex("名字[：:]\\s*([^\\n，,]+)").findAll(promptText)
            .map { it.groupValues[1].trim() }.toList()
        val ids = Regex("发言标识[：:]\\s*([A-Za-z0-9_\\-]+)").findAll(promptText)
            .map { it.groupValues[1].trim() }.toList()
        return ids.indices.mapNotNull { index ->
            val id = ids.getOrNull(index) ?: return@mapNotNull null
            val name = names.getOrNull(index) ?: id
            name to id
        }.distinctBy { it.second }.take(4)
    }
}
