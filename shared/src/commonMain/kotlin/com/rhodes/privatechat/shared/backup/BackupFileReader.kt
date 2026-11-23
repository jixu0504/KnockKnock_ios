package com.rhodes.privatechat.shared.backup

import kotlinx.serialization.json.Json

/**
 * Android BackupFileReader 的最小 iOS 移植（oracle：app/.../data/backup/BackupFileReader.kt）。
 * 行为逐条镜像：路径安全/重复检测、manifest 三元组、files 与 ZIP 严格一致、逐条目 SHA-256、
 * v2 分页清单校验 + recordCount 公式 + merge、载荷标记、引用完整性（5 类 ORPHAN 降级 issue）、
 * 媒体清单一致性。输入为内存字节（iOS 侧 document picker 直接给出 Data），ZIP 解析走纯 Kotlin BackupZip。
 */
class BackupFileReader(
    private val maxEntries: Int = 1_000,
    private val maxEntryBytes: Long = 100L * 1024 * 1024,
    private val maxTotalBytes: Long = 500L * 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(bytes: ByteArray): BackupValidationResult = runCatching { read(bytes) }.fold(
        onSuccess = { archive -> BackupValidationResult.Valid(archive.manifest, archive.issues) },
        onFailure = { BackupValidationResult.Invalid(it.message ?: "备份文件无效") },
    )

    fun read(bytes: ByteArray): BackupArchiveWire {
        var totalBytes = 0L
        val entries = linkedMapOf<String, EntryDigest>()
        var manifestBytes: ByteArray? = null
        var payloadBytes: ByteArray? = null
        val pageBytes = linkedMapOf<String, ByteArray>()
        val paths = mutableSetOf<String>()

        // ZIP 读取（oracle :32-55）：路径归一、安全校验、去重、目录跳过、限流 + 逐条目 CRC（BackupZip 内）。
        val zipEntries = runCatching {
            BackupZip.read(bytes, maxEntries, maxEntryBytes, maxTotalBytes)
        }.getOrElse { throw BackupFormatException(it.message ?: "备份文件无效") }
        for (entry in zipEntries) {
            val path = entry.name.replace('\\', '/')
            if (!isSafeArchivePath(path)) throw BackupFormatException("ZIP 路径不安全：$path")
            if (!paths.add(path)) throw BackupFormatException("ZIP 存在重复路径：$path")
            if (entry.isDirectory) continue
            totalBytes += entry.uncompressedSize
            if (totalBytes > maxTotalBytes) throw BackupFormatException("ZIP 解压总大小超过限制")
            val digest = EntryDigest(entry.uncompressedSize, BackupCrypto.sha256Hex(entry.data))
            entries[path] = digest
            if (path == BACKUP_MANIFEST_PATH) manifestBytes = entry.data
            if (path == BACKUP_PAYLOAD_PATH) payloadBytes = entry.data
            if (path.startsWith("$BACKUP_PAGES_ROOT/")) pageBytes[path] = entry.data
        }

        // manifest 解析与结构校验（oracle :57-69）
        val manifestContent = manifestBytes ?: throw BackupFormatException("缺少 manifest.json")
        val manifest = runCatching { json.decodeFromString<BackupManifestWire>(manifestContent.decodeToString()) }
            .getOrElse { throw BackupFormatException("manifest.json 无法解析") }
        if (manifest.format != BACKUP_FULL_FORMAT) throw BackupFormatException("不支持的备份类型：${manifest.format}")
        if (manifest.formatVersion !in setOf(BACKUP_LEGACY_FORMAT_VERSION, BACKUP_FORMAT_VERSION)) {
            throw BackupFormatException("不支持的备份格式版本：${manifest.formatVersion}")
        }
        if (manifest.scope != "full") throw BackupFormatException("这不是完整备份文件")
        if (manifest.files.map { it.path }.distinct().size != manifest.files.size) {
            throw BackupFormatException("manifest 存在重复文件条目")
        }
        if (manifest.files.map { it.path }.toSet() != entries.keys - BACKUP_MANIFEST_PATH) {
            throw BackupFormatException("manifest 文件清单与 ZIP 内容不一致")
        }
        manifest.files.forEach { file ->
            val actual = entries[file.path] ?: throw BackupFormatException("缺少文件：${file.path}")
            if (actual.size != file.size) throw BackupFormatException("文件大小不一致：${file.path}")
            if (actual.sha256 != file.sha256.lowercase()) throw BackupFormatException("文件校验失败：${file.path}")
        }

        // 载荷读取（oracle :76-89）：v1 单 payload / v2 分页
        val payload = when (manifest.formatVersion) {
            BACKUP_LEGACY_FORMAT_VERSION -> {
                val payloadContent = payloadBytes ?: throw BackupFormatException("缺少 data/backup.json")
                runCatching { json.decodeFromString<BackupPayloadWire>(payloadContent.decodeToString()) }
                    .getOrElse { throw BackupFormatException("data/backup.json 无法解析") }
            }
            BACKUP_FORMAT_VERSION -> readPagedPayload(manifest, payloadBytes, pageBytes)
            else -> error("version already validated")
        }
        if (manifest.formatVersion == BACKUP_FORMAT_VERSION) {
            entries.filterKeys { it.startsWith("$BACKUP_PAGES_ROOT/") }.forEach { (path, entry) ->
                if (entry.size > BACKUP_MAX_PAGE_BYTES) throw BackupFormatException("分页数据超过限制：$path")
            }
        }
        if (payload.content.type != "full_backup") throw BackupFormatException("备份内容不是完整备份")
        if (manifest.mediaIncluded != payload.media.isNotEmpty()) throw BackupFormatException("备份媒体清单与标记不一致")
        val issues = validateReferences(payload, strictDisplayEvents = manifest.formatVersion == BACKUP_FORMAT_VERSION)
        if (payload.media.map { it.archivePath }.distinct().size != payload.media.size ||
            payload.media.map { it.mediaId }.distinct().size != payload.media.size
        ) {
            throw BackupFormatException("媒体清单存在重复路径")
        }
        if (payload.media.map { it.archivePath }.toSet() != entries.keys.filter { it.startsWith("media/") }.toSet()) {
            throw BackupFormatException("媒体清单与 ZIP 内容不一致")
        }
        return BackupArchiveWire(manifest, payload, issues)
    }

    private fun readPagedPayload(
        manifest: BackupManifestWire,
        legacyPayload: ByteArray?,
        pages: Map<String, ByteArray>,
    ): BackupPayloadWire {
        // oracle :102-121
        if (manifest.layout != "paged_payload") throw BackupFormatException("v2 备份布局无效")
        if (legacyPayload != null) throw BackupFormatException("v2 备份不能包含单一 payload")
        if (manifest.pages.isEmpty()) throw BackupFormatException("v2 备份缺少分页清单")
        if (manifest.pages.map { it.path }.distinct().size != manifest.pages.size) throw BackupFormatException("分页清单存在重复路径")
        manifest.pages.groupBy { it.category }.forEach { (_, entries) ->
            if (entries.map { it.pageIndex }.distinct().size != entries.size) throw BackupFormatException("分页索引重复")
        }
        if (manifest.pages.map { it.path }.toSet() != pages.keys) throw BackupFormatException("分页清单与 ZIP 内容不一致")
        val decoded = manifest.pages.sortedWith(compareBy<BackupPageEntryWire> { it.category.name }.thenBy { it.pageIndex }).map { descriptor ->
            if (!descriptor.path.startsWith("$BACKUP_PAGES_ROOT/")) throw BackupFormatException("分页路径无效")
            val bytes = pages[descriptor.path] ?: throw BackupFormatException("缺少分页文件")
            val page = runCatching { json.decodeFromString<BackupPayloadPageWire>(bytes.decodeToString()) }
                .getOrElse { throw BackupFormatException("分页数据无法解析：${descriptor.path}") }
            if (page.category != descriptor.category || page.pageIndex != descriptor.pageIndex) {
                throw BackupFormatException("分页标识不一致：${descriptor.path}")
            }
            if (pageRecordCount(page.payload) != descriptor.recordCount) throw BackupFormatException("分页记录数不一致：${descriptor.path}")
            page
        }
        return BackupPagePlanner.merge(decoded)
    }

    fun pageRecordCount(payload: BackupPayloadWire): Int = with(payload.content) {
        operators.orEmpty().size + relationships.orEmpty().size + sessions.orEmpty().size + messages.orEmpty().size +
            memories.orEmpty().size + anchors.orEmpty().size + memoryItems.orEmpty().size + moments.orEmpty().size +
            momentLikes.orEmpty().size + momentComments.orEmpty().size + diaries.orEmpty().size +
            knowledgeBases.orEmpty().size + knowledgeBaseChunks.orEmpty().size +
            operatorKnowledgeBaseAssignments.orEmpty().size + payload.displayEvents.size + payload.chatArchives.size +
            payload.chatHistorySegments.size + payload.giftRecords.size + payload.dispatchRecords.size +
            payload.sharedExperiences.size + payload.sharedExperienceParticipants.size +
            if (payload.mahjongSave != null) 1 else 0 + if (settings != null) 1 else 0
    }

    private fun validateReferences(payload: BackupPayloadWire, strictDisplayEvents: Boolean = false): List<BackupIssue> {
        // oracle :132-207（消息/会话/角色/知识库/关系/群成员/展示事件/存档/礼物/记忆锚点/动态引用，
        // 5 类孤儿降级为 skippable BackupIssue）
        val issues = mutableListOf<BackupIssue>()
        val operatorIds = payload.content.operators.orEmpty().map { it.id }.toSet()
        val sessionIds = payload.content.sessions.orEmpty().map { it.id }.toSet()
        val messageIds = payload.content.messages.orEmpty().map { it.id }.toSet()
        val momentIds = payload.content.moments.orEmpty().map { it.id }.toSet()
        val commentIds = payload.content.momentComments.orEmpty().map { it.id }.toSet()
        val knowledgeBases = payload.content.knowledgeBases.orEmpty()
        val knowledgeBaseIds = knowledgeBases.map { it.id }.toSet()
        val chunks = payload.content.knowledgeBaseChunks.orEmpty()
        val assignments = payload.content.operatorKnowledgeBaseAssignments.orEmpty()
        if (operatorIds.size != payload.content.operators.orEmpty().size) throw BackupFormatException("备份包含重复角色")
        if (sessionIds.size != payload.content.sessions.orEmpty().size) throw BackupFormatException("备份包含重复会话")
        if (messageIds.size != payload.content.messages.orEmpty().size) throw BackupFormatException("备份包含重复聊天消息")
        if (knowledgeBaseIds.size != knowledgeBases.size) throw BackupFormatException("备份包含重复知识库")
        if (chunks.map { it.id }.distinct().size != chunks.size) throw BackupFormatException("备份包含重复知识库分段")
        if (chunks.map { it.knowledgeBaseId to it.ordinal }.distinct().size != chunks.size) throw BackupFormatException("备份包含重复知识库分段序号")
        if (assignments.map { it.operatorId to it.knowledgeBaseId }.distinct().size != assignments.size) throw BackupFormatException("备份包含重复角色知识库关联")
        chunks.forEach { if (it.knowledgeBaseId !in knowledgeBaseIds) throw BackupFormatException("知识库分段引用不存在的知识库") }
        assignments.forEach {
            if (it.operatorId !in operatorIds || it.knowledgeBaseId !in knowledgeBaseIds) throw BackupFormatException("角色知识库关联引用不完整")
        }
        payload.content.relationships.orEmpty().forEach { relationship ->
            if (relationship.operatorId !in operatorIds || relationship.relatedOperatorId !in operatorIds) {
                throw BackupFormatException("角色关系引用了不存在的角色")
            }
        }
        payload.content.sessions.orEmpty().forEach { session ->
            if (session.operatorId.startsWith("group_")) {
                session.members.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .firstOrNull { it !in operatorIds }
                    ?.let { missing -> throw BackupFormatException("群聊成员引用了不存在的角色：$missing") }
            } else if (session.operatorId !in operatorIds) {
                throw BackupFormatException("私聊会话引用了不存在的角色：${session.operatorId}")
            }
        }
        payload.content.messages.orEmpty().forEach { message ->
            if (message.sessionId !in sessionIds) throw BackupFormatException("聊天消息引用了不存在的会话")
        }
        val messageSessionById = payload.content.messages.orEmpty().associate { it.id to it.sessionId }
        if (strictDisplayEvents) {
            if (payload.displayEvents.map { it.messageId to it.segmentIndex }.distinct().size != payload.displayEvents.size) {
                throw BackupFormatException("备份包含重复消息展示事件")
            }
            payload.displayEvents.forEach { event ->
                if (event.sessionId !in sessionIds || messageSessionById[event.messageId] != event.sessionId) {
                    throw BackupFormatException("消息展示事件引用不完整")
                }
            }
        }
        payload.chatArchives.forEach { archive ->
            if (archive.sessionId !in sessionIds || archive.operatorId !in operatorIds) {
                throw BackupFormatException("聊天存档引用不完整")
            }
        }
        payload.chatHistorySegments.forEach { segment ->
            if (segment.sessionId !in sessionIds) throw BackupFormatException("聊天历史片段引用了不存在的会话")
        }
        payload.giftRecords.forEach { gift ->
            if (gift.operatorId !in operatorIds) throw BackupFormatException("礼物记录引用了不存在的角色")
        }
        val orphanMemories = payload.content.memories.orEmpty().count { memory ->
            !isGlobalDailySummary(memory) && (memory.operatorId !in operatorIds || memory.sessionId !in sessionIds)
        }
        if (orphanMemories > 0) issues += BackupIssue("ORPHAN_MEMORY", "孤立记忆", "部分记忆引用了不存在的角色或会话；跳过后不影响角色、聊天和人设恢复。", orphanMemories)
        val orphanAnchors = payload.content.anchors.orEmpty().count { it.operatorId !in operatorIds || it.sessionId !in sessionIds }
        if (orphanAnchors > 0) issues += BackupIssue("ORPHAN_ANCHOR", "孤立记忆锚点", "部分记忆锚点引用了不存在的角色或会话；跳过后不影响其他数据恢复。", orphanAnchors)
        val orphanComments = payload.content.momentComments.orEmpty().count {
            it.momentId !in momentIds || (it.parentCommentId != 0L && it.parentCommentId !in commentIds)
        }
        if (orphanComments > 0) issues += BackupIssue("ORPHAN_MOMENT_COMMENT", "孤立动态评论", "部分评论找不到所属动态或父评论；跳过后不影响其他动态恢复。", orphanComments)
        val orphanLikes = payload.content.momentLikes.orEmpty().count { it.momentId !in momentIds }
        if (orphanLikes > 0) issues += BackupIssue("ORPHAN_MOMENT_LIKE", "孤立动态点赞", "部分点赞找不到所属动态；跳过后不影响其他动态恢复。", orphanLikes)
        val experienceIds = payload.sharedExperiences.map { it.id }.toSet()
        val orphanParticipants = payload.sharedExperienceParticipants.count { it.experienceId !in experienceIds || it.operatorId !in operatorIds }
        if (orphanParticipants > 0) issues += BackupIssue("ORPHAN_SHARED_EXPERIENCE_PARTICIPANT", "孤立共同经历参与者", "部分共同经历参与者找不到对应角色或经历；跳过后不影响其他共同经历恢复。", orphanParticipants)
        return issues
    }

    private fun isGlobalDailySummary(memory: BackupMemoryWire): Boolean =
        memory.type == "DAILY" && memory.operatorId == "daily" && memory.sessionId.startsWith("daily_")

    private data class EntryDigest(val size: Long, val sha256: String)

    companion object {
        fun isSafeArchivePath(path: String): Boolean {
            val normalized = path.replace('\\', '/')
            return normalized.isNotBlank() &&
                !normalized.startsWith('/') &&
                !normalized.contains(":/") &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
        }
    }
}
