package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator

/**
 * T58：Markdown 聊天记录导入。
 *
 * 场景：Android 端「数据与备份 → 导出聊天记录」在部分系统上以可读文本保存（`ReadableChatExporter`，
 * oracle `app/.../data/backup/ReadableChatExporter.kt:12-27`），用户在 iOS 侧手上只有 `.md` / `.txt`
 * 文件。本文件把这份可读文本解析回结构化消息并写入真实 DB（不经过网络/模型/记忆管线）。
 *
 * 支持格式（白名单）：
 *  1. RhodesLink Markdown 导出（oracle `ReadableChatExporter.markdown`）：
 *     `# 与<角色名>的聊天记录` + `导出时间：yyyy-MM-dd HH:mm` + `## yyyy-MM-dd` 日期分段 +
 *     `<称呼>：<内容>` 行（内容经过 markdownSafe 转义：`\\ \` \* \_ \[ \] \# \>`）；
 *  2. 变体 A：`**名字**：内容`（加粗称呼）；
 *  3. 变体 B：`名字: 内容`（半角冒号）；
 *  4. 变体 C：`[yyyy-MM-dd HH:mm] 名字：内容`（oracle `ReadableChatExporter.text`）。
 *
 * DTO 与解析错误语义见 [MarkdownChatDocument] / [MarkdownChatImportException]。
 * 导入执行语义见 [MarkdownChatImporter]：会话匹配 `session_<operatorId>` → 复用或新建；
 * 消息去重键 `(timestamp,isMe,senderName,text)`（重复导入同文件零新增并给出警告）；
 * 单事务写入 + 自检；不触发模型、不写记忆、不增加未读。
 */
class MarkdownChatImportException(message: String) : Exception(message)

/** 解析后的单条消息（isMe 由称呼是否等于 userName 推断）。 */
data class MarkdownChatMessage(
    val senderName: String,
    val isMe: Boolean,
    val text: String,
    /** 日期分段推断（UTC 零点 + 分段内序号分钟）或 text 变体的显式时间；无法推断时 0。 */
    val timestamp: Long,
)

/** 解析结果 DTO（导入执行与 iOS 预览共用）。 */
data class MarkdownChatDocument(
    val title: String,
    val operatorName: String,
    val userName: String,
    val messages: List<MarkdownChatMessage>,
    val dayCount: Int,
    /** `rhodes-markdown` / `bold-name` / `plain-colon` / `text-timestamp` */
    val sourceFormat: String,
)

object MarkdownChatImportLimits {
    /** 文件上限 20MB（与需求「过大 >20MB」一致）。 */
    const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024

    /** 单文件消息软上限（对齐 rhodes-chat v1 的单会话上限）。 */
    const val MAX_MESSAGES: Int = 100_000
}

/**
 * Markdown 解析器：纯函数、无 IO、可单测。
 *
 * @param userName 「我」的称呼（导出器写入的 `settings.userName`，默认「博士」）。
 */
