@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.ui.util

import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController

/**
 * iOS 平台工具 actual 实现（T32 补齐 shareText / 剪贴板）。
 *
 * 线程约定：
 * - 剪贴板（UIPasteboard）调用来自 Main 或后台均可（UIKit 保证 generalPasteboard 可用）；
 * - 分享面板必须 Main：统一经 [runOnMain] 投递（NSOperationQueue.mainQueue）。
 */
actual fun showToast(message: String) {
    // TODO(iOS): 用 UIKit 横幅/UIAlertController 实现轻提示（Phase 3 玻璃壳落地时补齐）
}

actual fun copyToClipboard(text: String) {
    UIPasteboard.generalPasteboard.string = text
}

actual fun getClipboardText(): String? = UIPasteboard.generalPasteboard.string

/**
 * 系统分享面板（UIActivityViewController）。
 *
 * K/N 侧从 keyWindow（回退 windows.first）取 rootViewController present；iPad 需要
 * popover 锚点（sourceView/sourceRect），否则 iPad 上会崩溃。调用方可在任意线程。
 */
actual fun shareText(text: String) {
    if (text.isEmpty()) return
    runOnMain {
        val root = rootViewController() ?: return@runOnMain
        val activity = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        activity.popoverPresentationController()?.let { popover ->
            popover.sourceView = root.view
            popover.sourceRect = CGRectMake(0.0, 0.0, 1.0, 1.0)
        }
        root.presentViewController(activity, animated = true, completion = null)
    }
}

actual fun minimizeApp() {
    // iOS 无「最小化」概念，no-op
}

private fun runOnMain(block: () -> Unit) {
    NSOperationQueue.mainQueue.addOperationWithBlock { block() }
}

/** keyWindow → windows 回退，保证 SwiftUI 场景下能拿到 present 上下文。 */
private fun rootViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    val keyWindow = app.keyWindow
    if (keyWindow != null) return keyWindow.rootViewController
    val anyWindow = app.windows.firstOrNull { it is UIWindow } as? UIWindow
    return anyWindow?.rootViewController
}
