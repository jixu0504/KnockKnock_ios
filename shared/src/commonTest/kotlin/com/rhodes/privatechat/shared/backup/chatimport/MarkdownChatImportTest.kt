package com.rhodes.privatechat.shared.backup.chatimport

import com.rhodes.privatechat.shared.db.RhodesDatabase
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T58 Markdown 聊天记录导入：解析器单测（纯逻辑，无 DB）。
 *
 * 覆盖需求 1：RhodesLink md 导出格式、三种变体、markdown 反转义、日期分段、空文件/格式不符报错、
 * 自定义 userName（默认「博士」）。
 */
class MarkdownChatParserTest {

    private val parser = MarkdownChatParser()

    // ---- 1. RhodesLink Markdown 导出格式 ----

    @Test
    fun parsesRhodesMarkdownExport() {
        val md = """
            # 与阿米娅的聊天记录

            导出时间：2025-06-15 22:10

            ## 2025-06-14

            阿米娅：博士，今天的任务辛苦了。

            博士：还好，你呢？

            ## 2025-06-15

            阿米娅：我很好，谢谢博士关心。
        """.trimIndent()

        val doc = parser.parse(md)
        assertEquals("与阿米娅的聊天记录", doc.title)
        assertEquals("阿米娅", doc.operatorName)
        assertEquals("博士", doc.userName)
        assertEquals(3, doc.messages.size)
        assertEquals(2, doc.dayCount)
        assertEquals("rhodes-markdown", doc.sourceFormat)

        val first = doc.messages[0]
        assertEquals("阿米娅", first.senderName)
        assertFalse(first.isMe)
        assertEquals("博士，今天的任务辛苦了。", first.text)

        val second = doc.messages[1]
        assertTrue(second.isMe)
        assertEquals("博士", second.senderName)
        assertEquals("还好，你呢？", second.text)

        // 日期分段：两天时间戳落在各自的 UTC 零点，段内序号递增、且严格递增
        assertEquals(0L, first.timestamp % 86_400_000L)
        assertTrue(second.timestamp > first.timestamp)
        assertTrue(doc.messages[2].timestamp > second.timestamp)
        assertEquals(86_400_000L, doc.messages[2].timestamp - first.timestamp)
    }

    @Test
    fun unescapesMarkdownSafeEscapes() {
        val raw = "真相*只有一个#`代码`_下划线_ [链接] >引用 反斜杠\\结束"
        val escaped = markdownSafe(raw)
        val md = "# 与阿米娅的聊天记录\n\n## 2025-06-15\n\n阿米娅：$escaped\n"
        val doc = parser.parse(md)
        assertEquals(raw, doc.messages.single().text)
    }

    @Test
    fun customUserNameMarksMe() {
        val md = "# 与阿米娅的聊天记录\n\n## 2025-06-15\n\n阿米娅：你好\n\n指挥官：出发吧\n"
        val doc = parser.parse(md, userName = "指挥官")
        assertEquals("指挥官", doc.userName)
        assertFalse(doc.messages[0].isMe)
        assertTrue(doc.messages[1].isMe)
    }

    @Test
    fun blankLinesAndExportTimeIgnored() {
        val md = "# 与阿米娅的聊天记录\n\n\n导出时间：2025-06-15 22:10\n\n\n## 2025-06-15\n\n\n阿米娅：  只有一条  \n\n\n"
        val doc = parser.parse(md)
        assertEquals(1, doc.messages.size)
        assertEquals("只有一条", doc.messages.single().text)
    }

    @Test
    fun multiLineContentIsJoinedToPreviousMessage() {
        val md = "# 与阿米娅的聊天记录\n\n## 2025-06-15\n\n阿米娅：第一行\n第二行没有称呼分隔符\n博士：收到\n"
        val doc = parser.parse(md)
        assertEquals(2, doc.messages.size)
        assertEquals("第一行\n第二行没有称呼分隔符", doc.messages[0].text)
        assertTrue(doc.messages[1].isMe)
    }

    // ---- 2. 变体 ----

    @Test
    fun parsesBoldNameVariant() {
        val md = "# 与阿米娅的聊天记录\n\n**阿米娅**：你好，博士。\n\n**博士**：你好。\n"
        val doc = parser.parse(md)
        assertEquals(2, doc.messages.size)
        assertEquals("阿米娅", doc.messages[0].senderName)
        assertEquals("你好，博士。", doc.messages[0].text)
        assertTrue(doc.messages[1].isMe)
    }

