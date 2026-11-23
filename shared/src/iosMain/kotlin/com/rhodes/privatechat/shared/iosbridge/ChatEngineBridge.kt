@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.chat.ChatEngine
import com.rhodes.privatechat.shared.domain.chat.DefaultReplyCodec
import com.rhodes.privatechat.shared.domain.chat.EngineEvent
import com.rhodes.privatechat.shared.domain.chat.EngineEventSink
import com.rhodes.privatechat.shared.domain.chat.PromptAssembler
import com.rhodes.privatechat.shared.domain.chat.PromptBuildRequest
import com.rhodes.privatechat.shared.domain.chat.RepositoryChatStore
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.domain.chat.VisibleReplyContract
import com.rhodes.privatechat.shared.domain.data.DatabaseMessageMaxIdSource
import com.rhodes.privatechat.shared.domain.data.MessageIdAllocator
import com.rhodes.privatechat.shared.domain.media.MediaRepository
import com.rhodes.privatechat.shared.domain.media.MediaSource
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.files.MediaStoreIos
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.modelgateway.VisionAnalyzeRequest
import com.rhodes.privatechat.shared.modelgateway.VisionConfiguration
import com.rhodes.privatechat.shared.modelgateway.createVisionGateway
import com.rhodes.privatechat.shared.modelgateway.effectiveVisionConfiguration
import com.rhodes.privatechat.shared.network.ProviderConfigResolver
import com.rhodes.privatechat.shared.network.RealProviderStreamingClient
import com.rhodes.privatechat.shared.platformSynchronized
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.io.encoding.Base64
import kotlin.native.ObjCName
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLog

