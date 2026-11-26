package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupContentSelection
import com.rhodes.privatechat.shared.backup.BackupPortableSettings
import com.rhodes.privatechat.shared.backup.BackupRestoreOptions
import com.rhodes.privatechat.shared.backup.BackupRestoreStage
import com.rhodes.privatechat.shared.backup.BackupValidationResult
import com.rhodes.privatechat.shared.backup.InMemoryMediaSink
import com.rhodes.privatechat.shared.backup.TestBackupEnv
import com.rhodes.privatechat.shared.db.Chat_archives
import com.rhodes.privatechat.shared.db.Chat_history_segments
import com.rhodes.privatechat.shared.db.Diaries
import com.rhodes.privatechat.shared.db.Dispatch_records
import com.rhodes.privatechat.shared.db.Gift_records
import com.rhodes.privatechat.shared.db.Knowledge_base_chunks
import com.rhodes.privatechat.shared.db.Knowledge_bases
import com.rhodes.privatechat.shared.db.Memory_anchors
import com.rhodes.privatechat.shared.db.Memory_items
import com.rhodes.privatechat.shared.db.Memories
import com.rhodes.privatechat.shared.db.Moment_comments
import com.rhodes.privatechat.shared.db.Moment_likes
import com.rhodes.privatechat.shared.db.Moments
import com.rhodes.privatechat.shared.db.Operator_knowledge_bases
import com.rhodes.privatechat.shared.db.Operators
import com.rhodes.privatechat.shared.db.Relationships
import com.rhodes.privatechat.shared.db.Shared_experience_participants
import com.rhodes.privatechat.shared.db.Shared_experiences
import com.rhodes.privatechat.shared.model.ChatArchive
import com.rhodes.privatechat.shared.model.ChatHistorySegment
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Diary
import com.rhodes.privatechat.shared.model.DispatchRecord
import com.rhodes.privatechat.shared.model.GiftRecord
import com.rhodes.privatechat.shared.model.MahjongSave
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.model.MemoryAnchor
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.Moment
import com.rhodes.privatechat.shared.model.MomentComment
import com.rhodes.privatechat.shared.model.MomentLike
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.model.SharedExperience
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T31 全类目金样验收（规格卡验收 #1-#16；同一份 commonTest 跑 JVM 与 iOS 双路径）。
 *
 * 断言口径：以 `FullBackupReader` 解出的**完整 payload**为期望（媒体域经 `FullBackupMediaRewriter`
 * 同样变换），DB 侧用 SQLDelight 既有 select-all 查询逐表逐字段比对；自增表 id 不参与断言，
 * 按业务字段/映射结构断言（与 Android `RestoreDatabaseExecutor` 一致）。
 */
abstract class FullBackupGoldenTest {

    /** 平台源集提供：androidUnitTest = JdbcSqliteDriver 内存库；iosTest = NativeSqliteDriver。 */
    protected abstract fun createEnv(): TestBackupEnv

    private val expectedIssueCounts = mapOf(
        "full-memories-v2.zip" to 2,
        "full-social-v2.zip" to 3,
    )

    private suspend fun restore(
        env: TestBackupEnv,
        bytes: ByteArray,
        options: BackupRestoreOptions = BackupRestoreOptions(),
        sink: InMemoryMediaSink = InMemoryMediaSink(),
    ): Pair<BackupFullRestoreResult, InMemoryMediaSink> {
        val coordinator = BackupFullRestoreCoordinator(env.repository, env.settings)
        return coordinator.restore(bytes, sink, options = options) to sink
    }

    private fun assertRestored(result: BackupFullRestoreResult) {
        assertTrue(result.success, "恢复失败（${result.stage}）：${result.reason}")
        assertEquals(BackupRestoreStage.COMPLETED, result.stage)
        val check = result.selfCheck
        assertNotNull(check, "恢复后自检缺失")
        assertTrue(check.passed, "恢复后自检未通过：${check.mismatches}")
    }

    // ------------------------------------------------------------------ #1 金样校验

    @Test
    fun acceptance1_allGoldenPackagesValidate() = runBlocking {
        val reader = FullBackupReader()
        FullBackupGoldens.ALL_PACKAGES.forEach { name ->
            val bytes = FullBackupGoldens.load(name)
            val valid = reader.validate(bytes)
            assertTrue(valid is BackupValidationResult.Valid, "$name 应校验通过，实际 $valid")
            val archive = reader.read(bytes)
            assertEquals(expectedIssueCounts[name] ?: 0, archive.issues.size,
                "$name 预期孤儿 issue 数不符：${archive.issues.map { it.code }}")
            assertEquals("full_backup", archive.payload.content.type)
        }
    }

    // ------------------------------------------------------------------ #2 完整模型解码

    @Test
    fun acceptance2_fullReaderDecodesAllCategories() = runBlocking {
        val archive = FullBackupReader().read(FullBackupGoldens.load("full-all-v2.zip"))
        val payload = archive.payload
        assertEquals(2, payload.content.operators?.size)
        assertEquals(2, payload.content.relationships?.size)
        assertEquals(3, payload.content.sessions?.size)
        assertEquals(12, payload.content.messages?.size)
        assertEquals(4, payload.displayEvents.size)
        assertEquals(2, payload.chatArchives.size)
        assertEquals(1, payload.chatHistorySegments.size)
        assertEquals(3, payload.content.memories?.size)
        assertEquals(3, payload.content.anchors?.size)
        assertEquals(3, payload.content.memoryItems?.size)
        assertEquals(1, payload.content.knowledgeBases?.size)
        assertEquals(2, payload.content.knowledgeBaseChunks?.size)
        assertEquals(1, payload.content.operatorKnowledgeBaseAssignments?.size)
        assertEquals(2, payload.content.moments?.size)
        assertEquals(3, payload.content.momentComments?.size)
        assertEquals(2, payload.content.momentLikes?.size)
        assertEquals(2, payload.content.diaries?.size)
        assertEquals(2, payload.sharedExperiences.size)
        assertEquals(3, payload.sharedExperienceParticipants.size)
        assertEquals(2, payload.giftRecords.size)
        assertEquals(2, payload.dispatchRecords.size)
        assertEquals("current", payload.mahjongSave?.id)
        assertEquals(3, payload.media.size)
        assertTrue(payload.content.settings.orEmpty().isNotEmpty())
        assertEquals("金样演示员", payload.content.operators!!.first { it.id == "golden" }.name)
        assertEquals("档案手册", payload.content.knowledgeBases!!.first().name)
        assertEquals(MemoryLevel.L3, payload.content.memoryItems!!.first { it.id == 3L }.memoryLevel)
    }

