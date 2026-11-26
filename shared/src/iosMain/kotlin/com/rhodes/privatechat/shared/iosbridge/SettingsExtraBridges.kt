package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.settings.createPlatformSettings
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * T53 设置余项桥工厂（调试日志 + 通知设置）。
 *
 * 不扩展 `IosAppGraph` / `SettingsBridge`（T45/T54 并行写域），本文件自包含：
 * - [DebugLogBridge] 无依赖，直连 shared `IosDebugLogger`；
 * - [NotificationBridge] 自带一份 `SettingsRepository`（NSUserDefaults 标准域，与
 *   `IosAppGraph` 内的实例读写同一批键，互不冲突）。
 *
 * Swift 侧入口：`SettingsExtraBridgesKt.makeSettingsExtraBridges()`（只创建一次，见
 * `Bridge/SettingsExtraClient.swift`）。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "SettingsExtraBridges")
class SettingsExtraBridges internal constructor(
    private val debugLogBridge: DebugLogBridge,
    private val notificationBridge: NotificationBridge,
) {
    /** 调试日志桥（读日志文件 / 导出 / 清空 / 打点）。 */
    fun debugLogs(): DebugLogBridge = debugLogBridge

    /** 通知设置桥（权限状态/请求 + 4 类提醒偏好键）。 */
    fun notifications(): NotificationBridge = notificationBridge
}

@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "makeSettingsExtraBridges")
fun makeSettingsExtraBridges(): SettingsExtraBridges = SettingsExtraBridges(
    debugLogBridge = DebugLogBridge(),
    notificationBridge = NotificationBridge(SettingsRepository(createPlatformSettings())),
)
