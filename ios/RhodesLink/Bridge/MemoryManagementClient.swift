//
//  MemoryManagementClient.swift
//  记忆管理（T47）Swift 包装：分页/筛选/详情/编辑/删除/隐私/锚点。
//  Kotlin 类型（MemoryBridge / *Dto）只在本文件内出现，UI 只接触这里的 struct。
//
//  依赖：AppGraphClient.swift 中的 `memoryBridge` 访问器（1 行）。
//

import Foundation
import Shared

// MARK: - Swift 侧模型

/// 记忆列表行（`memory_items` v2 行 + 旧版 `memories` 行的统一视图）。
struct MemoryRecord: Identifiable, Hashable {
    let id: Int64
    /// v2 = shared memory_items；legacy = 旧版 memories 表
    let sourceTable: String
    /// L1 / L2 / L3
    let level: String
    /// SHORT_TERM / DAILY / LONG_TERM（level 的 iOS 域别名）
    let kind: String
    let memoryType: String
    let sourceKind: String
    let ownerType: String
    let ownerId: String
    let ownerName: String
    let content: String
    let importance: Int
    let privacy: String
    let status: String
    let createdAt: Int64
    let updatedAt: Int64
    let expiresAt: Int64
    let sessionId: String
    let sourceRefId: String
    let sourceActor: String

    init(dto: MemoryRecordDto) {
        id = dto.id
        sourceTable = dto.sourceTable
        level = dto.level
        kind = dto.kind
        memoryType = dto.memoryType
        sourceKind = dto.sourceKind
        ownerType = dto.ownerType
        ownerId = dto.ownerId
        ownerName = dto.ownerName
        content = dto.content
        importance = Int(dto.importance)
        privacy = dto.privacy
        status = dto.status
        createdAt = dto.createdAt
        updatedAt = dto.updatedAt
        expiresAt = dto.expiresAt
        sessionId = dto.sessionId
        sourceRefId = dto.sourceRefId
        sourceActor = dto.sourceActor
    }

    /// 跨表唯一身份（List 的 Identifiable 要求全局唯一）。
    var identity: String { "\(sourceTable)-\(id)" }

    var isLegacy: Bool { sourceTable == "legacy" }

    /// 层级标签（短期/日常/长期，规格卡 memory-v2-spec.md:58 命名映射）
    var levelText: String {
        switch level {
        case "L1": return "短期"
        case "L2": return "日常"
        case "L3": return "长期"
        default: return level
        }
    }

    var levelBadgeText: String { "\(level) \(levelText)" }

    /// 类型标签（v2 11 类；旧版固定显示「旧版印象」）
    var typeText: String {
        if isLegacy { return "旧版长期印象" }
        switch memoryType {
        case "preference_expression": return "偏好"
        case "emotion_state": return "情绪"
        case "behavior_state": return "行为状态"
        case "physiological_state": return "身体状态"
        case "agreement_commitment": return "约定"
        case "care_reminder": return "关心提醒"
        case "intent_wish": return "意图愿望"
        case "evaluation_opinion": return "评价观点"
        case "self_cognition_statement": return "自我认知"
        case "external_knowledge": return "外部知识"
        case "event": return "事件"
        default: return memoryType.replacingOccurrences(of: "_", with: " ")
        }
    }

    var sourceText: String {
        switch sourceKind.uppercased() {
        case "PRIVATE_CHAT": return "私聊"
        case "GROUP_CHAT": return "群聊"
        case "MOMENT": return "动态"
        case "MOMENT_COMMENT": return "评论"
        case "DIARY": return "日记"
        case "MANUAL_MEMORY": return "手动添加"
        default: return sourceKind
        }
    }

    var ownerText: String {
        switch ownerType {
        case "operator": return "角色：\(ownerName)"
        case "group": return "群：\(ownerName)"
        case "global": return "公开资料"
        default: return "\(ownerType)：\(ownerName)"
        }
    }

    var privacyText: String {
        switch privacy {
        case "private": return "私密"
        case "shared": return "共享"
        case "public": return "公开"
        case "": return "未知"
        default: return privacy
        }
    }

