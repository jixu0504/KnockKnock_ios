package com.rhodes.privatechat.shared.domain.chat

import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.OfflineModeResponse
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.ReplyTurn
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

/**
 * 私聊轮次引擎协议层：预算常数、阶段超时、状态事件与全部依赖接口。
 *
 * Android oracle：`app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt`
 *  - :102-124  预算常数（180s / 240s / 50s / 120s / 15s / 12s / 60s 加成）
 *  - :127-140  deepseekThinkingActive → 整轮/模型预算加成
 *  - ChatStageTimeout.kt:7-62 阶段超时与中文标签
 */
object ChatStageBudget {
    /** 整轮总预算（oracle :107）。 */
    const val TURN_BUDGET_MS = 180_000L

    /** 深度思考开启时整轮与模型预算各加的余量（oracle :124）。 */
    const val THINKING_BUDGET_BONUS_MS = 60_000L

    /** 提示词构建预算（oracle :108）。 */
    const val PROMPT_BUILD_BUDGET_MS = 50_000L

    /** 可选增强上下文池（oracle :115）。 */
    const val OPTIONAL_CONTEXT_POOL_MS = 12_000L

    /** 单次模型请求预算（oracle :116）。 */
    const val MODEL_BUDGET_MS = 120_000L

    /** 消息写超时：msgId 分配/用户消息写/读回校验/AI 回复写（oracle :117）。 */
    const val MESSAGE_WRITE_TIMEOUT_MS = 15_000L

    /** 内容重试门槛：剩余整轮预算 < 5s 时跳过第二次模型调用（oracle :1186-1189）。 */
    const val CONTENT_RETRY_MIN_REMAINING_MS = 5_000L

    /** 上下文超限降级重试上限（oracle :1506）。 */
    const val MAX_RETRIES = 3

    /** 上下文降级下限：历史轮数减半后不得低于 5（oracle :1709）。 */
    const val MIN_DEGRADED_HISTORY = 5

    /** 用户消息合并批次上限：一批 ≤2 条、合计 ≤600 字符（oracle :105-106）。 */
    const val MAX_MERGED_USER_MESSAGES = 2
    const val MAX_MERGED_USER_CHARS = 600

    /** 发送防抖窗口：250ms 后复核 pendingIds（oracle :1482）。 */
    const val SEND_DEBOUNCE_MS = 250L

    /** 重启清理等待上限：10s join，超时取消继续发送（oracle :1369）。 */
    const val RESTART_CLEANUP_JOIN_MS = 10_000L
}

/**
 * 阶段超时异常（oracle ChatStageTimeout.kt:7-13）。抛给管线外层：
 * 标记未送达 + 用户文案 Toast，不整轮打断。
 */
class ChatStageTimeoutException(
    val surface: String,
    val stage: String,
    val budgetMs: Long,
    val elapsedMs: Long,
    cause: Throwable? = null,
) : Exception("$surface/$stage timed out after ${elapsedMs}ms (budget=${budgetMs}ms)", cause) {

    /** oracle ChatStageTimeout.kt:35-36。 */
    fun userMessage(label: String): String =
        "$label${stage.chatStageLabel()}超时（预算${budgetMs / 1000}秒），本轮未生成或保存AI回复，请重试"
}

/** oracle ChatStageTimeout.kt:15-33。 */
suspend fun <T> withChatStageTimeout(
    surface: String,
    stage: String,
    budgetMs: Long,
    block: suspend () -> T,
): T {
    val startedAt = TimeSource.Monotonic.markNow()
    return try {
        withTimeout(budgetMs) { block() }
    } catch (error: TimeoutCancellationException) {
        throw ChatStageTimeoutException(
            surface = surface,
            stage = stage,
            budgetMs = budgetMs,
            elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
            cause = error,
        )
    }
}

