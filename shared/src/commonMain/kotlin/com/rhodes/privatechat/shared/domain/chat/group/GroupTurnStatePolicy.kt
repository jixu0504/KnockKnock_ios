package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.GroupTurnState

/**
 * 群回合状态与主线策略（群聊独有）。逐条迁移 oracle GVM:2589-2644：
 * - `parseGroupTurnState`：六字段解析（当前主线必填，各字段截断 80/16/80/100/16/60/60）
 * - `validateGroupTurnState`：13 项 userTurnType / 4 项 threadStatus 白名单 / 已收束约束 / 长度拒绝
 * - `deriveGroupTurnState`：解析失败时保守降级（提问/亲密邀约/补充/无用户发言 + 上一状态承接）
 * - `extractGroupPlotSummary`：本轮剧情简述（≤220 字）
 */
object GroupTurnStatePolicy {

    /** 13 项用户本轮作用白名单（oracle :2618）。 */
    val ALLOWED_USER_TURN_TYPES = setOf(
        "提问", "情绪表达", "邀请", "亲密邀约", "亲密接触", "成人互动请求", "确认", "拒绝", "选择", "补充", "转题", "无用户发言", "不明"
    )

    /** 4 项主线状态白名单（oracle :2619）。 */
    val ALLOWED_THREAD_STATUSES = setOf("继续", "等待用户", "已收束", "已转题")

    /** 已收束/已转题时下轮焦点必须为「无」（oracle :2621）。 */
    private val CLOSED_STATUSES = setOf("已收束", "已转题")

    /** 字段长度上限（oracle :2622）。 */
    const val MAX_TOPIC_CHARS = 80
    const val MAX_ANCHOR_CHARS = 80
    const val MAX_ADVANCE_CHARS = 100
    const val MAX_NEXT_FOCUS_CHARS = 60

    /** oracle :2596-2615。解析无「当前主线」时返回 null。 */
    fun parseGroupTurnState(raw: String): GroupTurnState? {
        fun field(name: String, maxLength: Int): String {
            val tag = Regex("""[【\[［]\s*${Regex.escape(name)}\s*(?:[：:]\s*)?[】\]］]""")
            val found = tag.find(raw) ?: return ""
            val next = Regex(GroupTaggedReplyParser.NEXT_TAG_PATTERN).find(raw, found.range.last + 1)
            return raw.substring(found.range.last + 1, next?.range?.first ?: raw.length).trim().take(maxLength)
        }
        val topic = field("当前主线", MAX_TOPIC_CHARS)
        if (topic.isBlank()) return null
        return GroupTurnState(
            currentTopic = topic,
            userTurnType = field("用户本轮作用", 16),
            currentAnchor = field("本轮承接", MAX_ANCHOR_CHARS),
            turnAdvance = field("本轮新增推进", MAX_ADVANCE_CHARS),
            threadStatus = field("主线状态", 16),
            nextFocus = field("下轮焦点", MAX_NEXT_FOCUS_CHARS),
            memberEmotions = field("成员心情", GroupEmotionRules.SNAPSHOT_MAX_CHARS)
        )
    }

    /** oracle :2617-2624。非法返回 null（调用方走保守降级 derive）。 */
    fun validateGroupTurnState(candidate: GroupTurnState): GroupTurnState? {
        if (candidate.currentTopic.isBlank() || candidate.userTurnType !in ALLOWED_USER_TURN_TYPES ||
            candidate.threadStatus !in ALLOWED_THREAD_STATUSES
        ) {
            return null
        }
        if (candidate.threadStatus in CLOSED_STATUSES && candidate.nextFocus != "无") return null
        if (candidate.currentTopic.length > MAX_TOPIC_CHARS ||
            candidate.currentAnchor.length > MAX_ANCHOR_CHARS ||
            candidate.turnAdvance.length > MAX_ADVANCE_CHARS ||
            candidate.nextFocus.length > MAX_NEXT_FOCUS_CHARS
        ) {
            return null
        }
        return candidate
    }

    /** oracle :2626-2644。解析/校验失败时的保守降级状态。 */
    fun deriveGroupTurnState(userMsg: String, previous: GroupTurnState?): GroupTurnState {
        val trimmed = userMsg.trim()
        val userTurnType = when {
            trimmed.isBlank() -> "无用户发言"
            Regex("亲|抱|贴贴|摸|触|同床|做爱|上床|作爱|性爱").containsMatchIn(trimmed) -> "亲密邀约"
            Regex("吗|？|\\?").containsMatchIn(trimmed) -> "提问"
            else -> "补充"
        }
        val topic = trimmed.take(MAX_TOPIC_CHARS)
            .ifBlank { previous?.currentTopic.orEmpty() }
            .ifBlank { "延续最近群聊话题" }
        val anchor = if (trimmed.isBlank()) {
            previous?.nextFocus.orEmpty().ifBlank { previous?.currentTopic.orEmpty() }
        } else {
            trimmed.take(MAX_TOPIC_CHARS)
        }
        return GroupTurnState(
            currentTopic = topic,
            userTurnType = userTurnType,
            currentAnchor = anchor.ifBlank { "承接最近群聊主线" },
            turnAdvance = "成员已围绕当前主线作出回应，未确认新的场景结果。",
            threadStatus = if (trimmed.isBlank()) "继续" else "等待用户",
            nextFocus = if (trimmed.isBlank()) {
                previous?.nextFocus.orEmpty().ifBlank { "延续当前主线" }
            } else {
                "用户回应当前成员态度或说明下一步"
            }
        )
    }

    /** oracle :2589-2594。抽取【本轮剧情简述】（≤220 字）。 */
    fun extractGroupPlotSummary(raw: String): String {
        val tag = Regex("""[【\[［]\s*本轮剧情简述\s*(?:[：:]\s*)?[】\]］]""")
        val found = tag.find(raw) ?: return ""
        val next = Regex(GroupTaggedReplyParser.NEXT_TAG_PATTERN).find(raw, found.range.last + 1)
        return raw.substring(found.range.last + 1, next?.range?.first ?: raw.length).trim().take(220)
    }
}
