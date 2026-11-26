//
//  KnowledgeClient.swift
//  T48 知识库桥的 Swift 包装（独立文件，遵循 ios-bridge-spec 纪律：
//  Kotlin 类型只在本文件内出现，SwiftUI 只接触 struct / async 方法）。
//
//  对应 shared 侧 `KnowledgeBaseBridge`（列表 / 详情 / 创建 / 重命名 / 删除 / 文件导入 /
//  切块预览 / 索引(plan+index) / 重试失败块 / 失效 / 召回测试 / 干员关联 / 状态查询）。
//
//  回调语义：`async -> Result` 或 `async -> String?`（nil = 成功，非 nil = 中文错误）。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared DTO 一一对应）

struct KnowledgeBookItem: Identifiable, Hashable {
    let id: String
    let name: String
    let chunkCount: Int
    let indexedChunkCount: Int
    let failedChunkCount: Int
    let indexStatus: String
    let indexStatusText: String
    let sourceFormat: String
    let sourceFileName: String
    let rawContentLength: Int
    let rawContentPreview: String
    let updatedAt: Int64
    let createdAt: Int64
    let assignedOperatorCount: Int

    init(dto: KnowledgeBaseDto) {
        id = dto.id
        name = dto.name
        chunkCount = Int(dto.chunkCount)
        indexedChunkCount = Int(dto.indexedChunkCount)
        failedChunkCount = Int(dto.failedChunkCount)
        indexStatus = dto.indexStatus
        indexStatusText = dto.indexStatusText
        sourceFormat = dto.sourceFormat
        sourceFileName = dto.sourceFileName
        rawContentLength = Int(dto.rawContentLength)
        rawContentPreview = dto.rawContentPreview
        updatedAt = dto.updatedAt
        createdAt = dto.createdAt
        assignedOperatorCount = Int(dto.assignedOperatorCount)
    }

    /// 已索引（可召回）
    var isIndexed: Bool { indexStatus == "ready" }
    /// 索引进行中（轮询刷新）
    var isIndexing: Bool {
        indexStatus.hasPrefix("indexing") || indexStatus.hasPrefix("partial_indexing") || indexStatus == "processing"
    }
    var hasFailures: Bool { failedChunkCount > 0 || indexStatus == "failed" || indexStatus == "partial_failed" }

    var chunkCountText: String {
        if indexedChunkCount > 0 && indexedChunkCount != chunkCount {
            return "\(indexedChunkCount)/\(chunkCount) 块已索引"
        }
        return "\(chunkCount) 块"
    }

    var updatedAtText: String {
        guard updatedAt > 0 else { return "" }
        let seconds = updatedAt > 10_000_000_000 ? Double(updatedAt) / 1000 : Double(updatedAt)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M/d HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }
}

struct KnowledgeChunkItem: Identifiable, Hashable {
    let id: String
    let ordinal: Int
    let heading: String
    let content: String
    let enabled: Bool
    let indexedAt: Int64
    let indexError: String

    init(dto: KnowledgeChunkDto) {
        id = dto.id
        ordinal = Int(dto.ordinal)
        heading = dto.heading
        content = dto.content
        enabled = dto.enabled
        indexedAt = dto.indexedAt
        indexError = dto.indexError
    }

    var isIndexed: Bool { indexedAt > 0 && indexError.isEmpty }

    var titleText: String {
        heading.isEmpty ? "切块 #\(ordinal)" : heading
    }
}

struct KnowledgeDetailItem {
    let book: KnowledgeBookItem
    let chunks: [KnowledgeChunkItem]
    let totalChunks: Int
    let assignedOperatorIds: [String]
}

struct KnowledgeStatusItem {
    let knowledgeBaseId: String
    let indexStatus: String
    let indexStatusText: String
    let chunkCount: Int
    let indexedChunkCount: Int
    let failedChunkCount: Int
    let updatedAt: Int64

    init(dto: KnowledgeBaseStatusDto) {
        knowledgeBaseId = dto.knowledgeBaseId
        indexStatus = dto.indexStatus
        indexStatusText = dto.indexStatusText
        chunkCount = Int(dto.chunkCount)
        indexedChunkCount = Int(dto.indexedChunkCount)
        failedChunkCount = Int(dto.failedChunkCount)
        updatedAt = dto.updatedAt
    }

    var isIndexing: Bool {
        indexStatus.hasPrefix("indexing") || indexStatus.hasPrefix("partial_indexing") || indexStatus == "processing"
    }
}

struct KnowledgeIndexPlanItem {
    let chunkCount: Int
    let usesRemoteEmbedding: Bool
    let requiresUserConfirmation: Bool

