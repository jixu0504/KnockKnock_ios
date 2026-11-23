package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.chat.ChatStore
import com.rhodes.privatechat.shared.domain.chat.SystemChatClock
import com.rhodes.privatechat.shared.domain.chat.ChatClock
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.prompt.PromptClock
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random

/**
 * 群聊轮次引擎协议层：预算常数、配置、事件、依赖端口、自动互动计划状态机。
 *
 * Android oracle：`app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt`
 *  - :114-131 群聊预算常数（210s/270s/60s/150s/15s/2s/1s/5min/6 次/2×600）
 *  - :1677-1698 内容重试无条件一次；:1648-1671 上下文超限 6 次降级
 *  - automation/GroupAutoChatScheduler.kt 自动计划纯逻辑（纯状态机部分）
 */

/** 群聊预算常数（规格卡 §边界，oracle GVM:114-131）。 */
object GroupStageBudget {
    /** 整轮总预算（oracle :123）。 */
    const val TURN_BUDGET_MS = 210_000L

    /** 深度思考开启时的整轮/模型余量（oracle :131）。 */
    const val THINKING_BUDGET_BONUS_MS = 60_000L

    /** 提示词构建总预算（oracle :121）。 */
    const val PROMPT_BUILD_BUDGET_MS = 60_000L

    /** 单次模型请求预算（oracle :122）。 */
    const val MODEL_BUDGET_MS = 150_000L

    /** 消息写超时：msgId/用户消息事务/AI 回复写（oracle :119）。 */
    const val MESSAGE_WRITE_TIMEOUT_MS = 15_000L

    /** 重启清理等待（oracle :120）。 */
    const val RESTART_CLEANUP_WAIT_MS = 2_000L

    /** 上下文超限降级重试上限（oracle :1653）。 */
    const val MAX_CONTEXT_RETRIES = 6

    /** 上下文降级保最新条数（oracle :1659-1663）。 */
    const val CONTEXT_RETRY_KEEP_LATEST = 10

    /** 用户消息合并批次：≤2 条、合计 ≤600 字符（oracle :117-118）。 */
    const val MAX_MERGED_USER_MESSAGES = 2
    const val MAX_MERGED_USER_CHARS = 600

    /** 发送防抖窗口 250ms（oracle :1064）。 */
    const val SEND_DEBOUNCE_MS = 250L

    /** 自动循环轮询下限（oracle :114）。 */
    const val MIN_AUTO_LOOP_DELAY_MS = 1_000L

    /** 自动计划 claim 租约 5min（GroupAutoChatScheduler.kt:24）。 */
    const val AUTO_CLAIM_LEASE_MS = 5 * 60_000L

    /** 自动计划失败后重排延迟 60s（GroupAutoChatWorker.kt:44-50）。 */
    const val AUTO_RETRY_DELAY_MS = 60_000L

    /** 启动 reconcile 扫描窗口 24h（oracle MainViewModel.kt:140 同口径）。 */
    const val RECONCILE_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** 历史页大小（oracle :116）。 */
    const val CHAT_PAGE_SIZE = 50L

    /** required 读预算（oracle :1112-1174）。 */
    const val SESSION_READ_MS = 10_000L
    const val MEMBER_PROFILES_READ_MS = 10_000L
    const val GROUP_TURN_STATE_READ_MS = 10_000L

    /** 可选上下文块预算（oracle :1180-1288）。 */
    const val OPTIONAL_MEMORY_BLOCK_MS = 20_000L
    const val OPTIONAL_SUMMARY_BLOCK_MS = 10_000L
    const val KNOWLEDGE_BINDINGS_READ_MS = 10_000L
    const val KNOWLEDGE_RECALL_MS = 30_000L

    /** 可选上下文剩余预算保留与跳过阈值（oracle :997-1002）。 */
    const val OPTIONAL_CONTEXT_RESERVE_MS = 2_000L
    const val OPTIONAL_CONTEXT_MIN_MS = 1_000L

