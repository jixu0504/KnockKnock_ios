//
//  KnowledgeAutomation.swift
//  T48 知识库端到端自检（仅 `--knowledge-selftest` 启动参数启用，对用户路径零影响）。
//
//  流程（全部经真实 Shared.framework + 真实 SQLite + 真实知识点/向量管线）：
//   1. 粘贴文本创建 → 切块数 > 0；
//   2. 文件导入（Markdown 字节经 DocumentPicker 等价路径）→ 切块数 > 0；
//   3. planIndex → indexBook（本地哈希嵌入）→ 成功块数 > 0、状态 ready；
//   4. status 查询 → 已索引块数 == 切块数；
//   5. 关联干员 → 召回测试（同聊天注入链路）→ 命中 > 0 且分数 ∈ (0,1]；
//   6. 删除 → 详情查询失败 + 列表数量回到基线（DB 核对）；
//   7. 播撒一本演示知识库（已索引 + 已关联）供 UI 截图与详情页召回测试自动运行。
//
//  证据：Documents/knowledge-selftest.log（simctl get_app_container 可取）+ NSLog。
//  说明：自检数据为幂等演示数据；删除验证用独立测试书名，不影响演示书。
//

import Foundation

struct KnowledgeSelfTestReport {
    let lines: [String]
    let passed: Bool
    let demoBookId: String
    let summary: String
}

