package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSLog

/**
 * 聊天排行榜 / 消费统计桥（T54，真实 SQLite + settings）。
 *
 * 数据/口径对齐 Android oracle：
 * - `RankingScreen.kt:53`（页面结构：昨日排行 / 历史总榜 两个 tab；行 = 排名/头像/名字/消息数/好感）；
 * - `DataViewModel.kt:91-104`：`getMessageRanking()` = `ChatMessages.sq:49 getMessageCountPerSender`
 *   （`isMe = 0 AND senderName != '' AND senderName != '系统' AND sessionId NOT LIKE 'group_%'`，
 *   `ORDER BY cnt DESC`）；`getDailyRanking()` = 同查询 + `timestamp >= 昨日 00:00`（**无上界**，oracle
 *   口径「昨日」实际含今天，本桥原样保留并在页面标注）；
 * - `RankingScreen.kt:92` 空态「暂无数据」；`:35` tabs = `昨日排行 / 历史总榜`；
 * - `SettingsRepository.getInt("intimacy_daily_<operatorId>", 0)`（`RankingScreen.kt:104` 好感列）；
 * - 消费统计 oracle = `TokenStatsScreen.kt:65`：分类固定 6 行（私聊/群聊/记忆/动态/日记/其他），
 *   今日 = `daily_in/out_<category>_<yyyy-MM-dd>`，本周 = 周一~今日逐日累加，全部 = `token_in/out_<category>`；
 *   `formatTokens` = `1.0M` / `1.2K` / 原值。
 *
 * 任务卡追加维度（oracle 无逐角色消费字段 → 近似并在 UI 标注「估算」）：
 * - 互动天数 = 该角色（senderName）在私聊会话中发过消息的**北京时间自然日去重计数**；
 * - 消费 = 该角色消息正文字符数合计（DB 无 per-operator token 字段，故为估算）。
 *
 * 纪律（同 ios-bridge-spec.md）：只导出简单类型 DTO；回调一律 Main 队列；错误经第二参数回传
 * （null = 成功）；桥内不导出 Flow/StateFlow/data class。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "RankingBridge")
class RankingBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    private val tz = TimeZone.of("Asia/Shanghai")

    // ==================================================================
    // 排行榜
    // ==================================================================

    /**
     * 排行榜快照。
     * [dimension] = `messages` / `days` / `consumption`；[range] = `yesterday` / `history`。
     * 失败返回 (null, errorMessage)。
     */
    fun ranking(dimension: String, range: String, onResult: (RankingSnapshotDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val snapshot = rankingSync(dimension, range)
                mainScope.launch { onResult(snapshot, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "排行榜读取失败") }
            }
        }
    }

    /**
     * 消费统计（Token）：[range] = `today` / `week` / `all`（oracle `TokenStatsScreen` 三个 tab）。
     */
    fun tokenStats(range: String, onResult: (TokenStatsDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val dto = tokenStatsSync(range)
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "消费统计读取失败") }
            }
        }
    }

    // ==================================================================
    // 同步实现
    // ==================================================================

    private suspend fun rankingSync(dimension: String, range: String): RankingSnapshotDto {
        val dim = if (dimension in setOf(DIM_MESSAGES, DIM_DAYS, DIM_CONSUMPTION)) dimension else DIM_MESSAGES
        val rng = if (range == RANGE_YESTERDAY) RANGE_YESTERDAY else RANGE_HISTORY
        val now = Clock.System.now().toEpochMilliseconds()
        val todayStart = todayStartMillis(now)
        val since = when (rng) {
            RANGE_YESTERDAY -> todayStart - DAY_MILLIS
            else -> 0L
        }

        val operators = chatRepo.getAllOperatorsSync()
        val byId = operators.associateBy { it.id }
        val byName = operators.associateBy { it.name }

        // 与 oracle SQL 完全同口径：私聊会话中「非本人」发送者的消息。
        val messages = chatRepo.getAllMessagesForBackup().filter { msg ->
            !msg.isMe && msg.senderName.isNotBlank() && msg.senderName != "系统" &&
                !msg.sessionId.startsWith("group_") && msg.timestamp >= since
        }
        val grouped = messages.groupBy { it.senderName }

        val rows = grouped.map { (sender, list) ->
            val op = byId[sender] ?: byName[sender]
            val opId = op?.id ?: sender
            val rawValue: Long = when (dim) {
                DIM_DAYS -> list.map { beijingDay(it.timestamp) }.toSet().size.toLong()
                DIM_CONSUMPTION -> list.sumOf { it.content.length.toLong() }
                else -> list.size.toLong()
            }
            Triple(sender, op, rawValue)
        }.sortedWith(compareByDescending<Triple<String, Operator?, Long>> { it.third }.thenBy { it.first })

        val entries = rows.mapIndexed { index, (sender, op, rawValue) ->
            val opId = op?.id ?: sender
            val intimacyDelta = settingsRepo.getInt("intimacy_daily_$opId", 0)
            RankingEntryDto(
                rank = index + 1,
                operatorId = opId,
                operatorName = op?.name ?: sender,
                senderName = sender,
                avatarId = op?.avatarUri.orEmpty(),
                value = rawValue,
                valueText = formatValue(rawValue, dim),
                unit = unitOf(dim),
                secondaryText = when {
                    intimacyDelta > 0 -> "+$intimacyDelta"
                    else -> intimacyDelta.toString()
                },
            )
        }
        val total = entries.sumOf { it.value }
        val dimensionLabel = when (dim) {
            DIM_DAYS -> "互动天数"
            DIM_CONSUMPTION -> "消费（估算）"
            else -> "消息数"
        }
        val rangeLabel = if (rng == RANGE_YESTERDAY) "昨日" else "历史"
        val note = when (dim) {
            DIM_DAYS -> "按北京时间自然日去重；仅统计私聊中角色的发言"
            DIM_CONSUMPTION -> "估算：按该角色消息字数近似，真实 Token 消耗见「消费统计」"
            else -> if (rng == RANGE_YESTERDAY) "时间范围：昨日 00:00 至今" else "全部历史消息数"
        }
        return RankingSnapshotDto(
            dimension = dim,
            dimensionLabel = dimensionLabel,
            range = rng,
            rangeLabel = rangeLabel,
            estimated = dim == DIM_CONSUMPTION,
            entries = entries,
            totalValue = total,
            generatedAt = now,
            note = note,
        )
    }

    private fun tokenStatsSync(range: String): TokenStatsDto {
        val rng = if (range in setOf(RANGE_TODAY, RANGE_WEEK, RANGE_ALL)) range else RANGE_TODAY
        val now = Clock.System.now().toEpochMilliseconds()
        val todayStart = todayStartMillis(now)
        val todayIso = Instant.fromEpochMilliseconds(todayStart).toLocalDateTime(tz).date.dayOfWeek.isoDayNumber
        val allCategories = TOKEN_CATEGORIES.map { cat ->
            var input = 0
            var output = 0
            when (rng) {
                RANGE_TODAY -> {
                    val key = beijingDay(todayStart)
                    cat.keys.forEach {
                        input += settingsRepo.getDailyInputTokenCount(it, key)
                        output += settingsRepo.getDailyOutputTokenCount(it, key)
                    }
                }
                RANGE_WEEK -> {
                    // oracle：周一 ~ 今天（时间戳按北京时间自然日步进，避免 DST/历法依赖）
                    var dayStart = todayStart - (todayIso - 1) * DAY_MILLIS
                    while (dayStart <= todayStart) {
                        val key = beijingDay(dayStart)
                        cat.keys.forEach {
                            input += settingsRepo.getDailyInputTokenCount(it, key)
                            output += settingsRepo.getDailyOutputTokenCount(it, key)
                        }
                        dayStart += DAY_MILLIS
                    }
                }
                else -> {
                    cat.keys.forEach {
                        input += settingsRepo.getInputTokenCount(it)
                        output += settingsRepo.getOutputTokenCount(it)
                    }
                }
            }
            TokenCategoryDto(name = cat.name, inputTokens = input, outputTokens = output, totalTokens = input + output)
        }
        val totalIn = allCategories.sumOf { it.inputTokens }
        val totalOut = allCategories.sumOf { it.outputTokens }
        return TokenStatsDto(
            range = rng,
            rangeLabel = when (rng) {
                RANGE_WEEK -> "本周"
                RANGE_ALL -> "全部"
                else -> "今日"
            },
            inputTokens = totalIn,
            outputTokens = totalOut,
            totalTokens = totalIn + totalOut,
            categories = allCategories,
            generatedAt = now,
        )
    }

    // ==================================================================
    // 自检（仅 `--ranking-selftest` 由 Swift 自动化触发）
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
                val path = "${MediaDirectory.applicationSupportDirectory()}/t54-ranking-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T54SELFTEST] report write failed: " + (it.message ?: "")) }
                mainScope.launch { runCatching { onDone(text) } }
            }

            try {
                lines += "=== T54 ranking selftest ==="
                val fixture = ensureFixtureSync()
                lines += "fixture ${fixture.line}"

                val history = rankingSync(DIM_MESSAGES, RANGE_HISTORY)
                check("rank.messages.nonEmpty", history.entries.isNotEmpty(), "entries=${history.entries.size}")
                check("rank.sortedDesc", isSortedDesc(history.entries), "first=${history.entries.firstOrNull()?.value} last=${history.entries.lastOrNull()?.value}")
                check("rank.ranksSequential", history.entries.mapIndexed { i, e -> e.rank == i + 1 }.all { it }, "n=${history.entries.size}")
                check(
                    "rank.fields",
                    history.entries.all { it.operatorName.isNotBlank() && it.operatorId.isNotBlank() && it.value >= 0 && it.unit.isNotBlank() },
                    "sample=${history.entries.firstOrNull()?.operatorName}/${history.entries.firstOrNull()?.avatarId}",
                )
                check("rank.notEstimated", !history.estimated, "estimated=${history.estimated} label=${history.dimensionLabel} range=${history.rangeLabel}")

                // DB 核对：桥消息计数 == oracle SQL getMessageCountPerSender
                val dbRank: Map<String, Long> = chatRepo.getMessageCountPerSender().associate { it.senderName to it.cnt }
                val bridgeCounts = history.entries.associate { it.senderName to it.value }
                val dbNames = dbRank.keys
                check(
                    "rank.db.countParity",
                    dbNames.size == history.entries.size &&
                        dbNames.all { bridgeCounts[it] == dbRank[it] },
                    "db=${dbNames.size} bridge=${history.entries.size} dbPairs=${dbRank.entries.take(5).joinToString(",") { "${it.key}:${it.value}" }}",
                )
                lines += "db.rank.totalMessages=" + dbRank.values.sum() + " bridge.total=" + history.totalValue

                val days = rankingSync(DIM_DAYS, RANGE_HISTORY)
                val consumption = rankingSync(DIM_CONSUMPTION, RANGE_HISTORY)
                check("rank.days.nonEmpty", days.entries.isNotEmpty(), "entries=${days.entries.size}")
                check("rank.days.lteMessages", days.entries.all { it.value >= 1 }, "sample=" + days.entries.take(3).joinToString(",") { "${it.operatorName}:${it.value}" })
                check("rank.consumption.estimated", consumption.estimated, "estimated=${consumption.estimated} note=${consumption.note}")
                check(
                    "rank.consumption.chars",
                    consumption.entries.isNotEmpty() && consumption.entries.all { it.value >= 0 },
                    "sample=" + consumption.entries.take(3).joinToString(",") { "${it.operatorName}:${it.value}" },
                )
                check("rank.days.totalLteConsumption", days.totalValue <= consumption.totalValue, "daysTotal=${days.totalValue} charsTotal=${consumption.totalValue}")

                val daily = rankingSync(DIM_MESSAGES, RANGE_YESTERDAY)
                check(
                    "rank.yesterday.lteHistory",
                    daily.entries.all { e -> (history.entries.firstOrNull { it.operatorName == e.operatorName }?.value ?: 0) >= e.value },
                    "yesterdayTotal=${daily.totalValue} historyTotal=${history.totalValue}",
                )
                lines += "rank.yesterday.total=${daily.totalValue} rank.history.total=${history.totalValue}"

                if (fixture.created) {
                    val row = history.entries.firstOrNull { it.operatorId == FIXTURE_OPERATOR_ID }
                    check("fixture.inRanking", row != null, "op=$FIXTURE_OPERATOR_ID")
                    check("fixture.messageCount", row?.value == 3L, "value=${row?.value} expected=3")
                    check("fixture.days", days.entries.firstOrNull { it.operatorId == FIXTURE_OPERATOR_ID }?.value == 3L, "expected 3 distinct days")
                    check("fixture.consumption", (consumption.entries.firstOrNull { it.operatorId == FIXTURE_OPERATOR_ID }?.value ?: 0) >= 3L, "chars=${consumption.entries.firstOrNull { it.operatorId == FIXTURE_OPERATOR_ID }?.value}")
                } else {
                    lines += "SKIP fixture.*（DB 已有真实私聊消息，未写入夹具，断言基于真实数据）"
                }

                // 消费统计（oracle TokenStatsScreen 三分段 + 6 分类）
                for (r in listOf(RANGE_TODAY, RANGE_WEEK, RANGE_ALL)) {
                    val stats = tokenStatsSync(r)
                    check(
                        "token.$r.structure",
                        stats.categories.size == 6 &&
                            stats.categories.all { it.totalTokens == it.inputTokens + it.outputTokens && it.name.isNotBlank() },
                        "categories=${stats.categories.map { it.name + ":" + it.totalTokens }.joinToString(",")}",
                    )
                    check(
                        "token.$r.totals",
                        stats.totalTokens == stats.inputTokens + stats.outputTokens && stats.totalTokens >= 0,
                        "in=${stats.inputTokens} out=${stats.outputTokens} total=${stats.totalTokens} label=${stats.rangeLabel}",
                    )
                }
                val allStats = tokenStatsSync(RANGE_ALL)
                lines += "token.all.total=" + allStats.totalTokens + " categories=" +
                    allStats.categories.joinToString(",") { it.name + "=" + it.totalTokens }
            } catch (t: Throwable) {
                lines += "FATAL ${t.message ?: t::class.simpleName}"
                failures++
            }
            report()
        }
    }

    // ==================================================================
    // 夹具（仅 DB 无任何私聊消息时写入，幂等；供自检/截图）
    // ==================================================================

    private suspend fun ensureFixtureSync(): FixtureInfo {
        val now = Clock.System.now().toEpochMilliseconds()
        val all = chatRepo.getAllMessagesForBackup()
        val hasPrivate = all.any { !it.isMe && !it.sessionId.startsWith("group_") && it.senderName.isNotBlank() && it.senderName != "系统" }
        if (hasPrivate) return FixtureInfo(false, "skip（DB 已有 ${all.size} 条消息，未写夹具）")

        val operators = chatRepo.getAllOperatorsSync()
        if (operators.none { it.id == FIXTURE_OPERATOR_ID }) {
            chatRepo.insertOperator(Operator(id = FIXTURE_OPERATOR_ID, name = FIXTURE_OPERATOR_NAME, avatarUri = "1"))
        }
        val sessionId = "session_$FIXTURE_OPERATOR_ID"
        if (chatRepo.getSession(sessionId) == null) {
            chatRepo.insertSession(
                ChatSession(
                    id = sessionId,
                    operatorId = FIXTURE_OPERATOR_ID,
                    operatorName = FIXTURE_OPERATOR_NAME,
                    mode = "online",
                    lastTime = now,
                ),
            )
        }
        val todayStart = todayStartMillis(now)
        // 3 条干员消息分布在 3 个不同北京时间自然日（今天 / 昨天 / 3 天前）+ 1 条本人消息
        val timestamps = listOf(todayStart + 3_600_000L, todayStart - DAY_MILLIS + 3_600_000L, todayStart - 3 * DAY_MILLIS + 3_600_000L)
        val contents = listOf(
            "T54自检：今天的聊天记录，用于排行榜与消费统计的端到端验证。",
            "T54自检：昨天的聊天记录，验证消息数/互动天数/消费三个维度。",
            "T54自检：前天的聊天记录，保证互动天数为 3。",
        )
        timestamps.forEachIndexed { index, ts ->
            val id = chatRepo.getNextMessageId()
            chatRepo.sendMessage(
                sessionId,
                ChatMessage(
                    id = id,
                    sessionId = sessionId,
                    senderId = FIXTURE_OPERATOR_ID,
                    senderName = FIXTURE_OPERATOR_NAME,
                    content = contents[index],
                    type = "text",
                    timestamp = ts,
                    isMe = false,
                ),
            )
        }
        val userId = chatRepo.getNextMessageId()
        chatRepo.sendMessage(
            sessionId,
            ChatMessage(
                id = userId,
                sessionId = sessionId,
                senderId = "me",
                senderName = "我",
                content = "T54自检：本人消息不应计入干员排行。",
                type = "text",
                timestamp = todayStart + 7_200_000L,
                isMe = true,
            ),
        )
        return FixtureInfo(true, "created op=$FIXTURE_OPERATOR_ID session=$sessionId messages=3")
    }

    // ==================================================================
    // 工具
    // ==================================================================


    private fun todayStartMillis(now: Long): Long {
        val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date
        return date.atStartOfDayIn(tz).toEpochMilliseconds()
    }

    private fun beijingDay(timestamp: Long): String =
        Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(tz).date.toString()

    private fun unitOf(dimension: String): String = when (dimension) {
        DIM_DAYS -> "天"
        DIM_CONSUMPTION -> "字"
        else -> "条"
    }

    private fun formatValue(value: Long, dimension: String): String = when (dimension) {
        DIM_CONSUMPTION -> formatTokens(value)
        else -> value.toString()
    }

    /** 与 oracle `TokenStatsScreen.formatTokens` 同口径。 */
    private fun formatTokens(t: Long): String = when {
        t >= 1_000_000 -> oneDecimal(t / 1_000_000.0) + "M"
        t >= 1_000 -> oneDecimal(t / 1000.0) + "K"
        else -> t.toString()
    }

    private fun oneDecimal(value: Double): String {
        val scaled = (value * 10.0 + 0.5).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    private fun isSortedDesc(entries: List<RankingEntryDto>): Boolean {
        for (i in 1 until entries.size) {
            if (entries[i - 1].value < entries[i].value) return false
        }
        return true
    }

    private data class FixtureInfo(val created: Boolean, val line: String)

    private data class TokenCategorySpec(val keys: List<String>, val name: String)

    companion object {
        const val DIM_MESSAGES = "messages"
        const val DIM_DAYS = "days"
        const val DIM_CONSUMPTION = "consumption"
        const val RANGE_YESTERDAY = "yesterday"
        const val RANGE_HISTORY = "history"
        const val RANGE_TODAY = "today"
        const val RANGE_WEEK = "week"
        const val RANGE_ALL = "all"

        private const val DAY_MILLIS = 86_400_000L
        private const val FIXTURE_OPERATOR_ID = "t54_selftest_op"
        private const val FIXTURE_OPERATOR_NAME = "T54自检干员"

        /** oracle `TokenStatsScreen.kt:65` 6 个显示分类（多存储 key 合并为一行）。 */
        private val TOKEN_CATEGORIES = listOf(
            TokenCategorySpec(listOf("private", "private_analysis", "voice_call", "inner_monologue", "sleep"), "私聊"),
            TokenCategorySpec(listOf("group"), "群聊"),
            TokenCategorySpec(listOf("memory"), "记忆"),
            TokenCategorySpec(listOf("moment", "comment"), "动态"),
            TokenCategorySpec(listOf("diary"), "日记"),
            TokenCategorySpec(listOf("dispatch", "mahjong", "poker"), "其他"),
        )
    }
}

