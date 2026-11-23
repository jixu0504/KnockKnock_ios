<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2530-2585
  Decl  : in_field
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
)
            val found = tag.find(raw) ?: return ""
            val next = Regex(GroupTaggedReplyParser.NEXT_TAG_PATTERN)
                .find(raw, found.range.last + 1)
            return raw.substring(found.range.last + 1, next?.range?.first ?: raw.length).trim().take(maxLength)
        }
        val topic = field("当前主线", 80)
        if (topic.isBlank()) return null
        return GroupTurnState(
            currentTopic = topic,
            userTurnType = field("用户本轮作用", 16),
            currentAnchor = field("本轮承接", 80),
            turnAdvance = field("本轮新增推进", 100),
            threadStatus = field("主线状态", 16),
            nextFocus = field("下轮焦点", 60),
            memberEmotions = field("成员心情", GroupEmotionRules.SNAPSHOT_MAX_CHARS)
        )
    }

    private fun validateGroupTurnState(candidate: GroupTurnState): GroupTurnState? {
        val allowedUserTypes = setOf("提问", "情绪表达", "邀请", "亲密邀约", "亲密接触", "成人互动请求", "确认", "拒绝", "选择", "补充", "转题", "无用户发言", "不明")
        val allowedStatuses = setOf("继续", "等待用户", "已收束", "已转题")
        if (candidate.currentTopic.isBlank() || candidate.userTurnType !in allowedUserTypes || candidate.threadStatus !in allowedStatuses) return null
        if (candidate.threadStatus in setOf("已收束", "已转题") && candidate.nextFocus != "无") return null
        if (candidate.currentTopic.length > 80 || candidate.currentAnchor.length > 80 || candidate.turnAdvance.length > 100 || candidate.nextFocus.length > 60) return null
        return candidate
    }

    private fun deriveGroupTurnState(userMsg: String, previous: GroupTurnState?): GroupTurnState {
        val trimmed = userMsg.trim()
        val userTurnType = when {
            trimmed.isBlank() -> "无用户发言"
            Regex("亲|抱|贴贴|摸|触|同床|做爱|上床|作爱|性爱").containsMatchIn(trimmed) -> "亲密邀约"
            Regex("吗|？|\\?").containsMatchIn(trimmed) -> "提问"
            else -> "补充"
        }
        val topic = trimmed.take(80).ifBlank { previous?.currentTopic.orEmpty() }.ifBlank { "延续最近群聊话题" }
        val anchor = if (trimmed.isBlank()) previous?.nextFocus.orEmpty().ifBlank { previous?.currentTopic.orEmpty() } else trimmed.take(80)
        return GroupTurnState(
            currentTopic = topic,
            userTurnType = userTurnType,
            currentAnchor = anchor.ifBlank { "承接最近群聊主线" },
            turnAdvance = "成员已围绕当前主线作出回应，未确认新的场景结果。",
            threadStatus = if (trimmed.isBlank()) "继续" else "等待用户",
            nextFocus = if (trimmed.isBlank()) previous?.nextFocus.orEmpty().ifBlank { "延续当前主线" } else "用户回应当前成员态度或说明下一步"
        )
    }

    /** Recovers standard name lines and bare stable-ID lines when a model omits the required brackets. */
    private fun extractSpeakerLines(raw: String, activeMembers: List<Operator>, groupName: String): List<GroupMsgResult> {
        if (raw.isBlank()) return emptyList()
        val byId = activeMembers.associateBy { it.id }
        val byUniqueName = activeMembers.groupBy { it.name }.mapValues { (_, members) -> members.singleOrNull() }
        val references = (byId.keys + byUniqueName.keys + "旁白").filter { it.isNotBlank() }
            .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val linePattern = Regex(
