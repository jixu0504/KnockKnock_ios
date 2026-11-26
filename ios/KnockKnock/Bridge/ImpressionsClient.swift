//
//  ImpressionsClient.swift
//  Swift 侧「大家的印象」包装（T54 / T82）：
//  - Kotlin `ImpressionsBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct / async 方法；
//  - 真实 SQLite：优先 impressions 印象实体表（active+frozen），空表回退 memory_items + 旧版 memories；
//  - 人工裁决 reject/freeze/unfreeze 与证据展开（memoryId|weight|createdAt，≤8 条）走同一桥。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared ImpressionsBridge DTO 一一对应）

struct ImpressionEntryItem: Identifiable, Hashable {
    /// 列表 key：旧版 memories.id 与 memory_items.id 不同域，使用下标前缀保证唯一。
    let id: String
    let operatorId: String
    let operatorName: String
    /// operators.avatarUri 原样；空 = 首字占位。
    let avatarId: String
    let level: String
    /// 长期印象 / 持续了解 / 互动印象。
    let levelLabel: String
    let memoryType: String
    let memoryTypeLabel: String
    let content: String
    /// impressions.value 原文（T83 编辑弹窗初始值；结构化事实只改这一段）。
    let rawValue: String
    /// memory_items.eventTime（「发生于」文本）；空串 = 无。
    let eventTime: String
    let createdAt: Int64
    let updatedAt: Int64
    let sourceLabel: String
    let importance: Int
    let isLegacy: Bool
    /// impressions.id；0 = T54 旧视图条目（无实体行，不能冻结/拒绝/查证据）。
    let impressionId: Int64
    /// 置信度 0..1（旧视图条目为 0）。
    let confidence: Double
    let evidenceCount: Int
    /// active / frozen（rejected 不会出现在列表里）。
    let status: String
    let firstSeenAt: Int64
    let lastConfirmedAt: Int64

    init(dto: ImpressionEntryDto, index: Int) {
        id = "\(index)-\(dto.id)-\(dto.isLegacy ? "legacy" : "item")"
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        avatarId = dto.avatarId
        level = dto.level
        levelLabel = dto.levelLabel
        memoryType = dto.memoryType
        memoryTypeLabel = dto.memoryTypeLabel
        content = dto.content
        rawValue = dto.rawValue
        eventTime = dto.eventTime
        createdAt = dto.createdAt
        updatedAt = dto.updatedAt
        sourceLabel = dto.sourceLabel
        importance = Int(dto.importance)
        isLegacy = dto.isLegacy
        impressionId = dto.impressionId
        confidence = dto.confidence
        evidenceCount = Int(dto.evidenceCount)
        status = dto.status
        firstSeenAt = dto.firstSeenAt
        lastConfirmedAt = dto.lastConfirmedAt
    }

    /// 是否来自 impressions 实体表（决定是否展示置信度/证据/状态与操作菜单）。
    var hasImpressionRow: Bool { impressionId > 0 }

    var isFrozen: Bool { status == "frozen" }

    var statusLabel: String {
        switch status {
        case "active": return "生效中"
        case "frozen": return "已冻结"
        case "rejected": return "已拒绝"
        default: return status
        }
    }

    /// 「置信度 72%」；无实体行返回空串。
    var confidenceText: String {
        return ""
    }

    var evidenceText: String { "证据 \(evidenceCount)" }

    var firstSeenText: String { impressionDate(firstSeenAt) }

    var lastConfirmedText: String { impressionDate(lastConfirmedAt) }

    /// 「首次 07/21 10:30 · 最近确认 07/25 09:00」（缺失的一侧自动省略）。
    var confirmText: String {
        guard hasImpressionRow else { return "" }
        let first = firstSeenText
        let last = lastConfirmedText
        switch (first.isEmpty, last.isEmpty) {
        case (false, false):
            return first == last ? "首次并确认于 \(first)" : "首次 \(first) · 最近确认 \(last)"
        case (false, true):
            return "首次 \(first)"
        case (true, false):
            return "最近确认 \(last)"
        case (true, true):
            return ""
        }
    }

