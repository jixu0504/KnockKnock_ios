package com.rhodes.privatechat.shared.domain.memory

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MemoryBatch
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemoryLink
import com.rhodes.privatechat.shared.model.MemorySourceItem
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.network.AIService
import com.rhodes.privatechat.shared.prompt.MemoryV2PromptTemplates
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import com.rhodes.privatechat.shared.vector.VectorMemory
import com.rhodes.privatechat.shared.vector.VectorSearchRequest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource
import kotlinx.datetime.Clock

/** 记忆索引健康（记忆体检页与启动自检共用口径）。 */
data class MemoryIndexHealth(
    val signature: String,
    val active: Int,
    val ready: Int,
    val missing: Int,
    val stale: Int,
    val expired: Int,
    val inactive: Int,
) {
    val needsRepair: Boolean get() = missing + stale > 0
}

/** 一次索引重建的进度/结果；`remaining` 为 -1 表示批次进行中尚未统计。 */
data class MemoryIndexRepairReport(
    val signature: String,
    val scanned: Int,
    val repaired: Int,
    val failed: Int,
    val remaining: Int,
    val cancelled: Boolean,
)

class MemoryV2Pipeline(
    private val repository: ChatRepository,
    private val settings: SettingsRepository,
    private val aiService: AIService,
    private val memoryVectorService: MemoryVectorService? = null,
    private val userNicknameProvider: () -> String,
    private val clock: Clock = Clock.System,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val parseRules by lazy {
        MemoryParseRules(settings, { aiService.cleanJson(it) }, { nowMillis() }, userNicknameProvider)
    }
    private val memoryRetryDelaysMs = longArrayOf(10_000L, 60_000L, 300_000L)

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    /** 失败窗口已重试次数：退避阶梯必须从真实次数继续，否则永远停留在第 1 档（审计 X-07）。 */
    private suspend fun retryCountOf(sourceId: Long): Int =
        runCatching { repository.memoryV2.memorySourceRetryCount(sourceId) }.getOrDefault(0)

    private suspend fun scheduleMemoryRetry(sourceId: Long, token: String, priorFailures: Int, reason: String): String {
        return if (priorFailures < memoryRetryDelaysMs.size) {
            val delayMs = memoryRetryDelaysMs[priorFailures]
            repository.retryMemorySource(sourceId, token, nowMillis() + delayMs, reason)
            "将在${delayMs / 1000}秒后进行第${priorFailures + 1}/${memoryRetryDelaysMs.size}次自动重试"
        } else {
            repository.failMemorySource(sourceId, token, reason)
            "已完成${memoryRetryDelaysMs.size}次自动重试，停止自动请求，保留失败窗口"
        }
    }

    private suspend fun requestMemoryModel(messages: List<AiMessage>, requestType: String): String {
        val startedAt = TimeSource.Monotonic.markNow()
        MemoryDiagnostics.log("AI/$requestType/请求", "记忆模型请求开始\n厂商=${settings.provider}\n模型=${settings.modelName}\n消息数=${messages.size}\n输入字符=${messages.sumOf { it.content.length }}")
        if (MemoryDiagnostics.allowSensitiveTrace) {
            MemoryDiagnostics.trace("AI/→$requestType", messages.joinToString("\n\n") { "【${it.role}】\n${it.content}" })
        }
        return try {
            val result = aiService.chat(
                settings.apiKey, messages, settings.provider, settings.modelName, settings.customUrl,
                temperature = settings.aiTemperature, requestType = requestType
            )
            MemoryDiagnostics.log("AI/$requestType/响应", "记忆模型请求成功\n耗时=${startedAt.elapsedNow().inWholeMilliseconds}ms\n输入Token=${result.inputTokens}\n输出Token=${result.outputTokens}\n输出字符=${result.content.length}")
            recordTokenUsage(result.inputTokens, result.outputTokens)
            if (settings.provider == "deepseek") {
                val reasoning = result.reasoningContent.orEmpty()
                // 思维链原文不落任何日志：用户可能导出调试日志，思考过程不应出现在里面。
                MemoryDiagnostics.log(
                    "AI/$requestType/思维链状态",
                    "请求 thinking.type=${if (result.thinkingDisabled) "disabled" else "未显式设置"}\n" +
                        "响应 reasoning_content_present=${reasoning.isNotBlank()}\n" +
                        "reasoning_content_chars=${reasoning.length}\n" +
                        "思维链原文不写入日志（只保留状态与用量）",
                )
            }
            MemoryDiagnostics.trace("AI/←$requestType", result.content)
            result.content
        } catch (e: Exception) {
            MemoryDiagnostics.log("AI/$requestType/错误", "记忆模型请求失败\n耗时=${startedAt.elapsedNow().inWholeMilliseconds}ms\n异常=${e::class.simpleName}\n原因=${e.message ?: "未知错误"}")
            throw e
        }
    }

    private fun recordTokenUsage(inputTokens: Int, outputTokens: Int) {
        if (inputTokens <= 0 && outputTokens <= 0) return
        // Consumption stats page aggregates the "memory" category;记忆的额外成本必须可对账（审计 P1-4）。
        if (inputTokens > 0) {
            settings.addInputTokenCount(TOKEN_CATEGORY_MEMORY, inputTokens)
            settings.addDailyInputTokenCount(TOKEN_CATEGORY_MEMORY, beijingDate(nowMillis()), inputTokens)
        }
        if (outputTokens > 0) {
            settings.addOutputTokenCount(TOKEN_CATEGORY_MEMORY, outputTokens)
            settings.addDailyOutputTokenCount(TOKEN_CATEGORY_MEMORY, beijingDate(nowMillis()), outputTokens)
        }
    }

    suspend fun ingestPrivateChat(sessionId: String, operatorId: String, operatorName: String, messages: List<ChatMessage>, currentRound: Int): Boolean {
        if (!settings.memoryV2Enabled || !settings.privateMemoryGenerationEnabled) return false
        if (messages.isEmpty()) return true
        val operationId = MemoryDiagnostics.beginOperation("私聊记忆提取", operatorName, sessionId)
        MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "提取窗口", "进行中", "消息=${messages.size}，范围=${messages.first().id}-${messages.last().id}，阈值=${settings.privateMemoryExtractionThreshold}")
        val sourceText = messages.joinToString("\n") { formatPrivateMessage(it) }
        val source = MemorySourceItem(
            sourceKind = MemorySourceKind.PRIVATE_CHAT,
            ownerType = "operator",
            ownerId = operatorId,
            sourceRefId = "$sessionId:epoch${settings.getMemoryTimelineEpoch(sessionId)}:${messages.first().id}-${messages.last().id}",
            contentText = sourceText,
            timestamp = messages.lastOrNull()?.timestamp ?: nowMillis(),
            createdAt = nowMillis()
        )
        val sourceId = repository.insertMemorySource(source)
        if (repository.isMemorySourceFinished(sourceId)) {
            MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本轮总览", "成功", "重复窗口已处理")
            return true
        }
        val claimToken = sourceId.takeIf { it > 0 }?.let { repository.claimMemorySource(it, nowMillis()) }
            ?: run {
                MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本轮总览", "失败", "记忆队列正被其他任务处理")
                return false
            }
        val leaseRenewal = launchLeaseRenewal(sourceId, claimToken)
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { leaseRenewal.cancel() }

        val l1 = try {
            extractL1(
                sourceKind = MemorySourceKind.PRIVATE_CHAT,
                text = sourceText,
                ownerType = "operator",
                ownerId = operatorId,
                sourceRefId = source.sourceRefId,
                sessionId = sessionId,
                operatorName = operatorName
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            MemoryDiagnostics.log("MemoryV2", "私聊L1提取失败，已保留队列等待重试: ${e.message?.take(80)}")
            leaseRenewal.cancel()
            val retryMessage = scheduleMemoryRetry(sourceId, claimToken, priorFailures = retryCountOf(sourceId), e.message?.take(160) ?: "提取失败")
            MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本轮总览", "失败", "${e::class.simpleName}；$retryMessage")
            return false
        }
        if (settings.getMemoryTimelineEpoch(sessionId) > source.createdAt) {
            repository.skipMemorySource(sourceId, claimToken, "会话时间线已失效")
            leaseRenewal.cancel()
            MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本轮总览", "已跳过", "会话时间线已变化，本批记忆不再保存")
            return false
        }
        return try {
            if (l1.isNotEmpty()) {
                val saved = saveMemoryItems(withMessageEvidence(l1, messages))
                MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本地保存", "成功", "模型有效=${l1.size}条，新增=${saved.size}条，重复=${l1.size - saved.size}条")
                repository.saveMemoryBatch(MemoryBatch(
                    ownerType = "operator",
                    ownerId = operatorId,
                    sourceKind = MemorySourceKind.PRIVATE_CHAT,
                    targetLevel = MemoryLevel.L1,
                    inputCount = messages.size,
                    outputCount = l1.size,
                    windowStart = messages.first().timestamp,
                    windowEnd = messages.last().timestamp,
                    promptVersion = "memory_v2_l1_v3",
                    createdAt = nowMillis()
                ))
            }
            repository.completeMemorySource(sourceId, claimToken)
            if (settings.privateMemoryPromotionEnabled) {
                runCatching {
                    maybePromotePrivateMemory(operatorId, thresholdL1 = settings.memoryV2PromoteL1Threshold, thresholdL2 = settings.memoryV2PromoteL2Threshold)
                }.onFailure { error ->
                    MemoryDiagnostics.log("MemoryV2", "私聊长期沉淀失败，将在后续提取时重试: ${error.message?.take(80)}")
                }
            }
            MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本轮总览", "成功", if (l1.isEmpty()) "提取完成，本批没有值得长期保存的信息" else "提取完成，已保存${l1.size}条有效记忆")
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            val retryMessage = scheduleMemoryRetry(sourceId, claimToken, priorFailures = retryCountOf(sourceId), e.message?.take(160) ?: "记忆保存失败")
            MemoryDiagnostics.conversationStep(operationId, "私聊记忆提取", "本轮总览", "失败", "${e::class.simpleName}；保存失败，$retryMessage")
            false
        } finally {
            leaseRenewal.cancel()
        }
    }

    suspend fun ingestGroupChat(groupId: String, groupName: String, messages: List<ChatMessage>, memberIds: List<String>): Boolean {
        if (!settings.memoryV2Enabled || !settings.groupMemoryGenerationEnabled) return false
        if (messages.isEmpty()) return true
        val operationId = MemoryDiagnostics.beginOperation("群聊记忆提取", groupName, groupId)
        MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "提取窗口", "进行中", "消息=${messages.size}，范围=${messages.first().id}-${messages.last().id}，阈值=${settings.groupMemoryExtractionThreshold}")
        val sourceText = messages.joinToString("\n") { formatGroupMessage(groupName, it) }
        // A group has many extraction windows.  Use the window identity rather than the group
        // identity so deleting one source in memory management cannot purge the whole group.
        val sourceRefId = "$groupId:epoch${settings.getMemoryTimelineEpoch(groupId)}:${messages.first().id}-${messages.last().id}"
        val source = MemorySourceItem(
            sourceKind = MemorySourceKind.GROUP_CHAT,
            ownerType = "group",
            ownerId = groupId,
            sourceRefId = sourceRefId,
            contentText = sourceText,
            timestamp = messages.lastOrNull()?.timestamp ?: nowMillis(),
            createdAt = nowMillis()
        )
        val sourceId = repository.insertMemorySource(source)
        if (repository.isMemorySourceFinished(sourceId)) {
            MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本轮总览", "成功", "重复窗口已处理")
            return true
        }
        val claimToken = sourceId.takeIf { it > 0 }?.let { repository.claimMemorySource(it, nowMillis()) }
            ?: run {
                MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本轮总览", "失败", "记忆队列正被其他任务处理")
                return false
            }
        val leaseRenewal = launchLeaseRenewal(sourceId, claimToken)
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { leaseRenewal.cancel() }
        val l1 = try {
            extractGroupL1(groupId, groupName, sourceText, sourceRefId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            MemoryDiagnostics.log("MemoryV2", "群聊L1提取失败，已保留队列等待重试: ${e.message?.take(80)}")
            leaseRenewal.cancel()
            val retryMessage = scheduleMemoryRetry(sourceId, claimToken, priorFailures = retryCountOf(sourceId), e.message?.take(160) ?: "提取失败")
            MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本轮总览", "失败", "${e::class.simpleName}；$retryMessage")
            return false
        }
        if (settings.getMemoryTimelineEpoch(groupId) > source.createdAt) {
            repository.skipMemorySource(sourceId, claimToken, "群聊时间线已失效")
            leaseRenewal.cancel()
            MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本轮总览", "已跳过", "群聊时间线已变化，本批记忆不再保存")
            return false
        }
        return try {
            if (l1.isNotEmpty()) {
                val savedGroupItems = saveMemoryItems(withMessageEvidence(l1, messages))
                MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本地保存", "成功", "模型有效=${l1.size}条，新增=${savedGroupItems.size}条，重复=${l1.size - savedGroupItems.size}条")
                repository.saveMemoryBatch(MemoryBatch(
                    ownerType = "group",
                    ownerId = groupId,
                    sourceKind = MemorySourceKind.GROUP_CHAT,
                    targetLevel = MemoryLevel.L1,
                    inputCount = messages.size,
                    outputCount = l1.size,
                    windowStart = messages.first().timestamp,
                    windowEnd = messages.last().timestamp,
                    promptVersion = "memory_v2_group_l1_v2",
                    createdAt = nowMillis()
                ))
                copyGroupMemoriesToMembers(savedGroupItems, memberIds)
            }
            repository.completeMemorySource(sourceId, claimToken)
            if (settings.groupMemoryPromotionEnabled) {
                runCatching {
                    maybePromoteOwnerMemory("group", groupId, MemorySourceKind.GROUP_CHAT, settings.memoryV2PromoteL1Threshold, settings.memoryV2PromoteL2Threshold)
                }.onFailure { error ->
                    MemoryDiagnostics.log("MemoryV2", "群聊长期沉淀失败，将在后续提取时重试: ${error.message?.take(80)}")
                }
            }
            MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本轮总览", "成功", if (l1.isEmpty()) "提取完成，本批没有值得长期保存的信息" else "提取完成，已保存${l1.size}条有效记忆")
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            val retryMessage = scheduleMemoryRetry(sourceId, claimToken, priorFailures = retryCountOf(sourceId), e.message?.take(160) ?: "记忆保存失败")
            MemoryDiagnostics.conversationStep(operationId, "群聊记忆提取", "本轮总览", "失败", "${e::class.simpleName}；保存失败，$retryMessage")
            false
        } finally {
            leaseRenewal.cancel()
        }
    }

    suspend fun ingestMoment(moment: Moment, contextGroup: String = "") {
        if (!settings.memoryV2Enabled || !settings.momentMemoryGenerationEnabled) return
        val sourceText = formatMoment(moment)
        val sourceId = repository.insertMemorySource(
            MemorySourceItem(
                sourceKind = MemorySourceKind.MOMENT,
                ownerType = "operator",
                ownerId = moment.operatorId,
                sourceRefId = moment.id.toString(),
                contentText = sourceText,
                timestamp = moment.createdAt,
                createdAt = nowMillis()
            )
        )
        val l1 = listOf(publicEventItem(MemorySourceKind.MOMENT, moment.operatorId, moment.id.toString(), sourceText, moment.createdAt))
        if (l1.isNotEmpty()) {
            saveMemoryItems(l1)
            // Public posts are a shared information source.  The author keeps their own
            // record while all characters can discover the public copy when it is relevant.
            saveMemoryItems(l1.map { it.copy(ownerType = "global", ownerId = "public", sourceTarget = "public", vectorId = "") })
            repository.saveMemoryBatch(MemoryBatch(
                ownerType = "operator",
                ownerId = moment.operatorId,
                sourceKind = MemorySourceKind.MOMENT,
                targetLevel = MemoryLevel.L1,
                inputCount = 1,
                outputCount = l1.size,
                windowStart = moment.createdAt,
                windowEnd = moment.createdAt,
                promptVersion = "memory_v2_moment_l1_v2",
                createdAt = nowMillis()
            ))
        }
        if (sourceId > 0) repository.markMemorySourceProcessedL1(sourceId)
        if (settings.momentMemoryPromotionEnabled) {
            maybePromoteOwnerMemory("operator", moment.operatorId, MemorySourceKind.MOMENT, settings.memoryV2PromoteL1Threshold, settings.memoryV2PromoteL2Threshold)
        }
    }

    suspend fun ingestMomentComment(comment: MomentComment, momentId: Long) {
        if (!settings.memoryV2Enabled || !settings.momentCommentMemoryGenerationEnabled) return
        val sourceText = formatMomentComment(comment, momentId)
        val sourceId = repository.insertMemorySource(
            MemorySourceItem(
                sourceKind = MemorySourceKind.MOMENT_COMMENT,
                ownerType = "operator",
                ownerId = comment.operatorId,
                sourceRefId = comment.id.toString(),
                contentText = sourceText,
                timestamp = comment.createdAt,
                createdAt = nowMillis()
            )
        )
        val l1 = listOf(publicEventItem(MemorySourceKind.MOMENT_COMMENT, comment.operatorId, comment.id.toString(), sourceText, comment.createdAt))
        if (l1.isNotEmpty()) {
            // Ordinary public chatter is useful as shared short-lived context, but should not
            // also flood each author's personal vector history.
            if (parseRules.isHighValueComment(comment)) saveMemoryItems(l1)
            saveMemoryItems(l1.map { it.copy(ownerType = "global", ownerId = "public", sourceTarget = "public", vectorId = "") })
            repository.saveMemoryBatch(MemoryBatch(
                ownerType = "operator",
                ownerId = comment.operatorId,
                sourceKind = MemorySourceKind.MOMENT_COMMENT,
                targetLevel = MemoryLevel.L1,
                inputCount = 1,
                outputCount = l1.size,
                windowStart = comment.createdAt,
                windowEnd = comment.createdAt,
                promptVersion = "memory_v2_comment_l1_v2",
                createdAt = nowMillis()
            ))
        }
        if (sourceId > 0) repository.markMemorySourceProcessedL1(sourceId)
        if (settings.momentCommentMemoryPromotionEnabled) {
            maybePromoteOwnerMemory("operator", comment.operatorId, MemorySourceKind.MOMENT_COMMENT, settings.memoryV2PromoteL1Threshold, settings.memoryV2PromoteL2Threshold)
        }
    }

    suspend fun ingestDiary(operatorId: String, operatorName: String, diaryId: String, content: String) {
        if (!settings.memoryV2Enabled || !settings.diaryMemoryGenerationEnabled || content.isBlank()) return
        val now = nowMillis()
        val originalDiary = diaryId.removePrefix("diary_").toLongOrNull()?.let { repository.diaries.getDiaryById(it) }
        val sourceId = repository.insertMemorySource(MemorySourceItem(
            sourceKind = MemorySourceKind.DIARY, ownerType = "operator", ownerId = operatorId,
            sourceRefId = diaryId, contentText = content, timestamp = originalDiary?.createdAt ?: now, createdAt = originalDiary?.createdAt ?: now))
        if (repository.isMemorySourceFinished(sourceId)) return
        val token = repository.claimMemorySource(sourceId, now) ?: return
        try {
            saveDiaryMemory(operatorId, operatorName, diaryId, content, now)
            repository.completeMemorySource(sourceId, token)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            scheduleMemoryRetry(sourceId, token, retryCountOf(sourceId), error::class.simpleName.orEmpty())
        }
    }

    private suspend fun saveDiaryMemory(operatorId: String, operatorName: String, diaryId: String, content: String, now: Long) {
        saveMemoryItems(listOf(MemoryItem(
            ownerType = "operator", ownerId = operatorId, memoryLevel = MemoryLevel.L1,
            memoryType = "event", sourceKind = MemorySourceKind.DIARY, sourceRefId = diaryId,
            content = "$operatorName 的日记：${content.take(180)}", importance = 45, privacy = "private",
            sourceActor = operatorName, sourceTarget = operatorId, topicKey = parseRules.topicKey("event", content),
            createdAt = now, updatedAt = now, expiresAt = now + 30L * 86_400_000L)))
        if (settings.diaryMemoryPromotionEnabled) {
            maybePromoteOwnerMemory("operator", operatorId, MemorySourceKind.DIARY,
                settings.memoryV2PromoteL1Threshold, settings.memoryV2PromoteL2Threshold)
        }
    }

    suspend fun maybePromotePrivateMemory(operatorId: String, thresholdL1: Int, thresholdL2: Int) {
        maybePromoteOwnerMemory("operator", operatorId, MemorySourceKind.PRIVATE_CHAT, thresholdL1, thresholdL2)
    }

    private suspend fun maybePromoteOwnerMemory(ownerType: String, ownerId: String, sourceKind: MemorySourceKind, thresholdL1: Int, thresholdL2: Int) {
        val lock = MemoryPromotionLocks.lockFor("$ownerType:$ownerId:${sourceKind.name}")
        lock.lock()
        try { maybePromoteOwnerMemoryLocked(ownerType, ownerId, sourceKind, thresholdL1, thresholdL2) }
        finally { lock.unlock() }
    }

    private suspend fun maybePromoteOwnerMemoryLocked(ownerType: String, ownerId: String, sourceKind: MemorySourceKind, thresholdL1: Int, thresholdL2: Int) {
        val now = nowMillis()
        // Promotion must preserve the source boundary. In particular, group knowledge must not
        // become a character's private-chat impression just because it has a copied owner.
        val l1Items = repository.getActiveMemoryItemsByLevel(ownerType, ownerId, MemoryLevel.L1, now)
            .filter { it.sourceKind == sourceKind }
        val stateKey = consolidationStateKey(ownerType, ownerId, sourceKind)
        val state = MemoryConsolidationPolicy.decode(settings.getString(stateKey, ""))
        val signature = MemoryConsolidationPolicy.signature(
            l1Items.map { Triple(it.id, it.updatedAt, it.importance) },
            CONSOLIDATION_PROMPT_VERSION,
        )
        if (MemoryConsolidationPolicy.shouldSkip(state, signature, now)) {
            MemoryDiagnostics.log(
                "Memory/Consolidation",
                "跳过整合：候选未变或退避中 owner=$ownerId source=$sourceKind status=${state.status}",
            )
            return
        }
        val day = beijingDate(now)
        var spentToday = false
        // 只有真的要调用模型时才消耗预算；没有达标 topic 的空跑不占额度。
        fun ensureBudget(): Boolean {
            if (spentToday) return true
            if (!MemoryConsolidationPolicy.canSpend(settings, ownerId, day)) {
                MemoryDiagnostics.log("Memory/Consolidation", "跳过整合：日预算用尽 owner=$ownerId day=$day")
                return false
            }
            MemoryConsolidationPolicy.recordSpend(settings, ownerId, day)
            spentToday = true
            return true
        }
        try {
        val l1Topic = parseRules.pickPromotionTopic(l1Items, thresholdL1)
        if (l1Topic != null && ensureBudget()) {
            val selected = l1Topic.items
            val messages = buildLevelMessages(MemoryV2PromptTemplates.L2, selected)
            val raw = withTimeout(50_000) { requestMemoryModel(messages, "MemoryL2") }
            val l2Items = parseRules.parsePromotionItems(raw, ownerType, ownerId, MemoryLevel.L2, sourceKind, l1Topic.topicKey, selected)
            if (l2Items.isNotEmpty()) {
                val saved = saveMemoryItems(l2Items)
                if (saved.isNotEmpty()) linkAndArchiveParents(saved, selected, "merge_l1_to_l2")
                repository.saveMemoryBatch(MemoryBatch(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    sourceKind = sourceKind,
                    targetLevel = MemoryLevel.L2,
                    inputCount = selected.size,
                    outputCount = saved.size,
                    windowStart = selected.minOfOrNull { it.createdAt } ?: nowMillis(),
                    windowEnd = selected.maxOfOrNull { it.createdAt } ?: nowMillis(),
                    promptVersion = "memory_v2_l2_v3",
                    createdAt = nowMillis()
                ))
            }
        }

        val l2Items = repository.getActiveMemoryItemsByLevel(ownerType, ownerId, MemoryLevel.L2, now)
            .filter { it.sourceKind == sourceKind }
        val l2Topic = parseRules.pickPromotionTopic(l2Items, thresholdL2, requireStable = true)
        if (l2Topic != null && ensureBudget()) {
            val selected = l2Topic.items
            val messages = buildLevelMessages(MemoryV2PromptTemplates.L3, selected)
            val raw = withTimeout(50_000) { requestMemoryModel(messages, "MemoryL3") }
            val l3Items = parseRules.parsePromotionItems(raw, ownerType, ownerId, MemoryLevel.L3, sourceKind, l2Topic.topicKey, selected)
            if (l3Items.isNotEmpty()) {
                val saved = saveMemoryItems(l3Items)
                if (saved.isNotEmpty()) linkAndArchiveParents(saved, selected, "merge_l2_to_l3")
                repository.saveMemoryBatch(MemoryBatch(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    sourceKind = sourceKind,
                    targetLevel = MemoryLevel.L3,
                    inputCount = selected.size,
                    outputCount = saved.size,
                    windowStart = selected.minOfOrNull { it.createdAt } ?: nowMillis(),
                    windowEnd = selected.maxOfOrNull { it.createdAt } ?: nowMillis(),
                    promptVersion = "memory_v2_l3_v3",
                    createdAt = nowMillis()
                ))
            }
        }
        settings.putString(stateKey, MemoryConsolidationPolicy.encode(MemoryConsolidationPolicy.onSuccess(signature, nowMillis())))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val nextState = MemoryConsolidationPolicy.onFailure(
                previous = state,
                signature = signature,
                now = nowMillis(),
                error = e.message ?: e::class.simpleName.orEmpty(),
            )
            settings.putString(stateKey, MemoryConsolidationPolicy.encode(nextState))
            if (!MemoryConsolidationPolicy.isDeadLetter(state) && MemoryConsolidationPolicy.isDeadLetter(nextState)) {
                settings.memoryConsolidationDeadLetterCount += 1
                settings.memoryConsolidationLastError = nextState.lastError
                MemoryDiagnostics.log(
                    "Memory/Consolidation",
                    "进入 dead-letter owner=$ownerId source=$sourceKind error=${nextState.lastError}",
                )
            }
            throw e
        }
    }

    private fun consolidationStateKey(ownerType: String, ownerId: String, sourceKind: MemorySourceKind): String =
        "memory_consolidation_state_${ownerType}_${ownerId}_${sourceKind.name}"

    private val recallEngine by lazy {
        MemoryRecallEngine(
            settings = settings,
            memoryVectorService = memoryVectorService,
            lexicalRecall = { operatorId, query -> buildLexicalRecallCandidates(operatorId, query) },
            factKeyResolver = { memoryIds ->
                repository.memoryV2.getActiveMemoryItemsByIds(memoryIds, nowMillis())
                    .associate { it.id to it.topicKey }
            },
            // T73：本轮最终注入的记忆累加 injectionCount（失败不影响召回，见 engine.notifySelected）。
            onSelected = { ids -> repository.memoryV2.bumpInjectionUsage(ids, nowMillis()) },
            nowMillis = { nowMillis() },
        )
    }

    /**
     * 词法通道候选：BM25 命中 → 回表 active 记忆 → 过滤来源开关 → 转成统一候选结构。
     * 只提供候选与分数，是否准入由 [MemoryRecallEngine] 的两阶段规则决定。
     */
    private suspend fun buildLexicalRecallCandidates(operatorId: String, query: String): List<VectorMemory> {
        if (!settings.memoryV2Enabled || query.isBlank()) return emptyList()
        val now = nowMillis()
        val hits = repository.memoryV2.searchLexical("operator", operatorId, query, limit = 120, now = now)
        if (hits.isEmpty()) return emptyList()
        val allowed = listOf(
            MemorySourceKind.PRIVATE_CHAT,
            MemorySourceKind.GROUP_CHAT,
            MemorySourceKind.MOMENT,
            MemorySourceKind.MOMENT_COMMENT,
            MemorySourceKind.DIARY,
            MemorySourceKind.MANUAL_MEMORY,
        ).filter { settings.isMemoryInjectionAllowed("private_chat", it.name) }
            .mapTo(mutableSetOf()) { it.name }
        if (allowed.isEmpty()) return emptyList()
        val items = repository.memoryV2.getActiveMemoryItemsByIds(hits.map { it.memoryId }, now)
        val byId = items.associateBy { it.id }
        val lexical = hits.mapNotNull { hit ->
            val item = byId[hit.memoryId] ?: return@mapNotNull null
            if (item.sourceKind.name !in allowed) return@mapNotNull null
            VectorMemory(
                id = MemoryVectorFormatter.vectorId(item),
                ownerType = item.ownerType,
                ownerId = item.ownerId,
                sourceType = MemoryVectorFormatter.sourceType(item),
                sourceId = item.sourceRefId.ifBlank { item.sessionId },
                content = MemoryVectorFormatter.content(item),
                importance = item.importance.coerceIn(0, 100) / 100.0,
                tags = MemoryVectorFormatter.tags(item),
                visibility = item.privacy ?: parseRules.defaultPrivacy(item.sourceKind),
                createdAt = item.createdAt,
                expiresAt = item.expiresAt,
                originMemoryId = item.id,
                originContent = item.content,
                lexicalScore = hit.score,
                lexicalMatchCount = hit.matchedTermCount,
                lexicalMaxTermIdf = hit.maxTermIdf,
            )
        }
        return mergeEntityRecallCandidates(lexical, operatorId, query, allowed, now)
    }

    /**
     * 实体通道（T72）：查询里出现的实体名（归一后长度≥2）直接换回挂在该实体上的记忆。
     *
     * 只补词法命中不到的语义类查询（「我养了什么猫」→ 实体「猫」），因此给固定分
     * [ENTITY_RECALL_SCORE]：足以过 FACT/TIME 的准入，但不冒充高相关；
     * 实体名天然具备区分度，故 [ENTITY_RECALL_IDF] 高于判别门槛。
     * 已由词法命中的记忆不重复加入。
     */
    private suspend fun mergeEntityRecallCandidates(
        lexical: List<VectorMemory>,
        operatorId: String,
        query: String,
        allowed: Set<String>,
        now: Long,
    ): List<VectorMemory> {
        if (query.isBlank()) return lexical
        val matchedNames = runCatching {
            repository.memoryV2.listMemoryEntities("operator", operatorId, ENTITY_CATALOG_LIMIT)
                .filter { it.normalizedName.length >= 2 && query.contains(it.normalizedName) }
                .map { it.normalizedName }
                .distinct()
        }.getOrDefault(emptyList())
        if (matchedNames.isEmpty()) return lexical
        val existing = lexical.mapTo(mutableSetOf()) { it.originMemoryId }
        val ids = runCatching {
            repository.memoryV2.findMemoryIdsByEntityNames("operator", operatorId, matchedNames, ENTITY_HIT_LIMIT)
        }.getOrDefault(emptyList()).filter { it !in existing }
        if (ids.isEmpty()) return lexical
        val items = repository.memoryV2.getActiveMemoryItemsByIds(ids, now)
        val entityHits = items.filter { it.sourceKind.name in allowed }.map { item ->
            VectorMemory(
                id = MemoryVectorFormatter.vectorId(item),
                ownerType = item.ownerType,
                ownerId = item.ownerId,
                sourceType = MemoryVectorFormatter.sourceType(item),
                sourceId = item.sourceRefId.ifBlank { item.sessionId },
                content = MemoryVectorFormatter.content(item),
                importance = item.importance.coerceIn(0, 100) / 100.0,
                tags = MemoryVectorFormatter.tags(item),
                visibility = item.privacy ?: parseRules.defaultPrivacy(item.sourceKind),
                createdAt = item.createdAt,
                expiresAt = item.expiresAt,
                originMemoryId = item.id,
                originContent = item.content,
                lexicalScore = ENTITY_RECALL_SCORE,
                lexicalMatchCount = 2,
                lexicalMaxTermIdf = ENTITY_RECALL_IDF,
            )
        }
        if (entityHits.isNotEmpty()) {
            MemoryDiagnostics.log(
                "Memory/Recall/实体",
                "实体通道命中：实体=${matchedNames.size} 记忆=${entityHits.size}",
            )
        }
        return lexical + entityHits
    }

    /** T81 回填入口：老库升级后把 L2/L3 聚合成印象（可重入）。 */
    suspend fun rebuildImpressionsForOwner(ownerType: String, ownerId: String): Int =
        repository.memoryV2.rebuildImpressionsForOwner(ownerType, ownerId, nowMillis())

    suspend fun buildPrivateMemoryContext(
        operatorId: String,
        limitL1: Int,
        limitL2: Int,
        limitL3: Int,
        query: String = "",
        applyPrivateSourceFilter: Boolean = false,
        allowedSources: Set<String>? = null,
        visibilities: List<String> = emptyList(),
    ): String = recallEngine.buildPrivateMemoryContext(
        operatorId, limitL1, limitL2, limitL3, query, applyPrivateSourceFilter, allowedSources, visibilities,
    )

    /**
     * A relationship is a runtime knowledge channel, not a copied memory.  A -> B lets B
     * recall a small, relevant subset of A's private-chat memories for the current response.
     * 候选收集（repository/向量服务）留在管线；纯排序/去重/包裹在 [MemoryRecallEngine.buildRelationshipContextText]。
     */
    suspend fun buildRelationshipPrivateMemoryContext(operatorId: String, query: String): String {
        if (!settings.memoryV2Enabled) return ""
        val vectorService = memoryVectorService ?: return ""
        if (query.isBlank()) return ""
        val now = nowMillis()
        val relations = repository.getReverseRelationships(operatorId)
        if (relations.isEmpty()) return ""
        val candidates = mutableListOf<VectorMemory>()
        relations.forEach { relation ->
            val policy = recallEngine.relationshipRecallPolicy(relation.intimacy) ?: return@forEach
            val recalled = runCatching {
                vectorService.search(
                    VectorSearchRequest(
                        ownerType = "operator",
                        ownerId = relation.operatorId,
                        query = query,
                        limit = policy.candidateLimit,
                        sourceTypes = listOf("memory_v2_l1", "memory_v2_l2", "memory_v2_l3"),
                        sourceKinds = listOf(MemorySourceKind.PRIVATE_CHAT.name),
                        minScore = 0.16,
                        now = now,
                        candidateLimit = 50,
                        minImportance = policy.minImportance / 100.0,
                    )
                )
            }.getOrDefault(emptyList())
            candidates += recalled
                .asSequence()
                .filter { memory -> memory.tags.split(',').any { it == MemorySourceKind.PRIVATE_CHAT.name } }
                .take(2)
                .toList()
        }
        return recallEngine.buildRelationshipContextText(candidates)
    }

    suspend fun buildPrivateStableImpression(operatorId: String, limit: Int = 3): String {
        if (!settings.memoryV2Enabled) return ""
        val items = repository.getActiveMemoryItemsByLevel("operator", operatorId, MemoryLevel.L3, nowMillis())
            .filter { it.sourceKind == MemorySourceKind.PRIVATE_CHAT }
            .take(limit)
        return recallEngine.buildStableImpressionText(items)
    }

    /** Private chat prioritizes recent facts without letting long-term memories fill the candidate pool. */
    suspend fun buildPrivateChatMemoryContext(
        operatorId: String,
        query: String,
        allowedSources: Set<String> = recallEngine.privateChatAllowedSources(),
        allowPrivateVisualRecall: Boolean = settings.privateRecallPrivateChatMemory,
    ): String = recallEngine.buildPrivateChatMemoryContext(operatorId, query, allowedSources, allowPrivateVisualRecall)

    fun privateChatAllowedSources(): Set<String> = recallEngine.privateChatAllowedSources()

    suspend fun buildPublicMemoryContext(
        query: String,
        limit: Int = 3,
        allowedSources: Set<String> = recallEngine.publicMemoryAllowedSources(),
    ): String = recallEngine.buildPublicMemoryContext(query, limit, allowedSources)

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
    ): String = recallEngine.buildOwnerMemoryContext(
        ownerType, ownerId, limitL1, limitL2, limitL3, query, allowedSources, minCreatedAt, visibilities,
    )

    /** Stores image recognition as a normal L1 item so it follows source permissions and cleanup. */
    suspend fun ingestVision(
        ownerType: String,
        ownerId: String,
        sourceKind: MemorySourceKind,
        sourceRefId: String,
        content: String,
        isPrivate: Boolean,
    ) {
        if (!settings.memoryV2Enabled || content.isBlank()) return
        val now = nowMillis()
        val item = MemoryItem(
            ownerType = ownerType, ownerId = ownerId, memoryLevel = MemoryLevel.L1,
            memoryType = "event", sourceKind = sourceKind, sourceRefId = sourceRefId,
            sessionId = sourceRefId.substringBefore(':').takeIf { it.isNotBlank() }.orEmpty(),
            content = content.take(500), importance = 55,
            privacy = if (isPrivate) "private" else "public",
            topicKey = parseRules.topicKey("vision", content), sourceTarget = ownerId,
            createdAt = now, updatedAt = now, expiresAt = now + 30L * 86_400_000L,
        )
        saveMemoryItems(listOf(item))
    }

    internal fun formatPrivateMessage(msg: ChatMessage): String {
        val time = if (msg.timestamp > 0) {
            beijingTimestamp(msg.timestamp)
        } else "unknown"
        val speaker = if (msg.isMe) "用户" else msg.senderName.ifBlank { "AI" }
        val content = if (msg.type == "ai_json") runCatching {
            val obj = json.parseToJsonElement(msg.content).jsonObject
            val segments = obj["segments"]?.jsonArray ?: return@runCatching ""
            segments.mapNotNull { element ->
                val segment = element.jsonObject
                if (segment["recalled"]?.jsonPrimitive?.contentOrNull.equals("true", true)) null
                else segment["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }.joinToString(" ")
        }.getOrDefault("") else msg.content
        return content.takeIf { it.isNotBlank() }?.let { "[$time] $speaker：${it.take(240)}" }.orEmpty()
    }

    private suspend fun withSourceEvidence(items: List<MemoryItem>, sourceRefId: String): List<MemoryItem> {
        if (!sourceRefId.contains(":epoch")) return items // Legacy provenance is unknown, not fabricated.
        val range = sourceRefId.substringAfterLast(':').split('-')
        val first = range.getOrNull(0)?.toLongOrNull() ?: return items
        val last = range.getOrNull(1)?.toLongOrNull() ?: return items
        val sessionId = sourceRefId.substringBefore(":epoch")
        val messages = repository.getMessagesAfterCursor(sessionId, first - 1, 0L, (last - first + 1).coerceAtMost(10000L))
            .filter { it.id <= last }
        return if (messages.isEmpty()) items else withMessageEvidence(items, messages)
    }

    internal fun withMessageEvidence(items: List<MemoryItem>, messages: List<ChatMessage>): List<MemoryItem> = items.map { item ->
        val existing = runCatching { json.parseToJsonElement(item.rawJson) as? JsonObject }.getOrNull().orEmpty()
        item.copy(rawJson = JsonObject(existing + ("sourceMessageIds" to kotlinx.serialization.json.JsonArray(messages.map { kotlinx.serialization.json.JsonPrimitive(it.id) })) ).toString())
    }

    private suspend fun extractL1(
        sourceKind: MemorySourceKind,
        text: String,
        ownerType: String,
        ownerId: String,
        sourceRefId: String,
        sessionId: String,
        operatorName: String
    ): List<MemoryItem> {
        val messages = listOf(
            AiMessage("system", MemoryV2PromptTemplates.getL1(sourceKind.name)),
            AiMessage("user", "【提取资料】\n系统提供的当前昵称：${userNicknameProvider()}\n干员：$operatorName\n内容：\n$text")
        )
        val raw = withTimeout(50_000) { requestMemoryModel(messages, "MemoryL1_${sourceKind.name}") }
        return parseRules.parseExtractedL1(raw, ownerType, ownerId, sourceKind, sourceRefId, sessionId)
    }

    private suspend fun extractGroupL1(groupId: String, groupName: String, text: String, sourceRefId: String): List<MemoryItem> {
        val messages = listOf(
            AiMessage("system", MemoryV2PromptTemplates.getL1("GROUP_CHAT")),
            AiMessage("user", "【提取资料】\n系统提供的当前昵称：${userNicknameProvider()}\n群聊：$groupName\n内容：\n$text")
        )
        val raw = withTimeout(50_000) { requestMemoryModel(messages, "MemoryL1_GROUP_CHAT") }
        return parseRules.parseExtractedL1(raw, "group", groupId, MemorySourceKind.GROUP_CHAT, sourceRefId, groupId)
    }

    internal fun formatGroupMessage(groupName: String, msg: ChatMessage): String {
        val time = if (msg.timestamp > 0) {
            beijingTimestamp(msg.timestamp)
        } else "unknown"
        if (msg.isMe) return "[$time] 群聊「$groupName」中，用户：${msg.content.take(240)}"
        if (msg.type == "ai_json") {
            val items = runCatching {
                val root = json.parseToJsonElement(msg.content)
                val arr = root as? kotlinx.serialization.json.JsonArray
                    ?: (root as? JsonObject)?.get("messages")?.jsonArray
                    ?: (root as? JsonObject)?.get("segments")?.jsonArray
                    ?: kotlinx.serialization.json.JsonArray(emptyList())
                arr.mapNotNull { element ->
                    val obj = element.jsonObject
                    if (obj["recalled"]?.jsonPrimitive?.contentOrNull.equals("true", true)) return@mapNotNull null
                    val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull
                        ?: obj["sender"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val content = obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: obj["content"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    if (content.isBlank()) null else "${if (speaker == "旁白" || obj["type"]?.jsonPrimitive?.contentOrNull.equals("narration", true)) "旁白" else speaker}：${content.trim()}"
                }
            }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return "[$time] 群聊「$groupName」中，${items.joinToString("\n").take(800)}"
        }
        if (msg.type == "ai_json") return ""
        return "[$time] 群聊「$groupName」中，${msg.senderName.ifBlank { "成员" }}：${msg.content.take(240)}"
    }

    internal fun formatMoment(moment: Moment): String {
        val time = if (moment.createdAt > 0) {
            beijingTimestamp(moment.createdAt)
        } else "unknown"
        return "[$time] ${moment.operatorName}发布动态：${moment.content.take(240)}"
    }

    internal fun formatMomentComment(comment: MomentComment, momentId: Long): String {
        val time = if (comment.createdAt > 0) {
            beijingTimestamp(comment.createdAt)
        } else "unknown"
        return "[$time] ${comment.operatorName}评论动态#$momentId：${comment.content.take(240)}"
    }

    internal fun buildLevelMessages(base: String, items: List<MemoryItem>): List<AiMessage> = listOf(
        AiMessage("system", base),
        AiMessage("user", "【待合并记忆 JSON】\n${json.encodeToString(ListSerializer(MemoryItem.serializer()), items)}")
    )

    /** Replays unprocessed private/group source windows after transient model or network failures. */
    suspend fun retryPendingSources(limit: Int = 20): Int {
        if (!settings.memoryV2Enabled) return 0
        var succeeded = 0
        val now = nowMillis()
        repository.claimPendingMemorySources(now, limit).forEach { (source, token) -> coroutineScope {
            val leaseRenewal = launch {
                while (true) {
                    delay(60_000L)
                    repository.renewMemorySourceLease(source.id, token, nowMillis())
                }
            }
            try {
            val generationDisabled = when (source.sourceKind) {
                MemorySourceKind.PRIVATE_CHAT -> !settings.privateMemoryGenerationEnabled
                MemorySourceKind.GROUP_CHAT -> !settings.groupMemoryGenerationEnabled
                MemorySourceKind.DIARY -> !settings.diaryMemoryGenerationEnabled
                else -> false
            }
            if (generationDisabled) {
                // Keep the durable window for a later re-enable, but never make a model request
                // while the user has disabled generation for this source.
                repository.releaseMemorySource(source.id, token, nowMillis() + 60_000L, "该来源的记忆生成已关闭")
                MemoryDiagnostics.log("MemoryV2", "记忆队列暂停 id=${source.id}: 该来源的记忆生成已关闭")
                return@coroutineScope
            }
            val timelineId = when (source.sourceKind) {
                MemorySourceKind.PRIVATE_CHAT -> source.sourceRefId.substringBefore(":epoch")
                MemorySourceKind.GROUP_CHAT -> source.ownerId
                else -> ""
            }
            if (timelineId.isNotBlank() && settings.getMemoryTimelineEpoch(timelineId) > source.createdAt) {
                repository.skipMemorySource(source.id, token, "来源时间线已失效")
                return@coroutineScope
            }
            var retryScheduled = false
            val completed = runCatching {
                when (source.sourceKind) {
                    MemorySourceKind.PRIVATE_CHAT -> {
                        val operator = repository.getOperator(source.ownerId) ?: return@runCatching false
                        val sessionId = source.sourceRefId.substringBefore(":epoch")
                        val l1 = extractL1(source.sourceKind, source.contentText, source.ownerType, source.ownerId, source.sourceRefId, sessionId, operator.name)
                        if (settings.getMemoryTimelineEpoch(sessionId) > source.createdAt) return@runCatching false
                        if (l1.isNotEmpty()) saveMemoryItems(withSourceEvidence(l1, source.sourceRefId))
                        if (settings.privateMemoryPromotionEnabled) maybePromotePrivateMemory(source.ownerId, settings.memoryV2PromoteL1Threshold, settings.memoryV2PromoteL2Threshold)
                        true
                    }
                    MemorySourceKind.GROUP_CHAT -> {
                        val group = repository.getSession(source.ownerId) ?: return@runCatching false
                        val l1 = extractGroupL1(source.ownerId, group.operatorName, source.contentText, source.sourceRefId)
                        if (settings.getMemoryTimelineEpoch(source.ownerId) > source.createdAt) return@runCatching false
                        if (l1.isNotEmpty()) {
                            val saved = saveMemoryItems(withSourceEvidence(l1, source.sourceRefId))
                            val memberIds = group.members.split(',').map { it.trim() }.filter { it.isNotBlank() }
                            copyGroupMemoriesToMembers(saved, memberIds)
                        }
                        if (settings.groupMemoryPromotionEnabled) maybePromoteOwnerMemory("group", source.ownerId, source.sourceKind, settings.memoryV2PromoteL1Threshold, settings.memoryV2PromoteL2Threshold)
                        true
                    }
                    MemorySourceKind.DIARY -> {
                        val op = repository.getOperator(source.ownerId) ?: return@runCatching false
                        val diary = source.sourceRefId.removePrefix("diary_").toLongOrNull()?.let { repository.diaries.getDiaryById(it) }
                            ?: return@runCatching false
                        if (diary.operatorId != source.ownerId) return@runCatching false
                        saveDiaryMemory(op.id, op.name, source.sourceRefId, source.contentText, source.createdAt)
                        true
                    }
                    else -> false
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                val reason = error.message?.take(160) ?: "未知错误"
                retryScheduled = true
                val retryMessage = scheduleMemoryRetry(source.id, token, source.retryCount, reason)
                MemoryDiagnostics.log("MemoryV2", "记忆队列重试失败 id=${source.id}: $reason；$retryMessage")
                false
            }
            if (completed) {
                repository.completeMemorySource(source.id, token)
                succeeded++
            } else if (!retryScheduled) {
                repository.skipMemorySource(source.id, token, "来源已删除或类型不支持")
            }
            } finally {
                leaseRenewal.cancel()
            }
        } }
        return succeeded
    }

    private suspend fun copyGroupMemoriesToMembers(items: List<MemoryItem>, memberIds: List<String>) {
        if (!settings.groupMemoryCopyToMembersEnabled) return
        val longTermShared = items.filter {
            it.importance >= 70 && it.memoryType in setOf(
                "agreement_commitment", "care_reminder", "preference_expression",
                "evaluation_opinion", "self_cognition_statement"
            )
        }
        memberIds.distinct().filter { it.isNotBlank() }.forEach { memberId ->
            saveMemoryItems(longTermShared.map { item ->
                item.copy(
                    id = 0, ownerType = "operator", ownerId = memberId,
                    sourceTarget = memberId, vectorId = "",
                    createdAt = nowMillis(), updatedAt = nowMillis(),
                )
            })
        }
    }

    private fun launchLeaseRenewal(sourceId: Long, token: String): kotlinx.coroutines.Job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        while (true) {
            delay(60_000L)
            repository.renewMemorySourceLease(sourceId, token, nowMillis())
        }
    }

    private suspend fun saveMemoryItems(items: List<MemoryItem>): List<MemoryItem> {
        val saved = mutableListOf<MemoryItem>()
        items.forEach { item ->
            val id = try {
                repository.insertMemoryItemIfAbsent(item, nowMillis()) ?: return@forEach
            } catch (e: Exception) {
                MemoryDiagnostics.log("Memory/SaveError", "记忆保存失败：${e::class.simpleName}，原因=${e.message?.take(160) ?: "未知原因"}")
                throw e
            }
            if (id > 0) {
                val withId = item.copy(id = id)
                saveMemoryFact(withId, nowMillis())
                saveMemoryItemToVector(id, withId)
                saved += withId
            }
        }
        MemoryDiagnostics.log("Memory/Save", "记忆保存完成：新增=${saved.size}条，输入=${items.size}条")
        if (saved.isNotEmpty()) {
            recordStructuredRelations(saved)
            if (saved.any { it.memoryLevel == MemoryLevel.L2 || it.memoryLevel == MemoryLevel.L3 }) {
                runCatching { repository.memoryV2.upsertImpressions(saved, nowMillis()) }
                    .onFailure { e ->
                        MemoryDiagnostics.log(
                            "Memory/ImpressionError",
                            "印象聚合失败：${e.message?.take(120) ?: "未知"}",
                        )
                    }
            }
        }
        return saved
    }

    /**
     * 结构化事实独立表（T69）：只有 L1 且 rawJson 里能解析出 subject/predicate/object 才写行。
     * 事实是可选的派生数据：解析失败或写入失败只记诊断，记忆本身已经保存成功。
     */
    private suspend fun saveMemoryFact(item: MemoryItem, now: Long) {
        if (item.memoryLevel != MemoryLevel.L1 || item.rawJson.isBlank()) return
        val fact = parseRules.structuredFactOf(item.rawJson) ?: return
        try {
            repository.memoryV2.upsertMemoryFact(item, fact, now)
            runCatching { repository.memoryV2.upsertMemoryEntities(item, fact, now) }
                .onFailure { e ->
                    MemoryDiagnostics.log(
                        "Memory/EntityError",
                        "实体索引写入失败：memoryId=${item.id}，原因=${e.message?.take(160) ?: "未知"}",
                    )
                }
        } catch (e: Exception) {
            MemoryDiagnostics.log(
                "Memory/FactError",
                "结构化事实写入失败：memoryId=${item.id}，原因=${e.message?.take(160) ?: e::class.simpleName ?: "未知原因"}",
            )
        }
    }

    /**
     * 结构化 L1 落库后记录事实关系（T78 数据基础）：
     * 同基座同极性 → supports；同基座相反极性 → contradicts；其余留给 T78 完整版的模型/用户裁决。
     * contradicts 同时把新旧事实行标为 suspected（待确认），只写标记不关闭/合并任何记忆。
     */
    private suspend fun recordStructuredRelations(saved: List<MemoryItem>) {
        var conflictRelations = 0
        val conflictIds = linkedSetOf<Long>()
        saved.filter { it.memoryLevel == MemoryLevel.L1 && it.topicKey.startsWith(MemoryRelationRules.FACT_PREFIX) }
            .forEach { item ->
                val opposite = MemoryRelationRules.oppositeKey(item.topicKey) ?: return@forEach
                val refs = runCatching {
                    repository.memoryV2.getActiveMemoryTopicRefs(
                        ownerType = item.ownerType,
                        ownerId = item.ownerId,
                        topicKeys = listOf(item.topicKey, opposite),
                        now = nowMillis(),
                        limit = 20,
                    )
                }.getOrDefault(emptyList())
                refs.forEach { (otherId, otherKey) ->
                    if (otherId == item.id) return@forEach
                    val type = MemoryRelationRules.relationType(item.topicKey, otherKey) ?: return@forEach
                    runCatching {
                        repository.memoryV2.insertMemoryRelation(
                            fromMemoryId = item.id,
                            toMemoryId = otherId,
                            relationType = type,
                            confidence = if (type == MemoryRelationRules.SUPPORTS) 0.85 else 0.6,
                            decidedBy = "rule",
                            now = nowMillis(),
                        )
                    }
                    if (type == MemoryRelationRules.CONTRADICTS) {
                        conflictRelations++
                        conflictIds += item.id
                        conflictIds += otherId
                    }
                }
            }
        if (conflictIds.isNotEmpty()) {
            val marked = runCatching {
                repository.memoryV2.markMemoryFactConflict(conflictIds.toList(), nowMillis())
            }.getOrDefault(0)
            MemoryDiagnostics.log(
                "Memory/FactConflict",
                "事实冲突标记：contradicts=$conflictRelations 候选=${conflictIds.size} 实际标记=$marked",
            )
        }
    }

    private suspend fun linkAndArchiveParents(children: List<MemoryItem>, parents: List<MemoryItem>, linkType: String) {
        val now = nowMillis()
        val parentIds = parents.filter { it.id > 0 }.map { it.id }.toSet()
        val links = children.filter { it.id > 0 }.flatMap { child ->
            parseRules.evidenceIdsFrom(child.rawJson).filter { it in parentIds }.distinct().map { parentId ->
                MemoryLink(parentMemoryId = parentId, childMemoryId = child.id, linkType = linkType, createdAt = now)
            }
        }
        repository.memoryV2.finishPromotion(children, parents, links, memoryVectorService?.currentEmbeddingSignature().orEmpty(), now)
    }

    /** Public social activity is recorded directly.  It does not spend an AI call per post. */
    internal fun publicEventItem(sourceKind: MemorySourceKind, ownerId: String, sourceRefId: String, content: String, eventAt: Long): MemoryItem {
        val now = nowMillis()
        return MemoryItem(
            ownerType = "operator",
            ownerId = ownerId,
            memoryLevel = MemoryLevel.L1,
            memoryType = "event",
            sourceKind = sourceKind,
            sourceRefId = sourceRefId,
            content = content.take(180),
            importance = 35,
            privacy = "public",
            eventTime = eventAt.takeIf { it > 0 }?.let {
                beijingTimestamp(it, withSeconds = false)
            },
            sourceActor = ownerId,
            sourceTarget = ownerId,
            topicKey = parseRules.topicKey("event", content),
            createdAt = now,
            updatedAt = now,
            expiresAt = now + 30L * 86_400_000L,
        )
    }

    // ------------------------------------------------------------------
    // 索引健康与重建（审计 P0-3 / P0-4：恢复备份、换嵌入模型后必须能自助恢复）
    // ------------------------------------------------------------------

    /** 当前索引状态分布（与记忆体检页/启动自检共用同一口径）。 */
    suspend fun indexHealth(): MemoryIndexHealth {
        val signature = memoryVectorService?.currentEmbeddingSignature().orEmpty()
        val states = repository.memoryV2.memoryIndexStates(signature, nowMillis()).values
        return MemoryIndexHealth(
            signature = signature,
            active = states.count { it == "ready" || it == "missing" || it == "stale" },
            ready = states.count { it == "ready" },
            missing = states.count { it == "missing" },
            stale = states.count { it == "stale" },
            expired = states.count { it == "expired" },
            inactive = states.count { it == "inactive" },
        )
    }

    /**
     * 分批重建缺失/签名漂移的向量。
     *
     * - 可取消：每批开始与每条之前检查 [shouldCancel]；
     * - 有限次：最多 [maxBatches] 批，剩余量由返回报告的 `remaining` 体现，下次活跃继续；
     * - 幂等：已尝试失败的条目不再重复计入本批次，避免同一条卡住整批。
     */
    suspend fun repairMemoryIndex(
        batchSize: Int = 200,
        maxBatches: Int = 25,
        shouldCancel: () -> Boolean = { false },
        onProgress: (MemoryIndexRepairReport) -> Unit = {},
    ): MemoryIndexRepairReport {
        val signature = memoryVectorService?.currentEmbeddingSignature().orEmpty()
        if (memoryVectorService == null || signature.isBlank()) {
            return MemoryIndexRepairReport(signature, scanned = 0, repaired = 0, failed = 0, remaining = 0, cancelled = false)
        }
        var scanned = 0
        var repaired = 0
        var failed = 0
        var cancelled = false
        // 游标而非「重试同一页」：单条内容持续无法嵌入时，不能让它永远占据首页。
        var cursorCreatedAt = 0L
        var cursorId = 0L
        var batches = 0
        while (batches < maxBatches) {
            if (shouldCancel()) {
                cancelled = true
                break
            }
            val now = nowMillis()
            val missing = runCatching {
                repository.memoryV2.getActiveMemoryItemsMissingVectorAfter(now, cursorCreatedAt, cursorId, batchSize)
            }.getOrDefault(emptyList())
            val stale = runCatching {
                repository.memoryV2.getActiveMemoryItemsWithStaleVectorAfter(signature, now, cursorCreatedAt, cursorId, batchSize)
            }.getOrDefault(emptyList())
            val batch = (missing + stale).distinctBy { it.id }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
            if (batch.isEmpty()) break
            for (item in batch) {
                if (shouldCancel()) {
                    cancelled = true
                    break
                }
                if (saveMemoryItemToVector(item.id, item)) repaired++ else failed++
                scanned++
                cursorCreatedAt = item.createdAt
                cursorId = item.id
            }
            batches++
            onProgress(MemoryIndexRepairReport(signature, scanned, repaired, failed, remaining = -1, cancelled = cancelled))
            if (cancelled) break
        }
        val remaining = runCatching {
            val now = nowMillis()
            repository.memoryV2.getActiveMemoryItemsMissingVector(now, 1).size +
                repository.memoryV2.getActiveMemoryItemsWithStaleVector(signature, now, 1).size
        }.getOrDefault(0)
        val report = MemoryIndexRepairReport(signature, scanned, repaired, failed, remaining = if (remaining > 0) 1 else 0, cancelled = cancelled)
        MemoryDiagnostics.log(
            "Memory/IndexRepair",
            "索引重建：扫描=$scanned 修复=$repaired 失败=$failed 仍有剩余=${report.remaining} 取消=$cancelled 签名=$signature",
        )
        onProgress(report)
        return report
    }

    private suspend fun saveMemoryItemToVector(id: Long, item: MemoryItem): Boolean {
        val service = memoryVectorService ?: return false
        if (item.content.isBlank()) return false
        val indexed = item.copy(id = id)
        val vectorId = MemoryVectorFormatter.vectorId(indexed)
        try {
            service.saveMemory(
                VectorMemory(
                    id = vectorId,
                    ownerType = item.ownerType,
                    ownerId = item.ownerId,
                    sourceType = MemoryVectorFormatter.sourceType(indexed),
                    sourceId = item.sourceRefId.ifBlank { item.sessionId },
                    content = MemoryVectorFormatter.content(indexed), originMemoryId = id, originContent = item.content,
                    importance = item.importance.coerceIn(0, 100) / 100.0,
                    tags = MemoryVectorFormatter.tags(indexed),
                    visibility = item.privacy ?: parseRules.defaultPrivacy(item.sourceKind),
                    createdAt = item.createdAt,
                    expiresAt = item.expiresAt,
                )
            )
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MemoryDiagnostics.log("Vector/Save", "记忆向量写入失败 owner=${item.ownerId} level=${item.memoryLevel} err=${e.message?.take(80)}")
            return false
        }
    }

    companion object {
        /** 消费统计页的「记忆」分类键（RankingBridge.TOKEN_CATEGORIES 同口径）。 */
        const val TOKEN_CATEGORY_MEMORY = "memory"
        /** 整合幂等签名里的提示词版本；prompt 升级时递增。 */
        const val CONSOLIDATION_PROMPT_VERSION = "v3"
        /** 实体通道：命中记忆的固定词法分（≥ FACT 2.0 / TIME 2.5 准入）。 */
        const val ENTITY_RECALL_SCORE = 2.6
        /** 实体名天然具备区分度，视作高于判别门槛的 IDF。 */
        const val ENTITY_RECALL_IDF = 2.5
        const val ENTITY_CATALOG_LIMIT = 400
        const val ENTITY_HIT_LIMIT = 40
    }
}
