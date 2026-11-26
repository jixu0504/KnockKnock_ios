package com.rhodes.privatechat.shared.backup

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/** coordinator 对仓储的最小依赖面（生产 = ChatRepository 适配器；测试 = RhodesDatabase 直连假实现）。 */
interface BackupRestoreRepository {
    suspend fun getAllOperatorsSync(): List<Operator>
    suspend fun getAllSessionsSync(): List<ChatSession>
    suspend fun runRestoreTransaction(block: RhodesDatabase.() -> Unit)
}

/** 生产适配器（ChatRepository.kt:55 的 runRestoreTransaction 是 Android oracle 的事务注入点）。 */
class ChatRepositoryBackupAdapter(private val repository: ChatRepository) : BackupRestoreRepository {
    override suspend fun getAllOperatorsSync(): List<Operator> = repository.getAllOperatorsSync()
    override suspend fun getAllSessionsSync(): List<ChatSession> = repository.getAllSessionsSync()
    override suspend fun runRestoreTransaction(block: RhodesDatabase.() -> Unit) = repository.runRestoreTransaction(block)
}

/**
 * 恢复编排的最小移植（oracle：app/.../data/backup/BackupRestoreCoordinator.kt:58-133）。
 *
 * lead 裁决 b（2026-11-22）：Phase 0 三层自动回滚 = 单事务回滚 + 媒体目录删除 + 设置快照回放；
 * 不落盘 before_restore_*.rbackup 安全备份文件（保留策略随 A5 完整实现）。
 * 平台差异补强（规格卡 P2 白名单）：任何失败（含媒体 staging 失败）都清理部分解出的媒体目录
 * （Android 现状只删 restoreInput，:125-129）。
 */
enum class BackupRestoreStage {
    VALIDATING, CREATING_SAFETY_BACKUP, STOPPING_BACKGROUND_WORK, RESTORING_MEDIA, RESTORING_DATABASE,
    REBUILDING_RUNTIME, COMPLETED, FAILED,
}

data class BackupRestoreProgress(
    val stage: BackupRestoreStage,
    val detail: String,
    val completed: Int? = null,
    val total: Int? = null,
)

data class BackupRestoreResult(
    val success: Boolean,
    val stage: BackupRestoreStage,
    val manifest: BackupManifestWire? = null,
    val reason: String = "",
)

/** 媒体落盘抽象：iosMain 用 NSFileManager 写入 Documents/restored_media/<backupId>；测试用内存实现。 */
interface BackupMediaSink {
    /** 写入全部媒体条目，返回 mediaId → 新 URL；任一失败抛错（调用方随后 cleanup 清理残留）。 */
    fun stage(media: List<BackupMediaItemWire>, entryBytes: Map<String, ByteArray>): Map<String, String>

    /** 递归删除 staging 目录（幂等）。 */
    fun cleanup()
}

class BackupRestoreCoordinator(
    private val repository: BackupRestoreRepository,
    private val settings: SettingsRepository,
    private val fileReader: BackupFileReader = BackupFileReader(),
    private val restoreExecutor: BackupRestoreExecutor = BackupRestoreExecutor { block -> repository.runRestoreTransaction(block) },
    private val portableSettings: BackupPortableSettings = BackupPortableSettings(),
) {
    /** 生产便捷构造：接 shared 的 ChatRepository（Koin 注入）。 */
    constructor(repository: ChatRepository, settings: SettingsRepository) : this(
        ChatRepositoryBackupAdapter(repository), settings,
    )

    suspend fun inspect(bytes: ByteArray): BackupValidationResult = fileReader.validate(bytes)

    suspend fun restore(
        bytes: ByteArray,
        mediaSink: BackupMediaSink,
        onProgress: (BackupRestoreProgress) -> Unit = {},
        options: BackupRestoreOptions = BackupRestoreOptions(),
    ): BackupRestoreResult {
        var manifest: BackupManifestWire? = null
        var previousSettings: Map<String, String>? = null
        var stagedMedia: Map<String, String> = emptyMap()
        return try {
            // VALIDATING（oracle :64-73）
            onProgress(BackupRestoreProgress(BackupRestoreStage.VALIDATING, "正在校验备份文件"))
            val archive = fileReader.read(bytes)
            manifest = archive.manifest

            // CREATING_SAFETY_BACKUP（Phase 0：不落盘安全备份文件，lead 裁决 b）
            onProgress(BackupRestoreProgress(BackupRestoreStage.CREATING_SAFETY_BACKUP, "恢复前安全备份（Phase 0 跳过文件落盘）"))

            // STOPPING_BACKGROUND_WORK（iOS：停自动化 actor；Phase 0 最小闭环 no-op）
            onProgress(BackupRestoreProgress(BackupRestoreStage.STOPPING_BACKGROUND_WORK, "正在停止后台任务"))

            val selection = options.contentSelection.normalized()
            val mediaBytes: Map<String, ByteArray> = if (selection.media && archive.payload.media.isNotEmpty()) {
                readMediaBytes(bytes, archive.payload.media)
            } else emptyMap()

            // RESTORING_MEDIA（oracle :85-89）
            onProgress(BackupRestoreProgress(BackupRestoreStage.RESTORING_MEDIA, "正在准备图片文件"))
            stagedMedia = if (!selection.media || archive.payload.media.isEmpty()) emptyMap() else {
                try {
                    mediaSink.stage(archive.payload.media, mediaBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { mediaSink.cleanup() } // P2 补强：staging 失败也清理部分解出目录
                    throw e
                }
            }
            val restoredPayload = BackupContentFilter.filterIssues(
                BackupRestorePayloadRewriter.rewriteMediaUris(archive.payload, stagedMedia),
                options.skipIssueCodes,
            )

            // RESTORING_DATABASE（oracle :95-111）
            onProgress(BackupRestoreProgress(BackupRestoreStage.RESTORING_DATABASE, "正在恢复结构化数据"))
            try {
                if (options.contentSelection.normalized().settings) {
                    previousSettings = portableSettings.snapshot(repository, settings)
                    portableSettings.clearForRestore(repository, settings)
                }
                restoreExecutor.restore(restoredPayload, options.contentSelection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { mediaSink.cleanup() }
                previousSettings?.let { portableSettings.apply(settings, it) }
                throw e
            }

            // REBUILDING_RUNTIME / COMPLETED（oracle :113-120）
            onProgress(BackupRestoreProgress(BackupRestoreStage.REBUILDING_RUNTIME, "正在重新安排自动内容"))
            onProgress(BackupRestoreProgress(BackupRestoreStage.COMPLETED, "恢复完成"))
            BackupRestoreResult(true, BackupRestoreStage.COMPLETED, manifest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(BackupRestoreProgress(BackupRestoreStage.FAILED, e.message ?: "恢复失败"))
            BackupRestoreResult(false, BackupRestoreStage.FAILED, manifest, e.message ?: "恢复失败")
        }
    }

    /** 从 ZIP 中取出 manifest 声明的媒体条目字节（staging 前置，与 BackupMediaStager.extract 的取数口径一致）。 */
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