/**
 * 私聊桥（真实 [ChatEngine] + 真实模型供应商/演示回退 + 真实 DB）。
 *
 * 分工（与规格卡 R2 / lead 裁决一致）：
 * - 引擎负责轮次状态机、租约、落库（`ai_json` 整包落库）；
 * - 桥负责把「回调 + Cancellable」暴露给 Swift：onDelta 为落库成功后对可见文本的
 *   UI 打字机模拟（引擎不产生 token 流），onDone 在整轮结束后回调一次；
 * - AI 通道（T37）由 [RoutingStreamingClient] 每次发送前重新解析配置：已配置真实供应商走
 *   `RealProviderStreamingClient`（Ktor Darwin SSE），否则回退 [DemoStreamingClient] 并以
 *   [ChatResultDto.demo] 标记「演示模式」；设置保存后下一轮立即生效。
 * - `providerStatus()` 供 UI 显示当前是真实模式还是演示模式及原因。
 *
 * 单飞语义：同一时刻只保留一次 send；新 send 会先取消上一次。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "ChatEngineBridge")
class ChatEngineBridge internal constructor(
    private val chatRepo: ChatRepository,
    dbWrapper: DatabaseWrapper,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
    private val sessionProvider: SessionListBridge,
) {
    private val lock = Any()
    private val codec = DefaultReplyCodec()

    /**
     * T45：真实提示词/记忆/知识库接线。构造失败（依赖缺失等极端情况）→ 保留 DemoPromptAssembler
     * 回退并记录，聊天本身不受影响。
     */
    private val promptWiring: PrivatePromptWiring? = runCatching {
        PrivatePromptWiring(chatRepo, dbWrapper, settingsRepo)
    }.onFailure { wiringLog("构造失败，回退 DemoPromptAssembler：${it.message}") }.getOrNull()

    /** 真实供应商配置解析（SettingsRepository + Keychain）；每次发送重新解析。 */
    private val providerResolver = ProviderConfigResolver(settingsRepo)
    private val realProviderClient = RealProviderStreamingClient(providerResolver)
    private val aiRouter = RoutingStreamingClient(
        resolver = providerResolver,
        demo = DemoStreamingClient(),
        real = realProviderClient,
    )

    // T42 图片消息：媒体落盘（Application Support/media/chat_images）+ 真实识图网关（commonMain modelgateway，
    // 与 Android oracle ChatViewModel.currentVisionGateway :2014 同一实现）；无供应商配置的演示模式走本地确定性识图桩。
    private val mediaStore = MediaStoreIos()
    private val mediaRepository = MediaRepository(mediaStore)

    private val engine: ChatEngine = ChatEngine(
        scope = workScope,
        store = RepositoryChatStore(chatRepo),
        allocator = MessageIdAllocator(DatabaseMessageMaxIdSource(dbWrapper.database), settingsRepo),
        settings = settingsRepo,
        assembler = promptWiring?.promptAssembler ?: DemoPromptAssembler(chatRepo),
        aiClient = aiRouter,
        events = EngineEventSink { event -> onEngineEvent(event) },
        visionConfigured = { visionIsConfigured() },
        visionAnalyze = { imageForModel -> analyzeVision(imageForModel) },
        imageForModelResolver = { uri -> imageForModelDataUrl(uri) },
    )

    private var activeJob: Job? = null
    private var activeSessionId: String = ""
    /**
     * T44 未读链路：UI 当前**正在查看**的会话（Android `ChatViewModel._currentSession`）。
     * - ChatView 打开会话时由 Swift 调 [setActiveSession] 写入；
     * - 返回会话列表时由 ChatListView 调 `setActiveSession("")` 清空（见 ChatListView 的 chatPath 观察）；
     * - [onEngineEvent] 消费 `UnreadHint` 时据此判定是否累加未读（当前会话不加，对齐 Android
     *   `markUnreadIfNotCurrent`；引擎只在成功生成可见回复后发 UnreadHint）。
     * 与 [activeSessionId]（发消息绑定、仅用于捕获本轮 ReplyStored id）互不影响。
     */
    private var viewingSessionId: String = ""
    private var capturedReplyId: Long = 0L
    private var capturedUserIds: Set<Long> = emptySet()
    private var capturedError: String = ""

    // T42 消息操作（撤回/重试/重说/继续说/本地删除/图片重发）的单次操作错误捕获。
    private var actionError: String = ""
    private var actionFailed: Boolean = false

    /** 按 UI 传入的会话 id 解析会话（兼容 operatorId 形式），找不到返回 null。 */
    private suspend fun resolveSession(sessionId: String): ChatSession? {
        if (sessionId.isBlank()) return null
        chatRepo.sessions.getSession(sessionId)?.let { return it }
        // 兼容把 operatorId 当会话 id 传入的情况（如深链 rhodes://role/{operatorId}）
        return chatRepo.sessions.getSessionByOperator(sessionId)
    }

    /**
     * 发送一条文本并返回取消句柄。onDelta 在 Main 队列按 2 字/20ms 分片回调，
     * 末尾 onDone 回调一次（成功/失败均回调，error 非空表示失败）。
     */
    fun send(
        sessionId: String,
        text: String,
        onDelta: (String) -> Unit,
        onReplySegments: (List<MessageSegmentDto>) -> Unit,
        onDone: (ChatResultDto) -> Unit,
    ): IosCancellable {
        val cancellable = IosCancellable { cancelActive() }
        val job = workScope.launch {
            try {
                // 会话绑定：优先用 UI 指定的 sessionId；为空/找不到时才回退到播种会话
                val session = resolveSession(sessionId)
                    ?: sessionProvider.ensureSession()
                    ?: throw IllegalStateException("没有可用会话，请先调用 bootstrap 播种演示数据")
                require(!session.id.startsWith("group_")) {
                    "该会话是群聊，请使用群聊接口发送（surface=group）"
                }
                platformSynchronized(lock) {
                    activeSessionId = session.id
                    capturedReplyId = 0L
                    capturedUserIds = emptySet()
                    capturedError = ""
                }
                engine.sendMessage(
                    textOverride = text,
                    session = session,
                    mode = session.mode,
                    onResponseComplete = { stored ->
                        completeSend(session, stored, onDelta, onReplySegments, onDone)
                    },
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                mainScope.launch {
                    onDone(ChatResultDto(false, 0L, 0L, "", t.message ?: "发送失败", demo = aiRouter.lastWasDemo))
                }
            }
        }
        activeJob = job
        return cancellable
    }

    /** 取消当前在飞轮次（引擎 generation 失效 + job 取消），幂等。 */
    fun cancel() {
        cancelActive()
    }

    // ==================================================================
    // T42 消息操作（对齐 Android 原版 ChatViewModel :2083/:2150/:2173/:2363/:2443/:2742）
    // 全部回调式：`onDone(error)`，error == null 表示成功；回调一律 Main 队列。
    // 语义与引擎完全一致（不是 UI 层伪造）：撤回删行、重试重发、重说乐观锁替换、
    // 继续说追加新 ai_json、本地删除只删本地 DB 行（不产生任何网络副作用）。
    // ==================================================================

    /** 撤回整条消息（oracle :2083-2114：取消在飞轮次 + turn 终态 + 删行 + 会话预览更新）。 */
    fun recallMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            if (messageId <= 0L) return@runOperation "消息 id 非法"
            engine.recallMessage(sessionId, messageId)?.join()
            if (chatRepo.getMessageById(messageId) != null) "撤回未生效（消息仍在）" else null
        }

    /**
     * 撤回 AI 消息的单个分段（oracle recallMessageSegment :2173-2190）：
     * `segmentIndex < 0`/非法参数按引擎语义退化整条撤回；全部段均撤回时引擎删整条。
     */
    fun recallMessageSegment(sessionId: String, messageId: Long, segmentIndex: Int, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            val before = chatRepo.getMessageById(messageId) ?: return@runOperation "消息不存在"
            engine.recallMessageSegment(sessionId, messageId, segmentIndex)?.join()
            val after = chatRepo.getMessageById(messageId)
            when {
                after == null -> null // 全部撤回 → 引擎删整条（oracle 语义）
                after.content != before.content -> null
                else -> "该分段撤回未生效"
            }
        }

    /** 重试失败消息（oracle retryFailedMessage :2150-2170；仅 send_failed / gift_reply_failed 可重试）。 */
    fun retryMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            val ok = engine.retryFailedMessage(sessionId, messageId)
            when {
                ok -> null
                else -> "该消息当前不可重试（仅「未送达」消息可重试）"
            }
        }

    /** 重新生成 AI 回复（oracle regenerateAiMessage :2363-2421：乐观锁替换；失败保留原回复）。 */
    fun regenerateMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            val before = chatRepo.getMessageById(messageId) ?: return@runOperation "消息不存在"
            engine.regenerateAiMessage(sessionId, messageId)?.join()
            val after = chatRepo.getMessageById(messageId) ?: return@runOperation "原回复已被删除"
            when {
                after.content != before.content -> null
                actionFailedFlag() -> actionErrorText().ifBlank { "重说失败，已保留原回复" }
                else -> "重说未生成新内容（已保留原回复）"
            }
        }

    /** 继续生成（oracle continueAiMessage :2443-2482：追加一条新 ai_json 消息）。 */
    fun continueMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            chatRepo.getMessageById(messageId) ?: return@runOperation "消息不存在"
            val before = chatRepo.countMessages(sessionId)
            engine.continueAiMessage(sessionId, messageId)?.join()
            val after = chatRepo.countMessages(sessionId)
            when {
                actionFailedFlag() -> actionErrorText().ifBlank { "继续生成失败" }
                after > before -> null
                else -> "继续生成未产出新消息"
            }
        }

    /** 图片消息重发（oracle resumeImageReply :2742-2756；仅识别失败/发送失败的 image 消息）。 */
    fun retryImageMessage(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            val ok = engine.resumeImageReply(sessionId, messageId)
            if (ok) null else "图片重发失败（请检查图片文件与识图模型配置）"
        }

    /** 本地删除消息（不撤回、不通知对方语义；仅删本机 DB 行并刷新会话预览）。 */
    fun deleteMessageLocal(sessionId: String, messageId: Long, onDone: (String?) -> Unit) =
        runOperation(onDone) {
            val message = chatRepo.getMessageById(messageId) ?: return@runOperation null // 已不存在视作成功
            chatRepo.deleteMessage(messageId)
            runCatching { chatRepo.deleteReplyTurnsBySourceMessage(messageId) }
            updateSessionPreview(sessionId)
            if (chatRepo.getMessageById(messageId) != null) "删除失败（消息仍在）" else null
        }

    /**
     * 发送图片消息（oracle sendImageMessage :1819-2006）：
     * - [imageId] 为 [ingestImage] 返回的媒体 ID（`media/<uuid>.<ext>`），也兼容 file:// / 绝对路径 / http(s)；
     * - 引擎负责：占位落库 → 识图 → 角色回复 → 整包落库；onDelta 为落库后的可见文本打字机回调；
     * - 未配置识图模型（且非演示模式）时引擎直接拒绝并 Toast，onDone(error) 带同样文案。
     */
    fun sendImage(
        sessionId: String,
        imageId: String,
        caption: String,
        onDelta: (String) -> Unit,
        onReplySegments: (List<MessageSegmentDto>) -> Unit,
        onDone: (ChatResultDto) -> Unit,
    ): IosCancellable {
        val cancellable = IosCancellable { cancelActive() }
        val job = workScope.launch {
            try {
                val session = resolveSession(sessionId)
                    ?: sessionProvider.ensureSession()
                    ?: throw IllegalStateException("没有可用会话，请先调用 bootstrap 播种演示数据")
                require(!session.id.startsWith("group_")) {
                    "该会话是群聊，请使用群聊接口发送（surface=group）"
                }
                platformSynchronized(lock) {
                    activeSessionId = session.id
                    capturedReplyId = 0L
                    capturedUserIds = emptySet()
                    capturedError = ""
                    actionError = ""
                    actionFailed = false
                }
                val imageForModel = imageForModelDataUrl(imageId)
                val engineJob = engine.sendImageMessage(
                    imageUri = imageId,
                    imageForModel = imageForModel,
                    caption = caption,
                    session = session,
                    mode = session.mode,
                    onReplyComplete = { stored ->
                        completeSend(session, stored, onDelta, onReplySegments, onDone)
                    },
                )
                if (engineJob == null) {
                    // 引擎 guard 拒绝（未配置识图模型/图片不可读）：Toast 已同步发到 onEngineEvent
                    val reason = platformSynchronized(lock) { capturedError }
                        .ifBlank { "图片未发送，请检查识图模型配置" }
                    mainScope.launch {
                        onDone(ChatResultDto(false, 0L, 0L, "", reason, demo = aiRouter.lastWasDemo))
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                mainScope.launch {
                    onDone(ChatResultDto(false, 0L, 0L, "", t.message ?: "图片发送失败", demo = aiRouter.lastWasDemo))
                }
            }
        }
        activeJob = job
        return cancellable
    }

    /**
     * 图片入库（oracle ImageUtils.copyToInternalStorage + MainActivity 相机/相册落盘）：
     * 将 Swift 侧选好的本地图片文件复制到 `Application Support/media/chat_images/media/<uuid>.<ext>`，
     * 返回媒体 ID（写入消息内容的是该相对 ID，渲染层再经 [imagePath] 解析）。
     * 失败（源不可读/写盘失败）返回 error，不残留半写文件。
     */
    fun ingestImage(sourcePath: String, onDone: (String, String?) -> Unit) {
        workScope.launch {
            try {
                if (sourcePath.isBlank()) {
                    mainScope.launch { onDone("", "图片路径为空") }
                    return@launch
                }
                val mediaId = mediaRepository.ingest(MediaSource.CAMERA, sourcePath)
                if (mediaId == sourcePath || !mediaId.startsWith(MediaRepository.MEDIA_ID_PREFIX)) {
                    mainScope.launch { onDone("", "图片保存失败（源文件不可读或写入失败）") }
                } else {
                    mainScope.launch { onDone(mediaId, null) }
                }
            } catch (t: Throwable) {
                mainScope.launch { onDone("", t.message ?: "图片保存失败") }
            }
        }
    }

    /**
     * 图片 ID → 本机绝对文件路径（渲染用；同步、无 IO 异常）。
     * 返回：http(s)/data: 原样透传；本地文件存在 → 绝对路径；不存在/非媒体 ID → ""。
     */
    fun imagePath(imageId: String): String {
        if (imageId.isBlank()) return ""
        if (imageId.startsWith("http://") || imageId.startsWith("https://") || imageId.startsWith("data:")) {
            return imageId
        }
        val direct = imageId.removePrefix("file://")
        if (direct.startsWith("/") && FileIo.exists(direct)) return direct
        return mediaRepository.resolve(imageId)?.removePrefix("file://").orEmpty()
    }

    // ==================================================================
    // T42 自检辅助（仅 `--chat-ops-selftest` 使用；不参与任何用户路径）
    // ==================================================================

    /**
     * 自检夹具：写一条 `send_failed` 消息（未送达态），用于走真实 `retryFailedMessage` 路径。
     * 这是「构造重试前置状态」的唯一手段（演示通道不会自然失败）；不影响正常业务。
     */
    fun debugSeedFailedMessage(sessionId: String, text: String, onDone: (Long, String?) -> Unit) {
        workScope.launch {
            try {
                val session = resolveSession(sessionId) ?: error("会话不存在")
                val id = chatRepo.getNextMessageId()
                chatRepo.sendMessage(
                    session.id,
                    ChatMessage(
                        id = id,
                        sessionId = session.id,
                        senderName = "我",
                        content = text,
                        type = "send_failed",
                        mode = session.mode,
                        timestamp = Clock.System.now().toEpochMilliseconds(),
                        isMe = true,
                    ),
                )
                mainScope.launch { onDone(id, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0L, t.message ?: "构造未送达消息失败") }
            }
        }
    }

    /**
     * T59 自检夹具（仅 `--bubble-split-selftest` 使用；不参与任何用户路径）：
     * 写一条真实 `ai_json` 回复消息（含旁白+台词交错段），用于断言 UI 派生显示项一段一气泡。
     * [mode] 决定展示规则（offline/director 才显示旁白，与 Android MessageParser 一致）；
     * [timestamp] <= 0 时取当前时间。
     */
    fun debugSeedAiJsonMessage(
        sessionId: String,
        content: String,
        mode: String,
        timestamp: Long,
        onDone: (Long, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val session = resolveSession(sessionId) ?: error("会话不存在")
                val id = chatRepo.getNextMessageId()
                chatRepo.sendMessage(
                    session.id,
                    ChatMessage(
                        id = id,
                        sessionId = session.id,
                        senderName = session.operatorName,
                        content = content,
                        type = "ai_json",
                        mode = mode.ifBlank { session.mode },
                        timestamp = if (timestamp > 0L) timestamp else Clock.System.now().toEpochMilliseconds(),
                        isMe = false,
                    ),
                )
                mainScope.launch { onDone(id, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0L, t.message ?: "构造 ai_json 消息失败") }
            }
        }
    }

    /**
     * T44 自检夹具（仅 `--unread-selftest` 使用；不参与任何用户路径）：
     * 直接对会话未读数加 [delta]（模拟「AI 在非当前会话回复」落库后的结果）。
     * 与真实 `EngineEvent.UnreadHint` → [markUnreadIfNotCurrent] 写的是同一 DB 列
     * （`chat_sessions.unreadCount`），用于 UI 角标截图与「角标 +1」断言。
     */
    fun debugIncrementUnread(sessionId: String, delta: Int, onDone: (String?) -> Unit) {
        workScope.launch {
            try {
                if (sessionId.isBlank() || delta == 0) {
                    mainScope.launch { onDone(null) }
                    return@launch
                }
                chatRepo.sessions.incrementUnread(sessionId, delta)
                mainScope.launch { onDone(null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(t.message ?: "未读写入失败") }
            }
        }
    }

    // ==================================================================
    // T45 自检（仅启动参数 `--prompt-wiring-selftest`；不参与任何用户路径）
    // ==================================================================

    /**
     * 回复落库后的记忆提取（fire-and-forget）：
     * - 不阻塞 onDone/UI（独立 workScope 协程）；
     * - 异常静默：只写 System log，不产生错误弹窗/失败消息；
     * - 未达提取阈值时 [PrivatePromptWiring.ingestPrivateChatAfterReply] 自行返回 false（无副作用）。
     */
    private fun scheduleMemoryIngest(sessionId: String) {
        val wiring = promptWiring ?: return
        if (sessionId.isBlank()) return
        workScope.launch {
            try {
                val ingested = wiring.ingestPrivateChatAfterReply(sessionId)
                if (ingested) wiringLog("记忆提取成功 session=$sessionId")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                wiringLog("记忆提取失败（静默）session=$sessionId err=${t.message?.take(120)}")
            }
        }
    }

    private fun wiringLog(message: String) {
        // K/N 2.3：NSLog 的 %@ vararg 不桥接 Kotlin String（见 ProviderStreamingClient 同款注释），
        // 统一用拼接 format；消息里的 % 需转义，避免格式串注入。
        NSLog("[RhodesLink][PromptWiring] " + message.replace("%", "%%"))
    }

    /** 自检直驱一轮：不经过 UI 打字机回调，await 引擎 onResponseComplete。 */
    private suspend fun runDirectRound(session: ChatSession, text: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val job = engine.sendMessage(
            textOverride = text,
            session = session,
            mode = session.mode,
            onResponseComplete = { ok -> if (!done.isCompleted) done.complete(ok) },
        ) ?: return false
        return withTimeoutOrNull(220_000L) { done.await() } ?: run {
            job.cancel()
            false
        }
    }

    /**
     * T45 端到端自检（`--prompt-wiring-selftest`，由 IosAppGraph 在启动时触发）：
     * a) 预置 L3 记忆 → 真实组装器构建提示词 → 断言含记忆/人设/参考资料边界块；
     * b) 演示模式（无供应商配置）仍能完整走一轮（fake AI + 真实完整提示词）；
     * c) 切到本地 mock 供应商（127.0.0.1:18767）连发轮次 → 触发 fire-and-forget 记忆提取 →
     *    `memory_items` 新增 L1 行；
     * d) 结束时恢复用户原本的供应商/阈值设置。
     * 结果写 `Application Support/t45-prompt-wiring-selftest.log` 并逐行 NSLog。
     */
    fun debugPromptWiringSelftest(onDone: (String) -> Unit) {
        workScope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            fun check(name: String, ok: Boolean, detail: String = "") {
                if (!ok) failures++
                lines += "${if (ok) "PASS" else "FAIL"} $name" + if (detail.isBlank()) "" else " | $detail"
            }
            fun report() {
                lines += "RESULT ${if (failures == 0) "PASS" else "FAIL($failures)"}"
                val text = lines.joinToString("\n")
                NSLog(("[RhodesLink][T45SELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t45-prompt-wiring-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T45SELFTEST] report write failed: " + (it.message ?: "")) }
                mainScope.launch { runCatching { onDone(text) } }
            }

            // 供应商/提取阈值快照（finally 恢复，绝不影响用户已有配置）
            val savedProvider = settingsRepo.provider
            val savedCustomUrl = settingsRepo.customUrl
            val savedModel = settingsRepo.modelName
            val savedApiKey = settingsRepo.apiKey
            val savedThreshold = settingsRepo.privateMemoryExtractionThreshold
            var templateRestore: (() -> Unit)? = null
            try {
                lines += "=== T45 prompt wiring selftest ==="
                lines += "WIRING " + promptWiringStatus()
                val wiring = promptWiring
                if (wiring == null) {
                    check("wiring.constructed", false, "回退 DemoPromptAssembler（构造失败）")
                    report()
                    return@launch
                }
                check("wiring.constructed", true, promptWiringStatus())

                // 会话/角色：复用 bootstrap 同语义（幂等）
                chatRepo.operators.ensurePresetOperators()
                var session = chatRepo.sessions.getAllSessionsSync()
                    .firstOrNull { !it.operatorId.startsWith("group") && !it.id.startsWith("group") }
                if (session == null) {
                    val operator = chatRepo.operators.getAllOperatorsSync().firstOrNull()
                    if (operator != null) {
                        session = chatRepo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)
                    }
                }
                val sess = session
                if (sess == null) {
                    check("session.available", false)
                    report()
                    return@launch
                }
                check("session.available", true, "session=${sess.id} operator=${sess.operatorName} mode=${sess.mode}")
                val operatorId = sess.operatorId
                val operatorName = sess.operatorName

                // ---- a) 预置 L3 记忆 → 构建提示词 ----
                settingsRepo.provider = "custom"
                settingsRepo.customUrl = ""
                settingsRepo.modelName = "mock-model"
                settingsRepo.apiKey = ""
                val token = "T45-SELFTEST-L3-" + Clock.System.now().toEpochMilliseconds()
                val seedId = runCatching {
                    wiring.debugSeedL3Memory(sess, "用户与$operatorName 的约定：我们的暗号是 $token。")
                }.getOrDefault(-1L)
                check("l3.seeded", seedId > 0L, "memoryItemId=$seedId token=$token")

                val probeUserContent = "T45 提示词自检：你还记得我们的约定吗？"
                val prompt = runCatching { wiring.debugBuildPrompt(sess, probeUserContent) }
                val promptText = prompt.getOrNull()?.joinToString("\n") { it.content }.orEmpty()
                check("prompt.assembled", prompt.isSuccess && promptText.length > 3_000, "chars=${promptText.length}")
                check("prompt.contains.reference_block", promptText.contains("【本轮参考资料】"))
                check("prompt.contains.user_message_block", promptText.contains("【用户本轮消息】"))
                check("prompt.contains.output_requirement", promptText.contains("【输出要求】"))
                check("prompt.contains.operator_name", promptText.contains(operatorName))
                check("prompt.contains.user_profile_name", promptText.contains(settingsRepo.userName.ifBlank { "博士" }))
                // 记忆 provider 确实执行（在线模式模板不内联 LONG_TERM_IMPRESSION，但自然运行时上下文含个人记忆块）
                check("prompt.memory_provider_ran", promptText.contains("与当前话题有关的过去经历"))
                lines += "PROMPT_HEAD=" + promptText.take(500).replace("\n", " ⏎ ")
                lines += "PROMPT_REFERENCES=" +
                    promptText.substringAfter("【本轮参考资料】", "").substringBefore("【用户本轮消息】")
                        .replace("\n", " ⏎ ").take(600)

                // a2) 自定义模板引用 {{LONG_TERM_IMPRESSION}} → 预置 L3 记忆必须出现在真实 build 结果里。
                // 在线/线下运行模板（TAG_PRIVATE_*）不内联该占位符（oracle 同款），因此用自定义模板验证装配
                // 管线本身；结束后恢复用户模板（finally）。
                val hadCustomPrivateTemplate = settingsRepo.isPromptTemplateCustom("private", sess.mode)
                val savedPrivateTemplate = settingsRepo.getPromptTemplate("private", sess.mode)
                val savedPrivateTemplateVersion = settingsRepo.getPromptTemplateVersion("private", sess.mode)
                templateRestore = {
                    if (hadCustomPrivateTemplate) {
                        settingsRepo.saveCustomPromptTemplate("private", sess.mode, savedPrivateTemplate, savedPrivateTemplateVersion)
                    } else {
                        settingsRepo.removePromptTemplate("private", sess.mode)
                    }
                }
                settingsRepo.saveCustomPromptTemplate(
                    "private", sess.mode,
                    "你是{{OPERATOR_NAME}}，正在以第一人称回应{{USER_NAME}}。\n" +
                        "【你记得的相关经历】\n长期印象：{{LONG_TERM_IMPRESSION}}\n{{MEMORY_V2_CONTEXT}}\n",
                    savedPrivateTemplateVersion,
                )
                val promptWithMemory = runCatching { wiring.debugBuildPrompt(sess, probeUserContent) }
                val promptWithMemoryText = promptWithMemory.getOrNull()?.joinToString("\n") { it.content }.orEmpty()
                check("prompt.custom_template.l3_memory", promptWithMemoryText.contains(token), "chars=${promptWithMemoryText.length}")
                check("prompt.custom_template.operator_name", promptWithMemoryText.contains(operatorName))
                lines += "PROMPT_MEMORY_SECTION=" + promptWithMemoryText
                    .substringAfter("长期印象", "(未找到长期印象段)").take(300).replace("\n", " ⏎ ")

                // ---- b) 演示模式（无供应商配置）仍可聊 ----
                val demoOk = runCatching { runDirectRound(sess, "T45 演示模式自检：现在还能聊天吗？") }.getOrDefault(false)
                check("demo.round.visible_reply", demoOk)
                check("demo.provider.status", isDemoMode(), "lastWasDemo=${aiRouter.lastWasDemo}")

                // ---- c) mock 供应商 → 两轮回复 → 记忆提取 ----
                settingsRepo.privateMemoryExtractionThreshold = 3
                settingsRepo.provider = "custom"
                settingsRepo.customUrl = "http://127.0.0.1:18767/v1"
                settingsRepo.modelName = "mock-model"
                settingsRepo.apiKey = "sk-t45-mock"
                val before = wiring.debugMemoryItems(operatorId)
                val beforeCount = before.size
                lines += "MEMORY_BEFORE count=$beforeCount " + before.joinToString("; ") {
                    it.memoryLevel.name + ":" + it.content.take(40)
                }
                val round1 = runCatching { runDirectRound(sess, "T45 记忆自检第 1 轮：我们在走廊约定了一个暗号。") }.getOrDefault(false)
                val round2 = runCatching { runDirectRound(sess, "T45 记忆自检第 2 轮：再说一次我们的暗号。") }.getOrDefault(false)
                check("mock.round1.visible_reply", round1)
                check("mock.round2.visible_reply", round2)
                check("mock.provider.real", !aiRouter.lastWasDemo)
                var waited = 0L
                var afterItems = before
                while (waited < 90_000L) {
                    afterItems = runCatching { wiring.debugMemoryItems(operatorId) }.getOrDefault(before)
                    if (afterItems.size > beforeCount) break
                    delay(1_000L)
                    waited += 1_000L
                }
                val newItems = afterItems.filter { item -> before.none { it.id == item.id } }
                check("memory.ingest.new_rows", afterItems.size > beforeCount, "before=$beforeCount after=${afterItems.size} waited=${waited}ms")
                check("memory.ingest.l1_row", newItems.any { it.memoryLevel.name == "L1" }, newItems.joinToString("; ") {
                    it.memoryLevel.name + ":" + it.content.take(60)
                })
                val messageCount = wiring.debugMessageCount(sess.id)
                check("chat.rounds.stored", messageCount > 0, "messages=$messageCount")
                lines += "MEMORY_AFTER count=${afterItems.size} new=" + newItems.joinToString("; ") {
                    it.memoryLevel.name + ":" + it.content.take(60)
                }
                // 信息性：记忆回填后再次 assemble，观察召回块是否包含新记忆（非硬断言）
                val afterIngestPrompt = runCatching {
                    wiring.debugBuildPrompt(sess, "T45 记忆自检第 2 轮：再说一次我们的暗号。")
                        .joinToString("\n") { it.content }
                }.getOrDefault("")
                lines += "PROMPT_AFTER_INGEST_HAS_L1=" + afterIngestPrompt.contains("T45-L1-MEMORY")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failures++
                lines += "FAIL exception | ${t::class.simpleName}: ${t.message?.take(200)}"
            } finally {
                runCatching { templateRestore?.invoke() }
                settingsRepo.provider = savedProvider
                settingsRepo.customUrl = savedCustomUrl
                settingsRepo.modelName = savedModel
                settingsRepo.apiKey = savedApiKey
                settingsRepo.privateMemoryExtractionThreshold = savedThreshold
                lines += "settings.restored provider=$savedProvider threshold=$savedThreshold"
            }
            report()
        }
    }

    /**
     * T55 聊天模式切换自检（`--mode-switch-selftest`，由 IosAppGraph 启动钩子触发）：
     * 读取当前 mode → 依次切 offline / director / online：
     * - 写 `chat_sessions.mode`（[SessionListBridge.updateMode] 同一底层 `SessionRepository.updateSessionMode`）
     *   + `settings.putLastMode`，随后从 DB 重读断言 mode 字段生效；
     * - 用真实装配器 [PrivatePromptWiring.debugBuildPrompt] 构建下一轮提示词，断言三模式模板与旁白协议
     *   随 mode 切换（offline：【场景连续性】+【旁白硬规则】；director：【导演互动】+【旁白硬规则】；
     *   online：【线上互动】且无【旁白硬规则】/【导演互动】/【场景连续性】）；
     * - 结束时恢复用户原本 mode，不改变用户数据。
     * 结果写 `Application Support/t55-mode-switch-selftest.log` 并逐行 NSLog。
     */
    fun debugModeSwitchSelftest(onDone: (String) -> Unit) {
        workScope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            fun check(name: String, ok: Boolean, detail: String = "") {
                if (!ok) failures++
                lines += "${if (ok) "PASS" else "FAIL"} $name" + if (detail.isBlank()) "" else " | $detail"
            }
            var sessionId = ""
            var operatorId = ""
            var originalMode = "online"
            // 自检隔离：先快照三模式的自定义提示词覆盖（历史自检可能留下残留），断言默认模板后原样恢复。
            val savedTemplates = linkedMapOf<String, Triple<Boolean, String, Int>>()
            try {
                lines += "=== T55 mode switch selftest ==="
                lines += "WIRING " + promptWiringStatus()
                val wiring = promptWiring
                if (wiring == null) {
                    check("wiring.constructed", false, "回退 DemoPromptAssembler（构造失败）")
                    return@launch
                }
                check("wiring.constructed", true, promptWiringStatus())

                // 会话/角色：与 T45 自检同语义（幂等），优先复用已有私聊会话
                var session = chatRepo.sessions.getAllSessionsSync().firstOrNull {
                    !it.id.startsWith("group_") && !it.operatorId.startsWith("group")
                }
                if (session == null) {
                    chatRepo.operators.ensurePresetOperators()
                    val operator = chatRepo.operators.getAllOperatorsSync().firstOrNull()
                    if (operator != null) {
                        session = chatRepo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)
                    }
                }
                val sess = session
                if (sess == null) {
                    check("session.available", false)
                    return@launch
                }
                sessionId = sess.id
                operatorId = sess.operatorId
                originalMode = sess.mode.ifBlank { "online" }
                check("session.available", true, "session=$sessionId operator=${sess.operatorName}")
                check("mode.initial.valid", originalMode in CHAT_MODES, "mode=$originalMode")

                // 快照并临时移除三模式自定义模板（保证断言的是 T18 默认模板；finally 原样恢复）
                CHAT_MODES.forEach { m ->
                    savedTemplates[m] = Triple(
                        settingsRepo.isPromptTemplateCustom("private", m),
                        settingsRepo.getPromptTemplate("private", m),
                        settingsRepo.getPromptTemplateVersion("private", m),
                    )
                    settingsRepo.removePromptTemplate("private", m)
                }
                lines += "PROMPT_OVERRIDES_SNAPSHOT " + savedTemplates.keys.joinToString(",")

                // 引擎侧口径（代码路径，非运行断言）：ChatEngineBridge.send 每次重新解析会话并传
                // mode = session.mode → 切 mode 写库后下一轮即生效（ChatEngineBridge.kt send/resolveSession）。
                lines += "ENGINE send.request.mode <- session.mode（DB 重读，无需重启引擎）"

                val probe = "T55 模式自检：你现在能看见我吗？"
                val cases = listOf(
                    Triple(
                        "offline",
                        listOf("【场景连续性】", "【旁白硬规则】", "与本轮资料中的用户面对面互动"),
                        listOf("【导演互动】", "【线上互动】"),
                    ),
                    Triple(
                        "director",
                        listOf("【导演互动】", "【旁白硬规则】", "用户本轮描述的地点、时间、人物状态"),
                        listOf("【场景连续性】", "【线上互动】"),
                    ),
                    Triple(
                        "online",
                        listOf("【线上互动】", "你无法看见用户此刻的动作"),
                        listOf("【旁白硬规则】", "【导演互动】", "【场景连续性】"),
                    ),
                )
                for ((mode, mustContain, mustNotContain) in cases) {
                    chatRepo.sessions.updateSessionMode(sessionId, mode)
                    settingsRepo.putLastMode(operatorId, mode)
                    val reread = chatRepo.sessions.getSession(sessionId)
                    check("db.mode.$mode", reread?.mode == mode, "dbMode=${reread?.mode}")
                    check("settings.last_mode.$mode", settingsRepo.getLastMode(operatorId) == mode,
                        "lastMode=${settingsRepo.getLastMode(operatorId)}")
                    val promptText = if (reread == null) "" else runCatching {
                        wiring.debugBuildPrompt(reread, probe).joinToString("\n") { it.content }
                    }.getOrDefault("")
                    check("prompt.assembled.$mode", promptText.length > 3_000, "chars=${promptText.length}")
                    mustContain.forEach { needle ->
                        check("prompt.$mode.contains.$needle", promptText.contains(needle))
                    }
                    mustNotContain.forEach { needle ->
                        check("prompt.$mode.notContains.$needle", !promptText.contains(needle))
                    }
                    check("prompt.$mode.contains.user_message", promptText.contains("【用户本轮消息】"))
                    lines += "PROMPT_HEAD.$mode=" + promptText.take(220).replace("\n", " ⏎ ")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failures++
                lines += "FAIL exception | ${t::class.simpleName}: ${t.message?.take(200)}"
            } finally {
                if (sessionId.isNotEmpty()) {
                    runCatching { chatRepo.sessions.updateSessionMode(sessionId, originalMode) }
                    runCatching { settingsRepo.putLastMode(operatorId, originalMode) }
                    lines += "MODE_RESTORED $originalMode"
                }
                savedTemplates.forEach { (m, snap) ->
                    runCatching {
                        if (snap.first) {
                            settingsRepo.saveCustomPromptTemplate("private", m, snap.second, snap.third)
                        } else {
                            settingsRepo.removePromptTemplate("private", m)
                        }
                    }
                }
                if (savedTemplates.isNotEmpty()) {
                    lines += "PROMPT_OVERRIDES_RESTORED " + savedTemplates.keys.joinToString(",")
                }
                reportModeSwitch(lines, failures, onDone)
            }
        }
    }

    /** 写模式自检报告（Application Support + NSLog），供 UI/证据取用。 */
    private fun reportModeSwitch(lines: List<String>, failures: Int, onDone: (String) -> Unit) {
        val all = lines + "RESULT ${if (failures == 0) "PASS" else "FAIL($failures)"}"
        val text = all.joinToString("\n")
        NSLog(("[RhodesLink][T55SELFTEST]\n" + text).replace("%", "%%"))
        val path = "${MediaDirectory.applicationSupportDirectory()}/t55-mode-switch-selftest.log"
        runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
            .onFailure { NSLog("[RhodesLink][T55SELFTEST] report write failed: " + (it.message ?: "")) }
        mainScope.launch { runCatching { onDone(text) } }
    }

    /** 当前提示词接线状态（真实 / 演示回退），供自检与诊断。 */
    fun promptWiringStatus(): String =
        promptWiring?.statusLine ?: "assembler=DemoPromptAssembler(fallback) memoryV2=none knowledge=none"

    /** 当前供应商状态（真实模式 / 演示模式 + 原因），供 UI 显示。 */
    fun providerStatus(): ProviderStatusDto = buildProviderStatus(providerResolver)

    private fun cancelActive() {
        val sessionId = platformSynchronized(lock) { activeSessionId }
        if (sessionId.isNotEmpty()) engine.cancelSessionRequests(sessionId)
        activeJob?.cancel()
    }

    // ==================================================================
    // T42 内部：消息操作公共封装
    // ==================================================================

    /** 单次消息操作的统一封装：清空错误捕获 → 执行 → Main 队列回调 `onDone(error)`。 */
    private fun runOperation(onDone: (String?) -> Unit, block: suspend () -> String?) {
        workScope.launch {
            platformSynchronized(lock) {
                actionError = ""
                actionFailed = false
            }
            val outcome = try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                t.message ?: "操作失败"
            }
            mainScope.launch { onDone(outcome) }
        }
    }

    private fun actionFailedFlag(): Boolean = platformSynchronized(lock) { actionFailed }

    private fun actionErrorText(): String = platformSynchronized(lock) { actionError }

    /** 本地删除后刷新会话预览（oracle 撤回/删除路径会重算 lastMessage）。 */
    private suspend fun updateSessionPreview(sessionId: String) {
        val latest = chatRepo.getRecentMessagesSync(sessionId, 1).firstOrNull()
        chatRepo.updateLastMessage(
            sessionId,
            latest?.let { previewText(it) }.orEmpty(),
            latest?.timestamp ?: Clock.System.now().toEpochMilliseconds(),
        )
    }

    /** 与引擎 PrivateReplyFormat.messagePreviewForSession 对齐的会话预览文案（撤回/删除后使用）。 */
    private fun previewText(message: ChatMessage): String = when {
        message.type == "image" -> "[图片]"
        message.type == "gift_hidden" || message.type == "gift_reply_failed" -> "[礼物]"
        message.type == "system" -> ""
        message.type == "ai_json" -> visibleText(message.content).take(50)
        else -> message.content.take(50)
    }

    // ==================================================================
    // T42 内部：图片 / 识图
    // ==================================================================

    /** 图片引用 → 模型可用的 imageUrl（data:image/<mime>;base64,…）；
     *  与 Android MainActivity.prepareImageForModel :197-215 同语义：远程引用透传、本地文件转 data URL、>4MB 拒绝。
     */
    private fun imageForModelDataUrl(imageId: String): String? {
        if (imageId.isBlank()) return null
        if (imageId.startsWith("http://") || imageId.startsWith("https://") || imageId.startsWith("data:")) {
            return imageId
        }
        val path = imagePath(imageId).takeIf { it.isNotEmpty() && FileIo.exists(it) } ?: return null
        val bytes = FileIo.readBytes(path) ?: return null
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "png"
            "webp" -> "webp"
            "heic" -> "heic"
            "gif" -> "gif"
            else -> "jpeg"
        }
        return "data:image/$mime;base64," + Base64.encode(bytes)
    }

    /** 识图配置：复用 commonMain `effectiveVisionConfiguration`（与 Android oracle 同一实现），
     *  并补 Keychain 密钥（T37 保存后 settings.apiKey 明文被清空，鉴权键在 Keychain）。
     */
    private fun visionConfiguration(): VisionConfiguration {
        val base = settingsRepo.effectiveVisionConfiguration()
        if (base.apiKey.isNotBlank()) return base
        val chatProvider = settingsRepo.provider.ifBlank { "deepseek" }
        if (base.provider != chatProvider) return base
        return base.copy(apiKey = providerResolver.apiKeyFor(chatProvider))
    }

    /**
     * 是否可发送图片消息。
     * - 已配置独立识图模型 → true（真实识图）；
     * - 演示模式（未配置任何供应商）→ true（本地确定性识图桩）；
     * - 已配置聊天供应商但未单独配置识图模型 → true（降级用本地识图桩，避免「文字能聊、图片发不出」的断层；
     *   与 Android oracle 的严格拒绝不同，属平台差异白名单，已在 handoff 记录）。
     */
    private fun visionIsConfigured(): Boolean {
        if (visionConfiguration().configured) return true
        if (isDemoMode()) return true
        return providerResolver.resolve().isConfigured
    }

    /** 识图（oracle ChatViewModel :1921-1935 的 prompt 原文；失败由引擎统一转「图片已保留，但识别失败」）。 */
    private suspend fun analyzeVision(imageForModel: String): String? {
        val config = visionConfiguration()
        if (config.configured) {
            return createVisionGateway(config.provider, config.endpoint, config.apiKey, config.model)
                .analyzeImage(VisionAnalyzeRequest(imageForModel, VISION_ANALYZE_PROMPT))
                .text
        }
        // 未配置识图模型（或演示模式）：降级为本地确定性识图桩，保证图片消息可发送
        return DEMO_VISION_JSON
    }

    /** 演示模式 = 未配置任何真实模型供应商（无 baseUrl/model/key）。 */
    private fun isDemoMode(): Boolean = !providerResolver.resolve().isConfigured

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.ReplyStored -> {
                platformSynchronized(lock) {
                    if (activeSessionId.isEmpty() || event.sessionId == activeSessionId) {
                        capturedReplyId = event.aiMessageId
                        capturedUserIds = event.userMessageIds
                    }
                }
                // T45：回复落库后 fire-and-forget 记忆提取（不阻塞回复；失败静默记录）。
                scheduleMemoryIngest(event.sessionId)
            }
            is EngineEvent.Toast -> platformSynchronized(lock) {
                if (capturedError.isEmpty()) capturedError = event.message
                if (actionError.isEmpty()) actionError = event.message
            }
            is EngineEvent.Undelivered -> platformSynchronized(lock) {
                if (capturedError.isEmpty()) capturedError = "本轮未送达（未生成回复）"
            }
            // T42 消息操作（重说/继续说）失败时引擎追加系统失败消息：给操作回调一个可读原因。
            is EngineEvent.FailureMessageStored -> platformSynchronized(lock) {
                if (actionError.isEmpty()) actionError = "生成失败（已追加系统提示）"
                actionFailed = true
            }
            // T40：与 Android MainViewModel 的 engineEventListener 一致——会话收到新消息（发出/回复落库）
            // 时取消主页隐藏（settings.hiddenIds），会话自动重新出现在聊天主页。
            is EngineEvent.UnhideSession -> sessionProvider.unhide(event.sessionId)
            // T44：未读链路（Android ChatViewModel.markUnreadIfNotCurrent :1075 / :1655）——
            // 引擎只在成功生成可见回复后发 UnreadHint（send / 重试 / 重说 / 继续说），count = 可见段数；
            // 仅当该会话不是 UI 当前查看会话时累加 DB `chat_sessions.unreadCount`。
            is EngineEvent.UnreadHint -> markUnreadIfNotCurrent(event.sessionId, event.count)
            else -> Unit
        }
    }

    /**
     * 「当前查看会话」由 Swift 告知（T44）。ChatView 打开会话时传入 sessionId；
     * 返回会话列表时传空串。仅影响 `UnreadHint` 的未读累加判定，不参与发送/落库。
     */
    fun setActiveSession(sessionId: String) {
        platformSynchronized(lock) { viewingSessionId = sessionId }
    }

    /**
     * Android `markUnreadIfNotCurrent`（ChatViewModel :1075-1080）等价实现：
     * 非当前查看会话 → `SessionRepository.incrementUnread(sessionId, count)`；
     * 当前查看会话 → 不累加（打开时已由 ChatViewModel markRead 清零）。
     * 引擎事件在 Default 线程回调，DB 写在 [workScope]；失败静默（未读是辅助信息，不阻断聊天）。
     */
    private fun markUnreadIfNotCurrent(sessionId: String, count: Int) {
        if (count <= 0 || sessionId.isEmpty()) return
        val viewing = platformSynchronized(lock) { viewingSessionId }
        if (sessionId == viewing) return
        workScope.launch {
            runCatching { chatRepo.sessions.incrementUnread(sessionId, count) }
        }
    }

    private fun completeSend(
        session: ChatSession,
        stored: Boolean,
        onDelta: (String) -> Unit,
        onReplySegments: (List<MessageSegmentDto>) -> Unit,
        onDone: (ChatResultDto) -> Unit,
    ) {
        val (replyId, userIds, captured) = platformSynchronized(lock) {
            Triple(capturedReplyId, capturedUserIds, capturedError)
        }
        // 本轮是否走了演示通道（无配置/无 Key），以及真实请求的失败细节
        val demoRound = aiRouter.lastWasDemo
        val providerFailure = aiRouter.lastError
        mainScope.launch {
            var text = ""
            // T59：整包落库后先解析可见段（旁白/台词交错），再按段拼接文本做打字机；
            // UI 每收到一段就独立成气泡，最后一段随 delta 增长。
            var segments: List<MessageSegmentDto> = emptyList()
            if (replyId > 0L) {
                val message = runCatching { chatRepo.getMessageById(replyId) }.getOrNull()
                if (message != null) {
                    text = visibleText(message.content)
                    segments = runCatching { visibleReplySegments(message.content, codec) }.getOrDefault(emptyList())
                }
            }
            if (segments.isNotEmpty()) {
                runCatching { onReplySegments(segments) }
            }
            val typed = if (segments.isNotEmpty()) segments.joinToString("") { it.text } else text
            if (typed.isNotEmpty()) {
                var index = 0
                while (index < typed.length) {
                    val end = minOf(index + 2, typed.length)
                    runCatching { onDelta(typed.substring(index, end)) }
                    index = end
                    delay(20)
                }
            }
            var error = when {
                captured.isNotEmpty() -> captured
                stored && text.isNotEmpty() -> ""
                else -> "本轮未生成可见回复"
            }
            // 引擎的通用兜底文案（"通讯出现波动"）不如供应商原始错误可操作，优先展示后者。
            if (error.isNotEmpty() && providerFailure.isNotEmpty()) error = providerFailure
            runCatching {
                onDone(
                    ChatResultDto(
                        ok = stored && text.isNotEmpty(),
                        userMessageId = userIds.maxOrNull() ?: 0L,
                        aiMessageId = replyId,
                        text = text,
                        error = error,
                        demo = demoRound,
                    ),
                )
            }
        }
    }

    /** 引擎落库的是 `ai_json`（OfflineModeResponse JSON）；抽出可见台词文本。 */
    private fun visibleText(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching { codec.normalize(raw) }.getOrNull() ?: return raw
        val segments = parsed.segments.orEmpty()
            .filter { it.content.isNotBlank() && !it.type.equals("narration", ignoreCase = true) }
        if (segments.isNotEmpty()) return segments.joinToString("") { it.content }
        if (parsed.dialogue.isNotBlank()) return parsed.dialogue
        return VisibleReplyContract.replyPreview(parsed)
    }
}