    @Test
    fun parsesPlainColonVariant() {
        val md = "阿米娅: 早上好\n博士：早上好\n阿米娅：今天也是好天气\n"
        val doc = parser.parse(md)
        assertEquals(3, doc.messages.size)
        assertEquals("阿米娅", doc.messages[0].senderName)
        assertEquals("早上好", doc.messages[0].text)
        assertFalse(doc.messages[0].isMe)
        assertTrue(doc.messages[1].isMe)
        // 没有标题时用首个非我方称呼推断角色名
        assertEquals("阿米娅", doc.operatorName)
        assertEquals("plain-colon", doc.sourceFormat)
    }

    @Test
    fun parsesTextTimestampVariant() {
        val md = "与阿米娅的聊天记录\n导出时间：2025-06-15 22:10\n\n[2025-06-15 10:30] 阿米娅：上午好\n[2025-06-15 10:31] 博士：上午好\n[2025-06-16 09:00] 阿米娅：新的一天\n"
        val doc = parser.parse(md)
        assertEquals(3, doc.messages.size)
        assertEquals("text-timestamp", doc.sourceFormat)
        assertEquals("阿米娅", doc.operatorName)
        assertEquals("与阿米娅的聊天记录", doc.title)
        assertTrue(doc.messages[1].isMe)
        // 2025-06-15 10:30 UTC 的段内偏移 = 10.5h
        val first = doc.messages[0].timestamp
        assertEquals(10L * 3_600_000L + 30L * 60_000L, first % 86_400_000L)
        // 次日 09:00：相差 86_400_000 - 10.5h + 9h = 81_000_000ms
        assertEquals(81_000_000L, doc.messages[2].timestamp - first)
    }

    @Test
    fun semicolonAndFullWidthColonBothSupported() {
        val md = "阿米娅：全角\n阿米娅: 半角\n"
        val doc = parser.parse(md)
        assertEquals(2, doc.messages.size)
        assertEquals("全角", doc.messages[0].text)
        assertEquals("半角", doc.messages[1].text)
    }

    // ---- 3. 错误态 ----

    @Test
    fun emptyFileFails() {
        val error = assertFailsWith<MarkdownChatImportException> { parser.parse("   \n\n  ") }
        assertTrue(error.message!!.contains("文件为空"))
    }

    @Test
    fun nonChatTextFails() {
        val error = assertFailsWith<MarkdownChatImportException> {
            parser.parse("# 会议纪要\n\n今天讨论了版本计划。\n")
        }
        assertTrue(error.message!!.contains("没有解析到聊天消息"), "实际：${error.message}")
    }

    @Test
    fun oversizedFileFails() {
        val big = "a".repeat((MarkdownChatImportLimits.MAX_FILE_BYTES + 1).toInt())
        val error = assertFailsWith<MarkdownChatImportException> { parser.parse(big) }
        assertTrue(error.message!!.contains("文件过大"))
    }

    @Test
    fun unescapeIsSinglePassAndKeepsUnknownEscapes() {
        assertEquals("a\\b", MarkdownChatParser.unescapeMarkdown("a\\\\b"))
        assertEquals("a*b", MarkdownChatParser.unescapeMarkdown("a\\*b"))
        assertEquals("\\n", MarkdownChatParser.unescapeMarkdown("\\n"))
        assertEquals("plain", MarkdownChatParser.unescapeMarkdown("plain"))
    }

    /** 镜像 `ReadableChatExporter.markdownSafe`（oracle app/.../ReadableChatExporter.kt:44-52）。 */
    private fun markdownSafe(value: String): String = value
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace(">", "\\>")
}

/**
 * T58 导入执行单测（真实 SQLite 内存库）：会话创建/复用、消息落库顺序与内容、幂等、角色匹配/回退、
 * 消息 id 重分配、未读不增加。JVM/iOS 双路径同一份断言（本抽象类由平台源集提供 `createEnv`）。
 */
abstract class MarkdownChatImportExecutionTest {

    protected abstract fun createEnv(): TestChatImportEnv

    private val parser = MarkdownChatParser()

