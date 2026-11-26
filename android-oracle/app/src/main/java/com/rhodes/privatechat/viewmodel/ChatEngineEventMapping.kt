package com.rhodes.privatechat.viewmodel

import com.rhodes.privatechat.shared.domain.chat.EngineEvent
import com.rhodes.privatechat.shared.domain.chat.EngineEventSink
import com.rhodes.privatechat.shared.domain.chat.ReplyDiagnostics
import com.rhodes.privatechat.shared.domain.chat.group.GroupEngineEvent

/**
 * T35 批 C 续作 步骤 1：引擎事件 → 宿主动作映射（**冻结表**，替换入口时不得各自解释事件）。
 *
 * 设计原则（`ios-port/handoffs/impl-batch-c.md` §5.2-1 / `impl-chat-engine.md` §6-1）：
 * - 引擎只**算**不**写**宿主专属副作用（亲密/龙门币/催眠/通知/未读/维护调度/WorkManager）；
 * - 宿主实现本接口，把事件映射回 ChatViewModel 的现有副作用点（逐条见下）；
 * - 映射是一对一的纯函数：`EngineEvent` 11 种 → 11 个回调，不做二次状态推导。
 *
 * | EngineEvent | 宿主动作（oracle ChatViewModel.kt 对照） |
 * |---|---|
 * | LoadingChanged | `_loadingSessions` 增删（:171-174 / :195-203） |
 * | Toast | `onShowToast`（各调用点） |
 * | ClaimDenied | DebugLogger 诊断；Toast 由引擎另行发射（:1448-1453） |
 * | Undelivered | `_messages` 内存行同步置 send_failed（:1281-1296；DB 写入引擎已完成） |
 * | ReplyStored | `finishDebugRound`（诊断快照 :1631/:1641/:1665）+ 滚动到最新 |
 * | Settled | 亲密 `updateOperatorIntimacy(affectionMod)`、`grantDailyLmb(dailyLmb)`、催眠递减、通知、`markUnreadIfNotCurrent`、`schedulePrivatePostReplyMaintenance`（:1649-1655） |
 * | TurnStateUpdated | `privateTurnStateUpdates++` 触发头部状态刷新（:1625-1626） |
 * | MaintenanceRequested | 滚动摘要/记忆维护调度（:2759，绝不占本轮锁） |
 * | UnhideSession | `onUnhideSession`（首页恢复 :1462） |
 * | UnreadHint | 仅当前会话之外的未读计数（:1655） |
 * | FailureMessageStored | DebugLogger 记录失败系统消息（:1268-1279；DB 写入引擎已完成） |
 */
interface ChatEngineHost {
    fun onLoadingChanged(sessionId: String, loading: Boolean)
    fun onToast(message: String)
    fun onClaimDenied(sessionId: String, messageId: Long, turnId: String)
    fun onUndelivered(sessionId: String, messageIds: Set<Long>)
    fun onReplyStored(sessionId: String, userMessageIds: Set<Long>, aiMessageId: Long, diagnostics: ReplyDiagnostics?)
    fun onSettled(
        sessionId: String,
        operatorId: String,
        affectionMod: Int,
        dailyLmb: Int,
        visibleCount: Int,
        preview: String,
        diagnostics: ReplyDiagnostics?,
    )
    fun onTurnStateUpdated(sessionId: String)
    fun onMaintenanceRequested(sessionId: String)
    fun onUnhideSession(sessionId: String)
    fun onUnreadHint(sessionId: String, count: Int)
    fun onFailureMessageStored(sessionId: String, messageId: Long)
}

/** `EngineEvent` → [ChatEngineHost] 的唯一映射实现（11/11 覆盖；冻结表见接口 KDoc）。 */
class ChatEngineEventMapper(
    private val host: ChatEngineHost,
) : EngineEventSink {
    override fun emit(event: EngineEvent) {
        when (event) {
            is EngineEvent.LoadingChanged -> host.onLoadingChanged(event.sessionId, event.loading)
            is EngineEvent.Toast -> host.onToast(event.message)
            is EngineEvent.ClaimDenied -> host.onClaimDenied(event.sessionId, event.messageId, event.turnId)
            is EngineEvent.Undelivered -> host.onUndelivered(event.sessionId, event.messageIds)
            is EngineEvent.ReplyStored ->
                host.onReplyStored(event.sessionId, event.userMessageIds, event.aiMessageId, event.diagnostics)
            is EngineEvent.Settled -> host.onSettled(
                sessionId = event.sessionId,
                operatorId = event.operatorId,
                affectionMod = event.affectionMod,
                dailyLmb = event.dailyLmb,
                visibleCount = event.visibleCount,
                preview = event.preview,
                diagnostics = event.diagnostics,
            )
            is EngineEvent.TurnStateUpdated -> host.onTurnStateUpdated(event.sessionId)
            is EngineEvent.MaintenanceRequested -> host.onMaintenanceRequested(event.sessionId)
            is EngineEvent.UnhideSession -> host.onUnhideSession(event.sessionId)
            is EngineEvent.UnreadHint -> host.onUnreadHint(event.sessionId, event.count)
            is EngineEvent.FailureMessageStored -> host.onFailureMessageStored(event.sessionId, event.messageId)
        }
    }
}