/** 识图 prompt 原文（oracle Android ChatViewModel.kt:1921-1935；Android / iOS 共用同一输出契约）。 */
private const val VISION_ANALYZE_PROMPT = """请分析这张图片，用以下 JSON 格式回答（只输出 JSON，不要 Markdown 标记）：
{
  "visibleSummary": "一句话描述画面中最重要的可见内容",
  "userStateGuess": "基于画面的谨慎推测，不确定写 unknown",
  "notableObjects": ["物体1", "物体2"],
  "sceneQuality": "clear | dim | blurry | blocked | unknown",
  "confidence": 0.0~1.0
}
要求：只描述确定看到的内容，不确定的字段填 unknown，不要编造看不见的内容，输出中文。"""

/** 演示模式（未配置任何模型供应商）的确定性识图桩：与 DemoStreamingClient 同属本地演示通道，不联网。 */
private const val DEMO_VISION_JSON =
    """{"visibleSummary":"演示模式：收到一张图片（本地识图桩，未接入真实识图模型）。","userStateGuess":"unknown","notableObjects":[],"sceneQuality":"unknown","confidence":0.0}"""

/**
 * 演示用提示词组装器：system 一行 + 最近 user 消息 + 本轮 user 文本。
 * 真实 T15 PrivatePromptAssembler 接线留 Phase 3（它依赖记忆/知识库/模板端口）。
 */
