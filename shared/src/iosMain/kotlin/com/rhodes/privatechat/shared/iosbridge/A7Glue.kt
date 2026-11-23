package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.automation.ContextMaintenanceEvent
import com.rhodes.privatechat.shared.domain.automation.ContextMaintenanceKeys
import com.rhodes.privatechat.shared.domain.automation.ContextMaintenanceTask
import com.rhodes.privatechat.shared.domain.automation.DelayedTaskDue
import com.rhodes.privatechat.shared.domain.automation.DelayedTaskLayer
import com.rhodes.privatechat.shared.domain.automation.DelayedTaskOutcome
import com.rhodes.privatechat.shared.domain.automation.MomentCommentContextProviders
import com.rhodes.privatechat.shared.domain.automation.MomentCommentKind
import com.rhodes.privatechat.shared.domain.automation.MomentCommentPromptAssembler
import com.rhodes.privatechat.shared.domain.automation.MomentReplyEvent
import com.rhodes.privatechat.shared.domain.automation.MomentReplyKeys
import com.rhodes.privatechat.shared.domain.automation.MomentReplySettings
import com.rhodes.privatechat.shared.domain.automation.MomentReplyTask
import com.rhodes.privatechat.shared.domain.automation.NotificationCopy
import com.rhodes.privatechat.shared.domain.automation.NotificationEvent
import com.rhodes.privatechat.shared.domain.automation.NotificationEventSink
import com.rhodes.privatechat.shared.domain.chat.StreamingClient
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.model.MemoryType
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.model.MomentLike
import com.rhodes.privatechat.shared.model.MomentReplyTask as MomentReplyPayload
import com.rhodes.privatechat.shared.model.ReplyTurn
import com.rhodes.privatechat.shared.model.ReplyTurnStatus
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.platformSynchronized
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLog
import kotlin.random.Random

/** T56 自动化接线配置（测试可压缩；生产默认值与 A7 §A-12 口径一致）。 */
data class A7GlueConfig(
    /** 前台 tick 周期（产品口径 30–60s；tick 只做兜底扫描，不是主调度路径）。 */
    val tickIntervalMs: Long = 45_000L,
    /** 启动/回前台 catch-up 后等待执行体完成的上限。 */
    val catchUpSettleTimeoutMs: Long = 60_000L,
    /** catch-up 判定「没有任务执行」的静默窗口。 */
    val catchUpQuietWindowMs: Long = 1_200L,
    /** 群自动互动单轮执行上限（oracle 单轮受群聊预算约束；iOS 前台通道给足 4min）。 */
    val groupAutoTimeoutMs: Long = 240_000L,
    /** 群自动互动唯一键前缀。 */
    val groupAutoKeyPrefix: String = "group-auto:",
)

