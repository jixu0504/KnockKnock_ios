<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:1510-1624
  Decl  : in_historyStats
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
.trimIndent()))
                DebugLogger.chatEvent("群聊", "请求模型", "开始", "群=$groupName，模式=$mode，成员=${activeMembers.size}，自动=$isAuto")
                DebugLogger.attachOperationModule(debugRoundId, "完整请求", sharedUtils.logAiCallText(apiMessages), sensitive = true)
                if (groupProvenanceEntries.isNotEmpty()) {
                    val provenanceSummary = com.rhodes.privatechat.util.ContextProvenance.compactSummary(groupProvenanceEntries)
                    DebugLogger.conversationStep(debugRoundId, "群聊", "上下文引用", "本轮", provenanceSummary)
                    // 只用一个模块：详情页的标签页是模块名，模块太多会把标签挤到显示不出来。
                    DebugLogger.attachOperationModule(
                        debugRoundId, "上下文引用",
                        com.rhodes.privatechat.util.ContextProvenance.detail(
                            "群聊", groupProvenanceEntries,
                            if (DebugLogger.allowSensitiveTrace) {
                                groupKbRecall?.hits?.map { "【知识库：${it.book}】${it.snippet.take(160)}（相似 ${(it.score * 100).toInt() / 100.0}）" }.orEmpty()
                            } else {
                                emptyList()
                            },
                        ),
                    )
                }
                DebugLogger.conversationStep(debugRoundId, "群聊", "模型请求", "开始", "成员=${activeMembers.joinToString("、") { it.name }}，自动=$isAuto，消息数=${apiMessages.size}")
                // In automatic mode, send requested history first and retry only after the
                // provider reports its real context limit.
                // 手动上限模式必须为本次输出留余量，深度思考开启时思维链还要额外占一份。
                val maxPromptTokens = (settings.maxContextTokens - sharedUtils.promptOutputReserveTokens()).coerceAtLeast(512)
                var totalTokens = apiMessages.sumOf { estimateTokens(it.content) + 10 }
                com.rhodes.privatechat.util.DebugLogger.log("GroupChat/Token", if (settings.automaticContextWindow) "自动上下文：跳过本地 token 裁剪，超限交由模型服务返回后重试；消息数=${apiMessages.size}" else "估算token=$totalTokens, 上限=$maxPromptTokens, ${sharedUtils.promptOutputReserveNote()}, 消息数=${apiMessages.size}")
                if (!settings.automaticContextWindow) {
                    val removed = com.rhodes.privatechat.shared.model.trimPromptHistory(
                        apiMessages, remainingHistoryCount, maxPromptTokens, ::estimateTokens
                    )
                    remainingHistoryCount -= removed
                }
                DebugLogger.conversationStep(debugRoundId, "群聊", "上下文统计", "完成", historyStats())
                val validSpeakers = activeMembers.map { it.name }.toSet() + "旁白"
                val membersById = activeMembers.associateBy { it.id }
                val membersByName = activeMembers.groupBy { it.name }
                val promptElapsedMs = turnElapsedMs()
                if (promptElapsedMs > GROUP_PROMPT_TIMEOUT_MS) {
                    throw ChatStageTimeoutException("group", "prompt_build", GROUP_PROMPT_TIMEOUT_MS, promptElapsedMs)
                }
                DebugLogger.conversationStep(debugRoundId, "群聊", "提示词总构建", "完成", "预算=${GROUP_PROMPT_TIMEOUT_MS}ms；耗时=${promptElapsedMs}ms；消息=${apiMessages.size}条")
                suspend fun generateGroupReply(messages: List<AiMessage>, tag: String, stage: String): String {
                    val budget = minOf(groupModelTimeoutMs, remainingTurnBudget())
                    pipelineStage = stage
                    // 每位成员多一行【心情】后输出变长约 10~15%，显式给出群聊输出预算，避免台词被截断。
                    val outputBudget = GroupOutputBudget.groupMaxOutputTokens(settings.provider)
                    return withChatStageTimeout("group", stage, budget) {
                        cacheUsage.recordAttempt()
                        sharedUtils.chatResult(messages, tag, maxOutputTokens = outputBudget).also(cacheUsage::record).content
                    }.trim()
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                }
                fun normalizeReply(raw: String): List<GroupMsgResult> {
                    val extracted = extractTaggedGroupResults(raw, membersById, membersByName, groupName).ifEmpty { extractGroupResults(raw) }
                    logRawGroupReplyStructure(extracted, validSpeakers, mode)
                    val normalized = normalizeGroupResults(extracted, validSpeakers, mode)
                    val fallback = if (normalized.isEmpty()) {
                        normalizeGroupResults(extractSpeakerLines(raw, activeMembers, groupName), validSpeakers, mode)
                    } else emptyList()
                    if (fallback.isNotEmpty()) {
                        DebugLogger.conversationStep(
                            debugRoundId,
                            "群聊",
                            "返回解析",
                            "已兼容恢复",
                            "模型漏写【发言人: 发言标识】标签，已将裸发言标识或唯一成员名映射为当前成员：${fallback.filter { it.type == "dialogue" }.joinToString("、") { it.speaker }}"
                        )
                    }
                    val candidates = if (normalized.isNotEmpty()) normalized else fallback
                    return candidates
                        .takeIf { isDisplayableGroupReply(it) }
                        .orEmpty()
                }
                val initialMessageCount = apiMessages.size
                var contextRetryCount = 0
                var rawBase: String
                while (true) {
                    try {
                        rawBase = generateGroupReply(apiMessages, "GroupChat#$debugRoundId", "model_primary_request")
                        break
                    } catch (error: Exception) {
                        if (!isContextLimitError(error) || contextRetryCount >= 6) throw error
                        val historyCount = remainingHistoryCount
                        if (historyCount == 0) throw error
                        // Keep the newest ten history messages where possible; group messages can
                        // contain several speakers, so round boundaries are not reliable here.
                        val targetHistoryCount = when {
                            historyCount > 20 -> (historyCount / 2).coerceAtLeast(10)
                            historyCount > 10 -> 10
                            historyCount > 5 -> 5
                            else -> 1
                        }
                        val removeCount = (historyCount - targetHistoryCount).coerceAtLeast(1)
                        repeat(removeCount) { apiMessages.removeAt(1) }
                        remainingHistoryCount -= removeCount
                        contextRetryCount++
                        DebugLogger.diagnostic("Context/History/特殊", "surface=group, groupId=$groupSessionId, initialMessages=$initialMessageCount, previousHistory=$historyCount, remainingHistory=$targetHistoryCount, retry=$contextRetryCount, reason=服务端拒绝上下文长度, error=${error.message?.take(240)}")
                        DebugLogger.conversationStep(debugRoundId, "群聊", "模型请求", "重试", "上下文超限，已裁剪较早历史，保留目标=${targetHistoryCount}条")
                    }
                }
                sharedUtils.trackTokens("group", apiMessages, rawBase)
                DebugLogger.attachOperationModule(debugRoundId, "AI原始返回", rawBase, sensitive = true)
                var filtered = normalizeReply(rawBase)
                var contentRetried = false
                logGroupReplyStructure(filtered, activeMembers.map { it.name }.toSet(), mode)
                if (filtered.isEmpty()) {
                    contentRetried = true
                    DebugLogger.chatEvent("群聊", "内容重试", "开始", "首次输出不可用")
                    DebugLogger.conversationStep(debugRoundId, "群聊", "返回解析", "失败", groupReplyFailureReason(rawBase, activeMembers, groupName, mode))
                    DebugLogger.conversationStep(debugRoundId, "群聊", "内容重试", "开始", "首次输出没有可展示的当前成员台词")
                    // The format model only preserves content. Regenerate once when the reply still
                    // lacks the minimum visible structure for the current mode.
                    DebugLogger.trace("AI/GroupContentRetry", "ORIGINAL_UNUSABLE_RESPONSE\n$rawBase")
                    val retryMessages = apiMessages.mapIndexed { index, message ->
                        if (index == 0 && message.role == "system") message.copy(content = message.content + 
