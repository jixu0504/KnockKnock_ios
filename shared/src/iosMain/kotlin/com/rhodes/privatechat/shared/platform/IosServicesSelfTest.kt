package com.rhodes.privatechat.shared.platform

import com.rhodes.privatechat.shared.domain.automation.NotificationEvent
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.ui.util.copyToClipboard
import com.rhodes.privatechat.shared.ui.util.getClipboardText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Clock

/** 服务自检结果（Swift 侧展示 + 断言用）。 */
class ServicesSelfTestResult(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val lines: List<String>,
    val summary: String,
) {
    override fun toString(): String = summary
}

/**
 * iOS 平台服务自检（T32 验收用；demo 走 `--services-selftest` 自动触发）。
 *
 * 覆盖：深链解析 / 剪贴板写入读回 / 文件日志落盘+导出 / 文件导出导入（需 Swift 注入宿主）/
 * 通知权限（provisional 静默授权）+ 调度 + 送达查询。
 *
 * 输出约定：每行带 `[T32SELFTEST]` 前缀，Swift 侧 NSLog 透出，便于 `simctl log show` 取证。
 */
object IosServicesSelfTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val PREFIX = "[T32SELFTEST]"

    fun run(onLine: (String) -> Unit, onDone: (ServicesSelfTestResult) -> Unit) {
        scope.launch {
            val lines = mutableListOf<String>()
            var passed = 0
            var failed = 0
            var skipped = 0

            fun emit(line: String) {
                lines += line
                onLine(line)
            }

            fun check(name: String, ok: Boolean, detail: String) {
                if (ok) passed++ else failed++
                emit("$PREFIX ${if (ok) "PASS" else "FAIL"} $name :: $detail")
            }

            fun skip(name: String, detail: String) {
                skipped++
                emit("$PREFIX SKIP $name :: $detail")
            }

            emit("$PREFIX begin ts=${Clock.System.now()}")

            // 1) 深链解析（纯 Kotlin 逻辑）
            run {
                val expectations = listOf(
                    "rhodes://chat/session-1" to "chat/session-1",
                    "rhodes://chat/abc?from=push" to "chat/abc",
                    "rhodes://operator/42" to "operator/42",
                    "rhodes://settings/notifications" to "settings/notifications",
                    "rhodes://settings" to "settings",
                    "rhodes://timeline" to "timeline",
                    "rhodes://memories" to "memories",
                    "rhodes://home" to "home",
                    "rhodes://" to "home",
                    "RHODES://CHAT/Case-Id" to "chat/Case-Id",
                )
                var okCount = 0
                val mismatches = mutableListOf<String>()
                for ((url, expected) in expectations) {
                    val parsed = DeepLinkParser.parse(url)
                    if (parsed.ok && parsed.route == expected) okCount++ else mismatches += "$url->${parsed.route}/${parsed.error}"
                }
                check("deeplink.parse", mismatches.isEmpty(), "$okCount/${expectations.size} ok ${mismatches.joinToString(";")}")

                val bad = listOf("http://evil.example/chat/1", "rhodes://chat", "rhodes://chat/a/b", "rhodes://unknown/x", "")
                val rejected = bad.count { !DeepLinkParser.parse(it).ok }
                check("deeplink.reject", rejected == bad.size, "$rejected/${bad.size} rejected")
                val q = DeepLinkParser.parse("rhodes://chat/s1?from=push&lang=zh")
                check(
                    "deeplink.query",
                    q.ok && q.query["from"] == "push" && q.query["lang"] == "zh",
                    "query=${q.query}",
                )
            }

            // 2) 剪贴板（UIPasteboard 写入读回）
            run {
                val probe = "RhodesLink-T32-clipboard-${Clock.System.now().toEpochMilliseconds()}"
                copyToClipboard(probe)
                val readBack = getClipboardText()
                check("clipboard.roundtrip", readBack == probe, "wrote=$probe readBack=$readBack")
            }

            // 3) 文件日志落盘 + 导出
            run {
                val marker = "T32-SELFTEST-LOG-${Clock.System.now().toEpochMilliseconds()}"
                IosDebugLogger.info("T32", marker)
                val content = IosDebugLogger.readCurrentLog()
                val path = IosDebugLogger.currentLogPath()
                check(
                    "logger.write",
                    content.contains(marker) && FileIo.exists(path),
                    "path=$path bytes=${IosDebugLogger.currentLogBytes()}",
                )
                val exported = IosDebugLogger.exportToFile()
                check("logger.export", exported.isNotEmpty() && FileIo.exists(exported), "export=$exported")
                emit("$PREFIX INFO logs=${IosDebugLogger.listLogFiles()}")
            }

            // 4) 文件导出/导入（依赖 Swift 注入 FilePickerHost；无宿主则 SKIP）
            if (IosFileTransfer.hasHost()) {
                val exportResult = suspendCancellableCoroutine { cont ->
                    IosFileTransfer.exportText("t32-selftest.txt", "RhodesLink T32 file transfer probe\n") { cont.resume(it) }
                }
                check(
                    "file.export",
                    exportResult.ok && exportResult.path.isNotEmpty() && FileIo.exists(exportResult.path),
                    "path=${exportResult.path} err=${exportResult.error}",
                )
                val importResult = suspendCancellableCoroutine { cont ->
                    IosFileTransfer.importFile { cont.resume(it) }
                }
                check(
                    "file.import",
                    importResult.ok && importResult.path.isNotEmpty() && FileIo.exists(importResult.path)
                        && importedContentMatches(importResult.path),
                    "path=${importResult.path} err=${importResult.error} cancelled=${importResult.cancelled}",
                )
            } else {
                skip("file.export", "FilePickerHost not installed")
                skip("file.import", "FilePickerHost not installed")
            }

            // 5) 通知：权限 → 调度（幂等 id）→ 等待 → 送达查询 → 清理
            val statusBefore = suspendCancellableCoroutine { cont ->
                IosNotificationScheduler.authorizationStatus { cont.resume(it) }
            }
            emit("$PREFIX INFO notification.status.before=$statusBefore")
            val statusAfter =
                if (statusBefore.granted) {
                    statusBefore
                } else {
                    suspendCancellableCoroutine { cont ->
                        IosNotificationScheduler.requestProvisionalAuthorization { cont.resume(it) }
                    }
                }
            check(
                "notification.authorization",
                statusAfter.granted,
                "status=${statusAfter.status}/${statusAfter.statusName} provisional=${statusAfter.provisional} err=${statusAfter.error}",
            )
            if (statusAfter.granted) {
                val identifier = "moment-reply-done-t32-selftest"
                val event = NotificationEvent(
                    id = identifier,
                    category = com.rhodes.privatechat.shared.domain.automation.NotificationCategory.MOMENT_REPLY_COMPLETED,
                    title = "T32 自检",
                    body = "通知链路自检：2 秒后应送达通知中心。",
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    payload = mapOf("source" to "ios-services-selftest"),
                )
                val scheduled = suspendCancellableCoroutine { cont ->
                    IosNotificationScheduler.scheduleEvent(event, 2.0) { cont.resume(it) }
                }
                check("notification.schedule", scheduled.ok, "id=${scheduled.identifier} err=${scheduled.error}")

                val scheduledAgain = suspendCancellableCoroutine { cont ->
                    IosNotificationScheduler.scheduleEvent(event, 2.0) { cont.resume(it) }
                }
                val pending = suspendCancellableCoroutine { cont ->
                    IosNotificationScheduler.pendingCount { cont.resume(it) }
                }
                check(
                    "notification.idempotent",
                    scheduledAgain.ok && pending <= 1,
                    "rescheduled=${scheduledAgain.ok} pendingCount=$pending (同 id 覆盖)",
                )

                delay(3_500)
                val deliveredIds = suspendCancellableCoroutine { cont ->
                    IosNotificationScheduler.deliveredIdentifiers { cont.resume(it) }
                }
                check(
                    "notification.delivered",
                    deliveredIds.contains(identifier),
                    "delivered=${deliveredIds.joinToString(",")}",
                )
                IosNotificationScheduler.cancel(identifier)
                IosDebugLogger.info("T32", "notification selftest delivered=$deliveredIds")
            } else {
                skip("notification.schedule", "notification permission not granted")
                skip("notification.delivered", "notification permission not granted")
            }

            val summary = "$PREFIX summary passed=$passed failed=$failed skipped=$skipped"
            emit(summary)
            IosDebugLogger.info("T32", summary)
            onDone(ServicesSelfTestResult(passed, failed, skipped, lines, summary))
        }
    }

    private fun importedContentMatches(path: String): Boolean {
        val bytes = FileIo.readBytes(path) ?: return false
        return bytes.isNotEmpty()
    }
}