    init(dto: KnowledgeIndexPlanDto) {
        chunkCount = Int(dto.chunkCount)
        usesRemoteEmbedding = dto.usesRemoteEmbedding
        requiresUserConfirmation = dto.requiresUserConfirmation
    }
}

struct KnowledgeIndexResultItem {
    let total: Int
    let succeeded: Int
    let failed: Int
    let errors: [String]
    let statusAfterText: String

    init(dto: KnowledgeIndexResultDto) {
        total = Int(dto.total)
        succeeded = Int(dto.succeeded)
        failed = Int(dto.failed)
        errors = dto.errors
        statusAfterText = dto.statusAfterText
    }

    var summary: String {
        if failed == 0 { return "索引完成：\(succeeded)/\(total) 块（\(statusAfterText)）" }
        return "索引完成：成功 \(succeeded)，失败 \(failed)（\(statusAfterText)）"
    }
}

struct KnowledgeOperatorItem: Identifiable, Hashable {
    let id: String
    let name: String
    let avatarId: String

    init(dto: KnowledgeOperatorDto) {
        id = dto.id
        name = dto.name
        avatarId = dto.avatarId
    }
}

struct KnowledgeRecallHitItem: Identifiable, Hashable {
    let id: String
    let book: String
    let snippet: String
    let score: Double
    let injected: Bool

    init(index: Int, dto: KnowledgeRecallHitDto) {
        id = "\(index)-\(dto.book)-\(dto.snippet.prefix(12))"
        book = dto.book
        snippet = dto.snippet
        score = dto.score
        injected = dto.injected
    }

    var scoreText: String { String(format: "%.3f", score) }
}

struct KnowledgeRecallItem {
    let text: String
    let hits: [KnowledgeRecallHitItem]
    let reason: String
    let reasonText: String
    let assignedBooks: Int

    init(dto: KnowledgeRecallDto) {
        text = dto.text
        hits = dto.hits.enumerated().map { KnowledgeRecallHitItem(index: $0.offset, dto: $0.element) }
        reason = dto.reason
        reasonText = dto.reasonText
        assignedBooks = Int(dto.assignedBooks)
    }

    var isHit: Bool { !hits.isEmpty }
}

// MARK: - AppGraphClient 包装

extension AppGraphClient {
    // MARK: 列表 / 详情 / 状态

