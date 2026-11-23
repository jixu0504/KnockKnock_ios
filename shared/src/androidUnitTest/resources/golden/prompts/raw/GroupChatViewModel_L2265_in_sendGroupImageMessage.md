<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2265-2428
  Decl  : in_sendGroupImageMessage
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

                ))?.text?.take(2000).orEmpty().ifBlank { throw IllegalStateException("没有识别到图片内容") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.log("Vision", "群聊识图失败: ${e.message?.take(100)}")
                val failedJson = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(mapOf(
                    "imageUri" to kotlinx.serialization.json.JsonPrimitive(imageUri),
                    "caption" to kotlinx.serialization.json.JsonPrimitive(caption.trim()),
                    "visionSummary" to kotlinx.serialization.json.JsonPrimitive(""),
                    "status" to kotlinx.serialization.json.JsonPrimitive("failed")
                )))
                repository.updateMessageContent(id, failedJson)
                _lastSendError.value = "图片识别失败，请检查识图模型设置后重试。"
                persistenceReceipt.complete(false)
                onReplyComplete(false)
                return@launch
            }
            if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
            val cleanText = visionText.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
            val visionSummary = runCatching {
                json.parseToJsonElement(cleanText).jsonObject["visibleSummary"]?.jsonPrimitive?.content
            }.getOrNull()?.take(500)
            val imageJson = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(mapOf(
                "imageUri" to kotlinx.serialization.json.JsonPrimitive(imageUri),
                "caption" to kotlinx.serialization.json.JsonPrimitive(caption.trim()),
                "visionSummary" to kotlinx.serialization.json.JsonPrimitive(visionText.take(800))
            )))
            repository.updateMessageContent(id, imageJson)
            val promptText = buildString {
                append("用户在群聊中发送了一张图片。")
                if (caption.isNotBlank()) append("\n用户附带文字：${caption.trim()}")
                append("\n【用户发送的图片分析】")
                append("\n画面内容：${visionSummary ?: visionText.take(500)}")
                append("\n请所有在场成员根据自己的性格、关系和当前群聊氛围自然回应这张图片。")
            }
            saveVisionVectorMemory(groupSessionId, caption, visionSummary ?: visionText)
            // The image message above is the only user-visible record. The vision result is AI-only context.
            if ((groupGenerations[groupSessionId] ?: 0L) != generation) return@launch
            sendGroupMessage(groupSessionId, groupName, promptText, mode, userMessageAlreadyStored = true, sourceMessageId = id, replyTurnIdOverride = replyTurnId, replyLeaseTokenOverride = replyLeaseToken, onResponseComplete = onReplyComplete)
            handedOffToGroupReply = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (imageMessageId != 0L) {
                val failedJson = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(mapOf(
                    "imageUri" to kotlinx.serialization.json.JsonPrimitive(imageUri),
                    "caption" to kotlinx.serialization.json.JsonPrimitive(caption.trim()),
                    "visionSummary" to kotlinx.serialization.json.JsonPrimitive(""),
                    "status" to kotlinx.serialization.json.JsonPrimitive("failed")
                )))
                repository.updateMessageContent(imageMessageId, failedJson)
            }
            throw e
        } catch (e: Exception) {
            _lastSendError.value = classifyGroupError(e)
            persistenceReceipt.complete(false)
            onReplyComplete(false)
        } finally {
            persistenceReceipt.complete(false)
            if (!handedOffToGroupReply && replyTurnId.isNotBlank() && replyLeaseToken.isNotBlank()) {
                runCatching { repository.releaseReplyTurn(replyTurnId, replyLeaseToken, System.currentTimeMillis(), System.currentTimeMillis(), "image_reply_not_completed") }
            }
            if (visionMutexLocked) mutexFor(groupSessionId).unlock()
            if (groupAiJobs[groupSessionId] == coroutineContext[Job]) groupAiJobs.remove(groupSessionId)
        }
    }
}