    /** 群资料上限（oracle :124-126，注入前不硬截断，仅协议约束）。 */
    const val GROUP_RULES_MAX_CHARS = 3_000
    const val MEMBER_PERSONA_MAX_CHARS = 1_500
    const val MEMBER_PROFILES_MAX_CHARS = 8_000

    /** 群名唯一性判定用的旁白发言人常量。 */
    const val NARRATION_SPEAKER = "旁白"
}

/** 群聊引擎配置（测试可整体压缩时间常数）。 */
data class GroupChatEngineConfig(
    val turnBudgetMs: Long = GroupStageBudget.TURN_BUDGET_MS,
    val thinkingBonusMs: Long = GroupStageBudget.THINKING_BUDGET_BONUS_MS,
    val promptBuildBudgetMs: Long = GroupStageBudget.PROMPT_BUILD_BUDGET_MS,
    val modelBudgetMs: Long = GroupStageBudget.MODEL_BUDGET_MS,
    val messageWriteBudgetMs: Long = GroupStageBudget.MESSAGE_WRITE_TIMEOUT_MS,
    val restartCleanupJoinMillis: Long = GroupStageBudget.RESTART_CLEANUP_WAIT_MS,
    val maxContextRetries: Int = GroupStageBudget.MAX_CONTEXT_RETRIES,
    val debounceBeforeAiMillis: Long = GroupStageBudget.SEND_DEBOUNCE_MS,
    val maxMergedUserMessages: Int = GroupStageBudget.MAX_MERGED_USER_MESSAGES,
    val maxMergedUserChars: Int = GroupStageBudget.MAX_MERGED_USER_CHARS,
    val sessionReadMs: Long = GroupStageBudget.SESSION_READ_MS,
    val memberProfilesReadMs: Long = GroupStageBudget.MEMBER_PROFILES_READ_MS,
    val turnStateReadMs: Long = GroupStageBudget.GROUP_TURN_STATE_READ_MS,
    val autoClaimLeaseMs: Long = GroupStageBudget.AUTO_CLAIM_LEASE_MS,
    val autoRetryDelayMs: Long = GroupStageBudget.AUTO_RETRY_DELAY_MS,
    val autoPollStepMs: Long = 100L,
) {
    /** 深度思考是否为本轮请求放宽预算（宿主读 settings 后注入，oracle :199-208）。 */
    var deepseekThinkingActive: Boolean = false

    /** 进轮固化的整轮预算（oracle :986）。 */
    val effectiveTurnBudgetMs: Long get() = turnBudgetMs + if (deepseekThinkingActive) thinkingBonusMs else 0L

    /** 进轮固化的模型预算（oracle :203-204）。 */
    val effectiveModelBudgetMs: Long get() = modelBudgetMs + if (deepseekThinkingActive) thinkingBonusMs else 0L
}

/**
 * 群聊引擎状态事件（oracle：`_groupLoading` / `_lastSendError` / 后台通知 / 维护调度等 UI 与宿主副作用的域形态）。
 * Android/iOS 宿主把这些事件映射回 UI 状态、通知与维护调度；域层不直接持有通知/WorkManager。
 */
sealed interface GroupEngineEvent {
    /** 群聊 loading 变化（oracle setGroupLoading :273-276）。 */
    data class LoadingChanged(val groupId: String, val loading: Boolean) : GroupEngineEvent

    /** Toast 文案（oracle `_lastSendError` / onShowToast 各调用点）。 */
    data class Toast(val message: String) : GroupEngineEvent

    /** 用户消息已落库，但租约被拒：由启动 reconcile + 前台补算补答（oracle :1049-1055）。 */
    data class ClaimDenied(val groupId: String, val messageId: Long, val turnId: String) : GroupEngineEvent

    /** 一批用户消息置 send_failed / gift_reply_failed（oracle markGroupMessagesUndelivered :1919-1930）。 */
    data class Undelivered(val groupId: String, val messageIds: Set<Long>) : GroupEngineEvent

    /** 群 AI 回复已落库并完成租约（oracle :1755-1761）。 */
    data class ReplyStored(
        val groupId: String,
        val userMessageIds: Set<Long>,
        val aiMessageId: Long,
        val speakers: List<String>,
        val narrationCount: Int,
    ) : GroupEngineEvent

