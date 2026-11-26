package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.db.Impressions as ImpressionRow
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.ImpressionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一行印象（T81）：`impressions` 表的读模型。 */
data class Impression(
    val id: Long = 0,
    val ownerType: String,
    val ownerId: String,
    val characterId: String = "",
    val layer: String = "interaction",
    val subject: String = "",
    val predicate: String = "",
    val value: String = "",
    val polarity: String = "unknown",
    val factKey: String = "",
    val confidence: Double = 0.0,
    val tone: String = "neutral",
    val evidenceCount: Int = 0,
    val firstSeenAt: Long = 0,
    val lastConfirmedAt: Long = 0,
    val status: String = "active",
    val updatedAt: Long = 0,
)

/** 一页印象 + 全表可见（active/frozen）总数。 */
data class ImpressionPage(
    val items: List<Impression>,
    val total: Long,
) {
    val hasMore: Boolean get() = items.size < total
}

/** T83 纠正审计的一行：只追加，不改写。 */
data class ImpressionHistoryEntry(
    val id: Long,
    val impressionId: Long,
    val action: String,
    val beforeValue: String,
    val afterValue: String,
    val beforeStatus: String,
    val afterStatus: String,
    val createdAt: Long,
)

/** 证据对应的记忆：正文与会话 id；记忆已被物理删除时两者为空串。 */
data class ImpressionMemoryRef(
    val memoryId: Long,
    val content: String,
    val sessionId: String,
)

/**
 * 印象读写（T81）。唯一键是 (ownerType, ownerId, factKey)：同一事实重复写入只累加证据，
 * 不产生第二行。SQLite 3.18 无 UPSERT，所以 [upsert] 在同一个事务里先查后写。
 *
 * 写入不会复活已 rejected/frozen 的印象：更新分支保留原有 status，人工裁决不会被新证据覆盖。
 */
class ImpressionRepository(private val db: RhodesDatabase) {

    private val queries get() = db.impressionsQueries

    suspend fun upsert(draft: ImpressionDraft, now: Long): Long = withContext(Dispatchers.Default) {
        db.transactionWithResult {
            val existing = queries
                .selectByFactKey(draft.ownerType, draft.ownerId, draft.factKey)
                .executeAsOneOrNull()
            if (existing == null) {
                queries.insertImpression(
                    draft.ownerType,
                    draft.ownerId,
                    draft.characterId,
                    draft.layer,
                    draft.subject,
                    draft.predicate,
                    draft.value,
                    draft.polarity,
                    draft.factKey,
                    draft.confidence,
                    draft.tone,
                    draft.evidenceCount.coerceAtLeast(0).toLong(),
                    draft.firstSeenAt.coerceAtLeast(0),
                    draft.lastConfirmedAt.coerceAtLeast(0),
                    now,
                )
                queries.lastInsertedImpressionId().executeAsOne()
            } else {
                queries.updateImpressionByFactKey(
                    draft.characterId.ifBlank { existing.characterId },
                    draft.layer,
                    draft.subject,
                    draft.predicate,
                    draft.value,
                    draft.polarity,
                    draft.confidence,
                    draft.tone,
                    existing.evidenceCount + draft.evidenceCount.coerceAtLeast(0).toLong(),
                    mergeFirstSeen(existing.firstSeenAt, draft.firstSeenAt),
                    maxOf(existing.lastConfirmedAt, draft.lastConfirmedAt),
                    existing.status,
                    now,
                    draft.ownerType,
                    draft.ownerId,
                    draft.factKey,
                )
                existing.id
            }
        }
    }

