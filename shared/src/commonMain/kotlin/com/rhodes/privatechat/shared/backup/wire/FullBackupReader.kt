package com.rhodes.privatechat.shared.backup.wire

import com.rhodes.privatechat.shared.backup.BACKUP_FORMAT_VERSION
import com.rhodes.privatechat.shared.backup.BACKUP_LEGACY_FORMAT_VERSION
import com.rhodes.privatechat.shared.backup.BACKUP_MAX_PAGE_BYTES
import com.rhodes.privatechat.shared.backup.BACKUP_PAGES_ROOT
import com.rhodes.privatechat.shared.backup.BACKUP_PAYLOAD_PATH
import com.rhodes.privatechat.shared.backup.BackupFileReader
import com.rhodes.privatechat.shared.backup.BackupFormatException
import com.rhodes.privatechat.shared.backup.BackupArchiveWire
import com.rhodes.privatechat.shared.backup.BackupPayloadWire
import com.rhodes.privatechat.shared.backup.BackupValidationResult
import com.rhodes.privatechat.shared.backup.BackupZip
import kotlinx.serialization.json.Json

/**
 * 全类目完整 payload 读取器（T31；规格卡契约 C1）。
 *
 * 校验单一来源：先走 T10 [BackupFileReader.read]（结构/哈希/分页/引用/媒体全项校验，`BackupFileReader.kt:32-244`），
 * 校验通过后再把同一份 ZIP 的分页文件二次解码为**完整字段**模型（[FullBackupPayloadWire]）。
 * 这是 lead 2026-11-23 批准的 D1 策略：不改 T10/T30 现有文件（写域仅新增），代价是恢复路径解码两次
 * （P2 性能风险，Phase 7 可合并为单解码）。
 *
 * 一致性护栏：二次解码后逐类目断言「完整模型记录数 == T10 占位模型记录数」且媒体列表逐项相等，
 * 任何漂移立即抛 [BackupFormatException]，避免两条解码路径静默分叉。
 */