// ======================================================================
// DTO（只导出简单类型；Swift 侧只读字段）
// ======================================================================

/** 排行榜一行（rank 从 1 开始；value 单位由 dimension 决定：条 / 天 / 字）。 */
class RankingEntryDto(
    val rank: Int,
    val operatorId: String,
    val operatorName: String,
    /** chat_messages.senderName 原值（oracle SQL 分组键；用于 DB 核对/兜底展示）。 */
    val senderName: String,
    /** operators.avatarUri 原样（"1".."147" 内置 Bundle 头像）；空 = 首字占位。 */
    val avatarId: String,
    val value: Long,
    val valueText: String,
    val unit: String,
    /** 好感列：`intimacy_daily_<operatorId>`（oracle 同源），带正号。 */
    val secondaryText: String,
)

/** 排行榜快照。[estimated] = true 表示该维度为近似估算（消费 = 消息字符数）。 */
class RankingSnapshotDto(
    val dimension: String,
    val dimensionLabel: String,
    val range: String,
    val rangeLabel: String,
    val estimated: Boolean,
    val entries: List<RankingEntryDto>,
    val totalValue: Long,
    val generatedAt: Long,
    val note: String,
)

/** 消费统计分类（oracle 6 行之一）。 */
class TokenCategoryDto(
    val name: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)

/** 消费统计快照（今日 / 本周 / 全部）。 */
class TokenStatsDto(
    val range: String,
    val rangeLabel: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val categories: List<TokenCategoryDto>,
    val generatedAt: Long,
)
