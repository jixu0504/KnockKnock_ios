package com.rhodes.privatechat.shared.domain.automation

/**
 * 本地通知事件（A7 §B-13 平台差异白名单；T13 实测全链路可用）。
 *
 * 口径：域层只产出「通知事件数据」，**不发送通知、不 import 任何通知/后台框架**；
 * iOS 壳波次用系统通知中心 API 发送（provisional 首次静默授权 → 进设置页再请求完整授权，
 * 见 ios-port/specs/bg-task-notification-spec.md §1.3）。层测试只断言事件数据与文案。
 */
enum class NotificationCategory {
    /** 动态评论回复落库完成（「完成即通知」，发出时刻=落库时刻，T13 §2 可承诺项 2）。 */
    MOMENT_REPLY_COMPLETED,

    /** 系统延后了定时任务、打开 App 后补算完成（兜底说明口径，不承诺准点）。 */
    DELAYED_TASK_CATCH_UP,
}

/**
 * 一条待发送的本地通知（幂等键 + 数据；不含平台类型）。
 *
 * @param id 幂等键：同一业务事件重复产出时宿主应覆盖/去重（iOS 用 UNNotificationRequest identifier）。
 * @param createdAt 事件产出时刻（= 落库时刻，符合「完成即提醒」在发出侧准点的口径）。
 */
data class NotificationEvent(
    val id: String,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val createdAt: Long,
    val payload: Map<String, String> = emptyMap(),
)

/** 通知事件出口（iOS 壳层实现为系统通知中心 API；测试收集断言）。 */
fun interface NotificationEventSink {
    fun emit(event: NotificationEvent)
}

/**
 * 通知文案常量（golden 化，逐字引用 T13 产品文案；见 CopyTextTest）。
 *
 * 禁用措辞口径（A7 §不可承诺清单 / T13 §3）：❌「准点」「精确到秒」「X 秒后回复」等承诺准点的表达。
 * 注意：被批准的产品文案里有「不承诺精确到秒」这类**否定式**用法，负向扫描按
 * [A7CopyGuard.unnegatedForbiddenHits] 只统计「未紧跟否定词」的出现，否定式免责声明不算违规。
 */
object NotificationCopy {
    /** 动态回复完成通知标题模板（B-13「XX 回复了你的评论」）。 */
    const val MOMENT_REPLY_TITLE_TEMPLATE = "{0} 回复了你的评论"

    /** 通知正文截断长度（oracle RhodesNotificationCenter.show 用 content.take(120)）。 */
    const val BODY_MAX_CHARS = 120

    /** 通知幂等键前缀（宿主用作 UNNotificationRequest identifier 前缀）。 */
    const val MOMENT_REPLY_ID_PREFIX = "moment-reply-done-"

    fun momentReplyTitle(commenterName: String): String =
        MOMENT_REPLY_TITLE_TEMPLATE.replace("{0}", commenterName.trim().ifBlank { "有人" })

    /** 正文：去换行 + 截断 120 字（通知栏单行可读；oracle :2050 content.take(120)）。 */
    fun momentReplyBody(comment: String): String =
        comment.replace(Regex("\\s+"), " ").trim().take(BODY_MAX_CHARS)

    /** 回复落库成功后的通知事件（B-13；发出时刻=落库时刻）。 */
    fun momentReplyCompleted(
        turnId: String,
        momentId: Long,
        commentId: Long,
        commenterName: String,
        content: String,
        createdAt: Long,
    ): NotificationEvent = NotificationEvent(
        id = MOMENT_REPLY_ID_PREFIX + turnId,
        category = NotificationCategory.MOMENT_REPLY_COMPLETED,
        title = momentReplyTitle(commenterName),
        body = momentReplyBody(content),
        createdAt = createdAt,
        payload = mapOf(
            "turnId" to turnId,
            "momentId" to momentId.toString(),
            "commentId" to commentId.toString(),
            "commenterName" to commenterName,
        ),
    )
}

/**
 * 产品文案常量（T13 §3 逐字引用；设置页 / 通知引导 / 兜底说明 / FAQ）。
 *
 * 这些常量是 iOS 壳波次与设置页的唯一文案来源，golden 测试锁定逐字相等；
 * 修改任何一条都必须同步 ios-port/specs/bg-task-notification-spec.md 与测试金样。
 */
object A7ProductCopy {
    /** 可承诺口径（T13 §2）：打开 App 立即补算。 */
    const val CAPABILITY_CATCH_UP_ON_OPEN = "打开 App 立即补算"

