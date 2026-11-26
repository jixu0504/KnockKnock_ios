package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.Memory
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
 * 「大家的印象」桥（T54，真实 SQLite：`memory_items` 记忆管线 L1/L2/L3 + 旧版 `memories` 长期印象）。
 *
 * 数据/口径对齐 Android oracle：
 * - `ImpressionsScreen.kt:59`（页面：长期印象 / 持续了解 / 互动印象 三段；行 = 角色头像+名字+级别·日期+正文+类型标签）；
 * - `MainViewModel.kt:1527-1551 getCurrentImpressions()`：`memory_items` 过滤条件逐条复刻：
 *   ownerType=operator、sourceKind=PRIVATE_CHAT、status=active、content 非空；L1 只收
 *   {preference_expression, emotion_state, physiological_state}；L2 需 `expiresAt > now && importance >= 60`
 *   且类型 ∈ 6 种；L3 需 `expiresAt > now`。排序 = L3 优先 → updatedAt 降序 → importance 降序；
 * - `ImpressionsScreen.kt:137-145` 三段标题/副标题与空态「暂无印象数据 / 多聊几次后，干员会逐渐形成对你的近期了解和长期印象」；
 * - 旧版 `memories` 表 LONG_TERM（`MainViewModel.migrateLegacyImpressions` 的来源）：本桥**只读兜底**，
 *   当某角色已有 memory_items L3 时不再重复展示旧版印象（与迁移后去重语义一致），避免桥内写库。
 *
 * 纪律（同 ios-bridge-spec.md）：只导出简单类型 DTO；回调一律 Main 队列；错误经第二参数回传。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "ImpressionsBridge")
class ImpressionsBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    /** 印象快照（按 oracle 排序；UI 再按级别分组）。失败返回 (null, errorMessage)。 */
    fun list(onResult: (ImpressionsSnapshotDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val snapshot = snapshotSync()
                mainScope.launch { onResult(snapshot, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "印象读取失败") }
            }
        }
    }

    // ==================================================================
    // 读取实现
    // ==================================================================

    private suspend fun snapshotSync(): ImpressionsSnapshotDto {
        val now = Clock.System.now().toEpochMilliseconds()
        val operators = chatRepo.getAllOperatorsSync()
        val byId = operators.associateBy { it.id }

        val allItems = chatRepo.getAllMemoryItems()
        val current = allItems.filter { item ->
            item.ownerType == "operator" &&
                item.sourceKind == MemorySourceKind.PRIVATE_CHAT &&
                item.status == "active" && item.content.isNotBlank() && when (item.memoryLevel) {
                MemoryLevel.L1 -> item.memoryType in IMPRESSION_L1_TYPES
                MemoryLevel.L2 -> item.expiresAt > now && item.importance >= 60 && item.memoryType in IMPRESSION_RECENT_TYPES
                MemoryLevel.L3 -> item.expiresAt > now
            }
        }.sortedWith(
            compareByDescending<MemoryItem> { it.memoryLevel == MemoryLevel.L3 }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.importance },
        )

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
                memoryTypeLabel = "旧版长期印象",
                content = memory.content,
                eventTime = "",
                createdAt = memory.createdAt,
                updatedAt = memory.createdAt,
                sourceLabel = SOURCE_LEGACY_MEMORIES,
                importance = 80,
                isLegacy = true,
            )
        }

        // 合并记忆管线条目与旧版兜底条目后统一按 oracle 排序：L3 优先 → updatedAt 降序 → importance 降序。
        val sortedEntries = entries.sortedWith(
            compareByDescending<ImpressionEntryDto> { it.level == "L3" }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.importance },
        )
        return ImpressionsSnapshotDto(
            entries = sortedEntries,
            totalCount = sortedEntries.size,
            longTermCount = sortedEntries.count { it.level == "L3" },
            sustainedCount = sortedEntries.count { it.level == "L2" },
            interactionCount = sortedEntries.count { it.level == "L1" },
            legacyCount = sortedEntries.count { it.isLegacy },
            generatedAt = now,
            note = "长期印象 = 反复互动后形成的稳定了解；持续了解 = 多次互动合并的偏好/约定；互动印象 = 按记录日期保留的偏好、情绪与身体状态",
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
                check("list.nonEmpty", snapshot.entries.isNotEmpty(), "total=${snapshot.totalCount}")
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
                    snapshot.totalCount == snapshot.longTermCount + snapshot.sustainedCount + snapshot.interactionCount,
                    "total=${snapshot.totalCount} L3=${snapshot.longTermCount} L2=${snapshot.sustainedCount} L1=${snapshot.interactionCount} legacy=${snapshot.legacyCount}",
                )
                check("list.levelLabels", snapshot.entries.all { labelOfLevel(it.level) == it.levelLabel }, "labels=" + snapshot.entries.map { it.level + ":" + it.levelLabel }.distinct())

                if (fixture.created) {
                    // 动态期望：夹具 3 条有效当前印象 + 已有/新写入的旧版 memories 兜底条目
                    val expectedCurrent = 3
                    val expectedTotal = expectedCurrent + snapshot.legacyCount
                    check("fixture.validCurrent", snapshot.entries.count { !it.isLegacy } == expectedCurrent, "current=${snapshot.entries.count { !it.isLegacy }} expected=$expectedCurrent")
                    check("fixture.totalCount", snapshot.totalCount == expectedTotal, "total=${snapshot.totalCount} expected=$expectedTotal（3 当前 + ${snapshot.legacyCount} 旧版）")
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

                // DB 核对：bridge 当前条目数 == 直接按 oracle 条件过滤 memory_items 的结果数
                val now = Clock.System.now().toEpochMilliseconds()
                val dbCurrent = chatRepo.getAllMemoryItems().count { item ->
                    item.ownerType == "operator" && item.sourceKind == MemorySourceKind.PRIVATE_CHAT &&
                        item.status == "active" && item.content.isNotBlank() && when (item.memoryLevel) {
                        MemoryLevel.L1 -> item.memoryType in IMPRESSION_L1_TYPES
                        MemoryLevel.L2 -> item.expiresAt > now && item.importance >= 60 && item.memoryType in IMPRESSION_RECENT_TYPES
                        MemoryLevel.L3 -> item.expiresAt > now
                    }
                }
                val dbLegacy = chatRepo.getAllLongTermImpressions().count { it.content.isNotBlank() && it.expiresAt > now }
                val bridgeNonLegacy = snapshot.entries.count { !it.isLegacy }
                check(
                    "db.countParity",
                    dbCurrent == bridgeNonLegacy,
                    "dbMemoryItems=$dbCurrent bridge=$bridgeNonLegacy dbLegacyRaw=$dbLegacy bridgeLegacy=${snapshot.legacyCount}",
                )
                lines += "db.memoryItems.total=" + chatRepo.getAllMemoryItems().size + " db.memories.legacy=$dbLegacy"
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
        "legacy_long_term" -> "旧版长期印象"
        else -> type.replace('_', ' ')
    }

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
        private val IMPRESSION_L1_TYPES = setOf("preference_expression", "emotion_state", "physiological_state")
        private val IMPRESSION_RECENT_TYPES = setOf(
            "preference_expression", "agreement_commitment", "care_reminder",
            "intent_wish", "evaluation_opinion", "self_cognition_statement",
        )
        private const val SOURCE_MEMORY_PIPELINE = "记忆管线"
        private const val SOURCE_LEGACY_MEMORIES = "历史印象（旧版 memories）"
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

/** 印象一行。[level] = L1/L2/L3；[isLegacy] = 旧版 memories 表兜底条目。 */
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
    /** 记忆的「发生于」时间文本（memory_items.eventTime）；空串 = 无。 */
    val eventTime: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 来源标签（记忆管线 / 历史印象（旧版 memories））。 */
    val sourceLabel: String,
    val importance: Int,
    val isLegacy: Boolean,
)

/** 印象快照（按 oracle 排序：L3 优先 → updatedAt 降序 → importance 降序）。 */
class ImpressionsSnapshotDto(
    val entries: List<ImpressionEntryDto>,
    val totalCount: Int,
    val longTermCount: Int,
    val sustainedCount: Int,
    val interactionCount: Int,
    val legacyCount: Int,
    val generatedAt: Long,
    val note: String,
)
