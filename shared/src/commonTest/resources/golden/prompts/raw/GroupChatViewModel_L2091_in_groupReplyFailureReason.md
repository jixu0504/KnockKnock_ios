<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2091-2158
  Decl  : in_groupReplyFailureReason
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
)
                .findAll(raw).map { it.groupValues[1] }.toList()
            val knownIds = activeMembers.map { it.id }.toSet()
            val unknown = bareReferences.filter { it != "旁白" && it !in knownIds && it !in activeNames && it != groupName }.distinct()
            return when {
                bareReferences.any { it in knownIds } -> "模型使用了裸发言标识，但本轮未能恢复为有效成员台词：${bareReferences.filter { it in knownIds }.distinct().joinToString("、")}" 
                bareReferences.any { it == groupName } -> "模型将群名“$groupName”当作发言人"
                unknown.isNotEmpty() -> "发言标识不在当前成员中：${unknown.joinToString("、")}" 
                else -> "解析失败：未识别到规定标签或可解析 JSON 条目"
            }
        }
        val invalid = extracted.map { it.speaker.trim() }
            .filter { it.isNotBlank() && it != "旁白" && it !in activeNames }
            .distinct()
        val validDialogue = extracted.any { it.message.isNotBlank() && it.speaker in activeNames && !it.type.equals("narration", true) }
        return when {
            invalid.isNotEmpty() -> "发言人不在当前成员中：${invalid.joinToString("、")}" 
            !validDialogue -> "解析后没有当前成员的可展示台词"
            mode == "online" && extracted.any { it.type.equals("narration", true) || it.speaker == "旁白" } -> "线上模式输出了旁白，且没有可接受台词"
            else -> "输出不符合当前群聊展示要求"
        }
    }

    /** Logs only structural parsing problems; content targets are enforced by the prompt. */
    private fun logGroupReplyStructure(results: List<GroupMsgResult>, activeNames: Set<String>, mode: String) {
        if (results.isEmpty()) return
        val issues = mutableListOf<String>()
        if (mode == "online" && results.any { it.type == "narration" }) issues += "线上模式包含旁白"
        if (!isDisplayableGroupReply(results)) issues += "缺少可展示的当前成员台词"
        if (issues.isNotEmpty()) DebugLogger.log("GroupChat/Protocol", "mode=$mode; ${issues.joinToString("；")}")
    }

    private fun isLowSignalKnowledgeQuery(query: String): Boolean {
        val normalized = query.lowercase().replace(Regex("[\\s，。！？!?、~～]+"), "")
        return normalized in setOf("好", "好的", "嗯", "行", "可以", "收到", "知道了", "哈哈", "哦", "ok", "okay")
    }

    private fun logRawGroupReplyStructure(results: List<GroupMsgResult>, validSpeakers: Set<String>, mode: String) {
        if (results.isEmpty()) {
            DebugLogger.log("GroupChat/Protocol", "mode=$mode; 原始输出未提取到任何条目")
            return
        }
        val issues = mutableListOf<String>()
        val invalidSpeakers = results.count { it.speaker.trim() !in validSpeakers && it.speaker.trim() != "" }
        val narration = results.filter { it.type.equals("narration", true) || it.speaker.trim() == "旁白" }
        if (invalidSpeakers > 0) issues += "非法发言者=$invalidSpeakers"
        if (mode == "online" && narration.isNotEmpty()) issues += "线上模式原始输出含旁白=${narration.size}"
        val firstPerson = narration.count { containsFirstPersonNarration(it.message) }
        if (firstPerson > 0) issues += "第一人称旁白=$firstPerson"
        if (issues.isNotEmpty()) DebugLogger.log("GroupChat/Protocol", "mode=$mode; ${issues.joinToString("；")}")
    }

    private fun stripSpeakerPrefix(content: String): Pair<String, String> {
        val idx = listOf(content.indexOf('：'), content.indexOf(':')).filter { it in 1..12 }.minOrNull() ?: return "" to content
        return content.substring(0, idx).trim(' ', '“', '”', '"') to content.substring(idx + 1).trim()
    }

    /** Removes structural dialogue labels that a model may leak into a group message. */
    private fun stripLeakedSegmentLabel(content: String): String = content.trimStart()
        .replaceFirst(Regex("^【(?:旁白|台词|台詞)(?:[：:])?】\\s*"), "")
        .trimStart()

    private fun containsFirstPersonNarration(content: String): Boolean {
        val outsideQuotes = content
            .replace(Regex("[“\"](?:\\.|[^”\"])*[”\"]"), "")
            .replace(Regex("[‘'](?:\\.|[^’'])*[’']"), "")
            .trimStart()
        return Regex(