    /** 系统消息已落库（全员禁言 / 重启提示，oracle :1157-1161）。 */
    data class SystemMessageStored(val groupId: String, val messageId: Long) : GroupEngineEvent

    /** 群回合状态已写入 settings（oracle :1762-1763）。 */
    data class TurnStateUpdated(val groupId: String, val fallbackDerived: Boolean) : GroupEngineEvent

    /**
     * 可见回复结算提示：群聊**无亲密度/催眠结算**（差异白名单）；仅手动轮次每日龙门币 50（oracle :1783-1786）。
     * 宿主负责写 `grantDailyLmb`/通知/未读（域层只算，同 T15 裁决）。
     */
    data class Settled(
        val groupId: String,
        val dailyLmb: Int?,
        val visibleCount: Int,
        val preview: String,
    ) : GroupEngineEvent

    /** 回复完成后应调度的记忆/摘要维护（宿主负责，绝不占用本轮锁，oracle :1789）。 */
    data class MaintenanceRequested(val groupId: String, val reason: String) : GroupEngineEvent

    /** 会话应被取消隐藏（首页恢复，oracle unhideSession :1779）。 */
    data class UnhideSession(val groupId: String) : GroupEngineEvent

    /** 未读计数提示（宿主按「是否当前群」决定是否应用，oracle :1788）。 */
    data class UnreadHint(val groupId: String, val count: Int) : GroupEngineEvent

    /** 自动规划状态变化（宿主据此重排 Timer/BGTask 通道，T22 胶水批）。 */
    data class AutoPlanChanged(val groupId: String, val state: String, val dueAt: Long, val round: Int) : GroupEngineEvent
}

/**
 * 群聊语义模式（T35 批 C 续作，D3 全员禁言对齐用）。
 *
 * - [allMutedReportsStored]（D3）：Android 基线（GroupChatViewModel 全员禁言路径）把该轮标记为
 *   `responseStored=true` 并回调 `onResponseComplete(true)`；引擎默认返回 false（更保守的「无回复」语义）。
 *   置 true 时引擎复刻基线回调值。**租约/系统消息/零模型调用三者两种模式下完全相同**（已有测试锁定），
 *   差异仅回调布尔值。默认 false 保持引擎既有行为（shared 既有群聊测试零改动）。
 */
data class GroupChatSemantics(
    val allMutedReportsStored: Boolean = false,
)

// === 依赖端口 ===

/** 群聊可选上下文块（固定注入顺序见 GroupPromptAssembler，契约 10）。 */
enum class GroupOptionalContext(val module: String, val budgetMs: Long) {
    RELATIONSHIP("group_relationship_read", GroupStageBudget.OPTIONAL_MEMORY_BLOCK_MS),
    MEMBER_PRIVATE("member_private_memory_read", GroupStageBudget.OPTIONAL_MEMORY_BLOCK_MS),
    GROUP_MEMORY("group_memory_read", GroupStageBudget.OPTIONAL_MEMORY_BLOCK_MS),
    PUBLIC_MEMORY("group_public_memory_read", GroupStageBudget.OPTIONAL_MEMORY_BLOCK_MS),
    RECENT_SOCIAL("recent_social_context_read", GroupStageBudget.OPTIONAL_MEMORY_BLOCK_MS),
    KNOWLEDGE_BINDINGS("knowledge_bindings_read", GroupStageBudget.KNOWLEDGE_BINDINGS_READ_MS),
    KNOWLEDGE_RECALL("knowledge_recall", GroupStageBudget.KNOWLEDGE_RECALL_MS),
}

/**
 * 群聊上下文数据源（记忆管线/知识库/AppState 名册的生产实现归后续记忆卡与 iOS 壳波次）。
 * 域层只依赖本接口：任一可选块失败/超时/空串都按「跳过」处理，不影响正常回复（oracle :989-1018）。
 */
interface GroupContextSource {
    /** AppState 内存名册兜底（oracle :1124-1129）；返回空表示只能用 DB。 */
    suspend fun inMemoryOperators(): List<Operator> = emptyList()

