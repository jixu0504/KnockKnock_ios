//
//  RolesView.swift
//  角色列表（Tab 根，A2 / T36）：数据来自 shared `RoleBridge`（真实 SQLite
//  operators + chat_sessions + memories）：
//  - 头像：operators.avatarUri "1".."147" → Bundle 资源 avatar_<N>.png（T16 裁决），
//    其它值走占位色块 + 首字；
//  - 关系/等级：operators.userRelation / intimacy；
//  - 会话状态：ChatSession 的 mode / lastMessage / lastTime（无会话显示「尚未开始对话」）。
//  导航：点击 → Route.roleDetail(roleId:)（operatorId；兼容旧 sessionId 深链）。
//  T40：右上角「+」新建聊天（对齐 Android ContactsScreen 的新建入口：选角色 → 创建/复用会话
//        → 进入聊天，路由落本 Tab 的 contactsPath，不再占用聊天 Tab 工具栏）。
//  玻璃元素计数：头部卡片 1（一屏 ≤5 原则）。
//

import SwiftUI

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
    @State private var scrollOffset: CGFloat = 0
    /// T49：角色人设编辑（.sheet(item:) 携带目标，避免 isPresented 闭包读到旧状态）
    @State private var roleEditTarget: RoleEditTarget?

    private var filteredRoles: [RoleItem] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return roles }
        return roles.filter {
            $0.name.localizedCaseInsensitiveContains(keyword)
                || $0.title.localizedCaseInsensitiveContains(keyword)
                || $0.relationLabel.localizedCaseInsensitiveContains(keyword)
        }
    }

    private var sessionCount: Int { roles.filter(\.hasSession).count }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollView {
                LazyVStack(spacing: 10) {
                    headerCard
                    searchField
                    groupSection

                    if let errorText {
                        EmptyStateView(
                            title: "角色读取失败",
                            message: errorText,
                            systemImage: "exclamationmark.triangle",
                            actionTitle: "重试",
                            action: { Task { await reload() } }
                        )
                    } else if isLoading && roles.isEmpty {
                        ProgressView("读取 shared 角色数据…")
                            .padding(40)
                    } else if roles.isEmpty {
                        EmptyStateView(
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
                        EmptyStateView(
                            title: "没有匹配的角色",
                            message: "没有名字 / 身份 / 关系匹配「\(searchText)」的角色。",
                            systemImage: "magnifyingglass"
                        )
                    } else {
                        ForEach(filteredRoles) { role in
                            roleRow(role)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 16)
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
            .refreshable { await reload() }
        }
        .navigationTitle("联系人")
        .toolbar {
            // T40：对齐 Android ContactsScreen——「+」新建聊天入口在联系人 Tab
            // T49：新增「新建角色」入口（person.badge.plus），与新建聊天/新建群聊入口并存不冲突
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    openRoleEditor(roleId: nil)
                } label: {
                    Image(systemName: "person.crop.circle.badge.plus")
                }
                .accessibilityLabel("新建角色")
                .accessibilityHint("创建新的角色人设")

                Button {
                    router.openNewChatFromContacts()
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("新建聊天")
                .accessibilityHint("选择角色开始对话")
            }
        }
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

    // MARK: 群聊分区（T43：列表 + 进入群聊 + 新建群聊）

    private var groupSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Label("群聊", systemImage: "person.3.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text("\(groups.count)")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(GlassTokens.brandOrange)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
                Spacer(minLength: 4)
                Button {
                    showNewGroup = true
                    GroupSelfTestLog.add("roles newGroup opened groups=\(groups.count)")
                } label: {
                    Label("新建群聊", systemImage: "plus.circle.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(GlassTokens.brandOrange)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("新建群聊")
            }

            if groups.isEmpty {
                Text("还没有群聊。点「新建群聊」选择成员创建；首次打开会自动播种预设群。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 4)
            } else {
                ForEach(groups) { group in
                    groupRow(group)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    private func groupRow(_ group: GroupItem) -> some View {
        Button {
            router.contactsPath.append(Route.groupChat(sessionId: group.sessionId))
            GroupSelfTestLog.add("roles openGroup id=\(group.sessionId) name=\(group.name) members=\(group.memberCount)")
        } label: {
            HStack(alignment: .center, spacing: 10) {
                ZStack {
                    Circle()
                        .fill(GlassTokens.brandOrange.opacity(0.16))
                    Image(systemName: "person.3.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(GlassTokens.brandOrange)
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(group.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if group.unreadCount > 0 {
                            Text("\(group.unreadCount)")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(GlassTokens.brandOrange, in: Capsule())
                        }
                        Spacer(minLength: 0)
                        Text(group.lastTimeText)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Text("\(group.memberCountText) · \(group.modeText)\(group.mutedCount > 0 ? " · \(group.mutedCount) 人禁言" : "")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text(group.lastMessage.isEmpty ? "（暂无群消息）" : group.lastMessage)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("群聊 \(group.name)，\(group.memberCountText)")
        .accessibilityHint("打开群聊")
    }

    // MARK: 头部（玻璃 1）

    private var headerCard: some View {
        GlassPanel(style: .clear, cornerRadius: 20) {
            HStack(spacing: 12) {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(GlassTokens.brandOrange)
                VStack(alignment: .leading, spacing: 2) {
                    Text("我的干员")
                        .font(.subheadline.weight(.semibold))
                    Text("\(roles.count) 位角色 · \(sessionCount) 个会话进行中")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            TextField("搜索角色 / 身份 / 关系", text: $searchText)
                .textFieldStyle(.plain)
                .font(.subheadline)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .cardSurface(cornerRadius: 14)
    }

    // MARK: 角色行（非玻璃）

    private func roleRow(_ role: RoleItem) -> some View {
        Button {
            router.contactsPath.append(Route.roleDetail(roleId: role.id))
        } label: {
            HStack(alignment: .top, spacing: 12) {
                RoleAvatar(avatarId: role.avatarId, name: role.name, size: 54)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(role.name)
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(role.levelText)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(GlassTokens.brandOrange)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(GlassTokens.bubbleSystem, in: Capsule())
                        Spacer(minLength: 0)
                        Text(role.hasSession ? role.modeText : "未建会话")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }

                    if !role.subtitle.isEmpty {
                        Text(role.subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    HStack(spacing: 6) {
                        Label(role.relationLabel, systemImage: "link")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        if !role.emotion.isEmpty {
                            Text("· \(role.emotion)")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    }

                    Text(role.hasSession
                         ? (role.lastMessage.isEmpty ? "（暂无对话）" : role.lastMessage)
                         : "尚未开始对话 · 点入详情可直接发起")
                        .font(.subheadline)
                        .foregroundStyle(role.hasSession ? .secondary : .tertiary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 16)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 20)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("角色 \(role.name)，\(role.title)，\(role.relationLabel)，\(role.levelText)")
        .accessibilityHint("打开角色详情")
    }

    // MARK: 角色编辑（T49）

    private func openRoleEditor(roleId: String?) {
        roleEditTarget = RoleEditTarget(operatorId: roleId)
        SelfTestLog.add("roles openRoleEditor roleId=\(roleId ?? "new")")
    }

    // MARK: 数据

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
