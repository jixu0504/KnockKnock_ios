package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.platform.IosNotificationScheduler
import com.rhodes.privatechat.shared.platform.NotificationPermissionStatus
import com.rhodes.privatechat.shared.settings.SettingsRepository
import com.rhodes.privatechat.shared.settings.createPlatformSettings
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * 通知设置桥（T53）：通知权限状态读取/请求 + 4 类提醒偏好开关。
 *
 * 权限：直接包 [IosNotificationScheduler]（UNUserNotificationCenter）：
 *  - [authorizationStatus] 只读，不弹窗；
 *  - [requestProvisionalAuthorization] 静默（provisional）授权，不弹窗（T13 结论）；
 *  - [requestFullAuthorization] 完整授权（真机会弹系统框；用户进入通知设置页时再请求）；
 *  - [openSystemSettings] 权限被拒后的引导。
 *
 * 偏好开关：iOS 侧此前无通知偏好键（Android oracle 只有通知中心 + 系统通知权限），
 * 本卡新增 4 个 settings 键（默认 true，全部落 NSUserDefaults，冷启动保持）：
 *  - `notify_private_reply` 私聊回复提醒
 *  - `notify_group_reply`   群聊回复提醒
 *  - `notify_moments`       动态（发布/评论）提醒
 *  - `notify_diary`         干员日记提醒
 *
 * 已知限制：当前 iOS 侧尚无「回复落库后按这些开关发本地通知」的接线（通知调度器 T32 已有，
 * 事件源在聊天/日记管线侧，属后续批次）；本卡先保证偏好键可读写、可持久化、UI 与权限状态真实。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "NotificationBridge")
class NotificationBridge internal constructor(
    private val repo: SettingsRepository,
) {

    /** 读 4 个提醒偏好 + 诊断快照。 */
    fun readPrefs(): NotificationPrefsDto = NotificationPrefsDto(
        privateReply = repo.getBoolean(KEY_PRIVATE_REPLY, true),
        groupReply = repo.getBoolean(KEY_GROUP_REPLY, true),
        moments = repo.getBoolean(KEY_MOMENTS, true),
        diary = repo.getBoolean(KEY_DIARY, true),
        settingsKeysCsv = "$KEY_PRIVATE_REPLY\n$KEY_GROUP_REPLY\n$KEY_MOMENTS\n$KEY_DIARY",
    )

    /**
     * 保存单个偏好开关。返回空串 = 成功；非空 = 错误说明。
     * @param scope private_reply / group_reply / moments / diary（与设置键一一对应）
     */
    fun savePref(scope: String, enabled: Boolean): String {
        val key = when (scope.trim().lowercase()) {
            "private_reply", "private" -> KEY_PRIVATE_REPLY
            "group_reply", "group" -> KEY_GROUP_REPLY
            "moments", "moment" -> KEY_MOMENTS
            "diary" -> KEY_DIARY
            else -> return "未知通知偏好范围：$scope"
        }
        repo.putBoolean(key, enabled)
        return ""
    }

    /** 权限状态（只读，不触发授权弹窗）。回调在主队列。 */
    fun authorizationStatus(onResult: (NotificationPermissionStatus) -> Unit) {
        IosNotificationScheduler.authorizationStatus { onResult(it) }
    }

    /** 完整授权（alert/badge/sound；真机会弹系统框）。回调在主队列。 */
    fun requestFullAuthorization(onResult: (NotificationPermissionStatus) -> Unit) {
        IosNotificationScheduler.requestFullAuthorization { onResult(it) }
    }

    /** 静默授权（provisional；不弹窗，通知进通知中心）。回调在主队列。 */
    fun requestProvisionalAuthorization(onResult: (NotificationPermissionStatus) -> Unit) {
        IosNotificationScheduler.requestProvisionalAuthorization { onResult(it) }
    }

    /** 打开系统「设置 → 通知 → RhodesLink」。 */
    fun openSystemSettings() {
        IosNotificationScheduler.openSettings()
    }

    /** 诊断快照：`private=true;group=true;moments=true;diary=true`。 */
    fun prefsSnapshot(): String {
        val prefs = readPrefs()
        return "private=${prefs.privateReply};group=${prefs.groupReply};" +
            "moments=${prefs.moments};diary=${prefs.diary}"
    }

    companion object {
        const val KEY_PRIVATE_REPLY: String = "notify_private_reply"
        const val KEY_GROUP_REPLY: String = "notify_group_reply"
        const val KEY_MOMENTS: String = "notify_moments"
        const val KEY_DIARY: String = "notify_diary"
    }
}

/** 通知偏好快照（4 个提醒开关 + 对应设置键清单）。 */
class NotificationPrefsDto(
    val privateReply: Boolean,
    val groupReply: Boolean,
    val moments: Boolean,
    val diary: Boolean,
    /** 本卡新增的 4 个 settings 键（`\n` 分隔，证据/文档用）。 */
    val settingsKeysCsv: String,
)
