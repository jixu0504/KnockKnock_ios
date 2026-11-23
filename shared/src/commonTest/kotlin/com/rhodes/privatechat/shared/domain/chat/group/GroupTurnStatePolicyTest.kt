package com.rhodes.privatechat.shared.domain.chat.group

import com.rhodes.privatechat.shared.model.GroupTurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群回合状态策略（验收 #4）：六字段解析/13 项 userTurnType/4 项 threadStatus/已收束约束/长度拒绝/保守降级。
 * oracle：GVM:2589-2644。
 */
class GroupTurnStatePolicyTest {

    private val fullRaw = """
        【群聊回合状态】
        【当前主线】雨夜门口
        【用户本轮作用】提问
        【本轮承接】昨晚的约定
        【本轮新增推进】阿米娅解释迟到原因
        【主线状态】继续
        【下轮焦点】用户决定是否进门
        【成员心情】amiya=担忧;blaze=不耐烦
    """.trimIndent()

    @Test
    fun parsesAllSixFields() {
        val state = GroupTurnStatePolicy.parseGroupTurnState(fullRaw)
        assertNotNull(state)
        assertEquals("雨夜门口", state.currentTopic)
        assertEquals("提问", state.userTurnType)
        assertEquals("昨晚的约定", state.currentAnchor)
        assertEquals("阿米娅解释迟到原因", state.turnAdvance)
        assertEquals("继续", state.threadStatus)
        assertEquals("用户决定是否进门", state.nextFocus)
        assertEquals("amiya=担忧;blaze=不耐烦", state.memberEmotions)
    }

    @Test
    fun missingCurrentTopicMeansNoState() {
        assertNull(GroupTurnStatePolicy.parseGroupTurnState("【用户本轮作用】提问"))
        assertNull(GroupTurnStatePolicy.parseGroupTurnState(""))
    }

    @Test
    fun fieldsAreTruncatedToOracleLimits() {
        val long = "主".repeat(200)
        val parsed = GroupTurnStatePolicy.parseGroupTurnState(
            """
            【当前主线】$long
            【用户本轮作用】${"问".repeat(40)}
            【本轮承接】$long
            【本轮新增推进】$long
            【主线状态】继续
            【下轮焦点】$long
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertEquals(80, parsed.currentTopic.length)
        assertEquals(16, parsed.userTurnType.length)
        assertEquals(80, parsed.currentAnchor.length)
        assertEquals(100, parsed.turnAdvance.length)
        assertEquals(60, parsed.nextFocus.length)
    }

    @Test
    fun allThirteenUserTurnTypesAndFourStatusesAreAllowed() {
        assertEquals(13, GroupTurnStatePolicy.ALLOWED_USER_TURN_TYPES.size)
        assertEquals(setOf("继续", "等待用户", "已收束", "已转题"), GroupTurnStatePolicy.ALLOWED_THREAD_STATUSES)
        for (type in GroupTurnStatePolicy.ALLOWED_USER_TURN_TYPES) {
            assertNotNull(
                GroupTurnStatePolicy.validateGroupTurnState(validState().copy(userTurnType = type)),
                "应允许 userTurnType=$type",
            )
        }
        for (status in GroupTurnStatePolicy.ALLOWED_THREAD_STATUSES) {
            val state = validState().copy(threadStatus = status, nextFocus = if (status in setOf("已收束", "已转题")) "无" else "继续")
            assertNotNull(GroupTurnStatePolicy.validateGroupTurnState(state), "应允许 threadStatus=$status")
        }
    }

    @Test
    fun closedThreadRequiresEmptyNextFocus() {
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(threadStatus = "已收束", nextFocus = "用户回应")))
        assertNotNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(threadStatus = "已收束", nextFocus = "无")))
        assertNotNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(threadStatus = "已转题", nextFocus = "无")))
    }

    @Test
    fun unknownTurnTypeOrStatusIsRejected() {
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(userTurnType = "卖萌")))
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(threadStatus = "不确定")))
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(currentTopic = "")))
    }

    @Test
    fun overLengthStateIsRejected() {
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(currentTopic = "主".repeat(81))))
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(currentAnchor = "承".repeat(81))))
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(turnAdvance = "推".repeat(101))))
        assertNull(GroupTurnStatePolicy.validateGroupTurnState(validState().copy(nextFocus = "焦".repeat(61))))
    }

    @Test
    fun deriveFallsBackConservativelyPerUserMessage() {
        val question = GroupTurnStatePolicy.deriveGroupTurnState("你今天去哪了？", previous = null)
        assertEquals("提问", question.userTurnType)
        assertEquals("等待用户", question.threadStatus)

        val intimacy = GroupTurnStatePolicy.deriveGroupTurnState("想抱抱你", previous = null)
        assertEquals("亲密邀约", intimacy.userTurnType)

        val supplement = GroupTurnStatePolicy.deriveGroupTurnState("我带了伞。", previous = null)
        assertEquals("补充", supplement.userTurnType)

        val auto = GroupTurnStatePolicy.deriveGroupTurnState("", previous = null)
        assertEquals("无用户发言", auto.userTurnType)
        assertEquals("继续", auto.threadStatus)
        assertTrue(auto.currentTopic.isNotBlank())
        assertEquals("延续当前主线", auto.nextFocus)
    }

    @Test
    fun deriveCarriesPreviousTopicAndFocusForAutoTurns() {
        val previous = GroupTurnState(currentTopic = "雨夜门口", nextFocus = "用户决定是否进门")
        val derived = GroupTurnStatePolicy.deriveGroupTurnState("", previous)
        assertEquals("雨夜门口", derived.currentTopic)
        assertEquals("用户决定是否进门", derived.currentAnchor)
        assertEquals("用户决定是否进门", derived.nextFocus)
    }

    @Test
    fun plotSummaryExtractionStopsAtNextTagAndCapsAt220() {
        val extracted = GroupTurnStatePolicy.extractGroupPlotSummary(
            """
            【本轮剧情简述】阿米娅解释迟到，煌不耐烦。
            【发言人: amiya】
            台词。
            """.trimIndent()
        )
        assertEquals("阿米娅解释迟到，煌不耐烦。", extracted)
        val long = GroupTurnStatePolicy.extractGroupPlotSummary("【本轮剧情简述】${"剧".repeat(300)}")
        assertEquals(220, long.length)
        assertEquals("", GroupTurnStatePolicy.extractGroupPlotSummary("没有简述"))
    }

    @Test
    fun invalidModelStateFallsBackToDerived() {
        // 模型输出主线为空 → parse 返回 null → 调用方走 derive（契约 9 的兜底路径）。
        val parsed = GroupTurnStatePolicy.parseGroupTurnState("【主线状态】继续")
        assertNull(parsed)
        val derived = GroupTurnStatePolicy.deriveGroupTurnState("我带了伞。", previous = null)
        assertEquals("补充", derived.userTurnType)
    }

    private fun validState() = GroupTurnState(
        currentTopic = "主线",
        userTurnType = "提问",
        currentAnchor = "承接",
        turnAdvance = "推进",
        threadStatus = "继续",
        nextFocus = "焦点",
    )
}
