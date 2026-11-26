package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * 完整备份恢复编排「补齐版」（规格卡契约 D31；T30）。
 *
 * 与 T10 [BackupRestoreCoordinator] 的差异（T10 类保持不动，本类仅新增文件）：
 *  1. 数据库恢复走 [BackupSelectiveRestoreExecutor]（插入也按分类选择门禁）；
 *  2. 恢复提交后执行 [BackupRestoreSelfCheck] 对账（recordCounts vs DB 计数）；
 *  3. 其余阶段/三层回滚语义与 T10 完全一致：VALIDATING → CREATING_SAFETY_BACKUP（Phase 0 不落盘）→
 *     STOPPING_BACKGROUND_WORK → RESTORING_MEDIA → RESTORING_DATABASE → 自检 → REBUILDING_RUNTIME → COMPLETED；
 *     失败 = 单事务回滚 + 媒体目录删除 + 设置快照回放。
 */
data class BackupRestoreCompletionResult(
    val success: Boolean,
    val stage: BackupRestoreStage,
    val manifest: BackupManifestWire? = null,
    val reason: String = "",
    val selfCheck: BackupRestoreSelfCheckResult? = null,
)

class BackupRestoreCoordinatorEx(
    private val repository: BackupRestoreRepository,
    private val settings: SettingsRepository,
    private val fileReader: BackupFileReader = BackupFileReader(),
    private val restoreExecutor: BackupSelectiveRestoreExecutor = BackupSelectiveRestoreExecutor(repository),
    private val portableSettings: BackupPortableSettings = BackupPortableSettings(),
    private val selfCheck: BackupRestoreSelfCheck = BackupRestoreSelfCheck(),
) {
    /** 生产便捷构造：接 shared ChatRepository（Koin 注入）。 */
    constructor(repository: ChatRepository, settings: SettingsRepository) : this(
        ChatRepositoryBackupAdapter(repository), settings,
        restoreExecutor = BackupSelectiveRestoreExecutor(repository),
    )

    suspend fun restore(
        bytes: ByteArray,
        mediaSink: BackupMediaSink,
        onProgress: (BackupRestoreProgress) -> Unit = {},
        options: BackupRestoreOptions = BackupRestoreOptions(),
    ): BackupRestoreCompletionResult {
        var manifest: BackupManifestWire? = null
        var previousSettings: Map<String, String>? = null
        try {
            onProgress(BackupRestoreProgress(BackupRestoreStage.VALIDATING, "正在校验备份文件"))
            val archive = fileReader.read(bytes)
            manifest = archive.manifest

            onProgress(BackupRestoreProgress(BackupRestoreStage.CREATING_SAFETY_BACKUP, "恢复前安全备份（Phase 0 跳过文件落盘）"))
            onProgress(BackupRestoreProgress(BackupRestoreStage.STOPPING_BACKGROUND_WORK, "正在停止后台任务"))

            val selection = options.contentSelection.normalized()
            val mediaBytes: Map<String, ByteArray> = if (selection.media && archive.payload.media.isNotEmpty()) {
                readMediaBytes(bytes, archive.payload.media)
            } else emptyMap()

            onProgress(BackupRestoreProgress(BackupRestoreStage.RESTORING_MEDIA, "正在准备图片文件"))
            val stagedMedia = if (!selection.media || archive.payload.media.isEmpty()) emptyMap() else {
                try {
                    mediaSink.stage(archive.payload.media, mediaBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { mediaSink.cleanup() }
                    throw e
                }
            }
            val restoredPayload = BackupContentFilter.filterIssues(
                BackupRestorePayloadRewriter.rewriteMediaUris(archive.payload, stagedMedia),
                options.skipIssueCodes,
            )

            onProgress(BackupRestoreProgress(BackupRestoreStage.RESTORING_DATABASE, "正在恢复结构化数据"))
            try {
                if (selection.settings) {
                    previousSettings = portableSettings.snapshot(repository, settings)
                    portableSettings.clearForRestore(repository, settings)
                }
                restoreExecutor.restore(restoredPayload, selection)
                if (selection.settings && restoredPayload.content.settings != null) {
                    portableSettings.apply(settings, restoredPayload.content.settings.orEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { mediaSink.cleanup() }
                previousSettings?.let { portableSettings.apply(settings, it) }
                throw e
            }

            onProgress(BackupRestoreProgress(BackupRestoreStage.REBUILDING_RUNTIME, "正在重新安排自动内容"))
            val check = runCatching { selfCheck.check(archive.manifest, selection, repository) }.getOrNull()
            onProgress(BackupRestoreProgress(BackupRestoreStage.COMPLETED, "恢复完成"))
            return BackupRestoreCompletionResult(true, BackupRestoreStage.COMPLETED, manifest, selfCheck = check)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(BackupRestoreProgress(BackupRestoreStage.FAILED, e.message ?: "恢复失败"))
            return BackupRestoreCompletionResult(false, BackupRestoreStage.FAILED, manifest, e.message ?: "恢复失败")
        }
    }

    /** 从 ZIP 中取出 manifest 声明的媒体条目字节（与 T10 `readMediaBytes` 同口径）。 */
    private fun readMediaBytes(bytes: ByteArray, media: List<BackupMediaItemWire>): Map<String, ByteArray> {
        val expected = media.map { it.archivePath }.toSet()
        val entries = BackupZip.read(bytes, maxEntries = 1_000, maxEntryBytes = 100L * 1024 * 1024, maxTotalBytes = 500L * 1024 * 1024)
        val out = linkedMapOf<String, ByteArray>()
        for (entry in entries) {
            val path = entry.name.replace('\\', '/')
            if (path in expected && !entry.isDirectory) out[path] = entry.data
        }
        if (out.keys != expected) throw BackupFormatException("备份媒体文件不完整，已取消恢复")
        return out
    }
}
