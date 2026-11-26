//
//  MemoryAutomation.swift
//  记忆管理页自动化验证（`--memory-mgmt-selftest`，T47）：
//  预置记忆 → 列表 → 筛选 → 编辑（改内容/重要度）→ 隐私读写 → 删除（单条 + 批量）→ 落盘日志。
//  全部经真实 MemoryBridge → 真实 SQLite，不 mock；日志写入 Documents/ios-shell-selftest.log。
//

import Foundation

@MainActor
enum MemoryAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--memory-mgmt-selftest")
    private static var didRun = false

    private static var passed = 0
    private static var failed = 0

    static func runIfNeeded(environment: AppEnvironment) async {
        guard isEnabled, !didRun else { return }
        didRun = true

        SelfTestLog.add("=== memory mgmt selftest start ===")
        let bridge = environment.bridge

        // 0. 操作者主体（owner operator）：取第一位角色
        var operatorId = ""
        var operatorName = ""
        switch await bridge.loadRoles() {
        case .success(let roles):
            if let first = roles.first {
                operatorId = first.id
                operatorName = first.name
            }
        case .failure(let error):
            SelfTestLog.add("memory selftest roles FAILED error=\(error.message)")
        }
        if operatorId.isEmpty {
            operatorId = "amiya"
            operatorName = operatorId
        }
        SelfTestLog.add("memory selftest owner=\(operatorId)(\(operatorName))")

        let stamp = Int(Date().timeIntervalSince1970)
        let marker = "T47自检记忆\(stamp)"
        let editedMarker = "\(marker)已编辑"
        let privacyMarker = "\(marker)私密内容秘密"

        // 1. 预置：新建一条手动记忆（MANUAL_MEMORY；隐私经 MemoryPrivacyPolicy 判定）
        let created: MemoryMutationResult?
        switch await bridge.createMemory(
            ownerType: "operator",
            ownerId: operatorId,
            content: "\(marker)：绳匠答应周末一起去训练场。",
            level: "L2",
            importance: 42,
            explicitPrivate: false,
            memoryType: "agreement_commitment"
        ) {
        case .success(let result):
            created = result
            SelfTestLog.add("memory selftest create id=\(result.id) ok=\(result.ok) message=\(result.message)")
        case .failure(let error):
            created = nil
            SelfTestLog.add("memory selftest create FAILED error=\(error.message)")
        }
        guard let created, created.ok, created.id > 0 else {
            finish(PASS: false, reason: "create 失败，后续用例跳过")
            return
        }
        let id = created.id

        // 1.5 敏感词隐私判定：命中「秘密」→ private
        switch await bridge.createMemory(
            ownerType: "operator",
            ownerId: operatorId,
            content: "\(privacyMarker)：这句只告诉你，别告诉别人。",
            level: "L1",
            importance: 77,
            explicitPrivate: false,
            memoryType: "emotion_state"
        ) {
        case .success(let result):
            SelfTestLog.add("memory selftest createSensitive id=\(result.id) message=\(result.message)")
            if let detail = await loadDetail(bridge, id: result.id, table: "v2"), detail.record.privacy == "private" {
                check("sensitive→private", true, "privacy=\(detail.record.privacy)")
            } else {
                check("sensitive→private", false, "privacy 未判定为 private")
            }
        case .failure(let error):
            check("sensitive→private", false, "create 失败 \(error.message)")
        }

        // 2. 列表：分页 size=1 命中新行
        let page = await loadPage(bridge, offset: 0, limit: 1, query: MemoryQuery.all)
        check("list.pageSize1", page?.items.count == 1 && page?.total ?? 0 > 0, "total=\(page?.total ?? -1) count=\(page?.items.count ?? -1)")
        let pageAll = await loadPage(bridge, offset: 0, limit: 100, query: MemoryQuery.all)
        let foundRow = pageAll?.items.first(where: { $0.id == id && $0.sourceTable == "v2" })
        check("list.containsCreated", foundRow != nil, "found=\(foundRow?.identity ?? "-")")

        // 3. 筛选矩阵：层级 / 关键词 / 来源 / 时间范围 / 排序
        var levelQuery = MemoryQuery()
        levelQuery.level = "L2"
        let levelPage = await loadPage(bridge, offset: 0, limit: 100, query: levelQuery)
        check("filter.level=L2", levelPage?.items.contains(where: { $0.id == id }) == true, "count=\(levelPage?.items.count ?? -1)")
        let levelL1Page = await loadPage(bridge, offset: 0, limit: 100, query: { var q = MemoryQuery(); q.level = "L1"; return q }())
        check("filter.level=L1.excludes", levelL1Page?.items.contains(where: { $0.id == id }) != true, "count=\(levelL1Page?.items.count ?? -1)")

        var keywordQuery = MemoryQuery()
        keywordQuery.keyword = marker
        let keywordPage = await loadPage(bridge, offset: 0, limit: 100, query: keywordQuery)
        check("filter.keyword", keywordPage?.items.contains(where: { $0.id == id }) == true && (keywordPage?.items.count ?? 0) >= 1, "count=\(keywordPage?.items.count ?? -1)")

        var sourceQuery = MemoryQuery()
        sourceQuery.sourceKind = "MANUAL_MEMORY"
        let sourcePage = await loadPage(bridge, offset: 0, limit: 100, query: sourceQuery)
        check("filter.source=MANUAL_MEMORY", sourcePage?.items.contains(where: { $0.id == id }) == true, "count=\(sourcePage?.items.count ?? -1)")

        var timeQuery = MemoryQuery()
        timeQuery.fromMillis = Int64(Date().timeIntervalSince1970 * 1000) - 3_600_000
        let timePage = await loadPage(bridge, offset: 0, limit: 100, query: timeQuery)
        check("filter.time=last1h", timePage?.items.contains(where: { $0.id == id }) == true, "count=\(timePage?.items.count ?? -1)")
        var oldQuery = MemoryQuery()
        oldQuery.toMillis = Int64(Date().timeIntervalSince1970 * 1000) - 86_400_000
        let oldPage = await loadPage(bridge, offset: 0, limit: 100, query: oldQuery)
        check("filter.time=beforeYesterday.excludes", oldPage?.items.contains(where: { $0.id == id }) != true, "count=\(oldPage?.items.count ?? -1)")

        var importanceQuery = MemoryQuery()
        importanceQuery.sort = "importance"
        let importancePage = await loadPage(bridge, offset: 0, limit: 100, query: importanceQuery)
        check("sort=importance", importancePage != nil, "count=\(importancePage?.items.count ?? -1)")

        // 4. 详情（含锚点）
        if let detail = await loadDetail(bridge, id: id, table: "v2") {
            let anchorTypes = detail.sourceAnchors.map(\.type)
            check("detail.record", detail.record.content.contains(marker) && detail.record.level == "L2" && detail.record.importance == 42, "level=\(detail.record.level) importance=\(detail.record.importance)")
            check("detail.sourceAnchors", !detail.sourceAnchors.isEmpty, "types=\(anchorTypes)")
            check("detail.canEdit", detail.canEditContent && detail.canEditImportance && detail.canDelete, "canEdit=\(detail.canEditContent)/\(detail.canEditImportance) canDelete=\(detail.canDelete)")
            check("detail.privacyLines", !detail.privacyLines.isEmpty, "lines=\(detail.privacyLines.count)")
        } else {
            check("detail.record", false, "detail 读取失败")
        }

        // 5. 编辑：改内容 + 重要度 → 详情复核
        switch await bridge.updateMemory(id: id, sourceTable: "v2", content: editedMarker, importance: 88) {
        case .success(let result):
            SelfTestLog.add("memory selftest edit id=\(id) ok=\(result.ok) message=\(result.message)")
        case .failure(let error):
            SelfTestLog.add("memory selftest edit FAILED error=\(error.message)")
        }
        if let detail = await loadDetail(bridge, id: id, table: "v2") {
            check("edit.content", detail.record.content == editedMarker, "content=\(detail.record.content)")
            check("edit.importance", detail.record.importance == 88, "importance=\(detail.record.importance)")
            check("edit.updatedAt", detail.record.updatedAt >= detail.record.createdAt, "created=\(detail.record.createdAt) updated=\(detail.record.updatedAt)")
        } else {
            check("edit.content", false, "编辑后 detail 读取失败")
        }

        // 6. 隐私设置读写（settings 键语义）
        let privacyBefore = bridge.memoryPrivacySettings()
        SelfTestLog.add("memory selftest privacy keys memory_v2=\(privacyBefore.memoryV2Enabled) distinguish=\(privacyBefore.distinguishPrivateMemory) sourceAware=\(privacyBefore.sourceAwareMemoryEnabled) globalPublic=\(privacyBefore.globalPublicMemoryEnabled) sensitiveWords=\(privacyBefore.sensitiveWords.count) policyLines=\(privacyBefore.policyLines.count)")
        let wrote = bridge.setMemoryPrivacyFlag(key: "distinguish_private_memory", value: !privacyBefore.distinguishPrivateMemory)
        let privacyAfter = bridge.memoryPrivacySettings()
        check("privacy.toggleReadback", wrote && privacyAfter.distinguishPrivateMemory == !privacyBefore.distinguishPrivateMemory, "before=\(privacyBefore.distinguishPrivateMemory) after=\(privacyAfter.distinguishPrivateMemory)")
        _ = bridge.setMemoryPrivacyFlag(key: "distinguish_private_memory", value: privacyBefore.distinguishPrivateMemory)
        let unknownKeyRejected = !bridge.setMemoryPrivacyFlag(key: "t47_unknown_key", value: true)
        check("privacy.unknownKeyRejected", unknownKeyRejected, "unknown key accepted")
        check("privacy.sensitiveWords18", privacyAfter.sensitiveWords.count == 18, "count=\(privacyAfter.sensitiveWords.count)")
        check("privacy.isSensitive", bridge.isSensitiveMemoryText("这是秘密") && !bridge.isSensitiveMemoryText("今天天气不错"), "命中判定")

        // 7. 删除（单条）→ 列表消失 + 详情不存在
        switch await bridge.deleteMemory(id: id, sourceTable: "v2") {
        case .success(let result):
            SelfTestLog.add("memory selftest delete id=\(id) ok=\(result.ok) message=\(result.message)")
        case .failure(let error):
            SelfTestLog.add("memory selftest delete FAILED error=\(error.message)")
        }
        var afterDelete = MemoryQuery()
        afterDelete.keyword = editedMarker
        let afterDeletePage = await loadPage(bridge, offset: 0, limit: 100, query: afterDelete)
        check("delete.singleGone", afterDeletePage?.items.contains(where: { $0.id == id }) != true, "remaining=\(afterDeletePage?.items.count ?? -1)")
        let deletedDetail = await loadDetail(bridge, id: id, table: "v2")
        check("delete.detailNil", deletedDetail == nil, "detail 仍可读取")

        // 8. 批量删除：新建 2 条 → deleteBatch → 全部消失
        var batchIDs: [Int64] = []
        for index in 0..<2 {
            switch await bridge.createMemory(
                ownerType: "operator",
                ownerId: operatorId,
                content: "\(marker)批量\(index)",
                level: "L1",
                importance: 30 + index,
                explicitPrivate: false,
                memoryType: "event"
            ) {
            case .success(let result):
                if result.ok { batchIDs.append(result.id) }
            case .failure(let error):
                SelfTestLog.add("memory selftest batchCreate \(index) FAILED error=\(error.message)")
            }
        }
        check("batchCreate", batchIDs.count == 2, "ids=\(batchIDs)")
        if batchIDs.count == 2 {
            switch await bridge.deleteMemories(ids: batchIDs, sourceTables: ["v2", "v2"]) {
            case .success(let result):
                SelfTestLog.add("memory selftest deleteBatch ok=\(result.ok) message=\(result.message)")
                check("delete.batch.ok", result.ok, "message=\(result.message)")
            case .failure(let error):
                check("delete.batch.ok", false, "error=\(error.message)")
            }
            var batchQuery = MemoryQuery()
            batchQuery.keyword = "\(marker)批量"
            let batchPage = await loadPage(bridge, offset: 0, limit: 100, query: batchQuery)
            check("delete.batchGone", (batchPage?.items.count ?? -1) == 0, "remaining=\(batchPage?.items.count ?? -1)")

            // 9. 错误态/空态路径：空关键词删除不存在的 id 应返回错误（不崩溃）
            switch await bridge.deleteMemory(id: 999_999_999, sourceTable: "v2") {
            case .success(let result):
                check("delete.missingRejected", !result.ok, "message=\(result.message)")
            case .failure(let error):
                SelfTestLog.add("memory selftest deleteMissing error=\(error.message)")
                check("delete.missingRejected", true, "error=\(error.message)")
            }
        }

        // 10. 清理敏感记忆
        let sensitivePage = await loadPage(bridge, offset: 0, limit: 100, query: { var q = MemoryQuery(); q.keyword = privacyMarker; return q }())
        if let sensitive = sensitivePage?.items.first {
            _ = await bridge.deleteMemory(id: sensitive.id, sourceTable: sensitive.sourceTable)
        }

        finish(PASS: failed == 0, reason: "passed=\(passed) failed=\(failed)")
    }

    // MARK: - 断言与辅助

    private static func check(_ name: String, _ condition: Bool, _ detail: String) {
        if condition { passed += 1 } else { failed += 1 }
        SelfTestLog.add("memory selftest \(condition ? "PASS" : "FAIL") \(name) :: \(detail)")
    }

    private static func finish(PASS: Bool, reason: String) {
        SelfTestLog.add("=== memory mgmt selftest done PASS=\(PASS) \(reason) ===")
        SelfTestLog.flushToDisk(summary: "memory-mgmt selftest PASS=\(PASS) \(reason)")
    }

    private static func loadPage(_ bridge: AppGraphClient, offset: Int, limit: Int, query: MemoryQuery) async -> MemoryPage? {
        switch await bridge.loadMemoryPage(offset: offset, limit: limit, query: query) {
        case .success(let page): return page
        case .failure(let error):
            SelfTestLog.add("memory selftest listPage FAILED error=\(error.message)")
            return nil
        }
    }

    private static func loadDetail(_ bridge: AppGraphClient, id: Int64, table: String) async -> MemoryDetail? {
        switch await bridge.loadMemoryDetail(id: id, sourceTable: table) {
        case .success(let detail): return detail
        case .failure: return nil
        }
    }
}