    /// oracle `ImpressionsScreen.impressionDate`：优先「发生于 eventTime」，否则「记录于 MM/dd HH:mm」（北京时间）。
    var dateText: String {
        let trimmed = eventTime.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return "发生于 \(trimmed)" }
        let formatted = impressionDate(createdAt)
        return formatted.isEmpty ? "" : "记录于 \(formatted)"
    }
}

/// 证据链一行：`memory_impression_evidence` 的 memoryId / weight / createdAt + 记忆正文摘要与会话 id。
struct ImpressionEvidenceItem: Identifiable, Hashable {
    let memoryId: Int64
    let weight: Double
    let createdAt: Int64
    /// memory_items.content 摘要（≤80 字）；空串 = 记忆已删除/不可读。
    let summary: String
    /// memory_items.sessionId；空串 = 没有可跳转的会话。
    let sessionId: String

    var id: String { "\(memoryId)-\(createdAt)" }

    var weightText: String { String(format: "%.2f", weight) }

    var dateText: String { impressionDate(createdAt) }

    var summaryText: String { summary.isEmpty ? "记忆正文不可用（可能已删除）" : summary }

    var hasSession: Bool { !sessionId.isEmpty }

    var isGroupSession: Bool { sessionId.hasPrefix("group_") }

    /// 解析桥返回的 `memoryId|weight|createdAt|摘要|sessionId` 行（≤8 条）；坏行直接丢弃。
    static func parse(_ text: String) -> [ImpressionEvidenceItem] {
        text.split(separator: "\n").compactMap { line in
            let parts = line.split(separator: "|", omittingEmptySubsequences: false)
            guard parts.count >= 5,
                  let memoryId = Int64(String(parts[0])),
                  let weight = Double(String(parts[1])),
                  let createdAt = Int64(String(parts[2])) else { return nil }
            return ImpressionEvidenceItem(
                memoryId: memoryId,
                weight: weight,
                createdAt: createdAt,
                summary: String(parts[3]),
                sessionId: String(parts[4])
            )
        }
    }
}

/// 毫秒时间戳 → 北京时间 `MM/dd HH:mm`；0/负值返回空串。
private func impressionDate(_ millis: Int64) -> String {
    guard millis > 0 else { return "" }
    let value = millis > 10_000_000_000 ? millis : millis * 1000
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
    formatter.dateFormat = "MM/dd HH:mm"
    return formatter.string(from: Date(timeIntervalSince1970: Double(value) / 1000))
}

struct ImpressionsSnapshotItem {
    let entries: [ImpressionEntryItem]
    /// 本次快照已加载的条目数（分页时 = 累计到 page 的行数）。
    let totalCount: Int
    /// 全表可见（active + frozen）总数，不受分页影响。
    let total: Int
    /// 还有下一页未加载。
    let hasMore: Bool
    let page: Int
    let pageSize: Int
    let longTermCount: Int
    let sustainedCount: Int
    let interactionCount: Int
    let legacyCount: Int
    /// 已拒绝条数（正常列表不展示；供「撤销拒绝」入口提示）。
    let rejectedCount: Int
    let generatedAt: Int64
    let note: String
    /// impressions = 印象实体表；memory_items = T54 过滤视图回退。
    let dataSource: String

    init(dto: ImpressionsSnapshotDto) {
        entries = ImpressionsSnapshotItem.items(from: dto.entries)
        totalCount = Int(dto.totalCount)
        total = Int(dto.total)
        hasMore = dto.hasMore
        page = Int(dto.page)
        pageSize = Int(dto.pageSize)
        longTermCount = Int(dto.longTermCount)
        sustainedCount = Int(dto.sustainedCount)
        interactionCount = Int(dto.interactionCount)
        legacyCount = Int(dto.legacyCount)
        rejectedCount = Int(dto.rejectedCount)
        generatedAt = dto.generatedAt
        note = dto.note
        dataSource = dto.dataSource
    }

    static func items(from dtos: [ImpressionEntryDto]) -> [ImpressionEntryItem] {
        dtos.enumerated().map { index, item in
            ImpressionEntryItem(dto: item, index: index)
        }
    }

