<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2159-2257
  Decl  : in_containsFirstPersonNarration
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
).containsMatchIn(outsideQuotes)
    }

    private fun isContextLimitError(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("too many tokens", true) ||
            (message.contains("context", true) && (
                message.contains("length", true) || message.contains("limit", true) ||
                    message.contains("window", true) || message.contains("token", true) ||
                    message.contains("maximum", true) || message.contains("exceed", true)
                )) ||
            (message.contains("上下文", true) && message.contains("超", true))
    }

    private fun estimateTokens(content: String): Int {
        var total = 0
        for (ch in content) total += if (ch.code <= 0x7F) 1 else 2
        return (total * 1.2).toInt()
    }

    fun sendGroupImageMessage(groupSessionId: String, groupName: String, imageUri: String, imageForModel: String?, caption: String, mode: String = "online", existingMessageId: Long? = null, replyLeaseTokenOverride: String? = null, onMessageSent: () -> Unit = {}, onResult: (Boolean) -> Unit = {}, onReplyComplete: (Boolean) -> Unit = {}) {
        val persistenceReceipt = com.rhodes.privatechat.util.MessagePersistenceReceipt(onResult)
        if (groupSessionId.isBlank()) { persistenceReceipt.complete(false); return }
        sharedUtils.chatConfigurationError()?.let { error ->
            _lastSendError.value = error
            persistenceReceipt.complete(false)
            return
        }
        if (!isVisionConfigured()) {
            _lastSendError.value = "图片聊天需要先设置识图模型，请在模型设置中填写识图地址、模型名和密钥。"
            persistenceReceipt.complete(false)
            return
        }
        if (imageForModel.isNullOrBlank()) {
            _lastSendError.value = "无法读取这张图片，请重新选择后再试。"
            persistenceReceipt.complete(false)
            return
        }
        val generation = groupGenerations[groupSessionId] ?: 0L
        val imageJob = scope.launch {
            var visionMutexLocked = false
            var imageMessageId = 0L
            var replyTurnId = ""
            var replyLeaseToken = ""
            var handedOffToGroupReply = false
            try {
                groupAiJobs[groupSessionId] = coroutineContext[Job]!!
                if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                // Reserve the group queue before the placeholder is visible, so no text/auto turn
                // can build history against an image that has not been analyzed yet.
                mutexFor(groupSessionId).lock()
                visionMutexLocked = true
                val id = existingMessageId ?: repository.getNextMessageId()
                imageMessageId = id
                val placeholderJson = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(mapOf(
                    "imageUri" to kotlinx.serialization.json.JsonPrimitive(imageUri),
                    "caption" to kotlinx.serialization.json.JsonPrimitive(caption.trim()),
                    "visionSummary" to kotlinx.serialization.json.JsonPrimitive("")
                )))
                if (existingMessageId == null) {
                    val imageTimestamp = System.currentTimeMillis()
                    val turnId = "group:$groupSessionId:$id"
                    replyTurnId = turnId
                    repository.sendMessageAndCreateReplyTurn(groupSessionId, ChatMessage(
                        id = id,
                        sessionId = groupSessionId,
                        senderName = "我",
                        content = placeholderJson,
                        type = "image",
                        mode = mode,
                        timestamp = imageTimestamp,
                        isMe = true
                    ), com.rhodes.privatechat.shared.model.ReplyTurn(
                        turnId, groupSessionId, "group", "image", id, "", mode,
                        "pending", 0, imageTimestamp, "", 0, null, "", imageTimestamp, imageTimestamp, 0,
                    ))
                } else {
                    replyTurnId = "group:$groupSessionId:$id"
                    val now = System.currentTimeMillis()
                    if (repository.replyTurns.get(replyTurnId) == null) {
                        repository.createReplyTurn(com.rhodes.privatechat.shared.model.ReplyTurn(
                            replyTurnId, groupSessionId, "group", "image", id, "", mode,
                            "pending", 0, now, "", 0, null, "", now, now, 0,
                        ))
                    }
                    repository.updateMessageContent(id, placeholderJson)
                }
                if (existingMessageId == null) onMessageSent()
                persistenceReceipt.complete(true)
                if (existingMessageId == null) runCatching { com.rhodes.privatechat.automation.ManualReplyScheduler.scheduleTurn(context, replyTurnId) }
                replyLeaseToken = replyLeaseTokenOverride ?: UUID.randomUUID().toString()
                if (replyLeaseTokenOverride == null && repository.claimReplyTurn(replyTurnId, replyLeaseToken, System.currentTimeMillis(), System.currentTimeMillis() + groupReplyTimeoutMs) == null) { persistenceReceipt.complete(true); return@launch }
                unhideSession(groupSessionId)
                // The message is safely persisted now. The composer must never wait for vision/AI work.
                if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
                val visionText = try {
                    currentVisionGateway().analyzeImage(VisionAnalyzeRequest(
                        imageUrlOrBase64 = imageForModel,
                        prompt = 