/** oracle ChatStageTimeout.kt:38-62（私聊涉及的全部 22 项）。 */
fun String.chatStageLabel(): String = when (this) {
    "prompt_build" -> "提示词构建"
    "operator_read" -> "角色资料读取"
    "short_term_memory_read" -> "滚动摘要读取"
    "history_read" -> "最近对话读取"
    "group_context_read" -> "群聊背景读取"
    "stable_impression_read" -> "稳定印象读取"
    "private_memory_read" -> "私聊记忆读取"
    "relationship_memory_read" -> "关系记忆读取"
    "public_memory_read" -> "公开记忆读取"
    "knowledge_bindings_read" -> "知识库绑定读取"
    "knowledge_recall" -> "知识库召回"
    "model_primary_request" -> "模型请求"
    "model_content_retry" -> "模型内容重试"
    "ai_reply_id" -> "AI消息编号读取"
    "ai_reply_write" -> "AI回复保存"
    "turn_total" -> "整轮处理"
    "group_turn_state_read" -> "群聊连续性读取"
    "group_summary_read" -> "群聊滚动摘要读取"
    "group_relationship_read" -> "群聊关系读取"
    "member_private_memory_read" -> "成员私聊记忆读取"
    "group_memory_read" -> "群聊向量记忆读取"
    "group_public_memory_read" -> "群聊公开记忆读取"
    else -> "阶段[$this]"
}

/** 模型返回无法解析（oracle AIService.kt:97 等价域异常）。 */
class InvalidModelResponseException : Exception("模型返回内容无法解析")

/**
 * 宿主诊断结算快照（T28 批 C 步骤 0；lead 裁决《impl-wiring-b.md》§7.1 开放 `shared/domain/chat` 写域）。
 *
 * 动机：`ChatViewModel.finishDebugRound`（oracle VM:1631/:1641/:1665）的「本轮总览」需要引擎内部的
 * 中间量，而 `ReplyStored`/`Settled` 原先只携带 id，宿主无法等价重建诊断页/日志摘要（B1）。
 * 引擎为每个成功回复计算一次本快照，随事件交给宿主消费；引擎自身仍然**不写** DebugLogger。
 *
 * 宿主仍需自行补齐的字段（引擎不感知，由接线层在 shim/StreamingClient 包装中记录）：
 * 模型用量与提示词缓存统计（来自 `StreamingClient.complete` 包装的 ChatCallResult）、
 * 本轮溯源摘要（来自 PromptAssembler shim 的 MemoryV2Pipeline/ChatTrace 调用点）、
 * `promptHistoryStats`（宿主历史窗口统计文本）。
 */
data class ReplyDiagnostics(
    /** 本轮模式（online/offline/proactive 等）。 */
    val mode: String = "",
    /** 交给模型的构建消息条数（oracle `apiMessages.size`）。 */
    val promptMessageCount: Int = 0,
    /** 本轮实际使用的历史轮数（oracle `effectiveHistoryMessages`，含上下文降级后的值）。 */
    val historyMessageCount: Int = 0,
    /** 内容重试/上下文降级轮次计数（oracle `retryCount`）。 */
    val contentRetryCount: Int = 0,
    /** 解析出的回复段数（oracle `parsed.segments.orEmpty().size`）。 */
    val segmentCount: Int = 0,
    /** 可见台词段数（oracle `visiblePrivateSegmentCount(parsed, mode)`）。 */
    val visibleCount: Int = 0,
    /** 协议完整性（oracle `isCompletePrivateReply(parsed, mode)`）。 */
    val protocolComplete: Boolean = true,
)

/**
 * 引擎状态事件流（oracle：isLoading StateFlow / Toast / 结算等 UI 可见副作用的域形态）。
 * Android 宿主把事件映射回 ChatViewModel 的 UI 状态与 Android 专属动作（通知/催眠/维护调度）。
 */
sealed interface EngineEvent {
    /** 会话 loading 状态变化（oracle beginLoading/finishLoading :195-203）。 */
    data class LoadingChanged(val sessionId: String, val loading: Boolean) : EngineEvent

    /** Toast 文案（oracle onShowToast 各调用点）。 */
    data class Toast(val message: String) : EngineEvent

    /** 用户消息已持久化但租约被拒：由启动 reconcile / 前台补算补答（oracle :1448-1453）。 */
    data class ClaimDenied(val sessionId: String, val messageId: Long, val turnId: String) : EngineEvent

    /** 一批用户消息置 send_failed / gift_reply_failed（oracle markPrivateMessagesUndelivered :1281-1296）。 */
    data class Undelivered(val sessionId: String, val messageIds: Set<Long>) : EngineEvent

