//
//  PersonaAutomation.swift
//  T49 角色人设编辑自动化（仅 `--persona-selftest` 启动参数启用，对用户路径零影响）：
//  关系类型值域 → 新建角色（姓名/标签/人设/简介/关系/内置头像/槽位）→ 角色列表出现 →
//  编辑姓名 + 人设 + 头像 + 槽位 → 读回核对（operators 行 + settings 槽位键）→
//  新建会话（确认新角色可聊天）→ 删除测试角色 → 列表消失 + 读取失败。
//
//  证据：NSLog；独立日志 Documents/ios-persona-selftest.log（simctl get_app_container 可取，
//  不依赖 `--selftest` 的 SelfTestLog 落盘开关）。DB 核对另用 sqlite3 查 operators 行。
//
//  截图辅助启动参数（不触发自检）：
//  - `--persona-edit-new`：RolesView 启动后直接弹出「新建角色」表单
//  - `--persona-edit-id <operatorId>`：弹出该角色的编辑表单
//  - `--persona-avatar-sheet` / `--persona-advanced`：表单内自动打开内置头像网格 / 高级槽位区
//

import Foundation

// MARK: - 角色人设自检日志（独立文件，避免依赖 --selftest）

enum PersonaSelfTestLog {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--persona-selftest")

    private static let lock = NSLock()
    private static var lines: [String] = []

    static func add(_ message: String) {
        NSLog("[RhodesLink][PersonaSelfTest] %@", message)
        guard isEnabled else { return }
        lock.lock()
        lines.append("\(timestamp()) \(message)")
        lock.unlock()
    }

    static func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return lines
    }

    static func flushToDisk(summary: String) {
        guard isEnabled else { return }
        let header = [
            "# RhodesLink iOS 角色人设编辑自检日志（T49）",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# device: \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "# ————————————————————",
        ]
        let body = snapshot().joined(separator: "\n")
        let text = (header + [body, "", "## summary", summary]).joined(separator: "\n")
        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent("ios-persona-selftest.log")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][PersonaSelfTest] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][PersonaSelfTest] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}

// MARK: - 自检驱动

