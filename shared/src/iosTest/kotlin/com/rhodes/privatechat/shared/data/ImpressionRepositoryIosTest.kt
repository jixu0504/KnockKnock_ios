package com.rhodes.privatechat.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.domain.memory.ImpressionDraft
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T81：impressions 表在真实 SQLite 上的读写（NativeSqliteDriver + RhodesDatabase.Schema）。
 *
 * 覆盖：同 factKey 二次 upsert 只保留一行且证据累加、分页与排序、按状态计数、
 * markEvidence 增量、listAllForOwner 与 deleteAll。
 */
class ImpressionRepositoryIosTest {

    private val now = 1_800_000_000_000L
    private val key = "fact:positive:用户|prefer|川菜"

    private fun draft(
        factKey: String = key,
        layer: String = "ongoing",
        evidenceCount: Int = 1,
        confidence: Double = 0.7,
        characterId: String = "",
        value: String = "川菜",
    ) = ImpressionDraft(
        ownerType = "operator",
        ownerId = "op1",
        characterId = characterId,
        layer = layer,
        subject = "用户",
        predicate = "prefer",
        value = value,
        polarity = "positive",
        factKey = factKey,
        confidence = confidence,
        tone = "positive",
        evidenceCount = evidenceCount,
        firstSeenAt = now,
        lastConfirmedAt = now,
        legacyKey = false,
    )

