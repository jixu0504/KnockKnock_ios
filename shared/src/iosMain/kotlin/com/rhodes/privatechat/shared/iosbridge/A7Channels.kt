package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.domain.automation.DelayedTaskChannel
import com.rhodes.privatechat.shared.domain.automation.DelayedTaskDue
import com.rhodes.privatechat.shared.domain.automation.NotificationCategory
import com.rhodes.privatechat.shared.domain.automation.NotificationEvent
import com.rhodes.privatechat.shared.platform.IosNotificationScheduler
import com.rhodes.privatechat.shared.platformSynchronized
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

/** T56 统一时钟（生产 = 系统挂钟；自检可注入确定性时钟）。 */
internal object A7GlueClock {
    fun now(): Long = Clock.System.now().toEpochMilliseconds()
}

/** T56 诊断日志（NSLog，`xcrun simctl spawn booted log show` 可查）。 */
internal object A7GlueLog {
    fun log(tag: String, message: String) {
        NSLog("[RhodesLink][A7][$tag] $message")
    }
}

// ---------------------------------------------------------------------------
// 前台 Timer 主通道（DelayedTaskChannel 的 iOS 实现）
// ---------------------------------------------------------------------------

/**
 * 前台 Timer 主通道（A7 §A-12 冻结协议实现；进程内协程 Timer）。
 *
 * - App 前台：每个唯一键一个 `delay(dueAt-now)` 协程；到期回调层（层负责真实到期判定/双通道去重）。
 * - App 后台：**暂停全部计时器但保留挂起表**；回前台由 glue 调 [setForeground] 重排（过期立即触发）。
 * - 回调前先移除通道挂起项：层若判定「早到」或 RETRY 会重新 [schedule] 重挂，通道表始终只反映真实挂起。
 * - [flushOverdue] 是 tick / 启动 catch-up 的兜底扫描：系统计时器漂移或后台暂停恢复时补触发。
 */