    private fun sampleMarkdown(operatorName: String = "阿米娅"): String = """
        # 与${operatorName}的聊天记录

        导出时间：2025-06-15 22:10

        ## 2025-06-14

        阿米娅：博士，今天的任务辛苦了。

        博士：还好，你呢？

        ## 2025-06-15

        阿米娅：我很好，谢谢博士关心。
    """.trimIndent()

    private fun seedOperators(database: RhodesDatabase) {
        database.insertTestOperator(mapOperatorRow(
            "amiya", "阿米娅", "近卫", "", "", "", "罗德岛", "休息", "平静",
            0L, "prompt", "", "", "", 10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
        ))
        database.insertTestOperator(mapOperatorRow(
            "kaltsit", "凯尔希", "医生", "", "", "", "罗德岛", "休息", "平静",
            0L, "prompt", "", "", "", 10000L, 0.5, 0.5, "medium", 0.5, "", "", "",
        ))
    }

    private fun envWithRepository(): Pair<TestChatImportEnv, MarkdownChatImporter> {
        val env = createEnv()
        seedOperators(env.database)
        return env to MarkdownChatImporter(TestMarkdownChatImportRepository(env.database))
    }

    @Test
    fun importsNewSessionWithOrderedMessages() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            val doc = parser.parse(sampleMarkdown())
            val outcome = importer.import(doc)
            assertTrue(outcome.success)
            assertEquals("session_amiya", outcome.sessionId)
            assertEquals("amiya", outcome.operatorId)
            assertEquals(3, outcome.insertedMessages)
            assertEquals(0, outcome.skippedDuplicates)
            assertTrue(outcome.createdSession)
            assertTrue(outcome.warning.isEmpty())

            val messages = env.database.readAllMessages()
            assertEquals(3, messages.size)
            // 顺序：timestamp ASC, id ASC
            assertEquals(listOf("博士，今天的任务辛苦了。", "还好，你呢？", "我很好，谢谢博士关心。"), messages.map { it.content })
            assertEquals(listOf(false, true, false), messages.map { it.isMe })
            assertEquals(listOf("阿米娅", "博士", "阿米娅"), messages.map { it.senderName })
            assertTrue(messages.all { it.type == "text" && it.sessionId == "session_amiya" })
            assertTrue(messages.all { it.timestamp > 0L })

