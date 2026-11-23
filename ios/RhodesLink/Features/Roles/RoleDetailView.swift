//
//  RoleDetailView.swift
//  角色详情（A2 二级页，T36）：资料卡 / 关系 / 印象 / 等级 + 进入聊天。
//  数据：shared `RoleBridge.find(roleId:)`（真实 operators + chat_sessions + memories），
//  关系网：`RoleBridge.relations(operatorId:)`（relationships 表双向）。
//  「开始聊天」：无会话 → RoleBridge.startChat 创建/复用 `session_<operatorId>` → 打开聊天页；
//  有会话 → 直接打开聊天页（AppRouter.openChat，跨 Tab 导航）。
//  玻璃元素计数：资料卡 1（一屏 ≤5 原则）。
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

            ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    if isLoading && role == nil {
                        ProgressView("读取角色资料…").padding(40)
                    } else if let errorText {
                        EmptyStateView(
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
                        chatActionCard(role)
                    } else {
                        EmptyStateView(
                            title: "角色不存在",
                            message: "shared operators 表中未找到角色 \(roleId)。",
                            systemImage: "person.crop.circle.badge.questionmark"
                        )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
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
        .navigationTitle(role?.name ?? "角色详情")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // T49：角色详情「编辑」入口 → RoleEditView（保存后刷新详情）
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showRoleEditor = true
                } label: {
                    Label("编辑", systemImage: "square.and.pencil")
                }
                .accessibilityLabel("编辑角色")
            }
        }
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
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if role != nil { showRelations = true }
        }
    }

    // MARK: 资料卡（玻璃 1）

    private func profileCard(_ role: RoleItem) -> some View {
        GlassPanel(style: .regular, cornerRadius: 24) {
            VStack(spacing: 10) {
                RoleAvatar(avatarId: role.avatarId, name: role.name, size: 92)
                Text(role.name)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)
                if !role.title.isEmpty {
                    Text(role.title)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                HStack(spacing: 6) {
                    infoChip(role.relationLabel, systemImage: "link")
                    if !role.gender.isEmpty { infoChip(role.gender, systemImage: "person.fill") }
                    infoChip(role.levelText, systemImage: "heart.fill")
                }

                if !role.subtitle.isEmpty {
                    Text(role.subtitle)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .multilineTextAlignment(.center)
                }

                HStack(spacing: 8) {
                    stateChip("位置", role.location)
                    stateChip("状态", role.activity)
                    stateChip("心情", role.emotion)
                }
            }
            .padding(18)
        }
    }

    private func infoChip(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.caption2.weight(.medium))
            .foregroundStyle(GlassTokens.brandOrange)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(GlassTokens.bubbleSystem, in: Capsule())
    }

    private func stateChip(_ title: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.tertiary)
            Text(value.isEmpty ? "—" : value)
                .font(.caption.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    // MARK: 等级 / 关系

    private func levelCard(_ role: RoleItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("等级与关系", systemImage: "chart.line.uptrend.xyaxis")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("\(role.levelText) · 亲密 \(role.level)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: min(max(Double(role.level), 0), 100), total: 100)
                .tint(GlassTokens.brandOrange)
            HStack {
                Text("与你的关系：\(role.relationLabel)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(role.hasSession ? "已有会话 · \(role.modeText)" : "尚未开始对话")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: 印象（长期印象 L3；无记忆时回退人设简介）

    private func impressionCard(_ role: RoleItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("长期印象", systemImage: "brain.head.profile")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(role.impressionText.isEmpty ? "暂无印象记忆" : "来自 memories · L3")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Text(role.impressionText.isEmpty
                 ? "还没有与 \(role.name) 的长期印象。持续对话后，记忆管线会把长期印象写入 memories（L3），并在此展示。"
                 : role.impressionText)
                .font(.body)
                .foregroundStyle(role.impressionText.isEmpty ? .secondary : .primary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    private func descriptionCard(_ role: RoleItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("角色资料", systemImage: "text.book.closed")
                .font(.subheadline.weight(.semibold))
            Text(role.intro.isEmpty ? "暂无角色简介。" : role.intro)
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: 关系网（relationships 表双向）

    private func relationsCard(_ role: RoleItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Label("关系网", systemImage: "person.3.sequence.fill")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("\(relations.count) 条")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                // T57：关系网编辑入口（添加/删除/双向互补关系）
                Button {
                    showRelations = true
                } label: {
                    HStack(spacing: 2) {
                        Text("管理")
                            .font(.caption.weight(.semibold))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .foregroundStyle(GlassTokens.brandOrange)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("管理\(role.name)的关系网")
            }

            if relations.isEmpty {
                Text("暂无关系网。建立关系后，亲密度越高越容易传递公开印象。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ForEach(relations) { relation in
                    Button {
                        router.contactsPath.append(Route.roleDetail(roleId: relation.direction == "out"
                                                                 ? relation.relatedOperatorId
                                                                 : relation.operatorId))
                    } label: {
                        HStack(spacing: 10) {
                            RoleAvatar(avatarId: "", name: relation.relatedOperatorName, size: 34)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(relation.relatedOperatorName)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                Text(relation.note.isEmpty ? relation.directionText : relation.note)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Text(relation.typeText)
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(GlassTokens.brandOrange)
                            Text("\(relation.intimacy)")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                            Image(systemName: "chevron.right")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(relation.relatedOperatorName)，\(relation.typeText)，亲密度 \(relation.intimacy)")
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: 会话状态 + 进入聊天

    private func emptySessionCard(_ role: RoleItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("尚无会话", systemImage: "bubble.left.and.bubble.right")
                .font(.subheadline.weight(.semibold))
            Text("与 \(role.name) 还没有会话。点下方「开始聊天」会创建 stable 会话 session_\(role.id) 并打开聊天页。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    private func chatActionCard(_ role: RoleItem) -> some View {
        VStack(spacing: 0) {
            Button {
                Task { await startChat(role) }
            } label: {
                HStack {
                    Image(systemName: isStartingChat ? "hourglass" : "bubble.left.and.bubble.right.fill")
                    Text(isStartingChat ? "正在准备会话…" : (role.hasSession ? "进入聊天" : "开始聊天"))
                        .font(.body.weight(.semibold))
                    Spacer()
                    Image(systemName: "arrow.turn.down.right")
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isStartingChat)
            .accessibilityLabel(role.hasSession ? "进入与 \(role.name) 的聊天" : "开始与 \(role.name) 聊天")

            if role.hasSession, !role.lastMessage.isEmpty {
                Divider().padding(.leading, 14).opacity(0.4)
                VStack(alignment: .leading, spacing: 3) {
                    Text("最近对话 · \(role.lastTimeText)")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    Text(role.lastMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let chatError {
                Divider().padding(.leading, 14).opacity(0.4)
                Label(chatError, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .cardSurface(cornerRadius: 20)
    }

    // MARK: 数据

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
