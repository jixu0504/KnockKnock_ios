@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalObjCName::class)

package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.backup.chatimport.ChatRepositoryMarkdownImportAdapter
import com.rhodes.privatechat.shared.backup.chatimport.MarkdownChatDocument
import com.rhodes.privatechat.shared.backup.chatimport.MarkdownChatImportException
import com.rhodes.privatechat.shared.backup.chatimport.MarkdownChatImportLimits
import com.rhodes.privatechat.shared.backup.chatimport.MarkdownChatImporter
import com.rhodes.privatechat.shared.backup.chatimport.MarkdownChatParser
import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.files.FileIo
import com.rhodes.privatechat.shared.files.MediaDirectory
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLog

/**
 * T58：Markdown 聊天记录导入桥（iOS 设置 →「数据与备份」）。
 *
 * 能力：
 *  - [parseMarkdownFile] / [parseMarkdownText]：解析 `.md` / `.markdown` / `.txt` 为 [ParsedMarkdownHandle]
 *    （标题/角色名/消息数/日期段数/首尾样例，供 UI 预览；不写库）；
 *  - [importMarkdown]：把解析结果写入真实 DB（目标角色可选），返回新增/去重条数与目标会话 id；
 *  - [debugRunSelfTest]：`--markdown-import-selftest` 端到端自检（写 Documents 夹具 → 解析 → 导入 →
 *    DB 核对条数/顺序/内容/senderName/type/unread → 重复导入零新增 → 清理夹具与自检写入的消息）。
 *
 * 桥约定（同 `ios-port/specs/ios-bridge-spec.md`）：只导出简单类型 DTO；回调一律 Main 队列；
 * 错误经第二参数回传中文原因；Kotlin/Native 侧捕获所有 Throwable，坏文件不崩溃。
 */
