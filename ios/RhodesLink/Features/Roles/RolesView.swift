//
//  RolesView.swift
//  角色列表（Tab 根，A2 / T36）：数据来自 shared `RoleBridge`（真实 SQLite
//  operators + chat_sessions + memories）：
//  - 头像：`RoleAvatar`（敲敲占位：黑底 + 1.5pt 浅蓝圈 + 白色 heavy 首字；原 147 内置头像默认不展示，
//    上传头像保留并套蓝圈）；
//  - 关系/等级：operators.userRelation / intimacy；
//  - 会话状态：ChatSession 的 mode / lastMessage / lastTime（无会话显示「尚未开始对话」）。
//  导航：点击 → Route.roleDetail(roleId:)（operatorId；兼容旧 sessionId 深链）。
//  T40：右上角「+」新建聊天（对齐 Android ContactsScreen 的新建入口：选角色 → 创建/复用会话
//        → 进入聊天，路由落本 Tab 的 contactsPath，不再占用聊天 Tab 工具栏）。
//  T49：右上角「新建角色」入口（person.badge.plus），与新建聊天/新建群聊入口并存不冲突。
//
//  P2「敲敲」改版（docs/敲敲UI改版计划书-v1.1.md）：
//  - 大标题 + `KnockSearchBar` + `KnockSegmentedBar` 二级筛选（图 B 条形栏形态）；
//  - 角色/群聊行 = `KnockListRow` 胶囊行（行底 + 1.5pt 描边 + 蓝圈头像）；
//  - 空态 = `KnockEmptyState`（E M P T Y 宽字距 pill + NoData 行）；
//  - 隐藏系统导航栏，iOS 26+ 关闭滚动边缘玻璃（`.knockHideSystemChrome()`）。
//  只改视觉层：数据加载 / 自检钩子 / 路由 / 日志断言全部保留。
//

import SwiftUI

/// 角色列表二级筛选（纯展示过滤；不改数据层与 bridge 调用）。
enum RoleListFilter: String, CaseIterable, Hashable, Identifiable {
    case all
    case withSession
    case withoutSession

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部"
        case .withSession: return "有会话"
        case .withoutSession: return "未开始"
        }
    }
}

