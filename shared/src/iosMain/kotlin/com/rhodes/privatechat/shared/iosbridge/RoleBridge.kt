package com.rhodes.privatechat.shared.iosbridge

import com.rhodes.privatechat.shared.data.ChatRepository
import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 角色 / 关系网桥（真实 DB）：`operators` + `chat_sessions` + `relationships` + `memories`。
 *
 * T36（Phase 4 UI 波次一）交付：角色 Tab 列表、角色详情（资料/关系/印象/等级）、
 * 「开始聊天」（创建或复用会话）全部走真实 SQLite。
 *
 * 纪律（同 ios-bridge-spec.md）：
 * - 只导出简单类型 DTO（[RoleDto] / [RelationDto]，见 IosDtos.kt），无 Flow / sealed；
 * - 回调一律在 Main 队列触发；错误经第二参数回传（null = 成功）；
 * - 内置头像值域保持 operators.avatarUri 原样（数字串 "1".."147"），Bundle 解析在 Swift 侧。
 *
 * 数据语义对齐 Android oracle：
 * - 角色列表顺序 = `getAllOperators`（ORDER BY name ASC，与 Android 联系人页一致）；
 * - 会话选取：优先稳定 id `session_<operatorId>`，否则取 lastTime 最新的一条；
 * - 印象 = 最新一条长期印象（MemoryRepository.getAllLongTermImpressions，L3）原文；
 * - 关系 = relationships 表双向（out + in），同 relatedOperatorId 时 out 优先去重。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "RoleBridge")
