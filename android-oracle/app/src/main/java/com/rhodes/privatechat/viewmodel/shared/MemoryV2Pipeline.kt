package com.rhodes.privatechat.viewmodel.shared

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.memory.MemoryDiagnostics
import com.rhodes.privatechat.shared.domain.memory.MemoryOperationSink
import com.rhodes.privatechat.shared.domain.memory.MemoryV2Pipeline as SharedMemoryV2Pipeline
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.network.AIService
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.util.DebugLogger

/**
 * App 侧记忆 V2 管线入口（T26 接线批 A）。
 *
 * 管线本体单一来源为 `shared/domain/memory/MemoryV2Pipeline.kt`（T24 由 Android oracle 整文件迁移，
 * 仅平台化：时钟注入 / 诊断日志接口 / 召回与解析规则拆函数）。本类仅保留 app 侧公开签名与构造签名的
 * 薄委托，ViewModels 的调用点零改动。
 *
 * 平台化差异（与 Android oracle 等价性论证见 ios-port/handoffs/impl-wiring-a.md）：
 * - 时间：oracle `System.currentTimeMillis()` → shared `Clock.System` 默认实现，同一墙钟；
 * - 诊断：oracle 直接调 [DebugLogger]（android.util.Log 专有），shared 经 [MemoryDiagnostics] 接口。
 *   此处在构造时把 `log`/`trace` 接回 [DebugLogger]，保持 Android 诊断日志继续可见；诊断页的
 *   operation 时间线（beginOperation/conversationStep）接口在当前 shared 层为 no-op 占位，属接线批 B
 *   待办（见 handoff open questions）。
 */
class MemoryV2Pipeline(
    private val repository: ChatRepository,
    private val settings: SettingsRepository,
    private val aiService: AIService,
    private val memoryVectorService: MemoryVectorService? = null,
    private val userNicknameProvider: () -> String,
) {
    private val delegate = SharedMemoryV2Pipeline(
        repository = repository,
        settings = settings,
        aiService = aiService,
        memoryVectorService = memoryVectorService,
        userNicknameProvider = userNicknameProvider,
    )

    init {
        // T24 handoff §8.3：app 启动/构造时把记忆诊断接口接回 DebugLogger（log/trace 两条通道）。
        MemoryDiagnostics.install { tag, message -> DebugLogger.log(tag, message) }
        // T27 接线批 B：operation 时间线（诊断页「私聊/群聊记忆提取」卡片）接回 DebugLogger。
        MemoryDiagnostics.installOperations(object : MemoryOperationSink {
            override fun beginOperation(surface: String, target: String, mode: String): String =
                DebugLogger.beginOperation(surface, target, mode)

            override fun conversationStep(roundId: String, surface: String, stage: String, status: String, details: String) =
                DebugLogger.conversationStep(roundId, surface, stage, status, details)
        })
        MemoryDiagnostics.allowSensitiveTrace = DebugLogger.allowSensitiveTrace
    }

    suspend fun ingestPrivateChat(sessionId: String, operatorId: String, operatorName: String, messages: List<ChatMessage>, currentRound: Int): Boolean =
        delegate.ingestPrivateChat(sessionId, operatorId, operatorName, messages, currentRound)

    suspend fun ingestGroupChat(groupId: String, groupName: String, messages: List<ChatMessage>, memberIds: List<String>): Boolean =
        delegate.ingestGroupChat(groupId, groupName, messages, memberIds)

    suspend fun ingestMoment(moment: Moment, contextGroup: String = "") =
        delegate.ingestMoment(moment, contextGroup)

    suspend fun ingestMomentComment(comment: MomentComment, momentId: Long) =
        delegate.ingestMomentComment(comment, momentId)

    suspend fun ingestDiary(operatorId: String, operatorName: String, diaryId: String, content: String) =
        delegate.ingestDiary(operatorId, operatorName, diaryId, content)

    suspend fun maybePromotePrivateMemory(operatorId: String, thresholdL1: Int, thresholdL2: Int) =
        delegate.maybePromotePrivateMemory(operatorId, thresholdL1, thresholdL2)

    suspend fun buildPrivateMemoryContext(
        operatorId: String,
        limitL1: Int,
        limitL2: Int,
        limitL3: Int,
        query: String = "",
        applyPrivateSourceFilter: Boolean = false,
        allowedSources: Set<String>? = null,
        visibilities: List<String> = emptyList(),
    ): String = delegate.buildPrivateMemoryContext(
        operatorId = operatorId,
        limitL1 = limitL1,
        limitL2 = limitL2,
        limitL3 = limitL3,
        query = query,
        applyPrivateSourceFilter = applyPrivateSourceFilter,
        allowedSources = allowedSources,
        visibilities = visibilities,
    )

    suspend fun ingestVision(
        ownerType: String,
        ownerId: String,
        sourceKind: MemorySourceKind,
        sourceRefId: String,
        content: String,
        isPrivate: Boolean,
    ) = delegate.ingestVision(ownerType, ownerId, sourceKind, sourceRefId, content, isPrivate)

    suspend fun buildPrivateChatMemoryContext(
        operatorId: String,
        query: String,
        allowedSources: Set<String> = privateChatAllowedSources(),
        allowPrivateVisualRecall: Boolean = settings.privateRecallPrivateChatMemory,
    ): String = delegate.buildPrivateChatMemoryContext(operatorId, query, allowedSources, allowPrivateVisualRecall)

    fun privateChatAllowedSources(): Set<String> = delegate.privateChatAllowedSources()

    suspend fun buildRelationshipPrivateMemoryContext(operatorId: String, query: String): String =
        delegate.buildRelationshipPrivateMemoryContext(operatorId, query)

    suspend fun buildPrivateStableImpression(operatorId: String, limit: Int = 3): String =
        delegate.buildPrivateStableImpression(operatorId, limit)

    suspend fun buildPublicMemoryContext(
        query: String,
        limit: Int = 3,
        allowedSources: Set<String> = buildSet {
            if (settings.isMemoryInjectionAllowed("private_chat", "MOMENT")) add(MemorySourceKind.MOMENT.name)
            if (settings.isMemoryInjectionAllowed("private_chat", "MOMENT_COMMENT")) add(MemorySourceKind.MOMENT_COMMENT.name)
        },
    ): String = delegate.buildPublicMemoryContext(query, limit, allowedSources)

    suspend fun buildOwnerMemoryContext(
        ownerType: String,
        ownerId: String,
        limitL1: Int,
        limitL2: Int,
        limitL3: Int,
        query: String = "",
        allowedSources: Set<String>? = null,
        minCreatedAt: Long = 0L,
        visibilities: List<String> = emptyList(),
    ): String = delegate.buildOwnerMemoryContext(
        ownerType = ownerType,
        ownerId = ownerId,
        limitL1 = limitL1,
        limitL2 = limitL2,
        limitL3 = limitL3,
        query = query,
        allowedSources = allowedSources,
        minCreatedAt = minCreatedAt,
        visibilities = visibilities,
    )

    suspend fun retryPendingSources(limit: Int = 20): Int = delegate.retryPendingSources(limit)
}
