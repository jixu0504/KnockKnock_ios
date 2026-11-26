package com.rhodes.privatechat.shared.settings

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.ObservableSettings
import platform.Foundation.NSUserDefaults

actual fun createPlatformSettings(): ObservableSettings =
    NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