/**
 * 群聊事件宿主（`GroupEngineEvent` 12 种 → 12 个回调；D3 全员禁言的「回调值」走
 * `GroupChatEngine.sendGroupMessage(onResponseComplete)`，不经过本映射）。
 *
 * | GroupEngineEvent | 宿主动作（oracle GroupChatViewModel.kt） |
 * |---|---|
 * | LoadingChanged | `_groupLoading` / `loadingGroups` 增删（:273-276） |
 * | Toast | `_lastSendError` + Toast（各调用点） |
 * | ClaimDenied | 诊断日志；「该消息正在恢复发送」Toast 由引擎发射（:1049-1056） |
 * | Undelivered | `_messages` 同步置 send_failed（:1919-1930；DB 写入引擎已完成） |
 * | ReplyStored | 滚动到最新 + 诊断（:1755-1761） |
 * | SystemMessageStored | 系统消息 UI 刷新（禁言/重启 :1157-1161） |
 * | TurnStateUpdated | 群回合状态刷新（:1762-1763） |
 * | Settled | 手动轮次每日龙门币 `grantDailyLmb`（:1783-1786；群聊无亲密结算） |
 * | MaintenanceRequested | 群聊记忆/摘要维护调度（:1789） |
 * | UnhideSession | 首页恢复（:1779） |
 * | UnreadHint | 非当前群未读计数（:1788） |
 * | AutoPlanChanged | Timer/BGTask 通道重排（T22 胶水批） |
 */
interface GroupChatEngineHost {
    fun onLoadingChanged(groupId: String, loading: Boolean)
    fun onToast(message: String)
    fun onClaimDenied(groupId: String, messageId: Long, turnId: String)
    fun onUndelivered(groupId: String, messageIds: Set<Long>)
    fun onReplyStored(groupId: String, userMessageIds: Set<Long>, aiMessageId: Long, speakers: List<String>, narrationCount: Int)
    fun onSystemMessageStored(groupId: String, messageId: Long)
    fun onTurnStateUpdated(groupId: String, fallbackDerived: Boolean)
    fun onSettled(groupId: String, dailyLmb: Int?, visibleCount: Int, preview: String)
    fun onMaintenanceRequested(groupId: String, reason: String)
    fun onUnhideSession(groupId: String)
    fun onUnreadHint(groupId: String, count: Int)
    fun onAutoPlanChanged(groupId: String, state: String, dueAt: Long, round: Int)
}

/** `GroupEngineEvent` → [GroupChatEngineHost] 的唯一映射实现（12/12 覆盖；冻结表见接口 KDoc）。 */
class GroupChatEngineEventMapper(
    private val host: GroupChatEngineHost,
) : (GroupEngineEvent) -> Unit {
    override fun invoke(event: GroupEngineEvent) {
        when (event) {
            is GroupEngineEvent.LoadingChanged -> host.onLoadingChanged(event.groupId, event.loading)
            is GroupEngineEvent.Toast -> host.onToast(event.message)
            is GroupEngineEvent.ClaimDenied -> host.onClaimDenied(event.groupId, event.messageId, event.turnId)
            is GroupEngineEvent.Undelivered -> host.onUndelivered(event.groupId, event.messageIds)
            is GroupEngineEvent.ReplyStored -> host.onReplyStored(
                event.groupId, event.userMessageIds, event.aiMessageId, event.speakers, event.narrationCount,
            )
            is GroupEngineEvent.SystemMessageStored -> host.onSystemMessageStored(event.groupId, event.messageId)
            is GroupEngineEvent.TurnStateUpdated -> host.onTurnStateUpdated(event.groupId, event.fallbackDerived)
            is GroupEngineEvent.Settled -> host.onSettled(event.groupId, event.dailyLmb, event.visibleCount, event.preview)
            is GroupEngineEvent.MaintenanceRequested -> host.onMaintenanceRequested(event.groupId, event.reason)
            is GroupEngineEvent.UnhideSession -> host.onUnhideSession(event.groupId)
            is GroupEngineEvent.UnreadHint -> host.onUnreadHint(event.groupId, event.count)
            is GroupEngineEvent.AutoPlanChanged -> host.onAutoPlanChanged(event.groupId, event.state, event.dueAt, event.round)
        }
    }
}