    /** 可选上下文块；空串按「无」处理。 */
    suspend fun optionalBlock(
        block: GroupOptionalContext,
        requestText: String,
        members: List<Operator>,
        groupPlotSummary: String,
        groupSummary: String,
    ): String = ""
}

/** 用户资料（oracle `getUserProfile()`，app 层桥接）。 */
data class GroupUserProfile(
    val nickname: String = "",
    val gender: String = "",
    val bio: String = "",
)

/**
 * 提示词来源端口（模板/模块/用户资料/时间/截断策略）。
 * 生产实现见 [SettingsGroupPromptSource]（SettingsRepository + prompt 包原语）；
 * 测试可替换为固定值实现，锁定「注入顺序契约」而不依赖存储。
 */
interface GroupPromptSource {
    /** 主模板（oracle getPromptTemplate("group", templateMode)，含四分支解析）。 */
    fun template(templateMode: String): String

    fun isTemplateCustom(templateMode: String): Boolean

    /** 行为模块（oracle getPromptModule("behavior","group",mode)，按真实 mode）。 */
    fun behaviorModule(mode: String): String

    /** 自定义 runtime 模块（null=未自定义）。 */
    fun customModuleOrNull(module: String, templateMode: String): String?

    /** runtimeKeys（PromptPlaceholderRegistry 接口化，规格卡 R5）。 */
    fun runtimeKeys(templateMode: String): Set<String>

    fun userProfile(): GroupUserProfile

    /** 北京时间文案 `yyyy-MM-dd HH:mm（时段）`。 */
    fun promptTime(): String

    /** 北京日期 `yyyy-MM-dd`。 */
    fun currentDate(): String

    /** 上下文块截断（oracle SharedUtils.trimContextBlock :720-732）。 */
    fun trimContextBlock(text: String, maxChars: Int): String

    /** 上下文块字符上限（oracle SharedUtils.contextBlockLimit :734-745）。 */
    fun contextBlockLimit(weight: Int = 1): Int

    /** 来源使用规则（oracle SharedUtils.sourceAwareUsageRule("group_chat") :838-848）。 */
    fun sourceAwareUsageRule(): String

    fun historyMessages(): Int
}

/** 生产实现：SettingsRepository + prompt 包原语。 */
class SettingsGroupPromptSource(
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
) : GroupPromptSource {
    override fun template(templateMode: String): String =
        settings.resolvePromptTemplate(
            "group",
            templateMode,
            com.rhodes.privatechat.shared.prompt.PromptTemplates.get("group", templateMode),
            com.rhodes.privatechat.shared.prompt.PromptTemplates.VERSION,
        )

    override fun isTemplateCustom(templateMode: String): Boolean =
        settings.isPromptTemplateCustom("group", templateMode)

    override fun behaviorModule(mode: String): String =
        if (settings.isPromptModuleCustom("behavior", "group", mode)) {
            settings.getPromptModule("behavior", "group", mode)
        } else {
            com.rhodes.privatechat.shared.prompt.PromptModuleDefaults.behavior("group", mode)
        }

    override fun customModuleOrNull(module: String, templateMode: String): String? =
        settings.getCustomPromptModuleOrNull(module, "group", templateMode)

    override fun runtimeKeys(templateMode: String): Set<String> =
        com.rhodes.privatechat.shared.prompt.PromptPlaceholderRegistry.runtimeKeys("group", templateMode)

    override fun userProfile(): GroupUserProfile = GroupUserProfile()

    override fun promptTime(): String = PromptClock.beijingPromptTime(clock)

    override fun currentDate(): String {
        val dt = clock.now().toLocalDateTime(PromptClock.beijingTimeZone())
        return "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"
    }

    override fun trimContextBlock(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val picked = mutableListOf<String>()
        var used = 0
        for (line in text.lines().filter { it.isNotBlank() }) {
            val next = line.take(maxChars)
            if (used + next.length + 1 > maxChars) break
            picked += next
            used += next.length + 1
        }
        return picked.joinToString("\n").ifBlank { text.take(maxChars) }
    }

    override fun contextBlockLimit(weight: Int): Int {
        val base = when (settings.contextMode) {
            "economy" -> 1_000
            "full" -> 5_000
            else -> 2_500
        }
        return (base * weight).coerceAtLeast(200)
    }

    override fun sourceAwareUsageRule(): String {
        if (!settings.sourceAwareMemoryEnabled) return ""
        val base = "不要说“系统记录”“摘要显示”或任何内部资料名称。这里提供的是过往经历、听说的事或背景信息，" +
            "不是当前正在发生的场景；当前用户发言与最近对话已确认的地点、时间、位置、状态、在场人物、进行中行动和话题优先。" +
            "需要时自然表现你是从哪里知道的。"
        return "$base 可以说“之前群里聊过”“我听谁提过”“动态下面有人说”。"
    }

    override fun historyMessages(): Int = settings.historyMessages
}

