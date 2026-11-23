package com.rhodes.privatechat.shared

/**
 * 平台错误日志（iOS-port 抽取）：Android 走 Log.e，iOS 走 NSLog。
 * 级别与文案语义与原 android.util.Log.e(tag, msg, throwable) 一致。
 */
expect fun logError(tag: String, message: String, throwable: Throwable? = null)
