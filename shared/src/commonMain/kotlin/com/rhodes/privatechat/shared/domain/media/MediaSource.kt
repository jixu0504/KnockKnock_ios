package com.rhodes.privatechat.shared.domain.media

/**
 * 媒体来源枚举（规格卡 message-id-media-spec.md 契约 13）。
 *
 * 与 Android 落点的对照（audit/paths.md §4 目录映射）：
 *   PICKER         相册/文件选择器 → 头像/背景       Android filesDir/images        iOS media/images/
 *   CAMERA         相机 → 聊天图（1920 缩放由调用方在 ingest 前后做，镜像 MainActivity.kt:178-193）
 *                   Android filesDir/chat_images      iOS media/chat_images/
 *   VOICE          录音/TTS                          Android filesDir/voice          iOS media/voice/
 *   REMOTE         下载/AI 生成 http(s)/data:        Android 不落盘（MainActivity.kt:197-198 透传） iOS 同
 *   BUILTIN_AVATAR 内置头像 1..147                    Android res/drawable/avatar_N   iOS app bundle 资源，不落 media 目录
 *   BACKUP_IMPORT  备份导入（BackupMediaCollector.kt:24-30 + BackupMediaStager）
 *                   Android filesDir/restored_media/<backupId>/media/images/
 *                   iOS Application Support/restored_media/<backupId>/media/images/（T10 mediaId 直映射）
 */
enum class MediaSource {
    PICKER,
    CAMERA,
    REMOTE,
    BUILTIN_AVATAR,
    BACKUP_IMPORT,
    VOICE,
}