    func loadKnowledgeBases() async -> Result<[KnowledgeBookItem], BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.list { dtos, error in
                assertMainQueue("knowledge.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(KnowledgeBookItem.init(dto:))))
                }
            }
        }
    }

    func loadKnowledgeDetail(id: String, chunkLimit: Int = 50) async -> Result<KnowledgeDetailItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.detail(knowledgeBaseId: id, chunkLimit: Int32(chunkLimit)) { dto, error in
                assertMainQueue("knowledge.detail")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    let detail = KnowledgeDetailItem(
                        book: KnowledgeBookItem(dto: dto.book),
                        chunks: dto.chunks.map(KnowledgeChunkItem.init(dto:)),
                        totalChunks: Int(dto.totalChunks),
                        assignedOperatorIds: dto.assignedOperatorIds
                    )
                    continuation.resume(returning: .success(detail))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "知识库不存在或已删除")))
                }
            }
        }
    }

    func loadKnowledgeStatus(id: String) async -> Result<KnowledgeStatusItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.status(knowledgeBaseId: id) { dto, error in
                assertMainQueue("knowledge.status")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(KnowledgeStatusItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "知识库不存在或已删除")))
                }
            }
        }
    }

    // MARK: 创建 / 导入 / 重命名 / 删除

    func createKnowledgeText(name: String, content: String) async -> Result<KnowledgeBookItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.createText(name: name, content: content) { dto, error in
                assertMainQueue("knowledge.createText")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(KnowledgeBookItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "创建失败")))
                }
            }
        }
    }

    /// 文件导入：Swift 侧把 DocumentPicker 的文件先落到临时目录，再交给桥读 bytes
    ///（Kotlin `ByteArray` 不直接导出到 Swift，桥提供 `importFileFromPath` 等价入口）。
    func importKnowledgeFile(fileName: String, fileURL: URL) async -> Result<KnowledgeBookItem, BridgeError> {
        let staged: URL
        do {
            staged = try KnowledgeFileStager.stage(fileURL: fileURL, fileName: fileName)
        } catch {
            return .failure(BridgeError(message: "无法读取所选文件：\(error.localizedDescription)"))
        }
        defer { try? FileManager.default.removeItem(at: staged) }

        return await withCheckedContinuation { continuation in
            knowledgeBridge.importFileFromPath(fileName: fileName, path: staged.path) { dto, error in
                assertMainQueue("knowledge.importFile")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(KnowledgeBookItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "导入失败")))
                }
            }
        }
    }

    func renameKnowledge(id: String, name: String) async -> Result<KnowledgeBookItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.rename(knowledgeBaseId: id, name: name) { dto, error in
                assertMainQueue("knowledge.rename")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(KnowledgeBookItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "重命名失败")))
                }
            }
        }
    }

    func deleteKnowledge(id: String) async -> String? {
        await withCheckedContinuation { continuation in
            knowledgeBridge.delete(knowledgeBaseId: id) { error in
                assertMainQueue("knowledge.delete")
                continuation.resume(returning: error)
            }
        }
    }

    // MARK: 索引

    func planKnowledgeIndex(id: String) async -> Result<KnowledgeIndexPlanItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.planIndex(knowledgeBaseId: id) { dto, error in
                assertMainQueue("knowledge.planIndex")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(KnowledgeIndexPlanItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "读取索引计划失败")))
                }
            }
        }
    }

    func indexKnowledge(id: String, onProgress: @escaping (Int, Int) -> Void) async -> Result<KnowledgeIndexResultItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.index(
                knowledgeBaseId: id,
                onProgress: { done, total in
                    onProgress(Int(done), Int(total))
                },
                onResult: { dto, error in
                    assertMainQueue("knowledge.index")
                    if let error {
                        continuation.resume(returning: .failure(BridgeError(message: error)))
                    } else if let dto {
                        continuation.resume(returning: .success(KnowledgeIndexResultItem(dto: dto)))
                    } else {
                        continuation.resume(returning: .failure(BridgeError(message: "索引失败")))
                    }
                }
            )
        }
    }

    func retryFailedKnowledgeChunks(id: String, onProgress: @escaping (Int, Int) -> Void) async -> Result<KnowledgeIndexResultItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.retryFailedChunks(
                knowledgeBaseId: id,
                onProgress: { done, total in
                    onProgress(Int(done), Int(total))
                },
                onResult: { dto, error in
                    assertMainQueue("knowledge.retryFailed")
                    if let error {
                        continuation.resume(returning: .failure(BridgeError(message: error)))
                    } else if let dto {
                        continuation.resume(returning: .success(KnowledgeIndexResultItem(dto: dto)))
                    } else {
                        continuation.resume(returning: .failure(BridgeError(message: "重试失败")))
                    }
                }
            )
        }
    }

    func invalidateKnowledgeIndex(id: String) async -> String? {
        await withCheckedContinuation { continuation in
            knowledgeBridge.invalidate(knowledgeBaseId: id) { error in
                assertMainQueue("knowledge.invalidate")
                continuation.resume(returning: error)
            }
        }
    }

    // MARK: 干员关联（召回前提）

    func loadKnowledgeOperators() async -> Result<[KnowledgeOperatorItem], BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.listOperators { dtos, error in
                assertMainQueue("knowledge.listOperators")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(KnowledgeOperatorItem.init(dto:))))
                }
            }
        }
    }

    func setKnowledgeAssignment(id: String, operatorId: String, enabled: Bool) async -> Result<[String], BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.setAssignment(
                knowledgeBaseId: id,
                operatorId: operatorId,
                enabled: enabled
            ) { assigned, error in
                assertMainQueue("knowledge.setAssignment")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let assigned {
                    continuation.resume(returning: .success(assigned))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "更新关联失败")))
                }
            }
        }
    }

    // MARK: 召回测试

    func recallKnowledge(operatorId: String, query: String, bookIds: [String]) async -> Result<KnowledgeRecallItem, BridgeError> {
        await withCheckedContinuation { continuation in
            knowledgeBridge.recallTest(operatorId: operatorId, query: query, bookIds: bookIds) { dto, error in
                assertMainQueue("knowledge.recallTest")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(KnowledgeRecallItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "召回测试失败")))
                }
            }
        }
    }
}

// MARK: - 文件暂存（DocumentPicker 安全作用域 → 临时文件）

enum KnowledgeFileStager {
    static func stage(fileURL: URL, fileName: String) throws -> URL {
        let scoped = fileURL.startAccessingSecurityScopedResource()
        defer { if scoped { fileURL.stopAccessingSecurityScopedResource() } }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("knowledge-import", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent("\(UUID().uuidString)-\(fileName)")
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: fileURL, to: destination)
        return destination
    }
}
