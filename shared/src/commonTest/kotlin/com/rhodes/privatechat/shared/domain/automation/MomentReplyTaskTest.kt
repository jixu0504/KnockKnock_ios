package com.rhodes.privatechat.shared.domain.automation

import com.rhodes.privatechat.shared.domain.chat.UserProfileView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 动态定时回复执行体（A7 §B）：唯一键 / turn 生成 / arming / moment_comment 管线 / 租约 /
 * 失败退避 / 通知事件化。fake-clock + 确定性 fake AI，零网络。
 */
class MomentReplyTaskTest {

    // === §B-1 Turn 生成 ===

    @Test
    fun turnIdIsDeterministicSha256OfOracleIdentity() = runBlocking {
        val a = MomentReplyContract.buildTurnId(7L, "op-1", 0L, 11L, "随便")
        val b = MomentReplyContract.buildTurnId(7L, "op-1", 0L, 11L, "随便")
        assertEquals(a, b, "同输入必须同 id（insertReplyTurnIfAbsent 幂等前提）")
        assertTrue(a.startsWith("moment-"))
        assertEquals(71, a.length, "moment- + 64 位十六进制")
        assertTrue(a.removePrefix("moment-").all { it in "0123456789abcdef" })
        // sourceCommentId 存在时 identity 用 sourceCommentId，不含 userContent。
        assertEquals(a, MomentReplyContract.buildTurnId(7L, "op-1", 0L, 11L, "不同内容"))
        val noSource = MomentReplyContract.buildTurnId(7L, "op-1", 0L, null, " 训练场怎么这么安静？ ")
        assertEquals(noSource, MomentReplyContract.buildTurnId(7L, "op-1", 0L, null, "训练场怎么这么安静？"))
        assertTrue(noSource != a)
    }

    @Test
    fun buildTurnUsesImmediateZeroOrRandomDelayAndOracleFields() {
        val harness = MomentReplyHarness()
        val payload = com.rhodes.privatechat.shared.model.MomentReplyTask(
            momentId = 7L, operatorId = "op-1", speakerName = "银灰",
            userContent = "内容", parentCommentId = 0L, userName = "博士",
        )
        val random = harness.task.buildTurn(payload, now = harness.clock.now, immediate = false)
        assertEquals(harness.clock.now + 10_000L, random.nextAttemptAt, "非 immediate：随机 10–30s（测试注入 10s）")
        val immediate = harness.task.buildTurn(payload, now = harness.clock.now, immediate = true)
        assertEquals(harness.clock.now, immediate.nextAttemptAt, "帖主/提及场景立即回")
        assertEquals("moment_comment", immediate.surface)
        assertEquals("moment_comment", immediate.triggerKind)
        assertEquals("online", immediate.mode)
        assertEquals("pending", immediate.status)
        assertEquals(0, immediate.attemptCount)
        assertEquals("moment:7", immediate.sessionId)
        assertEquals(immediate.id, harness.task.buildTurn(payload, harness.clock.now, true).id)
    }