            val session = env.database.readAllSessions().single()
            assertEquals("session_amiya", session.id)
            assertEquals("amiya", session.operatorId)
            assertEquals("阿米娅", session.operatorName)
            assertEquals("我很好，谢谢博士关心。", session.lastMessage)
            assertEquals(0, session.unreadCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun repeatedImportIsIdempotentWithWarning() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            val doc = parser.parse(sampleMarkdown())
            val first = importer.import(doc)
            assertEquals(3, first.insertedMessages)
            val second = importer.import(doc)
            assertTrue(second.success)
            assertEquals(0, second.insertedMessages)
            assertEquals(3, second.skippedDuplicates)
            assertTrue(second.warning.contains("已导入过"), "实际 warning=${second.warning}")
            assertFalse(second.createdSession)
            assertEquals(3, env.database.readAllMessages().size, "重复导入不得新增消息")
        } finally {
            env.close()
        }
    }

    @Test
    fun reimportAfterNewMessagesOnlyAddsMissing() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            val doc = parser.parse(sampleMarkdown())
            importer.import(doc)
            val extended = sampleMarkdown() + "\n\n阿米娅：补一条新消息。\n"
            val outcome = importer.import(parser.parse(extended))
            assertEquals(1, outcome.insertedMessages)
            assertEquals(3, outcome.skippedDuplicates)
            assertTrue(outcome.warning.contains("跳过 3 条"))
            assertEquals(4, env.database.readAllMessages().size)
        } finally {
            env.close()
        }
    }

    @Test
    fun explicitTargetOperatorWins() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            val outcome = importer.import(parser.parse(sampleMarkdown()), targetOperatorId = "kaltsit")
            assertEquals("session_kaltsit", outcome.sessionId)
            assertEquals("凯尔希", outcome.operatorName)
            assertTrue(outcome.warning.isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun unmatchedNameFallsBackToFirstOperator() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            val doc = parser.parse(sampleMarkdown(operatorName = "不存在的角色"))
            val outcome = importer.import(doc)
            // 解析出的名字匹配不到任何角色时回退到该库的第一个角色（排序由 SQL 决定，不假定 id）
            val firstOperator = env.database.operatorsQueries
                .getAllOperators(::mapOperatorRow).executeAsList().first()
            assertEquals(firstOperator.id, outcome.operatorId)
            assertEquals("session_${firstOperator.id}", outcome.sessionId)
        } finally {
            env.close()
        }
    }

    @Test
    fun existingSessionIsReusedAndUnreadNotTouched() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            env.database.insertTestSession(ChatSession(
                id = "session_amiya", operatorId = "amiya", operatorName = "阿米娅",
                lastMessage = "旧消息", lastTime = 1L, isPinned = true, unreadCount = 3,
            ))
            val outcome = importer.import(parser.parse(sampleMarkdown()))
            assertFalse(outcome.createdSession)
            val session = env.database.readAllSessions().single()
            assertTrue(session.isPinned, "复用会话必须保留置顶")
            assertEquals(3, session.unreadCount, "导入不得增加/清空未读")
        } finally {
            env.close()
        }
    }

    @Test
    fun messageIdsAllocatedFromGlobalMax() = runBlocking {
        val (env, importer) = envWithRepository()
        try {
            env.database.insertTestSession(ChatSession(id = "other", operatorId = "kaltsit", operatorName = "凯尔希"))
            env.database.insertTestMessage(ChatMessage(
                id = 100L, sessionId = "other", senderName = "凯尔希", content = "历史", isMe = false,
            ))
            val outcome = importer.import(parser.parse(sampleMarkdown()))
            assertEquals(3, outcome.insertedMessages)
            val ids = env.database.readAllMessages().filter { it.sessionId == "session_amiya" }.map { it.id }
            assertEquals(listOf(101L, 102L, 103L), ids)
        } finally {
            env.close()
        }
    }

    @Test
    fun noOperatorsFailsClearly() = runBlocking {
        val env = createEnv()
        try {
            val importer = MarkdownChatImporter(TestMarkdownChatImportRepository(env.database))
            val error = assertFailsWith<MarkdownChatImportException> {
                importer.import(parser.parse(sampleMarkdown()))
            }
            assertTrue(error.message!!.contains("没有可用角色"))
        } finally {
            env.close()
        }
    }
}

/** 基于 RhodesDatabase 的 [MarkdownChatImportRepository] 假实现（与生产适配器同语义）。 */
class TestMarkdownChatImportRepository(private val database: RhodesDatabase) : MarkdownChatImportRepository {

    override suspend fun getAllOperators(): List<Operator> =
        database.operatorsQueries.getAllOperators(::mapOperatorRow).executeAsList()

    override suspend fun getOperator(id: String): Operator? =
        database.operatorsQueries.getOperator(id, ::mapOperatorRow).executeAsOneOrNull()

    override suspend fun getSession(id: String): ChatSession? =
        database.chatSessionsQueries.getSession(id) { id_, operatorId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            ChatSession(id_, operatorId, operatorName, lastMessage, lastTime, mode, isPinned != 0L, unreadCount.toInt(), members, rules, avatarUri, mutedMembers)
        }.executeAsOneOrNull()

    override suspend fun getSessionByOperator(operatorId: String): ChatSession? =
        database.chatSessionsQueries.getSessionByOperator(operatorId, operatorId) { id_, opId, operatorName, lastMessage, lastTime, mode, isPinned, unreadCount, members, rules, avatarUri, mutedMembers ->
            ChatSession(id_, opId, operatorName, lastMessage, lastTime, mode, isPinned != 0L, unreadCount.toInt(), members, rules, avatarUri, mutedMembers)
        }.executeAsOneOrNull()

    override suspend fun getMessages(sessionId: String): List<ChatMessage> =
        database.chatMessagesQueries.getMessages(sessionId) { id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange, timestamp, isMe ->
            ChatMessage(id, sid, senderId, senderName, content, type, mode, emotion, activity, location, narration, segmentGroup, intimacyChange.toInt(), timestamp, isMe != 0L)
        }.executeAsList()

    override suspend fun maxMessageId(): Long? = database.chatMessagesQueries.getMaxId().executeAsOne().MAX

    override suspend fun runImportTransaction(block: RhodesDatabase.() -> Unit) {
        database.transaction { block(database) }
    }
}
