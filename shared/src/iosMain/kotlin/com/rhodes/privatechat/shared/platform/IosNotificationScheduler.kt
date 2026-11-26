@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.platform

import com.rhodes.privatechat.shared.domain.automation.NotificationCategory
import com.rhodes.privatechat.shared.domain.automation.NotificationCopy
import com.rhodes.privatechat.shared.domain.automation.NotificationEvent
import platform.Foundation.NSLock
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionProvisional
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationOptions
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.time.Clock

/**
 * 通知权限状态快照（Swift 只读）。
 *
 * @param status UNAuthorizationStatus 原始值（0=notDetermined 1=denied 2=authorized 3=provisional 4=ephemeral）
 * @param statusName 可读名
 * @param granted 是否已授权（authorized/provisional/ephemeral 均算）
 * @param provisional 是否 provisional（首次静默授权，T13 §1.3）
 * @param error 错误信息（请求失败时）
 */
class NotificationPermissionStatus(
    val status: Int,
    val statusName: String,
    val granted: Boolean,
    val provisional: Boolean,
    val error: String,
) {
    override fun toString(): String = "Permission(status=$status/$statusName, granted=$granted, provisional=$provisional, error=$error)"
}

/** 通知调度结果（Swift 只读）。 */
class NotificationScheduleResult(
    val ok: Boolean,
    val identifier: String,
    val error: String,
) {
    override fun toString(): String = "NotificationScheduleResult(ok=$ok, id=$identifier, error=$error)"
}

/**
 * iOS 本地通知调度器（UNUserNotificationCenter 封装；接 T25/T13）。
 *
 * 授权时机（T13 建议）：**不在首启动弹窗**。首次进 App 只请求 [requestProvisionalAuthorization]
 * （静默、不弹窗、直接进通知中心）；用户进入「消息提醒」等设置页时再 [requestFullAuthorization]
 * 请求完整权限（alert/badge/sound）。权限被拒后可用 [openSettings] 引导。
 *
 * 幂等：以 [NotificationEvent.id] 作为 UNNotificationRequest identifier，同一业务事件重复
 * [scheduleEvent] 会**覆盖**而不是重复排队（UNUserNotificationCenter 语义）。
 *
 * 线程：所有 onResult 回调统一投递到主队列（主线程 UI 安全）。
 */
object IosNotificationScheduler {

    const val STATUS_NOT_DETERMINED = 0
    const val STATUS_DENIED = 1
    const val STATUS_AUTHORIZED = 2
    const val STATUS_PROVISIONAL = 3
    const val STATUS_EPHEMERAL = 4

    private val lock = NSLock()
    private val scheduledIds = mutableSetOf<String>()

    private fun center(): UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter()

    // ---------------------------------------------------------------- 权限

    /** 查询当前通知权限状态（不触发任何授权弹窗）。 */
    fun authorizationStatus(onResult: (NotificationPermissionStatus) -> Unit) {
        center().getNotificationSettingsWithCompletionHandler { settings ->
            val snapshot = mapStatus(settings?.authorizationStatus, "")
            onMain { onResult(snapshot) }
        }
    }

    /** 首次静默授权（provisional：不弹窗，T13 §1.3 实测 ok）。 */
    fun requestProvisionalAuthorization(onResult: (NotificationPermissionStatus) -> Unit) {
        val options: UNAuthorizationOptions =
            UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or
                UNAuthorizationOptionSound or UNAuthorizationOptionProvisional
        requestAuthorization(options, onResult)
    }

    /** 进入相关设置页时的完整授权（真机会弹系统授权框）。 */
    fun requestFullAuthorization(onResult: (NotificationPermissionStatus) -> Unit) {
        val options: UNAuthorizationOptions =
            UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        requestAuthorization(options, onResult)
    }

    private fun requestAuthorization(
        options: UNAuthorizationOptions,
        onResult: (NotificationPermissionStatus) -> Unit,
    ) {
        center().requestAuthorizationWithOptions(options) { _: Boolean, error ->
            val errorText = error?.localizedDescription ?: ""
            center().getNotificationSettingsWithCompletionHandler { settings ->
                val snapshot = mapStatus(settings?.authorizationStatus, errorText)
                onMain { onResult(snapshot) }
            }
        }
    }

