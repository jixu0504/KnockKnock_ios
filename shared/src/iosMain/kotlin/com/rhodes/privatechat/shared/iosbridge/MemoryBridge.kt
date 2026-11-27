package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.memory.MemoryPrivacyPolicy
import com.rhodes.privatechat.shared.domain.memory.beijingTimestamp
import com.rhodes.privatechat.shared.domain.memory.currentTimeMillis
import com.rhodes.privatechat.shared.model.MemoryAnchor
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// T47 记忆管理 DTO（窄门面纪律：只用简单类型，普通 class，不导出 Flow/sealed）
// ---------------------------------------------------------------------------

/**
 * 记忆列表行（`memory_items` V2 行 + 旧版 `memories` 行的统一视图）。
 *
 * - [sourceTable]：`v2` = shared `memory_items` 表；`legacy` = 旧版 `memories` 表
 *   （T29 MemoryBridge.list 读的长期印象表，只读为主，仅 LONG_TERM 支持删除）。
 * - [level]：`L1` / `L2` / `L3`。iOS 域命名别名见规格卡 `memory-v2-spec.md:58`：
 *   L1=SHORT_TERM（短期）、L2=DAILY（日常）、L3=LONG_TERM（长期）。
 * - [kind]：层级别名（SHORT_TERM / DAILY / LONG_TERM），与 [level] 一一映射；
 *   筛选入参两个字段都接受（`kind` 是 `level` 的别名，写反了也能匹配）。
 * - [memoryType]：V2 的 11 类记忆类型（preference_expression 等）；旧版行为 `legacy_*`。
 * - [importance]：0..100；旧版 `memories` 表无重要度列，固定 0（UI 显示「未标注」）。
 */
class MemoryRecordDto(
    val id: Long,
    val sourceTable: String,
    val level: String,
    val kind: String,
    val memoryType: String,
    val sourceKind: String,
    val ownerType: String,
    val ownerId: String,
    val ownerName: String,
    val content: String,
    val importance: Int,
    val privacy: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val sessionId: String,
    val sourceRefId: String,
    val sourceActor: String,
)

/** 列表查询条件（所有字段为空/0 = 不过滤；时间范围 [fromMillis, toMillis] 为闭区间）。 */
class MemoryQueryDto(
    val level: String,
    val kind: String,
    val sourceKind: String,
    val keyword: String,
    val fromMillis: Long,
    val toMillis: Long,
    val status: String,
    val ownerType: String,
    val ownerId: String,
    /** newest（默认）/ oldest / importance */
    val sort: String,
)