    var statusText: String {
        switch status {
        case "active": return "正在使用"
        case "archived": return "已归档"
        case "expired": return "已过期"
        default: return status
        }
    }

    /// 旧版 memories 表没有重要度列（0 = 未标注）
    var importanceText: String { importance == 0 && isLegacy ? "未标注" : "\(importance)" }

    var createdAtText: String {
        guard createdAt > 0 else { return "" }
        let seconds = createdAt > 10_000_000_000 ? Double(createdAt) / 1000 : Double(createdAt)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy/M/d HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }
}

/// 列表查询条件（空串 / 0 = 不过滤）。
struct MemoryQuery: Hashable {
    var level: String = ""
    var sourceKind: String = ""
    var keyword: String = ""
    var fromMillis: Int64 = 0
    var toMillis: Int64 = 0
    var status: String = ""
    var sort: String = "newest"

    static let all = MemoryQuery()

    func bridgedDto() -> MemoryQueryDto {
        MemoryQueryDto(
            level: level,
            kind: "",
            sourceKind: sourceKind,
            keyword: keyword,
            fromMillis: fromMillis,
            toMillis: toMillis,
            status: status,
            ownerType: "",
            ownerId: "",
            sort: sort
        )
    }
}

struct MemoryPage {
    let items: [MemoryRecord]
    let total: Int
    let offset: Int
    let limit: Int
    let hasMore: Bool

    init(dto: MemoryPageDto) {
        items = dto.items.map(MemoryRecord.init(dto:))
        total = Int(dto.total)
        offset = Int(dto.offset)
        limit = Int(dto.limit)
        hasMore = dto.hasMore
    }
}

struct MemoryAnchorItem: Identifiable, Hashable {
    let type: String
    let content: String
    let importance: String
    let isPrivate: Bool
    let source: String
    let knownFrom: String
    let createdAt: Int64

    var id: String { "\(type)-\(content)-\(createdAt)" }

    init(dto: MemoryAnchorDto) {
        type = dto.type
        content = dto.content
        importance = dto.importance
        isPrivate = dto.isPrivate
        source = dto.source
        knownFrom = dto.knownFrom
        createdAt = dto.createdAt
    }

    /// 旧版六类锚点中文标签（其余为详情页派生锚点类型）
    var typeText: String {
        switch type.uppercased() {
        case "PLAN": return "计划"
        case "PREFERENCE": return "偏好"
        case "TABOO": return "禁忌"
        case "EVENT": return "事件"
        case "EMOTION": return "情绪"
        case "RELATION": return "关系"
        default: return type
        }
    }

    var importanceText: String {
        switch importance.lowercased() {
        case "strong": return "强"
        case "medium": return "中"
        case "weak": return "弱"
        default: return importance
        }
    }
}

struct MemoryDetail {
    let record: MemoryRecord
    let sourceAnchors: [MemoryAnchorItem]
    let policyAnchors: [MemoryAnchorItem]
    let privacyLines: [String]
    let canEditContent: Bool
    let canEditImportance: Bool
    let canDelete: Bool

    init(dto: MemoryDetailDto) {
        record = MemoryRecord(dto: dto.record)
        sourceAnchors = dto.sourceAnchors.map(MemoryAnchorItem.init(dto:))
        policyAnchors = dto.policyAnchors.map(MemoryAnchorItem.init(dto:))
        privacyLines = dto.privacyLines
        canEditContent = dto.canEditContent
        canEditImportance = dto.canEditImportance
        canDelete = dto.canDelete
    }
}

struct MemoryPrivacySettings {
    let memoryV2Enabled: Bool
    let distinguishPrivateMemory: Bool
    let sourceAwareMemoryEnabled: Bool
    let globalPublicMemoryEnabled: Bool
    let sensitiveWords: [String]
    let policyLines: [String]

    init(dto: MemoryPrivacyDto) {
        memoryV2Enabled = dto.memoryV2Enabled
        distinguishPrivateMemory = dto.distinguishPrivateMemory
        sourceAwareMemoryEnabled = dto.sourceAwareMemoryEnabled
        globalPublicMemoryEnabled = dto.globalPublicMemoryEnabled
        sensitiveWords = dto.sensitiveWords
        policyLines = dto.policyLines
    }
}