    suspend fun listForCharacter(
        ownerType: String,
        ownerId: String,
        status: String = ACTIVE,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<Impression> = withContext(Dispatchers.Default) {
        if (limit <= 0) return@withContext emptyList()
        queries.listForCharacter(ownerType, ownerId, status, limit.toLong(), offset.coerceAtLeast(0).toLong())
            .executeAsList()
            .map { row ->
                Impression(
                    id = row.id,
                    ownerType = row.ownerType,
                    ownerId = row.ownerId,
                    characterId = row.characterId,
                    layer = row.layer,
                    subject = row.subject,
                    predicate = row.predicate,
                    value = row.valueText,
                    polarity = row.polarity,
                    factKey = row.factKey,
                    confidence = row.confidence,
                    tone = row.tone,
                    evidenceCount = row.evidenceCount.toInt(),
                    firstSeenAt = row.firstSeenAt,
                    lastConfirmedAt = row.lastConfirmedAt,
                    status = row.status,
                    updatedAt = row.updatedAt,
                )
            }
    }

    suspend fun listAllForOwner(ownerType: String, ownerId: String): List<Impression> =
        withContext(Dispatchers.Default) {
            queries.listAllForOwner(ownerType, ownerId)
                .executeAsList()
                .map { row ->
                    Impression(
                        id = row.id,
                        ownerType = row.ownerType,
                        ownerId = row.ownerId,
                        characterId = row.characterId,
                        layer = row.layer,
                        subject = row.subject,
                        predicate = row.predicate,
                        value = row.valueText,
                        polarity = row.polarity,
                        factKey = row.factKey,
                        confidence = row.confidence,
                        tone = row.tone,
                        evidenceCount = row.evidenceCount.toInt(),
                        firstSeenAt = row.firstSeenAt,
                        lastConfirmedAt = row.lastConfirmedAt,
                        status = row.status,
                        updatedAt = row.updatedAt,
                    )
                }
        }

    suspend fun countByStatus(ownerType: String, ownerId: String, status: String = ACTIVE): Long =
        withContext(Dispatchers.Default) {
            queries.countByStatus(ownerType, ownerId, status).executeAsOne()
        }

    /**
     * T82 分页：`page` 从 1 起，返回「累计到第 page 页」的前 page*pageSize 行（active + frozen），
     * 供 Swift 侧一次替换快照、再按角色分组。`total` 是全表可见总数（不受分页影响）。
     */
    suspend fun listPage(
        ownerType: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): ImpressionPage = withContext(Dispatchers.Default) {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val limit = safeSize.toLong() * safePage
        val total = queries.countVisible(ownerType).executeAsOne()
        val rows = if (total <= 0) emptyList() else queries.listVisiblePage(ownerType, limit).executeAsList()
        ImpressionPage(items = rows.map(::toImpression), total = total)
    }

    /** 已拒绝印象（撤销拒绝管理入口；不进正常列表）。 */
    suspend fun listRejected(
        ownerType: String,
        limit: Int = DEFAULT_REJECTED_LIMIT,
    ): List<Impression> = withContext(Dispatchers.Default) {
        queries.listRejected(ownerType, limit.coerceIn(1, MAX_PAGE_SIZE).toLong())
            .executeAsList()
            .map(::toImpression)
    }

    suspend fun countVisibleByLayer(ownerType: String): Map<String, Long> =
        withContext(Dispatchers.Default) {
            queries.countVisibleByLayer(ownerType)
                .executeAsList()
                .associate { it.layer to it.rowCount }
        }

    suspend fun countAll(ownerType: String): Long = withContext(Dispatchers.Default) {
        queries.countAll(ownerType).executeAsOne()
    }

    suspend fun getById(id: Long): Impression? = withContext(Dispatchers.Default) {
        if (id <= 0) return@withContext null
        queries.selectImpressionById(id).executeAsOneOrNull()?.let(::toImpression)
    }

    /**
     * T83 编辑正文：只改 value 与 updatedAt，status/证据/时间戳不动；同一事务写一条 edit history。
     * 返回 false = 行不存在或正文为空。
     */
    suspend fun edit(id: Long, value: String, now: Long): Boolean = withContext(Dispatchers.Default) {
        if (id <= 0 || value.isBlank()) return@withContext false
        db.transactionWithResult {
            val row = queries.selectImpressionById(id).executeAsOneOrNull()
                ?: return@transactionWithResult false
            queries.updateValueById(value, now, id)
            queries.insertHistory(
                id, ACTION_EDIT, row.value_, value, row.status, row.status, now,
            )
            true
        }
    }

    /**
     * T83 撤销拒绝：rejected → active，写一条 undo_reject history；只有 rejected 行可撤销。
     * 返回 false = 行不存在或不在 rejected 状态。
     */
    suspend fun undoReject(id: Long, now: Long): Boolean = withContext(Dispatchers.Default) {
        if (id <= 0) return@withContext false
        db.transactionWithResult {
            val row = queries.selectImpressionById(id).executeAsOneOrNull()
                ?: return@transactionWithResult false
            if (row.status != STATUS_REJECTED) return@transactionWithResult false
            queries.updateStatusById(STATUS_ACTIVE, now, id)
            queries.insertHistory(
                id, ACTION_UNDO_REJECT, row.value_, row.value_, STATUS_REJECTED, STATUS_ACTIVE, now,
            )
            true
        }
    }

    /** 证据 → 记忆正文摘要与会话 id（证据展示用；记忆已删除的 id 不返回）。 */
    suspend fun memoryRefs(memoryIds: Collection<Long>): Map<Long, ImpressionMemoryRef> =
        withContext(Dispatchers.Default) {
            val ids = memoryIds.filter { it > 0 }.distinct()
            if (ids.isEmpty()) return@withContext emptyMap()
            queries.selectMemoryRefs(ids)
                .executeAsList()
                .associate { it.id to ImpressionMemoryRef(it.id, it.content, it.sessionId) }
        }

    /** 某条印象的纠正审计（按时间升序）；T83 自检与后续页面使用。 */
    suspend fun history(impressionId: Long): List<ImpressionHistoryEntry> =
        withContext(Dispatchers.Default) {
            if (impressionId <= 0) return@withContext emptyList()
            queries.historyForImpression(impressionId).executeAsList().map { row ->
                ImpressionHistoryEntry(
                    id = row.id,
                    impressionId = row.impressionId,
                    action = row.action,
                    beforeValue = row.beforeValue,
                    afterValue = row.afterValue,
                    beforeStatus = row.beforeStatus,
                    afterStatus = row.afterStatus,
                    createdAt = row.createdAt,
                )
            }
        }

    /**
     * 人工裁决（T82）：只改 status（active/rejected/frozen），文本、证据、首见/确认时间保持不变。
     * 复用 (ownerType, ownerId, factKey) 唯一键更新整行；返回是否命中该印象。
     */
    suspend fun setStatus(ownerType: String, ownerId: String, factKey: String, status: String, now: Long): Boolean =
        withContext(Dispatchers.Default) {
            if (status !in STATUSES) return@withContext false
            db.transactionWithResult {
                val existing = queries.selectByFactKey(ownerType, ownerId, factKey).executeAsOneOrNull()
                    ?: return@transactionWithResult false
                queries.updateImpressionByFactKey(
                    existing.characterId,
                    existing.layer,
                    existing.subject,
                    existing.predicate,
                    existing.valueText,
                    existing.polarity,
                    existing.confidence,
                    existing.tone,
                    existing.evidenceCount,
                    existing.firstSeenAt,
                    existing.lastConfirmedAt,
                    status,
                    now,
                    ownerType,
                    ownerId,
                    factKey,
                )
                // T83：人工裁决也要留痕（拒绝/冻结/解冻），状态没变则不写。
                if (existing.status != status) {
                    queries.insertHistory(
                        existing.id,
                        actionFor(existing.status, status),
                        existing.valueText,
                        existing.valueText,
                        existing.status,
                        status,
                        now,
                    )
                }
                true
            }
        }

    /** 追加证据：evidenceCount 累加，同时刷新最近确认时间；返回实际更新的行数。 */
    suspend fun markEvidence(count: Int, lastConfirmedAt: Long, updatedAt: Long, id: Long): Long =
        withContext(Dispatchers.Default) {
            if (id <= 0) return@withContext 0L
            queries.markEvidence(count.coerceAtLeast(0).toLong(), lastConfirmedAt, updatedAt, id).value
        }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.Default) {
        queries.deleteAllImpressions()
        Unit
    }

