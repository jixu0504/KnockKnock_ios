//
//  RoleDetailView.swift
//  角色详情（A2 二级页，T36）：资料卡 / 关系 / 印象 / 等级 + 进入聊天。
//  数据：shared `RoleBridge.find(roleId:)`（真实 operators + chat_sessions + memories），
//  关系网：`RoleBridge.relations(operatorId:)`（relationships 表双向）。
//  「开始聊天」：无会话 → RoleBridge.startChat 创建/复用 `session_<operatorId>` → 打开聊天页；
//  有会话 → 直接打开聊天页（AppRouter.openChat，跨 Tab 导航）。
//
//  P2「敲敲」改版（docs/敲敲UI改版计划书-v1.1.md）：
//  - 自绘 44pt 顶栏（返回胶囊 + 左对齐 heavy 标题 + 编辑胶囊），隐藏系统导航栏；
//  - 头部资料大卡片 `KnockPanel`；标签用 `KnockChip`（未选中 = 行底 + 描边）；
//  - 关系网入口行（分组标题 + 黄色「管理」链接 + 胶囊行）；
//  - 操作按钮：主 = `KnockPrimaryButtonStyle`（黄胶囊黑字）/ 次 = `KnockSecondaryButtonStyle`（描边）；
//  - 只改视觉层：加载 / 保存 / 开始聊天 / 自检日志 / 关系网跳转全部保留。
//

import SwiftUI

