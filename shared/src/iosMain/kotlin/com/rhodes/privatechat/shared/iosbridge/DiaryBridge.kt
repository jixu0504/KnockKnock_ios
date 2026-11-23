package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.Diary
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSLog

/**
 * 干员日记桥（T51，真实 SQLite：`diaries` 表 + settings `diary_read_at_<operatorId>`）。
 *
 * 数据/未读语义对齐 Android oracle：
 * - 列表 = `diaries` 全表（跨干员），按 `createdAt` 倒序（同刻按 id 倒序）。Android
 *   `DiaryScreen` 先按干员聚合再按「未读 > 有日记 > 最新时间 > 名字」排序；iOS 直接平铺
 *   日记条目并按时间倒序，未读筛选 = 所属干员未读（干员维度未读，与 oracle 一致）。
 * - 未读 = 该干员 `MAX(diaries.createdAt) > settings.getDiaryReadAt(operatorId)`；
 *   未读计数 = 未读干员数（= `MainViewModel.getUnreadDiaryCount()`）；打开阅读后
 *   `markRead` 写入 `diary_read_at_<operatorId> = 该干员最新 createdAt`（= `markDiaryRead`）。
 * - 标题/摘要：`diaries` 表**没有标题列**（golden/db/schema/Diaries.sq），桥按 oracle 展示语义
 *   派生：标题 = 「干员名 · 日期」，摘要 = 正文压平空白后的前 96 字（UI 展示用，不落库）。
 * - 删除：`Diaries.sq` 只有按干员/过期/全量删除，无单条删除查询；本卡写域不含
 *   `Diaries.sq`，故**不提供单条删除**（见 specs/diary-spec.md §6 限制）。
 *
 * 纪律（同 ios-bridge-spec.md）：只导出简单类型 DTO；回调一律 Main 队列；错误经第二参数回传
 * （null = 成功）；桥内不导出 Flow/StateFlow/data class。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "DiaryBridge")
class DiaryBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    // ==================================================================
    // 列表 / 详情 / 已读
    // ==================================================================

    /**
     * 日记列表（跨干员，createdAt 倒序）。
     * [unreadOnly] = true 时只返回所属干员未读的条目。
     */
    fun list(unreadOnly: Boolean, onResult: (DiaryListDto, String?) -> Unit) {
        workScope.launch {
            try {
                val dto = listSync(unreadOnly)
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch {
                    onResult(
                        DiaryListDto(emptyList(), 0, 0, unreadOnly),
                        t.message ?: "日记读取失败",
                    )
                }
            }
        }
    }

    /**
     * 按干员筛选的日记列表（T61「日记生成管理」页/后续干员维度视图用）：
     * 排序与 [list] 相同（createdAt 倒序、同刻 id 倒序），[unreadOnly] 语义不变。
     */
    fun listByOperator(operatorId: String, unreadOnly: Boolean, onResult: (DiaryListDto, String?) -> Unit) {
        workScope.launch {
            try {
                val dto = listByOperatorSync(operatorId, unreadOnly)
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(DiaryListDto(emptyList(), 0, 0, unreadOnly), t.message ?: "日记读取失败") }
            }
        }
    }

    /** 单篇全文（[id] = diaries.id）。不存在返回 (null, "日记不存在（可能已被删除）")。 */
    fun detail(id: Long, onResult: (DiaryDetailDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val snapshot = snapshotSync()
                val diary = snapshot.entries.firstOrNull { it.id == id }
                if (diary == null) {
                    mainScope.launch { onResult(null, "日记不存在（可能已被删除）") }
                } else {
                    val dto = detailDto(diary, snapshot)
                    mainScope.launch { onResult(dto, null) }
                }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "日记读取失败") }
            }
        }
    }

    /**
     * 标记某干员日记已读：`diary_read_at_<operatorId> = 该干员最新 createdAt`（oracle `markDiaryRead`）。
     * 回调返回剩余未读干员数（0 = 全部已读）。干员没有日记时为 no-op。
     */
    fun markRead(operatorId: String, onDone: (Int, String?) -> Unit) {
        workScope.launch {
            try {
                val entries = chatRepo.getAllDiaryEntries(operatorId)
                val latest = entries.maxOfOrNull { it.createdAt }
                if (latest != null) settingsRepo.putDiaryReadAt(operatorId, latest)
                val remaining = unreadOperatorIdsSync().size
                mainScope.launch { onDone(remaining, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(-1, t.message ?: "标记已读失败") }
            }
        }
    }

    /** 未读干员数（= Android `getUnreadDiaryCount()`；FeaturesScreen 日记角标同源）。 */
    fun unreadCount(onResult: (Int, String?) -> Unit) {
        workScope.launch {
            try {
                val count = unreadOperatorIdsSync().size
                mainScope.launch { onResult(count, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(-1, t.message ?: "未读计数失败") }
            }
        }
    }

    // ==================================================================
    // 自检夹具 / 端到端自检（仅 `--diary-selftest` 由 Swift 自动化触发）
    // ==================================================================

    /**
     * 预置截图/自检夹具（幂等）：
     * - DB 无任何日记 → 为第一名干员写一条「T51自检」日记（date = 北京时间今天）；
     * - DB 已有日记 → 不写库，直接复用该干员最新一篇；
     * - 无论哪种，都把该干员 `diary_read_at_<id>` 归零，保证列表出现未读点（仅自检路径调用）。
     * 返回一行诊断文本。
     */
    fun debugEnsureFixture(onDone: (String) -> Unit) {
        workScope.launch {
            val line = try {
                fixtureLine(ensureFixtureSync())
            } catch (t: Throwable) {
                "fixture.failed ${t.message ?: t::class.simpleName}"
            }
            mainScope.launch { onDone(line) }
        }
    }

    /**
     * T51 端到端自检（真实 DB + 真实 settings，不 mock）：
     * 夹具预置（DB 空则写 1 条）→ 列表出现 + 倒序 → 未读标记 → 详情全文 → 标记已读 →
     * 未读清零 → DB 行数/settings 键核对 → 保留夹具并重新置未读（供 UI 截图）。
     * 结果写 Application Support/t51-diary-selftest.log 并逐行 NSLog。
     */
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
                NSLog(("[RhodesLink][T51SELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t51-diary-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T51SELFTEST] report write failed: " + (it.message ?: "")) }
                mainScope.launch { runCatching { onDone(text) } }
            }

            try {
                lines += "=== T51 diary selftest ==="
                val fixture = ensureFixtureSync()
                lines += "fixture ${fixtureLine(fixture)}"

                // 1. 列表出现 + 倒序 + 字段完整
                val all = listSync(false)
                check("list.nonEmpty", all.items.isNotEmpty(), "total=${all.totalCount}")
                val target = all.items.firstOrNull { it.id == fixture.id }
                    ?: all.items.firstOrNull { it.operatorId == fixture.operatorId }
                check("list.containsFixture", target != null, "id=${fixture.id} op=${fixture.operatorId}")
                check("list.sortedDesc", isSortedDesc(all.items), "first=${all.items.firstOrNull()?.createdAt} last=${all.items.lastOrNull()?.createdAt}")
                check(
                    "list.fields",
                    target != null && target.operatorName.isNotBlank() && target.date.isNotBlank() &&
                        target.title.isNotBlank() && target.summary.isNotBlank(),
                    "name=${target?.operatorName} date=${target?.date} title=${target?.title} summaryLen=${target?.summary?.length}",
                )

                // 2. 未读标记（夹具已置未读）
                check("unread.before", all.unreadCount >= 1, "unreadOperators=${all.unreadCount}")
                val unreadOnly = listSync(true)
                check(
                    "list.unreadOnly",
                    unreadOnly.items.isNotEmpty() && unreadOnly.items.all { it.isUnread },
                    "size=${unreadOnly.items.size} operators=${unreadOnly.items.map { it.operatorId }.distinct().joinToString(",")}",
                )
                check("unread.marked", target?.isUnread == true, "isUnread=${target?.isUnread}")

                // 3. 详情全文
                val detail = detailSync(fixture.id)
                check("detail.ok", detail != null, "id=${fixture.id}")
                check("detail.content", detail?.content == fixture.content, "len=${detail?.content?.length}")
                check("detail.date", detail?.date == fixture.date, "date=${detail?.date}")

                // 4. 标记已读 → 未读清零（oracle：diary_read_at_<op> = 最新 createdAt）
                val beforeReadAt = settingsRepo.getDiaryReadAt(fixture.operatorId)
                val remaining = markReadSync(fixture.operatorId)
                val afterReadAt = settingsRepo.getDiaryReadAt(fixture.operatorId)
                check("markRead.latest", afterReadAt >= fixture.createdAt, "readAt=$afterReadAt latest=${fixture.createdAt}")
                check("markRead.unreadCleared", !unreadOperatorIdsSync().contains(fixture.operatorId), "readAt=$afterReadAt before=$beforeReadAt")
                if (fixture.wasOnlyUnreadOperator) {
                    check("markRead.unreadCountZero", remaining == 0, "remaining=$remaining")
                } else {
                    lines += "SKIP markRead.unreadCountZero（DB 中还有其它未读干员，remaining=$remaining）"
                }
                lines += "settings.diary_read_at_${fixture.operatorId}=$afterReadAt"

                // 5. DB 核对：行数 / 该干员已读位持久化
                val dbCount = chatRepo.getDiaryCount()
                check("db.count", dbCount >= fixture.countAfter, "dbCount=$dbCount expected>=${fixture.countAfter} created=${fixture.created}")
                check("db.getDiaryById", chatRepo.getAllDiaryEntries(fixture.operatorId).any { it.id == fixture.id }, "id=${fixture.id}")

                // 6. 留夹具并重新置未读，供后续 UI 自动化打开阅读（截图：未读点 → 详情 → 清零）
                settingsRepo.putDiaryReadAt(fixture.operatorId, 0L)
                lines += "fixture.leaveUnread operator=${fixture.operatorId} id=${fixture.id}（供 UI 截图；T51自检日记可经「数据管理→清理日记」或完整备份恢复清除）"
            } catch (t: Throwable) {
                lines += "FATAL ${t.message ?: t::class.simpleName}"
                failures++
            }
            report()
        }
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private class Snapshot(
        val entries: List<Diary>,
        /** 未读干员 id 集合（最新 createdAt > diary_read_at_<id>）。 */
        val unreadOperatorIds: Set<String>,
        val operatorsById: Map<String, Operator>,
        val operatorsByName: Map<String, Operator>,
    )

    private suspend fun snapshotSync(): Snapshot {
        ensureOperators()
        val entries = chatRepo.getAllDiariesForBackup()
        val operators = runCatching { chatRepo.getAllOperatorsSync() }.getOrDefault(emptyList())
        val unreadIds = entries.groupBy { it.operatorId }
            .mapNotNull { (operatorId, list) ->
                val latest = list.maxOfOrNull { it.createdAt } ?: 0L
                if (latest > settingsRepo.getDiaryReadAt(operatorId)) operatorId else null
            }
            .toSet()
        return Snapshot(
            entries = entries,
            unreadOperatorIds = unreadIds,
            operatorsById = operators.associateBy { it.id },
            operatorsByName = operators.associateBy { it.name },
        )
    }

    private suspend fun unreadOperatorIdsSync(): Set<String> = snapshotSync().unreadOperatorIds

    private suspend fun listSync(unreadOnly: Boolean): DiaryListDto {
        val snapshot = snapshotSync()
        val sorted = snapshot.entries.sortedWith(
            compareByDescending<Diary> { it.createdAt }.thenByDescending { it.id },
        )
        val items = sorted
            .filter { !unreadOnly || it.operatorId in snapshot.unreadOperatorIds }
            .map { entryDto(it, snapshot) }
        return DiaryListDto(
            items = items,
            totalCount = sorted.size,
            unreadCount = snapshot.unreadOperatorIds.size,
            unreadOnly = unreadOnly,
        )
    }

    private suspend fun listByOperatorSync(operatorId: String, unreadOnly: Boolean): DiaryListDto {
        val snapshot = snapshotSync()
        val sorted = snapshot.entries
            .filter { it.operatorId == operatorId }
            .sortedWith(compareByDescending<Diary> { it.createdAt }.thenByDescending { it.id })
        val items = sorted
            .filter { !unreadOnly || it.operatorId in snapshot.unreadOperatorIds }
            .map { entryDto(it, snapshot) }
        return DiaryListDto(
            items = items,
            totalCount = sorted.size,
            unreadCount = snapshot.unreadOperatorIds.size,
            unreadOnly = unreadOnly,
        )
    }

    private suspend fun detailSync(id: Long): DiaryDetailDto? {
        val snapshot = snapshotSync()
        val diary = snapshot.entries.firstOrNull { it.id == id } ?: return null
        return detailDto(diary, snapshot)
    }

    private suspend fun markReadSync(operatorId: String): Int {
        val entries = chatRepo.getAllDiaryEntries(operatorId)
        val latest = entries.maxOfOrNull { it.createdAt }
        if (latest != null) settingsRepo.putDiaryReadAt(operatorId, latest)
        return unreadOperatorIdsSync().size
    }

    private fun operatorOf(diary: Diary, snapshot: Snapshot): Operator? =
        snapshot.operatorsById[diary.operatorId] ?: snapshot.operatorsByName[diary.operatorName]

    private fun operatorNameOf(diary: Diary, snapshot: Snapshot): String =
        diary.operatorName.ifBlank { operatorOf(diary, snapshot)?.name ?: diary.operatorId }

    private fun avatarIdOf(diary: Diary, snapshot: Snapshot): String =
        operatorOf(diary, snapshot)?.avatarUri.orEmpty()

    private fun titleOf(diary: Diary, snapshot: Snapshot): String =
        "${operatorNameOf(diary, snapshot)} · ${diary.date}"

    private fun entryDto(diary: Diary, snapshot: Snapshot): DiaryEntryDto = DiaryEntryDto(
        id = diary.id,
        operatorId = diary.operatorId,
        operatorName = operatorNameOf(diary, snapshot),
        avatarId = avatarIdOf(diary, snapshot),
        date = diary.date,
        title = titleOf(diary, snapshot),
        summary = summarize(diary.content),
        createdAt = diary.createdAt,
        version = diary.version,
        isUnread = diary.operatorId in snapshot.unreadOperatorIds,
    )

    private fun detailDto(diary: Diary, snapshot: Snapshot): DiaryDetailDto = DiaryDetailDto(
        id = diary.id,
        operatorId = diary.operatorId,
        operatorName = operatorNameOf(diary, snapshot),
        avatarId = avatarIdOf(diary, snapshot),
        date = diary.date,
        title = titleOf(diary, snapshot),
        content = diary.content,
        createdAt = diary.createdAt,
        version = diary.version,
        isUnread = diary.operatorId in snapshot.unreadOperatorIds,
    )

    private suspend fun ensureOperators() {
        val existing = runCatching { chatRepo.getAllOperatorsSync() }.getOrDefault(emptyList())
        if (existing.isNotEmpty()) return
        runCatching { chatRepo.ensurePresetOperators() }
    }

    private class Fixture(
        val operatorId: String,
        val id: Long,
        val content: String,
        val date: String,
        val createdAt: Long,
        val countBefore: Int,
        val countAfter: Int,
        val created: Boolean,
        val unreadOperators: Int,
        val wasOnlyUnreadOperator: Boolean,
    )

    private suspend fun ensureFixtureSync(): Fixture {
        ensureOperators()
        val operators = chatRepo.getAllOperatorsSync()
        if (operators.isEmpty()) throw IllegalStateException("operators 为空，无法预置日记夹具")
        val today = todayDate()
        val countBefore = chatRepo.getDiaryCount()

        val entriesBefore = chatRepo.getAllDiariesForBackup()
        val existingToday = entriesBefore.firstOrNull {
            it.content.startsWith(SELFTEST_MARKER) && it.date == today
        }
        val fixtureDiary: Diary
        var created = false
        if (entriesBefore.isEmpty()) {
            val operator = operators.firstOrNull { it.id == DEFAULT_SELFTEST_OPERATOR_ID } ?: operators.first()
            val content = buildString {
                append(SELFTEST_MARKER)
                append("：今天训练结束后，")
                append(operator.name)
                append("在罗德岛走廊尽头写下了这篇独白——博士今天说的每一句话，都记在这里了。\n")
                append("（本条目由 --diary-selftest 预置，用于验证日记列表/未读/阅读链路。）")
            }
            fixtureDiary = chatRepo.insertDiary(
                Diary(
                    operatorId = operator.id,
                    operatorName = operator.name,
                    content = content,
                    date = today,
                    version = 1,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            created = true
        } else {
            fixtureDiary = existingToday ?: entriesBefore.maxByOrNull { it.createdAt }!!
        }
        // 自检：把所选干员的已读位归零，保证出现未读点（仅自检路径调用）。
        settingsRepo.putDiaryReadAt(fixtureDiary.operatorId, 0L)
        val countAfter = chatRepo.getDiaryCount()
        val unreadOps = unreadOperatorIdsSync()
        return Fixture(
            operatorId = fixtureDiary.operatorId,
            id = fixtureDiary.id,
            content = fixtureDiary.content,
            date = fixtureDiary.date,
            createdAt = fixtureDiary.createdAt,
            countBefore = countBefore,
            countAfter = countAfter,
            created = created,
            unreadOperators = unreadOps.size,
            wasOnlyUnreadOperator = unreadOps.size <= 1,
        )
    }

    private fun fixtureLine(fixture: Fixture): String = buildString {
        append(if (fixture.created) "created" else "reused")
        append(" operator=").append(fixture.operatorId)
        append(" id=").append(fixture.id)
        append(" date=").append(fixture.date)
        append(" createdAt=").append(fixture.createdAt)
        append(" contentLen=").append(fixture.content.length)
        append(" countBefore=").append(fixture.countBefore)
        append(" countAfter=").append(fixture.countAfter)
        append(" unreadOperators=").append(fixture.unreadOperators)
        append(" onlyUnread=").append(fixture.wasOnlyUnreadOperator)
    }

    private fun isSortedDesc(items: List<DiaryEntryDto>): Boolean {
        for (index in 1 until items.size) {
            val previous = items[index - 1]
            val current = items[index]
            if (previous.createdAt < current.createdAt) return false
            if (previous.createdAt == current.createdAt && previous.id < current.id) return false
        }
        return true
    }

    private fun todayDate(): String {
        val today = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai"))
        val month = today.monthNumber.toString().padStart(2, '0')
        val day = today.dayOfMonth.toString().padStart(2, '0')
        return "${today.year}-$month-$day"
    }

    private fun summarize(content: String): String {
        val flat = content.replace('\n', ' ').replace('\r', ' ').trim()
            .replace(Regex(" {2,}"), " ")
        return if (flat.length <= SUMMARY_MAX_CHARS) flat else flat.take(SUMMARY_MAX_CHARS) + "…"
    }

    companion object {
        private const val SUMMARY_MAX_CHARS = 96
        private const val SELFTEST_MARKER = "T51自检"
        private const val DEFAULT_SELFTEST_OPERATOR_ID = "amiya"
    }
}

// ---------------------------------------------------------------------------
// T51 DTO（纪律：只用简单类型；普通 class 非 data class）
// ---------------------------------------------------------------------------

/** 日记列表行（`diaries` 一行 + operators 展示字段 + 未读标记）。 */
class DiaryEntryDto(
    val id: Long,
    val operatorId: String,
    /** 干员名（diaries.operatorName，空时回退 operators.name）。 */
    val operatorName: String,
    /** operators.avatarUri 原样（"1".."147" = 内置 Bundle 头像；无则 "" → 首字占位）。 */
    val avatarId: String,
    /** diaries.date（yyyy-MM-dd，北京时间）。 */
    val date: String,
    /** 展示标题（「干员名 · 日期」；表无标题列，UI 派生）。 */
    val title: String,
    /** 摘要（正文压平空白后前 96 字）。 */
    val summary: String,
    val createdAt: Long,
    /** diaries.version（同一天重写递增）。 */
    val version: Int,
    /** 所属干员未读（干员维度，= 最新 createdAt > diary_read_at_<operatorId>）。 */
    val isUnread: Boolean,
)

/** 日记列表结果。 */
class DiaryListDto(
    val items: List<DiaryEntryDto>,
    /** 全表日记条数（未过滤）。 */
    val totalCount: Int,
    /** 未读干员数（= Android getUnreadDiaryCount）。 */
    val unreadCount: Int,
    val unreadOnly: Boolean,
)

/** 日记全文详情。 */
class DiaryDetailDto(
    val id: Long,
    val operatorId: String,
    val operatorName: String,
    val avatarId: String,
    val date: String,
    val title: String,
    /** diaries.content 全文。 */
    val content: String,
    val createdAt: Long,
    val version: Int,
    val isUnread: Boolean,
)