    @Test
    fun armingUsesMomentReplyKeyAndClampsOverdueToNow() = runBlocking {
        val harness = MomentReplyHarness()
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, now = harness.clock.now - 5_000L, immediate = false)
            .copy(nextAttemptAt = harness.clock.now - 1_000L) // 过期任务
        harness.port.seedTurn(turn)
        val key = requireNotNull(harness.task.arm(harness.layer, turn))
        assertEquals("moment-reply-${turn.id}", key, "唯一键 moment-reply-${'$'}id（oracle MomentReplyWorker.kt:21）")
        assertEquals(harness.clock.now, harness.primary.scheduled.getValue(key).first, "延迟下限钳 0：过期立即执行")
        val scheduledPayload = harness.primary.scheduled.getValue(key).second
        assertEquals(turn.id, scheduledPayload["turn"] ?: "<null>")
        assertEquals("7", scheduledPayload["moment"] ?: "<null>")
        // KEEP：同键二次 arming 被层忽略，不重置 dueAt。
        assertNull(harness.task.arm(harness.layer, turn.copy(nextAttemptAt = harness.clock.now + 99_000L)))
        assertEquals(1, harness.primary.scheduleCount)
    }

    @Test
    fun createAndArmIsIdempotentByTurnId() = runBlocking {
        val harness = MomentReplyHarness(settings = MomentReplySettings())
        val payload = harness.seedScenario()
        val first = harness.task.createAndArm(harness.layer, payload, harness.clock.now, immediate = true)
        assertNotNull(first)
        val second = harness.task.createAndArm(harness.layer, payload, harness.clock.now, immediate = true)
        assertNull(second, "同 turn id 已挂起 → KEEP 忽略")
        assertEquals(2, harness.port.createTurnCalls.size)
    }

    // === §B-8/§B-9 moment_comment 管线 ===

    @Test
    fun executePersistsCommentAndEmitsNotificationWithGoldenCopy() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("训练场安静得能听见风声。"))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        harness.task.arm(harness.layer, turn)

        harness.primary.fire("moment-reply-${turn.id}")
        assertTrue(harness.awaitComments(1), "到期执行必须落一条评论")

        val stored = harness.port.comments().single()
        assertEquals("训练场安静得能听见风声。", stored.content)
        assertEquals("银灰", stored.operatorName)
        assertEquals(0L, stored.parentCommentId)
        assertEquals("博士", stored.replyToName)
        assertEquals("succeeded", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertEquals(listOf(7L), harness.port.commentCountRefreshes, "评论计数刷新（oracle :2826-2830）")
        assertEquals(1, harness.ai.requests.size)
        assertEquals("MomentReply", harness.ai.requests.single().stage)

        // §B-13 通知事件化：域层只产事件数据（标题「XX 回复了你的评论」）。
        val notification = harness.notifications().single()
        assertEquals("银灰 回复了你的评论", notification.title)
        assertEquals("训练场安静得能听见风声。", notification.body)
        assertEquals(NotificationCategory.MOMENT_REPLY_COMPLETED, notification.category)
        assertEquals(NotificationCopy.MOMENT_REPLY_ID_PREFIX + turn.id, notification.id)
        assertEquals("7", notification.payload["momentId"] ?: "<null>")
        assertEquals(stored.id.toString(), notification.payload["commentId"] ?: "<null>")
        // 分层记忆写入被调用（memoryV2Enabled && momentCommentMemoryGenerationEnabled）。
        assertEquals(1, harness.ingested.size)
        assertEquals(stored.id, harness.ingested.single().first.id)
    }

    @Test
    fun promptAssemblyIncludesTemplateBoundaryAndRuntimeSnapshot() = runBlocking {
        var memoryQueried: Triple<String, String, Boolean>? = null
        val harness = MomentReplyHarness(scripts = listOf("听见风声了。"))
        val task = MomentReplyTask(
            store = harness.port,
            promptAssembler = MomentCommentPromptAssembler(),
            ai = harness.ai,
            settings = MomentReplySettings(commentMinChars = 5, commentMaxChars = 30),
            contextProviders = MomentCommentContextProviders(
                commenterMemory = { operatorId, query, isOwner ->
                    memoryQueried = Triple(operatorId, query, isOwner)
                    "记得上次在训练场聊过" to "这段记忆来自私聊"
                },
                knowledgeContext = { _, _ -> "知识库：训练场档案" },
                recentSocialContext = { _, _ -> "近期动态：训练记录" },
                personalMemoryReferenceStyle = { "仅在话题高度相关时提及" },
                sourceAwareRules = { "使用记忆时说明来源" },
                userProfile = { UserProfileView(nickname = "博士", gender = "男", bio = "指挥官") },
            ),
            clock = harness.clock::read,
            randomToken = { "token" },
            config = MomentReplyConfig(contentRetryDelayMs = 0L),
            memoryIngest = { _, _ -> },
        )
        task.register(harness.layer)
        val payload = harness.seedScenario()
        val turn = task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        task.arm(harness.layer, turn)

        assertEquals(DelayedTaskOutcome.SUCCESS, task.executeTurn(turn.id))

        val messages = harness.ai.requests.single().messages
        val system = messages.first { it.role == "system" }.content
        assertTrue(system.contains("【本轮资料边界】"), "system 必须带资料边界（oracle :545-551）")
        assertEquals(1, messages.count { it.role == "system" })
        val allUser = messages.filter { it.role == "user" }.joinToString("\n") { it.content }
        assertTrue(allUser.contains("【本轮任务】"), "最后一条 user 是【本轮任务】（oracle :554）")
        assertTrue(allUser.contains("评论者"), "自然运行时上下文含【评论者】块")
        assertTrue(allUser.contains("被评论的公开动态"))
        assertTrue(allUser.contains("已有评论和公开互动"), "评论上下文注入")
        assertTrue(allUser.contains("记得上次在训练场聊过"), "记忆上下文注入")
        assertTrue(allUser.contains("知识库：训练场档案"), "知识库上下文注入")
        assertTrue(allUser.contains("近期动态：训练记录"), "社交上下文注入")
        assertTrue(allUser.contains("使用记忆时说明来源"))
        assertTrue(allUser.contains("博士"), "回复对象/用户资料注入")
        assertEquals(Triple("op-1", "训练场怎么这么安静？", true), memoryQueried, "帖主回复自己的动态 = 私密互动")
        // 模板变量已替换（无残留 {{COMMENT_*}} 系统变量）。
        assertTrue(!system.contains("{{COMMENT_CONTEXT}}"))
        assertEquals(1, messages.count { it.role == "user" && it.content.contains("【本轮任务】") })
    }

    @Test
    fun emptyModelReplyIsTreatedAsFailureAndReleasesLease() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("", ""))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)

        assertEquals(DelayedTaskOutcome.RETRY, harness.task.executeTurn(turn.id))
        assertEquals(0, harness.port.commentCount(), "空模型回复 = 失败（oracle :3024-3025）")
        assertEquals(2, harness.ai.requests.size, "内容生成最多 2 次（oracle :2465）")
        val released = harness.port.released.single()
        assertEquals(turn.id, released.first)
        assertEquals(harness.clock.now + 60_000L, released.second, "首次失败退避 1min（oracle :2909-2910）")
        assertEquals("pending", harness.port.turnSnapshot(turn.id)?.status ?: "<null>")
        assertTrue(harness.events.any { it is MomentReplyEvent.TaskFailed && !it.terminal })
        assertTrue(harness.notifications().isEmpty(), "失败不得发完成通知")
    }

    @Test
    fun memoryIngestFailureDoesNotBlockReplyOrNotification() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("听见风声了。"))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        val failingTask = MomentReplyTask(
            store = harness.port,
            promptAssembler = MomentCommentPromptAssembler(),
            ai = harness.ai,
            clock = harness.clock::read,
            config = MomentReplyConfig(contentRetryDelayMs = 0L),
            memoryIngest = { _, _ -> throw IllegalStateException("记忆写入失败") },
            events = { harness.events += it },
        )
        assertEquals(DelayedTaskOutcome.SUCCESS, failingTask.executeTurn(turn.id))
        assertEquals(1, harness.port.commentCount())
        assertTrue(harness.events.any { it is MomentReplyEvent.MemoryIngestFailed })
        assertEquals(1, harness.notifications().size, "记忆失败不阻塞完成通知")
    }

    @Test
    fun leaseIsTenMinutesAndClaimIncrementsAttempt() = runBlocking {
        val harness = MomentReplyHarness(scripts = listOf("听见风声了。"))
        val payload = harness.seedScenario()
        val turn = harness.task.buildTurn(payload, harness.clock.now, immediate = true)
        harness.port.seedTurn(turn)
        assertEquals(DelayedTaskOutcome.SUCCESS, harness.task.executeTurn(turn.id))
        val claim = harness.port.claims.single()
        assertEquals(turn.id, claim.first)
        assertEquals(600_000L, claim.third - claim.second, "租约 10 分钟（oracle :2882）")
    }

    // === 模型输出清洗（oracle PlainGeneratedContentNormalizer 逐例迁移）===

    @Test
    fun plainContentNormalizerMatchesOracleCases() {
        assertEquals("今天食堂的汤有点咸。", PlainCommentContentNormalizer.normalize("今天食堂的汤有点咸。"))
        assertEquals(
            "刚巡完逻，风有点凉。",
            PlainCommentContentNormalizer.normalize("```json\n{\"content\":\"刚巡完逻，风有点凉。\"}\n```"),
        )
        assertEquals("这句我记住了。", PlainCommentContentNormalizer.normalize("\"这句我记住了。\""))
        assertEquals("走廊里安静得很。", PlainCommentContentNormalizer.normalize("下面是动态：走廊里安静得很。"))
        assertNull(PlainCommentContentNormalizer.normalize("{\"analysis\":\"先分析\"}"))
        assertNull(PlainCommentContentNormalizer.normalize("{\"content\":{\"text\":\"正文\"}}"))
        assertNull(PlainCommentContentNormalizer.normalize("作为AI，我不能这样做。"))
        assertNull(PlainCommentContentNormalizer.normalize("   "))
        assertEquals("没问题的。", PlainCommentContentNormalizer.normalize(" 没问题的。 "))
    }

    @Test
    fun payloadCodecRoundTripsOracleMomentReplyTask() {
        val codec = JsonMomentReplyPayloadCodec()
        val payload = com.rhodes.privatechat.shared.model.MomentReplyTask(
            momentId = 7L, operatorId = "op-1", speakerName = "银灰", userContent = "内容",
            parentCommentId = 12L, userName = "博士", customPrompt = "简短", mustInsert = true, sourceCommentId = 11L,
        )
        assertEquals(payload, codec.decode(codec.encode(payload)))
        assertNull(codec.decode("not-json"))
    }

    @Test
    fun sharedTaskPayloadIsDecodableFromOracleJsonShape() {
        val codec = JsonMomentReplyPayloadCodec()
        val decoded = codec.decode(
            """{"momentId":7,"operatorId":"op-1","speakerName":"银灰","userContent":"内容",""" +
                """"parentCommentId":0,"userName":"博士"}""",
        )
        assertNotNull(decoded)
        assertEquals(7L, decoded.momentId)
        assertNull(decoded.sourceCommentId)
    }
}
