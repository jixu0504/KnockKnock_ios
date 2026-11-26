package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.DatabaseWrapper
import com.rhodes.privatechat.shared.domain.chat.DefaultReplyCodec
import com.rhodes.privatechat.shared.domain.chat.PrivateContextProviders
import com.rhodes.privatechat.shared.domain.chat.PrivatePromptAssembler
import com.rhodes.privatechat.shared.domain.chat.PromptBuildRequest
import com.rhodes.privatechat.shared.domain.chat.RepositoryChatStore
import com.rhodes.privatechat.shared.domain.chat.UserProfileView
import com.rhodes.privatechat.shared.domain.knowledge.KnowledgeBaseService
import com.rhodes.privatechat.shared.domain.memory.MemoryV2Pipeline
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseContextBuilder
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseImportService
import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseIndexService
import com.rhodes.privatechat.shared.model.AiMessage
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.network.AIService
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.vector.LocalVectorStoreGateway
import com.rhodes.privatechat.shared.vector.MemoryVectorService
import kotlin.time.Clock

/**
 * T45 私聊提示词/记忆/知识库真实接线（`DemoPromptAssembler` 的生产替代）。
 *
 * 职责：按真实依赖构造 T24 记忆管线 / 知识库门面 / T15 提示词组装器，并把
 * `PrivateContextProviders` 的七个可选提供者接到真实数据源（oracle
 * `ChatViewModel.buildApiMessages :3055-3264` 的等价映射）：
 *
 * | ORACLE 提供者 | oracle 调用点 | 实现 |
 * | --- | --- | --- |
 * | shortTermMemory | `repository.getShortTermMemory` (:3081) | `ChatRepository.getShortTermMemory` |
 * | groupContext | `buildPrivateGroupContext` (:3140-3149) | 本文件 `buildPrivateGroupContext`（群会话最近摘要） |
 * | stableImpression | `memoryV2Pipeline.buildPrivateStableImpression` (:3164) | 同方法（L3 长期印象） |
 * | personalMemory | `memoryV2Pipeline.buildPrivateChatMemoryContext` (:3168-3173) | 同方法 + `privateChatAllowedSources` |
 * | relationshipMemory | `memoryV2Pipeline.buildRelationshipPrivateMemoryContext` (:3174) | 同方法（关系网反向记忆） |
 * | publicMemory | `memoryV2Pipeline.buildPublicMemoryContext` (:3187) | 同方法（MOMENT/MOMENT_COMMENT 源） |
 * | knowledgeContext | `knowledgeBaseContextBuilder.forOperatorRecall` (:3207-3220) | `KnowledgeBaseService.recallForPrivateChat`（含 enabled 分配过滤） |
 *
 * 用户档案 key 与 Android oracle 一致（`saveUserProfile` MainViewModel :2132-2136）：
 * `user_name` / `user_gender` / `user_signature`（bio）。
 *
 * 与 oracle 的已知差异（详见 ios-port/specs/prompt-wiring-spec.md）：
 * - 存档时间线隔离（`archive_context_active_*` / `archive_private_recall_ready_*`）未在 provider 内判定；
 * - `ChatTrace`/`ContextProvenance` 溯源卡片未移植（仅影响调试 UI，不影响提示词内容）；
 * - 短期摘要的生成（`generateShortTermSummary`）仍由后续 A7 维护层负责，这里只读取已有摘要。
 */
