package com.rhodes.privatechat.shared.iosbridge

/**
 * T29 iOS 窄门面 DTO。
 *
 * 纪律（可行性报告 §5.6 + ios-port/specs/ios-bridge-spec.md）：
 * - 只用简单类型（String / Int / Long / Boolean / Double），不导出 Flow / StateFlow / sealed；
 * - 用普通 class（非 data class）：Swift 侧只做只读字段读取，避免 copy/componentN 等 Kotlin 语义外溢；
 * - 字段名与顺序稳定，改名前先更新 ios-bridge-spec.md 的命名映射表。
 */
class SessionDto(
    val id: String,
    val title: String,
    val avatarId: String,
    val unreadCount: Int,
    val lastMessage: String,
    val lastTime: Long,
    val mode: String,
    /** T40：置顶标记（对齐 Android ChatSessionEntity.isPinned；列表排序 pinned 在前）。 */
    val isPinned: Boolean,
    /** 会话所属角色 id（私聊点头像进角色简介用；群聊为空串）。 */
    val operatorId: String,
)

class MemoryDto(
    val id: Long,
    val kind: String,
    val content: String,
    val createdAt: Long,
)

class ChatResultDto(
    val ok: Boolean,
    val userMessageId: Long,
    val aiMessageId: Long,
    val text: String,
    val error: String,
    /** T37：true = 本轮走了演示通道（未配置模型供应商/无 Key），UI 需明确提示「演示模式」。 */
    val demo: Boolean,
)

class BackupProgressDto(
    /** prepare / copy / verify / staged / done / failed */
    val stage: String,
    /** 0.0 .. 1.0 */
    val progress: Double,
    val message: String,
)

// ---------------------------------------------------------------------------
// T36 角色/关系页（Phase 4 UI 波次一）：真实 operators 表 + relationships 表
// ---------------------------------------------------------------------------

/**
 * 角色 DTO（operators 表一行 + 关联会话/长期印象）。
 *
 * - [avatarId]：operators.avatarUri 原样字符串。内置头像为数字串 "1".."147"，
 *   Swift 侧按数字解析 Bundle 资源（T16 lead 裁决）；用户上传头像走
 *   MediaRepository 相对 ID 语义（`media/<uuid>.<ext>`），本波留占位渲染。
 * - [relationType]：operators.userRelation（用户与该角色的关系，如「朋友」）。
 * - [level]：operators.intimacy（亲密度，Android oracle 同名字段）。
 * - [impression]：该角色最新一条长期印象（memories 表 L3）原文；无则 ""。
 * - [mode] / [sessionId] / [lastMessage] / [lastTime]：来自 chat_sessions；
 *   尚未建会话时 mode="offline"、sessionId=""、lastTime=0。
 */
class RoleDto(
    val id: String,
    val name: String,
    val title: String,
    /** 角色简介。注意：不能命名为 description —— Kotlin/Native 导出 ObjC 时会与
     *  NSObject.description 冲突，Swift 侧读到的是对象描述而非字段值（T36 实测）。
     */
    val intro: String,
    val gender: String,
    val avatarId: String,
    val relationType: String,
    val level: Int,
    val impression: String,
    val mode: String,
    val sessionId: String,
    val location: String,
    val activity: String,
    val emotion: String,
    val lastMessage: String,
    val lastTime: Long,
)

/**
 * 关系网节点 DTO（relationships 表一行）。
 *
 * - [direction]："out" = 该角色对 related 的关系；"in" = related 对该角色的关系。
 * - [type]：RelationshipType 枚举名（FRIEND / RIVAL / FAMILY …，20+ 种，值域同 Android）。
 */
class RelationDto(
    val id: Long,
    val operatorId: String,
    val relatedOperatorId: String,
    val relatedOperatorName: String,
    val type: String,
    val intimacy: Int,
    val note: String,
    val direction: String,
)