/** 群聊引擎需要的额外持久化读取（ChatStore 之外的会话清单/角色名册/摘要）。 */
interface GroupChatExtras {
    suspend fun allSessions(): List<ChatSession>
    suspend fun allOperators(): List<Operator>
    suspend fun shortTermSummary(sessionId: String): Memory?
    suspend fun latestDailySummary(sessionId: String): Memory?

    /** 重启群聊清理：在飞 turn 不得写入新时间线（oracle GVM:614）。记忆卡落地前默认无操作。 */
    suspend fun deleteReplyTurnsBySession(sessionId: String) {}

    /** 重启群聊清理：删短期记忆（记忆卡落地前默认无操作）。 */
    suspend fun deleteShortTermMemory(sessionId: String) {}

    /** 重启群聊清理：清群重启记忆（记忆卡落地前默认无操作）。 */
    suspend fun clearGroupRestartMemory(sessionId: String) {}
}

/** ChatRepository 生产适配器（Phase 2 Android 迁移 / iOS 宿主接线用）。 */
class RepositoryGroupChatExtras(private val repository: ChatRepository) : GroupChatExtras {
    override suspend fun allSessions(): List<ChatSession> = repository.getAllSessionsSync()
    override suspend fun allOperators(): List<Operator> = repository.getAllOperatorsSync()
    override suspend fun shortTermSummary(sessionId: String): Memory? = repository.getShortTermMemory(sessionId)
    override suspend fun latestDailySummary(sessionId: String): Memory? = repository.getLatestDailyBySession(sessionId)
    override suspend fun deleteReplyTurnsBySession(sessionId: String) {
        repository.deleteReplyTurnsBySession(sessionId)
    }
    override suspend fun deleteShortTermMemory(sessionId: String) {
        repository.deleteShortTermMemory(sessionId)
    }
    override suspend fun clearGroupRestartMemory(sessionId: String) {
        repository.clearGroupRestartMemory(sessionId)
    }
}

// === 自动互动计划（A1 iOS 语义；触发通道经通用 DelayedTaskLayer，T22 胶水批） ===

/** 计划持久化端口（生产 = SettingsRepository 的 group_auto_plan_* 键）。 */
interface GroupAutoChatPlanStore {
    fun isAutoEnabled(): Boolean
    fun isGroupAutoEnabled(groupId: String): Boolean
    fun isComplete(groupId: String): Boolean
    fun getPlan(groupId: String): SettingsRepository.GroupAutoChatPlan
    fun putPlan(groupId: String, plan: SettingsRepository.GroupAutoChatPlan)
    fun clearPlan(groupId: String)
    fun putComplete(groupId: String, value: Boolean)
    fun minIntervalSeconds(): Int
    fun maxIntervalSeconds(): Int
    fun maxRounds(): Int
}

