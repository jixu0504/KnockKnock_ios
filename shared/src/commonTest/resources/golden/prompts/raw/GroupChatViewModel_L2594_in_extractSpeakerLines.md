<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2594-2690
  Decl  : in_extractSpeakerLines
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
), "")
            val match = linePattern.matchEntire(dialogueLine) ?: return@forEach
            val reference = match.groupValues[1]
            val message = match.groupValues[2]
            val speaker = when {
                reference == "旁白" -> "旁白"
                reference == groupName -> ""
                else -> byId[reference]?.name ?: byUniqueName[reference]?.name.orEmpty()
            }
            if (speaker.isNotBlank() && message.isNotBlank()) {
                results += GroupMsgResult(
                    speaker = speaker,
                    message = message.trim().trim('"', '“', '”'),
                    type = if (speaker == "旁白") "narration" else "dialogue"
                )
            }
        }
        return results
    }

    private fun isStrictGroupJson(raw: String): Boolean = try {
        val cleaned = sharedUtils.aiService.cleanJson(raw)
        json.decodeFromString<List<GroupMsgResult>>(cleaned).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** Format repair is for any locally recovered content that is not canonical JSON. */
    private fun requiresFormatRepair(raw: String, normalized: List<GroupMsgResult>): Boolean =
        !isStrictGroupJson(raw) && normalized.isNotEmpty()

    private suspend fun generateGroupDailySummary(groupSessionId: String, groupName: String) {
        if (!settings.memoryV2Enabled || !settings.groupDailySummaryGenerationEnabled) return
        try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val dayBegin = cal.time
            val dateKey = sharedUtils.beijingSdf("yyyyMMdd").format(dayBegin)
            if (repository.getDailyBySessionAndDate(groupSessionId, dateKey) != null) return
            val dayEnd = java.util.Date(dayBegin.time + 86_400_000)
            val msgs = repository.getMessagesInRange(dayBegin.time, dayEnd.time)
                .filter { it.sessionId == groupSessionId }
            if (msgs.size <= 1) return
            val text = msgs.mapNotNull { formatGroupMessageForMemory(it, 60).takeIf { line -> line.isNotBlank() } }.joinToString("\n")
            if (text.isBlank()) return
            val dateStr = sharedUtils.beijingSdf("yyyy年MM月dd日").format(dayBegin)
            val prompt = "请总结${dateStr}「${groupName}」的聊天记录，生成50-150字的每日摘要。直接输出纯文本。\n${text}"
            val content = withTimeout(50_000) { sharedUtils.chat(listOf(AiMessage("system", prompt)), "Memory") }.trim()
            if (content.isNotBlank()) {
                repository.replaceDailyBySessionAndDate(com.rhodes.privatechat.shared.model.Memory(
                    sessionId = groupSessionId, operatorId = groupSessionId,
                    type = com.rhodes.privatechat.shared.model.MemoryType.DAILY, content = content, keywords = dateKey,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = MemoryPolicy.memoryExpiresAt(settings)
                ), dateKey)
                DebugLogger.log("GroupChat", "群聊每日摘要已生成: $groupSessionId")
            }
        } catch (e: Exception) {
            DebugLogger.log("GroupChat", "群聊每日摘要失败: ${e.message?.take(80)}")
        }
    }

    private suspend fun generateGroupShortTermSummary(groupSessionId: String, groupName: String): Boolean =
        groupSummaryMutexes.computeIfAbsent(groupSessionId) { Mutex() }.withLock {
            generateGroupShortTermSummaryLocked(groupSessionId, groupName)
        }

    private suspend fun generateGroupShortTermSummaryLocked(groupSessionId: String, groupName: String): Boolean {
        if (!settings.groupSummaryGenerationEnabled) return false
        val timelineEpoch = settings.getMemoryTimelineEpoch(groupSessionId)
        var operationId = ""
        try {
            val retain = settings.groupSummaryRawTailRounds
            val restartAt = settings.getSessionRestartAt(groupSessionId)
            val allMessages = repository.getMessagesSync(groupSessionId)
                .filter { it.type != "system" && it.type != "send_failed" && it.type != "gift_reply_failed" && (restartAt <= 0L || it.timestamp >= restartAt) }
            val cursor = if (settings.summaryCursorEnabled) settings.getSummaryCursor(groupSessionId) else 0L
            val source = if (cursor > 0L) allMessages.filter { it.id > cursor } else allMessages
            val rounds = recentGroupRounds(source, Int.MAX_VALUE).chunkedByGroupRound()
            val msgs = rounds.dropLast(retain).take(30).flatten()
            if (msgs.isEmpty()) {
                settings.putRollingSummaryStatus(groupSessionId, com.rhodes.privatechat.shared.settings.SettingsRepository.RollingSummaryStatus(
                    state = "idle", pendingRounds = 0, lastSuccessAt = settings.getRollingSummaryStatus(groupSessionId).lastSuccessAt))
                return false
            }
            operationId = DebugLogger.beginOperation("群聊滚动摘要", groupName, groupSessionId)
            DebugLogger.conversationStep(operationId, "群聊滚动摘要", "开始", "进行中", "处理${msgs.size}条消息，保留最近${retain}轮")
            val text = msgs.mapNotNull { formatGroupMessageForMemory(it, 120).takeIf { line -> line.isNotBlank() } }.joinToString("\n")
            if (text.isBlank()) return false
            val oldSummary = repository.getShortTermMemory(groupSessionId)
                ?.takeIf { restartAt <= 0L || it.createdAt >= restartAt }
                ?.content?.takeIf { it.isNotBlank() } ?: "无"
            val prompt = 
