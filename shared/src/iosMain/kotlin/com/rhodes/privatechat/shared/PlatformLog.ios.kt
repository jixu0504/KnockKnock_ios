package com.rhodes.privatechat.shared

import platform.Foundation.NSLog

actual fun logError(tag: String, message: String, throwable: Throwable?) {
    NSLog("%@: %@", tag, message)
    throwable?.let { NSLog("%@", it.stackTraceToString()) }
}