    /** AI 回复已落库并完成租约（oracle :1630-1631）。[diagnostics] 供宿主 finishDebugRound 使用。 */
    data class ReplyStored(
        val sessionId: String,
        val userMessageIds: Set<Long>,
        val aiMessageId: Long,
        val diagnostics: ReplyDiagnostics? = null,
    ) : EngineEvent

    /** 可见回复结算：亲密度增量、每日龙门币（oracle :1649-1655）。宿主执行写入与通知。 */
    data class Settled(
        val sessionId: String,
        val operatorId: String,
        val affectionMod: Int,
        val dailyLmb: Int,
        val visibleCount: Int,
        val preview: String,
        val diagnostics: ReplyDiagnostics? = null,
    ) : EngineEvent

    /** 轮次状态已写入 settings（oracle :1625-1626）。 */
    data class TurnStateUpdated(val sessionId: String) : EngineEvent

    /** 回复完成后应调度的记忆/摘要维护（宿主负责，绝不占用本轮锁，oracle :2759）。 */
    data class MaintenanceRequested(val sessionId: String) : EngineEvent

    /** 会话应被取消隐藏（首页恢复，oracle onUnhideSession :1462）。 */
    data class UnhideSession(val sessionId: String) : EngineEvent

    /** 未读计数提示（宿主按「是否当前会话」决定是否应用，oracle markUnreadIfNotCurrent :1655）。 */
    data class UnreadHint(val sessionId: String, val count: Int) : EngineEvent

    /** 系统失败消息已落库（oracle savePrivateFailure :1268-1279）。 */
    data class FailureMessageStored(val sessionId: String, val messageId: Long) : EngineEvent
}

/** 引擎预算与行为开关（测试可整体压缩时间常数）。 */
data class ChatEngineConfig(
    /** 整轮预算（不含思考加成；加成由 budgetBonus 提供）。oracle :107。 */
    val turnBudgetMs: Long = ChatStageBudget.TURN_BUDGET_MS,
    /** 深度思考开启时的加成（0 = 关闭）。oracle :124。 */
    val thinkingBonusMs: Long = ChatStageBudget.THINKING_BUDGET_BONUS_MS,
    val promptBuildBudgetMs: Long = ChatStageBudget.PROMPT_BUILD_BUDGET_MS,
    val optionalContextPoolMs: Long = ChatStageBudget.OPTIONAL_CONTEXT_POOL_MS,
    val modelBudgetMs: Long = ChatStageBudget.MODEL_BUDGET_MS,
    val messageWriteBudgetMs: Long = ChatStageBudget.MESSAGE_WRITE_TIMEOUT_MS,
    val maxRetries: Int = ChatStageBudget.MAX_RETRIES,
    val debounceBeforeAiMillis: Long = ChatStageBudget.SEND_DEBOUNCE_MS,
    val restartCleanupJoinMillis: Long = ChatStageBudget.RESTART_CLEANUP_JOIN_MS,
    val maxMergedUserMessages: Int = ChatStageBudget.MAX_MERGED_USER_MESSAGES,
    val maxMergedUserChars: Int = ChatStageBudget.MAX_MERGED_USER_CHARS,
    val contentRetryMinRemainingMs: Long = ChatStageBudget.CONTENT_RETRY_MIN_REMAINING_MS,
    val degradedHistoryFloor: Int = ChatStageBudget.MIN_DEGRADED_HISTORY,
) {
    /** 深度思考是否为本轮请求放宽预算（oracle :128-140；由宿主读取 settings 后注入）。 */
    var deepseekThinkingActive: Boolean = false

    val effectiveTurnBudgetMs: Long get() = turnBudgetMs + if (deepseekThinkingActive) thinkingBonusMs else 0L
    val effectiveModelBudgetMs: Long get() = modelBudgetMs + if (deepseekThinkingActive) thinkingBonusMs else 0L
}