class RoleBridge internal constructor(
    private val chatRepo: ChatRepository,
    private val workScope: CoroutineScope,
    private val mainScope: CoroutineScope,
) {
    /**
     * 角色列表（真实 operators 表）。
     *
     * 首次调用会幂等补齐预设角色（`ensurePresetOperators`，与 T33 bootstrap 同语义），
     * 保证空库也有真实角色可展示；用户自建角色不受影响。
     */
    fun list(onResult: (List<RoleDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val index = loadIndex()
                val items = index.operators.map { it.toRoleDto(index) }
                mainScope.launch { onResult(items, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取角色列表失败") }
            }
        }
    }

    /**
     * 角色详情（按 operatorId 或 sessionId 查找）。
     *
     * 兼容 T33 深链 `rhodes://role/{sessionId}`：先按 operatorId 命中；未命中则把
     * roleId 当作 sessionId 反查会话再取 operatorId（旧深链仍然可用）。
     */
    fun find(roleId: String, onResult: (RoleDto?, String?) -> Unit) {
        workScope.launch {
            try {
                val index = loadIndex()
                val operator = index.operators.firstOrNull { it.id == roleId }
                    ?: chatRepo.sessions.getSession(roleId)?.let { session ->
                        index.operators.firstOrNull { it.id == session.operatorId }
                    }
                mainScope.launch { onResult(operator?.toRoleDto(index), null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "读取角色失败") }
            }
        }
    }

    /** 关系网（relationships 双向合并；无关系返回空列表 + null 错误）。 */
    fun relations(operatorId: String, onResult: (List<RelationDto>, String?) -> Unit) {
        workScope.launch {
            try {
                val out = chatRepo.relationships.getRelationships(operatorId).map { rel ->
                    RelationDto(
                        id = rel.id,
                        operatorId = rel.operatorId,
                        relatedOperatorId = rel.relatedOperatorId,
                        relatedOperatorName = rel.relatedOperatorName,
                        type = rel.type.name,
                        intimacy = rel.intimacy,
                        note = rel.note,
                        direction = "out",
                    )
                }
                val outIds = out.mapTo(mutableSetOf()) { it.relatedOperatorId }
                val reverse = chatRepo.relationships.getReverseRelationships(operatorId)
                    .filter { it.relatedOperatorId !in outIds && it.operatorId !in outIds }
                    .map { rel ->
                        RelationDto(
                            id = rel.id,
                            operatorId = rel.operatorId,
                            relatedOperatorId = rel.operatorId,
                            relatedOperatorName = rel.relatedOperatorName.ifBlank { rel.operatorId },
                            type = rel.type.name,
                            intimacy = rel.intimacy,
                            note = rel.note,
                            direction = "in",
                        )
                    }
                mainScope.launch { onResult(out + reverse, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(emptyList(), t.message ?: "读取关系网失败") }
            }
        }
    }

    /**
     * 开始聊天：复用稳定会话 `session_<operatorId>`，不存在则创建（SessionRepository
     * .getOrCreateSession 语义，与 Android 会话列表点角色进私聊一致）。
     * 回调返回 sessionId（null + error = 失败）。
     */
    fun startChat(operatorId: String, onResult: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                val operator = chatRepo.operators.getOperator(operatorId)
                    ?: throw IllegalStateException("角色不存在：$operatorId")
                val session = chatRepo.getOrCreateSession(operator.id, operator.name, operator.avatarUri)
                mainScope.launch { onResult(session.id, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "创建会话失败") }
            }
        }
    }

    /**
     * 测试专用：创建一条独立会话（不改变用户「+」入口语义）。
     * 仅由 `--memory-e2e` 启动参数调用，用于跨会话记忆/晋升测试。
     */
    fun createTestSession(operatorId: String, sessionId: String, onResult: (String?, String?) -> Unit) {
        workScope.launch {
            try {
                val operator = chatRepo.operators.getOperator(operatorId)
                    ?: throw IllegalStateException("角色不存在：$operatorId")
                chatRepo.insertSession(
                    ChatSession(
                        id = sessionId,
                        operatorId = operator.id,
                        operatorName = operator.name,
                        avatarUri = operator.avatarUri,
                    ),
                )
                mainScope.launch { onResult(sessionId, null) }
            } catch (t: Throwable) {
                mainScope.launch { onResult(null, t.message ?: "创建测试会话失败") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部：一次性载入 operators / sessions / 印象，避免 N+1 查询
    // ------------------------------------------------------------------

    private class RoleIndex(
        val operators: List<Operator>,
        val sessions: Map<String, ChatSession>,
        val impressions: Map<String, String>,
    )

    private suspend fun loadIndex(): RoleIndex {
        chatRepo.operators.ensurePresetOperators()
        val operators = chatRepo.operators.getAllOperatorsSync()
        val sessions = chatRepo.sessions.getAllSessionsSync()
        val impressions = chatRepo.memories.getAllLongTermImpressions()
            .groupBy { it.operatorId }
            .mapValues { (_, list) -> list.maxByOrNull { it.createdAt }?.content ?: "" }
        return RoleIndex(operators, pickSessions(sessions), impressions)
    }

    /** 每个 operator 只保留一个用于列表展示的会话：稳定 id 优先，其次 lastTime 最新。 */
    private fun pickSessions(sessions: List<ChatSession>): Map<String, ChatSession> {
        val picked = LinkedHashMap<String, ChatSession>()
        for (session in sessions) {
            val stableId = "session_${session.operatorId}"
            val current = picked[session.operatorId]
            when {
                current == null -> picked[session.operatorId] = session
                session.id == stableId -> picked[session.operatorId] = session
                current.id != stableId && session.lastTime > current.lastTime -> picked[session.operatorId] = session
            }
        }
        return picked
    }

    // ------------------------------------------------------------------
    // DTO 映射
    // ------------------------------------------------------------------

    private fun Operator.toRoleDto(index: RoleBridge.RoleIndex): RoleDto {
        val session = index.sessions[id]
        return RoleDto(
            id = id,
            name = name,
            title = title,
            intro = description,
            gender = gender,
            avatarId = avatarUri,
            relationType = userRelation,
            level = intimacy,
            impression = index.impressions[id].orEmpty(),
            mode = session?.mode ?: "offline",
            sessionId = session?.id ?: "",
            location = location,
            activity = activity,
            emotion = emotion,
            lastMessage = session?.lastMessage ?: "",
            lastTime = session?.lastTime ?: 0L,
        )
    }
}
