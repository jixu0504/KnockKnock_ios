package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.data.Impression
import com.rhodes.privatechat.shared.data.ImpressionEvidenceRepository
import com.rhodes.privatechat.shared.data.ImpressionRepository
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.Memory
import com.rhodes.privatechat.shared.domain.memory.ImpressionFilter
import com.rhodes.privatechat.shared.model.MemoryItem
import com.rhodes.privatechat.shared.model.MemoryLevel
import com.rhodes.privatechat.shared.model.MemorySourceKind
import com.rhodes.privatechat.shared.model.MemoryType
import com.rhodes.privatechat.shared.model.Operator
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import platform.Foundation.NSLog

/**
 * 「大家的印象」桥（T82：优先读 `impressions` 印象实体表，空表时回退 T54 的 memory_items 过滤视图）。
 *
 * 数据/口径：
 * - `impressions` 表有任意行（含 rejected/frozen）时，按角色读 `ImpressionRepository.listForCharacter`
 *   的 active + frozen 行；rejected 不展示也不回退（拒绝后不复活）。层级映射 long_term→L3、
 *   ongoing→L2、interaction→L1，保留 `ImpressionRepository` 的层深→最近确认排序；
 * - 表完全为空（老库尚未回填）时回退 T54 视图：`memory_items` 过滤（`ImpressionFilter`）+ 旧版
 *   `memories` LONG_TERM 只读兜底，保证升级前页面仍有内容；
 * - 人工裁决 reject/freeze/unfreeze 经 `ImpressionRepository.setStatus`，只改 status，不改文本/证据。
 *
 * 纪律（同 ios-bridge-spec.md）：只导出简单类型 DTO；回调一律 Main 队列；错误经第二参数回传。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "ImpressionsBridge")
class ImpressionsBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val impressionRepo: ImpressionRepository,
    private val evidenceRepo: ImpressionEvidenceRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    /**
     * 印象快照（分页：`page` 从 1 起，返回累计到该页的条目；`total` / `hasMore` 描述全表）。
     * 排序沿用 oracle：L3 优先 → updatedAt 降序 → importance 降序；UI 再按角色分组。
     * 失败返回 (null, errorMessage)。
     */
    fun list(page: Int, pageSize: Int, onResult: (ImpressionsSnapshotDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val snapshot = snapshotSync(page, pageSize)
                mainScope.launch { onResult(snapshot, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "印象读取失败") }
            }
        }
    }

    /**
     * 已拒绝印象（撤销拒绝管理入口）：权重无关，按最近操作倒序；正常列表不含这些行。
     */
    fun rejected(onResult: (List<ImpressionEntryDto>?, String?) -> Unit) {
        workScope.launch {
            try {
                val entries = rejectedEntriesSync()
                mainScope.launch { onResult(entries, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "已拒绝印象读取失败") }
            }
        }
    }

    /**
     * 印象证据展开：每行 `memoryId|weight|createdAt|正文摘要(≤80字)|sessionId`，
     * 权重降序、最多 [EVIDENCE_LIMIT] 条；无证据返回空串（Swift 侧据此显示空态）。
     */
    fun evidence(impressionId: Long, onResult: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                val rows = if (impressionId <= 0) {
                    emptyList()
                } else {
                    evidenceRepo.list(impressionId).take(EVIDENCE_LIMIT)
                }
                val refs = impressionRepo.memoryRefs(rows.map { it.memoryId })
                val text = rows.joinToString("\n") { row ->
                    val ref = refs[row.memoryId]
                    "${row.memoryId}|${row.weight}|${row.createdAt}|${evidenceSummary(ref?.content)}|${ref?.sessionId.orEmpty()}"
                }
                mainScope.launch { onResult(text, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "证据读取失败") }
            }
        }
    }

    /** 拒绝：status=rejected，页面不再展示该印象（不回退旧视图，避免“拒绝后复活”）。 */
    fun reject(id: Long, onResult: (String?) -> Unit) = setStatus(id, STATUS_REJECTED, onResult)

    /** 冻结：status=frozen，保留展示但阻止后续整合改写（仍可解冻）。 */
    fun freeze(id: Long, onResult: (String?) -> Unit) = setStatus(id, STATUS_FROZEN, onResult)

    /** 解冻：status=active。 */
    fun unfreeze(id: Long, onResult: (String?) -> Unit) = setStatus(id, STATUS_ACTIVE, onResult)

    /** T83 编辑正文：只改 value/updatedAt，并写一条 edit history。 */
    fun edit(id: Long, value: String, onResult: (String?) -> Unit) {
        workScope.launch {
            try {
                val trimmed = value.trim()
                if (trimmed.isEmpty()) error("印象正文不能为空")
                val updated = impressionRepo.edit(id, trimmed, Clock.System.now().toEpochMilliseconds())
                if (!updated) error("印象已不存在，请刷新后重试")
                mainScope.launch { onResult(null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(t.message ?: "印象编辑失败") }
            }
        }
    }

    /** T83 撤销拒绝：rejected → active，并写一条 undo_reject history。 */
    fun undoReject(id: Long, onResult: (String?) -> Unit) {
        workScope.launch {
            try {
                val updated = impressionRepo.undoReject(id, Clock.System.now().toEpochMilliseconds())
                if (!updated) error("该印象不在已拒绝状态，无法撤销")
                mainScope.launch { onResult(null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(t.message ?: "撤销拒绝失败") }
            }
        }
    }

    private fun setStatus(id: Long, status: String, onResult: (String?) -> Unit) {
        workScope.launch {
            try {
                val ref = findImpressionRefSync(id) ?: error("印象已不存在，请刷新后重试")
                val updated = impressionRepo.setStatus(
                    ownerType = OWNER_TYPE_OPERATOR,
                    ownerId = ref.ownerId,
                    factKey = ref.factKey,
                    status = status,
                    now = Clock.System.now().toEpochMilliseconds(),
                )
                if (!updated) error("印象状态更新失败")
                mainScope.launch { onResult(null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(t.message ?: "印象操作失败") }
            }
        }
    }

    private suspend fun findImpressionRefSync(id: Long): Impression? {
        if (id <= 0) return null
        for (operator in chatRepo.getAllOperatorsSync()) {
            val hit = impressionRepo.listAllForOwner(OWNER_TYPE_OPERATOR, operator.id)
                .firstOrNull { it.id == id }
            if (hit != null) return hit
        }
        return null
    }

    // ==================================================================
    // 读取实现
    // ==================================================================

    /** 印象实体表优先；整表（含 rejected）一行都没有时才回退 T54 过滤视图。 */
    private suspend fun snapshotSync(page: Int = 1, pageSize: Int = DEFAULT_PAGE_SIZE): ImpressionsSnapshotDto {
        val now = Clock.System.now().toEpochMilliseconds()
        val safePage = page.coerceAtLeast(1)
        val safeSize = if (pageSize <= 0) DEFAULT_PAGE_SIZE else pageSize.coerceAtMost(MAX_PAGE_SIZE)
        return impressionTableSnapshot(safePage, safeSize, now) ?: legacySnapshotSync(now, safePage, safeSize)
    }

    private suspend fun impressionTableSnapshot(page: Int, pageSize: Int, now: Long): ImpressionsSnapshotDto? {
        val operators = chatRepo.getAllOperatorsSync()
        if (operators.isEmpty()) return null
        val byId = operators.associateBy { it.id }
        val pageData = impressionRepo.listPage(OWNER_TYPE_OPERATOR, page, pageSize)
        val allRows = impressionRepo.countAll(OWNER_TYPE_OPERATOR)
        if (pageData.total <= 0L && allRows <= 0L) return null

        val entries = pageData.items
            .map { row -> toEntry(row, byId[row.ownerId]) }
            .sortedWith(impressionComparator())
        val layerCounts = impressionRepo.countVisibleByLayer(OWNER_TYPE_OPERATOR)
        return ImpressionsSnapshotDto(
            entries = entries,
            totalCount = entries.size,
            total = pageData.total.toInt(),
            hasMore = pageData.hasMore,
            page = page,
            pageSize = pageSize,
            longTermCount = (layerCounts["long_term"] ?: 0L).toInt(),
            sustainedCount = (layerCounts["ongoing"] ?: 0L).toInt(),
            interactionCount = (layerCounts["interaction"] ?: 0L).toInt(),
            legacyCount = 0,
            rejectedCount = (allRows - pageData.total).coerceAtLeast(0L).toInt(),
            generatedAt = now,
            note = "长期印象来自反复互动；持续了解来自多次互动；互动印象按记录日期保留。冻结的印象仍会显示，拒绝的印象不再出现。",
            dataSource = SOURCE_IMPRESSIONS,
        )
    }

    private suspend fun rejectedEntriesSync(): List<ImpressionEntryDto> {
        val byId = chatRepo.getAllOperatorsSync().associateBy { it.id }
        return impressionRepo.listRejected(OWNER_TYPE_OPERATOR)
            .map { row -> toEntry(row, byId[row.ownerId]) }
            .sortedWith(impressionComparator())
    }

    private fun toEntry(row: Impression, operator: Operator?): ImpressionEntryDto {
        val level = levelOfLayer(row.layer)
        return ImpressionEntryDto(
            id = row.id,
            impressionId = row.id,
            operatorId = row.ownerId,
            operatorName = operator?.name ?: row.ownerId,
            avatarId = operator?.avatarUri.orEmpty(),
            level = level,
            levelLabel = labelOfLevel(level),
            memoryType = row.predicate.ifBlank { row.layer },
            memoryTypeLabel = predicateLabel(row.predicate),
            content = impressionContent(row),
            rawValue = row.value,
            eventTime = "",
            createdAt = row.firstSeenAt,
            updatedAt = if (row.lastConfirmedAt > 0) row.lastConfirmedAt else row.updatedAt,
            sourceLabel = SOURCE_IMPRESSIONS,
            importance = 0,
            isLegacy = false,
            confidence = row.confidence,
            evidenceCount = row.evidenceCount,
            status = row.status,
            firstSeenAt = row.firstSeenAt,
            lastConfirmedAt = row.lastConfirmedAt,
        )
    }

    /** T54 旧视图（老库回填前兜底）：memory_items L1/L2/L3 + 旧版 memories 长期印象；不分页。 */
    private suspend fun legacySnapshotSync(now: Long, page: Int, pageSize: Int): ImpressionsSnapshotDto {
        val operators = chatRepo.getAllOperatorsSync()
        val byId = operators.associateBy { it.id }

        val allItems = chatRepo.getAllMemoryItems()
        // B7：L1 重要度/近 30 天门槛 + 同主题只留最新一条（过滤逻辑在 commonMain，可单测）。
        val current = ImpressionFilter.selectCurrent(allItems, now)

        val currentL3OwnerIds = current.filter { it.memoryLevel == MemoryLevel.L3 }.map { it.ownerId }.toSet()
        val legacy = chatRepo.getAllLongTermImpressions().filter { memory ->
            memory.content.isNotBlank() && memory.expiresAt > now && memory.operatorId !in currentL3OwnerIds
        }

        val entries = mutableListOf<ImpressionEntryDto>()
        current.forEach { item ->
            val op = byId[item.ownerId]
            entries += ImpressionEntryDto(
                id = item.id,
                operatorId = item.ownerId,
                operatorName = op?.name ?: item.ownerId,
                avatarId = op?.avatarUri.orEmpty(),
                level = item.memoryLevel.name,
                levelLabel = levelLabel(item.memoryLevel),
                memoryType = item.memoryType,
                memoryTypeLabel = memoryTypeLabel(item.memoryType),
                content = item.content,
                rawValue = item.content,
                eventTime = item.eventTime?.trim().orEmpty(),
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                sourceLabel = SOURCE_MEMORY_PIPELINE,
                importance = item.importance,
                isLegacy = false,
            )
        }
        legacy.forEach { memory ->
            val op = byId[memory.operatorId]
            entries += ImpressionEntryDto(
                id = 0L, // 旧版 memories.id 与 memory_items.id 不同域，Swift 侧用 index 作为列表 key
                operatorId = memory.operatorId,
                operatorName = op?.name ?: memory.operatorId,
                avatarId = op?.avatarUri.orEmpty(),
                level = MemoryLevel.L3.name,
                levelLabel = "长期印象",
                memoryType = "legacy_long_term",
                memoryTypeLabel = "早期记录",
                content = memory.content,
                rawValue = memory.content,
                eventTime = "",
                createdAt = memory.createdAt,
                updatedAt = memory.createdAt,
                sourceLabel = SOURCE_LEGACY_MEMORIES,
                importance = 80,
                isLegacy = true,
            )
        }

        // 合并记忆管线条目与旧版兜底条目后统一按 oracle 排序：L3 优先 → updatedAt 降序 → importance 降序。
        val sortedEntries = entries.sortedWith(impressionComparator())
        return ImpressionsSnapshotDto(
            entries = sortedEntries,
            totalCount = sortedEntries.size,
            total = sortedEntries.size,
            hasMore = false,
            page = page,
            pageSize = pageSize,
            longTermCount = sortedEntries.count { it.level == "L3" },
            sustainedCount = sortedEntries.count { it.level == "L2" },
            interactionCount = sortedEntries.count { it.level == "L1" },
            legacyCount = sortedEntries.count { it.isLegacy },
            generatedAt = now,
            note = "长期印象来自反复互动；持续了解来自多次互动；互动印象按记录日期保留。",
            dataSource = SOURCE_MEMORY_PIPELINE,
        )
    }

    // ==================================================================
    // 自检（仅 `--impressions-selftest` 由 Swift 自动化触发）
    // ==================================================================

    fun debugRunSelfTest(onDone: (String) -> Unit) {
        workScope.launch {
            val lines = mutableListOf<String>()
            var failures = 0
            fun check(name: String, ok: Boolean, detail: String = "") {
                if (!ok) failures++
                lines += "${if (ok) "PASS" else "FAIL"} $name" + if (detail.isBlank()) "" else " | $detail"
            }
            fun report() {
                lines += "RESULT ${if (failures == 0) "PASS" else "FAIL($failures)"}"
                val text = lines.joinToString("\n")
                NSLog(("[RhodesLink][T54SELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t54-impressions-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T54SELFTEST] report write failed: " + (it.message ?: "")) }
                mainScope.launch { runCatching { onDone(text) } }
            }

            try {
                lines += "=== T54 impressions selftest ==="
                val fixture = ensureFixtureSync()
                lines += "fixture ${fixture.line}"

                val snapshot = snapshotSync()
                check("list.nonEmpty", snapshot.entries.isNotEmpty(), "total=${snapshot.total} loaded=${snapshot.totalCount}")
                check(
                    "list.fields",
                    snapshot.entries.all {
                        it.operatorId.isNotBlank() && it.operatorName.isNotBlank() && it.content.isNotBlank() &&
                            it.levelLabel.isNotBlank() && it.memoryTypeLabel.isNotBlank() && it.sourceLabel.isNotBlank()
                    },
                    "sample=${snapshot.entries.firstOrNull()?.operatorName}/${snapshot.entries.firstOrNull()?.levelLabel}",
                )
                check(
                    "list.sortedL3First",
                    sortedL3First(snapshot.entries),
                    "levels=" + snapshot.entries.take(8).joinToString(",") { it.level },
                )
                check(
                    "list.counts",
                    snapshot.total == snapshot.longTermCount + snapshot.sustainedCount + snapshot.interactionCount,
                    "total=${snapshot.total} loaded=${snapshot.totalCount} L3=${snapshot.longTermCount} L2=${snapshot.sustainedCount} L1=${snapshot.interactionCount} legacy=${snapshot.legacyCount}",
                )
                check("list.levelLabels", snapshot.entries.all { labelOfLevel(it.level) == it.levelLabel }, "labels=" + snapshot.entries.map { it.level + ":" + it.levelLabel }.distinct())

                if (fixture.created) {
                    // 动态期望：夹具 3 条有效当前印象 + 已有/新写入的旧版 memories 兜底条目
                    val expectedCurrent = 3
                    val expectedTotal = expectedCurrent + snapshot.legacyCount
                    check("fixture.validCurrent", snapshot.entries.count { !it.isLegacy } == expectedCurrent, "current=${snapshot.entries.count { !it.isLegacy }} expected=$expectedCurrent")
                    check("fixture.totalCount", snapshot.total == expectedTotal, "total=${snapshot.total} expected=$expectedTotal（3 当前 + ${snapshot.legacyCount} 旧版）")
                    check("fixture.longTermCount", snapshot.longTermCount == 1 + snapshot.legacyCount, "L3=${snapshot.longTermCount} expected=${1 + snapshot.legacyCount}")
                    check("fixture.sustainedCount", snapshot.sustainedCount == 1, "L2=${snapshot.sustainedCount}")
                    check("fixture.interactionCount", snapshot.interactionCount == 1, "L1=${snapshot.interactionCount}")
                    check("fixture.legacyCount", snapshot.legacyCount >= 1, "legacy=${snapshot.legacyCount}")
                    check(
                        "fixture.filterExcludesInvalid",
                        snapshot.entries.none { it.content.contains(FIXTURE_INVALID_LOW_IMPORTANCE_MARK) || it.content.contains(FIXTURE_INVALID_EXPIRED_MARK) || it.content.contains(FIXTURE_INVALID_GROUP_MARK) },
                        "contents=" + snapshot.entries.map { it.memoryType }.joinToString(","),
                    )
                    check(
                        "fixture.legacySource",
                        snapshot.entries.any { it.isLegacy && it.sourceLabel == SOURCE_LEGACY_MEMORIES },
                        "legacy=" + snapshot.entries.filter { it.isLegacy }.map { it.content.take(12) }.joinToString(","),
                    )
                } else {
                    lines += "SKIP fixture.*（DB 已有真实印象，未写夹具，断言基于真实数据）"
                }

                // DB 核对：旧视图比对 memory_items；印象表视图核对实体行 id/status。
                val now = Clock.System.now().toEpochMilliseconds()
                lines += "db.dataSource=${snapshot.dataSource}"
                if (snapshot.dataSource == SOURCE_IMPRESSIONS) {
                    check(
                        "db.impressionIds",
                        snapshot.entries.all { it.impressionId > 0 },
                        "ids=" + snapshot.entries.take(8).map { it.impressionId }.joinToString(","),
                    )
                    check(
                        "db.impressionStatus",
                        snapshot.entries.all { it.status == STATUS_ACTIVE || it.status == STATUS_FROZEN },
                        "statuses=" + snapshot.entries.map { it.status }.distinct().joinToString(","),
                    )
                } else {
                    val dbCurrent = ImpressionFilter.selectCurrent(chatRepo.getAllMemoryItems(), now).size
                    val bridgeNonLegacy = snapshot.entries.count { !it.isLegacy }
                    check(
                        "db.countParity",
                        dbCurrent == bridgeNonLegacy,
                        "dbMemoryItems=$dbCurrent bridge=$bridgeNonLegacy",
                    )
                    val dbLegacy = chatRepo.getAllLongTermImpressions().count { it.content.isNotBlank() && it.expiresAt > now }
                    lines += "db.memoryItems.total=" + chatRepo.getAllMemoryItems().size + " db.memories.legacy=$dbLegacy"
                }
                if (fixture.created) {
                    check(
                        "db.fixtureRowsPresent",
                        chatRepo.getAllMemoryItems().count { it.ownerId == fixture.operatorId } >= 6,
                        "rows=" + chatRepo.getAllMemoryItems().count { it.ownerId == fixture.operatorId } + " expected>=6（3 有效 + 3 应被过滤）",
                    )
                }
            } catch (t: Throwable) {
                lines += "FATAL ${t.message ?: t::class.simpleName}"
                failures++
            }
            report()
        }
    }

    // ==================================================================
    // 夹具（仅「无任何真实印象」时写入，幂等；供自检/截图）
    // ==================================================================

    private suspend fun ensureFixtureSync(): FixtureInfo {
        val now = Clock.System.now().toEpochMilliseconds()
        val existingCurrent = snapshotSync().entries.count { !it.isLegacy }
        if (existingCurrent > 0) {
            return FixtureInfo(false, "skip（DB 已有 ${existingCurrent} 条记忆管线印象，未写夹具）", "")
        }
        val operators = chatRepo.getAllOperatorsSync()
        val fixtureOp = operators.firstOrNull()
        val operatorId = fixtureOp?.id ?: FIXTURE_OPERATOR_ID
        if (fixtureOp == null) {
            chatRepo.insertOperator(Operator(id = operatorId, name = FIXTURE_OPERATOR_NAME, avatarUri = "2"))
        }
        var secondOpId = operators.getOrNull(1)?.id
        if (secondOpId == null) {
            secondOpId = if (operatorId == FIXTURE_OPERATOR_ID) FIXTURE_SECOND_OPERATOR_ID else operatorId
            if (secondOpId != operatorId) {
                chatRepo.insertOperator(Operator(id = secondOpId, name = FIXTURE_SECOND_OPERATOR_NAME, avatarUri = "3"))
            }
        }

        suspend fun insert(
            ownerId: String,
            level: MemoryLevel,
            type: String,
            content: String,
            importance: Int,
            expiresAt: Long = Long.MAX_VALUE,
            sourceKind: MemorySourceKind = MemorySourceKind.PRIVATE_CHAT,
            createdAt: Long = now - 60_000,
            updatedAt: Long = now - 60_000,
        ) {
            // 夹具经 memoryV2 写入（与生产 MemoryV2Repository.insertMemoryItem 同路径）
            chatRepo.memoryV2.insertMemoryItem(
                MemoryItem(
                    ownerType = "operator",
                    ownerId = ownerId,
                    memoryLevel = level,
                    memoryType = type,
                    sourceKind = sourceKind,
                    content = content,
                    importance = importance,
                    privacy = "private",
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    expiresAt = expiresAt,
                    status = "active",
                ),
            )
        }

        insert(operatorId, MemoryLevel.L3, "stable_impression", "$FIXTURE_VALID_MARK 长期印象：她记得你喜欢在深夜聊天，也记得你答应过要早点休息。", 85, updatedAt = now - 3_000)
        insert(operatorId, MemoryLevel.L2, "preference_expression", "$FIXTURE_VALID_MARK 持续了解：你偏爱清淡口味，周末常去城郊散步。", 70, updatedAt = now - 2_000)
        insert(operatorId, MemoryLevel.L1, "emotion_state", "$FIXTURE_VALID_MARK 互动印象：今天你看起来有点疲惫，但心情不错。", 40, updatedAt = now - 1_000)
        // 以下 3 条应被 oracle 过滤：低重要度 L2 / 已过期 L3 / 群聊来源 L3
        insert(operatorId, MemoryLevel.L2, "preference_expression", "$FIXTURE_INVALID_LOW_IMPORTANCE_MARK 低重要度 L2 不应展示。", 30)
        insert(operatorId, MemoryLevel.L3, "stable_impression", "$FIXTURE_INVALID_EXPIRED_MARK 已过期长期印象不应展示。", 80, expiresAt = now - 1_000)
        insert(operatorId, MemoryLevel.L3, "stable_impression", "$FIXTURE_INVALID_GROUP_MARK 群聊来源印象不进入私聊印象页。", 80, sourceKind = MemorySourceKind.GROUP_CHAT)

        // 旧版 memories 长期印象（另一角色）：验证只读兜底 + 与 memory_items L3 去重
        chatRepo.memories.saveMemory(
            Memory(
                sessionId = "session_$secondOpId",
                operatorId = secondOpId,
                type = MemoryType.LONG_TERM,
                content = "$FIXTURE_LEGACY_MARK 旧版长期印象：她记得你们一起看过的每一场雨。",
                createdAt = now - 10_000,
            ),
        )

        return FixtureInfo(
            true,
            "created op=$operatorId second=$secondOpId valid=3 invalid=3 legacy=1",
            operatorId,
        )
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private fun levelLabel(level: MemoryLevel): String = when (level) {
        MemoryLevel.L3 -> "长期印象"
        MemoryLevel.L2 -> "持续了解"
        MemoryLevel.L1 -> "互动印象"
    }

    private fun labelOfLevel(level: String): String = when (level) {
        "L3" -> "长期印象"
        "L2" -> "持续了解"
        "L1" -> "互动印象"
        else -> level
    }

    /** 与 oracle `ImpressionsScreen.memoryTypeLabel` 同口径。 */
    private fun memoryTypeLabel(type: String): String = when (type) {
        "preference_expression" -> "偏好"
        "emotion_state" -> "情绪"
        "physiological_state" -> "身体状态"
        "agreement_commitment" -> "约定"
        "care_reminder" -> "关心提醒"
        "intent_wish" -> "意图愿望"
        "evaluation_opinion" -> "评价观点"
        "self_cognition_statement" -> "自我认知"
        "stable_impression" -> "稳定印象"
        "legacy_long_term" -> "早期记录"
        else -> type.replace('_', ' ')
    }

    /** `impressions.layer` → 页面三段（与 T54 L1/L2/L3 语义对齐）。 */
    private fun levelOfLayer(layer: String): String = when (layer) {
        "long_term" -> "L3"
        "ongoing" -> "L2"
        else -> "L1"
    }

    /** 结构化事实谓词（`MemoryParseRules` 的 canonical predicate）→ 中文标签。 */
    private fun predicateLabel(predicate: String): String = when (predicate) {
        "prefer" -> "偏好"
        "dislike" -> "不喜欢"
        "reside" -> "居住"
        "work" -> "工作"
        "allergy" -> "过敏"
        "promise" -> "约定"
        "plan" -> "计划"
        "own" -> "拥有"
        "study" -> "学习"
        "birthday" -> "生日"
        "" -> "印象"
        else -> predicate.replace('_', ' ')
    }

    /**
     * 印象正文：结构化事实拼成「主体+谓词+客体」短句（如「用户喜欢川菜」）；
     * 无结构化键的条目直接用 value（ImpressionBuilder 已把旧内容截到 120 字）。
     */
    private fun impressionContent(row: Impression): String {
        val value = row.value.trim()
        val subject = row.subject.trim()
        val predicate = row.predicate.trim()
        if (value.isEmpty()) return subject.ifEmpty { row.factKey }
        if (subject.isEmpty() || predicate.isEmpty()) return value
        return when (predicate) {
            "prefer" -> "${subject}喜欢$value"
            "dislike" -> "${subject}不喜欢$value"
            "reside" -> "${subject}住在$value"
            "work" -> "${subject}工作于$value"
            "allergy" -> "${subject}对${value}过敏"
            "promise" -> "${subject}答应过$value"
            "plan" -> "${subject}计划$value"
            "own" -> "${subject}拥有$value"
            "study" -> "${subject}学习$value"
            "birthday" -> "${subject}的生日是$value"
            else -> "$subject $predicate $value"
        }
    }

    private fun impressionComparator(): Comparator<ImpressionEntryDto> =
        compareByDescending<ImpressionEntryDto> { it.level == "L3" }
            .thenByDescending { it.updatedAt }
            .thenByDescending { it.importance }
            .thenByDescending { it.confidence }

    /** 证据正文摘要：压平换行、避开协议分隔符 `|`，最多 80 字。 */
    private fun evidenceSummary(content: String?): String =
        content.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace('|', '｜')
            .trim()
            .take(EVIDENCE_SUMMARY_MAX)

    private fun sortedL3First(entries: List<ImpressionEntryDto>): Boolean {
        var seenNonL3 = false
        for (entry in entries) {
            if (entry.level == "L3") {
                if (seenNonL3) return false
            } else {
                seenNonL3 = true
            }
        }
        return true
    }

    private data class FixtureInfo(val created: Boolean, val line: String, val operatorId: String)

    companion object {
        private const val OWNER_TYPE_OPERATOR = "operator"
        private const val STATUS_ACTIVE = "active"
        private const val STATUS_REJECTED = "rejected"
        private const val STATUS_FROZEN = "frozen"
        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 200
        private const val EVIDENCE_LIMIT = 8
        private const val EVIDENCE_SUMMARY_MAX = 80
        private const val SOURCE_IMPRESSIONS = "印象表"
        private const val SOURCE_MEMORY_PIPELINE = "记忆管线"
        private const val SOURCE_LEGACY_MEMORIES = "早期记录"
        private const val FIXTURE_OPERATOR_ID = "t54_selftest_imp"
        private const val FIXTURE_OPERATOR_NAME = "T54印象自检干员"
        private const val FIXTURE_SECOND_OPERATOR_NAME = "T54印象自检干员B"
        private const val FIXTURE_SECOND_OPERATOR_ID = "t54_selftest_imp2"
        private const val FIXTURE_VALID_MARK = "T54自检有效"
        private const val FIXTURE_INVALID_LOW_IMPORTANCE_MARK = "T54自检低重要度"
        private const val FIXTURE_INVALID_EXPIRED_MARK = "T54自检已过期"
        private const val FIXTURE_INVALID_GROUP_MARK = "T54自检群聊来源"
        private const val FIXTURE_LEGACY_MARK = "T54自检旧版"
    }
}

// ======================================================================
// DTO（只导出简单类型；Swift 侧只读字段）
// ======================================================================

/**
 * 印象一行。[level] = L1/L2/L3；[isLegacy] = 旧版 memories 表兜底条目。
 * [impressionId] > 0 表示来自 `impressions` 表（可冻结/拒绝/查证据）；= 0 表示 T54 旧视图条目。
 */
class ImpressionEntryDto(
    val id: Long,
    val operatorId: String,
    val operatorName: String,
    /** operators.avatarUri 原样；空 = 首字占位。 */
    val avatarId: String,
    val level: String,
    /** 长期印象 / 持续了解 / 互动印象。 */
    val levelLabel: String,
    val memoryType: String,
    val memoryTypeLabel: String,
    val content: String,
    /** T83：impressions.value 原文（编辑弹窗的初始值；结构化事实只改这一段）。 */
    val rawValue: String,
    /** 记忆的「发生于」时间文本（memory_items.eventTime）；空串 = 无。 */
    val eventTime: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 来源标签（印象表 / 记忆管线 / 历史印象（旧版 memories））。 */
    val sourceLabel: String,
    val importance: Int,
    val isLegacy: Boolean,
    /** `impressions.id`；0 = 旧视图条目（无实体行，不能操作/查证据）。 */
    val impressionId: Long = 0,
    /** 置信度 0..1；旧视图条目为 0。 */
    val confidence: Double = 0.0,
    val evidenceCount: Int = 0,
    /** active / frozen；旧视图条目为空串。 */
    val status: String = "",
    val firstSeenAt: Long = 0,
    val lastConfirmedAt: Long = 0,
)

/** 印象快照（按 oracle 排序：L3 优先 → updatedAt 降序 → importance 降序）。 */
class ImpressionsSnapshotDto(
    val entries: List<ImpressionEntryDto>,
    /** 本次快照已加载的条目数（分页时 = 累计到 page 的行数）。 */
    val totalCount: Int,
    /** T82：全表可见（active + frozen）总数，不受分页影响。 */
    val total: Int = 0,
    /** T82：还有下一页未加载。 */
    val hasMore: Boolean = false,
    val page: Int = 1,
    val pageSize: Int = 20,
    val longTermCount: Int,
    val sustainedCount: Int,
    val interactionCount: Int,
    val legacyCount: Int,
    /** T83：已拒绝条数（正常列表不展示，供撤销拒绝入口提示）。 */
    val rejectedCount: Int = 0,
    val generatedAt: Long,
    val note: String,
    /** impressions = 印象实体表；memory_items = T54 过滤视图回退。 */
    val dataSource: String = "memory_items",
)