    private fun mergeFirstSeen(existing: Long, incoming: Long): Long = when {
        existing <= 0 -> incoming.coerceAtLeast(0)
        incoming <= 0 -> existing
        else -> minOf(existing, incoming)
    }

    private fun actionFor(before: String, after: String): String = when {
        before == STATUS_REJECTED && after == STATUS_ACTIVE -> ACTION_UNDO_REJECT
        after == STATUS_REJECTED -> ACTION_REJECT
        after == STATUS_FROZEN -> ACTION_FREEZE
        before == STATUS_FROZEN && after == STATUS_ACTIVE -> ACTION_UNFREEZE
        else -> ACTION_STATUS_CHANGE
    }

    private fun toImpression(row: ImpressionRow): Impression = Impression(
        id = row.id,
        ownerType = row.ownerType,
        ownerId = row.ownerId,
        characterId = row.characterId,
        layer = row.layer,
        subject = row.subject,
        predicate = row.predicate,
        value = row.value_,
        polarity = row.polarity,
        factKey = row.factKey,
        confidence = row.confidence,
        tone = row.tone,
        evidenceCount = row.evidenceCount.toInt(),
        firstSeenAt = row.firstSeenAt,
        lastConfirmedAt = row.lastConfirmedAt,
        status = row.status,
        updatedAt = row.updatedAt,
    )

    private companion object {
        const val ACTIVE = "active"
        const val STATUS_ACTIVE = "active"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_FROZEN = "frozen"
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_PAGE_SIZE = 20
        const val DEFAULT_REJECTED_LIMIT = 100
        const val MAX_PAGE_SIZE = 200
        const val ACTION_EDIT = "edit"
        const val ACTION_REJECT = "reject"
        const val ACTION_UNDO_REJECT = "undo_reject"
        const val ACTION_FREEZE = "freeze"
        const val ACTION_UNFREEZE = "unfreeze"
        const val ACTION_STATUS_CHANGE = "status_change"
        val STATUSES = setOf("active", "rejected", "frozen")
    }
}