    @OptIn(ExperimentalForeignApi::class)
    private fun withRepository(dbName: String, block: suspend (ImpressionRepository) -> Unit) = runBlocking {
        val driver = NativeSqliteDriver(
            RhodesDatabase.Schema,
            "${dbName}_${platform.posix.getpid()}_$now.db",
        )
        try {
            block(ImpressionRepository(RhodesDatabase(driver)))
        } finally {
            driver.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun withDatabase(dbName: String, block: suspend (RhodesDatabase, ImpressionRepository) -> Unit) =
        runBlocking {
            val driver = NativeSqliteDriver(
                RhodesDatabase.Schema,
                "${dbName}_${platform.posix.getpid()}_$now.db",
            )
            val db = RhodesDatabase(driver)
            try {
                block(db, ImpressionRepository(db))
            } finally {
                driver.close()
            }
        }

    @Test
    fun secondUpsertOnSameFactKeyKeepsOneRowAndAccumulatesEvidence() = withRepository("impression_upsert") { repository ->
        val first = repository.upsert(draft(evidenceCount = 1), now)
        assertTrue(first > 0)

        val second = repository.upsert(
            draft(layer = "long_term", evidenceCount = 2, confidence = 0.9, characterId = "char1", value = "川菜和火锅")
                .copy(firstSeenAt = now + 500, lastConfirmedAt = now + 1_000),
            now + 1_000,
        )
        assertEquals(first, second, "同 factKey 复用同一行 id")

        val rows = repository.listForCharacter("operator", "op1")
        assertEquals(1, rows.size, "同 factKey 只保留一行")
        val row = rows.single()
        assertEquals(3, row.evidenceCount, "证据数累加 1 + 2")
        assertEquals("long_term", row.layer, "层级取最近一次写入")
        assertEquals("char1", row.characterId)
        assertEquals("川菜和火锅", row.value)
        assertTrue(abs(0.9 - row.confidence) < 1e-9)
        assertEquals(now, row.firstSeenAt, "首见时间保留较早的一条（now 与 now+500 → now）")
        assertEquals(now + 1_000, row.lastConfirmedAt)
        assertEquals(now + 1_000, row.updatedAt)

        // 不同 owner / 不同 factKey 各自成行
        val otherOwner = repository.upsert(draft().copy(ownerId = "op2"), now + 2_000)
        assertTrue(otherOwner != first && otherOwner > 0, "另一 owner 是新行")
        repository.upsert(draft(factKey = "fact:positive:用户|own|猫"), now + 3_000)
        assertEquals(2, repository.listForCharacter("operator", "op1").size)
        assertEquals(1, repository.listForCharacter("operator", "op2").size)
    }

    @Test
    fun listForCharacterPaginatesByLayerThenRecentConfirm() = withRepository("impression_paging") { repository ->
        repository.upsert(draft(factKey = "fact:positive:用户|prefer|川菜"), now)
        repository.upsert(draft(factKey = "fact:positive:用户|own|猫", layer = "long_term"), now + 1_000)
        repository.upsert(draft(factKey = "fact:negative:用户|dislike|香菜", layer = "interaction"), now + 2_000)

        val all = repository.listForCharacter("operator", "op1", limit = 10)
        assertEquals(
            listOf("fact:positive:用户|own|猫", "fact:positive:用户|prefer|川菜", "fact:negative:用户|dislike|香菜"),
            all.map { it.factKey },
            "long_term → ongoing → interaction",
        )

        assertEquals(1, repository.listForCharacter("operator", "op1", limit = 1).size)
        assertEquals(
            listOf("fact:positive:用户|prefer|川菜", "fact:negative:用户|dislike|香菜"),
            repository.listForCharacter("operator", "op1", limit = 2, offset = 1).map { it.factKey },
            "offset 跨页不重不漏",
        )
        assertEquals(emptyList(), repository.listForCharacter("operator", "op1", limit = 0))
        assertEquals(emptyList(), repository.listForCharacter("operator", "op1", limit = 5, offset = 10))
    }

    @Test
    fun countByStatusAndMarkEvidenceTrackState() = withRepository("impression_evidence") { repository ->
        val id = repository.upsert(draft(evidenceCount = 1), now)
        repository.upsert(draft(factKey = "fact:positive:用户|own|猫"), now)
        assertEquals(2L, repository.countByStatus("operator", "op1", "active"))
        assertEquals(0L, repository.countByStatus("operator", "op1", "rejected"))
        assertEquals(0L, repository.countByStatus("operator", "op2", "active"))

        assertEquals(1L, repository.markEvidence(count = 2, lastConfirmedAt = now + 5_000, updatedAt = now + 6_000, id = id))
        val marked = repository.listAllForOwner("operator", "op1").first { it.id == id }
        assertEquals(3, marked.evidenceCount)
        assertEquals(now + 5_000, marked.lastConfirmedAt)
        assertEquals(now + 6_000, marked.updatedAt)

        assertEquals(0L, repository.markEvidence(count = 1, lastConfirmedAt = now, updatedAt = now, id = 9_999L), "不存在的行不影响")

        repository.deleteAll()
        assertEquals(0L, repository.countByStatus("operator", "op1", "active"))
        assertEquals(emptyList(), repository.listAllForOwner("operator", "op1"))
    }

    @Test
    fun listPageReturnsGlobalTotalAndHasMore() = withRepository("impression_page_total") { repository ->
        repository.upsert(draft(factKey = "fact:page:op1:long", layer = "long_term"), now)
        repository.upsert(draft(factKey = "fact:page:op1:0"), now + 1)
        repository.upsert(draft(factKey = "fact:page:op1:1"), now + 2)
        repository.upsert(draft().copy(ownerId = "op2", factKey = "fact:page:op2:0"), now + 100)
        repository.upsert(draft().copy(ownerId = "op2", factKey = "fact:page:op2:1"), now + 200)

        val first = repository.listPage("operator", page = 1, pageSize = 2)
        assertEquals(2, first.items.size, "第一页只返回 pageSize 行")
        assertEquals(5L, first.total, "total 是全表可见总数")
        assertTrue(first.hasMore, "还有下一页")

        val third = repository.listPage("operator", page = 3, pageSize = 2)
        assertEquals(5, third.items.size, "累计到第 3 页返回全部 5 行")
        assertEquals(5L, third.total)
        assertTrue(!third.hasMore, "第 3 页已无更多")
        assertEquals(5, third.items.map { it.id }.distinct().size, "跨页不重不漏")
        assertEquals("long_term", third.items.first().layer, "层深优先：long_term 排最前")

        // 拒绝一行后，可见总数减 1，rejected 列表能读到它
        val rejectedRow = third.items.first()
        assertTrue(repository.setStatus("operator", rejectedRow.ownerId, rejectedRow.factKey, "rejected", now + 300))
        assertEquals(4L, repository.listPage("operator", page = 1, pageSize = 20).total)
        assertEquals(listOf(rejectedRow.id), repository.listRejected("operator").map { it.id })

        // 拒绝后 long_term 不再出现在可见列表
        assertEquals("ongoing", repository.listPage("operator", page = 1, pageSize = 20).items.first().layer)
    }

    @Test
    fun editOnlyRewritesValueAndWritesHistory() = withRepository("impression_edit_history") { repository ->
        val id = repository.upsert(draft(evidenceCount = 2), now)
        val before = repository.listAllForOwner("operator", "op1").single()
        val editedAt = now + 10_000

        assertTrue(repository.edit(id, "火锅", editedAt))
        val after = repository.listAllForOwner("operator", "op1").single()
        assertEquals("火锅", after.value)
        assertEquals(editedAt, after.updatedAt, "只刷新 updatedAt")
        assertEquals(before.status, after.status, "status 不变")
        assertEquals(before.evidenceCount, after.evidenceCount, "证据数不变")
        assertEquals(before.lastConfirmedAt, after.lastConfirmedAt, "最近确认时间不变")

        val history = repository.history(id)
        assertEquals(1, history.size)
        val record = history.single()
        assertEquals("edit", record.action)
        assertEquals("川菜", record.beforeValue)
        assertEquals("火锅", record.afterValue)
        assertEquals("active", record.beforeStatus)
        assertEquals("active", record.afterStatus)
        assertEquals(editedAt, record.createdAt)

        assertTrue(!repository.edit(id, "   ", editedAt + 1), "空正文拒绝")
        assertTrue(!repository.edit(9_999L, "不存在", editedAt + 1), "不存在的印象不写 history")
        assertEquals(1, repository.history(id).size)
    }

    @Test
    fun undoRejectRestoresActiveAndWritesHistory() = withRepository("impression_undo_reject") { repository ->
        val id = repository.upsert(draft(), now)
        assertTrue(repository.setStatus("operator", "op1", key, "rejected", now + 1_000))
        assertEquals("rejected", repository.listRejected("operator").single().status)
        assertEquals(0L, repository.listPage("operator").total, "拒绝后可见总数为 0")
        assertEquals(listOf("reject"), repository.history(id).map { it.action })

        assertTrue(repository.undoReject(id, now + 2_000))
        val restored = repository.listPage("operator", page = 1, pageSize = 20).items.single()
        assertEquals("active", restored.status)
        assertEquals(now + 2_000, restored.updatedAt)
        assertEquals(emptyList(), repository.listRejected("operator"))
        assertEquals(1L, repository.listPage("operator").total)

        val history = repository.history(id)
        assertEquals(listOf("reject", "undo_reject"), history.map { it.action })
        val undo = history.last()
        assertEquals("川菜", undo.beforeValue)
        assertEquals("川菜", undo.afterValue)
        assertEquals("rejected", undo.beforeStatus)
        assertEquals("active", undo.afterStatus)

        assertTrue(!repository.undoReject(id, now + 3_000), "非 rejected 不可撤销")
        assertEquals(2, repository.history(id).size, "失败的撤销不写 history")
    }

    @Test
    fun freezeAndUnfreezeAlsoWriteHistory() = withRepository("impression_freeze_history") { repository ->
        val id = repository.upsert(draft(), now)
        assertTrue(repository.setStatus("operator", "op1", key, "frozen", now + 1_000))
        assertTrue(repository.setStatus("operator", "op1", key, "active", now + 2_000))
        assertEquals(listOf("freeze", "unfreeze"), repository.history(id).map { it.action })
        assertEquals(listOf("active->frozen", "frozen->active"), repository.history(id).map { "${it.beforeStatus}->${it.afterStatus}" })

        // 状态没变不重复留痕
        assertTrue(repository.setStatus("operator", "op1", key, "active", now + 3_000))
        assertEquals(2, repository.history(id).size)
    }

    @Test
    fun memoryRefsReturnContentAndSessionId() = withDatabase("impression_memory_refs") { db, repository ->
        val id = repository.upsert(draft(), now)
        db.memoryItemsQueries.insertMemoryItem(
            ownerType = "operator",
            ownerId = "op1",
            memoryLevel = "L2",
            memoryType = "preference_expression",
            sourceKind = "PRIVATE_CHAT",
            sourceRefId = "src-1",
            sessionId = "session-1",
            content = "用户很喜欢川菜，尤其是水煮鱼",
            nickname = "用户",
            importance = 60,
            privacy = "private",
            unmetNeed = 0,
            location = null,
            emotionValence = "neutral",
            eventTime = null,
            createdAt = now,
            updatedAt = now,
            expiresAt = Long.MAX_VALUE,
            status = "active",
            scheduledTime = null,
            action = "",
            careType = "none",
            topicKey = "taste:spicy",
            sourceActor = "用户",
            sourceTarget = "op1",
            lastUsedAt = 0,
            usedCount = 0,
            confidence = 0.8,
            rawJson = "",
            vectorId = "",
        )
        val memoryId = db.memoryItemsQueries.getAllMemoryItems().executeAsList().single().id
        val refs = repository.memoryRefs(listOf(memoryId, 9_999L))
        assertEquals(1, refs.size)
        assertEquals("用户很喜欢川菜，尤其是水煮鱼", refs.getValue(memoryId).content)
        assertEquals("session-1", refs.getValue(memoryId).sessionId)
        assertEquals(emptyMap(), repository.memoryRefs(emptyList()))
        assertEquals(id, repository.listPage("operator").items.single().id)
    }
}