/**
 * 引擎语义模式（T35 批 C 续作，D1/D2/D3 对齐用）。
 *
 * 引擎与 Android oracle（ChatViewModel.kt）在两处可观测行为上不同，本模式让宿主**显式选择**语义，
 * 默认值全部保持引擎原有行为（shared 既有 61 例测试零改动）：
 *
 * - [cancelSkipsDbWrites]（D1 取消）：Android 基线里 `cancelSessionRequests` 只是取消协程，
 *   catch/finally 中的 DB 写入因协程已取消而被跳过 —— 用户消息保持 `text`、租约停留在 `running`。
 *   引擎默认行为是 `send_failed` + 租约 release 回 pending（更完整，但改变基线可观测状态）。
 *   置 true 时引擎复刻基线：取消路径不写 DB，仅执行宿主侧清理（loading/job/锁/回调）。
 * - [recallDropsTurnRow]（D2 撤回）：**生产 [ChatStore]（RepositoryChatStore）上无需开关**——
 *   oracle 的 `deleteMessage` 会级联删除 `reply_turns` 行，引擎 `cancelBySource + deleteMessage`
 *   在同一 transaction 语义下得到相同可观测结果（无租约行）；差异只出现在纯内存测试替身上。
 *   该字段仅作为契约声明保留（默认 true = 与生产库/基线一致）。
 *
 * 语义冻结证据：`ChatEngineSemanticsTest`（D1/D3 双模式对照）+ app 侧
 * `ChatEngineFactoryTest`（真实 SQLDelight 库上的 D1/D2 基线对照）。
 */
data class ChatEngineSemantics(
    /** D1：取消时不写 DB（oracle 基线）。默认 false = 引擎现有行为 send_failed + release。 */
    val cancelSkipsDbWrites: Boolean = false,
    /** D2：撤回随消息级联删租约行（生产库/基线语义；仅契约声明）。 */
    val recallDropsTurnRow: Boolean = true,
)

/** 挂钟（可注入，测试固定时间戳）。 */
fun interface ChatClock {
    fun nowMillis(): Long
}

/** 确定性时钟实现（生产用）。 */
class SystemChatClock : ChatClock {
    override fun nowMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}

/**
 * 持久化抽象：消息、会话、租约（reply_turns）与 display events。
 * 生产适配器 `RepositoryChatStore` 见 ChatEngine.kt 尾部；测试可用 SQL 内存库或纯内存 fake。
 * oracle：ChatRepository.kt / MessageRepository.kt / ReplyTurnRepository.kt 的相关方法。
 */
interface ChatStore {
    // —— 消息 ——
    suspend fun getMessageById(id: Long): ChatMessage?
    suspend fun getMessagesSync(sessionId: String): List<ChatMessage>
    suspend fun getRecentMessagesSync(sessionId: String, limit: Long): List<ChatMessage>
    suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, beforeId: Long, limit: Long): List<ChatMessage>
    suspend fun sendMessageAndCreateReplyTurn(sessionId: String, message: ChatMessage, turn: ReplyTurn)
    suspend fun sendMessage(sessionId: String, message: ChatMessage)
    suspend fun sendReplyAndCompleteTurn(sessionId: String, message: ChatMessage, turnId: String, leaseToken: String, now: Long): Boolean
    suspend fun updateMessageType(id: Long, type: String)
    suspend fun updateMessageContent(id: Long, content: String)
    suspend fun updateMessageContentAndPreview(sessionId: String, id: Long, content: String, timestamp: Long)
    suspend fun deleteMessage(id: Long)
    suspend fun replaceReplyIfUnchanged(original: ChatMessage, replacement: String): Boolean
    suspend fun updateLastMessage(sessionId: String, lastMessage: String, lastTime: Long)
    suspend fun getDisplayEvents(sessionId: String): List<ChatDisplayEventView>
    suspend fun deleteDisplayEvent(messageId: Long, segmentIndex: Int)
    suspend fun deleteMessageDisplayEvents(messageId: Long)

    // —— 会话 / 角色 ——
    suspend fun getSession(id: String): ChatSession?
    suspend fun getOperator(id: String): Operator?

    // —— reply_turns 租约 ——
    suspend fun getTurn(id: String): ReplyTurn?
    suspend fun getTurnBySource(sessionId: String, messageId: Long): ReplyTurn?
    suspend fun createTurnIfAbsent(turn: ReplyTurn): ReplyTurn?
    suspend fun claimTurn(id: String, token: String, now: Long, leaseUntil: Long): ReplyTurn?
    suspend fun completeTurn(id: String, token: String, now: Long): Boolean
    suspend fun isTurnOwned(id: String, token: String): Boolean
    suspend fun releaseTurn(id: String, token: String, retryAt: Long, now: Long, error: String)
    suspend fun failTurn(id: String, token: String, now: Long, error: String)
    suspend fun cancelTurn(id: String, error: String, now: Long)
    suspend fun cancelTurnBySource(sessionId: String, messageId: Long, error: String)
    suspend fun retryableTurnIds(since: Long, now: Long, limit: Long): List<String>
    suspend fun cleanupOrphanedSucceededTurns(): Long
}