struct RoleDetailView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter
    @Environment(\.dismiss) private var dismiss

    /// operatorId；兼容 T33 旧深链 `rhodes://role/{sessionId}`（RoleBridge 内部反查会话）。
    let roleId: String

    @State private var role: RoleItem?
    @State private var relations: [RelationItem] = []
    @State private var isLoading = true
    @State private var errorText: String?
    @State private var isStartingChat = false
    @State private var chatError: String?
    /// T49：编辑角色人设入口（新建/编辑共用 RoleEditView）
    @State private var showRoleEditor = false
    /// T57：关系网编辑入口（角色详情「关系网 · 管理」→ RelationsView）
    @State private var showRelations = false

    /// 关系网卡片锚点（T57 `--t57-role-detail-scroll` 截图钩子用）
    static let relationsAnchor = "role-detail-relations"

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0)

            VStack(spacing: 0) {
                header

                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: KnockTokens.spacingM) {
                            if isLoading && role == nil {
                                loadingCard
                            } else if let errorText {
                                KnockEmptyState(
                                    title: "角色读取失败",
                                    message: errorText,
                                    systemImage: "exclamationmark.triangle",
                                    actionTitle: "重试",
                                    action: { Task { await load() } }
                                )
                            } else if let role {
                                profileCard(role)
                                if !role.hasSession {
                                    emptySessionCard(role)
                                }
                                levelCard(role)
                                impressionCard(role)
                                descriptionCard(role)
                                relationsCard(role).id(Self.relationsAnchor)
                                chatActionSection(role)
                            } else {
                                KnockEmptyState(
                                    title: "角色不存在",
                                    message: "shared operators 表中未找到角色 \(roleId)。",
                                    systemImage: "person.crop.circle.badge.questionmark"
                                )
                            }
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.top, KnockTokens.spacingS)
                        .padding(.bottom, KnockTokens.spacingXXL)
                    }
                    .scrollIndicators(.hidden)
                    .task {
                        // T57 截图钩子：`--t57-role-detail-scroll` 滚到关系网卡（管理入口可见）
                        guard ProcessInfo.processInfo.arguments.contains("--t57-role-detail-scroll") else { return }
                        try? await Task.sleep(nanoseconds: 900_000_000)
                        withAnimation(.none) { proxy.scrollTo(Self.relationsAnchor, anchor: .top) }
                    }
                }
            }
        }
        .knockHideSystemChrome()
        .sheet(isPresented: $showRoleEditor) {
            RoleEditView(
                roleId: role?.id ?? roleId,
                onSaved: { item in
                    SelfTestLog.add("role detail edit saved id=\(item.id) name=\(item.name)")
                    Task { await load() }
                },
                onDeleted: { id in
                    SelfTestLog.add("role detail edit deleted id=\(id)")
                    dismiss()
                }
            )
        }
        .task { await load() }
        .navigationDestination(isPresented: $showRelations) {
            if let role {
                RelationsView(operatorId: role.id, operatorName: role.name)
            }
        }
        .task {
            // T57 截图钩子：关系网演示（演示夹具由 shared `--relations-demo` 启动钩子写入）
            let args = ProcessInfo.processInfo.arguments
            guard args.contains("--relations-demo") || args.contains("--relations-demo-add") else { return }
            // 轮询等待角色数据加载完成（冷启动 bridge 初始化可能 > 1.2s），再打开关系网
            for _ in 0..<40 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if role != nil {
                    showRelations = true
                    SelfTestLog.add("role detail relations demo open role=\(roleId) name=\(role?.name ?? "-")")
                    return
                }
            }
        }
    }

    // MARK: - 顶栏（隐藏系统导航栏后的自绘 44pt 顶栏）

    private var header: some View {
        KnockRoleHeader(
            title: role?.name ?? "角色详情",
            subtitle: role?.title,
            showsBack: true,
            onBack: { dismiss() }
        ) {
            // T49：角色详情「编辑」入口 → RoleEditView（保存后刷新详情）
            KnockCapsuleIconButton(
                systemImage: "square.and.pencil",
                variant: .neutral,
                accessibilityLabel: "编辑角色"
            ) {
                showRoleEditor = true
            }
        }
    }

    private var loadingCard: some View {
        KnockPanel {
            HStack(spacing: KnockTokens.spacingM) {
                ProgressView()
                    .tint(KnockTokens.electric)
                KnockText("读取角色资料…", role: .caption, color: KnockTokens.textSecondary)
                Spacer(minLength: 0)
            }
        }
    }

    // MARK: - 资料大卡片

    private func profileCard(_ role: RoleItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                HStack(alignment: .center, spacing: KnockTokens.spacingL) {
                    RoleAvatar(avatarId: role.avatarId, name: role.name, size: 92)

                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        KnockText(role.name, role: .brand, lineLimit: 2)
                        if !role.title.isEmpty {
                            KnockText(role.title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 2)
                        }
                    }
                    Spacer(minLength: 0)
                }

                // 标签 Chips 独占一行（避免被头像列宽挤压截断）
                infoChipRow(role)

                if !role.subtitle.isEmpty {
                    KnockText(role.subtitle, role: .caption, color: KnockTokens.textTertiary, lineLimit: 2)
                }

                HStack(spacing: KnockTokens.spacingS) {
                    KnockInfoCell(title: "位置", value: role.location)
                    KnockInfoCell(title: "状态", value: role.activity)
                    KnockInfoCell(title: "心情", value: role.emotion)
                }
            }
        }
    }

    /// 标签 Chips：关系 / 性别 / 等级（未选中 = 行底 + 次描边 + 主文字）。
    /// 用横向滚动兜底：Dynamic Type 大字号 / 长关系名不会把 Chip 压成省略号。
    private func infoChipRow(_ role: RoleItem) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: KnockTokens.spacingXS) {
                KnockChip(title: role.relationLabel, systemImage: "link")
                if !role.gender.isEmpty {
                    KnockChip(title: role.gender, systemImage: "person.fill")
                }
                KnockChip(title: role.levelText, systemImage: "heart.fill")
            }
            .padding(.vertical, 1)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityLabel("关系 \(role.relationLabel)，\(role.gender.isEmpty ? "性别未设定" : role.gender)，\(role.levelText)")
    }

    // MARK: - 等级 / 关系

    private func levelCard(_ role: RoleItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingS) {
                    KnockGroupTitle(title: "等级与关系", systemImage: "chart.line.uptrend.xyaxis")
                    KnockText("\(role.levelText) · 亲密 \(role.level)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                }

                KnockProgressBar(progress: Double(role.level) / 100, height: 6)

                HStack(spacing: KnockTokens.spacingS) {
                    KnockText("与你的关系：\(role.relationLabel)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    Spacer(minLength: KnockTokens.spacingS)
                    KnockText(
                        role.hasSession ? "已有会话 · \(role.modeText)" : "尚未开始对话",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineLimit: 1
                    )
                }
            }
        }
    }

    // MARK: - 印象（长期印象 L3；无记忆时回退人设简介）

    private func impressionCard(_ role: RoleItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    KnockGroupTitle(title: "长期印象", systemImage: "brain.head.profile")
                    KnockText(
                        role.impressionText.isEmpty ? "暂无印象记忆" : "来自 memories · L3",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineLimit: 1
                    )
                }

                KnockText(
                    role.impressionText.isEmpty
                        ? "还没有与 \(role.name) 的长期印象。持续对话后，记忆管线会把长期印象写入 memories（L3），并在此展示。"
                        : role.impressionText,
                    role: .body,
                    color: role.impressionText.isEmpty ? KnockTokens.textSecondary : KnockTokens.textPrimary,
                    lineSpacing: 3
                )
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

                if role.impressionText.isEmpty {
                    KnockEmptyPill()
                }
            }
        }
    }

    private func descriptionCard(_ role: RoleItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "角色资料", systemImage: "text.book.closed")
                KnockText(
                    role.intro.isEmpty ? "暂无角色简介。" : role.intro,
                    role: .body,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 3
                )
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    // MARK: - 关系网（relationships 表双向）

    private func relationsCard(_ role: RoleItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                // 关系网入口行：分组标题 + 条数角标 + 黄色「管理」链接
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "person.3.sequence.fill")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textSecondary)
                        .accessibilityHidden(true)
                    KnockText("关系网", role: .sectionTitle, lineLimit: 1)
                    KnockBadge(text: "\(relations.count) 条", variant: .neutral)
                    Spacer(minLength: KnockTokens.spacingS)
                    Button {
                        showRelations = true
                    } label: {
                        HStack(spacing: 2) {
                            KnockText("管理", role: .sectionTitle, color: KnockTokens.textPrimary, lineLimit: 1)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .heavy))
                                .foregroundStyle(KnockTokens.textPrimary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("管理\(role.name)的关系网")
                }
                .accessibilityAddTraits(.isHeader)

                if relations.isEmpty {
                    KnockText(
                        "暂无关系网。建立关系后，亲密度越高越容易传递公开印象。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                    .fixedSize(horizontal: false, vertical: true)

                    KnockDashedButton(
                        title: "添加关系",
                        systemImage: "person.badge.plus"
                    ) {
                        showRelations = true
                    }
                } else {
                    VStack(spacing: KnockTokens.spacingS) {
                        ForEach(relations) { relation in
                            relationRow(relation)
                        }
                    }
                }
            }
        }
    }

    private func relationRow(_ relation: RelationItem) -> some View {
        Button {
            router.contactsPath.append(Route.roleDetail(roleId: relation.direction == "out"
                                                       ? relation.relatedOperatorId
                                                       : relation.operatorId))
        } label: {
            HStack(spacing: KnockTokens.spacingM) {
                RoleAvatar(avatarId: "", name: relation.relatedOperatorName, size: 34)

                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText(relation.relatedOperatorName, role: .rowTitle, lineLimit: 1)
                    KnockText(
                        relation.note.isEmpty ? relation.directionText : relation.note,
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineLimit: 1
                    )
                }

                Spacer(minLength: KnockTokens.spacingS)

                KnockBadge(text: relation.typeText, variant: .neutral)
                KnockText("\(relation.intimacy)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                KnockChevron()
            }
            .padding(.horizontal, KnockTokens.rowPaddingLeading)
            .frame(minHeight: KnockTokens.rowMinHeight)
            .background(Capsule(style: .continuous).fill(KnockTokens.row))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(relation.relatedOperatorName)，\(relation.typeText)，亲密度 \(relation.intimacy)")
    }

    // MARK: - 会话状态 + 进入聊天

    private func emptySessionCard(_ role: RoleItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                KnockGroupTitle(title: "尚无会话", systemImage: "bubble.left.and.bubble.right")
                KnockText(
                    "与 \(role.name) 还没有会话。点下方「开始聊天」会创建 stable 会话 session_\(role.id) 并打开聊天页。",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func chatActionSection(_ role: RoleItem) -> some View {
        VStack(spacing: KnockTokens.spacingM) {
            // 主 CTA：黄胶囊黑字（一屏最多一个黄主按钮）
            Button {
                Task { await startChat(role) }
            } label: {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: isStartingChat ? "hourglass" : "bubble.left.and.bubble.right.fill")
                        .font(.system(size: 15, weight: .heavy))
                    Text(isStartingChat ? "正在准备会话…" : (role.hasSession ? "进入聊天" : "开始聊天"))
                }
            }
            .buttonStyle(KnockPrimaryButtonStyle())
            .disabled(isStartingChat)
            .accessibilityLabel(role.hasSession ? "进入与 \(role.name) 的聊天" : "开始与 \(role.name) 聊天")

            // 次按钮：描边（编辑人设）
            Button {
                showRoleEditor = true
            } label: {
                Text("编辑角色人设")
            }
            .buttonStyle(KnockSecondaryButtonStyle())
            .accessibilityLabel("编辑角色人设")

            if role.hasSession, !role.lastMessage.isEmpty {
                lastMessageCard(role)
            }

            if let chatError {
                errorCard(chatError)
            }
        }
    }

    private func lastMessageCard(_ role: RoleItem) -> some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                KnockText("最近对话 · \(role.lastTimeText)", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                KnockText(role.lastMessage, role: .caption, color: KnockTokens.textSecondary, lineLimit: 3)
            }
        }
    }

    private func errorCard(_ text: String) -> some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(KnockTokens.dangerBright)
                    .accessibilityHidden(true)
                KnockText(text, role: .caption, color: KnockTokens.dangerBright, lineLimit: 4)
                Spacer(minLength: 0)
            }
        }
    }

    // MARK: - 数据

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        chatError = nil

        switch await environment.bridge.loadRole(id: roleId) {
        case .success(let item):
            role = item
            errorText = nil
            SelfTestLog.add("role detail loaded roleId=\(roleId) found=\(item != nil) name=\(item?.name ?? "-") session=\(item?.sessionId ?? "-") level=\(item?.level ?? -1) impression=\(item?.impression.isEmpty == false ? "yes" : "no") avatar=\(item.map { RoleAvatarProbe.description(avatarId: $0.avatarId) } ?? "-")")
            if let item {
                if case .success(let items) = await environment.bridge.loadRelations(operatorId: item.id) {
                    relations = items
                } else {
                    relations = []
                }
                SelfTestLog.add("role relations count=\(relations.count) role=\(item.id)")
            }
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("role detail load failed roleId=\(roleId) error=\(error.message)")
        }
    }

    private func startChat(_ role: RoleItem) async {
        chatError = nil
        if role.hasSession {
            router.openChat(sessionId: role.sessionId)
            SelfTestLog.add("role chat open existing role=\(role.id) session=\(role.sessionId)")
            return
        }

        isStartingChat = true
        defer { isStartingChat = false }

        switch await environment.bridge.startChat(operatorId: role.id) {
        case .success(let sessionId):
            SelfTestLog.add("role start chat created role=\(role.id) session=\(sessionId)")
            router.openChat(sessionId: sessionId)
        case .failure(let error):
            chatError = error.message
            SelfTestLog.add("role start chat failed role=\(role.id) error=\(error.message)")
        }
    }
}