internal class A7ForegroundChannel(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : DelayedTaskChannel {

    private class Entry(val dueAt: Long, val payload: Map<String, String>)

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val jobs = mutableMapOf<String, Job>()
    private var callback: (suspend (DelayedTaskDue) -> Unit)? = null
    private var foreground = true

    override fun onDue(callback: suspend (DelayedTaskDue) -> Unit) {
        platformSynchronized(lock) { this.callback = callback }
        if (isForeground()) rescheduleAll()
    }

    override suspend fun schedule(key: String, dueAt: Long, payload: Map<String, String>) {
        platformSynchronized(lock) {
            entries[key] = Entry(dueAt, payload)
            jobs.remove(key)?.cancel()
        }
        if (isForeground()) scheduleTimer(key)
    }

    override suspend fun cancel(key: String) {
        platformSynchronized(lock) {
            entries.remove(key)
            jobs.remove(key)?.cancel()
        }
    }

    override suspend fun pendingKeys(): Set<String> = platformSynchronized(lock) { entries.keys.toSet() }

    /** 非挂起挂起数（状态行/诊断用）。 */
    fun pendingKeysCount(): Int = platformSynchronized(lock) { entries.size }

    fun isForeground(): Boolean = platformSynchronized(lock) { foreground }

    /** 前台/后台切换；回前台立即重排（过期项 delay(0) 立即触发）。 */
    fun setForeground(value: Boolean) {
        val changed = platformSynchronized(lock) {
            if (foreground == value) return
            foreground = value
            if (!value) {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
            }
            true
        }
        if (changed && value) rescheduleAll()
    }

    /** tick / catch-up 兜底：把已到期但未触发的挂起项立即触发，返回触发数。 */
    fun flushOverdue(now: Long = clock()): Int {
        val due = platformSynchronized(lock) {
            entries.entries.filter { it.value.dueAt <= now }.map { it.key }.toList()
        }
        if (due.isEmpty()) return 0
        val fired = mutableListOf<Pair<String, Entry>>()
        due.forEach { key ->
            val entry = platformSynchronized(lock) {
                jobs.remove(key)?.cancel()
                entries.remove(key)
            } ?: return@forEach
            fired += key to entry
        }
        fireAll(fired)
        if (fired.isNotEmpty()) A7GlueLog.log("channel.flush", "fired=${fired.size} keys=${fired.joinToString(",") { it.first }}")
        return fired.size
    }

    /** 自检用：只进挂起表不排计时器（模拟「后台暂停期间到期」的项，等待 tick 补触发）。 */
    fun plantWithoutTimer(key: String, dueAt: Long, payload: Map<String, String>) {
        platformSynchronized(lock) {
            entries[key] = Entry(dueAt, payload)
            jobs.remove(key)?.cancel()
        }
    }

    private fun scheduleTimer(key: String) {
        val (dueAt, payload) = platformSynchronized(lock) {
            val entry = entries[key] ?: return
            entry.dueAt to entry.payload
        }
        val wait = (dueAt - clock()).coerceAtLeast(0L)
        val job = scope.launch {
            delay(wait)
            val entry = platformSynchronized(lock) {
                jobs.remove(key)
                entries.remove(key)
            } ?: return@launch
            fire(key, entry)
        }
        platformSynchronized(lock) {
            if (!foreground) job.cancel() else jobs[key] = job
        }
    }

    private fun rescheduleAll() {
        val keys = platformSynchronized(lock) { entries.keys.toList() }
        keys.forEach { scheduleTimer(it) }
    }

    private fun fire(key: String, entry: Entry) {
        val cb = platformSynchronized(lock) { callback } ?: return
        val due = DelayedTaskDue(key = key, dueAt = entry.dueAt, payload = entry.payload)
        scope.launch { cb(due) }
    }

    private fun fireAll(items: List<Pair<String, Entry>>) {
        items.forEach { (key, entry) -> fire(key, entry) }
    }
}

// ---------------------------------------------------------------------------
// 通知事件源（A7 NotificationEvent → UNUserNotificationCenter；按设置开关过滤/去重/静音）
// ---------------------------------------------------------------------------

/** 通知发送抽象（生产 = 系统通知中心；自检 = 记录器，绝无真实弹窗）。 */
internal interface A7NotificationSender {
    fun send(event: NotificationEvent)
}

/** 生产发送器：首次通知前静默（provisional）授权，之后直接调度；不阻塞调用方。 */
internal class IosA7NotificationSender : A7NotificationSender {
    private var permissionRequested = false

    override fun send(event: NotificationEvent) {
        if (!permissionRequested) {
            permissionRequested = true
            IosNotificationScheduler.authorizationStatus { status ->
                if (!status.granted && status.status == IosNotificationScheduler.STATUS_NOT_DETERMINED) {
                    A7GlueLog.log("notify.auth", "request provisional（首次通知时静默授权）")
                    IosNotificationScheduler.requestProvisionalAuthorization { scheduleNow(event) }
                } else {
                    scheduleNow(event)
                }
            }
        } else {
            scheduleNow(event)
        }
    }

    private fun scheduleNow(event: NotificationEvent) {
        IosNotificationScheduler.scheduleEvent(event, fireAfterSeconds = 0.0) { result ->
            A7GlueLog.log("notify.send", "id=${event.id} ok=${result.ok} err=${result.error}")
        }
    }
}

/** 自检记录器：只记录请求（`pending request` 语义），不弹窗、不进通知中心。 */
internal class A7RecordingNotificationSender : A7NotificationSender {
    val sent = mutableListOf<NotificationEvent>()

    override fun send(event: NotificationEvent) {
        sent += event
        A7GlueLog.log("notify.record", "id=${event.id} title=${event.title}")
    }
}

/**
 * 通知路由：设置开关过滤 + 同会话去重/静音 + 深链 payload。
 *
 * 开关映射（T53 `notify_*` 四键，默认 true）：
 * - `moment:` / MOMENT_REPLY_COMPLETED → `notify_moments`
 * - `group:` → `notify_group_reply`
 * - `diary:` → `notify_diary`
 * - `session:` → `notify_private_reply`
 * - DELAYED_TASK_CATCH_UP 兜底补算通知不挂具体会话开关（产品口径「打开 App 自动补上，不会丢」）。
 *
 * 静音/去重：
 * - 用户在 App 内正查看该会话（[setActiveSessionId]）→ 不发（`session_active`）；
 * - 同会话在 [dedupWindowMs] 内已发过 → 不发（`session_muted`）；
 * - 同幂等键 [NotificationEvent.id] 在窗口内已发过 → 不发（`duplicate`）。
 */
internal class A7NotificationRouter(
    private val settings: SettingsRepository,
    private val sender: A7NotificationSender,
    private val clock: () -> Long = { A7GlueClock.now() },
    private val dedupWindowMs: Long = 60_000L,
    private val maxRecords: Int = 128,
) {
    /** 一条通知处理记录（发送或抑制；自检/诊断用）。 */
    internal class Record(
        val event: NotificationEvent,
        val sessionId: String,
        val deeplink: String,
        val sentAt: Long,
        /** 空串 = 已发送；否则 = 抑制原因。 */
        val suppressed: String,
    )

    private val lock = Any()
    private val records = mutableListOf<Record>()
    private val lastSentBySession = mutableMapOf<String, Long>()
    private val sentIds = mutableMapOf<String, Long>()
    private var activeSessionId = ""

    /** UI 当前查看的会话（进入聊天页时写入；用于同会话静音）。 */
    fun setActiveSessionId(sessionId: String) {
        platformSynchronized(lock) { activeSessionId = sessionId.trim() }
    }

    fun onEvent(event: NotificationEvent, sessionId: String): Record {
        val deeplink = "rhodes://chat/$sessionId"
        val now = clock()
        val record = platformSynchronized(lock) {
            val reason = when {
                !switchEnabled(event, sessionId) -> "switch_off"
                sessionId.isNotBlank() && activeSessionId == sessionId -> "session_active"
                event.id.isNotBlank() && sentIds[event.id]?.let { now - it < dedupWindowMs } == true -> "duplicate"
                sessionId.isNotBlank() && lastSentBySession[sessionId]?.let { now - it < dedupWindowMs } == true -> "session_muted"
                else -> ""
            }
            if (reason.isEmpty()) {
                sentIds[event.id] = now
                if (sessionId.isNotBlank()) lastSentBySession[sessionId] = now
                records += Record(event, sessionId, deeplink, now, "")
                if (records.size > maxRecords) records.removeAt(0)
                Record(event, sessionId, deeplink, now, "")
            } else {
                records += Record(event, sessionId, deeplink, now, reason)
                if (records.size > maxRecords) records.removeAt(0)
                Record(event, sessionId, deeplink, now, reason)
            }
        }
        if (record.suppressed.isEmpty()) {
            val withLink = event.copy(payload = event.payload + mapOf("deeplink" to deeplink, "sessionId" to sessionId))
            sender.send(withLink)
            A7GlueLog.log("notify.sent", "id=${event.id} session=$sessionId deeplink=$deeplink")
        } else {
            A7GlueLog.log("notify.suppress", "id=${event.id} session=$sessionId reason=${record.suppressed}")
        }
        return record
    }

    fun records(): List<Record> = platformSynchronized(lock) { records.toList() }

    fun sentRecords(): List<Record> = records().filter { it.suppressed.isEmpty() }

    private fun switchEnabled(event: NotificationEvent, sessionId: String): Boolean {
        val key = when {
            sessionId.startsWith("group:") -> NotificationBridge.KEY_GROUP_REPLY
            sessionId.startsWith("diary:") -> NotificationBridge.KEY_DIARY
            sessionId.startsWith("moment:") -> NotificationBridge.KEY_MOMENTS
            sessionId.startsWith("session:") -> NotificationBridge.KEY_PRIVATE_REPLY
            event.category == NotificationCategory.MOMENT_REPLY_COMPLETED -> NotificationBridge.KEY_MOMENTS
            else -> return true
        }
        return settings.getBoolean(key, true)
    }
}