/**
 * T56：A7 自动化接线（iOS 宿主编排层；域逻辑全部复用 shared/domain/automation）。
 *
 * 组成（全部注册进同一个 [DelayedTaskLayer]）：
 * 1. **动态定时回复** [MomentReplyTask]（`moment-reply-<turnId>`）：启动 catch-up 重挂 + 前台 Timer 到期执行；
 * 2. **记忆维护** [ContextMaintenanceTask]（`context-maintenance-<sessionId>`）：启动分流重挂 + 五类触发点入口；
 * 3. **群聊自动互动**（`group-auto:<groupId>`）：监听 GroupChatEngine 的 `AutoPlanChanged` 事件重排计划，
 *    到期执行 `runScheduledAutoTurn`（A1 计划状态机保留 token/revision 单飞语义）。
 *
 * 生命周期：
 * - `start()`：启动 tick 循环 + 后台协程跑首次 catch-up（reconcile 扫描 24h 窗口，过期任务立即补算）；
 * - `setForeground(bool)`：前台恢复 tick 并补算，后台暂停计时器但保留挂起表；
 * - `catchUpNow()`：BGTaskScheduler 兜底触发（模拟器不可用 → 由 Swift 降级为前台补算）。
 *
 * 通知：订阅域层 [NotificationEvent]（MOMENT_REPLY_COMPLETED / DELAYED_TASK_CATCH_UP）→
 * [A7NotificationRouter]（设置开关过滤 + 同会话去重/静音 + `rhodes://chat/<sessionId>` 深链）→
 * [IosA7NotificationSender]（UNUserNotificationCenter；首次通知时才静默授权）。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "A7Glue")
class A7Glue internal constructor(
    private val chatRepo: ChatRepository,
    private val dbWrapper: DatabaseWrapper,
    private val settings: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
    private val groupChat: GroupChatBridge,
    private val momentAi: StreamingClient,
    private val wiring: A7MemoryWiring,
    private val summaryGenerator: A7SummaryGenerator,
    private val memoryIngestor: A7MemoryIngestor,
    private val memoryMaintenance: A7MemoryMaintenance,
    private val notificationSender: A7NotificationSender,
    private val config: A7GlueConfig = A7GlueConfig(),
    private val clock: () -> Long = { A7GlueClock.now() },
) {
    // === 调度层（前台 Timer 主通道；BGTask 兜底由 Swift 调 catchUpNow，不注册第二个通道） ===
    internal val channel = A7ForegroundChannel(workScope, clock)
    internal val layer = DelayedTaskLayer(primaryChannel = channel, scope = workScope, clock = clock)

    // === 领域执行体 ===
    internal val momentPort = RepositoryMomentReplyPort(chatRepo, dbWrapper)
    internal val contextPort = RepositoryContextMaintenancePort(
        repo = chatRepo,
        summary = summaryGenerator,
        ingestor = memoryIngestor,
        maintenance = memoryMaintenance,
    )
    internal val momentTask = buildMomentTask(momentAi, memoryIngest = { comment, momentId ->
        wiring.memoryV2.ingestMomentComment(comment, momentId)
    })
    internal val contextTask = ContextMaintenanceTask(
        store = contextPort,
        settings = settings,
        clock = clock,
        isRecoveryActive = { recoveryActive },
        isNetworkAvailable = { true },
        events = { event -> onContextEvent(event) },
    )

    // === 通知事件源 ===
    internal val notificationRouter = A7NotificationRouter(
        settings = settings,
        sender = notificationSender,
        clock = clock,
        dedupWindowMs = 60_000L,
    )

    // === 运行状态 ===
    private val stateLock = Any()
    private var started = false
    private var foreground = true
    private var recoveryActive = false
    private var tickJob: Job? = null
    private var executedCount = 0
    private var activeExecutions = 0
    private var tickCount = 0
    private var lastCatchUpAt = 0L
    private var lastCatchUpExecuted = 0
    private var defaultAutoPlanListener: ((String, SettingsRepository.GroupAutoChatPlan?) -> Unit)? = null

    init {
        // 群自动互动计划状态变化 → 重排 DelayedTaskLayer（A1 计划状态机只负责计划，不 import 层）。
        defaultAutoPlanListener = { groupId, plan ->
            workScope.launch { syncAutoPlanOn(layer, groupId, plan) }
        }
        groupChat.setAutoPlanListener(defaultAutoPlanListener)
        layer.registerHandler(MomentReplyKeys.PREFIX) { due -> tracked { momentTask.execute(due) } }
        layer.registerHandler(ContextMaintenanceKeys.PREFIX) { due -> tracked { contextTask.execute(due) } }
        layer.registerHandler(config.groupAutoKeyPrefix) { due -> tracked { executeGroupAuto(due) } }
    }

    // ------------------------------------------------------------------
    // 生命周期（Swift：AppEnvironment.start / scenePhase）
    // ------------------------------------------------------------------

    /** App 启动：tick 循环 + 首次 catch-up（幂等）。 */
    fun start() {
        val already = platformSynchronized(stateLock) {
            if (started) true else {
                started = true
                false
            }
        }
        if (already) return
        A7GlueLog.log("lifecycle", "start() 前台 tick=${config.tickIntervalMs}ms catch-up=on")
        startTickLoop()
        workScope.launch { runCatchUp("startup") }
    }

    /** 前后台切换：前台恢复 tick 并补算；后台暂停计时器（挂起表保留，回前台补触发）。 */
    fun setForeground(value: Boolean) {
        platformSynchronized(stateLock) { foreground = value }
        channel.setForeground(value)
        if (value) {
            A7GlueLog.log("lifecycle", "foreground=true 恢复 tick + 回前台补算")
            startTickLoop()
            workScope.launch { runCatchUp("foreground") }
        } else {
            A7GlueLog.log("lifecycle", "foreground=false 暂停 tick（挂起任务保留）")
            stopTickLoop()
        }
    }

    /** 备份/恢复护栏（oracle `BackupRestoreMaintenance.active`）：恢复中到期任务不执行、直接 retry。 */
    fun setRecoveryActive(value: Boolean) {
        platformSynchronized(stateLock) { recoveryActive = value }
        A7GlueLog.log("lifecycle", "recoveryActive=$value")
    }

    /** UI 当前查看的会话（同会话通知静音）。 */
    fun setActiveSessionId(sessionId: String) {
        notificationRouter.setActiveSessionId(sessionId)
    }

    /**
     * BGTaskScheduler 兜底路径（Swift 后台刷新 launchHandler 调用）：
     * 立即 catch-up，等待执行体结算后用回调返回「本次补算执行数」，供 `setTaskCompleted(success:)`。
     */
    fun catchUpNow(onDone: (Int) -> Unit) {
        workScope.launch {
            val executed = try {
                catchUpAndSettle("bg-task")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                A7GlueLog.log("catchup.fail", "err=${t.message?.take(120)}")
                0
            }
            mainScope.launch { onDone(executed) }
        }
    }

    // ------------------------------------------------------------------
    // 调度核心
    // ------------------------------------------------------------------

    internal suspend fun runCatchUp(reason: String) {
        lastCatchUpAt = clock()
        try {
            // 1) 已到期但通道未触发的挂起项（后台暂停/计时器漂移）。
            channel.flushOverdue(clock())
            // 2) 动态回复启动重挂（oracle MainViewModel.kt:628-631 的 pending turn 扫描）。
            val momentKeys = momentTask.reconcile(layer)
            // 3) 记忆维护启动分流（oracle MainViewModel.kt:632-639：rebuild pending 立即、摘要失败按退避）。
            val sessions = chatRepo.sessions.getAllSessionsSync()
            val contextKeys = contextTask.reconcile(layer, sessions)
            // 4) 群自动互动计划重建（oracle GroupAutoChatScheduler.ensurePlan 批量）。
            runCatching { groupChat.domainEngine().refreshAutoGroupChats() }
            // 5) 刚 reconcile 进来的过期任务立即补算。
            channel.flushOverdue(clock())
            A7GlueLog.log(
                "catchup",
                "reason=$reason momentReconcile=${momentKeys.size} contextReconcile=${contextKeys.size} " +
                    "sessions=${sessions.size} pending=${layer.pendingKeys().size}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            A7GlueLog.log("catchup.error", "reason=$reason err=${t.message?.take(160)}")
        }
    }

    /** catch-up + 结算：等执行体跑完（activeExecutions==0 且计数稳定）后返回执行数。 */
    internal suspend fun catchUpAndSettle(reason: String): Int {
        val before = platformSynchronized(stateLock) { executedCount }
        runCatchUp(reason)
        val deadline = clock() + config.catchUpSettleTimeoutMs
        var lastCount = platformSynchronized(stateLock) { executedCount }
        var quietSince = clock()
        while (clock() < deadline) {
            delay(120)
            val (count, active) = platformSynchronized(stateLock) { executedCount to activeExecutions }
            if (count != lastCount || active > 0) {
                lastCount = count
                quietSince = clock()
                continue
            }
            if (clock() - quietSince >= config.catchUpQuietWindowMs) break
        }
        val executed = platformSynchronized(stateLock) { executedCount } - before
        platformSynchronized(stateLock) { lastCatchUpExecuted = executed }
        if (executed > 0) {
            emitCatchUpNotification(executed)
        }
        A7GlueLog.log("catchup.settle", "reason=$reason executed=$executed")
        return executed
    }

    private fun startTickLoop() {
        val job = platformSynchronized(stateLock) {
            if (tickJob?.isActive == true) return
            tickJob = workScope.launch {
                while (isActive) {
                    delay(config.tickIntervalMs)
                    if (!platformSynchronized(stateLock) { foreground }) break
                    tickOnce()
                }
            }
            tickJob
        }
        A7GlueLog.log("tick", "loop started job=${job != null}")
    }

    private fun stopTickLoop() {
        platformSynchronized(stateLock) {
            tickJob?.cancel()
            tickJob = null
        }
    }

    /** 一次前台 tick：兜底扫描已到期挂起项（返回触发数）；不做 DB 重扫（catch-up 负责）。 */
    internal fun tickOnce(): Int {
        val fired = channel.flushOverdue(clock())
        platformSynchronized(stateLock) { tickCount++ }
        if (fired > 0) A7GlueLog.log("tick", "fired=$fired tick=${tickCount}")
        return fired
    }

    /** 自检/诊断：手动跑一次 tick。 */
    fun debugTick(onDone: (Int) -> Unit) {
        workScope.launch {
            val fired = tickOnce()
            mainScope.launch { onDone(fired) }
        }
    }

    // ------------------------------------------------------------------
    // 执行体包装（计数 + 通知）
    // ------------------------------------------------------------------

    private suspend fun tracked(block: suspend () -> DelayedTaskOutcome): DelayedTaskOutcome {
        platformSynchronized(stateLock) {
            activeExecutions++
            executedCount++
        }
        return try {
            block()
        } finally {
            platformSynchronized(stateLock) { activeExecutions-- }
        }
    }

    private fun onMomentEvent(event: MomentReplyEvent) {
        when (event) {
            is MomentReplyEvent.NotificationRequested -> {
                val momentId = event.event.payload["momentId"] ?: "0"
                notificationRouter.onEvent(event.event, "moment:$momentId")
            }
            is MomentReplyEvent.ReplyStored -> A7GlueLog.log(
                "moment.stored",
                "turn=${event.turnId} moment=${event.momentId} comment=${event.commentId} commenter=${event.commenterName}",
            )
            is MomentReplyEvent.TaskFailed -> A7GlueLog.log(
                "moment.failed",
                "turn=${event.turnId} terminal=${event.terminal} reason=${event.reason}",
            )
            else -> Unit
        }
    }

    private fun onContextEvent(event: ContextMaintenanceEvent) {
        when (event) {
            is ContextMaintenanceEvent.Completed -> A7GlueLog.log("context.completed", "session=${event.sessionId} group=${event.group}")
            is ContextMaintenanceEvent.SummaryGenerated -> A7GlueLog.log("context.summary", "session=${event.sessionId} ok=${event.success}")
            is ContextMaintenanceEvent.MemoryIngested -> A7GlueLog.log("context.ingest", "session=${event.sessionId} batches=${event.batches} messages=${event.messages}")
            is ContextMaintenanceEvent.Deferred -> A7GlueLog.log("context.deferred", "session=${event.sessionId} reason=${event.reason}")
            else -> Unit
        }
    }

    private fun emitCatchUpNotification(executed: Int) {
        val now = clock()
        val event = NotificationEvent(
            id = "delayed-task-catch-up-$now",
            category = com.rhodes.privatechat.shared.domain.automation.NotificationCategory.DELAYED_TASK_CATCH_UP,
            title = "补算完成",
            body = "打开 RhodesLink 后已补上 $executed 项延后的定时任务。",
            createdAt = now,
            payload = mapOf("kind" to "delayed-task-catch-up", "executed" to executed.toString()),
        )
        notificationRouter.onEvent(event, "catchup")
    }

    // ------------------------------------------------------------------
    // 群自动互动（A1 计划状态机 → 层）
    // ------------------------------------------------------------------

    /** 计划状态变化 → 取消旧键并按新 dueAt 重排（REPLACE 语义由「先 cancel 再 enqueue」实现）。 */
    internal suspend fun syncAutoPlanOn(
        target: DelayedTaskLayer,
        groupId: String,
        plan: SettingsRepository.GroupAutoChatPlan?,
    ) {
        val key = config.groupAutoKeyPrefix + groupId
        target.cancel(key)
        if (plan == null || plan.state == "cancelled" || plan.token.isBlank()) {
            A7GlueLog.log("group.auto", "cancel group=$groupId state=${plan?.state}")
            return
        }
        val dueAt = plan.dueAt.coerceAtLeast(clock())
        val accepted = target.enqueue(
            key = key,
            dueAt = dueAt,
            payload = mapOf(
                "groupId" to groupId,
                "token" to plan.token,
                "round" to plan.round.toString(),
                "revision" to plan.revision.toString(),
            ),
        )
        if (accepted) A7GlueLog.log("group.auto", "schedule group=$groupId dueAt=$dueAt round=${plan.round}")
    }

    /** 群自动互动到期执行（oracle `runScheduledAutoTurn`；计划状态机自己负责下一轮重排）。 */
    internal suspend fun executeGroupAuto(due: DelayedTaskDue): DelayedTaskOutcome {
        val groupId = due.payload["groupId"] ?: due.key.removePrefix(config.groupAutoKeyPrefix)
        if (groupId.isBlank()) return DelayedTaskOutcome.DONE
        val plan = settings.getGroupAutoChatPlan(groupId)
        if (plan.state == "cancelled" || plan.token.isBlank()) return DelayedTaskOutcome.DONE
        if (!settings.autoAiEnabled || !settings.getGroupAuto(groupId)) return DelayedTaskOutcome.DONE
        return try {
            val ok = withTimeoutOrNull(config.groupAutoTimeoutMs) {
                groupChat.domainEngine().runScheduledAutoTurn(
                    groupId = groupId,
                    token = plan.token,
                    planRevision = plan.revision,
                )
            } ?: false
            A7GlueLog.log("group.auto.run", "group=$groupId token=${plan.token} ok=$ok")
            // 计划状态机失败时已通过 AutoPlanChanged 重排，层不再叠加 EXP 退避（返回 DONE 销号）。
            if (ok) DelayedTaskOutcome.SUCCESS else DelayedTaskOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            A7GlueLog.log("group.auto.error", "group=$groupId err=${t.message?.take(160)}")
            DelayedTaskOutcome.DONE
        }
    }

    // ------------------------------------------------------------------
    // T60：发布动态后的干员自动回复/点赞（oracle MainViewModel.postUserMoment :3038-3090）
    // ------------------------------------------------------------------

    /** 一次发布互动调度的摘要（自检/日志用）。 */
    internal data class MomentReplyScheduleSummary(
        val replierCount: Int,
        val likerCount: Int,
        val armedKeys: List<String>,
    )

    /**
     * 生产入口：用户发布动态成功后由 `MomentsBridge.publish` 的 T60 hook 回调进入。
     * 生成干员评论任务（arm 进层）+ 立即写入干员点赞；返回本次入队的回复数。
     * 发布本身不受本方法结果影响（hook 侧有兜底）。
     */
    internal suspend fun onUserMomentPublished(momentId: Long): Int {
        if (momentId <= 0L) return 0
        return try {
            val summary = scheduleMomentReplies(layer, momentTask, momentId)
            A7GlueLog.log(
                "moment.published",
                "moment=$momentId repliers=${summary.replierCount} likers=${summary.likerCount} armed=${summary.armedKeys.size}",
            )
            summary.armedKeys.size
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            A7GlueLog.log("moment.publish.scheduleFail", "moment=$momentId err=${t.message?.take(120)}")
            0
        }
    }

    /**
     * 发布后自动互动调度（oracle `postUserMoment` :3048-3075 的域层等价）：
     * 1) 候选 = 被 @ 干员 + `dyn_<id>` 允许的干员（`getOperatorDynPermission`，默认 true）；
     * 2) 评论者 = 被 @ 干员（立即回）+ 旁观者随机 1..3 人（拟人延迟 10–30s，oracle :2833）；
     *    每条生成 `moment_comment` reply turn 并 arm 到层（唯一键 KEEP 幂等）；
     * 3) 点赞 = 被 @ 干员 + 旁观者随机 2..6 人（oracle :3069-3074），立即写 `moment_likes` 并回写 likeCount。
     *
     * @param now 挂钟（自检注入）
     * @param delayOverrideMs 覆盖随机延迟（自检传 0 = 立即到期；null = oracle 随机 10–30s）
     * @param random 随机源（自检注入确定性种子）
     */
    internal suspend fun scheduleMomentReplies(
        targetLayer: DelayedTaskLayer,
        targetTask: MomentReplyTask,
        momentId: Long,
        now: Long = clock(),
        delayOverrideMs: Long? = null,
        random: Random = Random.Default,
    ): MomentReplyScheduleSummary {
        val moment = chatRepo.getMoment(momentId)
            ?: return MomentReplyScheduleSummary(0, 0, emptyList())
        if (moment.operatorId != USER_OPERATOR_ID) {
            A7GlueLog.log("moment.published.skip", "moment=$momentId 非用户动态 operator=${moment.operatorId}")
            return MomentReplyScheduleSummary(0, 0, emptyList())
        }
        val userName = settings.userName.ifBlank { "博士" }
        val operators = chatRepo.getAllOperatorsSync()
            .filter { it.id != USER_OPERATOR_ID && !it.id.startsWith("group") }
        val eligible = operators.filter { it.name != userName && settings.getOperatorDynPermission(it.id) }
        val mentionedIds = moment.mentionedOperatorIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val mentioned = operators.filter { it.id in mentionedIds }
        val mentionedIdSet = mentioned.map { it.id }.toSet()
        val bystanderPool = eligible.filter { it.id !in mentionedIdSet }
        // oracle :3052：被 @ 干员必回 + 旁观者 take((1..3).random())。
        val repliers = (mentioned + bystanderPool.shuffled(random).take(1 + random.nextInt(3)))
            .distinctBy { it.id }
        // oracle :3069：点赞者 = 被 @ 干员 + 旁观者 take((2..6).random())。
        val likers = (mentioned + bystanderPool.shuffled(random).take(2 + random.nextInt(5)))
            .distinctBy { it.id }

        val cleanContent = MomentsBridge.splitContent(moment.content).first.ifBlank { moment.content.trim() }
        val armedKeys = mutableListOf<String>()
        for (op in repliers) {
            val mentionedHere = op.id in mentionedIdSet
            val persona = op.groupPrompt.ifBlank { op.description }
            val customPrompt = if (mentionedHere) {
                buildMentionedCommentPrompt(op.name, userName, cleanContent)
            } else {
                buildBystanderCommentPrompt(op.name, userName, cleanContent, mentioned.map { it.name }, persona)
            }
            val payload = MomentReplyPayload(
                momentId = momentId,
                operatorId = op.id,
                speakerName = op.name,
                userContent = cleanContent,
                parentCommentId = 0L,
                userName = userName,
                customPrompt = customPrompt,
                mustInsert = mentionedHere,
                sourceCommentId = null,
            )
            var turn = targetTask.buildTurn(payload, now, immediate = mentionedHere)
            if (delayOverrideMs != null) turn = turn.copy(nextAttemptAt = now + delayOverrideMs)
            val created = chatRepo.replyTurns.createIfAbsent(turn) ?: continue
            if (created.status != ReplyTurnStatus.PENDING && created.status != ReplyTurnStatus.RUNNING) continue
            targetTask.arm(targetLayer, created)?.let { armedKeys += it }
        }

        var liked = 0
        for (op in likers) {
            if (chatRepo.getLike(momentId, op.id) == null) {
                chatRepo.insertLike(
                    MomentLike(
                        momentId = momentId,
                        operatorId = op.id,
                        operatorName = op.name,
                        createdAt = now,
                    ),
                )
                liked++
            }
        }
        if (liked > 0) chatRepo.updateLikeCount(momentId, chatRepo.getLikeCount(momentId))
        A7GlueLog.log(
            "moment.schedule",
            "moment=$momentId eligible=${eligible.size} mentioned=${mentioned.size} " +
                "repliers=${repliers.size} liked=$liked armed=${armedKeys.size}",
        )
        return MomentReplyScheduleSummary(repliers.size, liked, armedKeys)
    }

    /** oracle `buildMentionedCommentPrompt`（MainViewModel.kt:3092-3103 逐字迁移）。 */
    private fun buildMentionedCommentPrompt(operatorName: String, userName: String, content: String): String = """
你是${operatorName}。用户${userName}刚发布了一条动态，并通过@功能明确提到了你。

动态原文：${content}

任务：你必须以${operatorName}本人身份，在这条动态下评论。
要求：
- 评论必须直接回应动态原文，或回应用户@你的事实。
- 不要说“${operatorName}姐/哥/老师”来称呼自己，也不要用第三人称称呼自己。
- 不要替其他角色发言，不要编造动态里没有的信息。
- 10-40字，像朋友圈评论。
- 只输出评论内容本身，不要加引号、前缀、冒号。
""".trimIndent()

    /** oracle `buildBystanderCommentPrompt`（MainViewModel.kt:3105-3118 逐字迁移）。 */
    private fun buildBystanderCommentPrompt(
        operatorName: String,
        userName: String,
        content: String,
        mentionedNames: List<String>,
        persona: String = "",
    ): String = """
你是${operatorName}${if (persona.isNotBlank()) "（${persona}）" else ""}。你刷到用户${userName}发布的一条动态。

动态原文：${content}
被@的人：${mentionedNames.joinToString("、").ifBlank { "无" }}

任务：你作为旁观者，以${operatorName}的性格自然评论这条动态。
要求：
- 评论必须围绕动态原文，不要转移到无关话题。
- 如果提到被@的人，只能作为旁观者提一句，不要冒充对方。
- 不要编造动态里没有的信息。
- 10-35字，像朋友圈评论。
- 只输出评论内容本身，不要加引号、前缀、冒号。
""".trimIndent()

    // ------------------------------------------------------------------
    // 诊断 / Swift 观测
    // ------------------------------------------------------------------

    /** 一行状态（设置页/自检日志显示；不含隐私内容）。 */
    fun statusLine(): String {
        val (startedNow, fg, executions, ticks, catchUpExecuted) = platformSynchronized(stateLock) {
            listOf(started, foreground, executedCount, tickCount, lastCatchUpExecuted)
        }
        val pending: Any = runCatching { channel.pendingKeysCount() }.getOrDefault(-1)
        val recent = notificationRouter.records().takeLast(3).joinToString("|") {
            "${it.event.id}->${if (it.suppressed.isEmpty()) "sent" else it.suppressed}"
        }
        return "started=$startedNow foreground=$fg executions=$executions ticks=$ticks " +
            "lastCatchUp=$catchUpExecuted pending=$pending demo=${(momentAi as? A7MomentStreamingClient)?.lastWasDemo} " +
            "notify=[$recent]"
    }

    /** 最近通知处理记录（调试页/自检证据；`id|session|sent|suppressed|deeplink` 换行分隔）。 */
    fun recentNotificationsCsv(): String = notificationRouter.records().takeLast(20).joinToString("\n") {
        "${it.event.id}|${it.sessionId}|${if (it.suppressed.isEmpty()) "sent" else it.suppressed}|${it.deeplink}"
    }

    /** 释放协程（demo 不调用）。 */
    fun close() {
        stopTickLoop()
        platformSynchronized(stateLock) { started = false }
    }

    // === 内部：构造 moment 执行体（设置快照 + 生产端口） ===
    private fun buildMomentTask(
        ai: StreamingClient,
        memoryIngest: suspend (MomentComment, Long) -> Unit,
    ): MomentReplyTask = MomentReplyTask(
        store = momentPort,
        promptAssembler = MomentCommentPromptAssembler(),
        ai = ai,
        settings = momentSettings(),
        contextProviders = buildContextProviders(),
        clock = clock,
        isRecoveryActive = { recoveryActive },
        isNetworkAvailable = { true },
        memoryIngest = memoryIngest,
        events = { event -> onMomentEvent(event) },
    )

    private fun momentSettings(): MomentReplySettings = MomentReplySettings(
        commentMinChars = settings.commentMinChars,
        commentMaxChars = settings.commentMaxChars,
        commentContextCount = settings.commentContextCount,
        memoryV2Enabled = settings.memoryV2Enabled,
        momentCommentMemoryGenerationEnabled = settings.momentCommentMemoryGenerationEnabled,
    )

    /** moment_comment 可选上下文：记忆/知识库/社交/风格/资料；全部失败降级（域层已兜底）。 */
    private fun buildContextProviders(): MomentCommentContextProviders = MomentCommentContextProviders(
        commenterMemory = { operatorId, query, _ ->
            runCatching {
                if (!settings.memoryV2Enabled) return@runCatching "" to ""
                val allowed = buildSet {
                    listOf(
                        MemorySourceKind.PRIVATE_CHAT,
                        MemorySourceKind.GROUP_CHAT,
                        MemorySourceKind.MOMENT,
                        MemorySourceKind.MOMENT_COMMENT,
                        MemorySourceKind.DIARY,
                        MemorySourceKind.MANUAL_MEMORY,
                    ).forEach { source ->
                        if (settings.isMemoryInjectionAllowed("comment", source.name)) add(source.name)
                    }
                }
                val memory = wiring.memoryV2.buildPrivateMemoryContext(
                    operatorId = operatorId,
                    limitL1 = 1,
                    limitL2 = settings.commentMemoryCount.coerceIn(1, 3),
                    limitL3 = 1,
                    query = query,
                    allowedSources = allowed,
                    visibilities = listOf("public", "shared"),
                ).ifBlank { "无" }
                memory to "无"
            }.getOrDefault("" to "")
        },
        knowledgeContext = { operatorId, query ->
            runCatching {
                wiring.knowledgeBase.recallForPrivateChat(operatorId, query, assignedBookIds = null).text.ifBlank { "无" }
            }.getOrDefault("无")
        },
        recentSocialContext = { _, _ -> "" },
        personalMemoryReferenceStyle = { runCatching { settings.personalMemoryReferenceStyle }.getOrDefault("") },
        sourceAwareRules = { "以下记忆与资料仅作为背景，不得在回复中提及来源、系统或提示词。" },
        userProfile = {
            com.rhodes.privatechat.shared.domain.chat.UserProfileView(
                nickname = settings.userName.ifBlank { "博士" },
                gender = settings.userGender,
                bio = settings.userSignature,
            )
        },
    )

    // ------------------------------------------------------------------
    // --a7-glue-selftest（Debug/自检专用：确定性离线夹具 + DB 证据 + 通知记录，无真实弹窗）
    // ------------------------------------------------------------------

    /**
     * A7 接线自检（由 IosAppGraph 在 `--a7-glue-selftest` 启动参数下调用）。
     *
     * 隔离：全部使用独立 testScope / testLayer / 确定性 AI / 记录型通知发送器；不改动生产 glue 的层，
     * 仅临时替换 GroupChatBridge 的计划监听（结束后恢复）。夹具写入真实 DB（证据可 sqlite3 复核），
     * 群自动互动关闭并清理计划，避免污染用户后续体验。
     *
     * @param onDone 报告文本（同时写 Application Support/t56-a7-glue-selftest.log）
     */
    fun debugRunSelfTest(onDone: (String) -> Unit) {
        workScope.launch {
            val report = runCatching { runGlueSelfTest() }
                .getOrElse { t ->
                    A7GlueLog.log("selftest.crash", "${t.message}")
                    "=== T56 A7 glue selftest ===\nRESULT FAIL crash=${t::class.simpleName ?: "Throwable"}: ${t.message}"
                }
            val logText = report
            val path = "${MediaDirectory.applicationSupportDirectory()}/t56-a7-glue-selftest.log"
            runCatching { FileIo.writeBytes(path, logText.encodeToByteArray()) }
                .onFailure { A7GlueLog.log("selftest.write", "err=${it.message}") }
            NSLog("[RhodesLink][T56] selftest report written to $path")
            mainScope.launch { onDone(logText) }
        }
    }

    private suspend fun runGlueSelfTest(): String {
        val now = clock()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lines = mutableListOf<String>()
        var pass = 0
        var fail = 0
        val testChannel = A7ForegroundChannel(testScope, clock)
        val testLayer = DelayedTaskLayer(primaryChannel = testChannel, scope = testScope, clock = clock)
        fun check(name: String, condition: Boolean, detail: String = "") {
            if (condition) pass++ else fail++
            lines += (if (condition) "PASS " else "FAIL ") + name + if (detail.isBlank()) "" else " | $detail"
            A7GlueLog.log("selftest", (if (condition) "PASS " else "FAIL ") + name + " " + detail.take(160))
        }
        try {
            val recorder = A7RecordingNotificationSender()
            val router = A7NotificationRouter(settings, recorder, clock, dedupWindowMs = 60_000L)
            // 1) 确定性 AI / 摘要 / 抽取桩（抽取桩写真实 memories 行，形成 DB 证据）。
            val demoAi = A7SelfTestCommentClient
            val summaryStub = A7SummaryGenerator { session, _ ->
                settings.putRollingSummaryStatus(
                    session.id,
                    SettingsRepository.RollingSummaryStatus(state = "idle", lastSuccessAt = clock(), lastAttemptAt = clock()),
                )
                chatRepo.replaceShortTermMemory(
                    Memory(
                        sessionId = session.id,
                        operatorId = session.operatorId,
                        type = MemoryType.SHORT_TERM,
                        content = "T56 自检滚动摘要：博士与${session.operatorName}约定明天去食堂。",
                        createdAt = clock(),
                    ),
                )
                true
            }
            val ingestStub = A7MemoryIngestor { session, messages, _ ->
                chatRepo.saveMemory(
                    Memory(
                        sessionId = session.id,
                        operatorId = session.operatorId,
                        type = MemoryType.SHORT_TERM,
                        content = "T56 自检记忆抽取：${messages.size} 条消息 → 约定一起去食堂。",
                        createdAt = clock(),
                    ),
                )
                true
            }
            val maintenanceStub = A7MemoryMaintenance { _ -> 0 }

            // 独立 test 层（同一端口/引擎，确定性桩）。
            val testMomentTask = MomentReplyTask(
                store = RepositoryMomentReplyPort(chatRepo, dbWrapper),
                promptAssembler = MomentCommentPromptAssembler(),
                ai = demoAi,
                settings = momentSettings(),
                contextProviders = MomentCommentContextProviders(),
                clock = clock,
                memoryIngest = { comment, momentId ->
                    chatRepo.saveMemory(
                        Memory(
                            sessionId = "moment:${momentId}",
                            operatorId = comment.operatorId,
                            type = MemoryType.SHORT_TERM,
                            content = "T56 自检：${comment.operatorName}回复了动态评论「${comment.content.take(20)}」",
                            createdAt = clock(),
                        ),
                    )
                    Unit
                },
                events = { event ->
                    if (event is MomentReplyEvent.NotificationRequested) {
                        val momentId = event.event.payload["momentId"] ?: "0"
                        router.onEvent(event.event, "moment:$momentId")
                    }
                },
            )
            val testContextTask = ContextMaintenanceTask(
                store = RepositoryContextMaintenancePort(chatRepo, summaryStub, ingestStub, maintenanceStub),
                settings = settings,
                clock = clock,
            )
            val executed = mutableListOf<String>()
            testLayer.registerHandler(MomentReplyKeys.PREFIX) { due ->
                executed += due.key
                testMomentTask.execute(due)
            }
            testLayer.registerHandler(ContextMaintenanceKeys.PREFIX) { due ->
                executed += due.key
                testContextTask.execute(due)
            }
            testLayer.registerHandler(config.groupAutoKeyPrefix) { due ->
                executed += due.key
                executeGroupAuto(due)
            }
            // 资源
            chatRepo.ensurePresetOperators()
            val operators = chatRepo.getAllOperatorsSync().filter { !it.id.startsWith("group") && it.id != "user" }
            check("fixture.operators", operators.size >= 2, "count=${operators.size}")
            if (operators.size < 2) return finishSelfTest(lines, pass, fail, "operators<2")
            val op1 = operators[0]
            val op2 = operators[1]
            val userName = settings.userName.ifBlank { "博士" }
            val prefsBefore = NotificationBridge(settings).readPrefs()

            // 2) 动态评论回复：预置动态 + 用户评论 + 到期 reply turn（catch-up 扫描 pending 重挂并执行）。
            val momentId = chatRepo.insertMoment(
                Moment(
                    operatorId = op1.id,
                    operatorName = op1.name,
                    content = "今天在罗德岛食堂试了新菜，居然还不错。",
                    createdAt = now - 3_000,
                ),
            )
            val sourceCommentId = chatRepo.insertComment(
                MomentComment(
                    momentId = momentId,
                    operatorId = "user",
                    operatorName = userName,
                    content = "看起来好好吃！下次带我一个。",
                    createdAt = now - 2_000,
                    isRead = true,
                ),
            )
            chatRepo.updateCommentCount(momentId, chatRepo.getCommentCount(momentId))
            val payload = MomentReplyPayload(
                momentId = momentId,
                operatorId = op1.id,
                speakerName = op1.name,
                userContent = "看起来好好吃！下次带我一个。",
                parentCommentId = 0L,
                userName = userName,
                sourceCommentId = sourceCommentId,
            )
            val turn = testMomentTask.buildTurn(payload, now - 120_000, immediate = true)
                .copy(nextAttemptAt = now - 120_000)
            chatRepo.replyTurns.createIfAbsent(turn)
            val pendingBefore = chatRepo.replyTurns.pendingMomentTurnIds()
            check("moment.pending.scan", pendingBefore.contains(turn.id), "pending=${pendingBefore.size}")
            // 启动 catch-up 语义：reconcile 记录（dueAt 过去）→ 层钳 now → Timer 立即执行。
            testLayer.reconcile(
                listOf(
                    DelayedTaskDue(
                        key = MomentReplyKeys.forTurn(turn.id),
                        dueAt = turn.nextAttemptAt,
                        payload = mapOf(
                            MomentReplyTask.PAYLOAD_TURN to turn.id,
                            MomentReplyTask.PAYLOAD_MOMENT to momentId.toString(),
                        ),
                    ),
                ),
            )
            waitUntil("moment.catchup", 20_000L) {
                chatRepo.replyTurns.get(turn.id)?.status == ReplyTurnStatus.SUCCEEDED
            }
            val storedTurn = chatRepo.replyTurns.get(turn.id)
            check("moment.turn.succeeded", storedTurn?.status == ReplyTurnStatus.SUCCEEDED, "status=${storedTurn?.status} pendingBefore=${pendingBefore.size}")
            val momentComments = momentPort.recentComments(momentId)
            val replyComment = momentComments.firstOrNull { it.operatorId == op1.id }
            check(
                "moment.comment.inserted",
                replyComment != null && replyComment.content.isNotBlank(),
                "commentId=${replyComment?.id} content=${replyComment?.content?.take(30)}",
            )
            check(
                "moment.memory.ingested",
                chatRepo.getShortTermMemory("moment:$momentId")?.content?.contains("T56") == true,
                "memory=${chatRepo.getShortTermMemory("moment:$momentId")?.content?.take(40)}",
            )
            val momentNotifId = NotificationCopy.MOMENT_REPLY_ID_PREFIX + turn.id
            check(
                "notify.moment.recorded",
                recorder.sent.any { it.id == momentNotifId },
                "ids=${recorder.sent.map { it.id }}",
            )
            val sentMomentEvent = recorder.sent.firstOrNull { it.id == momentNotifId }
            check(
                "notify.deepLink",
                sentMomentEvent?.payload?.get("deeplink")?.startsWith("rhodes://chat/moment:") == true,
                "deeplink=${sentMomentEvent?.payload?.get("deeplink")}",
            )

            // 3) 群聊自动互动：建群 + 预置到期计划 → 执行 runScheduledAutoTurn → chat_messages 落库。
            //    确定性：临时把供应商置为「自定义 + 空端点」（未配置）→ GroupChatEngine 走本地演示 AI；
            //    结束后恢复原供应商配置（自检不联网、不依赖用户真实 Key 的连通性）。
            val savedProvider = settings.provider
            val savedCustomUrl = settings.customUrl
            settings.provider = "custom"
            settings.customUrl = ""
            val groupId = createGroupForSelfTest(op2, operators.take(3))
            if (groupId != null) {
                settings.putGroupAuto(groupId, true)
                settings.autoAiEnabled = true
                settings.putGroupAutoChatComplete(groupId, false)
                val groupSession = chatRepo.sessions.getSession(groupId)
                val plan = SettingsRepository.GroupAutoChatPlan(
                    token = "t56-" + now.toString(16),
                    dueAt = now - 1_000,
                    round = 0,
                    state = "pending",
                    revision = 1,
                )
                settings.putGroupAutoChatPlan(groupId, plan)
                // 用测试层接管计划监听（避免生产层被自检计划污染；结束恢复）。
                groupChat.setAutoPlanListener { gid, newPlan ->
                    testScope.launch { syncAutoPlanOn(testLayer, gid, newPlan) }
                }
                syncAutoPlanOn(testLayer, groupId, plan)
                waitUntil("group.auto", 30_000L) { chatRepo.getMessagesSync(groupId).isNotEmpty() }
                val groupMessages = chatRepo.getMessagesSync(groupId)
                check("group.auto.messages", groupMessages.isNotEmpty(), "count=${groupMessages.size} session=${groupSession?.id}")
                check(
                    "chat_messages.evidence",
                    groupMessages.any { it.type == "ai_json" || it.content.isNotBlank() },
                    "types=${groupMessages.map { it.type }}",
                )
                val autoTurn = chatRepo.replyTurns.get("auto:$groupId:${plan.token}")
                check("group.auto.turn", autoTurn != null, "status=${autoTurn?.status}")

                // 清理群自动互动（避免自检夹具在用户 App 里继续自动聊天）。
                runCatching {
                    settings.putGroupAuto(groupId, false)
                    settings.putGroupAutoChatComplete(groupId, true)
                    settings.clearGroupAutoChatPlan(groupId)
                    groupChat.domainEngine().stopAutoGroupChat(groupId)
                }
                // 恢复生产计划监听 + 清掉测试层残留键。
                groupChat.setAutoPlanListener(defaultAutoPlanListener)
                testLayer.cancel(config.groupAutoKeyPrefix + groupId)
                assertTickExecution(groupId, now, testLayer, testChannel, lines, ::check, contextKeyFor = ContextMaintenanceKeys.forSession(groupId))
            } else {
                check("group.auto.fixture", false, "createGroup 失败")
            }
            settings.provider = savedProvider
            settings.customUrl = savedCustomUrl

            // 5) 通知路由矩阵：开关过滤 / 同会话去重 / 同会话静音 / 活跃会话静音。
            NotificationBridge(settings).savePref("moments", true)
            NotificationBridge(settings).savePref("private_reply", true)
            val baseEvent = NotificationCopy.momentReplyCompleted(
                turnId = "t56-router",
                momentId = momentId,
                commentId = 1L,
                commenterName = "阿米娅",
                content = "这条动态真有意思。",
                createdAt = now,
            )
            val routerSession = "moment:router-$now"
            val first = router.onEvent(baseEvent, routerSession)
            check("router.sent", first.suppressed.isEmpty() && recorder.sent.any { it.id == baseEvent.id }, "suppressed=${first.suppressed}")
            val duplicate = router.onEvent(baseEvent, routerSession)
            check("router.dedupe", duplicate.suppressed == "duplicate", "suppressed=${duplicate.suppressed}")
            val muted = router.onEvent(baseEvent.copy(id = baseEvent.id + "-b"), routerSession)
            check("router.mute.sameSession", muted.suppressed == "session_muted", "suppressed=${muted.suppressed}")
            router.setActiveSessionId("session:$groupId")
            val active = router.onEvent(
                baseEvent.copy(id = "t56-active", category = com.rhodes.privatechat.shared.domain.automation.NotificationCategory.DELAYED_TASK_CATCH_UP),
                "session:$groupId",
            )
            check("router.mute.activeSession", active.suppressed == "session_active", "suppressed=${active.suppressed}")
            NotificationBridge(settings).savePref("moments", false)
            val switchedOff = router.onEvent(baseEvent.copy(id = "t56-off"), routerSession)
            check("router.switch.off", switchedOff.suppressed == "switch_off", "suppressed=${switchedOff.suppressed}")
            NotificationBridge(settings).savePref("moments", prefsBefore.moments)
            NotificationBridge(settings).savePref("private_reply", prefsBefore.privateReply)
            router.setActiveSessionId("")

            // 6) 层 KEEP / 取消语义（快速矩阵，避免回归）。
            val keepKey = "t56-keep"
            testLayer.registerHandler("t56-") { _ -> DelayedTaskOutcome.DONE }
            val firstEnqueue = testLayer.enqueue(keepKey, now + 60_000)
            val secondEnqueue = testLayer.enqueue(keepKey, now + 120_000)
            check("layer.keep", firstEnqueue && !secondEnqueue, "first=$firstEnqueue second=$secondEnqueue")
            testLayer.cancel(keepKey)
            check("layer.cancel", testLayer.pendingKeys().contains(keepKey).not(), "keys=${testLayer.pendingKeys()}")
        } finally {
            runCatching { groupChat.setAutoPlanListener(defaultAutoPlanListener) }
            runCatching { testScope.cancel() }
        }
        return finishSelfTest(lines, pass, fail, "")
    }

    /** 自检专用：直接调用已注册的群自动互动执行体（不依赖生产层）。 */
    private suspend fun createGroupForSelfTest(
        preferredOperator: com.rhodes.privatechat.shared.model.Operator,
        candidates: List<com.rhodes.privatechat.shared.model.Operator>,
    ): String? {
        val ids = candidates.map { it.id }.distinct().take(3)
        if (ids.isEmpty()) return null
        val deferred = CompletableDeferred<String?>()
        groupChat.createGroup("T56 自动化验证群", (listOf(preferredOperator.id) + ids).distinct()) { groupId, error ->
            if (groupId == null) A7GlueLog.log("selftest.group", "create failed err=$error")
            deferred.complete(groupId)
        }
        return withTimeoutOrNull(15_000L) { deferred.await() }
    }

    private suspend fun waitUntil(name: String, timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            if (condition()) return true
            delay(150)
        }
        A7GlueLog.log("selftest.timeout", name)
        return false
    }

    /**
     * tick 补触发断言（模拟「App 后台期间到期、回前台 tick 兜底」）：
     * 前台 false → 层 enqueue（只进挂起表、不排 Timer）→ 层确无执行 → flushOverdue（tick 原语）→ 恰好执行 1 次
     * → 记忆维护重建路径完成（rebuild_pending 清除 + 短期摘要落库）。
     */
    private suspend fun assertTickExecution(
        groupId: String,
        now: Long,
        testLayer: DelayedTaskLayer,
        testChannel: A7ForegroundChannel,
        lines: MutableList<String>,
        check: (String, Boolean, String) -> Unit,
        contextKeyFor: String,
    ) {
        val groupMessages = chatRepo.getMessagesSync(groupId)
        settings.putBoolean(ContextMaintenanceKeys.rebuildPendingKey(groupId), true)
        settings.putString(
            ContextMaintenanceKeys.rebuildChangedKey(groupId),
            groupMessages.firstOrNull()?.id?.toString().orEmpty(),
        )
        if (groupMessages.size >= 2) settings.putMemoryExtractionCursor(groupId, groupMessages[0].id)
        val payload = mapOf(
            ContextMaintenanceKeys.PAYLOAD_SESSION to groupId,
            ContextMaintenanceKeys.PAYLOAD_GROUP to "true",
        )
        testChannel.setForeground(false)
        val queued = testLayer.enqueue(contextKeyFor, now - 1_000L, payload)
        check("tick.queued", queued && testLayer.pendingKeys().contains(contextKeyFor), "pending=${testLayer.pendingKeys()}")
        check("tick.paused", testChannel.isForeground().not(), "foreground=${testChannel.isForeground()}")
        // tick 兜底扫描（生产中由前台 tick 循环调用同一原语）。
        val tickFired = testChannel.flushOverdue(clock())
        check("tick.fired", tickFired >= 1, "fired=$tickFired key=$contextKeyFor")
        testChannel.setForeground(true)
        waitUntil("context.executed", 20_000L) {
            !settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(groupId))
        }
        check(
            "context.rebuild.cleared",
            !settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(groupId)),
            "pending=${settings.getBoolean(ContextMaintenanceKeys.rebuildPendingKey(groupId))}",
        )
        check(
            "context.memory.ingested",
            chatRepo.getShortTermMemory(groupId)?.content?.contains("T56 自检") == true,
            "summary=${chatRepo.getShortTermMemory(groupId)?.content?.take(40)}",
        )
    }

    private fun finishSelfTest(lines: MutableList<String>, pass: Int, fail: Int, abort: String): String {
        val head = "=== T56 A7 glue selftest ===\n" +
            "time=${clock()} pass=$pass fail=$fail" + if (abort.isBlank()) "" else " abort=$abort"
        val result = if (fail == 0 && abort.isBlank()) "RESULT PASS" else "RESULT FAIL"
        return (listOf(head) + lines + listOf(result)).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // --moment-reply-selftest / --moment-reply-demo（T60：发布→调度→执行→DB/UI 断言）
    // ------------------------------------------------------------------

    /**
     * T60 自检入口（由 IosAppGraph 启动参数调用）：
     * - `--moment-reply-selftest`：走真实 `MomentsBridge.publish` + 生产调度逻辑（测试层/确定性 AI/立即到期），
     *   断言 reply_turns/评论/点赞/未读/通知/列表 DTO，结束后清理夹具；
     * - `--moment-reply-demo`：同一路径但保留夹具，供 UI 截图取证。
     * 报告写 Application Support/t60-moment-reply-{selftest,demo}.log。
     */
    fun debugRunMomentReplySelfTest(bridge: MomentsBridge, keepFixture: Boolean, onDone: (String) -> Unit) {
        workScope.launch {
            val report = runCatching { runMomentReplySelfTest(bridge, keepFixture) }
                .getOrElse { t ->
                    A7GlueLog.log("selftest.t60.crash", "${t.message}")
                    "=== T60 moment auto reply selftest ===\nRESULT FAIL crash=${t::class.simpleName ?: "Throwable"}: ${t.message}"
                }
            val name = if (keepFixture) "t60-moment-reply-demo.log" else "t60-moment-reply-selftest.log"
            val path = "${MediaDirectory.applicationSupportDirectory()}/$name"
            runCatching { FileIo.writeBytes(path, report.encodeToByteArray()) }
                .onFailure { A7GlueLog.log("selftest.t60.write", "err=${it.message}") }
            NSLog("[RhodesLink][T60] selftest report written to $path")
            mainScope.launch { onDone(report) }
        }
    }

    /** `--moment-reply-fixture-clean`：删除 T60 自检/演示夹具（含 reply_turns）。回调 (删除动态数, 错误)。 */
    fun debugCleanMomentReplyFixtures(onDone: (Int, String?) -> Unit) {
        workScope.launch {
            try {
                val removed = cleanMomentReplyFixtures()
                mainScope.launch { onDone(removed, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(0, t.message ?: "清理失败") }
            }
        }
    }

    /** 删除内容带 T60 标记的用户动态（级联点赞/评论）与其 reply_turns；返回删除动态数。 */
    private suspend fun cleanMomentReplyFixtures(): Int {
        val mine = chatRepo.moments.getMomentsByOperator(USER_OPERATOR_ID)
        var removed = 0
        mine.forEach { moment ->
            if (moment.content.contains(FIXTURE_MARK)) {
                chatRepo.replyTurns.deleteBySession("moment:${moment.id}")
                chatRepo.deleteMoment(moment.id)
                removed++
            }
        }
        return removed
    }

    private suspend fun runMomentReplySelfTest(bridge: MomentsBridge, keepFixture: Boolean): String {
        val lines = mutableListOf<String>()
        var pass = 0
        var fail = 0
        fun check(name: String, condition: Boolean, detail: String = "") {
            if (condition) pass++ else fail++
            lines += (if (condition) "PASS " else "FAIL ") + name + if (detail.isBlank()) "" else " | $detail"
            A7GlueLog.log("selftest.t60", (if (condition) "PASS " else "FAIL ") + name + " " + detail.take(160))
        }
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val testChannel = A7ForegroundChannel(testScope, clock)
        val testLayer = DelayedTaskLayer(primaryChannel = testChannel, scope = testScope, clock = clock)
        val savedHook = bridge.momentReplyHook
        val savedProvider = settings.provider
        val savedCustomUrl = settings.customUrl
        val savedMin = settings.commentMinChars
        val savedMax = settings.commentMaxChars
        val savedPrefs = NotificationBridge(settings).readPrefs()
        var fixtureMomentId = 0L
        try {
            lines += "=== T60 moment auto reply selftest ==="
            lines += "time=${clock()} keepFixture=$keepFixture"
            lines += "fixture.cleanup removed=${cleanMomentReplyFixtures()}"
            chatRepo.ensurePresetOperators()
            val operators = chatRepo.getAllOperatorsSync()
                .filter { it.id != USER_OPERATOR_ID && !it.id.startsWith("group") }
            val userName = settings.userName.ifBlank { "博士" }
            check("fixture.operators", operators.size >= 2, "count=${operators.size}")
            if (operators.size < 2) {
                return finishMomentReplySelfTest(lines, pass, fail, "operators<2")
            }
            // 确定性：未配置供应商（走演示 AI）+ 固定评论字数设置（oracle 默认 10..40）。
            settings.provider = "custom"
            settings.customUrl = ""
            settings.commentMinChars = 10
            settings.commentMaxChars = 40
            NotificationBridge(settings).savePref("moments", true)

            val recorder = A7RecordingNotificationSender()
            val router = A7NotificationRouter(settings, recorder, clock, dedupWindowMs = 60_000L)
            val testTask = MomentReplyTask(
                store = RepositoryMomentReplyPort(chatRepo, dbWrapper),
                promptAssembler = MomentCommentPromptAssembler(),
                ai = A7SelfTestCommentClient,
                settings = momentSettings(),
                contextProviders = MomentCommentContextProviders(),
                clock = clock,
                events = { event ->
                    if (event is MomentReplyEvent.NotificationRequested) {
                        val momentId = event.event.payload["momentId"] ?: "0"
                        router.onEvent(event.event, "moment:$momentId")
                    }
                },
            )
            testLayer.registerHandler(MomentReplyKeys.PREFIX) { due -> testTask.execute(due) }
            // 模拟「App 运行中但计时器未触发」：先只入挂起表，随后用 tick/catch-up 的 flushOverdue 原语补触发。
            testChannel.setForeground(false)
            // 发布 hook = 生产调度逻辑（延迟/参与人数/字数设置一致），仅覆盖延迟为立即到期并注入确定性随机源。
            bridge.momentReplyHook = { momentId ->
                scheduleMomentReplies(
                    targetLayer = testLayer,
                    targetTask = testTask,
                    momentId = momentId,
                    delayOverrideMs = 0L,
                    random = Random(T60_TEST_RANDOM_SEED),
                )
            }

            val token = "$FIXTURE_MARK$TEST_CONTENT_PREFIX" + clock()
            val text = "$token：今天和干员们一起完成了训练，记录一下。"
            fixtureMomentId = bridge.publishForSelfTest(text)
            check("publish.ok", fixtureMomentId > 0L, "id=$fixtureMomentId")
            val storedMoment = chatRepo.getMoment(fixtureMomentId)
            check(
                "publish.isUserPost",
                storedMoment?.isUserPost == true && storedMoment.operatorId == USER_OPERATOR_ID,
                "operator=${storedMoment?.operatorId} name=${storedMoment?.operatorName}",
            )

            // 1) 调度：1..3 个 pending 评论 turn + 2..6 个干员点赞（oracle postUserMoment :3052/:3069）。
            val turnsAtSchedule = chatRepo.replyTurns.momentTurns(fixtureMomentId)
            check("schedule.turns", turnsAtSchedule.size in 1..3, "turns=${turnsAtSchedule.size}")
            check(
                "schedule.turns.pending",
                turnsAtSchedule.all { it.status == ReplyTurnStatus.PENDING },
                "status=${turnsAtSchedule.map { it.status }}",
            )
            check(
                "schedule.turns.session",
                turnsAtSchedule.all { it.sessionId == "moment:$fixtureMomentId" },
                "session=${turnsAtSchedule.firstOrNull()?.sessionId}",
            )
            val likesAtSchedule = chatRepo.getLikeCount(fixtureMomentId)
            check("schedule.likes", likesAtSchedule in 2..6, "likes=$likesAtSchedule")
            check(
                "schedule.likeCountField",
                chatRepo.getMoment(fixtureMomentId)?.likeCount == likesAtSchedule,
                "field=${chatRepo.getMoment(fixtureMomentId)?.likeCount} rows=$likesAtSchedule",
            )
            check(
                "schedule.turns.due",
                turnsAtSchedule.all { it.nextAttemptAt <= clock() },
                "dueAt=${turnsAtSchedule.map { it.nextAttemptAt }}",
            )

            // 2) tick/catch-up 补触发 → 执行体落库（与前台 tick 循环同一原语）。
            val fired = testChannel.flushOverdue(clock())
            testChannel.setForeground(true)
            check("tick.fired", fired == turnsAtSchedule.size, "fired=$fired turns=${turnsAtSchedule.size}")
            val settled = waitUntil("turns.settle", 30_000L) {
                val current = chatRepo.replyTurns.momentTurns(fixtureMomentId)
                current.isNotEmpty() && current.all {
                    it.status != ReplyTurnStatus.PENDING && it.status != ReplyTurnStatus.RUNNING
                }
            }
            check("execute.settled", settled)
            val settledTurns = chatRepo.replyTurns.momentTurns(fixtureMomentId)
            val succeededTurns = settledTurns.filter { it.status == ReplyTurnStatus.SUCCEEDED }
            check(
                "turn.succeeded",
                settledTurns.isNotEmpty() && succeededTurns.size == settledTurns.size,
                "succeeded=${succeededTurns.size}/${settledTurns.size} status=${settledTurns.map { it.status }}",
            )
            check("layer.drained", testLayer.pendingKeys().isEmpty(), "pending=${testLayer.pendingKeys()}")

            // 3) moment_comments 落库：干员名 + 内容 + 字数在设置范围 + 计数回写 + 未读。
            val dbComments = momentPort.recentComments(fixtureMomentId)
            val aiComments = dbComments.filter { it.operatorId != USER_OPERATOR_ID }
            check(
                "comment.inserted",
                aiComments.isNotEmpty() && aiComments.size == succeededTurns.size,
                "ai=${aiComments.size} db=${dbComments.size} turns=${succeededTurns.size}",
            )
            val firstComment = aiComments.firstOrNull()
            check(
                "comment.operatorName",
                firstComment != null && firstComment.operatorName.isNotBlank() && firstComment.operatorName != userName,
                "name=${firstComment?.operatorName}",
            )
            val contentLength = firstComment?.content?.length ?: 0
            check(
                "comment.length",
                contentLength in settings.commentMinChars..settings.commentMaxChars,
                "len=$contentLength range=${settings.commentMinChars}..${settings.commentMaxChars}",
            )
            check(
                "comment.content",
                firstComment?.content == A7MomentStreamingClient.DEMO_COMMENT,
                "content=${firstComment?.content?.take(40)}",
            )
            check(
                "comment.unread",
                aiComments.isNotEmpty() && aiComments.all { !it.isRead },
                "unread=${aiComments.count { !it.isRead }}/${aiComments.size}",
            )
            check(
                "moment.commentCount",
                chatRepo.getCommentCount(fixtureMomentId) == dbComments.size &&
                    chatRepo.getMoment(fixtureMomentId)?.commentCount == dbComments.size,
                "rows=${dbComments.size} field=${chatRepo.getMoment(fixtureMomentId)?.commentCount}",
            )
            check(
                "moment.likes",
                chatRepo.getLikeCount(fixtureMomentId) == likesAtSchedule,
                "rows=${chatRepo.getLikeCount(fixtureMomentId)} scheduled=$likesAtSchedule",
            )

            // 4) 未读角标（评论收件箱）：AI 评论未读 → getUnreadCommentCount 增加（FeaturesView 10s 轮询同口径）。
            val cutoff = clock() - 30L * 24 * 60 * 60 * 1000
            val unreadComments = chatRepo.getUnreadCommentCount(cutoff, userName)
            check("badge.unreadComments", unreadComments >= aiComments.size, "unread=$unreadComments ai=${aiComments.size}")

            // 5) 通知事件（notify_moments 开关打开；同会话 60s 去重只保证首条送达）。
            val notifIds = recorder.sent.map { it.id }
            val expectedIds = succeededTurns.map { NotificationCopy.MOMENT_REPLY_ID_PREFIX + it.id }
            check(
                "notify.recorded",
                expectedIds.isNotEmpty() && expectedIds.any { it in notifIds },
                "expected=${expectedIds.take(2)} sent=${notifIds.takeLast(2)}",
            )

            // 6) UI 刷新数据源：列表 DTO 的评论/点赞数已更新（MomentsView reload 读同一接口）。
            val row = bridge.debugMomentRow(fixtureMomentId)
            check("ui.row.present", row != null, "row=${row?.id}")
            check("ui.row.text", row?.text == text, "text=${row?.text?.take(24)}")
            check(
                "ui.row.commentCount",
                row?.commentCount == dbComments.size,
                "dto=${row?.commentCount} db=${dbComments.size}",
            )
            check(
                "ui.row.likeCount",
                row?.likeCount == chatRepo.getLikeCount(fixtureMomentId),
                "dto=${row?.likeCount} db=${chatRepo.getLikeCount(fixtureMomentId)}",
            )

            // 7) 重复调度幂等：同 moment 再跑一次生产调度不新增 turn/点赞（KEEP + createIfAbsent）。
            scheduleMomentReplies(
                targetLayer = testLayer,
                targetTask = testTask,
                momentId = fixtureMomentId,
                delayOverrideMs = 0L,
                random = Random(T60_TEST_RANDOM_SEED),
            )
            check(
                "schedule.idempotent",
                chatRepo.replyTurns.momentTurns(fixtureMomentId).size == settledTurns.size &&
                    chatRepo.getLikeCount(fixtureMomentId) == likesAtSchedule,
                "turns=${chatRepo.replyTurns.momentTurns(fixtureMomentId).size} likes=${chatRepo.getLikeCount(fixtureMomentId)}",
            )
            lines += "fixture.momentId=$fixtureMomentId comments=${dbComments.size} likes=${likesAtSchedule} " +
                "turns=${settledTurns.size} keep=$keepFixture"
        } catch (t: Throwable) {
            lines += "FATAL ${t.message ?: t::class.simpleName}"
            fail++
        } finally {
            bridge.momentReplyHook = savedHook
            settings.provider = savedProvider
            settings.customUrl = savedCustomUrl
            settings.commentMinChars = savedMin
            settings.commentMaxChars = savedMax
            runCatching { NotificationBridge(settings).savePref("moments", savedPrefs.moments) }
            if (!keepFixture && fixtureMomentId > 0L) {
                runCatching { chatRepo.replyTurns.deleteBySession("moment:$fixtureMomentId") }
                runCatching { chatRepo.deleteMoment(fixtureMomentId) }
                lines += "fixture.cleaned momentId=$fixtureMomentId"
            }
            runCatching { testScope.cancel() }
        }
        return finishMomentReplySelfTest(lines, pass, fail, "")
    }

    private fun finishMomentReplySelfTest(
        lines: MutableList<String>,
        pass: Int,
        fail: Int,
        abort: String,
    ): String {
        val head = "=== T60 moment auto reply selftest ===\n" +
            "time=${clock()} pass=$pass fail=$fail" + if (abort.isBlank()) "" else " abort=$abort"
        val result = if (fail == 0 && abort.isBlank()) "RESULT PASS" else "RESULT FAIL"
        return (listOf(head) + lines + listOf(result)).joinToString("\n")
    }

}

/** T60 自检/演示夹具常量与随机种子（确定性：固定种子 → 固定参与干员集合）。 */
private const val USER_OPERATOR_ID = "user"
private const val FIXTURE_MARK = "T60"
private const val TEST_CONTENT_PREFIX = "自检"
private const val T60_TEST_RANDOM_SEED = 60

/** 自检用确定性评论模型（不联网、不读供应商配置）。 */
private object A7SelfTestCommentClient : StreamingClient {
    override suspend fun complete(messages: List<AiMessage>, stage: String): String {
        delay(40)
        return A7MomentStreamingClient.DEMO_COMMENT
    }
}
