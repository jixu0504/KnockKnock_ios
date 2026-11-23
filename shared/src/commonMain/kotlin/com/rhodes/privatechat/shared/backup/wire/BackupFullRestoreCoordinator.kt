package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BackupContentSelection
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupManifestWire
import com.rhodes.privatechat.shared.backup.BackupMediaSink
import com.rhodes.privatechat.shared.backup.BackupMediaItemWire
import com.rhodes.privatechat.shared.backup.BackupRestoreOptions
import com.rhodes.privatechat.shared.backup.BackupRestoreProgress
import com.rhodes.privatechat.shared.backup.BackupRestoreRepository
import com.rhodes.privatechat.shared.backup.BackupRestoreSelfCheckResult
import com.rhodes.privatechat.shared.backup.BackupRestoreStage
import com.rhodes.privatechat.shared.backup.BackupValidationResult
import com.rhodes.privatechat.shared.backup.BackupZip
import com.rhodes.privatechat.shared.backup.ChatRepositoryBackupAdapter
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/** 全类目恢复结果（与 T10 `BackupRestoreResult` 同形 + `selfCheck`）。 */
data class BackupFullRestoreResult(
    val success: Boolean,
    val stage: BackupRestoreStage,
    val manifest: BackupManifestWire? = null,
    val reason: String = "",
    val selfCheck: BackupRestoreSelfCheckResult? = null,
)

/**
 * 全类目恢复编排（T31；规格卡契约 C7/C8/C12/C14）。
 *
 * 阶段与三层回滚语义完全沿用 T10 `BackupRestoreCoordinator`/T30 `BackupRestoreCoordinatorEx`：
 *  - 阶段：VALIDATING → CREATING_SAFETY_BACKUP（Phase 0 不落盘）→ STOPPING_BACKGROUND_WORK（no-op）→
 *    RESTORING_MEDIA（staging）→ RESTORING_DATABASE（单事务：删+插+提交前自检）→ REBUILDING_RUNTIME（no-op）→
 *    COMPLETED；任何异常 → FAILED（返回 success=false）。
 *  - 三层回滚：单事务回滚（executor 内抛出）+ 媒体目录 cleanup + 设置快照回放（含本次新增 key 删除）。
 *  - 取消：`CancellationException` 上抛，清理媒体，事务未提交自然回滚。
 *  - 提交后自检 mismatch 不翻转 success（T10 口径）。
 */
