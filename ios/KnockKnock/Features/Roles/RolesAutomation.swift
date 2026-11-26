//
//  RolesAutomation.swift
//  T36 角色/会话页自动化验证钩子（仅启动参数启用，对用户路径零影响）：
//  - `--roles-selftest`：角色列表 / 头像 Bundle 计数 / 详情 / 关系网 / 新建会话全链路打点；
//  - `--autochat <operatorId>`：选择角色 → 创建/复用会话 → 进入聊天页（与「+」按钮、
//    角色详情「开始聊天」走完全相同的 AppGraphClient.startChat + AppRouter.openChat 路径）。
//
//  证据：NSLog + `--selftest` 时的 Documents/ios-shell-selftest.log（simctl 可取）。
//

import Foundation

@MainActor
enum RolesAutomation {
    private static var didRun = false

    static func runIfNeeded(environment: AppEnvironment, router: AppRouter) async {
        guard !didRun else { return }
        didRun = true

        let arguments = ProcessInfo.processInfo.arguments

        // T49：角色人设编辑全链路自检（--persona-selftest：新建→列表→编辑→DB 读回→删除）
        if arguments.contains("--persona-selftest") {
            await PersonaAutomation.runIfNeeded(environment: environment)
        }
        if arguments.contains("--persona-cleanup") {
            await PersonaAutomation.runCleanupIfNeeded(environment: environment)
        }

        if arguments.contains("--roles-selftest") {
            await runRolesSelfTest(environment: environment, router: router)
        }

        // T52：上传头像渲染自检（--avatar-render-selftest：真实媒体入库 → 列表/详情真实图片渲染）
        if arguments.contains("--avatar-render-selftest") {
            await AvatarRenderAutomation.runIfNeeded(environment: environment, router: router)
        }

        if let index = arguments.firstIndex(of: "--autochat"), index + 1 < arguments.count {
            let operatorId = arguments[index + 1]
            await autochat(operatorId: operatorId, environment: environment, router: router)
        }
    }

    // MARK: 角色/关系页全链路自检

    private static func runRolesSelfTest(environment: AppEnvironment, router: AppRouter) async {
        SelfTestLog.add("roles selftest started")

        let rolesResult = await environment.bridge.loadRoles()
        switch rolesResult {
        case .success(let roles):
            let withSession = roles.filter(\.hasSession).count
            // builtin 值域仅解析兼容（旧 147 头像资源已删除，渲染走首字占位）
            let builtinAvatars = roles.filter { RoleAvatarResolver.source(for: $0.avatarId).isBuiltin }.count
            SelfTestLog.add("roles selftest list count=\(roles.count) withSession=\(withSession) builtinAvatar=\(builtinAvatars) first=\(roles.first?.name ?? "-")")
            SelfTestLog.add("roles selftest bundledAvatars=\(RoleAvatarProbe.bundledAvatarCount())/0 legacyAvatarsRemoved=true")

            guard let first = roles.first else {
                SelfTestLog.add("roles selftest list empty")
                return
            }

            switch await environment.bridge.loadRole(id: first.id) {
            case .success(let role):
                SelfTestLog.add("roles selftest detail id=\(first.id) found=\(role != nil) name=\(role?.name ?? "-") level=\(role?.level ?? -1) relation=\(role?.relationType ?? "-") impression=\(role?.impression.isEmpty == false ? "yes" : "no") session=\(role?.sessionId ?? "-")")
            case .failure(let error):
                SelfTestLog.add("roles selftest detail failed error=\(error.message)")
            }

            switch await environment.bridge.loadRelations(operatorId: first.id) {
            case .success(let relations):
                SelfTestLog.add("roles selftest relations count=\(relations.count) role=\(first.id)")
            case .failure(let error):
                SelfTestLog.add("roles selftest relations failed error=\(error.message)")
            }

            // 找一位「尚无会话」的角色验证新建会话；没有则用第一位（复用会话路径）
            let target = roles.first(where: { !$0.hasSession }) ?? first
            switch await environment.bridge.startChat(operatorId: target.id) {
            case .success(let sessionId):
                SelfTestLog.add("roles selftest startChat role=\(target.id) session=\(sessionId)")
                let after = await environment.bridge.loadRoles()
                if case .success(let updated) = after {
                    let created = updated.first(where: { $0.id == target.id })
                    SelfTestLog.add("roles selftest session linked role=\(target.id) mode=\(created?.mode ?? "-") sessionId=\(created?.sessionId ?? "-")")
                }
            case .failure(let error):
                SelfTestLog.add("roles selftest startChat failed role=\(target.id) error=\(error.message)")
            }

            await environment.refreshCounts()
            SelfTestLog.add("roles selftest done sessions=\(environment.sessionCount)")
        case .failure(let error):
            SelfTestLog.add("roles selftest list failed error=\(error.message)")
        }

        // 导航到角色 Tab（截图脚本可配合 --roles-selftest 直接落在角色列表）
        router.selectedTab = .contacts
    }

    // MARK: 新建会话 → 进入聊天

    private static func autochat(operatorId: String, environment: AppEnvironment, router: AppRouter) async {
        switch await environment.bridge.loadRole(id: operatorId) {
        case .success(let role):
            guard let role else {
                SelfTestLog.add("autochat role not found id=\(operatorId)")
                return
            }
            if role.hasSession {
                SelfTestLog.add("autochat existing role=\(operatorId) session=\(role.sessionId)")
                router.openChat(sessionId: role.sessionId)
            } else {
                switch await environment.bridge.startChat(operatorId: operatorId) {
                case .success(let sessionId):
                    SelfTestLog.add("autochat created role=\(operatorId) session=\(sessionId)")
                    router.openChat(sessionId: sessionId)
                case .failure(let error):
                    SelfTestLog.add("autochat failed role=\(operatorId) error=\(error.message)")
                }
            }
            await environment.refreshCounts()
        case .failure(let error):
            SelfTestLog.add("autochat load role failed id=\(operatorId) error=\(error.message)")
        }
    }
}