    /** 可承诺口径（T13 §2）：补算完成用本地通知提醒（完成即通知）。 */
    const val CAPABILITY_NOTIFY_ON_COMPLETION = "完成即通知"

    /** 可承诺口径（T13 §2）：尽力而为的定时（不承诺准点）。 */
    const val CAPABILITY_BEST_EFFORT_TIMING = "尽力而为定时"

    /** 设置页：定时回复开关副标题。 */
    const val TIMING_SWITCH_SUBTITLE = "按你的节奏，尽快回复。"

    /** 设置页：定时回复开关副标题补充（明确不承诺精确到秒 + 打开 App 立即补上）。 */
    const val TIMING_SWITCH_DETAIL =
        "iOS 不承诺精确到秒的定时；RhodesLink 会在条件允许时尽快执行，App 打开时立即补上。"

    /** 设置页：通知权限引导。 */
    const val NOTIFICATION_PERMISSION_GUIDE =
        "开启通知后，回复完成时第一时间提醒你。首次不会打扰你；你也可以在系统设置中把 RhodesLink 改为横幅提醒。"

    /** 设置页：后台受限兜底说明。 */
    const val BACKGROUND_FALLBACK_NOTE =
        "如果系统延后了定时任务，打开 RhodesLink 会自动补上，不会丢。"

    /** 说明页 FAQ：定时回复为什么不一定准点。 */
    const val FAQ_NOT_PUNCTUAL_ANSWER =
        "iOS 为省电采用机会式后台调度，任何 App 都无法承诺精确到秒的后台执行。" +
            "RhodesLink 的做法是：App 在前台时按你的节奏执行；在后台时由系统择机执行；" +
            "你打开 App 的瞬间立即补算，并在完成后发本地通知提醒你。"

    /** 说明页 FAQ：为什么没收到通知。 */
    const val FAQ_NO_NOTIFICATION_ANSWER =
        "请到 系统设置 → 通知 → RhodesLink 检查开关。首次授权是静默模式（不弹横幅），可在系统设置中调整为横幅/声音提醒。"

    /** 说明页 FAQ：定时回复会丢吗。 */
    const val FAQ_WILL_REPLY_BE_LOST_ANSWER =
        "不会。所有待回复都会持久保存，任何一次 App 启动都会检查并补上过期任务。"

    /**
     * 设置页 + 通知文案集合：负向词表扫描的作用域（不含 FAQ——FAQ 含被批准的否定式免责声明）。
     */
    fun notificationAndSettingsCopy(): List<String> = listOf(
        TIMING_SWITCH_SUBTITLE,
        TIMING_SWITCH_DETAIL,
        NOTIFICATION_PERMISSION_GUIDE,
        BACKGROUND_FALLBACK_NOTE,
        NotificationCopy.MOMENT_REPLY_TITLE_TEMPLATE,
        CAPABILITY_CATCH_UP_ON_OPEN,
        CAPABILITY_NOTIFY_ON_COMPLETION,
        CAPABILITY_BEST_EFFORT_TIMING,
    )

    /** 禁用措辞（承诺准点的表达，出现在通知/设置文案即失败）。 */
    val FORBIDDEN_PHRASES: List<String> = listOf(
        "准点回复", "准点", "定时精确到秒", "精确到秒", "X 秒后回复", "秒后回复", "准时回复", "准时",
    )

    internal val NEGATION_MARKERS: List<String> = listOf(
        "不承诺", "无法承诺", "不能承诺", "不保证", "无法保证", "不是",
    )
}

/** 文案护栏：负向词表扫描（否定式免责声明不算违规）。 */
object A7CopyGuard {
    /**
     * 返回 [text] 中「未被否定词前置」的禁用措辞；空列表 = 合规。
     * 判定：命中的短语前 12 个字符内出现否定词（如「不承诺精确到秒」）时放行。
     */
    fun unnegatedForbiddenHits(
        text: String,
        forbidden: List<String> = A7ProductCopy.FORBIDDEN_PHRASES,
    ): List<String> {
        val hits = mutableListOf<String>()
        for (phrase in forbidden) {
            var from = 0
            while (true) {
                val index = text.indexOf(phrase, from)
                if (index < 0) break
                val prefix = text.substring((index - 12).coerceAtLeast(0), index)
                val negated = A7ProductCopy.NEGATION_MARKERS.any { prefix.contains(it) }
                if (!negated) hits += phrase
                from = index + phrase.length
            }
        }
        return hits
    }

    /** 批量扫描（返回所有违规文案，空 = 合规）。 */
    fun violations(copies: List<String>): List<String> =
        copies.filter { unnegatedForbiddenHits(it).isNotEmpty() }
}