class BackupFullRestoreCoordinator(
    private val repository: BackupRestoreRepository,
    private val settings: SettingsRepository,
    private val reader: FullBackupReader = FullBackupReader(),
    private val mediaRewriter: FullBackupMediaRewriter = FullBackupMediaRewriter,
    private val contentFilter: FullBackupContentFilter = FullBackupContentFilter,
    private val settingsRestorer: BackupFullSettingsRestorer = BackupFullSettingsRestorer(),
    private val executor: BackupFullRestoreExecutor = BackupFullRestoreExecutor(repository),
    private val selfCheck: BackupFullSelfCheck = BackupFullSelfCheck(),
) {
    /** 生产便捷构造：接 shared ChatRepository（Koin 注入）。 */
    constructor(repository: ChatRepository, settings: SettingsRepository) : this(
        ChatRepositoryBackupAdapter(repository), settings,
        executor = BackupFullRestoreExecutor(repository),
    )

    suspend fun inspect(bytes: ByteArray): BackupValidationResult = reader.validate(bytes)

    suspend fun restore(
        bytes: ByteArray,
        mediaSink: BackupMediaSink,
        onProgress: (BackupRestoreProgress) -> Unit = {},
        options: BackupRestoreOptions = BackupRestoreOptions(),
    ): BackupFullRestoreResult {
        var manifest: BackupManifestWire? = null
        try {
            onProgress(BackupRestoreProgress(BackupRestoreStage.VALIDATING, "正在校验备份文件"))
            val archive = reader.read(bytes)
            manifest = archive.manifest
            val selection = options.contentSelection.normalized()

            onProgress(BackupRestoreProgress(BackupRestoreStage.CREATING_SAFETY_BACKUP, "恢复前安全备份（Phase 0 跳过文件落盘）"))
            onProgress(BackupRestoreProgress(BackupRestoreStage.STOPPING_BACKGROUND_WORK, "正在停止后台任务"))

            val mediaBytes: Map<String, ByteArray> =
                if (selection.media && archive.payload.media.isNotEmpty()) readMediaBytes(bytes, archive.payload.media)
                else emptyMap()
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
            val rewritten = mediaRewriter.rewrite(archive.payload, stagedMedia)
            val filtered = contentFilter.filterIssues(rewritten, options.skipIssueCodes)

            onProgress(BackupRestoreProgress(BackupRestoreStage.RESTORING_DATABASE, "正在恢复结构化数据"))
            // 设置（Preferences 在 SQLite 事务外，与 oracle BackupRestoreCoordinator.kt:96-111 同语义）。
            // D3：settings 选中 = 快照+清空+全量 apply（即使备份 settings 为空也要清空白名单）；
            //     未选中但恢复 roles/chats = 仅 apply 角色局部 key 家族。
            val settingsValues = filtered.content.settings.orEmpty()
            var appliedSettings: Map<String, String> = emptyMap()
            var previousSettings: Map<String, String>? = null
            if (selection.settings) {
                appliedSettings = settingsValues
                previousSettings = settingsRestorer.snapshot(repository, settings)
                settingsRestorer.clearForRestore(repository, settings)
                settingsRestorer.apply(settings, appliedSettings)
            } else if (selection.roles || selection.chats) {
                val roleLocal = settingsRestorer.select(settingsValues, full = false)
                if (roleLocal.isNotEmpty()) {
                    appliedSettings = roleLocal
                    previousSettings = settingsRestorer.snapshot(repository, settings)
                    settingsRestorer.apply(settings, appliedSettings)
                }
            }
            try {
                executor.restore(filtered, selection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { mediaSink.cleanup() }
                previousSettings?.let { previous ->
                    runCatching { settingsRestorer.rollback(settings, previous, appliedSettings) }
                }
                throw e
            }

            onProgress(BackupRestoreProgress(BackupRestoreStage.REBUILDING_RUNTIME, "正在重新安排自动内容"))
            val check = runCatching {
                selfCheck.check(archive.manifest, selection, filtered, repository, settings)
            }.getOrNull()
            onProgress(BackupRestoreProgress(BackupRestoreStage.COMPLETED, "恢复完成"))
            return BackupFullRestoreResult(true, BackupRestoreStage.COMPLETED, manifest, selfCheck = check)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(BackupRestoreProgress(BackupRestoreStage.FAILED, e.message ?: "恢复失败"))
            return BackupFullRestoreResult(false, BackupRestoreStage.FAILED, manifest, e.message ?: "恢复失败")
        }
    }

    /** 从 ZIP 中取出 manifest 声明的媒体条目字节（与 T10 `readMediaBytes` 同口径）。 */
    private fun readMediaBytes(bytes: ByteArray, media: List<BackupMediaItemWire>): Map<String, ByteArray> {
        val expected = media.map { it.archivePath }.toSet()
        val entries = BackupZip.read(
            bytes,
            maxEntries = 1_000,
            maxEntryBytes = 100L * 1024 * 1024,
            maxTotalBytes = 500L * 1024 * 1024,
        )
        val out = linkedMapOf<String, ByteArray>()
        for (entry in entries) {
            val path = entry.name.replace('\\', '/')
            if (path in expected && !entry.isDirectory) out[path] = entry.data
        }
        if (out.keys != expected) throw BackupFormatException("备份媒体文件不完整，已取消恢复")
        return out
    }
}
