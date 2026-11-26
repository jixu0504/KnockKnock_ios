package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.domain.chat.DefaultReplyCodec
import com.rhodes.privatechat.shared.domain.chat.ImageMessageContract
import com.rhodes.privatechat.shared.domain.chat.PrivateReplyFormat
import com.rhodes.privatechat.shared.domain.chat.VisibleReplyContract
import com.rhodes.privatechat.shared.model.ChatMessage
import com.rhodes.privatechat.shared.model.OfflineModeResponse
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 消息历史桥（T34）：只读分页读取真实 `chat_messages` 表。
 *
 * 与 T19 ChatEngine 的写入格式对齐：
 * - 用户消息 `type=text`：content 即原文；
 * - AI 回复 `type=ai_json`：content 是 OfflineModeResponse JSON，这里复用 commonMain 的
 *   [DefaultReplyCodec] / [VisibleReplyContract] 解析成可见文本 + 旁白，并按段判断撤回；
 * - 图片 `type=image`：content 是 ImageMessageContract JSON，解析 `imageUri` → `imageId`；
 * - 礼物 `type=gift_*`、系统 `type=system` 按最小语义展示。
 *
 * 分页语义（详见 ios-port/specs/message-history-bridge-spec.md）：
 * - `beforeId <= 0` 取首屏（最新 limit 条）；
 * - `beforeId > 0` 取「比该 id 更早」的一页（内部按 (timestamp,id) 游标查询，id 单调）；
 * - 返回 [MessagePageDto]，items 按 id 倒序（最新在前），`nextBeforeId` 是下一页游标；
 * - 多取 1 条判断 `hasMore`，避免额外 COUNT 查询。
 *
 * 线程：查询在 [workScope]（Dispatchers.Default）执行，回调在 [mainScope]（Main）；
 * 不导出 Flow / StateFlow / suspend，Swift 侧包装成 async 函数。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "MessageHistoryBridge")
class MessageHistoryBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    private val codec = DefaultReplyCodec()

    /** 默认页大小（UI 首屏 50）；上限 200 防止一次拉过多。 */
    private val defaultLimit: Int = 50

    /**
     * 分页查询。`onResult(page, error)`：error == null 表示成功。
     * [beforeId] 传 0 表示首屏；否则传上一页返回的 `nextBeforeId`。
     */
    fun page(
        sessionId: String,
        beforeId: Long,
        limit: Int,
        onResult: (MessagePageDto, String?) -> Unit,
    ) {
        val safeLimit = if (limit <= 0) defaultLimit else limit.coerceAtMost(200)
        workScope.launch {
            try {
                val page = loadPage(sessionId, beforeId, safeLimit)
                mainScope.launch { onResult(page, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(MessagePageDto(emptyList(), false, 0L, 0), t.message ?: "读取消息历史失败") }
            }
        }
    }

    private suspend fun loadPage(sessionId: String, beforeId: Long, limit: Int): MessagePageDto {
        // 多取 1 条判断 hasMore（避免 COUNT + 二次查询）。
        val fetch = limit + 1
        val rows: List<ChatMessage> = if (beforeId <= 0L) {
            chatRepo.messages.getRecentMessagesSync(sessionId, fetch.toLong())
        } else {
            // 游标按 (timestamp,id) 查询：先查锚点消息拿 timestamp（id 单调递增，锚点通常存在）。
            val anchorTimestamp = chatRepo.messages.getMessageById(beforeId)?.timestamp
            if (anchorTimestamp != null) {
                chatRepo.messages.getMessagesBefore(sessionId, anchorTimestamp, beforeId, fetch.toLong())
            } else {
                // 锚点已被删除/不存在：退化为最新一页，保证调用方不会陷入死循环。
                chatRepo.messages.getRecentMessagesSync(sessionId, fetch.toLong())
            }
        }
        val hasMore = rows.size > limit
        val pageRows = if (hasMore) rows.subList(0, limit) else rows
        val items = pageRows.map { it.toMessageDto() }
        val totalCount = runCatching {
            chatRepo.messages.countMessages(sessionId).toInt()
        }.getOrDefault(0)
        return MessagePageDto(
            items = items,
            hasMore = hasMore,
            nextBeforeId = items.lastOrNull()?.id ?: 0L,
            totalCount = totalCount,
        )
    }

    /**
     * 单条消息查询（诊断/测试用）。不存在返回 null。
     */
    fun find(messageId: Long, onResult: (MessageDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val dto = chatRepo.messages.getMessageById(messageId)?.toMessageDto()
                mainScope.launch { onResult(dto, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取消息失败") }
            }
        }
    }

    /**
     * 按连续 id 区间读取（T 聊天记录导入定位用）：
     * 导入器从 `MAX(id)+1` 起**连续**分配消息 id（见 MarkdownChatImporter），UI 拿到 `insertedIds`
     * 后可一次取出整块导入记录——它们的 timestamp 通常早于首屏分页窗口（历史时间），
     * 普通 `page(beforeId:)` 需要翻很多页才看得到，导入后「看起来没生效」正是这个原因。
     * - 只返回属于 [sessionId] 且真实存在的行（已删除/id 冲突的跳过），按 id 升序；
     * - [count] 上限 500，避免一次拉过多（导入定位只需要最近一段可滚动窗口）。
     * 回调在 Main 队列；error == null 表示成功。
     */
    fun pageByIdRange(
        sessionId: String,
        fromId: Long,
        count: Int,
        onResult: (List<MessageDto>, String?) -> Unit,
    ) {
        workScope.launch {
            try {
                val safeCount = if (count <= 0) 0 else count.coerceAtMost(500)
                val items = buildList {
                    for (offset in 0 until safeCount) {
                        val message = chatRepo.messages.getMessageById(fromId + offset) ?: continue
                        if (message.sessionId != sessionId) continue
                        add(message.toMessageDto())
                    }
                }
                mainScope.launch { onResult(items, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取导入消息失败") }
            }
        }
    }

    // MARK: - DTO 映射（content 解析）

    private fun ChatMessage.toMessageDto(): MessageDto {
        val parsed = parseContent()
        return MessageDto(
            id = id,
            sessionId = sessionId,
            senderName = senderName,
            isMe = isMe,
            text = parsed.text,
            narration = parsed.narration,
            type = type,
            imageId = parsed.imageId,
            timePayload = timePayload(timestamp),
            timestamp = timestamp,
            mode = mode,
            isRecalled = parsed.isRecalled,
            segments = parsed.segments,
        )
    }

    private class ParsedContent(
        val text: String,
        val narration: String = "",
        val imageId: String = "",
        val isRecalled: Boolean = false,
        /** T59：ai_json 可见段按原始顺序展开（旁白/台词交错），供 UI 一段一气泡渲染。 */
        val segments: List<MessageSegmentDto> = emptyList(),
    )

    private fun ChatMessage.parseContent(): ParsedContent = when (type) {
        "ai_json" -> parseAiJson(content)
        "image" -> {
            val image = ImageMessageContract.parse(content)
            val caption = image?.caption.orEmpty().trim()
            val failed = image?.isSendFailed == true
            ParsedContent(
                text = when {
                    failed -> "[图片发送失败]"
                    caption.isNotEmpty() -> caption
                    else -> "[图片]"
                },
                imageId = image?.imageUri.orEmpty(),
            )
        }
        "gift_hidden" -> ParsedContent(text = "[送出了礼物]")
        "gift_reply_failed" -> ParsedContent(text = "[礼物回复失败]")
        "system" -> ParsedContent(text = content.trim())
        else -> ParsedContent(text = content.trim())
    }

    /**
     * `ai_json` 解析：复用 shared 的 [DefaultReplyCodec]（严格 JSON → 标签脚本 → 文本兜底），
     * 再按 [VisibleReplyContract.sanitizeVisibleSegmentContent] 剥内部状态卡标签；
     * 被撤回的段用 [PrivateReplyFormat.isPrivateSegmentRecalled] 过滤，整条全部撤回 → isRecalled。
     */
    private fun parseAiJson(raw: String): ParsedContent {
        if (raw.isBlank()) return ParsedContent(text = "")
        val parsed = runCatching { codec.normalize(raw) }.getOrNull()
            ?: return ParsedContent(text = raw.take(300))
        val rawSegments = parsed.segments.orEmpty()
        val recalledFlags = rawSegments.indices.map { index ->
            runCatching { PrivateReplyFormat.isPrivateSegmentRecalled(raw, index) }.getOrDefault(false)
        }
        val allRecalled = rawSegments.isNotEmpty() && recalledFlags.all { it }
        // T59：保留原始段索引与交错顺序（撤回/空段过滤后索引不回填），一段一个显示气泡。
        val segments = if (allRecalled) emptyList() else visibleReplySegments(raw, codec, parsed)
        val dialogue = segments.filter { it.type != "narration" }
            .joinToString("\n") { it.text }
        val narration = segments.filter { it.type == "narration" }
            .joinToString("\n") { it.text }
        val text = when {
            allRecalled -> ""
            dialogue.isNotBlank() -> dialogue
            narration.isNotBlank() -> narration
            else -> VisibleReplyContract.replyPreview(parsed).ifBlank { raw.take(300) }
        }
        return ParsedContent(
            text = text,
            narration = narration,
            isRecalled = allRecalled,
            segments = segments,
        )
    }

    private fun timePayload(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return runCatching {
            val local = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
            val now = Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val hour = local.hour.toString().padStart(2, '0')
            val minute = local.minute.toString().padStart(2, '0')
            if (local.date == now.date) "$hour:$minute" else "${local.monthNumber}/${local.dayOfMonth} $hour:$minute"
        }.getOrDefault("")
    }
}

/**
 * 单条消息 DTO（最小展示面 + 解析后的可见文本）。
 * 字段名/顺序稳定；改名前先更新 ios-port/specs/message-history-bridge-spec.md。
 */
class MessageDto(
    val id: Long,
    val sessionId: String,
    val senderName: String,
    val isMe: Boolean,
    /** 可见正文：text=原文；ai_json=台词段拼接；image=附言；gift/system=占位文案。 */
    val text: String,
    /** 旁白（仅 ai_json 线下/导演模式；无则空串）。 */
    val narration: String,
    /** chat_messages.type：text / ai_json / image / gift_hidden / gift_reply_failed / system。 */
    val type: String,
    /** image 消息的本地 imageUri（T31 ImageMessageContract.imageUri），其它为空串。 */
    val imageId: String,
    /** 展示用时间（今天 "HH:mm"，更早 "M/d HH:mm"）；Swift 不重复做时区格式化。 */
    val timePayload: String,
    val timestamp: Long,
    val mode: String,
    /** ai_json 全部段落已撤回时为 true（整条占位展示）。 */
    val isRecalled: Boolean,
    /**
     * T59：ai_json 可见段（按原文交错顺序；撤回/空段已过滤；非 ai_json / 整条撤回为空）。
     * UI 每段一个气泡；单条消息内 `index` 单调递增、作为稳定 id 的段索引。
     */
    val segments: List<MessageSegmentDto> = emptyList(),
)

/**
 * T59：单个可见段（旁白 / 台词）。与 Android `MessageParser.parsePrivateAiJson` 展开口径一致：
 * `id = messageId * 1000 + index` 由 Swift 侧组合，桥只负责段内容与原始段索引。
 */
class MessageSegmentDto(
    /** 原始 segments 数组下标（撤回/空段被过滤后不回填，保证 `recalled` 语义可追溯）。 */
    val index: Int,
    /** `dialogue` = 台词；`narration` = 旁白（已归一化小写）。 */
    val type: String,
    /** 已剥内部状态卡标签的可见文本。 */
    val text: String,
)

/**
 * T59：`ai_json` 原文 → 可见段列表（旁白/台词交错；撤回段与空段过滤；整条全撤回返回空）。
 * 历史桥与流式桥（ChatEngineBridge）共用同一解析口径，避免两处展示不一致。
 */
internal fun visibleReplySegments(
    raw: String,
    codec: DefaultReplyCodec,
    parsedOverride: OfflineModeResponse? = null,
): List<MessageSegmentDto> {
    if (raw.isBlank()) return emptyList()
    val parsed = parsedOverride ?: runCatching { codec.normalize(raw) }.getOrNull() ?: return emptyList()
    val rawSegments = parsed.segments.orEmpty()
    if (rawSegments.isEmpty()) return emptyList()
    val recalledFlags = rawSegments.indices.map { index ->
        runCatching { PrivateReplyFormat.isPrivateSegmentRecalled(raw, index) }.getOrDefault(false)
    }
    if (recalledFlags.all { it }) return emptyList()
    return rawSegments.mapIndexedNotNull { index, segment ->
        if (recalledFlags[index]) return@mapIndexedNotNull null
        val cleaned = VisibleReplyContract.sanitizeVisibleSegmentContent(segment.content)
        if (cleaned.isBlank()) return@mapIndexedNotNull null
        val type = if (segment.type.equals("narration", ignoreCase = true)) "narration" else "dialogue"
        MessageSegmentDto(index = index, type = type, text = cleaned)
    }
}

/**
 * 分页结果 DTO。items 按 id 倒序（最新在前）；`nextBeforeId` 作为下一页 `beforeId`。
 */
class MessagePageDto(
    val items: List<MessageDto>,
    val hasMore: Boolean,
    val nextBeforeId: Long,
    val totalCount: Int,
)