class FullBackupReader(
    private val delegate: BackupFileReader = BackupFileReader(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(bytes: ByteArray): BackupValidationResult = delegate.validate(bytes)

    fun read(bytes: ByteArray): FullBackupArchiveWire {
        // 1) T10 全校验（失败即抛出/返回 Invalid；本函数抛出）。
        val archive: BackupArchiveWire = delegate.read(bytes)
        // 2) 二次解码完整字段。
        val payload = when (archive.manifest.formatVersion) {
            BACKUP_LEGACY_FORMAT_VERSION -> readLegacyPayload(bytes)
            else -> readPagedPayload(bytes, archive)
        }
        // 3) 双解码一致性护栏。
        verifyNoDrift(payload, archive.payload)
        return FullBackupArchiveWire(archive.manifest, payload, archive.issues)
    }

    private fun readPagedPayload(bytes: ByteArray, archive: BackupArchiveWire): FullBackupPayloadWire {
        val entries = BackupZip.read(
            bytes,
            maxEntries = 1_000,
            maxEntryBytes = 100L * 1024 * 1024,
            maxTotalBytes = 500L * 1024 * 1024,
        )
        val pageBytes = linkedMapOf<String, ByteArray>()
        for (entry in entries) {
            val path = entry.name.replace('\\', '/')
            if (!entry.isDirectory && path.startsWith("$BACKUP_PAGES_ROOT/")) pageBytes[path] = entry.data
        }
        if (pageBytes.size != archive.manifest.pages.size) {
            throw BackupFormatException("分页清单与 ZIP 内容不一致")
        }
        val specs = archive.manifest.pages.sortedWith(
            compareBy({ it.category.name }, { it.pageIndex }),
        )
        val pages = specs.map { spec ->
            val bytesOfPage = pageBytes[spec.path] ?: throw BackupFormatException("缺少分页文件：${spec.path}")
            if (bytesOfPage.size > BACKUP_MAX_PAGE_BYTES) throw BackupFormatException("分页数据超过限制：${spec.path}")
            val page = runCatching { json.decodeFromString<FullBackupPayloadPageWire>(bytesOfPage.decodeToString()) }
                .getOrElse { throw BackupFormatException("分页数据无法解析：${spec.path}") }
            if (page.category != spec.category || page.pageIndex != spec.pageIndex) {
                throw BackupFormatException("分页标识不一致：${spec.path}")
            }
            page
        }
        return merge(pages)
    }

    private fun readLegacyPayload(bytes: ByteArray): FullBackupPayloadWire {
        val entries = BackupZip.read(
            bytes,
            maxEntries = 1_000,
            maxEntryBytes = 100L * 1024 * 1024,
            maxTotalBytes = 500L * 1024 * 1024,
        )
        val entry = entries.firstOrNull { it.name.replace('\\', '/') == BACKUP_PAYLOAD_PATH }
            ?: throw BackupFormatException("缺少 data/backup.json")
        return runCatching { json.decodeFromString<FullBackupPayloadWire>(entry.data.decodeToString()) }
            .getOrElse { throw BackupFormatException("data/backup.json 无法解析") }
    }

    /** 与 T10 `BackupPagePlanner.merge` 同语义的分页合并（oracle：`BackupPagePlanner.kt:102-122`）。 */
    private fun merge(pages: List<FullBackupPayloadPageWire>): FullBackupPayloadWire {
        fun <T> flat(get: (FullBackupPayloadWire) -> List<T>?): List<T>? =
            pages.flatMap { get(it.payload).orEmpty() }.takeIf { it.isNotEmpty() }

        val content = FullBackupContentWire(
            version = 5,
            type = "full_backup",
            timestamp = pages.lastOrNull()?.payload?.content?.timestamp ?: 0L,
            operators = flat { it.content.operators },
            relationships = flat { it.content.relationships },
            sessions = flat { it.content.sessions },
            messages = flat { it.content.messages },
            memories = flat { it.content.memories },
            anchors = flat { it.content.anchors },
            moments = flat { it.content.moments },
            momentLikes = flat { it.content.momentLikes },
            momentComments = flat { it.content.momentComments },
            diaries = flat { it.content.diaries },
            memoryItems = flat { it.content.memoryItems },
            knowledgeBases = flat { it.content.knowledgeBases },
            knowledgeBaseChunks = flat { it.content.knowledgeBaseChunks },
            operatorKnowledgeBaseAssignments = flat { it.content.operatorKnowledgeBaseAssignments },
            settings = pages.lastOrNull { it.payload.content.settings != null }?.payload?.content?.settings,
        )
        return FullBackupPayloadWire(
            content = content,
            displayEvents = pages.flatMap { it.payload.displayEvents },
            chatArchives = pages.flatMap { it.payload.chatArchives },
            chatHistorySegments = pages.flatMap { it.payload.chatHistorySegments },
            giftRecords = pages.flatMap { it.payload.giftRecords },
            dispatchRecords = pages.flatMap { it.payload.dispatchRecords },
            mahjongSave = pages.mapNotNull { it.payload.mahjongSave }.lastOrNull(),
            sharedExperiences = pages.flatMap { it.payload.sharedExperiences },
            sharedExperienceParticipants = pages.flatMap { it.payload.sharedExperienceParticipants },
            media = pages.flatMap { it.payload.media },
        )
    }

    private fun verifyNoDrift(full: FullBackupPayloadWire, base: BackupPayloadWire) {
        fun check(name: String, a: Int, b: Int) {
            if (a != b) throw BackupFormatException("完整模型与校验模型记录数不一致：$name（$a vs $b）")
        }
        check("operators", full.content.operators.orEmpty().size, base.content.operators.orEmpty().size)
        check("relationships", full.content.relationships.orEmpty().size, base.content.relationships.orEmpty().size)
        check("sessions", full.content.sessions.orEmpty().size, base.content.sessions.orEmpty().size)
        check("messages", full.content.messages.orEmpty().size, base.content.messages.orEmpty().size)
        check("memories", full.content.memories.orEmpty().size, base.content.memories.orEmpty().size)
        check("anchors", full.content.anchors.orEmpty().size, base.content.anchors.orEmpty().size)
        check("moments", full.content.moments.orEmpty().size, base.content.moments.orEmpty().size)
        check("momentsLikes", full.content.momentLikes.orEmpty().size, base.content.momentLikes.orEmpty().size)
        check("momentComments", full.content.momentComments.orEmpty().size, base.content.momentComments.orEmpty().size)
        check("diaries", full.content.diaries.orEmpty().size, base.content.diaries.orEmpty().size)
        check("memoryItems", full.content.memoryItems.orEmpty().size, base.content.memoryItems.orEmpty().size)
        check("knowledgeBases", full.content.knowledgeBases.orEmpty().size, base.content.knowledgeBases.orEmpty().size)
        check("knowledgeBaseChunks", full.content.knowledgeBaseChunks.orEmpty().size, base.content.knowledgeBaseChunks.orEmpty().size)
        check("assignments", full.content.operatorKnowledgeBaseAssignments.orEmpty().size, base.content.operatorKnowledgeBaseAssignments.orEmpty().size)
        check("displayEvents", full.displayEvents.size, base.displayEvents.size)
        check("archives", full.chatArchives.size, base.chatArchives.size)
        check("historySegments", full.chatHistorySegments.size, base.chatHistorySegments.size)
        check("gifts", full.giftRecords.size, base.giftRecords.size)
        check("dispatches", full.dispatchRecords.size, base.dispatchRecords.size)
        check("experiences", full.sharedExperiences.size, base.sharedExperiences.size)
        check("participants", full.sharedExperienceParticipants.size, base.sharedExperienceParticipants.size)
        if ((full.mahjongSave != null) != (base.mahjongSave != null)) {
            throw BackupFormatException("完整模型与校验模型麻将存档不一致")
        }
        if ((full.content.settings != null) != (base.content.settings != null)) {
            throw BackupFormatException("完整模型与校验模型设置不一致")
        }
        if (full.media.map { it.mediaId } != base.media.map { it.mediaId } ||
            full.media.map { it.archivePath } != base.media.map { it.archivePath }
        ) {
            throw BackupFormatException("完整模型与校验模型媒体清单不一致")
        }
    }
}
