<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2466-2522
  Decl  : in_untrustedGroupRepairInput
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
.trimIndent()

    /** Parses tagged output without allowing unknown members to enter persisted group history. */
    private fun extractTaggedGroupResults(
        raw: String,
        membersById: Map<String, Operator>,
        membersByName: Map<String, List<Operator>>,
        groupName: String
    ): List<GroupMsgResult> = GroupTaggedReplyParser.extractTagged(raw) { reference ->
        // Stable IDs are preferred. A display name is accepted only when unique.
        val member = membersById[reference]
            ?: membersByName[reference]?.singleOrNull()?.takeIf { reference != groupName }
        member?.let { GroupTaggedReplyParser.SpeakerIdentity(it.name, it.id) }
    }

    /** 把历史心情按 speakerId（老数据按显示名）整理成回退扫描用的列表。 */
    private suspend fun trackedEmotionsFromHistory(
        groupSessionId: String,
        excludeMessageId: Long = -1L,
        roundLimit: Int = 20
    ): List<GroupEmotionRules.TrackedEmotion> {
        // 存档隔离：剧情存档上下文与“重新开始群聊”之后都不沿用旧会话的心情（与私聊 archiveContextActive 同一套判断）。
        if (settings.getBoolean("archive_context_active_$groupSessionId", false)) return emptyList()
        val restartAt = settings.getSessionRestartAt(groupSessionId)
        val rounds = repository.getMessagesSync(groupSessionId)
            .filter {
                it.type == "ai_json" && !it.isMe && it.id != excludeMessageId &&
                    (restartAt <= 0L || it.timestamp >= restartAt)
            }
            .takeLast(roundLimit)
        return rounds.flatMap { msg ->
            extractGroupResults(msg.content)
                .filter { !it.type.equals("narration", true) && it.speaker != "旁白" }
                .mapNotNull { result ->
                    val emotion = GroupEmotionRules.cleanEmotion(result.emotion)
                    if (emotion.isBlank()) null
                    else GroupEmotionRules.TrackedEmotion(
                        speakerId = result.speakerId,
                        speakerName = result.speaker,
                        emotion = emotion,
                        spokenAtMs = msg.timestamp
                    )
                }
        }
    }

    /** 为落库的条目补齐 speakerId，便于改名后仍能按标识归属心情。 */
    private fun enrichGroupSpeakerIds(
        results: List<GroupMsgResult>,
        membersByName: Map<String, List<Operator>>
    ): List<GroupMsgResult> = results.map { result ->
        if (result.type.equals("narration", true) || result.speakerId.isNotBlank() || result.speaker == "旁白") result
        else result.copy(speakerId = membersByName[result.speaker]?.singleOrNull()?.id.orEmpty())
    }

    private fun extractGroupPlotSummary(raw: String): String {
        val tag = Regex(