class MarkdownChatParser {
    fun parse(text: String, userName: String = DEFAULT_USER_NAME): MarkdownChatDocument {
        val normalizedUser = userName.ifBlank { DEFAULT_USER_NAME }.trim()
        val raw = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (raw.isBlank()) throw MarkdownChatImportException("文件为空：没有可解析的聊天内容")
        if (raw.encodeToByteArray().size > MarkdownChatImportLimits.MAX_FILE_BYTES) {
            throw MarkdownChatImportException("文件过大：超过 20MB 上限，请拆分后再导入")
        }

        var title = ""
        var operatorName = ""
        var dayStart: Long? = null
        var dayCounter = 0
        var dayCount = 0
        var sawTitle = false
        var sawTextTimestamp = false
        var sawBoldName = false
        var sawPlainColon = false

        val messages = mutableListOf<MarkdownChatMessage>()
        var pendingText: StringBuilder? = null
        var pendingSender = ""
        var pendingIsMe = false
        var pendingTimestamp = 0L

        fun flush() {
            val builder = pendingText ?: return
            val content = builder.toString().trim()
            if (content.isNotEmpty()) {
                messages += MarkdownChatMessage(
                    senderName = pendingSender,
                    isMe = pendingIsMe,
                    text = content,
                    timestamp = pendingTimestamp,
                )
            }
            pendingText = null
        }

        for (rawLine in raw.split('\n')) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 标题：`# 与<角色名>的聊天记录`（导出器 markdownSafe 后的首行）
            if (!sawTitle && trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                flush()
                val heading = unescapeMarkdown(trimmed.removePrefix("# ").trim())
                title = heading
                extractOperatorName(heading)?.let { operatorName = it }
                sawTitle = true
                continue
            }

            // 日期分段：`## yyyy-MM-dd`
            if (trimmed.startsWith("## ")) {
                flush()
                val date = parseDate(unescapeMarkdown(trimmed.removePrefix("## ").trim()))
                if (date != null) {
                    dayStart = date
                    dayCounter = 0
                    dayCount += 1
                }
                continue
            }

            // 元数据行：`导出时间：…` / `导出时间: …`
            if (trimmed.startsWith("导出时间")) continue

            // 变体 C：`[yyyy-MM-dd HH:mm] 名字：内容`
            TEXT_TIMESTAMP_REGEX.matchEntire(trimmed)?.let { match ->
                flush()
                sawTextTimestamp = true
                val (year, month, day, hour, minute) = match.destructured
                pendingTimestamp = timestampOf(year, month, day, hour, minute) ?: 0L
                pendingSender = unescapeMarkdown(match.groupValues[6].trim())
                pendingIsMe = isMeSender(pendingSender, normalizedUser)
                pendingText = StringBuilder(unescapeMarkdown(match.groupValues[7]))
                pendingSender.let { sender ->
                    if (operatorName.isBlank() && !pendingIsMe) operatorName = sender
                }
                continue
            }

            // 变体 A：`**名字**：内容`
            BOLD_NAME_REGEX.matchEntire(trimmed)?.let { match ->
                flush()
                sawBoldName = true
                pendingTimestamp = nextSyntheticTimestamp(dayStart, dayCounter).also {
                    if (dayStart != null) dayCounter += 1
                }
                pendingSender = unescapeMarkdown(match.groupValues[1].trim())
                pendingIsMe = isMeSender(pendingSender, normalizedUser)
                pendingText = StringBuilder(unescapeMarkdown(match.groupValues[2]))
                if (operatorName.isBlank() && !pendingIsMe) operatorName = pendingSender
                continue
            }

            // 变体 B（也是主格式）：`名字：内容` / `名字: 内容`
            PLAIN_COLON_REGEX.matchEntire(trimmed)?.let { match ->
                flush()
                sawPlainColon = true
                pendingTimestamp = nextSyntheticTimestamp(dayStart, dayCounter).also {
                    if (dayStart != null) dayCounter += 1
                }
                pendingSender = unescapeMarkdown(match.groupValues[1].trim())
                pendingIsMe = isMeSender(pendingSender, normalizedUser)
                pendingText = StringBuilder(unescapeMarkdown(match.groupValues[2]))
                if (operatorName.isBlank() && !pendingIsMe) operatorName = pendingSender
                continue
            }

            // 续行（多行消息内容）：并入上一条消息
            val builder = pendingText
            if (builder != null) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(unescapeMarkdown(trimmed))
            }
        }
        flush()

        if (messages.isEmpty()) {
            throw MarkdownChatImportException(
                "没有解析到聊天消息：文件既不是 RhodesLink Markdown 导出（`# 与…的聊天记录` + `称呼：内容`），" +
                    "也不是 `名字: 内容` / `[日期 时间] 名字：内容` 变体",
            )
        }
        if (messages.size > MarkdownChatImportLimits.MAX_MESSAGES) {
            throw MarkdownChatImportException("消息数量超过上限（${MarkdownChatImportLimits.MAX_MESSAGES} 条）")
        }

        val resolvedOperator = operatorName.ifBlank { messages.firstOrNull { !it.isMe }?.senderName.orEmpty() }
        val resolvedTitle = title.ifBlank {
            if (resolvedOperator.isNotBlank()) "与${resolvedOperator}的聊天记录" else "导入的聊天记录"
        }
        val format = when {
            sawTextTimestamp -> "text-timestamp"
            sawBoldName -> "bold-name"
            sawTitle -> "rhodes-markdown"
            sawPlainColon -> "plain-colon"
            else -> "rhodes-markdown"
        }
        return MarkdownChatDocument(
            title = resolvedTitle,
            operatorName = resolvedOperator,
            userName = normalizedUser,
            messages = messages,
            dayCount = dayCount,
            sourceFormat = format,
        )
    }

    private fun extractOperatorName(heading: String): String? {
        TITLE_WITH_OPERATOR_REGEX.matchEntire(heading)?.let { return it.groupValues[1].trim() }
        TITLE_SUFFIX_REGEX.matchEntire(heading)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun isMeSender(sender: String, userName: String): Boolean =
        sender == userName || sender == "我"

    /**
     * 日期分段内没有时间信息：用「UTC 零点 + 段内序号分钟」合成确定时间戳。
     * 确定性保证重复导入同一文件时去重键一致（不依赖导入时刻）。
     */
    private fun nextSyntheticTimestamp(dayStartMillis: Long?, indexInDay: Int): Long =
        if (dayStartMillis == null) 0L else dayStartMillis + indexInDay * 60_000L

    private fun parseDate(value: String): Long? {
        DATE_REGEX.matchEntire(value)?.let { match ->
            val days = daysFromCivil(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            ) ?: return null
            return days * 86_400_000L
        }
        return null
    }

    private fun timestampOf(year: String, month: String, day: String, hour: String, minute: String): Long? {
        val days = daysFromCivil(year.toInt(), month.toInt(), day.toInt()) ?: return null
        val h = hour.toInt()
        val m = minute.toInt()
        if (h !in 0..23 || m !in 0..59) return null
        return days * 86_400_000L + h * 3_600_000L + m * 60_000L
    }

    /** Howard Hinnant `days_from_civil`：1970-01-01 起的天数（UTC），非法日期返回 null。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long? {
        if (month !in 1..12 || day !in 1..31 || year !in 1970..9999) return null
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146_097 + doe - 719_468).toLong()
    }

    companion object {
        const val DEFAULT_USER_NAME: String = "博士"

        private val TITLE_WITH_OPERATOR_REGEX = Regex("^与(.+?)的聊天记录$")
        private val TITLE_SUFFIX_REGEX = Regex("^(.+?)的聊天记录$")
        private val DATE_REGEX = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
        private val TEXT_TIMESTAMP_REGEX =
            Regex("^\\[(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})\\]\\s*(.+?)\\s*[：:]\\s?(.*)$")
        private val BOLD_NAME_REGEX = Regex("^\\*\\*(.+?)\\*\\*\\s*[：:]\\s?(.*)$")
        private val PLAIN_COLON_REGEX = Regex("^([^\\s：:][^：:]{0,39})[：:]\\s?(.*)$")

        /**
         * 反转义 `ReadableChatExporter.markdownSafe`（`\\ \` \* \_ \[ \] \# \>`）。
         * 单遍扫描：仅反斜杠 + 白名单字符的组合被还原，其余字符原样保留。
         */
        fun unescapeMarkdown(value: String): String {
            if ('\\' !in value) return value
            val builder = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                if (ch == '\\' && index + 1 < value.length && value[index + 1] in ESCAPABLE) {
                    builder.append(value[index + 1])
                    index += 2
                } else {
                    builder.append(ch)
                    index += 1
                }
            }
            return builder.toString()
        }

        private val ESCAPABLE = setOf('\\', '`', '*', '_', '[', ']', '#', '>')
    }
}