internal class PrivatePromptWiring(
    private val repo: ChatRepository,
    dbWrapper: DatabaseWrapper,
    private val settings: SettingsRepository,
) {
    private val codec = DefaultReplyCodec()

    /** 真实 AIService（记忆 L1/L2/L3 提取用；与 Android `sharedUtils.aiService` 同实现）。 */
    private val aiService = AIService(deepseekThinking = { settings.deepseekThinkingEnabled })

    /** 向量服务：默认本地哈希嵌入（settings.vectorProviderMode 默认 local，离线可用）。 */
    private val vectorService = MemoryVectorService(settings, LocalVectorStoreGateway(dbWrapper))

    /** T24 记忆 V2 管线（真实 DB/AI/向量）。 */
    val memoryV2 = MemoryV2Pipeline(
        repository = repo,
        settings = settings,
        aiService = aiService,
        memoryVectorService = vectorService,
        userNicknameProvider = { userNickname() },
    )

    private val knowledgeIndexService = KnowledgeBaseIndexService(repo.knowledgeBases, vectorService, settings)

    /** T24 知识库域门面（导入/索引/召回）。 */
    val knowledgeBase = KnowledgeBaseService(
        importService = KnowledgeBaseImportService(repo.knowledgeBases, knowledgeIndexService, settings),
        indexService = knowledgeIndexService,
        contextBuilder = KnowledgeBaseContextBuilder(repo.knowledgeBases, vectorService),
    )

    /** T15 私聊提示词组装器（四层 system + 参考资料边界块 + 历史 + 用户消息）。 */
    val promptAssembler = PrivatePromptAssembler(
        store = RepositoryChatStore(repo),
        settings = settings,
        codec = codec,
        providers = buildProviders(),
        userProfileProvider = { userProfile() },
    )

    /** 当前接线状态（供自检/日志显示，不含任何密钥/隐私内容）。 */
    val statusLine: String =
        "assembler=PrivatePromptAssembler memoryV2=MemoryV2Pipeline knowledge=KnowledgeBaseService " +
            "providers=7 embedding=local"

    private fun userNickname(): String = settings.userName.ifBlank { "绳匠" }

    /** 用户档案（oracle `AppStateHolder.UserProfile`：nickname/gender/bio）。 */
    fun userProfile(): UserProfileView = UserProfileView(
        nickname = userNickname(),
        gender = settings.userGender,
        bio = settings.userSignature,
    )

    // ------------------------------------------------------------------
    // 七个上下文提供者（全部可选：失败由 PrivatePromptAssembler 的 optionalRead 兜底）
    // ------------------------------------------------------------------

    private fun memoryAllowed(source: String): Boolean =
        settings.memoryV2Enabled && settings.isMemoryInjectionAllowed("private_chat", source)

    private fun buildProviders(): PrivateContextProviders = PrivateContextProviders(
        shortTermMemory = { sessionId ->
            runCatching { repo.getShortTermMemory(sessionId)?.content?.takeIf { it.isNotBlank() } }.getOrNull()
        },
        groupContext = { operatorId, query -> buildPrivateGroupContext(operatorId, query) },
        stableImpression = { operatorId ->
            if (memoryAllowed("PRIVATE_CHAT")) memoryV2.buildPrivateStableImpression(operatorId) else ""
        },
        personalMemory = { operatorId, query ->
            val sources = memoryV2.privateChatAllowedSources()
            if (sources.isEmpty() || !memoryAllowed("PRIVATE_CHAT")) "" else memoryV2.buildPrivateChatMemoryContext(
                operatorId = operatorId,
                query = query,
                allowedSources = sources,
                allowPrivateVisualRecall = MemorySourceKind.PRIVATE_CHAT.name in sources,
            )
        },
        relationshipMemory = { operatorId, query ->
            if (memoryAllowed("RELATIONSHIP")) memoryV2.buildRelationshipPrivateMemoryContext(operatorId, query) else ""
        },
        publicMemory = { query ->
            val sources = memoryV2.privateChatAllowedSources()
                .intersect(setOf(MemorySourceKind.MOMENT.name, MemorySourceKind.MOMENT_COMMENT.name))
            if (!settings.globalPublicMemoryEnabled || sources.isEmpty()) "无"
            else memoryV2.buildPublicMemoryContext(query, limit = 2, allowedSources = sources).ifBlank { "无" }
        },
        knowledgeContext = { operatorId, query -> buildKnowledgeContext(operatorId, query) },
    )

    /** oracle `buildPrivateGroupContext` :3708-3728：该干员所在群聊的最近滚动摘要。 */
    private suspend fun buildPrivateGroupContext(operatorId: String, query: String): String {
        if (settings.privateGroupContextCount <= 0) return "无"
        val groups = runCatching {
            repo.sessions.getAllSessionsSync()
                .filter { it.operatorId.startsWith("group") || it.id.startsWith("group") }
                .filter { group -> group.members.split(',').map(String::trim).any { it == operatorId } }
                .sortedByDescending { it.lastTime }
        }.getOrDefault(emptyList())
        if (groups.isEmpty()) return "无"
        val expandedRecall = listOf("群", "大家", "群里", "谁说", "谁提", "之前").any(query::contains)
        val candidates = if (expandedRecall) groups.take(settings.privateGroupContextCount) else groups.take(1)
        val lines = candidates.mapNotNull { group ->
            val restartAt = settings.getSessionRestartAt(group.id)
            runCatching { repo.getShortTermMemory(group.id) }.getOrNull()
                ?.takeIf { restartAt <= 0L || it.createdAt >= restartAt }
                ?.content?.takeIf { it.isNotBlank() }
                ?.let { "- 在群聊「${group.operatorName}」中：${it.take(220)}" }
        }
        return lines.joinToString("\n").ifBlank { "无" }
    }

    /**
     * oracle :3195-3220：先按 `operator_knowledge_assignments.enabled` + 各知识库在
     * `private_chat` 场景的启用开关过滤，再走知识库召回。无分配 → "无"（不触发向量请求）。
     */
    private suspend fun buildKnowledgeContext(operatorId: String, query: String): String {
        if (operatorId.isBlank() || query.isBlank()) return "无"
        val books = runCatching {
            repo.knowledgeBases.getAssignments(operatorId)
                .filter { it.enabled && settings.isKnowledgeBaseEnabledForBook(it.knowledgeBaseId, "private_chat") }
                .mapTo(mutableSetOf()) { it.knowledgeBaseId }
        }.getOrDefault(emptySet())
        if (books.isEmpty()) return "无"
        return runCatching {
            knowledgeBase.recallForPrivateChat(
                operatorId = operatorId,
                query = query,
                assignedBookIds = books,
                candidateLimit = settings.knowledgeBasePrivateCandidateLimit,
            ).text.ifBlank { "无" }
        }.getOrDefault("无")
    }

    // ------------------------------------------------------------------
    // 记忆提取（引擎回复落库后 fire-and-forget；oracle :2250-2266 / :2748-2760）
    // ------------------------------------------------------------------

    private val excludedTypes = setOf("system", "send_failed", "gift_reply_failed")

    /**
     * 回复落库后按 oracle 维护语义读取「游标之后」的最近消息窗口：
     * 未达到提取阈值（`privateMemoryExtractionThreshold`，默认 12）→ 直接返回 false；
     * 达到阈值 → `ingestPrivateChat`，成功后推进 `memory_extraction_cursor_session_*`。
     *
     * 异常由调用方（ChatEngineBridge）捕获并静默记录：记忆提取绝不影响回复。
     */
    suspend fun ingestPrivateChatAfterReply(sessionId: String): Boolean {
        if (!settings.memoryV2Enabled || !settings.privateMemoryGenerationEnabled) return false
        val session = repo.sessions.getSession(sessionId) ?: return false
        if (session.operatorId.startsWith("group_")) return false
        val threshold = settings.privateMemoryExtractionThreshold.coerceAtLeast(1)
        val cursor = settings.getMemoryExtractionCursor(sessionId)
        val restartAt = settings.getSessionRestartAt(sessionId)
        val page = repo.getMessagesAfterCursor(sessionId, cursor, restartAt, threshold.toLong())
        val batch = page.filter { it.type !in excludedTypes }
        if (batch.size < threshold) return false
        val ok = memoryV2.ingestPrivateChat(
            sessionId = sessionId,
            operatorId = session.operatorId,
            operatorName = session.operatorName,
            messages = batch,
            currentRound = settings.getSessionMessageCounter(sessionId),
        )
        if (ok) {
            batch.maxOfOrNull { it.id }?.let { settings.putMemoryExtractionCursor(sessionId, it) }
            settings.putBoolean("archive_private_recall_ready_$sessionId", true)
        }
        return ok
    }

    // ------------------------------------------------------------------
    // 自检辅助（仅 --prompt-wiring-selftest；不参与用户路径）
    // ------------------------------------------------------------------

    suspend fun debugBuildPrompt(session: ChatSession, userContent: String): List<AiMessage> =
        promptAssembler.build(
            PromptBuildRequest(session = session, userContent = userContent, mode = session.mode),
        )

    suspend fun debugSeedL3Memory(session: ChatSession, content: String): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        return repo.memoryV2.insertMemoryItem(
            MemoryItem(
                ownerType = "operator",
                ownerId = session.operatorId,
                memoryLevel = MemoryLevel.L3,
                memoryType = "evaluation_opinion",
                sourceKind = MemorySourceKind.PRIVATE_CHAT,
                sourceRefId = "t45-selftest",
                sessionId = session.id,
                content = content,
                nickname = userNickname(),
                importance = 88,
                privacy = "private",
                createdAt = now,
                updatedAt = now,
                expiresAt = Long.MAX_VALUE,
            ),
        )
    }

    suspend fun debugMemoryItems(operatorId: String): List<MemoryItem> =
        runCatching { repo.memoryV2.getMemoryItemsByOwner("operator", operatorId) }.getOrDefault(emptyList())

    suspend fun debugMessageCount(sessionId: String): Long =
        runCatching { repo.countMessages(sessionId) }.getOrDefault(0L)

    /** 自检发送辅助：直接驱动引擎一轮（不经过 UI 打字机回调），返回是否成功落库。 */
    suspend fun debugRecentVisibleText(sessionId: String, limit: Long = 2): List<ChatMessage> =
        runCatching { repo.getRecentMessagesSync(sessionId, limit) }.getOrDefault(emptyList())
}