struct MemoryMutationResult {
    let ok: Bool
    let id: Int64
    let message: String
}

// MARK: - AppGraphClient 记忆管理扩展（T47）

extension AppGraphClient {
    func loadMemoryPage(offset: Int, limit: Int, query: MemoryQuery) async -> Result<MemoryPage, BridgeError> {
        await withCheckedContinuation { continuation in
            memoryBridge.listPage(offset: Int32(offset), limit: Int32(limit), query: query.bridgedDto()) { page, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let page {
                    continuation.resume(returning: .success(MemoryPage(dto: page)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "记忆列表为空响应")))
                }
            }
        }
    }

    func loadMemoryDetail(id: Int64, sourceTable: String) async -> Result<MemoryDetail, BridgeError> {
        await withCheckedContinuation { continuation in
            memoryBridge.detail(id: id, sourceTable: sourceTable) { detail, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let detail {
                    continuation.resume(returning: .success(MemoryDetail(dto: detail)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "记忆详情为空响应")))
                }
            }
        }
    }

    /// 新建手动记忆（MANUAL_MEMORY；隐私经 MemoryPrivacyPolicy 判定）。
    func createMemory(
        ownerType: String,
        ownerId: String,
        content: String,
        level: String,
        importance: Int,
        explicitPrivate: Bool,
        memoryType: String
    ) async -> Result<MemoryMutationResult, BridgeError> {
        await withCheckedContinuation { continuation in
            memoryBridge.create(
                ownerType: ownerType,
                ownerId: ownerId,
                content: content,
                level: level,
                importance: Int32(importance),
                explicitPrivate: explicitPrivate,
                memoryType: memoryType
            ) { result, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let result {
                    continuation.resume(returning: .success(MemoryMutationResult(ok: result.ok, id: result.id, message: result.message)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "新建记忆失败")))
                }
            }
        }
    }

    /// 保存内容/重要度。`importance < 0` = 不修改重要度。
    func updateMemory(id: Int64, sourceTable: String, content: String, importance: Int) async -> Result<MemoryMutationResult, BridgeError> {
        await withCheckedContinuation { continuation in
            memoryBridge.edit(id: id, sourceTable: sourceTable, content: content, importance: Int32(importance)) { result, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let result {
                    continuation.resume(returning: .success(MemoryMutationResult(ok: result.ok, id: result.id, message: result.message)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "保存记忆失败")))
                }
            }
        }
    }

    func deleteMemory(id: Int64, sourceTable: String) async -> Result<MemoryMutationResult, BridgeError> {
        await withCheckedContinuation { continuation in
            memoryBridge.delete(id: id, sourceTable: sourceTable) { result, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let result {
                    continuation.resume(returning: .success(MemoryMutationResult(ok: result.ok, id: result.id, message: result.message)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "删除记忆失败")))
                }
            }
        }
    }

    func deleteMemories(ids: [Int64], sourceTables: [String]) async -> Result<MemoryMutationResult, BridgeError> {
        await withCheckedContinuation { continuation in
            memoryBridge.deleteBatch(ids: ids.map { KotlinLong(value: $0) }, sourceTables: sourceTables) { result, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let result {
                    continuation.resume(returning: .success(MemoryMutationResult(ok: result.ok, id: result.id, message: result.message)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "批量删除失败")))
                }
            }
        }
    }

    /// 隐私设置读取（NSUserDefaults，同步）。
    func memoryPrivacySettings() -> MemoryPrivacySettings {
        MemoryPrivacySettings(dto: memoryBridge.privacySettings())
    }

    /// 隐私开关写入；key 与 shared SettingsRepository 键名一致。返回 false = 未知键。
    @discardableResult
    func setMemoryPrivacyFlag(key: String, value: Bool) -> Bool {
        memoryBridge.setPrivacyFlag(key: key, value: value)
    }

    /// 敏感词判定（MemoryPrivacyPolicy 18 条）
    func isSensitiveMemoryText(_ text: String) -> Bool {
        memoryBridge.isSensitive(text: text)
    }
}
