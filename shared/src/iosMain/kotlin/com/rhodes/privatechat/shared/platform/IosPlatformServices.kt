@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rhodes.privatechat.shared.platform

import com.rhodes.privatechat.shared.ui.util.copyToClipboard as platformCopyToClipboard
import com.rhodes.privatechat.shared.ui.util.getClipboardText as platformClipboardText
import com.rhodes.privatechat.shared.ui.util.shareText as platformShareText
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController

/**
 * Swift 侧统一服务入口（薄封装，命名稳定；底层复用 commonMain 的 expect actuals）。
 *
 * 为什么多一层：普通顶层 expect 函数在 K/N 的 ObjC 导出名依赖文件 facade（`PlatformUtilsKt`），
 * 对 Swift 不稳定也不直观；这里用 object 把剪贴板/分享/文件分享固定成 `IosPlatformServices.shared.xxx`。
 */
object IosPlatformServices {

    // ---------------------------------------------------------------- 剪贴板

    fun copyToClipboard(text: String) = platformCopyToClipboard(text)

    fun clipboardText(): String = platformClipboardText() ?: ""

    fun clipboardProbe(text: String): String {
        platformCopyToClipboard(text)
        return platformClipboardText() ?: ""
    }

    // ---------------------------------------------------------------- 分享

    /** 系统文本分享面板（UIActivityViewController）。 */
    fun shareText(text: String) {
        if (text.isEmpty()) return
        runOnMain {
            val root = rootViewController() ?: return@runOnMain
            presentActivity(root, listOf(text))
        }
    }

    /** 系统文件分享面板（UIActivityViewController + file URL；日志导出后直接分享）。 */
    fun shareFile(path: String) {
        if (path.isEmpty()) return
        runOnMain {
            val root = rootViewController() ?: return@runOnMain
            val url = NSURL(fileURLWithPath = path)
            presentActivity(root, listOf(url))
        }
    }

    // ---------------------------------------------------------------- 宿主注入（启动时由 Swift 调用）

    /** Swift 壳启动时注入 UIDocumentPickerViewController 宿主（见 [FilePickerHost]）。 */
    fun installFilePickerHost(host: FilePickerHost?) {
        IosFileTransfer.installHost(host)
    }

    fun hasFilePickerHost(): Boolean = IosFileTransfer.hasHost()

    // ---------------------------------------------------------------- 内部

    private fun presentActivity(root: UIViewController, items: List<Any>) {
        val activity = UIActivityViewController(activityItems = items, applicationActivities = null)
        activity.popoverPresentationController()?.let { popover ->
            popover.sourceView = root.view
            popover.sourceRect = CGRectMake(0.0, 0.0, 1.0, 1.0)
        }
        root.presentViewController(activity, animated = true, completion = null)
    }

    private fun runOnMain(block: () -> Unit) {
        NSOperationQueue.mainQueue.addOperationWithBlock { block() }
    }

    private fun rootViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        app.keyWindow?.rootViewController?.let { return it }
        val anyWindow = app.windows.firstOrNull { it is UIWindow } as? UIWindow
        return anyWindow?.rootViewController
    }
}