@MainActor
enum PersonaAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--persona-selftest")
    private static var didRun = false

    /// 截图辅助：`--persona-edit-new` 或 `--persona-edit-id <id>` 时要求 RolesView 弹出编辑表单。
    static var editRequested: Bool {
        let args = ProcessInfo.processInfo.arguments
        return args.contains("--persona-edit-new") || args.contains("--persona-edit-id")
    }

    /// 编辑目标 operatorId；nil = 新建表单。
    static var editTarget: String? {
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--persona-edit-id"), index + 1 < args.count {
            return args[index + 1]
        }
        return nil
    }

    /// 由 RolesAutomation.runIfNeeded（App 启动 .task）调用，无需进入联系人 Tab 即可跑完整链路。
    static func runIfNeeded(environment: AppEnvironment) async {
        guard isEnabled, !didRun else { return }
        didRun = true

        PersonaSelfTestLog.add("=== persona selftest start ===")
        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            if ok { passCount += 1 } else { failCount += 1 }
            PersonaSelfTestLog.add("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " \(detail)")")
        }

        let bridge = environment.bridge
        let keepRole = ProcessInfo.processInfo.arguments.contains("--persona-selftest-keep")
        let stamp = Int(Date().timeIntervalSince1970)
        let baseName = "T49测试角色\(stamp)"
        var createdId = ""

        // 1. 关系类型（24 种，值域同 Android RelationshipType）
        let relationTypes = bridge.operatorRelationTypes()
        let friendLabel = relationTypes.first(where: { $0.code == "FRIEND" })?.label ?? "朋友"
        check("relationTypes", relationTypes.count >= 20, "count=\(relationTypes.count) codes=\(relationTypes.map(\.code))")

        // 2. 新建角色（姓名/标签/人设/简介/关系/内置头像 7/槽位 2-3）
        var createDraft = OperatorDraft()
        createDraft.name = baseName
        createDraft.tags = "测试,毒舌"
        createDraft.intro = "T49 自动化创建的角色"
        createDraft.persona = "你是\(baseName)，说话简短直接。"
        createDraft.gender = "女"
        createDraft.relationType = friendLabel
        createDraft.avatarId = "7"
        createDraft.privateSlots = [createDraft.persona, "私聊槽位2-新建", "私聊槽位3-新建"]
        createDraft.groupSlots = ["群聊槽位1-新建", "", ""]

        switch await bridge.createOperator(createDraft) {
        case .success(let item):
            createdId = item.id
            check("create", item.id.hasPrefix("custom_"), "id=\(item.id) name=\(item.name)")
            check("create fields",
                  item.name == baseName && item.tags == "测试,毒舌" && item.avatarId == "7"
                      && item.persona == createDraft.persona && item.relationType == friendLabel
                      && item.intro == createDraft.intro,
                  "name=\(item.name) tags=\(item.tags) avatar=\(item.avatarId) relation=\(item.relationType)")
            check("create slots",
                  item.privateSlots[1] == "私聊槽位2-新建" && item.privateSlots[2] == "私聊槽位3-新建"
                      && item.privateActiveSlot == 1,
                  "privateSlots=\(item.privateSlots) active=\(item.privateActiveSlot)")
            check("create group slot", item.groupSlots[0] == "群聊槽位1-新建", "groupSlots=\(item.groupSlots)")
        case .failure(let error):
            PersonaSelfTestLog.add("create FAILED error=\(error.message)")
            PersonaSelfTestLog.flushToDisk(summary: "PASS=\(passCount) FAIL=\(failCount + 1) (create aborted)")
            return
        }

        // 3. 角色列表出现
        var listHasRole = false
        switch await bridge.loadRoles() {
        case .success(let roles):
            listHasRole = roles.contains(where: { $0.id == createdId })
            check("list contains created", listHasRole, "count=\(roles.count) id=\(createdId)")
        case .failure(let error):
            check("list contains created", false, "error=\(error.message)")
        }

        // 4. 编辑：姓名/人设/标签/关系/头像(42)/槽位
        let editedName = baseName + "改"
        let editedPersona = "更新后的人设：毒舌但关键时刻可靠。"
        var updateDraft = createDraft
        updateDraft.id = createdId
        updateDraft.name = editedName
        updateDraft.tags = "测试,已编辑"
        updateDraft.persona = editedPersona
        updateDraft.avatarId = "42"
        updateDraft.relationType = relationTypes.first(where: { $0.code == "RIVAL" })?.label ?? "对手"
        updateDraft.privateSlots = [editedPersona, "私聊槽位2-已编辑", "私聊槽位3-已编辑"]
        updateDraft.groupSlots = ["群聊槽位1-已编辑", "群聊槽位2-已编辑", ""]
        updateDraft.groupActiveSlot = 2
        switch await bridge.updateOperator(updateDraft) {
        case .success(let item):
            check("update", item.name == editedName && item.persona == editedPersona && item.avatarId == "42",
                  "name=\(item.name) avatar=\(item.avatarId)")
            check("update slot override",
                  item.privateSlots[1] == "私聊槽位2-已编辑" && item.groupSlots[1] == "群聊槽位2-已编辑"
                      && item.groupActiveSlot == 2,
                  "privateSlots=\(item.privateSlots) groupSlots=\(item.groupSlots) groupActive=\(item.groupActiveSlot)")
        case .failure(let error):
            check("update", false, "error=\(error.message)")
        }

        // 5. 读回核对（模拟重启后从 DB/settings 读：编辑表单 + 角色列表）
        switch await bridge.loadOperatorEdit(id: createdId) {
        case .success(let item):
            check("readback",
                  item.name == editedName && item.persona == editedPersona && item.avatarId == "42"
                      && item.tags == "测试,已编辑",
                  "name=\(item.name) persona=\(item.persona) tags=\(item.tags) avatar=\(item.avatarId)")
        case .failure(let error):
            check("readback", false, "error=\(error.message)")
        }
        switch await bridge.loadRole(id: createdId) {
        case .success(let role):
            check("role dto after edit",
                  role?.name == editedName && role?.avatarId == "42",
                  "name=\(role?.name ?? "-") avatar=\(role?.avatarId ?? "-") relation=\(role?.relationType ?? "-")")
        case .failure(let error):
            check("role dto after edit", false, "error=\(error.message)")
        }

        // 6. 内置头像切换（1..147）
        switch await bridge.setOperatorAvatarBuiltin(id: createdId, number: 99) {
        case .success(let value):
            check("setAvatarBuiltin", value == "99", "avatarUri=\(value)")
        case .failure(let error):
            check("setAvatarBuiltin", false, "error=\(error.message)")
        }

        // 7. 新角色可开聊（会话使用新 avatarUri/name）
        var sessionId = ""
        switch await bridge.startChat(operatorId: createdId) {
        case .success(let id):
            sessionId = id
            check("startChat", !id.isEmpty, "session=\(id)")
        case .failure(let error):
            check("startChat", false, "error=\(error.message)")
        }
        if !sessionId.isEmpty {
            switch await bridge.loadRole(id: createdId) {
            case .success(let role):
                check("session uses edited role",
                      role?.sessionId == sessionId && role?.name == editedName,
                      "session=\(role?.sessionId ?? "-") name=\(role?.name ?? "-")")
            case .failure(let error):
                check("session uses edited role", false, "error=\(error.message)")
            }
        }

        // 7.5 证据用「保留模式」：--persona-selftest-keep 保留测试角色供 sqlite3 核对/截图，
        //    随后用 `--persona-cleanup <id>` 清理（见 runCleanupIfNeeded）。
        if keepRole {
            PersonaSelfTestLog.add("KEEP role=\(createdId) name=\(editedName) session=\(sessionId)")
            await environment.refreshCounts()
            let keepSummary = "PASS=\(passCount) FAIL=\(failCount) KEPT role=\(createdId)"
            PersonaSelfTestLog.add("=== persona selftest done (keep) \(keepSummary) ===")
            PersonaSelfTestLog.flushToDisk(summary: keepSummary)
            return
        }

        // 8. 删除测试角色（清理测试数据）
        switch await bridge.deleteOperator(id: createdId) {
        case .success(let deletedId):
            check("delete", deletedId == createdId, "id=\(deletedId)")
        case .failure(let error):
            check("delete", false, "error=\(error.message)")
        }
        switch await bridge.loadRoles() {
        case .success(let roles):
            check("list after delete", !roles.contains(where: { $0.id == createdId }), "count=\(roles.count)")
        case .failure(let error):
            check("list after delete", false, "error=\(error.message)")
        }
        switch await bridge.loadOperatorEdit(id: createdId) {
        case .success:
            check("read after delete fails", false, "still readable id=\(createdId)")
        case .failure:
            check("read after delete fails", true, "id=\(createdId)")
        }

        await environment.refreshCounts()
        let summary = "PASS=\(passCount) FAIL=\(failCount) role=\(createdId)"
        PersonaSelfTestLog.add("=== persona selftest done \(summary) ===")
        PersonaSelfTestLog.flushToDisk(summary: summary)
    }

    /// 清理 --persona-selftest-keep 留下的测试角色：`--persona-cleanup <operatorId>`。
    static func runCleanupIfNeeded(environment: AppEnvironment) async {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "--persona-cleanup"), index + 1 < args.count else { return }
        let operatorId = args[index + 1]
        switch await environment.bridge.deleteOperator(id: operatorId) {
        case .success(let deletedId):
            NSLog("[RhodesLink][PersonaSelfTest] cleanup deleted id=%@", deletedId)
            PersonaSelfTestLog.add("cleanup deleted id=\(deletedId)")
        case .failure(let error):
            NSLog("[RhodesLink][PersonaSelfTest] cleanup failed id=%@ error=%@", operatorId, error.message)
            PersonaSelfTestLog.add("cleanup failed id=\(operatorId) error=\(error.message)")
        }
        PersonaSelfTestLog.flushToDisk(summary: "cleanup id=\(operatorId)")
    }
}
