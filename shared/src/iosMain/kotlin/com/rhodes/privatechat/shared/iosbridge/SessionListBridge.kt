package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.settings.SettingsRepository
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 会话列表桥（真实 DB）：`SessionRepository.getAllSessionsSync()`。
 *
 * 回调在 Main 队列触发；返回 [SessionDto] 数组（Swift `[SessionDto]`），
 * 错误经第二参数回传（null = 成功）。
 *
 * T40「会话列表操作对齐 Android 原版」：
 * - 置顶：[pin] / [togglePin] → `SessionRepository.updatePinned`（Android `SessionViewModel.pinSession`）；
 * - 已读：[markRead] / [markAllRead] → `SessionRepository.markSessionRead/markAllRead`
 *   （Android `SessionViewModel.markSessionRead/markAllRead`）；
 * - 未读自增（T44）：由 [ChatEngineBridge] 消费 `EngineEvent.UnreadHint` 时调用
 *   `SessionRepository.incrementUnread`（Android `ChatViewModel.markUnreadIfNotCurrent`），
 *   「当前查看会话」由 [ChatEngineBridge.setActiveSession] 告知；本桥只做已读清零/列表读取。
 * - 从主页移除：[hide] / [hideAll] 写 `settings.hiddenIds`（Android `MainViewModel.hideSession` /
 *   `SessionViewModel.clearAllMessages`），列表读取时过滤，DB 数据与消息保留；
 * - 收到新消息后自动恢复：[unhide] 由 [ChatEngineBridge] 在 `EngineEvent.UnhideSession` 时调用
 *   （Android `MainViewModel.unhideSession` 的等价语义）；
 * - 列表顺序：SQL `ORDER BY isPinned DESC, lastTime DESC`（与 Android 一致），hiddenIds 过滤后返回。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "SessionListBridge")
class SessionListBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    fun list(onResult: (List<SessionDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val all = chatRepo.sessions.getAllSessionsSync()
                // 与 Android MainViewModel:654 一致：清掉已不存在会话的失效 hiddenIds。
                val existingIds = all.mapTo(mutableSetOf()) { it.id }
                val hidden = settingsRepo.hiddenIds
                val validHidden = hidden.filterTo(mutableSetOf()) { it in existingIds }
                if (validHidden != hidden) settingsRepo.hiddenIds = validHidden
                val items = all.filter { it.id !in validHidden }.map { it.toDto() }
                mainScope.launch { onResult(items, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取会话列表失败") }
            }
        }
    }

    /** 单会话查询（demo/诊断用；不存在返回 null）。 */
    fun find(sessionId: String, onResult: (SessionDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val item = chatRepo.sessions.getSession(sessionId)?.toDto()
                mainScope.launch { onResult(item, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取会话失败") }
            }
        }
    }

    // MARK: - T40 会话操作（回调式：onDone(error)，null = 成功）

    /** 置顶 / 取消置顶（Android `SessionViewModel.pinSession` → `SessionRepository.updatePinned`）。 */
    fun pin(sessionId: String, pinned: Boolean, onDone: (String?) -> Unit) = mutate(onDone) {
        chatRepo.sessions.updatePinned(sessionId, pinned)
    }

    /** 切换置顶（读当前 isPinned 后取反，等价 Android `pinSession`）。 */
    fun togglePin(sessionId: String, onDone: (String?) -> Unit) = mutate(onDone) {
        val session = chatRepo.sessions.getSession(sessionId)
            ?: throw IllegalStateException("会话不存在：$sessionId")
        chatRepo.sessions.updatePinned(sessionId, !session.isPinned)
    }

    /** 单会话标记已读（Android `SessionViewModel.markSessionRead`）。 */
    fun markRead(sessionId: String, onDone: (String?) -> Unit) = mutate(onDone) {
        chatRepo.sessions.markSessionRead(sessionId)
    }

    /** 全部标记已读（Android `SessionViewModel.markAllRead`，直接执行、无确认）。 */
    fun markAllRead(onDone: (String?) -> Unit) = mutate(onDone) {
        chatRepo.sessions.markAllRead()
    }

    /**
     * T55 切换聊天模式（Android `ChatViewModel.setMode` → `SessionRepository.updateSessionMode`）：
     * - 写 `chat_sessions.mode`（`online` / `offline` / `director`）；
     * - 同步写 `settings.last_mode_<operatorId>`（oracle `settings.putLastMode`）；
     * - 下一轮 [ChatEngineBridge.send] 每次重新解析会话并以其 `mode` 作为
     *   `ChatEngine.sendMessage(mode=…)`，提示词按 T18 三套模板（TAG_PRIVATE_ONLINE/OFFLINE/DIRECTOR）
     *   + 旁白协议（离线/导演含 `【旁白硬规则】`，线上不含）即时生效。
     */
    fun updateMode(sessionId: String, mode: String, onDone: (String?) -> Unit) = mutate(onDone) {
        val normalized = mode.trim().lowercase()
        require(normalized in CHAT_MODES) { "不支持的聊天模式：$mode" }
        val session = chatRepo.sessions.getSession(sessionId)
            ?: throw IllegalStateException("会话不存在：$sessionId")
        if (session.mode != normalized) {
            chatRepo.sessions.updateSessionMode(sessionId, normalized)
            settingsRepo.putLastMode(session.operatorId, normalized)
        }
    }

    /**
     * 从主页移除单个会话（Android `MainViewModel.hideSession`）：只写 `settings.hiddenIds`，
     * 不删会话 / 不删消息；收到新消息后由 [unhide] 自动恢复。
     */
    fun hide(sessionId: String, onDone: (String?) -> Unit) = mutate(onDone) {
        val hidden = settingsRepo.hiddenIds.toMutableSet()
        if (hidden.add(sessionId)) settingsRepo.hiddenIds = hidden
    }

    /** 移除主页所有会话（Android `SessionViewModel.clearAllMessages`）：hiddenIds 并入全部会话 id。 */
    fun hideAll(onDone: (String?) -> Unit) = mutate(onDone) {
        val hidden = settingsRepo.hiddenIds.toMutableSet()
        chatRepo.sessions.getAllSessionsSync().forEach { hidden.add(it.id) }
        settingsRepo.hiddenIds = hidden
    }

    /**
     * 取消隐藏（引擎事件 `EngineEvent.UnhideSession` 触发；Android `MainViewModel.unhideSession`）。
     * 由 [ChatEngineBridge.onEngineEvent] 在 Default 线程调用，Settings 写入线程安全。
     */
    internal fun unhide(sessionId: String) {
        val hidden = settingsRepo.hiddenIds
        if (sessionId in hidden) settingsRepo.hiddenIds = hidden - sessionId
    }

    private fun mutate(onDone: (String?) -> Unit, block: suspend () -> Unit) {
        workScope.launch {
            try {
                block()
                mainScope.launch { onDone(null) }
            } catch (t: Throwable) {
                mainScope.launch { onDone(t.message ?: "会话操作失败") }
            }
        }
    }

    /**
     * 保证至少有一个可在演示中发送的私聊会话：
     * 1) DB 已有会话 → 返回最新一条；
     * 2) 否则播种预设角色（`ensurePresetOperators`）并为第一位角色 `getOrCreateSession`。
     */
    internal suspend fun ensureSession(): ChatSession? {
        chatRepo.sessions.getAllSessionsSync().firstOrNull()?.let { return it }
        chatRepo.operators.ensurePresetOperators()
        val operators = chatRepo.operators.getAllOperatorsSync()
        val operator = operators.firstOrNull { it.id == "amiya" } ?: operators.firstOrNull() ?: return null
        return chatRepo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)
    }
}

internal fun ChatSession.toDto(): SessionDto = SessionDto(
    id = id,
    title = operatorName.ifBlank { operatorId },
    avatarId = avatarUri,
    unreadCount = unreadCount,
    lastMessage = lastMessage,
    lastTime = lastTime,
    mode = mode,
    isPinned = isPinned,
)

/** T55：私聊三模式（与 Android `ChatViewModel.setMode` / `PromptTemplates` 口径一致）。 */
internal val CHAT_MODES: Set<String> = setOf("online", "offline", "director")