// ---------------------------------------------------------------------------
// 导入执行
// ---------------------------------------------------------------------------

/** 导入结果（iOS 桥转 DTO 后回传 Swift）。 */
data class MarkdownChatImportOutcome(
    val success: Boolean,
    val sessionId: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    val insertedMessages: Int = 0,
    val skippedDuplicates: Int = 0,
    val totalMessages: Int = 0,
    val createdSession: Boolean = false,
    /** 非空 = 需要 UI 提示的警告（例如重复导入零新增），不是失败。 */
    val warning: String = "",
    val reason: String = "",
)

/** Markdown 导入的仓储依赖面（读 + 单事务写；生产适配 `ChatRepository`，测试用内存库）。 */
interface MarkdownChatImportRepository {
    suspend fun getAllOperators(): List<Operator>
    suspend fun getOperator(id: String): Operator?
    suspend fun getSession(id: String): ChatSession?
    suspend fun getSessionByOperator(operatorId: String): ChatSession?
    suspend fun getMessages(sessionId: String): List<ChatMessage>
    suspend fun maxMessageId(): Long?
    suspend fun runImportTransaction(block: RhodesDatabase.() -> Unit)
}

/** 生产适配器：复用 `ChatRepository` 现有子仓储与恢复事务入口。 */
class ChatRepositoryMarkdownImportAdapter(
    private val repository: ChatRepository,
) : MarkdownChatImportRepository {

    override suspend fun getAllOperators(): List<Operator> = repository.operators.getAllOperatorsSync()

    override suspend fun getOperator(id: String): Operator? = repository.getOperator(id)

    override suspend fun getSession(id: String): ChatSession? = repository.getSession(id)

    override suspend fun getSessionByOperator(operatorId: String): ChatSession? =
        repository.getSessionByOperator(operatorId)

    override suspend fun getMessages(sessionId: String): List<ChatMessage> =
        repository.getMessagesSync(sessionId)

    override suspend fun maxMessageId(): Long? {
        var max: Long? = null
        repository.runRestoreTransaction {
            max = chatMessagesQueries.getMaxId().executeAsOne().MAX
        }
        return max
    }

    override suspend fun runImportTransaction(block: RhodesDatabase.() -> Unit) =
        repository.runRestoreTransaction(block)
}