/** display events 读取视图（oracle MessageRepository.kt:19-23）。 */
data class ChatDisplayEventView(
    val messageId: Long,
    val segmentIndex: Int,
    val revealOrder: Long,
)

/**
 * 流式/模型客户端抽象。引擎只依赖本接口——T9 已验证 Ktor Darwin 取消语义
 * （cancelAndJoin / 超时中止，114ms 首 token / 1ms 取消），Ktor 接线留在 iOS 宿主层。
 * 契约：实现必须传播 CancellationException（取消即取消）；超时由引擎的 withChatStageTimeout 兜底。
 */
interface StreamingClient {
    /**
     * 一次性返回完整回复文本（Android 私聊 1.14.27 实为整包落库，streamChat 是兼容遗留，
     * AIService.kt:666-668；lead 裁决：iOS 沿用整包落库，delta 流仅 UI 打字机层）。
     */
    suspend fun complete(messages: List<AiMessage>, stage: String): String
}

/** 模型输出编解码：归一化解析 + JSON 清洗。生产适配器可包 AIService.normalizeOfflineResponse/cleanJson。 */
interface ModelOutputCodec {
    fun normalize(raw: String): OfflineModeResponse
    fun cleanJson(raw: String): String
}

/** 提示词组装请求（oracle buildApiMessages :3034-3500 的参数形态）。 */
data class PromptBuildRequest(
    val session: ChatSession,
    val userContent: String,
    val historyLimitOverride: Int? = null,
    val excludeMessageIds: Set<Long> = emptySet(),
    val historyBeforeMessageId: Long? = null,
    val mode: String,
    val onStage: (String) -> Unit = {},
)

/** 提示词组装器（T15 自持 buildApiMessages；T18 交付组装原语）。 */
interface PromptAssembler {
    suspend fun build(request: PromptBuildRequest): List<AiMessage>
}

/** 引擎事件出口。 */
fun interface EngineEventSink {
    fun emit(event: EngineEvent)
}

/**
 * 结算策略（oracle :1649-1652）：
 * 仅可见回复结算——亲密度 +2+affection_mod（-2..2 截断 → 0..4）、每日龙门币 50。
 */
object SettlementPolicy {
    const val DAILY_LMB = 50

    /** oracle :1649。 */
    fun affectionMod(parsed: OfflineModeResponse): Int =
        (2 + parsed.affection_mod.coerceIn(-2, 2)).coerceIn(0, 4)

    /** oracle :1251-1256 —— 保留十轮、降到五、最后为一的历史阶梯。 */
    fun nextHistoryLimit(current: Int): Int = when {
        current > 20 -> (current / 2).coerceAtLeast(10)
        current > 10 -> 10
        current > 5 -> 5
        else -> 1
    }

    /** oracle :1239-1248 —— 上下文超限错误判定（400 + context_length/token 类）。 */
    fun isContextLimitError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("too many tokens", true) ||
            (message.contains("context", true) && (
                message.contains("length", true) || message.contains("limit", true) ||
                    message.contains("window", true) || message.contains("token", true) ||
                    message.contains("maximum", true) || message.contains("exceed", true)
                )) ||
            (message.contains("上下文", true) && message.contains("超", true))
    }
}

/** 失败系统消息文案（oracle classifyError :2587-… 的精简等价：超时/上下文/通用三档）。 */
object FailureMessages {
    fun classify(error: Throwable?): String = when {
        error == null -> "通讯出现波动，再发一遍吧"
        error is ChatStageTimeoutException || error is TimeoutCancellationException -> "回复超时了，再试一次吧"
        SettlementPolicy.isContextLimitError(error) -> "上下文超限，已自动缩短记忆后重试"
        error is InvalidModelResponseException -> "模型回复格式异常，请重试"
        else -> "通讯出现波动，再发一遍吧"
    }
}