/** 生产实现：SettingsRepository 落盘（oracle GroupAutoChatScheduler.kt:30-121 的存储面）。 */
class SettingsGroupAutoChatPlanStore(private val settings: SettingsRepository) : GroupAutoChatPlanStore {
    override fun isAutoEnabled(): Boolean = settings.autoAiEnabled
    override fun isGroupAutoEnabled(groupId: String): Boolean = settings.getGroupAuto(groupId)
    override fun isComplete(groupId: String): Boolean = settings.isGroupAutoChatComplete(groupId)
    override fun getPlan(groupId: String): SettingsRepository.GroupAutoChatPlan = settings.getGroupAutoChatPlan(groupId)
    override fun putPlan(groupId: String, plan: SettingsRepository.GroupAutoChatPlan) {
        settings.putGroupAutoChatPlan(groupId, plan)
    }
    override fun clearPlan(groupId: String) = settings.clearGroupAutoChatPlan(groupId)
    override fun putComplete(groupId: String, value: Boolean) = settings.putGroupAutoChatComplete(groupId, value)
    override fun minIntervalSeconds(): Int = settings.groupChatMinInterval
    override fun maxIntervalSeconds(): Int = settings.groupChatMaxInterval
    override fun maxRounds(): Int = settings.groupAutoMaxRounds
}

/** 自动计划状态变更出口（宿主据此 schedule/cancel DelayedTaskLayer 任务）。 */
fun interface GroupAutoPlanSink {
    fun onPlanChanged(groupId: String, plan: SettingsRepository.GroupAutoChatPlan?)
}

/**
 * 自动互动计划纯状态机（oracle automation/GroupAutoChatScheduler.kt 纯逻辑部分的域形态）。
 *
 * Android 原语：WorkManager `enqueueUniqueWork(REPLACE)` + 持久 plan（token/revision/state/lease）。
 * iOS 语义（规格卡契约 20 / R1）：本状态机保留 token/revision claim 单飞语义；
 * 到期触发 → 通用 DelayedTaskLayer（T22 胶水批适配 Timer/BGTask 通道），本类不 import WorkManager。
 */
interface AutoChatPlanPort {
    fun ensurePlan(groupId: String): SettingsRepository.GroupAutoChatPlan?
    fun resetPlan(groupId: String): SettingsRepository.GroupAutoChatPlan?
    fun claim(groupId: String, token: String, revision: Long): SettingsRepository.GroupAutoChatPlan?
    fun isCurrentClaim(groupId: String, token: String, revision: Long): Boolean
    fun scheduleNext(
        groupId: String,
        completedRound: Int,
        expectedToken: String? = null,
        expectedRevision: Long? = null,
    ): SettingsRepository.GroupAutoChatPlan?
    fun releaseClaim(groupId: String, completedRound: Int, token: String, revision: Long)
    fun releaseClaimForRetry(
        groupId: String,
        token: String,
        revision: Long,
        retryDelayMs: Long = GroupStageBudget.AUTO_RETRY_DELAY_MS,
    ): SettingsRepository.GroupAutoChatPlan?
    fun cancel(groupId: String)
    /** 启动 reconcile：为所有已开自动群重建计划（oracle GroupAutoChatScheduler.reconcileSuspending :107-113）。 */
    fun reconcile(groupIds: List<String>): List<SettingsRepository.GroupAutoChatPlan>
}

