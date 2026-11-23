//
//  NewChatView.swift
//  新建聊天入口（A1 / T36）：从聊天 Tab 右上角「+」进入，选择角色 → 创建/复用会话 →
//  进入聊天页。角色来自 shared `RoleBridge`（真实 operators 表），已存在会话的角色可直接进入。
//  路径与角色详情页「开始聊天」完全一致：RoleBridge.startChat（session_<operatorId>）→
//  AppRouter.openChat(sessionId:)。
//

import SwiftUI

struct NewChatView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter

    @State private var roles: [RoleItem] = []
    @State private var searchText = ""
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var busyRoleId: String?
    @State private var actionError: String?

    private var filteredRoles: [RoleItem] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return roles }
        return roles.filter {
            $0.name.localizedCaseInsensitiveContains(keyword)
                || $0.title.localizedCaseInsensitiveContains(keyword)
        }
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0)

            ScrollView {
                LazyVStack(spacing: 10) {
                    headerCard
                    searchField

                    if let actionError {
                        Label(actionError, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .cardSurface(cornerRadius: 14)
                    }

                    if let errorText {
                        EmptyStateView(
                            title: "角色读取失败",
                            message: errorText,
                            systemImage: "exclamationmark.triangle",
                            actionTitle: "重试",
                            action: { Task { await reload() } }
                        )
                    } else if isLoading && roles.isEmpty {
                        ProgressView("读取可选角色…").padding(40)
                    } else if roles.isEmpty {
                        EmptyStateView(
                            title: "没有可选角色",
                            message: "shared operators 表为空，无法新建聊天。",
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
                            message: "没有名字 / 身份匹配「\(searchText)」的角色。",
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
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle("新建聊天")
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
    }

    private var headerCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "square.and.pencil")
                .font(.system(size: 20))
                .foregroundStyle(GlassTokens.brandOrange)
            VStack(alignment: .leading, spacing: 2) {
                Text("选择一位角色开始对话")
                    .font(.subheadline.weight(.semibold))
                Text("已有会话的角色会直接打开；其余会创建 stable 会话 session_<id>")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .cardSurface(cornerRadius: 18)
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            TextField("搜索角色", text: $searchText)
                .textFieldStyle(.plain)
                .font(.subheadline)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .cardSurface(cornerRadius: 14)
    }

    private func roleRow(_ role: RoleItem) -> some View {
        Button {
            Task { await pick(role) }
        } label: {
            HStack(spacing: 12) {
                RoleAvatar(avatarId: role.avatarId, name: role.name, size: 46)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(role.name)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        if role.hasSession {
                            Text("已有会话")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 1)
                                .background(GlassTokens.bubbleSystem, in: Capsule())
                        }
                    }
                    Text(role.title.isEmpty ? role.relationLabel : role.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if busyRoleId == role.id {
                    ProgressView()
                } else {
                    Image(systemName: "bubble.left.fill")
                        .font(.caption)
                        .foregroundStyle(GlassTokens.brandOrange)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 18)
        }
        .buttonStyle(.plain)
        .disabled(busyRoleId != nil)
        .accessibilityLabel("\(role.name)，\(role.hasSession ? "已有会话" : "新建会话")")
        .accessibilityHint("进入与该角色的聊天")
    }

    private func reload() async {
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadRoles() {
        case .success(let items):
            roles = items
            errorText = nil
            SelfTestLog.add("new chat roles loaded count=\(items.count)")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("new chat roles load failed error=\(error.message)")
        }
    }

    private func pick(_ role: RoleItem) async {
        actionError = nil
        if role.hasSession {
            SelfTestLog.add("new chat open existing role=\(role.id) session=\(role.sessionId)")
            router.openChat(sessionId: role.sessionId)
            return
        }

        busyRoleId = role.id
        defer { busyRoleId = nil }

        switch await environment.bridge.startChat(operatorId: role.id) {
        case .success(let sessionId):
            SelfTestLog.add("new chat created role=\(role.id) session=\(sessionId)")
            router.openChat(sessionId: sessionId)
        case .failure(let error):
            actionError = error.message
            SelfTestLog.add("new chat create failed role=\(role.id) error=\(error.message)")
        }
    }
}
