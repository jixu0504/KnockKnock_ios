package com.rhodes.privatechat.shared.domain.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收 #8：通知/产品文案 golden。
 * 文案逐字引用 `ios-port/specs/bg-task-notification-spec.md` §2/§3（T13 实测结论）：
 * 「打开 App 立即补算」「完成即通知」「尽力而为定时」；禁用措辞不承诺准点。
 */
class CopyTextTest {

    @Test
    fun approvedProductCopyIsVerbatim() {
        // T13 §3 设置页文案（逐字）。
        assertEquals("按你的节奏，尽快回复。", A7ProductCopy.TIMING_SWITCH_SUBTITLE)
        assertEquals(
            "iOS 不承诺精确到秒的定时；RhodesLink 会在条件允许时尽快执行，App 打开时立即补上。",
            A7ProductCopy.TIMING_SWITCH_DETAIL,
        )
        assertEquals(
            "开启通知后，回复完成时第一时间提醒你。首次不会打扰你；你也可以在系统设置中把 RhodesLink 改为横幅提醒。",
            A7ProductCopy.NOTIFICATION_PERMISSION_GUIDE,
        )
        assertEquals(
            "如果系统延后了定时任务，打开 RhodesLink 会自动补上，不会丢。",
            A7ProductCopy.BACKGROUND_FALLBACK_NOTE,
        )
        // T13 §2 可承诺口径（不可弱化）。
        assertEquals("打开 App 立即补算", A7ProductCopy.CAPABILITY_CATCH_UP_ON_OPEN)
        assertEquals("完成即通知", A7ProductCopy.CAPABILITY_NOTIFY_ON_COMPLETION)
        assertEquals("尽力而为定时", A7ProductCopy.CAPABILITY_BEST_EFFORT_TIMING)
        // T13 §3 FAQ 逐字。
        assertEquals(
            "iOS 为省电采用机会式后台调度，任何 App 都无法承诺精确到秒的后台执行。RhodesLink 的做法是：" +
                "App 在前台时按你的节奏执行；在后台时由系统择机执行；你打开 App 的瞬间立即补算，并在完成后发本地通知提醒你。",
            A7ProductCopy.FAQ_NOT_PUNCTUAL_ANSWER,
        )
        assertEquals(
            "请到 系统设置 → 通知 → RhodesLink 检查开关。首次授权是静默模式（不弹横幅），可在系统设置中调整为横幅/声音提醒。",
            A7ProductCopy.FAQ_NO_NOTIFICATION_ANSWER,
        )
        assertEquals(
            "不会。所有待回复都会持久保存，任何一次 App 启动都会检查并补上过期任务。",
            A7ProductCopy.FAQ_WILL_REPLY_BE_LOST_ANSWER,
        )
    }

    @Test
    fun notificationAndSettingsCopyContainsNoUnnegatedForbiddenPromises() {
        val violations = A7CopyGuard.violations(A7ProductCopy.notificationAndSettingsCopy())
        assertTrue(violations.isEmpty(), "禁用措辞出现在通知/设置文案：$violations")
        for (copy in A7ProductCopy.notificationAndSettingsCopy()) {
            assertTrue(A7CopyGuard.unnegatedForbiddenHits(copy).isEmpty(), "违规文案：$copy")
        }
    }

    @Test
    fun faqNegatedDisclaimerIsAllowed() {
        // FAQ 含被批准的否定式免责声明「无法承诺精确到秒」，负向扫描必须放行（否则与规格卡文案自相矛盾）。
        assertTrue(A7ProductCopy.FAQ_NOT_PUNCTUAL_ANSWER.contains("精确到秒"))
        assertTrue(A7CopyGuard.unnegatedForbiddenHits(A7ProductCopy.FAQ_NOT_PUNCTUAL_ANSWER).isEmpty())
        // 正向承诺必须被抓出来。
        assertTrue(A7CopyGuard.unnegatedForbiddenHits("本功能准点回复，定时精确到秒").isNotEmpty())
        assertTrue(A7CopyGuard.unnegatedForbiddenHits("X 秒后回复").isNotEmpty())
        assertTrue(A7CopyGuard.violations(listOf("准点回复")).isNotEmpty())
    }

    @Test
    fun momentReplyNotificationGolden() {
        val title = NotificationCopy.momentReplyTitle("银灰")
        assertEquals("银灰 回复了你的评论", title)
        assertEquals("有人 回复了你的评论", NotificationCopy.momentReplyTitle("  "))
        assertEquals("听见风声了。", NotificationCopy.momentReplyBody("听见风声了。"))
        // 换行折叠 + 120 字截断（oracle :2050 content.take(120)）。
        assertEquals("第一行 第二行", NotificationCopy.momentReplyBody("第一行\n第二行"))
        assertEquals(120, NotificationCopy.momentReplyBody("啊".repeat(200)).length)

        val event = NotificationCopy.momentReplyCompleted(
            turnId = "moment-abc",
            momentId = 7L,
            commentId = 99L,
            commenterName = "银灰",
            content = "听见风声了。",
            createdAt = 1_750_000_000_000L,
        )
        assertEquals("moment-reply-done-moment-abc", event.id)
        assertEquals(NotificationCategory.MOMENT_REPLY_COMPLETED, event.category)
        assertEquals("银灰 回复了你的评论", event.title)
        assertEquals("听见风声了。", event.body)
        assertEquals(1_750_000_000_000L, event.createdAt)
        assertEquals("moment-abc", event.payload["turnId"] ?: "<null>")
        assertEquals("7", event.payload["momentId"] ?: "<null>")
        assertEquals("99", event.payload["commentId"] ?: "<null>")
        assertEquals("银灰", event.payload["commenterName"] ?: "<null>")
        // 通知文案本身也不得含禁用承诺。
        assertTrue(A7CopyGuard.unnegatedForbiddenHits(event.title + event.body).isEmpty())
    }

    @Test
    fun notificationCopyPrefersApprovedVocabulary() {
        // ✅ 允许的替代措辞出现在通知/设置文案；❌ 未否定形式的禁用承诺被扫描拦住（见上一条测试）。
        val allowed = listOf("尽快回复", "第一时间提醒你", "立即补上")
        val all = A7ProductCopy.notificationAndSettingsCopy().joinToString("\n")
        allowed.forEach { assertTrue(all.contains(it), "缺少允许措辞：$it") }
        assertTrue(!all.contains("准点"), "设置/通知文案不得出现「准点」")
        assertTrue(A7CopyGuard.unnegatedForbiddenHits(all).isEmpty())
    }
}