@ObjCName(swiftName = "MarkdownChatImportBridge")
class MarkdownChatImportBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
    private val dbPath: String,
) {
    private val parser = MarkdownChatParser()
    private val importer = MarkdownChatImporter(ChatRepositoryMarkdownImportAdapter(chatRepo))

    // ------------------------------------------------------------------ 解析

    /** 解析 Markdown 文件（校验存在/非空/≤20MB）；成功回调 [ParsedMarkdownHandle]。 */
    fun parseMarkdownFile(
        path: String,
        userName: String,
        onDone: (ParsedMarkdownHandle?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                if (!IosFileSupport.exists(path)) throw MarkdownChatImportException("文件不存在或不可读")
                val bytes = IosFileSupport.readBytes(path)
                    ?: throw MarkdownChatImportException("文件读取失败（可能不在本地或无访问权限）")
                if (bytes.isEmpty()) throw MarkdownChatImportException("文件为空")
                if (bytes.size > MarkdownChatImportLimits.MAX_FILE_BYTES) {
                    throw MarkdownChatImportException("文件过大：${bytes.size / (1024 * 1024)}MB，超过 20MB 上限")
                }
                val document = parser.parse(bytes.decodeToString(), userName.ifBlank { defaultUserName() })
                mainScope.launch { onDone(ParsedMarkdownHandle(document), null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t)) }
            }
        }
    }

    /** 直接解析文本（调试/自动化用；同样执行空文件/格式校验）。 */
    fun parseMarkdownText(
        text: String,
        userName: String,
        onDone: (ParsedMarkdownHandle?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val document = parser.parse(text, userName.ifBlank { defaultUserName() })
                mainScope.launch { onDone(ParsedMarkdownHandle(document), null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t)) }
            }
        }
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 导入解析结果。
     * @param targetOperatorId 目标角色 id（空串 = 按解析出的角色名自动匹配 / 回退第一个角色）
     * @param onDone 成功回传 [MarkdownChatImportInfoDto]；失败 error 为中文原因（单事务已回滚）
     */
    fun importMarkdown(
        handle: ParsedMarkdownHandle,
        targetOperatorId: String,
        onDone: (MarkdownChatImportInfoDto?, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                // 导入前记录 MAX(id)：导入器按「MAX(id)+1 起严格递增」分配消息 id（单事务内二次断言），
                // 因此本次新增消息 id = [beforeMaxId+1, beforeMaxId+insertedMessages]，作为连续区间回传 UI。
                // 用途：导入后已打开的私聊页 / 下次进入时能按 id 区间把「时间较早、不在首屏分页窗口内」的
                // 导入记录直接取出来并入时间线（否则用户会以为导入失败——它们被更大的现有时间戳压到历史里）。
                val beforeMaxId = currentMaxMessageId()
                val outcome = importer.import(handle.document, targetOperatorId.ifBlank { null })
                val insertedIds = if (outcome.insertedMessages > 0) {
                    val firstId = (beforeMaxId ?: 0L) + 1L
                    (0 until outcome.insertedMessages).map { firstId + it }
                } else {
                    emptyList()
                }
                val info = MarkdownChatImportInfoDto(
                    sessionId = outcome.sessionId,
                    operatorId = outcome.operatorId,
                    operatorName = outcome.operatorName,
                    insertedMessages = outcome.insertedMessages,
                    skippedDuplicates = outcome.skippedDuplicates,
                    totalMessages = outcome.totalMessages,
                    createdSession = outcome.createdSession,
                    warning = outcome.warning,
                    insertedIds = insertedIds,
                )
                mainScope.launch { onDone(info, null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(null, userMessage(t)) }
            }
        }
    }

    /** 当前 `chat_messages.MAX(id)`（事务内读取，与导入器同一口径；无消息返回 null）。 */
    private suspend fun currentMaxMessageId(): Long? {
        var maxId: Long? = null
        chatRepo.runRestoreTransaction {
            maxId = chatMessagesQueries.getMaxId().executeAsOne().MAX
        }
        return maxId
    }

    /** 当前用户昵称（设置 `user_name`，默认「博士」）——供 UI 传给解析器判定 isMe。 */
    fun currentUserName(): String = defaultUserName()

    private fun defaultUserName(): String = runCatching { settingsRepo.userName }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: MarkdownChatParser.DEFAULT_USER_NAME

    // ------------------------------------------------------------------ 自检（--markdown-import-selftest）

    /**
     * 端到端自检：Documents 写样例 md → 文件解析 → 导入到真实 DB → 逐条核对（条数/顺序/内容/senderName/
     * isMe/type/unread）→ 重复导入零新增 → 清理（删除本次插入消息、恢复/删除会话、删除夹具文件）。
     * 报告写 Application Support/t58-markdown-import-selftest.log 并逐行 NSLog。
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
                NSLog(("[RhodesLink][T58SELFTEST]\n" + text).replace("%", "%%"))
                val path = "${MediaDirectory.applicationSupportDirectory()}/t58-markdown-import-selftest.log"
                runCatching { FileIo.writeBytes(path, (text + "\n").encodeToByteArray()) }
                    .onFailure { NSLog("[RhodesLink][T58SELFTEST] report write failed: " + (it.message ?: "")) }
                mainScope.launch { runCatching { onDone(text) } }
            }

            var fixturePath = ""
            var importedIds = listOf<Long>()
            var sessionIdForCleanup = ""
            var originalSessionJson: String? = null
            var createdSession = false
            try {
                lines += "=== T58 markdown import selftest ==="
                chatRepo.operators.ensurePresetOperators()
                val operators = chatRepo.operators.getAllOperatorsSync()
                check("operators available", operators.isNotEmpty(), "count=${operators.size}")
                val operator = operators.firstOrNull { it.id == "amiya" }
                    ?: operators.firstOrNull { !it.id.startsWith("group_") }
                    ?: throw MarkdownChatImportException("没有角色")
                val userName = defaultUserName()

                // ---- 夹具：RhodesLink md 导出格式（转义 + 日期分段 + 我方/AI 交替 + 多行内容）----
                val escapedContent = markdownSafe("真相*只有一个#`代码`_下划线_ [链接] >引用 反斜杠\\结束")
                val fixture = buildString {
                    appendLine("# 与${operator.name}的聊天记录")
                    appendLine()
                    appendLine("导出时间：2025-06-15 22:10")
                    appendLine()
                    appendLine("## 2025-06-14")
                    appendLine()
                    appendLine("${operator.name}：博士，今天的任务辛苦了。")
                    appendLine()
                    appendLine("$userName：还好，你呢？")
                    appendLine()
                    appendLine("## 2025-06-15")
                    appendLine()
                    appendLine("${operator.name}：$escapedContent")
                    appendLine()
                    appendLine("$userName：收到，注意休息。")
                    appendLine()
                    appendLine("${operator.name}：第一行")
                    appendLine("第二行没有称呼分隔符。")
                }
                val documents = NSHomeDirectory() + "/Documents"
                fixturePath = "$documents/t58-markdown-fixture.md"
                IosFileSupport.ensureParentDirectory(fixturePath)
                check("fixture written", IosFileSupport.writeBytes(fixturePath, fixture.encodeToByteArray()), fixturePath)
                val bytes = IosFileSupport.readBytes(fixturePath) ?: ByteArray(0)
                check("fixture bytes", bytes.isNotEmpty(), "size=${bytes.size}")

                // ---- 解析 ----
                val document = parser.parse(bytes.decodeToString(), userName)
                check("parse title", document.title == "与${operator.name}的聊天记录", document.title)
                check("parse operatorName", document.operatorName == operator.name, document.operatorName)
                check("parse messageCount", document.messages.size == 5, "count=${document.messages.size}")
                check("parse dayCount", document.dayCount == 2, "days=${document.dayCount}")
                check("parse format", document.sourceFormat == "rhodes-markdown", document.sourceFormat)
                check("parse escape", document.messages[2].text == unescapeExpected(escapedContent), document.messages[2].text.take(40))
                check("parse multiline", document.messages[4].text == "第一行\n第二行没有称呼分隔符。", document.messages[4].text)
                check("parse isMe", document.messages[1].isMe && !document.messages[0].isMe)

                // ---- 记忆导入前状态（用于清理/核对）----
                val previousSession = chatRepo.getSession("session_${operator.id}")
                    ?: chatRepo.getSessionByOperator(operator.id)
                sessionIdForCleanup = previousSession?.id ?: "session_${operator.id}"
                createdSession = previousSession == null
                originalSessionJson = previousSession?.let {
                    "${it.id}\u0001${it.operatorId}\u0001${it.operatorName}\u0001${it.lastMessage}\u0001${it.lastTime}" +
                        "\u0001${it.mode}\u0001${it.isPinned}\u0001${it.unreadCount}\u0001${it.members}\u0001${it.rules}" +
                        "\u0001${it.avatarUri}\u0001${it.mutedMembers}"
                }
                val previousMessages = chatRepo.getMessagesSync(sessionIdForCleanup)
                val previousUnread = previousSession?.unreadCount ?: 0
                val preMax = previousMessages.maxOfOrNull { it.id } ?: 0L

                // ---- 导入 ----
                val outcome = importer.import(document, targetOperatorId = operator.id)
                check("import success", outcome.success)
                check("import inserted", outcome.insertedMessages == 5, "inserted=${outcome.insertedMessages}")
                check("import sessionId", outcome.sessionId == sessionIdForCleanup, outcome.sessionId)
                check("import no warning", outcome.warning.isEmpty(), outcome.warning)

                // ---- sqlite 直接核对（chat_messages ORDER BY timestamp ASC, id ASC）----
                val after = chatRepo.getMessagesSync(sessionIdForCleanup)
                val imported = after.filter { it.id > preMax }
                importedIds = imported.map { it.id }
                check("db count delta", after.size == previousMessages.size + 5, "before=${previousMessages.size} after=${after.size}")
                check("db imported count", imported.size == 5, "count=${imported.size}")
                check("db id strict ascending", imported.map { it.id } == imported.map { it.id }.sorted(), imported.map { it.id }.toString())
                check("db order content", imported.map { it.content } == document.messages.map { it.text }, imported.map { it.content }.toString())
                check("db senderName", imported.map { it.senderName } == document.messages.map { it.senderName }, imported.map { it.senderName }.toString())
                check("db isMe", imported.map { it.isMe } == document.messages.map { it.isMe }, imported.map { it.isMe }.toString())
                check("db type text", imported.all { it.type == "text" }, imported.map { it.type }.toString())
                check("db session", imported.all { it.sessionId == outcome.sessionId }, outcome.sessionId)
                val sessionAfter = chatRepo.getSession(sessionIdForCleanup)
                check("db session unread", (sessionAfter?.unreadCount ?: 0) == previousUnread, "unread=${sessionAfter?.unreadCount}")
                check("db session lastMessage", sessionAfter?.lastMessage?.isNotEmpty() == true, sessionAfter?.lastMessage.orEmpty())

                // ---- 重复导入：零新增 + 警告 ----
                val second = importer.import(document, targetOperatorId = operator.id)
                check("re-import inserted 0", second.insertedMessages == 0, "inserted=${second.insertedMessages}")
                check("re-import deduped", second.skippedDuplicates == 5, "skipped=${second.skippedDuplicates}")
                check("re-import warning", second.warning.isNotEmpty(), second.warning)
                val afterSecond = chatRepo.getMessagesSync(sessionIdForCleanup).filter { it.id > preMax }
                check("re-import db unchanged", afterSecond.size == 5, "count=${afterSecond.size}")

                // ---- 证据快照：把运行库（含 -wal/-shm）复制到 Documents/t58-evidence，供宿主 sqlite3 复核 ----
                val evidenceDir = NSHomeDirectory() + "/Documents/t58-evidence"
                IosFileSupport.ensureParentDirectory("$evidenceDir/rhodes.db")
                val copied = listOf("", "-wal", "-shm").count { suffix ->
                    IosFileSupport.copyFile("$dbPath$suffix", "$evidenceDir/rhodes.db$suffix")
                }
                check("db snapshot copied", copied >= 1, "files=$copied dir=$evidenceDir")

                // ---- 清理：删除本次插入消息 + 恢复/删除会话 + 删除夹具 ----
                chatRepo.runRestoreTransaction {
                    importedIds.forEach { id -> chatMessagesQueries.deleteMessage(id) }
                    if (createdSession) {
                        chatSessionsQueries.deleteSession(sessionIdForCleanup)
                    } else {
                        IosSessionRestore.restore(this, originalSessionJson)
                    }
                }
                val cleaned = chatRepo.getMessagesSync(sessionIdForCleanup).filter { it.id > preMax }
                check("cleanup messages", cleaned.isEmpty(), "left=${cleaned.size}")
                if (createdSession) {
                    check("cleanup session", chatRepo.getSession(sessionIdForCleanup) == null)
                }
                check("cleanup fixture", IosFileSupport.deleteIfExists(fixturePath) || !IosFileSupport.exists(fixturePath))
            } catch (t: Throwable) {
                failures++
                lines += "FAIL selftest exception | ${t.message ?: t::class.simpleName}"
                runCatching {
                    if (importedIds.isNotEmpty()) {
                        chatRepo.runRestoreTransaction {
                            importedIds.forEach { id -> chatMessagesQueries.deleteMessage(id) }
                            if (createdSession) chatSessionsQueries.deleteSession(sessionIdForCleanup)
                        }
                    }
                    if (fixturePath.isNotEmpty()) IosFileSupport.deleteIfExists(fixturePath)
                }
            }
            report()
        }
    }

    private fun unescapeExpected(escaped: String): String = MarkdownChatParser.unescapeMarkdown(escaped)

    /** 镜像 `ReadableChatExporter.markdownSafe`（用于自检夹具构造转义内容）。 */
    private fun markdownSafe(value: String): String = value
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace(">", "\\>")

    private fun userMessage(t: Throwable): String =
        (t as? MarkdownChatImportException)?.message
            ?: t.message?.takeIf { it.isNotBlank() }
            ?: "Markdown 聊天记录处理失败"
}