private class DemoPromptAssembler(
    private val chatRepo: ChatRepository,
) : PromptAssembler {
    override suspend fun build(request: PromptBuildRequest): List<AiMessage> {
        val historyLimit = (request.historyLimitOverride ?: 10).coerceIn(1, 40)
        val recent = runCatching { chatRepo.getRecentMessagesSync(request.session.id, 40) }.getOrDefault(emptyList())
        val history = recent.asReversed()
            .filter { it.isMe && it.content.isNotBlank() && it.id !in request.excludeMessageIds }
            .takeLast(historyLimit)
            .map { AiMessage(role = "user", content = it.content) }
        val system = AiMessage(
            role = "system",
            content = "你是角色「${request.session.operatorName}」。用简体中文以第一人称回复用户，" +
                "只输出 JSON：{\"emotion\":\"平静\",\"state\":\"坐着\",\"location\":\"宿舍\",\"continuity\":\"继续\"," +
                "\"segments\":[{\"type\":\"dialogue\",\"content\":\"…\",\"speaker\":\"\"}]}",
        )
        return listOf(system) + history + AiMessage(role = "user", content = request.userContent)
    }
}

/**
 * 演示用 fake AI 流式客户端（确定性、不联网）。真实供应商（Ktor Darwin）
 * 接线只需替换本类并复用 T9 已验证的取消语义。
 */