@MainActor
enum KnowledgeAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--knowledge-selftest")
    static let demoBookName = "新艾利都通讯协议（演示）"
    static let recallQuery = "第七紧急通讯频道 值班代理人 确认收到"

    private static var didRun = false

    static func runIfNeeded(bridge: AppGraphClient) async -> KnowledgeSelfTestReport? {
        guard isEnabled, !didRun else { return nil }
        didRun = true

        var lines: [String] = []
        func add(_ message: String) {
            lines.append("\(timestamp()) \(message)")
            NSLog("[KnockKnock][T48] %@", message)
        }
        var passed = true
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            passed = passed && ok
            add("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " " + detail)")
        }

        add("=== knowledge selftest start ===")
        add("args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))")

        // 0. 幂等清理：删除上一次自检/截图遗留的测试书与演示书（可重复运行）
        if case .success(let existing) = await bridge.loadKnowledgeBases() {
            for book in existing where book.name.hasPrefix("T48 自检知识库") || book.name == Self.demoBookName {
                let cleanupError = await bridge.deleteKnowledge(id: book.id)
                add("cleanup previous book id=\(book.id) name=\(book.name) error=\(cleanupError ?? "-")")
            }
        }

        let baselineCount: Int
        switch await bridge.loadKnowledgeBases() {
        case .success(let items):
            baselineCount = items.count
            add("baseline books=\(baselineCount)")
        case .failure(let error):
            add("ABORT list failed error=\(error.message)")
            return finish(lines: lines, passed: false, demoBookId: "", summary: "ABORT list failed")
        }

        // 1. 粘贴文本创建（真实 KnowledgeBaseService.saveText → 同步切块）
        var textBookId = ""
        switch await bridge.createKnowledgeText(name: "T48 自检知识库（文本）", content: Self.fixtureMarkdown) {
        case .success(let book):
            textBookId = book.id
            check("createText", book.chunkCount > 0, "id=\(book.id) chunks=\(book.chunkCount) status=\(book.indexStatus)")
        case .failure(let error):
            check("createText", false, "error=\(error.message)")
        }

        // 2. 文件导入（临时目录里写 Markdown 字节 → 桥 importFileFromPath → importFile(bytes)）
        var fileBookId = ""
        let fixtureURL = try? writeFixtureToTemp(name: "t48-selftest.md")
        if let fixtureURL {
            switch await bridge.importKnowledgeFile(fileName: "t48-selftest.md", fileURL: fixtureURL) {
            case .success(let book):
                fileBookId = book.id
                check("importFile(bytes)", book.chunkCount > 0, "id=\(book.id) chunks=\(book.chunkCount) format=\(book.sourceFormat) status=\(book.indexStatus)")
            case .failure(let error):
                check("importFile(bytes)", false, "error=\(error.message)")
            }
            try? FileManager.default.removeItem(at: fixtureURL)
        } else {
            check("importFile(bytes)", false, "fixture write failed")
        }

        // 3. 索引计划 + 全量索引（本地哈希嵌入，离线免费）
        if !textBookId.isEmpty {
            switch await bridge.planKnowledgeIndex(id: textBookId) {
            case .success(let plan):
                check("planIndex", plan.chunkCount > 0, "chunks=\(plan.chunkCount) remote=\(plan.usesRemoteEmbedding) confirm=\(plan.requiresUserConfirmation)")
                check("planIndex.localMode", plan.requiresUserConfirmation == false, "requiresUserConfirmation=\(plan.requiresUserConfirmation)")
            case .failure(let error):
                check("planIndex", false, "error=\(error.message)")
            }

            var lastDone = 0
            switch await bridge.indexKnowledge(id: textBookId, onProgress: { done, _ in lastDone = done }) {
            case .success(let result):
                check("index", result.succeeded > 0 && result.failed == 0, "total=\(result.total) succeeded=\(result.succeeded) failed=\(result.failed) after=\(result.statusAfterText)")
                check("index.progress", lastDone > 0, "lastDone=\(lastDone)")
            case .failure(let error):
                check("index", false, "error=\(error.message)")
            }

            // 4. 状态查询
            switch await bridge.loadKnowledgeStatus(id: textBookId) {
            case .success(let status):
                check("status", status.indexedChunkCount == status.chunkCount && status.chunkCount > 0, "indexed=\(status.indexedChunkCount)/\(status.chunkCount) status=\(status.indexStatusText)")
            case .failure(let error):
                check("status", false, "error=\(error.message)")
            }

            // 5. 关联干员 + 召回测试
            var operatorId = "amiya"
            var operatorName = "阿米娅"
            switch await bridge.loadKnowledgeOperators() {
            case .success(let operators):
                if let amiya = operators.first(where: { $0.id == "amiya" }) {
                    operatorId = amiya.id
                    operatorName = amiya.name
                } else if let first = operators.first {
                    operatorId = first.id
                    operatorName = first.name
                }
                check("listOperators", !operators.isEmpty, "count=\(operators.count) picked=\(operatorId)(\(operatorName))")
            case .failure(let error):
                check("listOperators", false, "error=\(error.message)")
            }

            switch await bridge.setKnowledgeAssignment(id: textBookId, operatorId: operatorId, enabled: true) {
            case .success(let assigned):
                check("assignOperator", assigned.contains(operatorId), "assigned=\(assigned)")
            case .failure(let error):
                check("assignOperator", false, "error=\(error.message)")
            }

            switch await bridge.recallKnowledge(operatorId: operatorId, query: Self.recallQuery, bookIds: [textBookId]) {
            case .success(let recall):
                let topScore = recall.hits.first?.score ?? 0
                check("recall", !recall.hits.isEmpty && topScore > 0 && topScore <= 1.0 + 0.0001, "hits=\(recall.hits.count) topScore=\(String(format: "%.4f", topScore)) reason=\(recall.reason.isEmpty ? "ok" : recall.reason)")
                for (index, hit) in recall.hits.prefix(3).enumerated() {
                    add("recall hit[\(index)] book=\(hit.book) score=\(String(format: "%.4f", hit.score)) injected=\(hit.injected) snippet=\(hit.snippet.prefix(40))")
                }
                if recall.hits.isEmpty {
                    add("recall reasonText=\(recall.reasonText) assignedBooks=\(recall.assignedBooks)")
                }
            case .failure(let error):
                check("recall", false, "error=\(error.message)")
            }

            // 6. 删除 + DB 核对
            let deleteError = await bridge.deleteKnowledge(id: textBookId)
            check("delete", deleteError == nil, "error=\(deleteError ?? "-")")

            switch await bridge.loadKnowledgeDetail(id: textBookId, chunkLimit: 5) {
            case .failure:
                check("delete.detailGone", true)
            case .success(let detail):
                check("delete.detailGone", false, "still exists chunks=\(detail.totalChunks)")
            }

            if !fileBookId.isEmpty {
                let fileDeleteError = await bridge.deleteKnowledge(id: fileBookId)
                check("delete.fileBook", fileDeleteError == nil, "error=\(fileDeleteError ?? "-")")
            }

            switch await bridge.loadKnowledgeBases() {
            case .success(let items):
                check("delete.countBackToBaseline", items.count == baselineCount, "count=\(items.count) baseline=\(baselineCount) ids=\(items.map { $0.id })")
            case .failure(let error):
                check("delete.countBackToBaseline", false, "error=\(error.message)")
            }
        }

        // 7. 演示知识库（已索引 + 已关联 + 可召回），供截图与详情页召回测试
        var demoBookId = ""
        if let demoURL = try? writeFixtureToTemp(name: "t48-demo.md") {
            switch await bridge.importKnowledgeFile(fileName: "t48-demo.md", fileURL: demoURL) {
            case .success(let book):
                demoBookId = book.id
                add("demo book created id=\(book.id) chunks=\(book.chunkCount)")
            case .failure(let error):
                add("demo book create failed error=\(error.message)")
            }
            try? FileManager.default.removeItem(at: demoURL)
        }
        if !demoBookId.isEmpty {
            let _ = await bridge.renameKnowledge(id: demoBookId, name: Self.demoBookName)
            let indexResult = await bridge.indexKnowledge(id: demoBookId, onProgress: { _, _ in })
            if case .success(let result) = indexResult {
                add("demo book index succeeded=\(result.succeeded) failed=\(result.failed) status=\(result.statusAfterText)")
            }
            var demoOperator = "amiya"
            if case .success(let operators) = await bridge.loadKnowledgeOperators(),
               let amiya = operators.first(where: { $0.id == "amiya" }) ?? operators.first {
                demoOperator = amiya.id
            }
            _ = await bridge.setKnowledgeAssignment(id: demoBookId, operatorId: demoOperator, enabled: true)
            if case .success(let recall) = await bridge.recallKnowledge(operatorId: demoOperator, query: Self.recallQuery, bookIds: [demoBookId]) {
                add("demo book recall hits=\(recall.hits.count) reasonText=\(recall.reasonText)")
            }
        }

        add("=== knowledge selftest done (passed=\(passed)) ===")
        let summary = (passed ? "PASS" : "FAIL") + " baseline=\(baselineCount) demoBook=\(demoBookId)"
        return finish(lines: lines, passed: passed, demoBookId: demoBookId, summary: summary)
    }

    // MARK: - 内部

    private static func finish(lines: [String], passed: Bool, demoBookId: String, summary: String) -> KnowledgeSelfTestReport {
        var output = [
            "# KnockKnock iOS T48 知识库自检日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# device: \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "# ————————————————————",
        ]
        output += lines
        output += ["", "## summary", summary]
        let text = output.joined(separator: "\n")
        if let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let url = directory.appendingPathComponent("knowledge-selftest.log")
            do {
                try text.write(to: url, atomically: true, encoding: .utf8)
                NSLog("[KnockKnock][T48] log written to %@", url.path)
            } catch {
                NSLog("[KnockKnock][T48] log write failed: %@", error.localizedDescription)
            }
        }
        return KnowledgeSelfTestReport(lines: lines, passed: passed, demoBookId: demoBookId, summary: summary)
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }

    private static func writeFixtureToTemp(name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("knowledge-selftest", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(name)
        try fixtureMarkdown.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// 固定测试文本（Markdown，含章节标题；总长 > 1,200 字 → 多切块，便于预览与召回）。
    static let fixtureMarkdown = """
    # 新艾利都通讯协议

    ## 第七紧急通讯频道

    新艾利都在遇到空洞灾害、以太异变或敌方突袭时，会启用第七紧急通讯频道。该频道使用短波加密传输，只有获得治安局值班权限的代理人可以发言。值班代理人必须在十秒内确认收到指令，并完整复述关键坐标与撤离路线，任何遗漏都要立刻要求总部重发。频道内禁止闲聊，所有通话在结束后二十四小时内由通讯班整理成文字记录，归档到本市情报库。若频道被敌对势力监听，通讯班应立即切换备用频率并通知全体值班代理人。

    ## 物资调度规则

    后勤部每周三与周六进行物资盘点。前线代理人如果需要补充弹药、以太抑制触媒或急救药品，应当提前四小时在调度终端提交申请，写清数量、用途与领取地点。紧急情况下可以先领用，但必须事后二十四小时内补交申请单。任何人不得私自挪用储备库物资，违者由人事部记录并通报。盘点期间仓库暂停非紧急出库，医疗舱的急救通道不受影响。

    ## 医疗舱守则

    进入医疗舱必须更换消毒外套，携带污染样本时走独立通道。任何代理人出现以太侵蚀急性发作症状，应立即通知值班医师并封锁邻近区块，等待医疗组到场评估。未经允许不得接触污染样本与诊断记录。医疗舱夜间仅保留一名值班医师，紧急呼叫按钮位于每张病床右侧，按下后警报会同步传达到治安局值班室与绳匠终端。

    ## 新人登记流程

    新加入新艾利都的代理人需要在人事部完成登记，提交代号、所属阵营、以太适性状况与紧急联系人信息。登记完成后领取城区通行证与宿舍钥匙，并参加为期三天的安全培训。培训内容包括城区通行规则、空洞避难路线、医疗舱使用规范与通讯设备操作。未完成培训的代理人不得独自离城执行任务。
    """
}
