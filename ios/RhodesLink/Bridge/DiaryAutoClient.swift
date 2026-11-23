//
//  DiaryAutoClient.swift
//  T61 干员日记自动生成（Swift 侧包装）：
//  - Kotlin `DiaryAutoGlue` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct / async 方法；
//  - 真实 SQLite（diaries 表）+ 真实 settings（diary_auto_enabled / diary_auto_<operatorId>），无 mock；
//  - 生成走 shared 日记提示词管线（PromptTemplates("diary") + PromptRender）；未配置模型供应商时
//    shared 侧回退本地确定性演示内容，UI 用 `demoMode` 明确提示。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared DiaryAutoGlue DTO 一一对应）

struct DiaryOperatorAutoItem: Identifiable, Hashable {
    let operatorId: String
    let operatorName: String
    /// operators.avatarUri 原样（"1".."147" 内置 Bundle 头像；空 = 首字占位）。
    let avatarId: String
    /// 单干员自动生成开关（settings diary_auto_<operatorId>）。
    let autoEnabled: Bool
    /// 最近一篇日记 createdAt（0 = 还没有日记）。
    let lastDiaryAt: Int64
    let lastDiaryDate: String
    let lastDiaryContentLength: Int
    /// 北京今天是否已有该干员的日记。
    let todayGenerated: Bool

    var id: String { operatorId }

    init(dto: DiaryOperatorAutoDto) {
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        avatarId = dto.avatarId
        autoEnabled = dto.autoEnabled
        lastDiaryAt = dto.lastDiaryAt
        lastDiaryDate = dto.lastDiaryDate
        lastDiaryContentLength = Int(dto.lastDiaryContentLength)
        todayGenerated = dto.todayGenerated
    }

    /// 行尾「最近日记」文案：今天 HH:mm / 其它 yyyy/M/d / 还没有日记。
    var lastDiaryText: String {
        guard lastDiaryAt > 0 else { return "还没有日记" }
        let millis = lastDiaryAt > 10_000_000_000 ? lastDiaryAt : lastDiaryAt * 1000
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
            return "今天 \(formatter.string(from: date)) · \(lastDiaryContentLength) 字"
        }
        formatter.dateFormat = "M/d"
        return "\(formatter.string(from: date)) · \(lastDiaryContentLength) 字"
    }
}

struct DiaryAutoStatusItem {
    let masterEnabled: Bool
    let entries: [DiaryOperatorAutoItem]
    let enabledCount: Int
    let todayGeneratedCount: Int
    let dailyLimit: Int
    /// true = 未配置模型供应商，生成内容为本地演示内容。
    let demoMode: Bool
    let totalCount: Int

    init(dto: DiaryAutoStatusDto) {
        masterEnabled = dto.masterEnabled
        entries = dto.entries.map(DiaryOperatorAutoItem.init(dto:))
        enabledCount = Int(dto.enabledCount)
        todayGeneratedCount = Int(dto.todayGeneratedCount)
        dailyLimit = Int(dto.dailyLimit)
        demoMode = dto.demoMode
        totalCount = Int(dto.totalCount)
    }
}

struct DiaryGenerateResultItem: Hashable {
    let id: Int64
    let operatorId: String
    let operatorName: String
    let date: String
    let contentLength: Int
    let version: Int
    let createdAt: Int64
    /// false = 同日幂等命中（返回已有日记，未写库）。
    let created: Bool
    let demoMode: Bool
    let elapsedMs: Int64
    let message: String

    init(dto: DiaryGenerateResultDto) {
        id = dto.id
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        date = dto.date
        contentLength = Int(dto.contentLength)
        version = Int(dto.version)
        createdAt = dto.createdAt
        created = dto.created
        demoMode = dto.demoMode
        elapsedMs = dto.elapsedMs
        message = dto.message
    }
}

struct DiaryTickResultItem: Hashable {
    let generated: Int
    let enabledCount: Int
    let skippedExisting: Int
    let failed: Int
    let limit: Int
    let demoMode: Bool
    let elapsedMs: Int64
    let message: String

    init(dto: DiaryTickDto) {
        generated = Int(dto.generated)
        enabledCount = Int(dto.enabledCount)
        skippedExisting = Int(dto.skippedExisting)
        failed = Int(dto.failed)
        limit = Int(dto.limit)
        demoMode = dto.demoMode
        elapsedMs = dto.elapsedMs
        message = dto.message
    }
}

// MARK: - AppGraphClient 扩展（干员日记自动生成）

extension AppGraphClient {
    /// 「日记生成管理」状态：总开关 + 147 干员 × 开关 / 最近日记 / 今日是否已生成。
    func loadDiaryAutoStatus() async -> Result<DiaryAutoStatusItem, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryAutoBridge.status { dto, error in
                assertMainQueue("diaryAuto.status")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(DiaryAutoStatusItem(dto: dto)))
                }
            }
        }
    }

    /// 总开关（settings diary_auto_enabled）。返回当前开启的干员数。
    func setDiaryAutoMaster(enabled: Bool) async -> Result<Int, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryAutoBridge.setMasterEnabled(enabled: enabled) { count, error in
                assertMainQueue("diaryAuto.setMaster")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(Int(count.intValue)))
                }
            }
        }
    }

    /// 单干员开关（settings diary_auto_<operatorId>）。返回生效状态（= 总开关 && 单干员开关）。
    func setDiaryOperatorAuto(operatorId: String, enabled: Bool) async -> Result<Bool, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryAutoBridge.setOperatorAuto(operatorId: operatorId, enabled: enabled) { effective, error in
                assertMainQueue("diaryAuto.setOperator")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(effective.boolValue))
                }
            }
        }
    }

    /// 全部开启 / 全部关闭（同时写总开关）。返回生效干员数。
    func setAllDiaryAuto(enabled: Bool) async -> Result<Int, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryAutoBridge.setAllOperatorAuto(enabled: enabled) { count, error in
                assertMainQueue("diaryAuto.setAll")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(Int(count.intValue)))
                }
            }
        }
    }

    /// 立即为单干员生成一篇：force=false 时同日幂等（已有今天日记则返回原记录）。
    func generateDiaryNow(operatorId: String, force: Bool) async -> Result<DiaryGenerateResultItem, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryAutoBridge.generateForOperator(operatorId: operatorId, force: force) { result, error in
                assertMainQueue("diaryAuto.generate")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let result {
                    continuation.resume(returning: .success(DiaryGenerateResultItem(dto: result)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "日记生成失败")))
                }
            }
        }
    }

    /// 每日 tick：补齐「已开启且今天还没写」的干员，每轮最多 limit 位（<=0 用 settings 上限）。
    func runDiaryAutoTick(limit: Int) async -> Result<DiaryTickResultItem, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryAutoBridge.tick(limitOverride: Int32(limit)) { result, error in
                assertMainQueue("diaryAuto.tick")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(DiaryTickResultItem(dto: result)))
                }
            }
        }
    }
}