private class DemoStreamingClient : StreamingClient {
    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        val userText = messages.asReversed().firstOrNull { it.role == "user" }?.content.orEmpty()
        delay(120) // 模拟首 token 延迟
        val reply = "（演示模式）我听见了：${userText.take(60)}。当前未配置模型供应商，这是本地演示回复；在「设置 → 模型供应商」填写 API Key 即可接入真实模型。"
        return buildReplyJson(reply)
    }

    private fun buildReplyJson(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "{\"emotion\":\"平静\",\"state\":\"坐在窗边\",\"location\":\"宿舍\",\"continuity\":\"继续\"," +
            "\"segments\":[{\"type\":\"dialogue\",\"content\":\"$escaped\",\"speaker\":\"\"}]}"
    }
}

/**
 * 真实/演示路由客户端（T37）：每次 `complete` 重新解析配置，实现「保存后下一轮立即生效」。
 *
 * - 已配置（供应商合法 + 模型非空 + 端点非空 + Key 存在）→ [real]（Ktor Darwin SSE）；
 * - 否则 → [demo]（确定性 fake，不联网），[lastWasDemo] = true，由桥在结果里标记演示模式。
 */
private class RoutingStreamingClient(
    private val resolver: ProviderConfigResolver,
    private val demo: StreamingClient,
    private val real: RealProviderStreamingClient,
) : StreamingClient {
    /** 最近一轮是否走演示通道。 */
    var lastWasDemo: Boolean = true
        private set

    /** 最近一轮真实请求的失败细节（成功/演示时为空串）。 */
    val lastError: String get() = real.lastError

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
