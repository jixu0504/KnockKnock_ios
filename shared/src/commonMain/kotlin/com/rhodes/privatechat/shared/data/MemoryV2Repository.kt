package com.rhodes.privatechat.shared.data
import kotlinx.datetime.Clock

import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.db.DatabaseDispatcher
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.ImpressionBuilder
import com.rhodes.privatechat.shared.domain.memory.MemoryDiagnostics
import com.rhodes.privatechat.shared.domain.memory.ImpressionEvidencePlanner
import com.rhodes.privatechat.shared.domain.memory.MemoryEntityExtractor
import com.rhodes.privatechat.shared.domain.memory.MemoryParseRules
import com.rhodes.privatechat.shared.model.MemoryBatch
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemoryLink
import com.rhodes.privatechat.shared.model.MemorySourceItem
import com.rhodes.privatechat.shared.model.MemorySourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MemoryV2Repository(private val wrapper: DatabaseWrapper) {
    private val lexicalIndex = MemoryLexicalIndex(wrapper.database)
    private val factRepository = MemoryFactRepository(wrapper.database)
    private val usageRepository = MemoryUsageRepository(wrapper.database)
    private val entityRepository = MemoryEntityRepository(wrapper.database)
    private val impressionRepository = ImpressionRepository(wrapper.database)
    private val impressionEvidenceRepository = ImpressionEvidenceRepository(wrapper.database)
    private val db: RhodesDatabase get() = wrapper.database

    /** Link new conclusions only if every parent is still the same evidence we summarised. */
    suspend fun finishPromotion(children: List<MemoryItem>, parents: List<MemoryItem>, links: List<MemoryLink>, signature: String, now: Long) =
        withContext(DatabaseDispatcher.dispatcher) {
            db.transaction {
                val unchanged = parents.all { original ->
                    db.memoryItemsQueries.getMemoryItemById(original.id).executeAsOneOrNull()?.let {
                        it.status == "active" && it.content == original.content && it.privacy == original.privacy && it.sourceKind == original.sourceKind.name && it.sourceRefId == original.sourceRefId && it.expiresAt > now
                    } == true
                }
                if (!unchanged) {
                    children.forEach { child ->
                        db.memoryItemsQueries.getMemoryVectorId(child.id).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                            ?.let { db.vectorMemoriesQueries.deleteVectorMemory(it) }
                        db.memoryItemsQueries.archiveMemoryItem(now, child.id)
                    }
                } else {
                    links.forEach { link -> db.memoryLinksQueries.insertMemoryLink(link.parentMemoryId, link.childMemoryId, link.linkType, link.createdAt) }
                    links.groupBy { it.parentMemoryId }.forEach { (parentId, evidenceLinks) ->
                        // Missing child vectors must not remove working parent recall.
                        val allIndexed = evidenceLinks.all { link ->
                            db.memoryItemsQueries.hasUsableMemoryVector(link.childMemoryId, signature, now).executeAsOne()
                        }
                        if (allIndexed) {
                            db.memoryItemsQueries.getMemoryVectorId(parentId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                                ?.let { db.vectorMemoriesQueries.deleteVectorMemory(it) }
                            db.memoryItemsQueries.archiveMemoryItem(now, parentId)
                        }
                    }
                }
            }
        }

    suspend fun insertMemoryItem(item: MemoryItem): Long = withContext(DatabaseDispatcher.dispatcher) {
        db.transactionWithResult { insertMemoryItemInternal(item) }
    }

    /** No check-then-insert gap; different evidence and privacy boundaries remain separate. */
    suspend fun insertMemoryItemIfAbsent(item: MemoryItem, now: Long): Long? = withContext(DatabaseDispatcher.dispatcher) {
        db.transactionWithResult {
            val existing = db.memoryItemsQueries.findEquivalentLiveMemory(item.ownerType, item.ownerId,
                item.memoryLevel.name, item.memoryType, item.content, item.sourceKind.name,
                item.sourceRefId, item.sessionId, item.privacy, now).executeAsOneOrNull()
            if (existing != null) null else insertMemoryItemInternal(item)
        }
    }

    private fun insertMemoryItemInternal(item: MemoryItem): Long {
        db.memoryItemsQueries.insertMemoryItem(
            item.ownerType,
            item.ownerId,
            item.memoryLevel.name,
            item.memoryType,
            item.sourceKind.name,
            item.sourceRefId,
            item.sessionId,
            item.content,
            item.nickname,
            item.importance.toLong(),
            item.privacy,
            if (item.unmetNeed) 1L else 0L,
            item.location,
            item.emotionValence,
            item.eventTime,
            item.createdAt,
            item.updatedAt,
            item.expiresAt,
            item.status,
            item.scheduledTime,
            item.action,
            item.careType,
            item.topicKey,
            item.sourceActor,
            item.sourceTarget,
            item.lastUsedAt,
            item.usedCount.toLong(),
            item.confidence,
            item.rawJson,
            item.vectorId,
        )
        val id = db.memoryItemsQueries.getLastInsertedMemoryItemId().executeAsOne()
        // 词法索引与记忆行同事务写入：宁可整笔回滚，也不留下「有记忆、搜不到」的静默状态。
        lexicalIndex.indexItem(item.copy(id = id))
        return id
    }

    /**
     * L1 事实落库：validFrom 取记忆自身的事件时间（消息时间），timeSource 标记来源为消息；
     * 冲突状态先记 none，supersede/contradicts 裁决留给 T78。
     */
    internal suspend fun upsertMemoryFact(item: MemoryItem, fact: MemoryParseRules.StructuredFact, now: Long) {
        factRepository.upsert(
            MemoryFact(
                memoryId = item.id,
                ownerType = item.ownerType,
                ownerId = item.ownerId,
                subject = fact.subject,
                predicate = fact.predicate,
                objectValue = fact.objectValue,
                polarity = fact.polarity,
                qualifiers = fact.qualifiers,
                factKey = fact.factKey,
                validFrom = item.createdAt,
                validTo = null,
                recordedAt = now,
                timeSource = "message",
                conflictStatus = "none",
                updatedAt = now,
            ),
        )
    }

    suspend fun getMemoryFact(memoryId: Long): MemoryFact? = factRepository.get(memoryId)

    /** T69：事实的主体/客体落实体表并挂到记忆上（名字归一，代词/单字跳过）。 */
    internal suspend fun upsertMemoryEntities(item: MemoryItem, fact: MemoryParseRules.StructuredFact, now: Long) {
        MemoryEntityExtractor.extract(fact, item.ownerType, item.ownerId).forEach { draft ->
            val entityId = entityRepository.upsertEntity(
                ownerType = draft.ownerType,
                ownerId = draft.ownerId,
                kind = draft.kind,
                name = draft.name,
                normalizedName = draft.normalizedName,
                now = now,
            )
            if (entityId > 0) entityRepository.linkMemory(item.id, entityId, draft.role)
        }
    }

    /**
     * T81：L2/L3 落库后聚合成印象并挂证据。
     *
     * 结构化事实键能回查到同事实的全部 active 记忆（L1/L2/L3）；legacy 兜底键没有事实键，
     * 只能用本次落库的条目作为证据，避免把不同话题的记忆错误挂在一起。
     */
    internal suspend fun upsertImpressions(items: List<MemoryItem>, now: Long) {
        val drafts = ImpressionBuilder.build(items)
        drafts.forEach { draft ->
            runCatching {
                val impressionId = impressionRepository.upsert(draft, now)
                if (impressionId <= 0) return@runCatching
                val evidenceItems = if (draft.legacyKey) {
                    items.filter { it.ownerType == draft.ownerType && it.ownerId == draft.ownerId && it.id > 0 }
                } else {
                    val refs = getActiveMemoryTopicRefs(draft.ownerType, draft.ownerId, listOf(draft.factKey), now, 40)
                    getActiveMemoryItemsByIds(refs.map { it.first }, now)
                }
                val planned = ImpressionEvidencePlanner.plan(evidenceItems)
                if (planned.isNotEmpty()) impressionEvidenceRepository.linkAll(impressionId, planned, now)
            }.onFailure { e ->
                MemoryDiagnostics.log(
                    "Memory/ImpressionError",
                    "印象写入失败：key=${draft.factKey.take(40)}，原因=${e.message?.take(120) ?: "未知"}",
                )
            }
        }
    }

    /**
     * T81 回填：把该 owner 现有的 L2/L3 记忆重新聚合成印象（可重入，upsert 去重）。
     * 返回生成的印象条数；用于老库升级后一次性补齐，不依赖新写入。
     */
    internal suspend fun rebuildImpressionsForOwner(ownerType: String, ownerId: String, now: Long): Int {
        val l2 = getActiveMemoryItemsByLevel(ownerType, ownerId, MemoryLevel.L2, now)
        val l3 = getActiveMemoryItemsByLevel(ownerType, ownerId, MemoryLevel.L3, now)
        val items = l2 + l3
        if (items.isEmpty()) return 0
        upsertImpressions(items, now)
        return ImpressionBuilder.build(items).size
    }

    suspend fun listMemoryEntities(ownerType: String, ownerId: String, limit: Int): List<MemoryEntityRef> =
        entityRepository.listEntities(ownerType, ownerId, limit)

    suspend fun findMemoryIdsByEntityNames(
        ownerType: String,
        ownerId: String,
        normalizedNames: List<String>,
        limit: Int,
    ): List<Long> = entityRepository.findMemoryIdsByNames(ownerType, ownerId, normalizedNames, limit)

    /** 被取代的旧 memory_items 归档（列表/召回都按 active 过滤，实现「旧值不再当现状」）。 */
    suspend fun archiveSupersededMemoryItems(memoryIds: List<Long>, now: Long): Int =
        withContext(Dispatchers.Default) {
            var archived = 0
            memoryIds.forEach { id ->
                runCatching {
                    db.memoryItemsQueries.archiveMemoryItem(now, id)
                    archived++
                }
            }
            archived
        }

    /** T78：单值事实换值后关闭旧事实有效期（不删除记忆）。 */
    suspend fun supersedeMemoryFacts(memoryIds: List<Long>, now: Long): Int =
        factRepository.markSuperseded(memoryIds, now)

    /** T78：确认冲突（新事实成立），旧事实关闭有效期；供后续确认入口调用。 */
    suspend fun confirmMemoryFactConflict(newMemoryId: Long, oldMemoryId: Long, now: Long) =
        factRepository.confirmConflict(newMemoryId, oldMemoryId, now)

    /** T78：冲突判定为 contradicts 时把新旧事实行标为待确认，不关闭/合并记忆。 */
    suspend fun markMemoryFactConflict(memoryIds: List<Long>, now: Long): Int =
        factRepository.markConflict(memoryIds, now)

    /** T73：召回引擎最终选中的记忆批量累加注入计数；去重/过滤非正 id 由 usage 仓库兜底。 */
    suspend fun bumpInjectionUsage(ids: List<Long>, now: Long) = usageRepository.bumpInjected(ids, now)

    suspend fun memoryIndexStates(signature: String, now: Long): Map<Long, String> = withContext(DatabaseDispatcher.dispatcher) {
        db.memoryItemsQueries.getMemoryIndexStates(now, signature).executeAsList().associate { it.id to it.indexState }
    }

    suspend fun getMemoryItemsByOwner(ownerType: String, ownerId: String): List<MemoryItem> = withContext(DatabaseDispatcher.dispatcher) {
        db.memoryItemsQueries.getMemoryItemsByOwner(ownerType, ownerId) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun getAllMemoryItems(): List<MemoryItem> = withContext(DatabaseDispatcher.dispatcher) {
        db.memoryItemsQueries.getAllMemoryItems { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun getMemoryItemsByLevel(ownerType: String, ownerId: String, level: MemoryLevel): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getMemoryItemsByLevel(ownerType, ownerId, level.name) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun getActiveMemoryItemsByLevel(ownerType: String, ownerId: String, level: MemoryLevel, now: Long): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveMemoryItemsByLevel(ownerType, ownerId, level.name, now) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun getMemoryItemsByType(ownerType: String, ownerId: String, type: String): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getMemoryItemsByType(ownerType, ownerId, type) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun getActiveMemoryItemByContent(ownerType: String, ownerId: String, level: MemoryLevel, type: String, content: String): MemoryItem? = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveMemoryItemByContent(ownerType, ownerId, level.name, type, content) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content_, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content_, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsOneOrNull()
    }

    suspend fun getMemoryItemsForBatch(ownerType: String, ownerId: String, level: MemoryLevel, start: Long, end: Long): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getMemoryItemsForBatch(ownerType, ownerId, level.name, start, end) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun saveBatch(batch: MemoryBatch) = withContext(Dispatchers.Default) {
        db.memoryBatchesQueries.insertMemoryBatch(
            batch.ownerType,
            batch.ownerId,
            batch.sourceKind.name,
            batch.targetLevel.name,
            batch.inputCount.toLong(),
            batch.outputCount.toLong(),
            batch.windowStart,
            batch.windowEnd,
            batch.promptVersion,
            batch.status,
            batch.createdAt,
        )
    }

    suspend fun getBatches(ownerType: String, ownerId: String): List<MemoryBatch> = withContext(Dispatchers.Default) {
        db.memoryBatchesQueries.getMemoryBatchesByOwner(ownerType, ownerId).executeAsList().map {
            MemoryBatch(
                id = it.id,
                ownerType = it.ownerType,
                ownerId = it.ownerId,
                sourceKind = try { MemorySourceKind.valueOf(it.sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT },
                targetLevel = try { MemoryLevel.valueOf(it.targetLevel) } catch (_: Exception) { MemoryLevel.L1 },
                inputCount = it.inputCount.toInt(),
                outputCount = it.outputCount.toInt(),
                windowStart = it.windowStart,
                windowEnd = it.windowEnd,
                promptVersion = it.promptVersion,
                status = it.status,
                createdAt = it.createdAt,
            )
        }
    }

    suspend fun insertSource(source: MemorySourceItem): Long = withContext(Dispatchers.Default) {
        // Retries and duplicate UI events can submit the same extraction window more than once.
        // Reuse its unfinished task so it keeps one lease and one retry history.
        db.transactionWithResult {
            fun existingId() = db.memorySourceQueueQueries.getUnfinishedMemorySourceId(
                source.sourceKind.name, source.ownerType, source.ownerId, source.sourceRefId,
                source.contentText, source.timestamp,
            ).executeAsOneOrNull()
            existingId()?.let { return@transactionWithResult it }
            db.memorySourceQueueQueries.insertMemorySource(
                source.sourceKind.name, source.ownerType, source.ownerId, source.sourceRefId,
                source.contentText, source.timestamp, if (source.processedL1) 1L else 0L,
                if (source.processedVector) 1L else 0L, source.status, source.retryCount.toLong(),
                source.nextRetryAt, source.leaseUntil, source.claimToken, source.lastError, source.createdAt,
            )
            db.memorySourceQueueQueries.getMemorySourceId(
                source.sourceKind.name, source.ownerType, source.ownerId, source.sourceRefId,
                source.contentText, source.timestamp,
            ).executeAsOne()
        }
    }

    suspend fun claimPendingSources(now: Long, limit: Int, leaseMs: Long = 10 * 60_000L): List<Pair<MemorySourceItem, String>> = withContext(Dispatchers.Default) {
        db.memorySourceQueueQueries.getPendingMemorySources(now, now, limit.toLong()).executeAsList().mapNotNull {
            val token = "memory-${now}-${Random.nextLong()}"
            db.memorySourceQueueQueries.claimMemorySource(now + leaseMs, token, it.id, now, now)
            if (db.memorySourceQueueQueries.getMemorySourceClaimToken(it.id).executeAsOneOrNull() != token) return@mapNotNull null
            MemorySourceItem(
                id = it.id,
                sourceKind = try { MemorySourceKind.valueOf(it.sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT },
                ownerType = it.ownerType,
                ownerId = it.ownerId,
                sourceRefId = it.sourceRefId,
                contentText = it.contentText,
                timestamp = it.timestamp,
                processedL1 = it.processedL1 != 0L,
                processedVector = it.processedVector != 0L,
                status = "running",
                retryCount = it.retryCount.toInt(),
                nextRetryAt = it.nextRetryAt,
                leaseUntil = now + leaseMs,
                claimToken = token,
                lastError = it.lastError,
                createdAt = it.createdAt,
            ) to token
        }
    }

    suspend fun claimSource(id: Long, now: Long, leaseMs: Long = 10 * 60_000L): String? = withContext(Dispatchers.Default) {
        val token = "memory-${now}-${Random.nextLong()}"
        db.memorySourceQueueQueries.claimMemorySourceById(now + leaseMs, token, id, now)
        token.takeIf { db.memorySourceQueueQueries.getMemorySourceClaimToken(id).executeAsOneOrNull() == it }
    }

    suspend fun isMemorySourceFinished(id: Long): Boolean = withContext(Dispatchers.Default) {
        db.memorySourceQueueQueries.getMemorySourceStatus(id).executeAsOneOrNull()?.let { state ->
            state.processedL1 != 0L || state.status == "succeeded" || state.status == "skipped"
        } == true
    }

    suspend fun markSourceProcessedL1(id: Long) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.markMemorySourceProcessedL1(id) }
    suspend fun markSourceProcessedVector(id: Long) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.markMemorySourceProcessedVector(id) }
    suspend fun deleteMemorySource(id: Long) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.deleteMemorySource(id) }
    suspend fun completeSource(id: Long, token: String) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.completeMemorySource(id, token) }
    suspend fun retrySource(id: Long, token: String, nextRetryAt: Long, error: String) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.retryMemorySource(nextRetryAt, error, id, token) }
    suspend fun releaseSource(id: Long, token: String, nextRetryAt: Long, reason: String) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.releaseMemorySource(nextRetryAt, reason, id, token) }
    suspend fun failSource(id: Long, token: String, error: String) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.failMemorySource(error, id, token) }
    suspend fun skipSource(id: Long, token: String, error: String) = withContext(Dispatchers.Default) { db.memorySourceQueueQueries.skipMemorySource(error, id, token) }
    suspend fun renewSourceLease(id: Long, token: String, now: Long, leaseMs: Long = 10 * 60_000L) = withContext(Dispatchers.Default) {
        db.memorySourceQueueQueries.renewMemorySourceLease(now + leaseMs, id, token)
    }

    suspend fun updateMemoryItemVectorId(id: Long, vectorId: String, updatedAt: Long) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.updateMemoryItemVectorId(vectorId, updatedAt, id)
    }

    suspend fun getActiveMemoryItemsMissingVector(now: Long, limit: Int): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveMemoryItemsMissingVector(now, limit.toLong()) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    /** 与当前嵌入签名不一致、需要重建的历史向量条目（换模型/切第三方嵌入后会全部命中）。 */
    suspend fun getActiveMemoryItemsWithStaleVector(signature: String, now: Long, limit: Int): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveMemoryItemsWithStaleVector(now, signature, limit.toLong()) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    /** 游标分页版：从 (afterCreatedAt, afterId) 之后继续，失败的条目不会阻塞后续条目。 */
    suspend fun getActiveMemoryItemsMissingVectorAfter(
        now: Long,
        afterCreatedAt: Long,
        afterId: Long,
        limit: Int,
    ): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveMemoryItemsMissingVectorAfter(now, afterCreatedAt, afterId, limit.toLong()) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    suspend fun getActiveMemoryItemsWithStaleVectorAfter(
        signature: String,
        now: Long,
        afterCreatedAt: Long,
        afterId: Long,
        limit: Int,
    ): List<MemoryItem> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveMemoryItemsWithStaleVectorAfter(now, signature, afterCreatedAt, afterId, limit.toLong()) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    /** 队列窗口已完成的失败重试次数（用于退避阶梯，避免每次失败都从第 1 次重算）。 */
    suspend fun memorySourceRetryCount(id: Long): Int = withContext(Dispatchers.Default) {
        db.memorySourceQueueQueries.getMemorySourceRetryCount(id).executeAsOneOrNull()?.toInt() ?: 0
    }

    /** 词法检索（BM25）：索引陈旧时先按 DB 重建，再返回 memoryId + 分数。 */
    suspend fun searchLexical(
        ownerType: String,
        ownerId: String,
        query: String,
        limit: Int,
        now: Long,
    ): List<LexicalHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        lexicalIndex.ensureReady(ownerType, ownerId, now)
        return lexicalIndex.search(ownerType, ownerId, query, limit)
    }

    /**
     * 启动/回前台预热：为所有有 active 记忆的 owner 检查并重建词法索引。
     * 避免升级后第一次聊天在提示词预算内同步扫描全库（12s 共享预算）。
     */
    suspend fun warmLexicalIndex(now: Long) = lexicalIndex.warm(now)

    /** 按事实键批量取 active 记忆的 (id, topicKey)，用于关系判定。 */
    suspend fun getActiveMemoryTopicRefs(
        ownerType: String,
        ownerId: String,
        topicKeys: List<String>,
        now: Long,
        limit: Int,
    ): List<Pair<Long, String>> = withContext(Dispatchers.Default) {
        if (topicKeys.isEmpty()) return@withContext emptyList()
        db.memoryItemsQueries.getActiveMemoryTopicRefs(ownerType, ownerId, now, topicKeys, limit.toLong())
            .executeAsList()
            .map { it.id to it.topicKey }
    }

    /** 结构化事实行的 (id, topicKey, createdAt)，用于按归并基座判定关系（EXACT 键匹配会漏掉限定词差异）。 */
    suspend fun getActiveStructuredTopicRefs(
        ownerType: String,
        ownerId: String,
        now: Long,
        limit: Int,
    ): List<StructuredTopicRef> = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.getActiveStructuredTopicRefs(ownerType, ownerId, now, limit.toLong())
            .executeAsList()
            .map { StructuredTopicRef(it.id, it.topicKey, it.createdAt) }
    }

    /** 已被新事实取代的记忆 id（召回层据此不再当作现状）。 */
    suspend fun supersededMemoryIds(memoryIds: List<Long>): Set<Long> = withContext(Dispatchers.Default) {
        if (memoryIds.isEmpty()) return@withContext emptySet()
        db.memoryFactsQueries.getSupersededMemoryIds(memoryIds).executeAsList().toSet()
    }

    suspend fun insertMemoryRelation(
        fromMemoryId: Long,
        toMemoryId: Long,
        relationType: String,
        confidence: Double,
        decidedBy: String,
        now: Long,
    ) = withContext(Dispatchers.Default) {
        db.memoryRelationsQueries.insertMemoryRelation(fromMemoryId, toMemoryId, relationType, confidence, decidedBy, now)
    }

    suspend fun memoryRelationCount(): Long = withContext(Dispatchers.Default) {
        db.memoryRelationsQueries.countMemoryRelations().executeAsOne()
    }

    /** 按 id 批量取 active 记忆（词法命中回表用）。 */
    suspend fun getActiveMemoryItemsByIds(ids: List<Long>, now: Long): List<MemoryItem> = withContext(Dispatchers.Default) {
        if (ids.isEmpty()) return@withContext emptyList()
        db.memoryItemsQueries.getActiveMemoryItemsByIds(ids, now) { id, ownerType_, ownerId_, memoryLevel, memoryType, sourceKind, sourceRefId, sessionId, content, nickname, importance, privacy, unmetNeed, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount, confidence, rawJson, vectorId ->
            MemoryItem(id, ownerType_, ownerId_, try { MemoryLevel.valueOf(memoryLevel) } catch (_: Exception) { MemoryLevel.L1 }, memoryType, try { MemorySourceKind.valueOf(sourceKind) } catch (_: Exception) { MemorySourceKind.PRIVATE_CHAT }, sourceRefId, sessionId, content, nickname, importance.toInt(), privacy, unmetNeed != 0L, location, emotionValence, eventTime, createdAt, updatedAt, expiresAt, status, scheduledTime, action, careType, topicKey, sourceActor, sourceTarget, lastUsedAt, usedCount.toInt(), confidence, rawJson, vectorId)
        }.executeAsList()
    }

    /** 宽限期后物理删除已过期条目，并清理悬空的父子关系（软删只改状态，不删证据链关系）。 */
    suspend fun purgeExpiredMemoryItems(cutoff: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.transaction {
            db.memoryItemsQueries.purgeExpiredMemoryItems(cutoff)
            db.memoryLinksQueries.deleteOrphanedMemoryLinks()
        }
    }

    suspend fun updateMemoryItemContent(id: Long, content: String, updatedAt: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.transaction {
            db.memoryItemsQueries.getMemoryVectorId(id).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                ?.let { db.vectorMemoriesQueries.deleteVectorMemory(it) }
            val pending = ArrayDeque<Long>()
            pending.addLast(id)
            invalidateDescendantsInternal(pending).forEach { db.vectorMemoriesQueries.deleteVectorMemory(it) }
            db.memoryItemsQueries.updateMemoryItemContent(content, updatedAt, id)
            db.memoryItemsQueries.updateMemoryItemVectorId("", updatedAt, id)
        }
    }

    /** T47 记忆管理页：手动调整重要度（0..100，调用方负责裁剪）；不动向量索引。 */
    suspend fun updateMemoryItemImportance(id: Long, importance: Int, updatedAt: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.memoryItemsQueries.updateMemoryItemImportance(importance.toLong(), updatedAt, id)
    }

    suspend fun updateActiveMemoryExpiry(expiresAt: Long, updatedAt: Long) = withContext(Dispatchers.Default) {
        db.transaction {
            db.memoryItemsQueries.updateActiveMemoryItemExpiry(expiresAt, updatedAt)
            db.vectorMemoriesQueries.updateMemoryItemVectorExpiry(expiresAt)
        }
    }

    suspend fun restoreExpiredMemoryItems(expiresAt: Long, updatedAt: Long) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.restoreExpiredMemoryItems(expiresAt, updatedAt)
    }

    suspend fun clearAllMemoryItemVectorIds() = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.clearAllMemoryItemVectorIds()
    }

    suspend fun clearMemoryItemVectorIdsByOwner(ownerType: String, ownerId: String) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.clearMemoryItemVectorIdsByOwner(ownerType, ownerId)
    }

    suspend fun archiveMemoryItemsByLevel(ownerType: String, ownerId: String, level: MemoryLevel, updatedAt: Long) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.archiveMemoryItemsByLevel(updatedAt, ownerType, ownerId, level.name)
    }

    suspend fun archiveMemoryItem(id: Long, updatedAt: Long) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.archiveMemoryItem(updatedAt, id)
    }

    suspend fun deleteMemoryItemsBySession(sessionId: String) = withContext(Dispatchers.Default) {
        deleteBySession(sessionId)
    }

    /** Deletes the structured source records and all direct vector copies of that source. */
    suspend fun deleteBySource(sourceKind: MemorySourceKind, sourceRefId: String) = withContext(Dispatchers.Default) {
        db.transaction {
            val invalidatedVectorIds = invalidateDerivedBySourceInternal(sourceKind, sourceRefId)
            invalidatedVectorIds.forEach { vectorId -> db.vectorMemoriesQueries.deleteVectorMemory(vectorId) }
            // Structured items retain the exact vector IDs, avoiding collisions between
            // independently numbered moments and comments.
            db.vectorMemoriesQueries.deleteVectorsForMemorySource(sourceKind.name, sourceRefId)
            db.memoryItemsQueries.deleteMemoryItemsBySource(sourceKind.name, sourceRefId)
            db.memorySourceQueueQueries.deleteMemorySourcesBySource(sourceKind.name, sourceRefId)
            db.vectorMemoriesQueries.deleteVectorMemoriesBySourceTypeAndId(sourceKind.name.lowercase(), sourceRefId)
            db.memoryLinksQueries.deleteOrphanedMemoryLinks()
        }
    }

    suspend fun deleteGroupChatMemoryByGroupId(groupId: String) = withContext(Dispatchers.Default) {
        val prefix = "$groupId:%"
        // Delete each window through the normal source path so promoted L2/L3 descendants of
        // copies given to group members are invalidated before their L1 evidence disappears.
        val sourceRefIds = db.memoryItemsQueries
            .getSourceRefIdsBySourcePrefix(MemorySourceKind.GROUP_CHAT.name, prefix)
            .executeAsList()
        sourceRefIds.forEach { sourceRefId -> deleteBySource(MemorySourceKind.GROUP_CHAT, sourceRefId) }
        db.transaction {
            db.memoryItemsQueries.deleteVectorsBySourcePrefix(MemorySourceKind.GROUP_CHAT.name, prefix)
            db.memoryItemsQueries.deleteMemoryItemsBySourcePrefix(MemorySourceKind.GROUP_CHAT.name, prefix)
            db.memorySourceQueueQueries.deleteMemorySourcesBySourcePrefix(MemorySourceKind.GROUP_CHAT.name, prefix)
            db.memoryLinksQueries.deleteOrphanedMemoryLinks()
        }
    }

    /**
     * A deleted source invalidates every promoted conclusion that used it as evidence.  Those
     * conclusions are archived rather than silently kept in recall; remaining evidence can form
     * a new conclusion later.
     */
    private fun invalidateDerivedBySourceInternal(sourceKind: MemorySourceKind, sourceRefId: String): List<String> {
        val pending = ArrayDeque<Long>()
        db.memoryItemsQueries.getMemoryIdsBySource(sourceKind.name, sourceRefId).executeAsList().forEach(pending::addLast)
        return invalidateDescendantsInternal(pending)
    }

    private fun invalidateDescendantsInternal(pending: ArrayDeque<Long>): List<String> {
        val visited = mutableSetOf<Long>()
        val vectorIds = mutableListOf<String>()
        val now = Clock.System.now().toEpochMilliseconds()
        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            db.memoryLinksQueries.getChildMemoryIds(parentId).executeAsList().forEach { childId ->
                if (childId !in visited) {
                    db.memoryItemsQueries.getMemoryVectorId(childId).executeAsOneOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(vectorIds::add)
                    db.memoryItemsQueries.archiveMemoryItem(now, childId)
                    pending.addLast(childId)
                }
            }
        }
        return vectorIds
    }

    /** Keeps all records for audit; only affected evidence and its descendants stop being recalled. */
    suspend fun invalidateChangedMessage(sessionId: String, messageId: Long) = withContext(DatabaseDispatcher.dispatcher) {
        db.transaction {
            val affected = db.memoryItemsQueries.getMemoryIdsBySessionForRepair(sessionId).executeAsList().filter { id ->
                val item = db.memoryItemsQueries.getMemoryItemById(id).executeAsOneOrNull() ?: return@filter false
                val evidence = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(item.rawJson).let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("sourceMessageIds") as? kotlinx.serialization.json.JsonArray
                }.getOrNull()
                // Records without message-level evidence cannot be proven to depend on the recalled message.
                // Keep them active; only explicitly linked evidence is safe to invalidate.
                evidence?.any { it.toString().trim('"').toLongOrNull() == messageId } == true
            }
            val pending = ArrayDeque<Long>()
            affected.forEach { id ->
                db.memoryItemsQueries.getMemoryVectorId(id).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { db.vectorMemoriesQueries.deleteVectorMemory(it) }
                db.memoryItemsQueries.archiveMemoryItem(Clock.System.now().toEpochMilliseconds(), id)
                pending.addLast(id)
            }
            invalidateDescendantsInternal(pending).forEach { db.vectorMemoriesQueries.deleteVectorMemory(it) }
        }
    }

    suspend fun invalidateDerivedBySession(sessionId: String) = withContext(Dispatchers.Default) {
        db.transaction {
            val pending = ArrayDeque<Long>()
            db.memoryItemsQueries.getMemoryIdsBySession(sessionId).executeAsList().forEach(pending::addLast)
            invalidateDescendantsInternal(pending).forEach { vectorId -> db.vectorMemoriesQueries.deleteVectorMemory(vectorId) }
        }
    }

    suspend fun markMemoryItemUsed(id: Long, now: Long) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.markMemoryItemUsed(now, now, id)
    }

    suspend fun insertMemoryLink(link: MemoryLink) = withContext(Dispatchers.Default) {
        db.memoryLinksQueries.insertMemoryLink(link.parentMemoryId, link.childMemoryId, link.linkType, link.createdAt)
    }

    suspend fun getMemoryLinksByParent(parentMemoryId: Long): List<MemoryLink> = withContext(Dispatchers.Default) {
        db.memoryLinksQueries.getMemoryLinksByParent(parentMemoryId).executeAsList().map {
            MemoryLink(
                id = it.id,
                parentMemoryId = it.parentMemoryId,
                childMemoryId = it.childMemoryId,
                linkType = it.linkType,
                createdAt = it.createdAt,
            )
        }
    }

    suspend fun deleteByOwner(ownerType: String, ownerId: String) = withContext(Dispatchers.Default) {
        db.memoryItemsQueries.deleteMemoryItemsByOwner(ownerType, ownerId)
        db.memoryBatchesQueries.deleteMemoryBatchesByOwner(ownerType, ownerId)
        db.memorySourceQueueQueries.deleteMemorySourcesByOwner(ownerType, ownerId)
    }

    suspend fun deleteBySession(sessionId: String) = withContext(Dispatchers.Default) {
        invalidateDerivedBySession(sessionId)
        db.transaction {
            db.vectorMemoriesQueries.deleteVectorsForMemorySession(sessionId)
            db.memoryItemsQueries.deleteMemoryItemsBySession(sessionId)
            db.memorySourceQueueQueries.deleteMemorySourcesBySession(sessionId)
            db.memoryLinksQueries.deleteOrphanedMemoryLinks()
        }
    }

    suspend fun deleteMemoryItem(id: Long) = withContext(Dispatchers.Default) {
        db.transaction {
            db.memoryItemsQueries.getMemoryVectorId(id).executeAsOneOrNull()
                ?.takeIf { it.isNotBlank() }?.let { vectorId -> db.vectorMemoriesQueries.deleteVectorMemory(vectorId) }
            val pending = ArrayDeque<Long>()
            pending.addLast(id)
            invalidateDescendantsInternal(pending).forEach { vectorId -> db.vectorMemoriesQueries.deleteVectorMemory(vectorId) }
        }
        db.memoryLinksQueries.deleteMemoryLinksByParent(id)
        db.memoryLinksQueries.deleteMemoryLinksByChild(id)
        db.memoryItemsQueries.deleteMemoryItem(id)
    }

    suspend fun deleteByOwnerAndSourceKind(ownerType: String, ownerId: String, sourceKind: MemorySourceKind) = withContext(Dispatchers.Default) {
        val vectorIds = db.memoryItemsQueries.getMemoryVectorIdsByOwnerAndSourceKind(ownerType, ownerId, sourceKind.name)
            .executeAsList().filter { it.isNotBlank() }
        vectorIds.forEach { vectorId -> db.vectorMemoriesQueries.deleteVectorMemory(vectorId) }
        if (sourceKind == MemorySourceKind.PRIVATE_CHAT) {
            db.memoryLinksQueries.deleteMemoryLinksForOwnerPrivateSource(ownerType, ownerId, ownerType, ownerId)
            db.memoryBatchesQueries.deletePrivateMemoryBatchesByOwner(ownerType, ownerId)
        }
        db.memoryItemsQueries.deleteMemoryItemsByOwnerAndSourceKind(ownerType, ownerId, sourceKind.name)
        db.memorySourceQueueQueries.deleteMemorySourcesByOwnerAndKind(ownerType, ownerId, sourceKind.name)
    }

}
