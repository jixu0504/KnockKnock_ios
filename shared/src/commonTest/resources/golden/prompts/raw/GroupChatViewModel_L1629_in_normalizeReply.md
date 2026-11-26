<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:1629-2091
  Decl  : in_normalizeReply
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
.trimMargin()) else message
                    }
                    rawBase = generateGroupReply(retryMessages, "GroupChatContentRetry#$debugRoundId", "model_content_retry")
                    sharedUtils.trackTokens("group", retryMessages, rawBase)
                    filtered = normalizeReply(rawBase)
                    logGroupReplyStructure(filtered, activeMembers.map { it.name }.toSet(), mode)
                    DebugLogger.conversationStep(debugRoundId, "群聊", "内容重试", if (filtered.isEmpty()) "失败" else "成功", if (filtered.isEmpty()) groupReplyFailureReason(rawBase, activeMembers, groupName, mode) else "已得到${filtered.size}条可展示内容")
                }
                // Counts, lengths, narration order and all-member participation are prompt goals.
                // Keep any readable current-member dialogue instead of supplementing or rejecting it in code.
                if (filtered.isEmpty()) {
                    if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                    if (isAuto && autoGeneration != null && autoChatGenerations[groupSessionId] != autoGeneration) return@launch
                    DebugLogger.log("GroupChat/InvalidResponse", "模型返回内容无法解析为有效群聊, rawChars=${rawBase.length}")
                    DebugLogger.chatEvent("群聊", "返回解析", "失败", "无法得到可展示消息")
                    DebugLogger.conversationStep(debugRoundId, "群聊", "本轮结果", "失败", groupReplyFailureReason(rawBase, activeMembers, groupName, mode))
                    DebugLogger.attachOperationModule(debugRoundId, "模型用量", cacheUsage.summary())
                    DebugLogger.conversationStep(debugRoundId, "群聊", "本轮总览", "失败", "模式=$mode，成员=${activeMembers.size}，自动=$isAuto，构建消息=${apiMessages.size}条，${historyStats()}，原始输出=${rawBase.length}字，原因=${groupReplyFailureReason(rawBase, activeMembers, groupName, mode)}，缓存=${cacheUsage.summary()}")
                    markGroupMessagesUndelivered(groupSessionId, if (batchIds.isNotEmpty()) batchIds else failureMessageId?.let(::setOf).orEmpty(), groupName)
                } else {
                    val dialogueCount = filtered.count { it.type == "dialogue" }
                    val narrationCount = filtered.count { it.type == "narration" }
                    DebugLogger.log("GroupChat/Decision", "最终展示${filtered.size}条消息：成员台词${dialogueCount}条，旁白${narrationCount}条")
                    DebugLogger.chatEvent("群聊", "返回解析", "成功", "台词=$dialogueCount，旁白=$narrationCount")
                    DebugLogger.conversationStep(debugRoundId, "群聊", "返回解析", "成功", "台词=$dialogueCount，旁白=$narrationCount；${groupStructureGap(filtered, activeMembers.map { it.name }.toSet(), mode)}")
                }
                if (filtered.isNotEmpty()) {
                    if (isAuto && autoGeneration != null && autoChatGenerations[groupSessionId] != autoGeneration) return@launch
                    if (isAuto && autoPlanToken != null && autoPlanRevision != null && !GroupAutoChatScheduler.isCurrentClaim(settings, groupSessionId, autoPlanToken, autoPlanRevision)) return@launch
                    if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                    pipelineStage = "ai_reply_id"
                    val aiMsgId = withChatStageTimeout("group", "ai_reply_id", minOf(GROUP_MESSAGE_WRITE_TIMEOUT_MS, remainingTurnBudget())) { repository.getNextMessageId() }
                    val storedResults = enrichGroupSpeakerIds(filtered, membersByName)
                    val storedContent = if (storedResults.isNotEmpty()) {
                        try {
                            json.encodeToString(storedResults)
                        } catch (_: Exception) { rawBase }
                    } else rawBase
                    // 成员心情快照：本轮台词解析到的心情优先，其次是模型【成员心情】字段为本轮已发言成员声明的心情；
                    // 都缺失时按 6 小时窗口回退上一轮快照或最近历史。每轮重新解析并覆盖，重新生成后快照与落库 JSON 保持一致。
                    val parsedTurnState = parseGroupTurnState(rawBase)?.let(::validateGroupTurnState)
                    val emotionResolvedAt = System.currentTimeMillis()
                    val roundEmotions = GroupEmotionRules.resolveRoundEmotions(
                        nowMs = emotionResolvedAt,
                        results = storedResults,
                        declaredSnapshot = parsedTurnState?.memberEmotions.orEmpty(),
                        previousSnapshot = groupTurnState?.memberEmotions.orEmpty(),
                        previousSnapshotAtMs = groupTurnState?.updatedAt ?: 0L,
                        previousInheritedKeys = groupTurnState?.emotionFallbackKeys.orEmpty(),
                        history = trackedEmotionsFromHistory(groupSessionId, excludeMessageId = aiMsgId)
                    )
                    val verifiedState = (parsedTurnState
                        ?: deriveGroupTurnState(userMsg = if (isAuto) "" else text, previous = groupTurnState))
                        .copy(
                            memberEmotions = GroupEmotionRules.formatEmotionSnapshot(roundEmotions.emotions),
                            emotionFallbackKeys = GroupEmotionRules.formatKeyList(roundEmotions.inheritedKeys)
                        )
                    if (roundEmotions.inheritedKeys.isNotEmpty()) {
                        DebugLogger.log("GroupChat/Emotion", "本轮沿用上一轮心情：${roundEmotions.inheritedKeys.joinToString("、")}")
                    }
                    if (parsedTurnState == null) {
                        DebugLogger.conversationStep(debugRoundId, "群聊", "连续性状态", "已保守降级", "模型未输出完整群聊回合状态，已使用当前用户消息和上一有效状态生成保守状态")
                    }
                    pipelineStage = "ai_reply_write"
                    withChatStageTimeout("group", "ai_reply_write", minOf(GROUP_MESSAGE_WRITE_TIMEOUT_MS, remainingTurnBudget())) {
                        check(repository.sendReplyAndCompleteTurn(groupSessionId, ChatMessage(
                            id = aiMsgId, sessionId = groupSessionId,
                            senderName = groupName, content = storedContent,
                            type = "ai_json", mode = mode, isMe = false
                        ), replyTurnId, replyLeaseToken, System.currentTimeMillis())) { "reply turn lease lost" }
                    }
                    settings.putGroupTurnState(groupSessionId, verifiedState.copy(updatedAt = emotionResolvedAt))
                    settings.putGroupPlotSummary(groupSessionId, verifiedState.currentTopic)
                    DebugLogger.traceFinalSaved("群聊", debugRoundId, storedContent)
                    responseStored = true
                    com.rhodes.privatechat.automation.ManualReplyScheduler.completeTurn(context, replyTurnId)
                    batchIds.forEach { messageId ->
                        com.rhodes.privatechat.automation.ManualReplyScheduler.complete(context, messageId)
                    }
                    if (pendingModeTransition.isNotBlank()) {
                        settings.clearPendingGroupModeTransition(groupSessionId)
                    }
                    DebugLogger.chatEvent("群聊", "回复落库", "成功", "群=$groupName，条目=${filtered.size}")
                    DebugLogger.conversationStep(debugRoundId, "群聊", "本轮结果", "成功", "已保存${filtered.size}条群聊内容")
                    DebugLogger.attachOperationModule(debugRoundId, "模型用量", cacheUsage.summary())
                    DebugLogger.conversationStep(debugRoundId, "群聊", "本轮总览", "成功", "模式=$mode，成员=${activeMembers.size}，自动=$isAuto，构建消息=${apiMessages.size}条，${historyStats()}，台词=${filtered.count { it.type == "dialogue" }}，旁白=${filtered.count { it.type == "narration" }}，内容重试=${if (contentRetried) "已执行" else "未执行"}，AI消息ID=$aiMsgId，缓存=${cacheUsage.summary()}")
                    // Auto messages and replies can arrive while the group is open, so unread-based
                    // restoration alone is insufficient after the user removed it from the home page.
                    unhideSession(groupSessionId)
                    notifyIfBackground(groupName, filtered.firstOrNull()?.let { "${it.speaker}：${it.message}" } ?: "群聊有新消息", groupSessionId)
                    val last = filtered.last()
                    repository.updateLastMessage(groupSessionId, "${last.speaker}：${last.message.take(50)}", System.currentTimeMillis())
                    if (!isAuto) {
                        val today = sharedUtils.beijingSdf("yyyyMMdd").format(java.util.Date())
                        settings.grantDailyLmb(today, 50)
                    }
                }
                if (filtered.isNotEmpty()) markGroupUnreadIfNotCurrent(groupSessionId, visibleGroupMessageCount(filtered, mode))
                if (filtered.isNotEmpty()) scheduleGroupPostReplyMaintenance(groupSessionId, groupName)
            } catch (e: ChatStageTimeoutException) {
                if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                if (isAuto && autoGeneration != null && autoChatGenerations[groupSessionId] != autoGeneration) return@launch
                val turnElapsed = if (turnStartedAtMs > 0L) (android.os.SystemClock.elapsedRealtime() - turnStartedAtMs).coerceAtLeast(0L) else 0L
                DebugLogger.diagnostic("ChatTimeout", "surface=group,roundId=$debugRoundId,groupId=$groupSessionId,stage=${e.stage},budgetMs=${e.budgetMs},elapsedMs=${e.elapsedMs},turnBudgetMs=$turnBudgetMs,turnElapsedMs=$turnElapsed,thinking=$deepseekThinkingActive,exceptionType=${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
                DebugLogger.chatEvent("群聊", e.stage.chatStageLabel(), "超时", "群=$groupName，预算=${e.budgetMs}ms，耗时=${e.elapsedMs}ms")
                DebugLogger.conversationStep(debugRoundId, "群聊", e.stage.chatStageLabel(), "失败", "超时；预算=${e.budgetMs}ms，耗时=${e.elapsedMs}ms，未生成或保存AI回复")
                DebugLogger.attachOperationModule(debugRoundId, "模型用量", cacheUsage.summary())
                DebugLogger.conversationStep(debugRoundId, "群聊", "本轮总览", "失败", "模式=$mode，自动=$isAuto，阶段=${e.stage}，预算=${e.budgetMs}ms，耗时=${e.elapsedMs}ms，缓存=${cacheUsage.summary()}")
                failureSnapshot("${e.stage}_timeout", e)
                markGroupMessagesUndelivered(groupSessionId, if (batchIds.isNotEmpty()) batchIds else failureMessageId?.let(::setOf).orEmpty(), groupName)
                if (!isAuto) _lastSendError.value = e.userMessage("群聊")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                if (isAuto && autoGeneration != null && autoChatGenerations[groupSessionId] != autoGeneration) return@launch
                Log.e("GroupChat", "Timeout: ${e.message}")
                DebugLogger.log("GroupChat/Error", "整轮处理超时：${e.message ?: "超过${turnBudgetMs / 1000}秒"}")
                DebugLogger.chatEvent("群聊", "请求模型", "超时", "群=$groupName")
                DebugLogger.conversationStep(debugRoundId, "群聊", "本轮结果", "失败", "模型请求超时")
                DebugLogger.attachOperationModule(debugRoundId, "模型用量", cacheUsage.summary())
                DebugLogger.conversationStep(debugRoundId, "群聊", "本轮总览", "失败", "模式=$mode，自动=$isAuto，原因=模型请求超时，缓存=${cacheUsage.summary()}")
                failureSnapshot("group_pipeline_timeout", e)
                markGroupMessagesUndelivered(groupSessionId, if (batchIds.isNotEmpty()) batchIds else failureMessageId?.let(::setOf).orEmpty(), groupName)
                if (!isAuto) _lastSendError.value = "群聊整轮处理超时（预算${turnBudgetMs / 1000}秒），本轮未生成或保存AI回复，请重试"
            } catch (e: kotlinx.coroutines.CancellationException) {
                markGroupMessagesUndelivered(groupSessionId, if (batchIds.isNotEmpty()) batchIds else failureMessageId?.let(::setOf).orEmpty(), groupName)
                throw e
            } catch (e: Exception) {
                if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                if (isAuto && autoGeneration != null && autoChatGenerations[groupSessionId] != autoGeneration) return@launch
                val errMsg = classifyGroupError(e)
                Log.e("GroupChat", "Error: ${e.message}", e)
                DebugLogger.log("GroupChat/Error", "发送失败: $errMsg")
                DebugLogger.chatEvent("群聊", "请求模型", "失败", errMsg)
                DebugLogger.conversationStep(debugRoundId, "群聊", "本轮结果", "失败", "模型错误：$errMsg")
                DebugLogger.attachOperationModule(debugRoundId, "模型用量", cacheUsage.summary())
                DebugLogger.conversationStep(debugRoundId, "群聊", "本轮总览", "失败", "模式=$mode，自动=$isAuto，错误=${e.javaClass.simpleName}，缓存=${cacheUsage.summary()}")
                failureSnapshot("group_pipeline_error", e)
                markGroupMessagesUndelivered(groupSessionId, if (batchIds.isNotEmpty()) batchIds else failureMessageId?.let(::setOf).orEmpty(), groupName)
                _lastSendError.value = errMsg
            } finally {
                if (!responseStored && replyTurnId.isNotBlank() && replyLeaseToken.isNotBlank()) {
                    runCatching { repository.releaseReplyTurn(replyTurnId, replyLeaseToken, System.currentTimeMillis(), System.currentTimeMillis(), "reply_not_completed") }
                }
                setGroupLoading(groupSessionId, false)
                if (groupAiJobs[groupSessionId] == coroutineContext[Job]) groupAiJobs.remove(groupSessionId)
                if (mutexLocked) mutexFor(groupSessionId).unlock()
                onResponseComplete(responseStored)
            }
            }
        }
    }

    fun sendHiddenGiftMessage(
        groupSessionId: String,
        groupName: String,
        text: String,
        mode: String = "online",
        imageUri: String,
        giftName: String,
        recipientNames: List<String>
    ) {
        scope.launch {
            val id = repository.getNextMessageId()
            val giftTimestamp = System.currentTimeMillis()
            val giftPayload = buildJsonObject {
                put("event", "gift")
                put("prompt", text)
                put("imageUri", imageUri)
                put("giftName", giftName)
                put("recipientNames", buildJsonArray { recipientNames.forEach { add(JsonPrimitive(it)) } })
            }.toString()
            val turnId = "group:$groupSessionId:$id"
            repository.sendMessageAndCreateReplyTurn(groupSessionId, ChatMessage(
                id = id,
                sessionId = groupSessionId,
                senderName = "我",
                content = giftPayload,
                type = "gift_hidden",
                mode = mode,
                timestamp = giftTimestamp,
                isMe = true
            ), com.rhodes.privatechat.shared.model.ReplyTurn(turnId, groupSessionId, "group", "gift", id, "", mode, "pending", 0, giftTimestamp, "", 0, null, "", giftTimestamp, giftTimestamp, 0))
            com.rhodes.privatechat.automation.ManualReplyScheduler.scheduleTurn(context, turnId)
            unhideSession(groupSessionId)
            DebugLogger.chatEvent("送礼", "群聊礼物", "开始", "群=$groupName，礼物=$giftName，收礼人=${recipientNames.joinToString("、")}")
            sendGroupMessage(groupSessionId, groupName, text, mode, userMessageAlreadyStored = true, sourceMessageId = id, replyTurnIdOverride = turnId)
        }
    }

    fun retryFailedMessage(groupSessionId: String, groupName: String, msgId: Long, mode: String) {
        if (!retryingMessageIds.add(msgId)) return
        scope.launch {
            val message = repository.getMessagesSync(groupSessionId)
                .firstOrNull { it.id == msgId && it.isMe && (it.type == "send_failed" || it.type == "gift_reply_failed" || it.type == "image") }
            if (message == null) {
                retryingMessageIds.remove(msgId)
                return@launch
            }
            val isGift = message.type == "gift_reply_failed"
            if (message.type == "image") {
                val image = runCatching { json.parseToJsonElement(message.content).jsonObject }.getOrNull()
                val imageUri = image?.get("imageUri")?.jsonPrimitive?.content.orEmpty()
                val caption = image?.get("caption")?.jsonPrimitive?.content.orEmpty()
                if (imageUri.isBlank()) { retryingMessageIds.remove(msgId); return@launch }
                sendGroupImageMessage(groupSessionId, groupName, imageUri, MainActivity.imageForModel(imageUri), caption, mode, existingMessageId = msgId, onResult = { retryingMessageIds.remove(msgId) })
                return@launch
            }
            if (isGift) DebugLogger.chatEvent("送礼", "群聊礼物", "重试", "group=$groupName，messageId=$msgId")
            else DebugLogger.chatEvent("群聊", "手动重试", "开始", "group=$groupName，messageId=$msgId，mode=$mode")
            sendGroupMessage(
                groupSessionId,
                groupName,
                if (isGift) giftPromptText(message.content) else message.content,
                mode,
                userMessageAlreadyStored = true,
                retryMessageId = msgId,
                onReplyTurnClaimed = { repository.updateMessageType(msgId, if (isGift) "gift_hidden" else "text") },
                onResponseComplete = { retryingMessageIds.remove(msgId) }
            )
        }
    }

    private suspend fun markGroupMessagesUndelivered(groupSessionId: String, messageIds: Set<Long>, groupName: String) {
        if (messageIds.isEmpty()) return
        repository.getMessagesSync(groupSessionId)
            .filter { it.id in messageIds && it.isMe }
            .forEach { message ->
                val type = if (message.type == "gift_hidden") "gift_reply_failed" else "send_failed"
                if (type == "gift_reply_failed") {
                    DebugLogger.chatEvent("送礼", "群聊礼物", "回复失败", "group=$groupName，messageId=${message.id}")
                }
                repository.updateMessageType(message.id, type)
            }
    }

    /** Used by either the foreground timer or WorkManager after both validate the same plan token. */
    suspend fun runScheduledAutoTurn(groupId: String, token: String, expectedGeneration: Long? = null, planRevision: Long? = null): Boolean {
        // Idle chat never preempts a user reply. Do not consume the durable plan until the queue is free.
        while (groupAiJobs[groupId]?.isActive == true && isAutoGroupChatEnabled(groupId)) delay(100)
        val revision = planRevision ?: settings.getGroupAutoChatPlan(groupId).revision
        val plan = GroupAutoChatScheduler.claim(settings, groupId, token, revision) ?: return false
        if (expectedGeneration != null && autoChatGenerations[groupId] != expectedGeneration) {
            GroupAutoChatScheduler.releaseClaim(context, settings, groupId, plan.round - 1, plan.token, plan.revision)
            return false
        }
        val session = repository.getSession(groupId) ?: run {
            GroupAutoChatScheduler.cancel(context, settings, groupId)
            return false
        }
        if (!GroupAutoChatScheduler.isCurrentClaim(settings, groupId, plan.token, plan.revision)) return false
        autoRoundCounts[groupId] = plan.round
        val turnId = "auto:$groupId:${plan.token}"
        val turnNow = System.currentTimeMillis()
        repository.createReplyTurn(com.rhodes.privatechat.shared.model.ReplyTurn(
            turnId, groupId, "group", "group_auto", null, plan.token, getGroupChatMode(groupId),
            "pending", 0, turnNow, "", 0, null, "", turnNow, turnNow, 0,
        ))
        val leaseToken = UUID.randomUUID().toString()
        if (repository.claimReplyTurn(turnId, leaseToken, System.currentTimeMillis(), System.currentTimeMillis() + groupReplyTimeoutMs) == null) {
            GroupAutoChatScheduler.releaseClaim(context, settings, groupId, plan.round - 1, plan.token, plan.revision)
            return false
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            try {
                sendGroupMessage(groupId, session.operatorName, "", getGroupChatMode(groupId), isAuto = true,
                    autoGeneration = expectedGeneration, autoPlanToken = plan.token, autoPlanRevision = plan.revision,
                    replyTurnIdOverride = turnId, replyLeaseTokenOverride = leaseToken, onResponseComplete = { succeeded ->
                        if (succeeded && GroupAutoChatScheduler.isCurrentClaim(settings, groupId, plan.token, plan.revision)) {
                            GroupAutoChatScheduler.scheduleNext(context, settings, groupId, plan.round, plan.token, plan.revision)
                        } else {
                            // A failed automatic turn has no user bubble to retry. Keep the same
                            // round pending for one scheduler retry instead of silently losing it.
                            GroupAutoChatScheduler.releaseClaim(context, settings, groupId, plan.round - 1, plan.token, plan.revision)
                            DebugLogger.log("GroupChat/Auto", "自动群聊未生成，已释放计划等待稍后重试: group=$groupId round=${plan.round}")
                        }
                        if (continuation.isActive) continuation.resume(succeeded)
                    })
            } catch (_: Exception) {
                GroupAutoChatScheduler.releaseClaim(context, settings, groupId, plan.round - 1, plan.token, plan.revision)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    /** A group round starts with a user message; automatic AI batches remain individual rounds. */
    private fun recentGroupRounds(messages: List<ChatMessage>, roundLimit: Int): List<ChatMessage> {
        val dialogueMessages = messages.filter { it.type != "system" && it.type != "send_failed" && it.type != "gift_reply_failed" }
        if (roundLimit <= 0) return dialogueMessages
        val roundStarts = mutableListOf<Int>()
        dialogueMessages.forEachIndexed { index, message ->
            if (message.isMe || (index == 0 && !message.isMe) || (!message.isMe && dialogueMessages[index - 1].isMe.not() && message.type == "ai_json")) {
                roundStarts += index
            }
        }
        if (roundStarts.isEmpty()) return dialogueMessages.takeLast(roundLimit)
        val startIndex = roundStarts.getOrElse((roundStarts.size - roundLimit).coerceAtLeast(0)) { 0 }
        return dialogueMessages.drop(startIndex)
    }

    /** A group round starts with a user message or the first AI row after an earlier AI batch. */
    private fun List<ChatMessage>.chunkedByGroupRound(): List<List<ChatMessage>> {
        if (isEmpty()) return emptyList()
        val rounds = mutableListOf<MutableList<ChatMessage>>()
        forEachIndexed { index, message ->
            val previous = getOrNull(index - 1)
            val startsRound = message.isMe || rounds.isEmpty() || (!message.isMe && previous?.isMe == false && message.type == "ai_json")
            if (startsRound) rounds.add(mutableListOf())
            rounds.last().add(message)
        }
        return rounds
    }

    private fun formatGroupHistoryForPrompt(msg: ChatMessage, activeNames: Set<String>): String {
        if (msg.type == "gift_reply_failed") return ""
        if (msg.type == "gift_hidden") return giftPromptText(msg.content)
        if (msg.type == "image" && msg.isMe) return formatGroupImageForPrompt(msg)
        if (msg.isMe) return "用户：${msg.content}"
        if (msg.type == "system") return "系统：${msg.content}"
        if (msg.type != "ai_json") return "${msg.senderName}：${msg.content}"
        return try {
            val items = extractGroupResults(msg.content)
            if (items.isNotEmpty()) {
                items.filterIndexed { index, item ->
                    !isGroupSegmentRecalled(msg.content, index) &&
                        (item.type.equals("narration", true) || item.speaker == "旁白" || item.speaker in activeNames)
                }
                    .joinToString("\n") { r ->
                        // 只有本轮真实解析到心情的历史行才带（心情：x）；回退值不写进行内，
                        // 避免模型把陈旧情绪当成本轮事实。旁白行不加心情。
                        if (r.type == "narration" || r.speaker == "旁白") {
                            "旁白：${r.message}"
                        } else {
                            val emotion = GroupEmotionRules.cleanEmotion(r.emotion)
                            if (emotion.isBlank()) "${r.speaker}：${r.message}"
                            else "${r.speaker}（心情：$emotion）：${r.message}"
                        }
                    }
                    .ifBlank {
                        logUnavailableGroupHistoryReply(msg, "所有回复片段均已撤回或不属于当前成员")
                        "群聊回复：[上一条消息格式异常]"
                    }
            } else {
                logUnavailableGroupHistoryReply(msg, "历史 ai_json 未提取到可用条目")
                "群聊回复：[上一条消息格式异常]"
            }
        } catch (error: Exception) {
            logUnavailableGroupHistoryReply(msg, "历史 ai_json 解析失败", error)
            "群聊回复：[上一条消息格式异常]"
        }
    }

    private fun logUnavailableGroupHistoryReply(msg: ChatMessage, reason: String, error: Exception? = null) {
        if (!unavailableHistoryReplyIds.add("${msg.sessionId}:${msg.id}")) return
        DebugLogger.diagnostic(
            "HistoryReply/Group/失败",
            "surface=group, sessionId=${msg.sessionId}, messageId=${msg.id}, type=${msg.type}, contentLength=${msg.content.length}, reason=$reason" +
                error?.let { ", error=${it.javaClass.simpleName}:${it.message?.take(160)}" }.orEmpty()
        )
    }

    private fun isGroupSegmentRecalled(content: String, index: Int): Boolean = runCatching {
        val root = json.parseToJsonElement(content)
        val array = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject -> (root["messages"] as? kotlinx.serialization.json.JsonArray)
                ?: (root["segments"] as? kotlinx.serialization.json.JsonArray) ?: return@runCatching false
            else -> return@runCatching false
        }
        array.getOrNull(index)?.let { element ->
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("recalled")?.jsonPrimitive?.content.equals("true", true)
        } == true
    }.getOrDefault(false)

    private fun giftPromptText(content: String): String = runCatching {
        val root = json.parseToJsonElement(content).jsonObject
        root["prompt"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("（用户送出了礼物）")

    private fun formatGroupMessageForMemory(msg: ChatMessage, limit: Int): String {
        if (msg.type == "system") return ""
        if (msg.type == "gift_reply_failed") return ""
        if (msg.type == "gift_hidden") return giftPromptText(msg.content).take(limit)
        if (msg.type == "image" && msg.isMe) return formatGroupImageForPrompt(msg).take(limit)
        if (!msg.isMe && msg.type != "ai_json") return ""
        if (msg.isMe) return "用户：${msg.content.take(limit)}"
        if (msg.type == "system") return "系统：${msg.content.take(limit)}"
        if (msg.type != "ai_json") return "${msg.senderName}：${msg.content.take(limit)}"
        return try {
            val items = extractGroupResults(msg.content)
                .filterIndexed { index, _ -> !isGroupSegmentRecalled(msg.content, index) }
                .takeLast(16)
            if (items.isNotEmpty()) {
                items.joinToString("\n") { r ->
                    if (r.type == "narration" || r.speaker == "旁白") "旁白：${r.message.take(limit)}" else "${r.speaker}：${r.message.take(limit)}"
                }
            } else "群聊回复：[格式异常]"
        } catch (_: Exception) {
            "群聊回复：[格式异常]"
        }
    }

    private fun normalizeGroupResults(results: List<GroupMsgResult>, validSpeakers: Set<String>, mode: String): List<GroupMsgResult> {
        return results.mapNotNull { raw ->
            val stripped = stripSpeakerPrefix(raw.message)
            var speaker = raw.speaker.trim().ifBlank { stripped.first.ifBlank { "旁白" } }
            var message = stripLeakedSegmentLabel(stripped.second.ifBlank { raw.message }).trim()
            var type = if (raw.type.equals("narration", true) || raw.type == "旁白") "narration" else "dialogue"
            if (stripped.first.isNotBlank() && stripped.first in validSpeakers) speaker = stripped.first
            if (speaker == "旁白") type = "narration"
            if (type == "narration") speaker = "旁白"
            if (type == "narration" && containsFirstPersonNarration(message)) return@mapNotNull null
            if (mode == "online" && type == "narration") return@mapNotNull null
            if (message.isBlank() || speaker !in validSpeakers) return@mapNotNull null
            // 心情只跟随它原本归属的成员；发言人被改名或纠正时清空标识，稍后按显示名重新补齐。
            val speakerId = if (speaker == raw.speaker.trim()) raw.speakerId else ""
            GroupMsgResult(
                speaker = speaker,
                message = message,
                type = type,
                emotion = if (type == "narration") "" else GroupEmotionRules.cleanEmotion(raw.emotion),
                speakerId = if (type == "narration") "" else speakerId
            )
        }
    }


    fun resumePersistedReply(groupSessionId: String, msgId: Long, onComplete: (Boolean) -> Unit, replyTurnId: String? = null, replyLeaseToken: String? = null) {
        scope.launch {
            val session = repository.getSession(groupSessionId)
            val message = repository.getMessagesSync(groupSessionId).firstOrNull { it.id == msgId && it.isMe }
            if (session == null || message == null) {
                onComplete(true)
                return@launch
            }
            val isGift = message.type == "gift_reply_failed" || message.type == "gift_hidden"
            repository.updateMessageType(msgId, if (isGift) "gift_hidden" else "text")
            sendGroupMessage(
                groupSessionId = groupSessionId,
                groupName = session.operatorName,
                text = if (isGift) giftPromptText(message.content) else message.content,
                mode = message.mode,
                userMessageAlreadyStored = true,
                retryMessageId = msgId,
                replyTurnIdOverride = replyTurnId,
                replyLeaseTokenOverride = replyLeaseToken,
                onResponseComplete = onComplete
            )
        }
    }

    /** Only real current-member dialogue is required to display and persist a recovered group reply. */
    private fun isDisplayableGroupReply(results: List<GroupMsgResult>): Boolean =
        results.any { it.type == "dialogue" && it.message.isNotBlank() && it.speaker != "旁白" }

    private fun groupStructureGap(results: List<GroupMsgResult>, activeNames: Set<String>, mode: String): String =
        if (isDisplayableGroupReply(results)) "结构可展示" else "缺少可展示的当前成员台词"

    private fun groupReplyFailureReason(raw: String, activeMembers: List<Operator>, groupName: String, mode: String): String {
        val activeNames = activeMembers.map { it.name }.toSet()
        val extracted = extractGroupResults(raw)
        if (extracted.isEmpty()) {
            val bareReferences = Regex(