// ---------------------------------------------------------------------------
// DTO / 句柄（普通 class；Swift 侧不接触 Kotlin 内部类型）
// ---------------------------------------------------------------------------

/** 解析成功后的句柄：持有 [MarkdownChatDocument]，同时暴露 UI 预览所需字段。 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@ObjCName(swiftName = "ParsedMarkdownHandle")
class ParsedMarkdownHandle internal constructor(
    internal val document: MarkdownChatDocument,
) {
    val title: String get() = document.title
    val operatorName: String get() = document.operatorName
    val userName: String get() = document.userName
    val messageCount: Int get() = document.messages.size
    val dayCount: Int get() = document.dayCount
    val sourceFormat: String get() = document.sourceFormat

    /** 预览样例：前 2 条 + 后 2 条（不足 4 条时去重）。 */
    val samples: List<MarkdownMessageSampleDto>
        get() {
            val messages = document.messages
            if (messages.isEmpty()) return emptyList()
            val indexes = linkedSetOf<Int>(0, 1, messages.size - 2, messages.size - 1)
            return indexes.filter { it in messages.indices }.map { index ->
                val message = messages[index]
                MarkdownMessageSampleDto(
                    index = index,
                    senderName = message.senderName,
                    isMe = message.isMe,
                    text = message.text.take(120),
                    timestamp = message.timestamp,
                )
            }
        }

    val firstMessageText: String get() = document.messages.firstOrNull()?.text.orEmpty().take(120)
    val lastMessageText: String get() = document.messages.lastOrNull()?.text.orEmpty().take(120)
}