    /** 打开系统「设置 → 通知 → RhodesLink」（权限被拒后的引导，T13 §1.3）。 */
    fun openSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(
            url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 调度一条本地通知。
     *
     * @param event T25/T13 的通知事件（id 用作唯一 identifier，payload 进 userInfo）
     * @param fireAfterSeconds 多少秒后送达；<=0 表示尽快送达（无 trigger）
     */
    fun scheduleEvent(event: NotificationEvent, fireAfterSeconds: Double, onResult: (NotificationScheduleResult) -> Unit) {
        val content = UNMutableNotificationContent()
        content.setTitle(event.title)
        content.setBody(event.body)
        content.setSound(UNNotificationSound.defaultSound)
        val userInfo = mutableMapOf<Any?, Any?>()
        userInfo["category"] = event.category.name
        userInfo["createdAt"] = event.createdAt
        event.payload.forEach { (key, value) -> userInfo[key] = value }
        content.setUserInfo(userInfo)

        val trigger: UNTimeIntervalNotificationTrigger? =
            if (fireAfterSeconds > 0.0) {
                UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(fireAfterSeconds, repeats = false)
            } else {
                null
            }
        val request = UNNotificationRequest.requestWithIdentifier(event.id, content, trigger)
        center().addNotificationRequest(request) { error ->
            val errorText = error?.localizedDescription ?: ""
            val result = NotificationScheduleResult(errorText.isEmpty(), event.id, errorText)
            if (result.ok) {
                lock.lock()
                scheduledIds.add(event.id)
                lock.unlock()
            }
            onMain { onResult(result) }
        }
    }

    /** 用 T25 的动态回复完成事件做一次真实调度（Swift demo / 自检便捷入口）。 */
    fun scheduleMomentReplyDemo(
        turnId: String,
        commenterName: String,
        contentText: String,
        fireAfterSeconds: Double,
        onResult: (NotificationScheduleResult) -> Unit,
    ) {
        val event = NotificationCopy.momentReplyCompleted(
            turnId = turnId,
            momentId = 1L,
            commentId = 2L,
            commenterName = commenterName,
            content = contentText,
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        scheduleEvent(event, fireAfterSeconds, onResult)
    }

    /** 系统延后补算完成（DELAYED_TASK_CATCH_UP）的演示调度。 */
    fun scheduleCatchUpDemo(fireAfterSeconds: Double, onResult: (NotificationScheduleResult) -> Unit) {
        val now = Clock.System.now().toEpochMilliseconds()
        val event = NotificationEvent(
            id = "catch-up-demo-$now",
            category = NotificationCategory.DELAYED_TASK_CATCH_UP,
            title = "补算完成",
            body = "打开 RhodesLink 后已补上延后的定时任务。",
            createdAt = now,
            payload = mapOf("kind" to "delayed-task-catch-up"),
        )
        scheduleEvent(event, fireAfterSeconds, onResult)
    }

    /** 取消某个 identifier 的待送达通知（幂等）。 */
    fun cancel(identifier: String) {
        lock.lock()
        scheduledIds.remove(identifier)
        lock.unlock()
        val ids = listOf(identifier)
        center().removePendingNotificationRequestsWithIdentifiers(ids)
        center().removeDeliveredNotificationsWithIdentifiers(ids)
    }

    /** 取消全部待送达通知。 */
    fun cancelAll() {
        lock.lock()
        scheduledIds.clear()
        lock.unlock()
        center().removeAllPendingNotificationRequests()
    }

    // ---------------------------------------------------------------- 查询（自检/诊断用）

    fun pendingCount(onResult: (Int) -> Unit) {
        center().getPendingNotificationRequestsWithCompletionHandler { requests ->
            val count = requests?.size ?: 0
            onMain { onResult(count) }
        }
    }

    fun pendingIdentifiers(onResult: (List<String>) -> Unit) {
        center().getPendingNotificationRequestsWithCompletionHandler { requests ->
            val ids = requests.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier }
            onMain { onResult(ids) }
        }
    }

    fun deliveredCount(onResult: (Int) -> Unit) {
        center().getDeliveredNotificationsWithCompletionHandler { notifications ->
            val count = notifications?.size ?: 0
            onMain { onResult(count) }
        }
    }

    fun deliveredIdentifiers(onResult: (List<String>) -> Unit) {
        center().getDeliveredNotificationsWithCompletionHandler { notifications ->
            val ids = notifications.orEmpty().mapNotNull { (it as? UNNotification)?.request?.identifier }
            onMain { onResult(ids) }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun onMain(block: () -> Unit) {
        NSOperationQueue.mainQueue.addOperationWithBlock { block() }
    }

    private fun mapStatus(status: Long?, error: String): NotificationPermissionStatus {
        val code = statusCode(status)
        val name = when (code) {
            STATUS_NOT_DETERMINED -> "notDetermined"
            STATUS_DENIED -> "denied"
            STATUS_AUTHORIZED -> "authorized"
            STATUS_PROVISIONAL -> "provisional"
            STATUS_EPHEMERAL -> "ephemeral"
            else -> "unknown"
        }
        val granted = code == STATUS_AUTHORIZED || code == STATUS_PROVISIONAL || code == STATUS_EPHEMERAL
        return NotificationPermissionStatus(
            status = code,
            statusName = name,
            granted = granted,
            provisional = code == STATUS_PROVISIONAL,
            error = error,
        )
    }

    /** 直接取 NSInteger 原始值（0..4），避免依赖平台枚举 import。 */
    private fun statusCode(status: Long?): Int = status?.toInt() ?: -1
}