/** 分页结果。[hasMore] = 还有下一页。 */
class MemoryPageDto(
    val items: List<MemoryRecordDto>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

/** 详情锚点（[type] = 归属/来源/层级/类型/会话/记录时间/来源引用 或旧版六类锚点类型名）。 */
class MemoryAnchorDto(
    val type: String,
    val content: String,
    val importance: String,
    val isPrivate: Boolean,
    val source: String,
    val knownFrom: String,
    val createdAt: Long,
)

/**
 * 记忆详情。
 *
 * - [sourceAnchors]：由记录字段派生的来源锚点（恒非空，UI「锚点列表」）。
 * - [policyAnchors]：旧版 `memory_anchors` 六类锚点（PLAN/PREFERENCE/TABOO/EVENT/EMOTION/RELATION，
 *   按 ownerId=operatorId 查询；非 operator 归属或表空时为空）。
 * - [privacyLines]：`MemoryPrivacyPolicy` 的来源→可见性语义（UI 隐私说明区）。
 */
class MemoryDetailDto(
    val record: MemoryRecordDto,
    val sourceAnchors: List<MemoryAnchorDto>,
    val policyAnchors: List<MemoryAnchorDto>,
    val privacyLines: List<String>,
    val canEditContent: Boolean,
    val canEditImportance: Boolean,
    val canDelete: Boolean,
)

/** 写操作结果（create / edit / delete / deleteBatch 共用）。 */
class MemoryMutationResultDto(
    val ok: Boolean,
    val id: Long,
    val message: String,
)

/** 隐私设置读取结果（键名与 shared `SettingsRepository` 完全一致）。 */
class MemoryPrivacyDto(
    val memoryV2Enabled: Boolean,
    val distinguishPrivateMemory: Boolean,
    val sourceAwareMemoryEnabled: Boolean,
    val globalPublicMemoryEnabled: Boolean,
    val sensitiveWords: List<String>,
    val policyLines: List<String>,
)

/**
 * 记忆桥：T29 只读长期印象列表 + T47 记忆管理面（分页/筛选/详情/编辑/删除/隐私/锚点）。
 *
 * 读路径复用 shared `MemoryV2Repository` / `MemoryRepository` / `AnchorRepository`；
 * 写路径（edit/delete）复用仓库既有事务语义（删除传播、派生失效、向量清理见
 * `MemoryV2Repository.deleteMemoryItem/updateMemoryItemContent`）。
 *
 * 线程：读写在 `workScope`（Dispatchers.Default/DB 派发器），回调一律回 `mainScope`（Main）。
 * 隐私设置读写同步执行（NSUserDefaults 线程安全）。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "MemoryBridge")
class MemoryBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    // ------------------------------------------------------------------
    // T29 旧面（保留兼容：AppEnvironment / --selftest 仍消费）
    // ------------------------------------------------------------------

    fun list(onResult: (List<MemoryDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val items = chatRepo.memories.getAllLongTermImpressions().map { memory ->
                    MemoryDto(
                        id = memory.id,
                        kind = memory.type.name,
                        content = memory.content,
                        createdAt = memory.createdAt,
                    )
                }
                mainScope.launch { onResult(items, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取记忆失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // T47 列表（分页 + 筛选）
    // ------------------------------------------------------------------

    fun listPage(offset: Int, limit: Int, query: MemoryQueryDto, onResult: (MemoryPageDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val all = loadAllRecords()
                val filtered = all.filter { matches(it, query) }
                val sorted = sortRecords(filtered, query.sort)
                val safeOffset = offset.coerceAtLeast(0)
                val safeLimit = limit.coerceIn(1, 200)
                val page = sorted.drop(safeOffset).take(safeLimit)
                val hasMore = safeOffset + page.size < sorted.size
                mainScope.launch {
                    onResult(MemoryPageDto(page, sorted.size, safeOffset, safeLimit, hasMore), null)
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取记忆列表失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // T47 详情（含锚点）
    // ------------------------------------------------------------------

    fun detail(id: Long, sourceTable: String, onResult: (MemoryDetailDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val table = normalizeTable(sourceTable)
                val record = loadAllRecords().firstOrNull { it.id == id && it.sourceTable == table }
                    ?: run {
                        mainScope.launch { onResult(null, "记忆不存在（id=$id, table=$table）") }
                        return@launch
                    }
                val policyAnchors = loadPolicyAnchors(record)
                val detail = MemoryDetailDto(
                    record = record,
                    sourceAnchors = sourceAnchorsOf(record),
                    policyAnchors = policyAnchors,
                    privacyLines = privacyPolicyLines(),
                    canEditContent = record.sourceTable == "v2",
                    canEditImportance = record.sourceTable == "v2",
                    canDelete = record.sourceTable == "v2" || record.kind == "LONG_TERM",
                )
                mainScope.launch { onResult(detail, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取记忆详情失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // T47 新建手动记忆（MANUAL_MEMORY，隐私经 MemoryPrivacyPolicy）
    // ------------------------------------------------------------------

    fun create(
        ownerType: String,
        ownerId: String,
        content: String,
        level: String,
        importance: Int,
        explicitPrivate: Boolean,
        memoryType: String,
        onResult: (MemoryMutationResultDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val clean = content.trim()
                if (clean.isBlank()) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, 0, "内容不能为空"), null) }
                    return@launch
                }
                if (clean.length > 500) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, 0, "内容过长（≤500 字）"), null) }
                    return@launch
                }
                val owner = ownerType.ifBlank { "operator" }
                val ownerKey = ownerId.ifBlank { mainOperatorId() }
                val memoryLevel = normalizeLevel(level).ifBlank { "L2" }
                val now = currentTimeMillis()
                val visibility = MemoryPrivacyPolicy.forSource(
                    sourceKind = MemorySourceKind.MANUAL_MEMORY,
                    ownerType = owner,
                    ownerId = ownerKey,
                    content = clean,
                    explicitPrivate = explicitPrivate,
                )
                val sessionId = if (owner == "operator") {
                    runCatching { chatRepo.getSessionByOperator(ownerKey)?.id ?: "" }.getOrDefault("")
                } else {
                    ""
                }
                val item = MemoryItem(
                    ownerType = visibility.ownerType,
                    ownerId = visibility.ownerId,
                    memoryLevel = com.rhodes.privatechat.shared.model.MemoryLevel.valueOf(memoryLevel),
                    memoryType = memoryType.ifBlank { "preference_expression" },
                    sourceKind = MemorySourceKind.MANUAL_MEMORY,
                    sourceRefId = "manual:$now",
                    sessionId = sessionId,
                    content = clean,
                    importance = importance.coerceIn(0, 100),
                    privacy = visibility.privacy,
                    sourceActor = "manual",
                    sourceTarget = ownerKey,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = Long.MAX_VALUE,
                    status = "active",
                )
                val id = chatRepo.insertMemoryItem(item)
                mainScope.launch {
                    onResult(
                        MemoryMutationResultDto(true, id, "已新建手动记忆（privacy=${visibility.privacy}）"),
                        null,
                    )
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "新建记忆失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 手动调整层级（短期 / 日常 / 长期）
    // ------------------------------------------------------------------

    /**
     * 把一条记忆改成指定层级。用户判断「这条其实是长期认知」时可直接提升，
     * 不必等自动整合攒够证据。升长期会把过期时间置为最大值。
     */
    fun promote(
        id: Long,
        sourceTable: String,
        targetLevel: String,
        onResult: (MemoryMutationResultDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val level = normalizeLevel(targetLevel)
                if (level.isBlank()) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, 0, "层级无效"), null) }
                    return@launch
                }
                if (id <= 0L) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, 0, "记忆 id 无效"), null) }
                    return@launch
                }
                // 派生表（legacy 印象等）不允许直接改层级，避免破坏证据链；"v2"/空 均指 memory_items。
                val table = sourceTable.trim().lowercase()
                if (table.isNotEmpty() && table != "v2" && table != "memory_items") {
                    mainScope.launch {
                        onResult(MemoryMutationResultDto(false, 0, "只能调整记忆条目的层级（sourceTable=$sourceTable）"), null)
                    }
                    return@launch
                }
                val target = com.rhodes.privatechat.shared.model.MemoryLevel.valueOf(level)
                chatRepo.updateMemoryItemLevel(id, target, currentTimeMillis())
                mainScope.launch {
                    onResult(MemoryMutationResultDto(true, id, "已调整为${levelLabelOf(level)}记忆"), null)
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "调整层级失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // T47 编辑（内容 / 重要度）
    // ------------------------------------------------------------------

    fun edit(
        id: Long,
        sourceTable: String,
        content: String,
        importance: Int,
        onResult: (MemoryMutationResultDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val table = normalizeTable(sourceTable)
                if (table != "v2") {
                    mainScope.launch {
                        onResult(MemoryMutationResultDto(false, id, "旧版长期印象只支持删除，不支持编辑"), null)
                    }
                    return@launch
                }
                val before = chatRepo.memoryV2.getAllMemoryItems().firstOrNull { it.id == id }
                    ?: run {
                        mainScope.launch { onResult(null, "记忆不存在（id=$id）") }
                        return@launch
                    }
                val clean = content.trim()
                if (clean.isBlank()) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, id, "内容不能为空"), null) }
                    return@launch
                }
                if (clean.length > 500) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, id, "内容过长（≤500 字）"), null) }
                    return@launch
                }
                val now = currentTimeMillis()
                var changed = false
                if (clean != before.content) {
                    // 仓库既有语义：内容变更 → 派生认知失效 + 旧向量删除，等待重新索引。
                    chatRepo.updateMemoryItemContent(id, clean, now)
                    changed = true
                }
                val targetImportance = importance.coerceIn(0, 100)
                if (targetImportance != before.importance) {
                    chatRepo.updateMemoryItemImportance(id, targetImportance, now)
                    changed = true
                }
                mainScope.launch {
                    onResult(
                        MemoryMutationResultDto(
                            true,
                            id,
                            if (changed) "已保存（重要度 $targetImportance）" else "内容未变化",
                        ),
                        null,
                    )
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "保存记忆失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // T47 删除（单条 / 批量）
    // ------------------------------------------------------------------

    fun delete(id: Long, sourceTable: String, onResult: (MemoryMutationResultDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val error = deleteInternal(id, sourceTable)
                if (error != null) {
                    mainScope.launch { onResult(MemoryMutationResultDto(false, id, error), null) }
                } else {
                    mainScope.launch { onResult(MemoryMutationResultDto(true, id, "已删除记忆 $id"), null) }
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "删除记忆失败") }
            }
        }
    }

    fun deleteBatch(
        ids: List<Long>,
        sourceTables: List<String>,
        onResult: (MemoryMutationResultDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                var deleted = 0
                val errors = mutableListOf<String>()
                ids.forEachIndexed { index, id ->
                    val table = sourceTables.getOrNull(index) ?: "v2"
                    val error = deleteInternal(id, table)
                    if (error == null) deleted++ else errors.add("$id: $error")
                }
                mainScope.launch {
                    onResult(
                        MemoryMutationResultDto(
                            ok = errors.isEmpty(),
                            id = 0,
                            message = if (errors.isEmpty()) "已删除 $deleted 条记忆" else "删除 $deleted 条，失败 ${errors.size} 条：${errors.take(3).joinToString("；")}",
                        ),
                        null,
                    )
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "批量删除失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // T47 隐私（settings 键与 MemoryPrivacyPolicy 语义）
    // ------------------------------------------------------------------

    /** 同步读取隐私设置（NSUserDefaults 线程安全）。键名字面量见 shared `SettingsRepository`。 */
    fun privacySettings(): MemoryPrivacyDto = MemoryPrivacyDto(
        memoryV2Enabled = settingsRepo.memoryV2Enabled,
        distinguishPrivateMemory = settingsRepo.distinguishPrivateMemory,
        sourceAwareMemoryEnabled = settingsRepo.sourceAwareMemoryEnabled,
        globalPublicMemoryEnabled = settingsRepo.globalPublicMemoryEnabled,
        sensitiveWords = MemoryPrivacyPolicy.sensitiveWords,
        policyLines = privacyPolicyLines(),
    )

    /**
     * 同步写入隐私开关，[key] 取 shared SettingsRepository 的键名（oracle 语义）：
     * `memory_v2_enabled` / `distinguish_private_memory` / `source_aware_memory_enabled` /
     * `global_public_memory_enabled`。返回 false = 未知键。
     */
    fun setPrivacyFlag(key: String, value: Boolean): Boolean = when (key) {
        "memory_v2_enabled" -> { settingsRepo.memoryV2Enabled = value; true }
        "distinguish_private_memory" -> { settingsRepo.distinguishPrivateMemory = value; true }
        "source_aware_memory_enabled" -> { settingsRepo.sourceAwareMemoryEnabled = value; true }
        "global_public_memory_enabled" -> { settingsRepo.globalPublicMemoryEnabled = value; true }
        else -> false
    }

    /** 敏感词判定（MemoryPrivacyPolicy 18 条），供详情页实时提示。 */
    fun isSensitive(text: String): Boolean = MemoryPrivacyPolicy.isSensitive(text)

    // ------------------------------------------------------------------
    // 内部：读取 / 映射
    // ------------------------------------------------------------------

    private suspend fun loadAllRecords(): List<MemoryRecordDto> {
        val operators = runCatching { chatRepo.operators.getAllOperatorsSync() }.getOrDefault(emptyList())
        val operatorNames = operators.associate { it.id to it.name }
        val sessions = runCatching { chatRepo.sessions.getAllSessionsSync() }.getOrDefault(emptyList())
        val groupNames = sessions.associate { it.id to it.operatorName }

        val v2 = runCatching { chatRepo.memoryV2.getAllMemoryItems() }.getOrDefault(emptyList())
            .map { item -> toRecord(item, operatorNames, groupNames) }
        val legacy = runCatching { chatRepo.memories.getAllMemoriesForBackup() }.getOrDefault(emptyList())
            .map { memory ->
                val level = when (memory.type.name) {
                    "SHORT_TERM" -> "L1"
                    "DAILY" -> "L2"
                    else -> "L3"
                }
                MemoryRecordDto(
                    id = memory.id,
                    sourceTable = "legacy",
                    level = level,
                    kind = memory.type.name,
                    memoryType = "legacy_" + memory.type.name.lowercase(),
                    sourceKind = "PRIVATE_CHAT",
                    ownerType = "operator",
                    ownerId = memory.operatorId,
                    ownerName = operatorNames[memory.operatorId] ?: memory.operatorId,
                    content = memory.content,
                    importance = 0,
                    privacy = "shared",
                    status = "active",
                    createdAt = memory.createdAt,
                    updatedAt = memory.createdAt,
                    expiresAt = memory.expiresAt,
                    sessionId = memory.sessionId,
                    sourceRefId = "",
                    sourceActor = "",
                )
            }
        return v2 + legacy
    }

    private fun toRecord(
        item: MemoryItem,
        operatorNames: Map<String, String>,
        groupNames: Map<String, String>,
    ): MemoryRecordDto {
        val level = item.memoryLevel.name
        val kind = when (level) {
            "L1" -> "SHORT_TERM"
            "L2" -> "DAILY"
            else -> "LONG_TERM"
        }
        val ownerName = when (item.ownerType) {
            "operator" -> operatorNames[item.ownerId] ?: item.ownerId
            "group" -> groupNames[item.ownerId] ?: item.ownerId
            "global" -> "公开资料"
            else -> item.ownerId
        }
        return MemoryRecordDto(
            id = item.id,
            sourceTable = "v2",
            level = level,
            kind = kind,
            memoryType = item.memoryType,
            sourceKind = item.sourceKind.name,
            ownerType = item.ownerType,
            ownerId = item.ownerId,
            ownerName = ownerName,
            content = item.content,
            importance = item.importance,
            privacy = item.privacy ?: "",
            status = item.status,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            expiresAt = item.expiresAt,
            sessionId = item.sessionId,
            sourceRefId = item.sourceRefId,
            sourceActor = item.sourceActor,
        )
    }

    private fun matches(record: MemoryRecordDto, query: MemoryQueryDto): Boolean {
        val wantedLevel = normalizedLevelOrEmpty(query.level).ifBlank { normalizedLevelOrEmpty(query.kind) }
        if (wantedLevel.isNotBlank() && record.level != wantedLevel) return false

        val wantedSource = query.sourceKind.trim().uppercase()
        if (wantedSource.isNotBlank() && record.sourceKind.uppercase() != wantedSource) return false

        val wantedStatus = query.status.trim().lowercase()
        if (wantedStatus.isNotBlank() && wantedStatus != "all" && record.status.lowercase() != wantedStatus) return false

        if (query.ownerType.isNotBlank() && !record.ownerType.equals(query.ownerType.trim(), ignoreCase = true)) return false
        if (query.ownerId.isNotBlank() && record.ownerId != query.ownerId.trim()) return false

        if (query.fromMillis > 0 && record.createdAt < query.fromMillis) return false
        if (query.toMillis > 0 && record.createdAt > query.toMillis) return false

        val keyword = query.keyword.trim()
        if (keyword.isNotBlank()) {
            val haystack = "${record.content}\n${record.sourceActor}\n${record.ownerName}"
            if (!haystack.contains(keyword, ignoreCase = true)) return false
        }
        return true
    }

    private fun sortRecords(records: List<MemoryRecordDto>, sort: String): List<MemoryRecordDto> = when (sort.trim().lowercase()) {
        "oldest" -> records.sortedBy { it.createdAt }
        "importance" -> records.sortedWith(
            compareByDescending<MemoryRecordDto> { it.importance }.thenByDescending { it.createdAt }
        )
        else -> records.sortedByDescending { it.createdAt }
    }

    private suspend fun loadPolicyAnchors(record: MemoryRecordDto): List<MemoryAnchorDto> {
        if (record.ownerType != "operator" || record.ownerId.isBlank()) return emptyList()
        val anchors = runCatching { chatRepo.anchors.getAnchors(record.ownerId) }.getOrDefault(emptyList())
        return anchors
            .filter { anchor -> record.sessionId.isBlank() || anchor.sessionId.isBlank() || anchor.sessionId == record.sessionId }
            .map { anchor -> anchor.toDto() }
    }

    private fun MemoryAnchor.toDto(): MemoryAnchorDto = MemoryAnchorDto(
        type = type.name,
        content = content,
        importance = importance,
        isPrivate = isPrivate,
        source = source,
        knownFrom = knownFrom,
        createdAt = createdAt,
    )

    /** 由记录字段派生的来源锚点（恒非空；UI 的「锚点列表」主内容）。 */
    private fun sourceAnchorsOf(record: MemoryRecordDto): List<MemoryAnchorDto> {
        val anchors = mutableListOf<MemoryAnchorDto>()
        fun add(type: String, value: String) {
            if (value.isNotBlank()) anchors.add(
                MemoryAnchorDto(type = type, content = value, importance = "", isPrivate = record.privacy == "private", source = record.sourceKind, knownFrom = "", createdAt = record.createdAt)
            )
        }
        add("归属", ownerDisplay(record))
        add("来源", sourceKindLabel(record.sourceKind))
        add("层级", levelLabel(record.level))
        add("类型", record.memoryType)
        add("等级别名", record.kind)
        add("记录时间", beijingTimestamp(record.createdAt))
        if (record.updatedAt > 0 && record.updatedAt != record.createdAt) add("更新时间", beijingTimestamp(record.updatedAt))
        if (record.expiresAt in 1 until Long.MAX_VALUE) add("过期时间", beijingTimestamp(record.expiresAt))
        add("会话", record.sessionId)
        add("来源引用", record.sourceRefId)
        return anchors
    }

    private fun ownerDisplay(record: MemoryRecordDto): String = when (record.ownerType) {
        "operator" -> "角色：${record.ownerName}"
        "group" -> "群：${record.ownerName}"
        "global" -> "公开资料"
        else -> "${record.ownerType}:${record.ownerName}"
    }

    private fun ownerTypeLabel(value: String): String = when (value) {
        "operator" -> "角色"
        "group" -> "群"
        "global" -> "公开"
        else -> value
    }

    private fun levelLabel(level: String): String = when (level) {
        "L1" -> "L1 短期"
        "L2" -> "L2 日常"
        "L3" -> "L3 长期"
        else -> level
    }

    private fun sourceKindLabel(value: String): String = when (value.uppercase()) {
        "PRIVATE_CHAT" -> "私聊"
        "GROUP_CHAT" -> "群聊"
        "MOMENT" -> "动态"
        "MOMENT_COMMENT" -> "评论"
        "DIARY" -> "日记"
        "MANUAL_MEMORY" -> "手动添加"
        else -> value
    }

    private fun privacyPolicyLines(): List<String> = listOf(
        "私聊记忆：" + "命中敏感词或显式私密 → private，否则 shared（不写公开副本）",
        "群聊记忆：public（owner = group，不写公开副本）",
        "动态 / 评论：public，并写全局公开副本",
        "日记：private",
        "手动记忆：命中敏感词 → private，否则 shared",
        "敏感词（${MemoryPrivacyPolicy.sensitiveWords.size} 条）：" + MemoryPrivacyPolicy.sensitiveWords.joinToString("、"),
    )

    // ------------------------------------------------------------------
    // 内部：写操作 / 工具
    // ------------------------------------------------------------------

    private suspend fun deleteInternal(id: Long, sourceTable: String): String? {
        return when (normalizeTable(sourceTable)) {
            "v2" -> {
                val exists = chatRepo.memoryV2.getAllMemoryItems().any { it.id == id }
                if (!exists) return "记忆不存在（id=$id）"
                chatRepo.deleteMemoryItem(id)
                null
            }
            else -> {
                val legacy = chatRepo.memories.getAllMemoriesForBackup().firstOrNull { it.id == id }
                    ?: return "记忆不存在（legacy id=$id）"
                if (legacy.type.name != "LONG_TERM") {
                    return "旧版短期/日常记录不支持逐条删除（请在对应会话中清理）"
                }
                // 旧版保持「每个角色一条长期印象」语义（replaceLongTermImpression 同款删除路径）。
                chatRepo.memories.deleteLongTermByOperator(legacy.operatorId)
                null
            }
        }
    }

    private fun normalizeTable(value: String): String = if (value.trim().lowercase() == "legacy") "legacy" else "v2"

    private suspend fun mainOperatorId(): String = runCatching {
        chatRepo.operators.getAllOperatorsSync().firstOrNull()?.id ?: ""
    }.getOrDefault("")

    private fun levelLabelOf(level: String): String = when (level.uppercase()) {
        "L1" -> "短期"
        "L2" -> "日常"
        "L3" -> "长期"
        else -> level
    }

    private fun normalizeLevel(value: String): String = when (value.trim().uppercase()) {
        "L1", "SHORT_TERM", "SHORT", "SHORTTERM" -> "L1"
        "L2", "DAILY", "MID_TERM", "MID", "MIDTERM" -> "L2"
        "L3", "LONG_TERM", "LONG", "LONGTERM" -> "L3"
        else -> ""
    }

    private fun normalizedLevelOrEmpty(value: String): String = normalizeLevel(value)
}