/** 默认实现：纯逻辑 + 可注入时钟/随机源（测试确定性）。 */
class DefaultAutoChatPlanPort(
    private val store: GroupAutoChatPlanStore,
    private val clock: ChatClock = SystemChatClock(),
    private val random: Random = Random,
    private val sink: GroupAutoPlanSink = GroupAutoPlanSink { _, _ -> },
) : AutoChatPlanPort {

    private fun isEnabled(groupId: String) = store.isAutoEnabled() && store.isGroupAutoEnabled(groupId)

    private fun newPlan(groupId: String, round: Int, revision: Long): SettingsRepository.GroupAutoChatPlan {
        val minMs = store.minIntervalSeconds().coerceAtLeast(1) * 1000L
        val maxMs = store.maxIntervalSeconds().coerceAtLeast(1) * 1000L
        val span = (maxMs - minMs).coerceAtLeast(0L)
        val delayMs = minMs + if (span > 0L) random.nextLong(span + 1) else 0L
        val token = buildString {
            repeat(8) { append("0123456789abcdef"[random.nextInt(16)]) }
            append('-'); append(clock.nowMillis().toString(16))
        }
        return SettingsRepository.GroupAutoChatPlan(
            token = token,
            dueAt = clock.nowMillis() + delayMs,
            round = round,
            revision = revision,
        ).also {
            store.putPlan(groupId, it)
            sink.onPlanChanged(groupId, it)
        }
    }

    override fun ensurePlan(groupId: String): SettingsRepository.GroupAutoChatPlan? {
        if (!isEnabled(groupId) || store.isComplete(groupId)) return null
        val current = store.getPlan(groupId)
        val now = clock.nowMillis()
        val plan = when {
            current.token.isNotBlank() && current.state == "pending" && current.dueAt >= 0L -> current
            current.token.isNotBlank() && current.state == "claimed" && current.leaseExpiresAt > now -> current
            else -> newPlan(groupId, (current.round + 1).coerceAtLeast(1), current.revision + 1)
        }
        return plan
    }

    override fun resetPlan(groupId: String): SettingsRepository.GroupAutoChatPlan? {
        val revision = store.getPlan(groupId).revision + 1
        cancel(groupId)
        if (!isEnabled(groupId)) return null
        store.putComplete(groupId, false)
        return newPlan(groupId, 1, revision)
    }

    override fun claim(groupId: String, token: String, revision: Long): SettingsRepository.GroupAutoChatPlan? {
        val plan = store.getPlan(groupId)
        if (!isEnabled(groupId) || token.isBlank()) return null
        if (plan.token != token || plan.revision != revision || plan.state != "pending") return null
        val now = clock.nowMillis()
        if (plan.dueAt > now) return null
        val claimed = plan.copy(state = "claimed", claimedAt = now, leaseExpiresAt = now + GroupStageBudget.AUTO_CLAIM_LEASE_MS)
        store.putPlan(groupId, claimed)
        sink.onPlanChanged(groupId, claimed)
        return plan
    }

    override fun isCurrentClaim(groupId: String, token: String, revision: Long): Boolean {
        val plan = store.getPlan(groupId)
        return isEnabled(groupId) && plan.token == token && plan.revision == revision &&
            plan.state == "claimed" && plan.leaseExpiresAt > clock.nowMillis()
    }

    override fun scheduleNext(
        groupId: String,
        completedRound: Int,
        expectedToken: String?,
        expectedRevision: Long?,
    ): SettingsRepository.GroupAutoChatPlan? {
        if (expectedToken != null || expectedRevision != null) {
            val current = store.getPlan(groupId)
            if (current.token != expectedToken || current.revision != expectedRevision || current.state != "claimed") {
                return null
            }
        }
        if (!isEnabled(groupId) || completedRound >= store.maxRounds()) {
            cancel(groupId)
            if (completedRound >= store.maxRounds()) store.putComplete(groupId, true)
            return null
        }
        val revision = store.getPlan(groupId).revision + 1
        return newPlan(groupId, completedRound + 1, revision)
    }

    override fun releaseClaim(groupId: String, completedRound: Int, token: String, revision: Long) {
        scheduleNext(groupId, completedRound, token, revision)
    }

    override fun releaseClaimForRetry(
        groupId: String,
        token: String,
        revision: Long,
        retryDelayMs: Long,
    ): SettingsRepository.GroupAutoChatPlan? {
        val current = store.getPlan(groupId)
        if (!isEnabled(groupId) || current.token != token || current.revision != revision || current.state != "claimed") {
            return null
        }
        val pending = current.copy(
            state = "pending",
            dueAt = clock.nowMillis() + retryDelayMs,
            claimedAt = 0L,
            leaseExpiresAt = 0L,
        )
        store.putPlan(groupId, pending)
        sink.onPlanChanged(groupId, pending)
        return pending
    }

    override fun cancel(groupId: String) {
        store.clearPlan(groupId)
        sink.onPlanChanged(groupId, null)
    }

    override fun reconcile(groupIds: List<String>): List<SettingsRepository.GroupAutoChatPlan> {
        if (!store.isAutoEnabled()) return emptyList()
        return groupIds.mapNotNull { groupId ->
            if (!store.isGroupAutoEnabled(groupId)) return@mapNotNull null
            ensurePlan(groupId)
        }
    }
}