class MarkdownMessageSampleDto(
    val index: Int,
    val senderName: String,
    val isMe: Boolean,
    val text: String,
    val timestamp: Long,
)

class MarkdownChatImportInfoDto(
    val sessionId: String,
    val operatorId: String,
    val operatorName: String,
    val insertedMessages: Int,
    val skippedDuplicates: Int,
    val totalMessages: Int,
    val createdSession: Boolean,
    /** 非空 = 警告（例如重复导入零新增），仍算成功。 */
    val warning: String,
    /**
     * 本次新增消息的 id（严格升序连续，导入器从 MAX(id)+1 起分配；零新增时为空）。
     * UI 用它把导入记录并入已加载时间线 / 下次进入会话时定位到导入块（见 MessageHistoryBridge.pageByIdRange）。
     */
    val insertedIds: List<Long> = emptyList(),
)

/**
 * 自检清理用：从扁平串恢复会话行（避免在事务内引入额外依赖）。
 * 与 [MarkdownChatImportBridge.debugRunSelfTest] 的序列化格式一一对应。
 */
private object IosSessionRestore {
    fun restore(db: com.rhodes.privatechat.shared.db.RhodesDatabase, json: String?) {
        if (json.isNullOrBlank()) return
        val parts = json.split('\u0001')
        if (parts.size < 12) return
        db.chatSessionsQueries.insertSession(
            parts[0], parts[1], parts[2], parts[3], parts[4].toLongOrNull() ?: 0L, parts[5],
            if (parts[6] == "true") 1L else 0L, parts[7].toLongOrNull() ?: 0L,
            parts[8], parts[9], parts[10], parts[11],
        )
    }
}