    var usesImpressionTable: Bool { dataSource == "impressions" }

    /// 三段分组（长期印象 / 持续了解 / 互动印象；空组不展示）。
    var sections: [ImpressionSection] {
        [
            ImpressionSection(level: "L3", title: "长期印象", subtitle: "反复互动后形成的稳定了解", entries: entries.filter { $0.level == "L3" }),
            ImpressionSection(level: "L2", title: "持续了解", subtitle: "经多次互动合并出的偏好、约定和关心点", entries: entries.filter { $0.level == "L2" }),
            ImpressionSection(level: "L1", title: "互动印象", subtitle: "偏好、情绪和身体状态会按记录日期保留", entries: entries.filter { $0.level == "L1" }),
        ].filter { !$0.entries.isEmpty }
    }
}

/// 印象三段分组（oracle ImpressionsScreen 三个 section header）。
struct ImpressionSection: Identifiable {
    let level: String
    let title: String
    let subtitle: String
    let entries: [ImpressionEntryItem]

    var id: String { level }
}

// MARK: - AppGraphClient 扩展（大家的印象）

extension AppGraphClient {
    /// 印象快照（oracle 排序：L3 优先 → updatedAt 降序 → importance 降序）。
    /// `page` 从 1 起；返回累计到该页的条目，配合 `hasMore` / `total` 做无限滚动。
    func loadImpressions(page: Int = 1, pageSize: Int = 20) async -> Result<ImpressionsSnapshotItem, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.list(page: Int32(max(1, page)), pageSize: Int32(max(1, pageSize))) { dto, error in
                assertMainQueue("impressions.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(ImpressionsSnapshotItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "印象数据为空")))
                }
            }
        }
    }

    /// 桥层端到端自检（夹具 / 过滤 / 分组 / 排序 / DB 核对）。
    func runImpressionsSelfTest() async -> (report: String, error: BridgeError?) {
        await withCheckedContinuation { continuation in
            impressionsBridge.debugRunSelfTest { report in
                assertMainQueue("impressions.selftest")
                let failed = report.contains("FAIL")
                continuation.resume(returning: (report, failed ? BridgeError(message: "自检失败") : nil))
            }
        }
    }

    // MARK: - T82 人工裁决 + 证据展开

    /// 拒绝某条印象（status=rejected）：成功后该行不再出现在列表里。
    func rejectImpression(id: Int64) async -> Result<Void, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.reject(id: id) { error in
                assertMainQueue("impressions.reject")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    /// 冻结某条印象（status=frozen）：仍展示，但后续整合不再改写。
    func freezeImpression(id: Int64) async -> Result<Void, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.freeze(id: id) { error in
                assertMainQueue("impressions.freeze")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    /// 解冻（status=active）。
    func unfreezeImpression(id: Int64) async -> Result<Void, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.unfreeze(id: id) { error in
                assertMainQueue("impressions.unfreeze")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    /// T83 编辑印象正文（只改 value/updatedAt，桥内写 edit history）。
    func editImpression(id: Int64, value: String) async -> Result<Void, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.edit(id: id, value: value) { error in
                assertMainQueue("impressions.edit")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    /// T83 撤销拒绝（rejected → active，桥内写 undo_reject history）。
    func undoRejectImpression(id: Int64) async -> Result<Void, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.undoReject(id: id) { error in
                assertMainQueue("impressions.undoReject")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    /// T83 已拒绝印象列表（撤销拒绝管理入口；正常列表不含 rejected）。
    func loadRejectedImpressions() async -> Result<[ImpressionEntryItem], BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.rejected { dtos, error in
                assertMainQueue("impressions.rejected")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(ImpressionsSnapshotItem.items(from: dtos ?? [])))
                }
            }
        }
    }

    /// 证据展开：`memory_impression_evidence` 最多 8 条（权重降序）。
    func loadImpressionEvidence(id: Int64) async -> Result<[ImpressionEvidenceItem], BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.evidence(impressionId: id) { text, error in
                assertMainQueue("impressions.evidence")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(ImpressionEvidenceItem.parse(text ?? "")))
                }
            }
        }
    }
}
