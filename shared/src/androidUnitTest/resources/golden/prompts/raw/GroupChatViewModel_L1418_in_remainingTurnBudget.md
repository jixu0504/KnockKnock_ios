<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:1418-1505
  Decl  : in_remainingTurnBudget
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
.trimMargin() */
                 val customGroupProtocol = settings.getCustomPromptModuleOrNull("protocol", "group", templateMode)
                     ?.let { sharedUtils.applyTemplate(it, grpReplacements) }
                 val requiredGroupProtocol = sharedUtils.applyTemplate(PromptModuleDefaults.outputProtocol("group", mode), grpReplacements)
                 // Shipped protocol changes must reach existing installs even when a legacy
                 // protocol module was saved before the current tagged group format existed.
                 val groupProtocol = customGroupProtocol?.let {
                     "【用户自定义表达补充】\n$it\n\n【应用固定输出协议，必须优先遵守】\n$requiredGroupProtocol"
                 } ?: requiredGroupProtocol
                  val narrationProtocol = PromptModuleDefaults.narrationProtocol(mode)
                  val systemWithCustomProtocol = listOf(
                      finalSystemPrompt,
                      groupProtocol,
                      narrationProtocol
                  ).filter { it.isNotBlank() }.joinToString("\n\n")
                val historyLimit = settings.historyMessages
                val activeNames = activeMembers.map { it.name }.toSet() + "我" + "系统"
                val pendingModeTransition = settings.getPendingGroupModeTransition(groupSessionId)
                val allHistory = repository.getMessagesSync(groupSessionId).let { msgs ->
                    val restartAt = settings.getSessionRestartAt(groupSessionId)
                    val currentConversation = if (restartAt > 0L) msgs.filter { it.timestamp >= restartAt } else msgs
                    val dialogueHistory = recentGroupRounds(currentConversation.filter { it.type != "send_failed" && it.type != "gift_reply_failed" }, historyLimit)
                    val recentSystemEvents = currentConversation.filter { it.type == "system" }.takeLast(3)
                    (dialogueHistory + recentSystemEvents)
                        .distinctBy { it.id }
                        .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                        .filter { msg ->
                            (msg.id !in batchIds && msg.id != retryMessageId && msg.id != sourceMessageId) &&
                                (msg.isMe || msg.type == "system" || msg.type == "ai_json" || msg.senderName in activeNames)
                        }
                }.toMutableList()
                // Keep the pre-stored media row in history, but remove a non-batched trailing text row.
                if (!isAuto && batchIds.isEmpty() && !userMessageAlreadyStored && allHistory.lastOrNull()?.isMe == true) {
                    allHistory.removeAt(allHistory.lastIndex)
                }
                 val apiMessages = mutableListOf(AiMessage("system", systemWithCustomProtocol))
                allHistory.forEach { msg ->
                        val formatted = formatGroupHistoryForPrompt(msg, activeMembers.map { member -> member.name }.toSet())
                    if (formatted.isNotBlank()) {
                        apiMessages.add(AiMessage(if (msg.isMe) "user" else "assistant", formatted))
                    }
                }
                var remainingHistoryCount = apiMessages.size - 1
                val groupHistoryRows = allHistory.filter { formatGroupHistoryForPrompt(it, activeMembers.map { member -> member.name }.toSet()).isNotBlank() }
                fun historyStats(): String {
                    val kept = groupHistoryRows.takeLast(remainingHistoryCount)
                    val rounds = kept.filter { it.type != "system" }.chunkedByGroupRound().size
                    return "设置回看=${settings.historyMessages}轮，实际历史=$rounds 轮，历史消息=$remainingHistoryCount 条，附加消息=${apiMessages.size - remainingHistoryCount}条（厂商可能合并同角色消息，重试另计）"
                }
                val runtimeContext = com.rhodes.privatechat.viewmodel.shared.GroupRuntimeContext.build(
                    continuity = groupContinuityBlock,
                    customRuntime = settings.getCustomPromptModuleOrNull("runtime", "group", templateMode),
                    naturalRuntime = naturalRuntimeContext,
                    replacements = grpReplacements,
                    render = { sharedUtils.applyTemplate(it, grpReplacements) },
                    memberIdentifiers = activeMembers.sortedBy { it.id }.joinToString("；") { "${it.name}=${it.id}" },
                    templateRuntime = { excluded ->
                        if (isCustomTemplate) com.rhodes.privatechat.viewmodel.shared.CachePromptLayering.runtimeContext(
                            grpTpl, grpReplacements,
                            com.rhodes.privatechat.data.PromptPlaceholderRegistry.runtimeKeys("group", templateMode),
                            excluded, deduplicateAliases = true,
                        ) else ""
                    },
                )
                if (!isAuto) {
                    val userMsg = when {
                        autoSpeak -> "（群聊已空闲一段时间，干员们自然地闲聊起来，无需等待用户发言。）"
                        userMessageAlreadyStored || retryMessageId != null -> text
                        else -> requestText
                    }
                    val transitionContext = pendingModeTransition.takeIf { it.isNotBlank() }
                        ?.let { "【本轮互动变化】\n$it\n请从本轮开始按这项变化自然回应。\n" }.orEmpty()

                    val hasManualRuntimeContext = (transitionContext + runtimeContext).isNotBlank()
                    // A mode transition belongs to this turn, not to the cacheable system prefix.
                    if (hasManualRuntimeContext) apiMessages.add(AiMessage("user", "$transitionContext$runtimeContext".trim()))
                    if (autoSpeak) {
                        apiMessages.add(AiMessage("user", "【本轮续聊任务】\n$userMsg"))
                    } else {
                        apiMessages.add(AiMessage("user", "【用户本轮消息】\n用户：$userMsg"))
                    }
                } else if (pendingModeTransition.isNotBlank()) {
                    // Auto turns still need the transition, but it must not destabilize the system prefix.
                    apiMessages.add(AiMessage("user", "【本轮互动变化】\n$pendingModeTransition\n请从本轮开始按这项变化自然回应。$runtimeContext"))
                } else if (isAuto) {
                    apiMessages.add(AiMessage("user", runtimeContext))
                }
                apiMessages.add(AiMessage("user", 
