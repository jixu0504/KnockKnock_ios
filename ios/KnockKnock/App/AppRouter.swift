//
//  AppRouter.swift
//  T33 路由表：4 个 Tab × 各自 NavigationStack 路径 + 统一 Route enum + 深链解析。
//
//  Tab 与 Android 原版对齐（AppNavigation.kt:87-93）：聊天 / 联系人 / 功能 / 设置（从左至右）。
//  - 聊天 Tab：会话列表 → 聊天页 / 新建聊天
//  - 联系人 Tab：联系人（干员）列表 → 联系人详情 / 新建聊天
//  - 功能 Tab：动态广场 / 干员日记 / 大家的印象 / 聊天排行榜 / 记忆管理（后续批次替换占位页）
//  - 设置 Tab：设置主页 → 外观 / 可访问性 / 模型供应商 / 数据与备份（后续批次接线）
//
//  深链（Info.plist CFBundleURLTypes: rhodes）：
//  - rhodes://chat/{sessionId}      打开聊天页
//  - rhodes://chat                  聊天列表
//  - rhodes://chat/new              新建聊天（选角色）
//  - rhodes://role/{operatorId}     打开联系人详情（旧 sessionId 亦兼容）
//  - rhodes://memory/{id}           记忆详情（功能 Tab）
//  - rhodes://features/{moments|diary|impressions|ranking|memory}  功能页子入口
//  - rhodes://settings/{section}    设置子页（appearance / accessibility / …）
//  - rhodes://group/{id}            群聊页（T43：真实 GroupChatEngine）
//  - 其它 host 也落到占位路由，保证不崩且可扩展
//

import SwiftUI

// MARK: - Tab（与 Android 原版一致：聊天 / 联系人 / 功能 / 设置）

enum AppTab: String, CaseIterable, Hashable, Identifiable {
    case chat
    case contacts
    case features
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .chat: return "聊天"
        case .contacts: return "联系人"
        case .features: return "功能"
        case .settings: return "设置"
        }
    }

    /// 敲敲 Tab 图标：SF Symbols **单色线性**（无 `.fill`，选中态由 KnockTabBar 的黄胶囊表达，
    /// 不再依赖系统填充/着色），统一 `.heavy` 字重在 KnockSegmentedBar 内施加。
    var systemImage: String {
        switch self {
        case .chat: return "bubble.left.and.bubble.right"
        case .contacts: return "person.2"
        case .features: return "square.grid.2x2"
        case .settings: return "gearshape"
        }
    }

    var accessibilityLabel: String { title }
}

// MARK: - 路由

enum Route: Hashable {
    case chat(sessionId: String)
    /// 新建聊天：选择角色 → 创建/复用会话 → 进入聊天
    case newChat
    /// 联系人详情。roleId = operatorId；T33 旧深链的 sessionId 由 RoleBridge 反查兼容。
    case roleDetail(roleId: String)
    /// T43 群聊页（groupId = chat_sessions.id，operatorId 以 group_ 前缀）
    case groupChat(sessionId: String)
    case memoryDetail(id: Int64)
    // 功能 Tab（与 Android FeaturesScreen 对齐）
    case moments
    case diary
    case impressions
    case ranking
    case memoryList
    /// T48 知识库列表（导入/切块/索引/召回）。P5 收口补齐：此前只能从「功能」Tab 的
    /// `NavigationLink { KnowledgeListView() }` 直达、无路由无深链，截图/回归无法稳定复现。
    case knowledgeList
    case settingsAppearance
    case settingsAccessibility
    /// T37 模型供应商（11 家 + 自定义端点 + Keychain 配置）
    case settingsProviders
    /// T38 数据与备份（完整备份 / 角色卡 / 聊天记录导入导出）
    case settingsBackup
    /// T46 聊天表现（温度 / 深度思考 / 历史轮数 / 回复长度上限）
    case settingsChatPerformance
    /// T46 提示词编辑器（PromptTemplates 分类 → 详情编辑）
    case settingsPromptEditor
    /// T53 通知设置（授权状态读取/请求 + 私聊/群聊/动态/日记 4 类提醒开关）
    case settingsNotifications
    /// T53 调试日志（shared IosDebugLogger 文件日志：级别/关键词筛选、导出、清空）
    case settingsDebugLogs
    /// 其余路由占位（Phase 3 逐步替换为正式页面）
    case placeholder(title: String, detail: String)