private fun formatGroupImageForPrompt(msg: ChatMessage): String = try {
    val obj = json.parseToJsonElement(msg.content).jsonObject
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty()
    val rawSummary = obj["visionSummary"]?.jsonPrimitive?.content.orEmpty()
    val cleanSummary = rawSummary.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
    val summary = runCatching {
        json.parseToJsonElement(cleanSummary).jsonObject["visibleSummary"]?.jsonPrimitive?.content
    }.getOrNull() ?: rawSummary.take(200)
    buildString {
        append("用户发送图片")
        if (summary.isNotBlank()) append("：$summary")
        if (caption.isNotBlank()) append("；附言：$caption")
    }
} catch (_: Exception) { "用户发送了一张图片" }

private fun isVisionConfigured(): Boolean =
    settings.hasVisionConfiguration()

private fun currentVisionGateway(): VisionGateway = createVisionGateway(settings)

private suspend fun saveGroupAnchorToVector(anchor: MemoryAnchor, groupSessionId: String, groupName: String) {
    if (!settings.memoryV2Enabled || !settings.groupMemoryGenerationEnabled) return
    if (!repository.saveAnchor(anchor)) return
    val service = memoryVectorService ?: return
    val now = anchor.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    val importance = if (anchor.importance == AnchorSourcePolicy.WEAK) 0.25 else 0.6
    val content = "在群聊「$groupName」中，${anchor.sourceActor.ifBlank { "成员" }}提到：${anchor.content}".take(180)
    try {
        service.saveMemory(VectorMemory(
            id = "anchor_${anchor.operatorId}_${now}_${anchor.content.hashCode()}",
            ownerType = "operator",
            ownerId = anchor.operatorId,
            sourceType = "anchor_${anchor.source.ifBlank { anchor.type.name.lowercase() }}",
            sourceId = anchor.sessionId,
            content = content,
            importance = importance,
            tags = anchor.type.name,
            visibility = "public",
            createdAt = now,
            expiresAt = anchor.expiresAt
        ))
        service.saveMemory(VectorMemory(
            id = "group_anchor_${groupSessionId}_${anchor.operatorId}_${now}_${anchor.content.hashCode()}",
            ownerType = "group",
            ownerId = groupSessionId,
            sourceType = "anchor_${anchor.source.ifBlank { anchor.type.name.lowercase() }}",
            sourceId = anchor.sessionId,
            content = content,
            importance = importance,
            tags = anchor.type.name,
            visibility = "public",
            createdAt = now,
            expiresAt = anchor.expiresAt
        ))
    } catch (e: Exception) {
        DebugLogger.log("Vector/Save", "群聊锚点向量写入失败: ${e.message?.take(80)}")
    }
}

private suspend fun saveVisionVectorMemory(groupSessionId: String, caption: String, visionText: String) {
    if (!settings.memoryV2Enabled || !settings.groupMemoryGenerationEnabled) return
    if (visionText.isBlank() || visionText.startsWith("[")) return
    val content = buildString {
        append("群聊图片识图内容：${visionText.take(500)}")
        if (caption.isNotBlank()) append("；用户附带文字：${caption.take(120)}")
    }
    memoryV2Pipeline.ingestVision(
        ownerType = "group", ownerId = groupSessionId,
        sourceKind = MemorySourceKind.GROUP_CHAT,
        sourceRefId = "$groupSessionId:vision:${System.currentTimeMillis()}",
        content = content, isPrivate = false
    )
}

private fun notifyIfBackground(title: String, content: String, sessionId: String?) {
    if (!RhodesAppVisibility.isForeground) {
        showNotification(title, content.take(120), sessionId)
    }
}

fun clearSendError() { _lastSendError.value = "" }

private suspend fun markGroupUnreadIfNotCurrent(groupSessionId: String, count: Int = 1) {
    if (_currentGroupId.value != groupSessionId) {
        repository.incrementUnread(groupSessionId, count)
        unhideSession(groupSessionId)
    }
}

private fun visibleGroupMessageCount(items: List<GroupMsgResult>, mode: String): Int =
    items.count { mode != "online" || (it.type != "narration" && it.speaker != "旁白") }

private fun classifyGroupError(e: Exception): String = when {
    e.message?.contains("401") == true || e.message?.contains("api key", true) == true -> "API Key 无效或已过期，请在设置中检查"
    e.message?.contains("402") == true || e.message?.contains("insufficient", true) == true || e.message?.contains("quota") == true -> "API 余额不足，请充值后重试"
    e.message?.contains("429") == true -> "AI 服务请求太频繁，请稍后重试"
    Regex(