    // ------------------------------------------------------------------ #3 roles/chats 字段级往返

    @Test
    fun acceptance3_rolesAndChatsRoundTrip() = runBlocking {
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-all-v2.zip"))
            val (result, sink) = restore(env, FullBackupGoldens.load("full-all-v2.zip"))
            assertRestored(result)
            val expected = FullBackupMediaRewriter.rewrite(archive.payload, sink.urls)

            val dbOps = FullBackupDb.operators(env.database).associateBy { it.id }
            assertEquals(expected.content.operators!!.size, dbOps.size)
            expected.content.operators!!.forEach { op ->
                val row = dbOps.getValue(op.id)
                assertEquals(op.name, row.name, "operators.name")
                assertEquals(op.title, row.title)
                assertEquals(op.description, row.description)
                assertEquals(op.gender, row.gender)
                assertEquals(op.avatarUri, row.avatarUri, "operators.avatarUri 应经媒体重写")
                assertEquals(op.location, row.location)
                assertEquals(op.activity, row.activity)
                assertEquals(op.emotion, row.emotion)
                assertEquals(op.intimacy.toLong(), row.intimacy)
                assertEquals(op.privatePrompt, row.privatePrompt)
                assertEquals(op.groupPrompt, row.groupPrompt)
                assertEquals(op.memoryInjection, row.memoryInjection)
                assertEquals(op.userRelation, row.userRelation)
                assertEquals(op.lmb.toLong(), row.lmb)
                assertEquals(op.attack.toDouble(), row.attack, 1e-6)
                assertEquals(op.defense.toDouble(), row.defense, 1e-6)
                assertEquals(op.meldPref, row.meldPref)
                assertEquals(op.activityLevel.toDouble(), row.activityLevel, 1e-6)
                assertEquals(op.voiceName, row.voiceName)
                assertEquals(op.voiceSpeed, row.voiceSpeed)
                assertEquals(op.voicePitch, row.voicePitch)
            }

            val expectedRelationships = expected.content.relationships.orEmpty().map {
                listOf(it.operatorId, it.relatedOperatorId, it.relatedOperatorName, it.type,
                    it.intimacy.toString(), it.isPreset.toString(), it.note)
            }.sortedBy { it.joinToString("\u0000") }
            assertEquals(expectedRelationships, FullBackupDb.relationships(env.database).map(::relTuple).sortedBy { it.joinToString("\u0000") })

            val dbSessions = FullBackupDb.sessions(env.database).associateBy { it.id }
            expected.content.sessions!!.forEach { session ->
                val row = dbSessions.getValue(session.id)
                assertEquals(session.operatorId, row.operatorId)
                assertEquals(session.operatorName, row.operatorName)
                assertEquals(session.lastMessage, row.lastMessage)
                assertEquals(session.lastTime, row.lastTime)
                assertEquals(session.mode, row.mode)
                assertEquals(session.isPinned, row.isPinned != 0L)
                assertEquals(session.unreadCount.toLong(), row.unreadCount)
                assertEquals(session.members, row.members)
                assertEquals(session.rules, row.rules)
                assertEquals(session.avatarUri, row.avatarUri, "sessions.avatarUri 应经媒体重写")
                assertEquals(session.mutedMembers, row.mutedMembers)
            }

            val expectedMessages = expected.content.messages.orEmpty().map(::messageTuple).sortedBy { it.joinToString("\u0000") }
            assertEquals(expectedMessages, FullBackupDb.messages(env.database).map(::messageTuple).sortedBy { it.joinToString("\u0000") })
            // 保留消息 id（原样插入，oracle :99-101）
            assertEquals(expected.content.messages!!.map { it.id }.sorted(), FullBackupDb.messages(env.database).map { it.id }.sorted())

            assertEquals(
                expected.displayEvents.map {
                    listOf(it.messageId, it.segmentIndex, it.sessionId, it.revealOrder)
                }.sortedBy { it.toString() },
                FullBackupDb.displayEvents(env.database).map {
                    listOf(it.messageId, it.segmentIndex.toInt(), it.sessionId, it.revealOrder)
                }.sortedBy { it.toString() },
            )
            assertEquals(
                expected.chatArchives.map(::archiveTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.archives(env.database).map { row ->
                    listOf(row.id, row.sessionId, row.operatorId, row.title, row.note, row.mode,
                        row.messagesJson, row.summary, row.stateJson, row.status, row.createdAt.toString(), row.updatedAt.toString())
                }.sortedBy { it.joinToString("\u0000") },
            )
            assertEquals(
                expected.chatHistorySegments.map(::historyTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.historySegments(env.database).map { row ->
                    listOf(row.id, row.sessionId, row.title, row.reason, row.messagesJson, row.createdAt.toString())
                }.sortedBy { it.joinToString("\u0000") },
            )
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #4 memories 三层 + 锚点 + memoryItems

    @Test
    fun acceptance4_memoriesRoundTrip() = runBlocking {
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-all-v2.zip"))
            assertRestored(restore(env, FullBackupGoldens.load("full-all-v2.zip")).first)
            val expected = archive.payload

            assertEquals(
                expected.content.memories.orEmpty().map(::memoryTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.memories(env.database).map(::memoryRowTuple).sortedBy { it.joinToString("\u0000") },
            )
            assertEquals(
                expected.content.anchors.orEmpty().map(::anchorTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.anchors(env.database).map(::anchorRowTuple).sortedBy { it.joinToString("\u0000") },
            )

            val dbMoments = FullBackupDb.moments(env.database)
            val dbComments = FullBackupDb.comments(env.database)
            val momentIdByContent = dbMoments.associate { it.content to it.id }
            val commentIdByContent = dbComments.associate { it.content to it.id }
            val dbItems = FullBackupDb.memoryItems(env.database)
            assertEquals(expected.content.memoryItems.orEmpty().size, dbItems.size)
            expected.content.memoryItems.orEmpty().forEach { item ->
                val row = dbItems.first { it.content == item.content }
                assertEquals(item.ownerType, row.ownerType)
                assertEquals(item.ownerId, row.ownerId)
                assertEquals(item.memoryLevel.name, row.memoryLevel)
                assertEquals(item.memoryType, row.memoryType)
                assertEquals(item.sourceKind.name, row.sourceKind)
                assertEquals(item.sessionId, row.sessionId)
                assertEquals(item.importance.toLong(), row.importance)
                assertEquals(item.importance.toLong(), row.importance)
                assertEquals(item.unmetNeed, row.unmetNeed != 0L)
                assertEquals(item.emotionValence, row.emotionValence)
                assertEquals(item.createdAt, row.createdAt)
                assertEquals(item.updatedAt, row.updatedAt)
                assertEquals(item.expiresAt, row.expiresAt)
                assertEquals(item.action, row.action)
                assertEquals(item.careType, row.careType)
                assertEquals(item.topicKey, row.topicKey)
                assertEquals(item.sourceActor, row.sourceActor)
                assertEquals(item.sourceTarget, row.sourceTarget)
                assertEquals(item.usedCount.toLong(), row.usedCount)
                assertEquals(item.confidence, row.confidence, 1e-9)
                assertEquals(item.rawJson, row.rawJson)
                // 派生归一：L1 保留 status，L2/L3 archived；vectorId 清空。
                val expectedStatus = if (item.memoryLevel == MemoryLevel.L1) item.status else "archived"
                assertEquals(expectedStatus, row.status)
                assertEquals("", row.vectorId)
                // sourceRefId 重映射（MOMENT/MOMENT_COMMENT → 本次新 id）。
                val expectedRef = when (item.sourceKind.name) {
                    "MOMENT" -> momentIdByContent.getValue("今天整理完档案了。").toString()
                    "MOMENT_COMMENT" -> commentIdByContent.getValue("辛苦了。").toString()
                    else -> item.sourceRefId
                }
                assertEquals(expectedRef, row.sourceRefId, "memoryItems.sourceRefId 应映射到新 id")
            }
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #5 social + 引用重映射 + 计数回填

    @Test
    fun acceptance5_socialRoundTripAndReferenceMapping() = runBlocking {
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-all-v2.zip"))
            assertRestored(restore(env, FullBackupGoldens.load("full-all-v2.zip")).first)
            val expected = archive.payload

            val dbMoments = FullBackupDb.moments(env.database)
            assertEquals(expected.content.moments.orEmpty().size, dbMoments.size)
            val momentByContent = dbMoments.associateBy { it.content }
            expected.content.moments.orEmpty().forEach { moment ->
                val row = momentByContent.getValue(moment.content)
                assertEquals(moment.operatorId, row.operatorId)
                assertEquals(moment.operatorName, row.operatorName)
                assertEquals(moment.isUserPost, row.isUserPost != 0L)
                assertEquals(moment.mentionedOperatorIds, row.mentionedOperatorIds)
                assertEquals(moment.createdAt, row.createdAt)
                // 计数按实际行数回填（oracle :137-138）
                val likeCount = expected.content.momentLikes.orEmpty().count { it.momentId == moment.id }
                val commentCount = expected.content.momentComments.orEmpty().count { it.momentId == moment.id }
                assertEquals(likeCount.toLong(), row.likeCount)
                assertEquals(commentCount.toLong(), row.commentCount)
            }
            val dbComments = FullBackupDb.comments(env.database)
            assertEquals(expected.content.momentComments.orEmpty().size, dbComments.size)
            expected.content.momentComments.orEmpty().forEach { comment ->
                val row = dbComments.first { it.content == comment.content }
                assertEquals(momentByContent.getValue(expected.content.moments!!.first { it.id == comment.momentId }.content).id, row.momentId)
                assertEquals(comment.operatorId, row.operatorId)
                assertEquals(comment.operatorName, row.operatorName)
                assertEquals(comment.replyToName, row.replyToName)
                assertEquals(comment.createdAt, row.createdAt)
                assertEquals(comment.isRead, row.isRead != 0L)
                if (comment.parentCommentId == 0L) {
                    assertEquals(0L, row.parentCommentId)
                } else {
                    val parentContent = expected.content.momentComments!!.first { it.id == comment.parentCommentId }.content
                    assertEquals(dbComments.first { it.content == parentContent }.id, row.parentCommentId, "父评论应重映射")
                }
            }
            val likes = FullBackupDb.likes(env.database)
            assertEquals(expected.content.momentLikes.orEmpty().size, likes.size)
            expected.content.momentLikes.orEmpty().forEach { like ->
                val momentContent = expected.content.moments!!.first { it.id == like.momentId }.content
                val row = likes.first { it.operatorId == like.operatorId }
                assertEquals(momentByContent.getValue(momentContent).id, row.momentId, "likes.momentId 应重映射")
                assertEquals(like.operatorName, row.operatorName)
                assertEquals(like.createdAt, row.createdAt)
            }
            assertEquals(
                expected.content.diaries.orEmpty().map(::diaryTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.diaries(env.database).map(::diaryRowTuple).sortedBy { it.joinToString("\u0000") },
            )
            val dbExperiences = FullBackupDb.experiences(env.database)
            assertEquals(expected.sharedExperiences.size, dbExperiences.size)
            assertEquals(
                expected.sharedExperiences.map(::experienceTuple).sortedBy { it.joinToString("\u0000") },
                dbExperiences.map(::experienceRowTuple).sortedBy { it.joinToString("\u0000") },
            )
            val dbParticipants = FullBackupDb.participants(env.database)
            assertEquals(expected.sharedExperienceParticipants.size, dbParticipants.size)
            val experienceContentById = expected.sharedExperiences.associate { it.id to it.content }
            expected.sharedExperienceParticipants.forEach { participant ->
                val content = experienceContentById.getValue(participant.experienceId)
                val dbExperienceId = dbExperiences.first { it.content == content }.id
                assertTrue(dbParticipants.any {
                    it.experienceId == dbExperienceId && it.operatorId == participant.operatorId && it.role == participant.role
                }, "participants 应映射到新 experience id")
            }
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #6 knowledge + extras + settings

    @Test
    fun acceptance6_knowledgeExtrasAndSettingsRoundTrip() = runBlocking {
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-all-v2.zip"))
            val restored = restore(env, FullBackupGoldens.load("full-all-v2.zip"))
            assertRestored(restored.first)
            val expected = FullBackupMediaRewriter.rewrite(archive.payload, restored.second.urls)

            val dbKbs = FullBackupDb.knowledgeBases(env.database)
            assertEquals(expected.content.knowledgeBases!!.size, dbKbs.size)
            expected.content.knowledgeBases!!.forEach { book ->
                val row = dbKbs.first { it.id == book.id }
                assertEquals(book.name, row.name)
                assertEquals(book.rawContent, row.rawContent)
                assertEquals(book.sourceFileName, row.sourceFileName)
                assertEquals(book.sourceFormat, row.sourceFormat)
                assertEquals(book.sourceType, row.sourceType)
                assertEquals(book.chunkingMode, row.chunkingMode)
                assertEquals(book.createdAt, row.createdAt)
                assertEquals(book.updatedAt, row.updatedAt)
                // 派生归一（oracle :82）
                assertEquals("pending", row.indexStatus)
                assertEquals("", row.indexedEmbeddingSignature)
            }
            assertEquals(
                expected.content.knowledgeBaseChunks.orEmpty().map(::chunkTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.chunks(env.database).map(::chunkRowTuple).sortedBy { it.joinToString("\u0000") },
            )
            FullBackupDb.chunks(env.database).forEach { row ->
                assertEquals(0L, row.indexedAt)
                assertEquals("", row.indexError)
            }
            assertEquals(
                expected.content.operatorKnowledgeBaseAssignments.orEmpty().map {
                    listOf(it.operatorId, it.knowledgeBaseId, it.enabled.toString(), it.sortOrder.toString())
                }.sortedBy { it.joinToString("\u0000") },
                FullBackupDb.assignments(env.database).map {
                    listOf(it.operatorId, it.knowledgeBaseId, (it.enabled != 0L).toString(), it.sortOrder.toString())
                }.sortedBy { it.joinToString("\u0000") },
            )
            assertEquals(
                expected.giftRecords.map(::giftTuple).sortedBy { it.joinToString("\u0000") },
                FullBackupDb.gifts(env.database).map(::giftRowTuple).sortedBy { it.joinToString("\u0000") },
            )
            val dbDispatches = FullBackupDb.dispatches(env.database)
            assertEquals(expected.dispatchRecords.size, dbDispatches.size)
            expected.dispatchRecords.forEach { dispatch ->
                val row = dbDispatches.first { it.id == dispatch.id }
                assertEquals(dispatch.taskType, row.taskType)
                assertEquals(dispatch.durationHours.toLong(), row.durationHours)
                assertEquals(dispatch.budget.toLong(), row.budget)
                assertEquals(dispatch.netProfit.toLong(), row.netProfit)
                assertEquals(dispatch.operatorIds, row.operatorIds)
                assertEquals(dispatch.logChain, row.logChain)
                val expectedStatus = if (dispatch.status == "active" || dispatch.status == "generating") "paused" else dispatch.status
                assertEquals(expectedStatus, row.status, "dispatch status 应归一")
                assertEquals(dispatch.startTime, row.startTime)
                assertEquals(dispatch.endTime, row.endTime)
                assertEquals(dispatch.totalSegments.toLong(), row.totalSegments)
                assertEquals(dispatch.segmentInterval, row.segmentInterval)
                assertEquals(dispatch.items, row.items)
            }
            val mahjong = FullBackupDb.mahjong(env.database)
            assertNotNull(mahjong)
            assertEquals(expected.mahjongSave!!.saveJson, mahjong.saveJson)
            assertEquals(expected.mahjongSave.ruleType, mahjong.ruleType)
            assertEquals(expected.mahjongSave.savedAt, mahjong.savedAt)

            // 设置全量：非 hidden_ids 键逐值相等（snapshot 口径）。
            val snapshot = BackupPortableSettings().snapshot(env.repository, env.settings)
            expected.content.settings.orEmpty().forEach { (key, encoded) ->
                if (key == "hidden_ids") return@forEach
                assertEquals(encoded, snapshot[key], "settings.$key")
            }
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #7 派生字段归一 + 运行表重置

    @Test
    fun acceptance7_derivedNormalizationAndRuntimeReset() = runBlocking {
        val env = createEnv()
        try {
            // 预置 6 张运行期表 + 一个向量，恢复后必须全部清空（不重建）。
            val db = env.database
            db.transaction {
                db.dailyDeliveriesQueries.insertDeliveryIfAbsent("seed-delivery", 1L)
                db.replyTurnsQueries.insertReplyTurnIfAbsent(
                    "seed-turn", "session_golden", "private", "manual", null, "", "online", 0L, 1L, 1L,
                )
                db.memoryLinksQueries.insertMemoryLink(9001L, 9002L, "merge", 1L)
                db.memorySourceQueueQueries.insertMemorySource(
                    "PRIVATE_CHAT", "operator", "golden", "seed-ref", "seed-content", 1L,
                    0L, 0L, "pending", 0L, 0L, 0L, "", "", 1L,
                )
                db.memoryBatchesQueries.insertMemoryBatch(
                    "operator", "golden", "PRIVATE_CHAT", "L1", 1L, 1L, 1L, 2L, "v1", "done", 1L,
                )
                db.vectorMemoriesQueries.insertVectorMemory(
                    "seed-vector", "operator", "golden", "memory_v2_l1", "1", "seed", 1.0,
                    "[]", "", "public", "", 1L, Long.MAX_VALUE,
                )
            }
            assertRestored(restore(env, FullBackupGoldens.load("full-all-v2.zip")).first)

            assertNull(env.database.dailyDeliveriesQueries.getDelivery("seed-delivery").executeAsOneOrNull())
            assertNull(env.database.replyTurnsQueries.getReplyTurn("seed-turn").executeAsOneOrNull())
            assertTrue(env.database.memoryLinksQueries.getMemoryLinksByParent(9001L).executeAsList().isEmpty())
            assertNull(env.database.memorySourceQueueQueries.getMemorySourceId(
                "PRIVATE_CHAT", "operator", "golden", "seed-ref", "seed-content", 1L,
            ).executeAsOneOrNull())
            assertTrue(env.database.memoryBatchesQueries.getMemoryBatchesByOwner("operator", "golden").executeAsList().isEmpty())
            assertTrue(env.database.vectorMemoriesQueries.getVectorMemoriesByOwner("operator", "golden").executeAsList().isEmpty())
            // memoryItems.vectorId 全空
            assertTrue(FullBackupDb.memoryItems(env.database).all { it.vectorId.isEmpty() })
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #8 幂等

    @Test
    fun acceptance8_idempotentReRestore() = runBlocking {
        val env = createEnv()
        try {
            val bytes = FullBackupGoldens.load("full-all-v2.zip")
            assertRestored(restore(env, bytes).first)
            val first = FullBackupDb.counts(env.database)
            val firstOperators = FullBackupDb.operators(env.database).map { it.id to it.name }.sortedBy { it.first }
            val firstContents = FullBackupDb.memories(env.database).map { it.content }.sorted()
            val result2 = restore(env, bytes).first
            assertRestored(result2)
            assertEquals(first, FullBackupDb.counts(env.database), "重复恢复后计数应等价")
            assertEquals(firstOperators, FullBackupDb.operators(env.database).map { it.id to it.name }.sortedBy { it.first })
            assertEquals(firstContents, FullBackupDb.memories(env.database).map { it.content }.sorted())
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #9 坏包无残留

    @Test
    fun acceptance9_badPackagesLeaveNoResidue() = runBlocking {
        val base = "full-all-v2.zip"
        val pagePath = "data/pages/social/010000.json"
        val variants = linkedMapOf<String, ByteArray>(
            "缺分页页" to FullBackupGoldenZip(base).removeEntry(pagePath).build(),
            "recordCount 不符" to FullBackupGoldenZip(base).mutateManifest { manifest ->
                JsonObject(manifest + ("pages" to JsonArray(manifest["pages"]!!.let { it as JsonArray }.map { element ->
                    val page = element.jsonObject
                    if (page["path"]!!.jsonPrimitive.content == pagePath) {
                        JsonObject(page + ("recordCount" to JsonPrimitive(page["recordCount"]!!.jsonPrimitive.int + 1)))
                    } else element
                })))
            }.build(),
            "sha256 不符" to FullBackupGoldenZip(base)
                .mutatePage(pagePath) { page -> JsonObject(page + ("xProbe" to JsonPrimitive(1))) }
                .build(recomputeFiles = false),
            "不安全路径" to FullBackupGoldenZip(base).addRawEntry("../evil.json").build(allowUnsafePaths = true),
            "重复路径" to FullBackupGoldenZip(base).duplicateEntry(pagePath).build(),
            "媒体字节损坏" to FullBackupGoldenZip(base)
                .corruptEntry(FullBackupGoldens.load(base).let { bytes ->
                    com.rhodes.privatechat.shared.backup.BackupZip.read(bytes, 1_000, 100L * 1024 * 1024, 500L * 1024 * 1024)
                        .first { it.name.startsWith("media/") }.name
                }) { data -> data.copyOf().also { it[1] = (it[1].toInt() xor 0xFF).toByte() } }
                .build(recomputeFiles = false),
        )
        variants.forEach { (label, bytes) ->
            val env = createEnv()
            try {
                val (result, sink) = restore(env, bytes)
                assertFalse(result.success, "$label 应恢复失败")
                assertEquals(0, FullBackupDb.operators(env.database).size, "$label 不得残留角色")
                assertEquals(0, FullBackupDb.sessions(env.database).size, "$label 不得残留会话")
                assertTrue(FullBackupDb.memories(env.database).isEmpty(), "$label 不得残留记忆")
                assertTrue(FullBackupDb.moments(env.database).isEmpty(), "$label 不得残留动态")
                assertTrue(FullBackupDb.knowledgeBases(env.database).isEmpty(), "$label 不得残留知识库")
                assertTrue(!sink.cleaned || sink.stagedBytes.isEmpty())
            } finally {
                env.close()
            }
        }
    }

    // ------------------------------------------------------------------ #10 事务内失败三层回滚

    @Test
    fun acceptance10_transactionFailureRollsBackEverything() = runBlocking {
        val env = createEnv()
        try {
            // 预置现网数据 + 设置哨兵。
            val db = env.database
            db.transaction {
                db.operatorsQueries.insertAllOperators(
                    "seed_op", "现网角色", "", "", "", "", "宿舍", "休息", "平静", 0L,
                    "", "", "", "", 10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
                )
                db.chatSessionsQueries.insertSession(
                    "session_seed", "seed_op", "现网角色", "", 0L, "online", 0L, 0L, "", "", "", "",
                )
            }
            env.settings.putString("user_name", "恢复前")

            // 注入 DB 失败：gift_records 主键重复（reader 不校验 gift id 唯一；insert 用普通 INSERT）。
            val bytes = FullBackupGoldenZip("full-all-v2.zip").let { zip ->
                zip.mutatePayloadArray(zip.pagePath("extras", 0), "giftRecords") { gifts ->
                    JsonArray(gifts.mapIndexed { index, element ->
                        if (index == 1) JsonObject(element.jsonObject + ("id" to JsonPrimitive(1))) else element
                    })
                }.build()
            }
            val (result, sink) = restore(env, bytes)
            assertFalse(result.success, "注入 DB 失败后恢复应失败")
            assertEquals(1, FullBackupDb.operators(env.database).size, "事务失败后现网角色必须保留")
            assertEquals("seed_op", FullBackupDb.operators(env.database).single().id)
            assertEquals("session_seed", FullBackupDb.sessions(env.database).single().id)
            assertTrue(FullBackupDb.memories(env.database).isEmpty())
            assertTrue(FullBackupDb.moments(env.database).isEmpty())
            assertEquals("恢复前", env.settings.getString("user_name"), "设置快照必须回放（三层回滚第 3 层）")
            assertTrue(sink.cleaned, "媒体目录必须清理")
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #11 自检 + 失配检测

    @Test
    fun acceptance11_selfCheckAndMismatchDetection() = runBlocking {
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-all-v2.zip"))
            val result = restore(env, FullBackupGoldens.load("full-all-v2.zip")).first
            assertRestored(result)
            val check = result.selfCheck!!
            assertTrue(check.passed)
            assertEquals(23, check.checked.size, "自检应覆盖 22 表 + settings 共 23 项")
            assertEquals(2, check.checked["operators"]!!.first)
            assertEquals(12, check.checked["messages"]!!.first)

            // 提交后人为删除一条消息 → 直接调用自检应检出 mismatch。
            env.database.chatMessagesQueries.deleteMessage(1L)
            val mismatch = BackupFullSelfCheck().check(
                archive.manifest, BackupContentSelection.All, archive.payload, env.repository, env.settings,
            )
            assertFalse(mismatch.passed)
            assertTrue(mismatch.mismatches.any { it.contains("messages") }, "应检出 messages 失配：${mismatch.mismatches}")
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #12 分类选择门禁

    @Test
    fun acceptance12_selectionGating() = runBlocking {
        val env = createEnv()
        try {
            seedForeignData(env)
            // 只恢复 roles/chats（其余类目 false）：现网 social/memories/knowledge/extras 必须保留。
            val options = BackupRestoreOptions(
                contentSelection = BackupContentSelection(
                    roles = true, chats = true, memories = false, social = false,
                    knowledgeBases = false, extras = false, settings = false, media = true,
                ),
            )
            assertRestored(restore(env, FullBackupGoldens.load("full-all-v2.zip"), options).first)
            assertEquals(2, FullBackupDb.operators(env.database).size, "roles 应整体替换")
            assertFalse(FullBackupDb.operators(env.database).any { it.id == "seed_op" })
            assertEquals(1, FullBackupDb.moments(env.database).size, "social 未选中：现存动态必须保留")
            assertEquals("seed_moment", FullBackupDb.moments(env.database).single().content)
            assertEquals(1, FullBackupDb.knowledgeBases(env.database).size, "KB 未选中：现存知识库必须保留")
            assertEquals("seed_kb", FullBackupDb.knowledgeBases(env.database).single().id)
            assertEquals(1, FullBackupDb.gifts(env.database).size, "extras 未选中：现存礼物必须保留")
            assertTrue(FullBackupDb.memories(env.database).isEmpty(), "memories 未选中：不插备份记忆")
            // D3：settings 未选中 → 全局设置不动，但角色局部 key 随角色恢复。
            assertEquals("现网用户", env.settings.getString("user_name"))
            assertEquals("i:1", "i:${env.settings.getInt("operator_prompt_slot_golden_private")}")
            assertTrue(env.settings.getString("operator_prompt_slot_golden_private_1").isNotEmpty())
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #13 空包

    @Test
    fun acceptance13_emptyPackageClearsSelectedCategories() = runBlocking {
        val env = createEnv()
        try {
            seedForeignData(env)
            assertRestored(restore(env, FullBackupGoldens.load("full-empty-v2.zip")).first)
            val counts = FullBackupDb.counts(env.database)
            assertEquals(0, counts["operators"])
            assertEquals(0, counts["sessions"])
            assertEquals(0, counts["messages"])
            assertEquals(0, counts["memories"])
            assertEquals(0, counts["moments"])
            assertEquals(0, counts["knowledgeBases"])
            assertEquals(0, counts["gifts"])
            assertEquals(0, counts["mahjong"])
            assertTrue(FullBackupDb.operators(env.database).isEmpty())
            // settings 选中且备份 settings={} → 白名单 key 清空（user_name 回默认空串）。
            assertEquals("", env.settings.getString("user_name"))
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #14 媒体重写全扫描域

    @Test
    fun acceptance14_mediaRewriteCoverage() = runBlocking {
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-media-v2.zip"))
            val (result, sink) = restore(env, FullBackupGoldens.load("full-media-v2.zip"))
            assertRestored(result)
            val mediaByOriginal = archive.payload.media.associate { item ->
                item.originalUri to sink.urls.getValue(item.mediaId)
            }
            fun rewritten(value: String): String =
                mediaByOriginal.entries.fold(value) { acc, entry -> acc.replace(entry.key, entry.value) }

            val operator = FullBackupDb.operators(env.database).single()
            assertTrue(operator.avatarUri.startsWith("file:///staged/"), "头像应重写：${operator.avatarUri}")
            val session = FullBackupDb.sessions(env.database).single()
            assertTrue(session.avatarUri.startsWith("file:///staged/"), "会话头像应重写：${session.avatarUri}")
            val messages = FullBackupDb.messages(env.database)
            assertEquals(2, messages.size)
            val expectedMessages = archive.payload.content.messages!!.map { it.copy(content = rewritten(it.content)) }
            assertEquals(expectedMessages.map { it.content }.sorted(), messages.map { it.content }.sorted())
            val gift = FullBackupDb.gifts(env.database).single()
            assertEquals(rewritten(archive.payload.giftRecords.single().imageUri), gift.imageUri)
            val archiveRow = FullBackupDb.archives(env.database).single()
            assertEquals(rewritten(archive.payload.chatArchives.single().messagesJson), archiveRow.messagesJson)
            assertEquals(rewritten(archive.payload.chatArchives.single().stateJson), archiveRow.stateJson)
            val historyRow = FullBackupDb.historySegments(env.database).single()
            assertEquals(rewritten(archive.payload.chatHistorySegments.single().messagesJson), historyRow.messagesJson)
            val snapshot = BackupPortableSettings().snapshot(env.repository, env.settings)
            assertEquals("s:" + rewritten(archive.payload.content.settings!!.getValue("user_avatar_uri").removePrefix("s:")),
                snapshot["user_avatar_uri"])
            assertTrue(snapshot.getValue("support_conversation").contains("file:///staged/"))
            // mediaId 直映射：originalUri 不再残留于消息内容。
            messages.forEach { message ->
                mediaByOriginal.keys.forEach { original ->
                    assertFalse(message.content.contains(original), "消息内容不得残留旧 URI：$original")
                }
            }
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ #15 ORPHAN issue / skip 语义

    @Test
    fun acceptance15_orphanIssueAndSkipSemantics() = runBlocking {
        // memories 包：1 孤儿记忆 + 1 孤儿锚点
        val memoriesBytes = FullBackupGoldens.load("full-memories-v2.zip")
        val reader = FullBackupReader()
        val archive = reader.read(memoriesBytes)
        assertTrue(archive.issues.any { it.code == "ORPHAN_MEMORY" })
        assertTrue(archive.issues.any { it.code == "ORPHAN_ANCHOR" })

        val envNoSkip = createEnv()
        try {
            assertRestored(restore(envNoSkip, memoriesBytes).first)
            assertEquals(4, FullBackupDb.memories(envNoSkip.database).size, "未 skip 时孤儿记忆也落库（Android 同款）")
            assertEquals(7, FullBackupDb.anchors(envNoSkip.database).size)
        } finally {
            envNoSkip.close()
        }
        val envSkip = createEnv()
        try {
            val options = BackupRestoreOptions(skipIssueCodes = setOf("ORPHAN_MEMORY", "ORPHAN_ANCHOR"))
            assertRestored(restore(envSkip, memoriesBytes, options).first)
            assertEquals(3, FullBackupDb.memories(envSkip.database).size, "skip 后孤儿记忆被剔除")
            assertEquals(6, FullBackupDb.anchors(envSkip.database).size)
            assertTrue(FullBackupDb.memories(envSkip.database).none { it.content.contains("孤儿") })
        } finally {
            envSkip.close()
        }

        // social 包：孤儿评论/点赞/参与者
        val socialBytes = FullBackupGoldens.load("full-social-v2.zip")
        val socialArchive = reader.read(socialBytes)
        assertEquals(3, socialArchive.issues.size)
        val envSocial = createEnv()
        try {
            val options = BackupRestoreOptions(
                skipIssueCodes = setOf(
                    "ORPHAN_MOMENT_COMMENT", "ORPHAN_MOMENT_LIKE", "ORPHAN_SHARED_EXPERIENCE_PARTICIPANT",
                ),
            )
            assertRestored(restore(envSocial, socialBytes, options).first)
            assertEquals(3, FullBackupDb.comments(envSocial.database).size)
            assertEquals(2, FullBackupDb.likes(envSocial.database).size)
            assertEquals(2, FullBackupDb.participants(envSocial.database).size)
        } finally {
            envSocial.close()
        }
    }

    // ------------------------------------------------------------------ #16 分类包 + v1 单 payload

    @Test
    fun acceptance16_categoryPackagesAndV1() = runBlocking {
        listOf(
            "full-memories-v2.zip", "full-social-v2.zip",
            "full-knowledge-v2.zip", "full-extras-v2.zip",
        ).forEach { name ->
            val env = createEnv()
            try {
                val result = restore(env, FullBackupGoldens.load(name), BackupRestoreOptions(
                    skipIssueCodes = setOf(
                        "ORPHAN_MEMORY", "ORPHAN_ANCHOR", "ORPHAN_MOMENT_COMMENT",
                        "ORPHAN_MOMENT_LIKE", "ORPHAN_SHARED_EXPERIENCE_PARTICIPANT",
                    ),
                )).first
                assertRestored(result)
            } finally {
                env.close()
            }
        }
        // v1 单 payload 全类目兼容恢复
        val env = createEnv()
        try {
            val archive = FullBackupReader().read(FullBackupGoldens.load("full-v1-single.zip"))
            assertEquals(1, archive.manifest.formatVersion)
            assertRestored(restore(env, FullBackupGoldens.load("full-v1-single.zip")).first)
            assertEquals(2, FullBackupDb.operators(env.database).size)
            assertEquals(2, FullBackupDb.messages(env.database).size)
            assertEquals(1, FullBackupDb.memories(env.database).size)
            assertEquals(1, FullBackupDb.moments(env.database).size)
            assertEquals(1, FullBackupDb.knowledgeBases(env.database).size)
            assertEquals(1, FullBackupDb.gifts(env.database).size)
            assertEquals("{\"round\":2}", FullBackupDb.mahjong(env.database)!!.saveJson)
        } finally {
            env.close()
        }
    }

    // ------------------------------------------------------------------ 公共夹具

    private fun seedForeignData(env: TestBackupEnv) {
        val db = env.database
        db.transaction {
            db.operatorsQueries.insertAllOperators(
                "seed_op", "现网角色", "", "", "", "", "宿舍", "休息", "平静", 0L,
                "", "", "", "", 10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
            )
            db.chatSessionsQueries.insertSession(
                "session_seed", "seed_op", "现网角色", "", 0L, "online", 0L, 0L, "", "", "", "",
            )
            db.momentsQueries.insertMoment("seed_op", "现网角色", "seed_moment", 0L, "", 0L, 0L, 1L)
            db.knowledgeBasesQueries.insertKnowledgeBase(
                "seed_kb", "现网知识库", "", "", "", "user", "smart", "pending", "", 1L, 1L,
            )
            db.giftRecordsQueries.insert(1L, "seed_op", "", "现网礼物", "我", 1L)
            db.mahjongSavesQueries.insertSave("current", "{}", "classic", 1L)
        }
        env.settings.putString("user_name", "现网用户")
    }

    private fun relTuple(row: Relationships): List<String> = listOf(
        row.operatorId, row.relatedOperatorId, row.relatedOperatorName, row.type,
        row.intimacy.toString(), (row.isPreset != 0L).toString(), row.note,
    )

    private fun messageTuple(message: ChatMessage): List<String> = listOf(
        message.id.toString(), message.sessionId, message.senderId, message.senderName,
        message.content, message.type, message.mode, message.emotion, message.activity,
        message.location, message.narration, message.segmentGroup,
        message.intimacyChange.toString(), message.timestamp.toString(), message.isMe.toString(),
    )

    private fun messageTuple(row: com.rhodes.privatechat.shared.db.Chat_messages): List<String> = listOf(
        row.id.toString(), row.sessionId, row.senderId, row.senderName,
        row.content, row.type, row.mode, row.emotion, row.activity,
        row.location, row.narration, row.segmentGroup,
        row.intimacyChange.toString(), row.timestamp.toString(), (row.isMe != 0L).toString(),
    )

    private fun archiveTuple(archive: ChatArchive): List<String> = listOf(
        archive.id, archive.sessionId, archive.operatorId, archive.title, archive.note,
        archive.mode, archive.messagesJson, archive.summary, archive.stateJson,
        archive.status, archive.createdAt.toString(), archive.updatedAt.toString(),
    )

    private fun historyTuple(history: ChatHistorySegment): List<String> = listOf(
        history.id, history.sessionId, history.title, history.reason,
        history.messagesJson, history.createdAt.toString(),
    )

    private fun memoryTuple(memory: Memory): List<String> = listOf(
        memory.sessionId, memory.operatorId, memory.type.name, memory.content, memory.keywords,
        memory.preferences, memory.taboos, memory.createdAt.toString(), memory.expiresAt.toString(),
    )

    private fun memoryRowTuple(row: Memories): List<String> = listOf(
        row.sessionId, row.operatorId, row.type, row.content, row.keywords,
        row.preferences, row.taboos, row.createdAt.toString(), row.expiresAt.toString(),
    )

    private fun anchorTuple(anchor: MemoryAnchor): List<String> = listOf(
        anchor.sessionId, anchor.operatorId, anchor.type.name, anchor.content,
        anchor.isPrivate.toString(), anchor.createdAt.toString(), anchor.expiresAt.toString(),
        anchor.source, anchor.sourceName, anchor.sourceActor, anchor.sourceTarget,
        anchor.importance, anchor.knownFrom,
    )

    private fun anchorRowTuple(row: Memory_anchors): List<String> = listOf(
        row.sessionId, row.operatorId, row.type, row.content,
        (row.isPrivate != 0L).toString(), row.createdAt.toString(), row.expiresAt.toString(),
        row.source, row.sourceName, row.sourceActor, row.sourceTarget,
        row.importance, row.knownFrom,
    )

    private fun diaryTuple(diary: Diary): List<String> = listOf(
        diary.operatorId, diary.operatorName, diary.content, diary.date,
        diary.version.toString(), diary.createdAt.toString(),
    )

    private fun diaryRowTuple(row: Diaries): List<String> = listOf(
        row.operatorId, row.operatorName, row.content, row.date, row.version.toString(), row.createdAt.toString(),
    )

    private fun chunkTuple(chunk: FullBackupKnowledgeBaseChunkWire): List<String> = listOf(
        chunk.id, chunk.knowledgeBaseId, chunk.ordinal.toString(), chunk.sourceHeading,
        chunk.content, chunk.userKeywords, chunk.enabled.toString(),
        chunk.createdAt.toString(), chunk.updatedAt.toString(),
    )

    private fun chunkRowTuple(row: Knowledge_base_chunks): List<String> = listOf(
        row.id, row.knowledgeBaseId, row.ordinal.toString(), row.sourceHeading,
        row.content, row.userKeywords, (row.enabled != 0L).toString(),
        row.createdAt.toString(), row.updatedAt.toString(),
    )

    private fun giftTuple(gift: GiftRecord): List<String> = listOf(
        gift.id.toString(), gift.operatorId, gift.imageUri, gift.giftName, gift.senderName, gift.createdAt.toString(),
    )

    private fun giftRowTuple(row: Gift_records): List<String> = listOf(
        row.id.toString(), row.operatorId, row.imageUri, row.giftName, row.senderName, row.createdAt.toString(),
    )

    private fun experienceTuple(experience: SharedExperience): List<String> = listOf(
        experience.sourceKind, experience.sourceRefId, experience.groupId, experience.content,
        experience.importance.toString(), experience.status, experience.createdAt.toString(),
        experience.expiresAt.toString(),
    )

    private fun experienceRowTuple(row: Shared_experiences): List<String> = listOf(
        row.sourceKind, row.sourceRefId, row.groupId, row.content,
        row.importance.toString(), row.status, row.createdAt.toString(), row.expiresAt.toString(),
    )
}