    var logDescription: String {
        switch self {
        case .chat(let id): return "chat(\(id))"
        case .newChat: return "newChat"
        case .roleDetail(let id): return "roleDetail(\(id))"
        case .groupChat(let id): return "groupChat(\(id))"
        case .memoryDetail(let id): return "memoryDetail(\(id))"
        case .moments: return "moments"
        case .diary: return "diary"
        case .impressions: return "impressions"
        case .ranking: return "ranking"
        case .memoryList: return "memoryList"
        case .knowledgeList: return "knowledgeList"
        case .settingsAppearance: return "settingsAppearance"
        case .settingsAccessibility: return "settingsAccessibility"
        case .settingsProviders: return "settingsProviders"
        case .settingsBackup: return "settingsBackup"
        case .settingsChatPerformance: return "settingsChatPerformance"
        case .settingsPromptEditor: return "settingsPromptEditor"
        case .settingsNotifications: return "settingsNotifications"
        case .settingsDebugLogs: return "settingsDebugLogs"
        case .placeholder(let title, _): return "placeholder(\(title))"
        }
    }
}

// MARK: - Router

@MainActor
final class AppRouter: ObservableObject {
    @Published var selectedTab: AppTab
    @Published var chatPath = NavigationPath()
    @Published var contactsPath = NavigationPath()
    @Published var featuresPath = NavigationPath()
    @Published var settingsPath = NavigationPath()

    /// 深链证据（自检日志 / 设置页诊断用）
    @Published private(set) var lastHandledLink: String?
    @Published private(set) var linkHistory: [String] = []

    init() {
        selectedTab = Self.initialTab()
        // 截图/调试钩子：`--deep-link rhodes://features/ranking` 启动时直接应用深链
        // （simctl openurl 会弹系统确认框，截图不可用）；无参数零影响
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--deep-link"), index + 1 < args.count,
           let url = URL(string: args[index + 1]) {
            _ = handle(url: url)
        }
    }