/**
 * 把 [MarkdownChatDocument] 写入真实 DB。
 *
 * 语义（与 ChatMergeRestorer 的合并导入不同的「可读文本导入」从零设计）：
 *  - 目标角色：显式 `targetOperatorId` → 解析出的角色名（名称精确命中，其次包含）→ 第一个角色；
 *    没有权限/角色时明确报错；
 *  - 会话：`session_<operatorId>`（`SessionRepository.kt:42-43` 的稳定私聊 id）命中或按 operator 命中则复用，
 *    否则新建；复用会保留置顶/未读/成员/规则等现有字段（镜像 `ChatSessions.sq:45 updateLastMessageIfNotNewer`）；
 *  - 幂等：去重键 `(timestamp,isMe,senderName,text)`；重复导入同文件 → 新增 0 条 + warning；
 *  - 消息 id：从 `chat_messages MAX(id)+1` 起严格递增分配（不复用文件中的序号），事务内二次断言防并发；
 *  - 消息内容：纯文本 `type="text"`、`mode="online"`、保留 senderName/时间/isMe；不写 ai_json、不触发模型/记忆/未读；
 *  - 单事务写 + 提交前自检（目标会话消息数 == 导入前 + 计划数），失败抛错回滚。
 */
class MarkdownChatImporter(
    private val repository: MarkdownChatImportRepository,
) {
    suspend fun import(
        document: MarkdownChatDocument,
        targetOperatorId: String? = null,
    ): MarkdownChatImportOutcome {
        if (document.messages.isEmpty()) {
            throw MarkdownChatImportException("没有可导入的消息")
        }
        val operators = repository.getAllOperators()
        if (operators.isEmpty()) {
            throw MarkdownChatImportException("当前没有可用角色，请先在「联系人」中创建角色后再导入")
        }
        val operator = resolveOperator(operators, document, targetOperatorId)
        val canonicalSessionId = "session_${operator.id}"
        val existingSession = repository.getSession(canonicalSessionId)
            ?: repository.getSessionByOperator(operator.id)
        val sessionId = existingSession?.id ?: canonicalSessionId
        val existingMessages = existingSession?.let { repository.getMessages(it.id) }.orEmpty()

        // ---- 幂等去重（事务外读）----
        val existingKeys = existingMessages.mapTo(mutableSetOf()) { dedupKeyOf(it) }
        var nextId = (repository.maxMessageId() ?: 0L) + 1L
        val toInsert = mutableListOf<Pair<MarkdownChatMessage, Long>>()
        var skipped = 0
        for (message in document.messages) {
            val key = dedupKey(message.timestamp, message.isMe, message.senderName, message.text)
            if (!existingKeys.add(key)) {
                skipped += 1
                continue
            }
            toInsert += message to nextId
            nextId += 1
        }
        if (toInsert.isEmpty() && skipped > 0) {
            return MarkdownChatImportOutcome(
                success = true,
                sessionId = sessionId,
                operatorId = operator.id,
                operatorName = operator.name,
                insertedMessages = 0,
                skippedDuplicates = skipped,
                totalMessages = document.messages.size,
                createdSession = false,
                warning = "该聊天记录已导入过：${skipped} 条消息全部命中已有记录，未新增消息",
            )
        }

        val lastInserted = toInsert.lastOrNull()?.first ?: document.messages.last()
        val lastTime = toInsert.lastOrNull()?.first?.timestamp ?: lastInserted.timestamp
        val sessionToWrite = mergeSession(existingSession, sessionId, operator, lastInserted.text, lastTime)
        val firstNewId = toInsert.firstOrNull()?.second
        val expectedCount = existingMessages.size + toInsert.size

        repository.runImportTransaction {
            if (firstNewId != null) {
                val dbMax = chatMessagesQueries.getMaxId().executeAsOne().MAX
                if (dbMax != null && dbMax >= firstNewId) {
                    throw MarkdownChatImportException("消息 ID 分配冲突，请重试")
                }
            }
            chatSessionsQueries.insertSession(
                sessionToWrite.id, sessionToWrite.operatorId, sessionToWrite.operatorName,
                sessionToWrite.lastMessage, sessionToWrite.lastTime, sessionToWrite.mode,
                if (sessionToWrite.isPinned) 1L else 0L, sessionToWrite.unreadCount.toLong(),
                sessionToWrite.members, sessionToWrite.rules, sessionToWrite.avatarUri,
                sessionToWrite.mutedMembers,
            )
            toInsert.forEach { (message, messageId) ->
                chatMessagesQueries.insertMessage(
                    messageId, sessionId, "", message.senderName, message.text, "text", "online",
                    "", "", "", "", "", 0L, message.timestamp, if (message.isMe) 1L else 0L,
                )
            }
            val actualCount = chatMessagesQueries.getMessages(sessionId) { id, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> id }
                .executeAsList().size
            if (actualCount != expectedCount) {
                throw MarkdownChatImportException("Markdown 导入自检失败：消息数量不一致（期望 $expectedCount，实际 $actualCount）")
            }
        }

        return MarkdownChatImportOutcome(
            success = true,
            sessionId = sessionId,
            operatorId = operator.id,
            operatorName = operator.name,
            insertedMessages = toInsert.size,
            skippedDuplicates = skipped,
            totalMessages = document.messages.size,
            createdSession = existingSession == null,
            warning = if (skipped > 0) "跳过 $skipped 条重复消息（已存在相同时间/称呼/内容）" else "",
        )
    }

    private fun resolveOperator(
        operators: List<Operator>,
        document: MarkdownChatDocument,
        targetOperatorId: String?,
    ): Operator {
        if (!targetOperatorId.isNullOrBlank()) {
            return operators.firstOrNull { it.id == targetOperatorId }
                ?: throw MarkdownChatImportException("找不到目标角色（id=$targetOperatorId）")
        }
        val wanted = document.operatorName.trim()
        if (wanted.isNotEmpty()) {
            operators.firstOrNull { it.name.trim() == wanted }?.let { return it }
            operators.firstOrNull { it.name.trim().contains(wanted) || wanted.contains(it.name.trim()) }?.let { return it }
        }
        // 回退：第一个非群聊占位角色
        return operators.firstOrNull { !it.id.startsWith("group_") }
            ?: throw MarkdownChatImportException("找不到可用的目标角色")
    }

    private fun mergeSession(
        existing: ChatSession?,
        sessionId: String,
        operator: Operator,
        lastMessage: String,
        lastTime: Long,
    ): ChatSession {
        if (existing == null) {
            return ChatSession(
                id = sessionId,
                operatorId = operator.id,
                operatorName = operator.name,
                lastMessage = lastMessage.take(200),
                lastTime = lastTime,
                unreadCount = 0,
            )
        }
        val newer = lastTime > existing.lastTime
        return existing.copy(
            lastMessage = if (newer) lastMessage.take(200) else existing.lastMessage,
            lastTime = if (newer) lastTime else existing.lastTime,
        )
    }

    private fun dedupKeyOf(message: ChatMessage): String =
        dedupKey(message.timestamp, message.isMe, message.senderName, message.content)

    private fun dedupKey(timestamp: Long, isMe: Boolean, senderName: String, text: String): String =
        "$timestamp\u0000$isMe\u0000$senderName\u0000$text"
}