struct RolesView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter

    @State private var roles: [RoleItem] = []
    /// T43 群聊分区（真实 chat_sessions：operatorId LIKE 'group_%'）
    @State private var groups: [GroupItem] = []
    @State private var showNewGroup = false
    @State private var searchText = ""
    @State private var isLoading = false
    @State private var errorText: String?
    /// P2：二级筛选（全部 / 有会话 / 未开始）
    @State private var filter: RoleListFilter = .all
    /// T49：角色人设编辑（.sheet(item:) 携带目标，避免 isPresented 闭包读到旧状态）
    @State private var roleEditTarget: RoleEditTarget?

    private var filteredRoles: [RoleItem] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return roles.filter { role in
            if !keyword.isEmpty {
                let matched = role.name.localizedCaseInsensitiveContains(keyword)
                    || role.title.localizedCaseInsensitiveContains(keyword)
                    || role.relationLabel.localizedCaseInsensitiveContains(keyword)
                guard matched else { return false }
            }
            switch filter {
            case .all: return true
            case .withSession: return role.hasSession
            case .withoutSession: return !role.hasSession
            }
        }
    }

    private var sessionCount: Int { roles.filter(\.hasSession).count }

    private var filterItems: [KnockSegmentItem<RoleListFilter>] {
        RoleListFilter.allCases.map { KnockSegmentItem(value: $0, title: $0.title) }
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            ScrollView {
                LazyVStack(spacing: KnockTokens.spacingM) {
                    titleSection
                    KnockSearchBar(placeholder: "搜索角色 / 身份 / 关系", text: $searchText)
                    KnockSegmentedBar(
                        items: filterItems,
                        selection: $filter,
                        height: KnockTokens.segmentedSmallHeight,
                        showsIcons: false
                    )
                    .accessibilityLabel("角色筛选")

                    groupSection
                    contentSection
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.top, KnockTokens.spacingS)
                .padding(.bottom, KnockTokens.spacingXL)
            }
            .scrollIndicators(.hidden)
            .refreshable { await reload() }
        }
        .knockHideSystemChrome()
        .task {
            await reload()
            // T43 群聊全链路自检（--group-selftest；真实引擎 + DB + 深链，独立日志落盘）
            // T52：`--group-unread-selftest` 走同一入口（群聊未读 +N / 打开清零）
            if GroupChatAutomation.isEnabled || GroupChatAutomation.unreadSelfTestEnabled {
                await GroupChatAutomation.runIfNeeded(environment: environment, router: router)
            }
            // T49 角色人设编辑截图/自动化：--persona-edit-new / --persona-edit-id <id>
            if PersonaAutomation.editRequested {
                try? await Task.sleep(nanoseconds: 400_000_000)
                openRoleEditor(roleId: PersonaAutomation.editTarget)
            }
        }
        .task {
            // T52：群聊未读角标近实时刷新——联系人页停留期间每 5 秒重载群列表
            // （新消息 UnreadHint → chat_sessions.unreadCount 变化后，无需手动下拉即可看到角标）
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if Task.isCancelled { break }
                await reloadGroups()
            }
        }
        .sheet(isPresented: $showNewGroup) {
            NewGroupView { sessionId in
                router.openGroupChat(sessionId: sessionId)
                Task { await reloadGroups() }
            }
        }
        .sheet(item: $roleEditTarget) { target in
            RoleEditView(
                roleId: target.operatorId,
                onSaved: { item in
                    SelfTestLog.add("roles roleEdit saved id=\(item.id) name=\(item.name)")
                    Task { await reload() }
                },
                onDeleted: { id in
                    SelfTestLog.add("roles roleEdit deleted id=\(id)")
                    Task { await reload() }
                }
            )
        }
    }

    // MARK: - 大标题 + 顶部操作（替代系统导航栏 toolbar）

    private var titleSection: some View {
        HStack(alignment: .center, spacing: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText("角色", role: .display, lineLimit: 1)
                KnockText(
                    "\(roles.count) 位角色 · \(sessionCount) 个会话进行中",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineLimit: 1
                )
            }

            Spacer(minLength: KnockTokens.spacingS)

            // T49：新建角色入口（原 toolbar person.badge.plus）
            KnockCapsuleIconButton(
                systemImage: "person.badge.plus",
                variant: .neutral,
                accessibilityLabel: "新建角色"
            ) {
                openRoleEditor(roleId: nil)
            }

            // T40：新建聊天入口（主 CTA = 黄胶囊）
            KnockCapsuleIconButton(
                systemImage: "plus",
                variant: .accent,
                accessibilityLabel: "新建聊天"
            ) {
                router.openNewChatFromContacts()
            }
        }
        .padding(.bottom, KnockTokens.spacingXS)
    }

    // MARK: - 群聊分区（T43：列表 + 进入群聊 + 新建群聊）

    private var groupSection: some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "person.3.fill")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textSecondary)
                        .accessibilityHidden(true)
                    KnockText("群聊", role: .sectionTitle, lineLimit: 1)
                    KnockBadge(text: "\(groups.count)", variant: .neutral)
                    Spacer(minLength: KnockTokens.spacingS)
                    Button {
                        showNewGroup = true
                        GroupSelfTestLog.add("roles newGroup opened groups=\(groups.count)")
                    } label: {
                        KnockText("新建群聊", role: .sectionTitle, color: KnockTokens.textPrimary, lineLimit: 1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("新建群聊")
                }
                .accessibilityAddTraits(.isHeader)

                if groups.isEmpty {
                    KnockText(
                        "还没有群聊。点「新建群聊」选择成员创建；首次打开会自动播种预设群。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                    .fixedSize(horizontal: false, vertical: true)
                } else {
                    VStack(spacing: KnockTokens.spacingS) {
                        ForEach(groups) { group in
                            groupRow(group)
                        }
                    }
                }
            }
        }
    }

    private func groupRow(_ group: GroupItem) -> some View {
        let subtitle = [
            group.lastTimeText,
            group.memberCountText,
            group.modeText,
            group.mutedCount > 0 ? "\(group.mutedCount) 人禁言" : "",
            group.lastMessage.isEmpty ? "（暂无群消息）" : group.lastMessage,
        ]
        .filter { !$0.isEmpty }
        .joined(separator: " · ")

        return KnockListRow(
            title: group.name,
            subtitle: subtitle,
            height: KnockTokens.rowMinHeight,
            action: {
                router.contactsPath.append(Route.groupChat(sessionId: group.sessionId))
                GroupSelfTestLog.add("roles openGroup id=\(group.sessionId) name=\(group.name) members=\(group.memberCount)")
            }
        ) {
            KnockRowGlyph(systemImage: "person.3.fill")
        } trailing: {
            HStack(spacing: KnockTokens.spacingS) {
                if group.unreadCount > 0 {
                    // 未读 = 聊天语义 → 聊天蓝角标（黄只做选中/主 CTA）
                    KnockBadge(text: "\(group.unreadCount)", variant: .chat)
                }
                KnockChevron()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("群聊 \(group.name)，\(group.memberCountText)")
        .accessibilityHint("打开群聊")
    }

    // MARK: - 角色列表 / 状态

    @ViewBuilder
    private var contentSection: some View {
        if let errorText {
            KnockEmptyState(
                title: "角色读取失败",
                message: errorText,
                systemImage: "exclamationmark.triangle",
                actionTitle: "重试",
                action: { Task { await reload() } }
            )
        } else if isLoading && roles.isEmpty {
            loadingCard
        } else if roles.isEmpty {
            KnockEmptyState(
                title: "还没有角色",
                message: "shared operators 表为空。首次进入会自动补齐预设角色；若仍为空，可尝试重新播种。",
                systemImage: "person.2",
                actionTitle: "重新播种",
                action: {
                    Task {
                        _ = await environment.bridge.bootstrap()
                        await reload()
                    }
                }
            )
        } else if filteredRoles.isEmpty {
            KnockEmptyState(
                title: "没有匹配的角色",
                message: "没有名字 / 身份 / 关系匹配「\(searchText)」的角色。",
                systemImage: "magnifyingglass"
            )
        } else {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    KnockText("干员", role: .sectionTitle, lineLimit: 1)
                    KnockBadge(text: "\(filteredRoles.count)", variant: .neutral)
                    Spacer(minLength: KnockTokens.spacingS)
                }
                .accessibilityAddTraits(.isHeader)

                ForEach(filteredRoles) { role in
                    roleRow(role)
                }
            }
        }
    }

    private var loadingCard: some View {
        KnockPanel {
            HStack(spacing: KnockTokens.spacingM) {
                ProgressView()
                    .tint(KnockTokens.electric)
                KnockText("读取 shared 角色数据…", role: .caption, color: KnockTokens.textSecondary)
                Spacer(minLength: 0)
            }
        }
    }

    // MARK: - 角色行（胶囊行；非玻璃）

    private func roleRow(_ role: RoleItem) -> some View {
        KnockListRow(
            title: role.name,
            subtitle: rowSubtitle(role),
            action: {
                router.contactsPath.append(Route.roleDetail(roleId: role.id))
            }
        ) {
            RoleAvatar(avatarId: role.avatarId, name: role.name, size: KnockTokens.rowIconSize)
        } trailing: {
            HStack(spacing: KnockTokens.spacingS) {
                VStack(alignment: .trailing, spacing: KnockTokens.spacingXXS) {
                    KnockBadge(text: role.levelText, variant: .neutral)
                    if role.hasSession {
                        KnockText(role.modeText, role: .badge, color: KnockTokens.textSecondary, lineLimit: 1)
                    } else {
                        KnockText("未建会话", role: .badge, color: KnockTokens.textTertiary, lineLimit: 1)
                    }
                }
                KnockChevron()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("角色 \(role.name)，\(role.title)，\(role.relationLabel)，\(role.levelText)")
        .accessibilityHint("打开角色详情")
    }

    /// 行副标题：身份 + 关系摘要 + 会话状态 / 最近一句（胶囊行只放最有辨识度的信息）。
    private func rowSubtitle(_ role: RoleItem) -> String {
        var parts: [String] = []
        if !role.title.isEmpty { parts.append(role.title) }
        parts.append(role.relationLabel)
        if role.hasSession {
            parts.append(role.lastMessage.isEmpty ? "（暂无对话）" : role.lastMessage)
        } else {
            parts.append("尚未开始对话")
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - 角色编辑（T49）

    private func openRoleEditor(roleId: String?) {
        roleEditTarget = RoleEditTarget(operatorId: roleId)
        SelfTestLog.add("roles openRoleEditor roleId=\(roleId ?? "new")")
    }

    // MARK: - 数据

    private func reload() async {
        isLoading = true
        defer { isLoading = false }

        switch await environment.bridge.loadRoles() {
        case .success(let items):
            roles = items
            errorText = nil
            SelfTestLog.add("roles loaded count=\(items.count) sessions=\(items.filter(\.hasSession).count) first=\(items.first?.id ?? "-") avatar=\(items.first.map { RoleAvatarProbe.description(avatarId: $0.avatarId) } ?? "-")")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("roles load failed error=\(error.message)")
        }

        await reloadGroups()
        await environment.refreshCounts()
    }

    /// 群聊列表（真实 chat_sessions；首次调用会幂等播种预设群）
    private func reloadGroups() async {
        switch await environment.bridge.loadGroups() {
        case .success(let items):
            groups = items
            GroupSelfTestLog.add("roles groups loaded count=\(items.count) first=\(items.first?.name ?? "-")")
        case .failure(let error):
            GroupSelfTestLog.add("roles groups load failed error=\(error.message)")
        }
    }
}