    /// `--tab chat|contacts|features|settings` 启动参数用于验证四 Tab（截图脚本使用）
    private static func initialTab() -> AppTab {
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--tab"), index + 1 < args.count {
            let raw = args[index + 1].lowercased()
            // 兼容旧参数值 roles / memory
            switch raw {
            case "roles": return .contacts
            case "memory": return .features
            default:
                if let tab = AppTab(rawValue: raw) { return tab }
            }
        }
        return .chat
    }

    // MARK: 导航动作

    func openChat(sessionId: String) {
        selectedTab = .chat
        chatPath = NavigationPath()
        chatPath.append(Route.chat(sessionId: sessionId))
        record("openChat \(sessionId)")
    }

    func openRoleDetail(roleId: String) {
        selectedTab = .contacts
        contactsPath = NavigationPath()
        contactsPath.append(Route.roleDetail(roleId: roleId))
        record("openRoleDetail \(roleId)")
    }

    /// 新建聊天入口（深链 / 自动化 / 空态兜底）：push 到聊天 Tab 的角色选择页。
    func openNewChat() {
        selectedTab = .chat
        chatPath = NavigationPath()
        chatPath.append(Route.newChat)
        record("openNewChat")
    }

    /// T43：打开群聊（联系人页群聊分区分组 / 深链 rhodes://group/{id}）
    func openGroupChat(sessionId: String) {
        selectedTab = .chat
        chatPath = NavigationPath()
        chatPath.append(Route.groupChat(sessionId: sessionId))
        record("openGroupChat \(sessionId)")
    }

    /// 新建聊天入口（T40：对齐 Android ContactsScreen——新建聊天在「联系人」Tab 右上角「+」）。
    func openNewChatFromContacts() {
        selectedTab = .contacts
        contactsPath = NavigationPath()
        contactsPath.append(Route.newChat)
        record("openNewChatFromContacts")
    }

    /// 功能页入口（动态/日记/印象/排行/记忆管理）。
    func openFeature(_ route: Route) {
        selectedTab = .features
        featuresPath = NavigationPath()
        featuresPath.append(route)
        record("openFeature \(route.logDescription)")
    }

    func pushSettings(_ route: Route, tab: AppTab = .settings) {
        selectedTab = tab
        switch tab {
        case .settings: settingsPath.append(route)
        case .chat: chatPath.append(route)
        case .contacts: contactsPath.append(route)
        case .features: featuresPath.append(route)
        }
        record("push \(route.logDescription)")
    }

    // MARK: 深链

    /// 处理 rhodes:// 深链；返回是否命中已注册路由。
    @discardableResult
    func handle(url: URL) -> Bool {
        // 同时接受新 scheme `knock` 与旧 `rhodes`（Info.plist 已改名 knock；旧链接保持兼容）
        guard let scheme = url.scheme?.lowercased(), scheme == "knock" || scheme == "rhodes" else {
            return false
        }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let host = (components?.host ?? "").lowercased()
        var segments = url.pathComponents.filter { $0 != "/" }
        // 兼容 rhodes://chat?sessionId=xxx 形式
        if segments.isEmpty,
           let queryValue = components?.queryItems?.first(where: { $0.name == "sessionId" || $0.name == "id" })?.value {
            segments = [queryValue]
        }
        components = nil

        lastHandledLink = url.absoluteString
        SelfTestLog.add("deeplink url=\(url.absoluteString) host=\(host) segments=\(segments)")

        switch host {
        case "chat":
            selectedTab = .chat
            chatPath = NavigationPath()
            if segments.first == "new" {
                chatPath.append(Route.newChat)
                record("deeplink chat new")
            } else if let sessionId = segments.first {
                chatPath.append(Route.chat(sessionId: sessionId))
                record("deeplink chat \(sessionId)")
            } else {
                record("deeplink chat list")
            }
            return true

        case "role", "roles", "contacts":
            selectedTab = .contacts
            contactsPath = NavigationPath()
            if let roleId = segments.first {
                contactsPath.append(Route.roleDetail(roleId: roleId))
                record("deeplink role \(roleId)")
            } else {
                record("deeplink contacts list")
            }
            return true

        case "memory", "memories":
            selectedTab = .features
            featuresPath = NavigationPath()
            if let raw = segments.first, let id = Int64(raw) {
                featuresPath.append(Route.memoryDetail(id: id))
                record("deeplink memory \(id)")
            } else {
                featuresPath.append(Route.memoryList)
                record("deeplink memory list")
            }
            return true

        case "features":
            selectedTab = .features
            featuresPath = NavigationPath()
            switch segments.first?.lowercased() {
            case "moments": featuresPath.append(Route.moments)
            case "diary": featuresPath.append(Route.diary)
            case "impressions": featuresPath.append(Route.impressions)
            case "ranking": featuresPath.append(Route.ranking)
            case "memory": featuresPath.append(Route.memoryList)
            case "knowledge", "knowledge-base", "knowledgebase":
                featuresPath.append(Route.knowledgeList)
            case .some(let section):
                featuresPath.append(Route.placeholder(
                    title: "功能 / \(section)",
                    detail: "该功能正在准备中，敬请期待。"
                ))
            case .none:
                break
            }
            record("deeplink features \(segments.first ?? "<root>")")
            return true

        case "settings":
            selectedTab = .settings
            settingsPath = NavigationPath()
            switch segments.first?.lowercased() {
            case "appearance":
                settingsPath.append(Route.settingsAppearance)
            case "accessibility":
                settingsPath.append(Route.settingsAccessibility)
            case "providers", "provider", "model":
                settingsPath.append(Route.settingsProviders)
            case "backup", "backups":
                settingsPath.append(Route.settingsBackup)
            case "chat-performance", "performance", "chatparams", "chat-params":
                settingsPath.append(Route.settingsChatPerformance)
            case "prompt", "prompts", "prompt-editor", "prompteditor":
                settingsPath.append(Route.settingsPromptEditor)
            // T53：通知设置 / 调试日志
            case "notifications", "notification", "notify":
                settingsPath.append(Route.settingsNotifications)
            case "debug-logs", "debuglogs", "logs", "log":
                settingsPath.append(Route.settingsDebugLogs)
            case .some(let section):
                settingsPath.append(Route.placeholder(
                    title: "设置 / \(section)",
                    detail: "该设置项正在准备中，敬请期待。"
                ))
            case .none:
                break
            }
            record("deeplink settings \(segments.first ?? "<root>")")
            return true

        case "group", "groups":
            selectedTab = .chat
            chatPath = NavigationPath()
            let groupId = segments.first ?? ""
            if groupId.isEmpty || groupId == "new" {
                record("deeplink group list")
            } else {
                chatPath.append(Route.groupChat(sessionId: groupId))
                record("deeplink group \(groupId)")
            }
            return true

        default:
            // 未知 host：落到当前 Tab 的占位路由（不崩、可扩展）
            let name = host.isEmpty ? "未命名" : host
            switch selectedTab {
            case .settings:
                settingsPath.append(Route.placeholder(title: "未知深链 /\(name)", detail: "暂时无法打开这个链接。"))
            case .chat:
                chatPath.append(Route.placeholder(title: "未知深链 /\(name)", detail: "暂时无法打开这个链接。"))
            case .contacts:
                contactsPath.append(Route.placeholder(title: "未知深链 /\(name)", detail: "暂时无法打开这个链接。"))
            case .features:
                featuresPath.append(Route.placeholder(title: "未知深链 /\(name)", detail: "暂时无法打开这个链接。"))
            }
            record("deeplink unknown \(url.absoluteString)")
            return false
        }
    }

    private func record(_ line: String) {
        linkHistory.append(line)
        if linkHistory.count > 20 { linkHistory.removeFirst(linkHistory.count - 20) }
        SelfTestLog.add("router \(line)")
    }
}
