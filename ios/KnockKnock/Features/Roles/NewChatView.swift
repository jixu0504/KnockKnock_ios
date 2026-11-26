//
//  NewChatView.swift
//  新建聊天入口（P1 视觉重写，业务逻辑零改动）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md：
//  - 黑底 + 斜切迷彩；顶栏自绘 `KnockHeader`（返回胶囊 + 左对齐 heavy 标题），隐藏系统导航栏；
//  - 说明面板 `KnockPanel` + 搜索条 `KnockSearchBar` + **胶囊角色行**（KnockListRow：
//    左 44pt 头像、角色名 heavy、身份副标题、右「已有会话」角标 / 聊天图标）；
//  - 空态/错误态用 `KnockEmptyState`（E M P T Y 宽字距 pill）。
//
//  能力保留：角色来自 shared `RoleBridge`（真实 operators 表），已存在会话的角色可直接进入；
//  路径与角色详情页「开始聊天」完全一致：RoleBridge.startChat（session_<operatorId>）→
//  AppRouter.openChat(sessionId:)；`SelfTestLog` 打点原样保留。
//

import SwiftUI

struct NewChatView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter
    @Environment(\.dismiss) private var dismiss

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
        VStack(spacing: 0) {
            header
            ZStack {
                KnockCamoBackground()

                ScrollView {
                    VStack(spacing: KnockTokens.spacingM) {
                        introPanel
                        KnockSearchBar(placeholder: "搜索角色", text: $searchText)

                        if let actionError {
                            errorRow(actionError)
                        }

                        contentRows
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingM)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeEffectHidden()
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await reload() }
    }

    // MARK: - 顶栏

    private var header: some View {
        ZStack(alignment: .center) {
            VStack(spacing: 3) {
                // 标题行铺满可用宽度并居中 → 与屏幕中线严格对齐
                KnockText("新建聊天", role: .pageTitle, lineLimit: 1)
                    .frame(maxWidth: .infinity, alignment: .center)
                KnockText("选择一位角色开始对话", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .padding(.horizontal, 92)

            HStack(spacing: 0) {
                backButton
                Spacer(minLength: KnockTokens.spacingS)
            }
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(KnockTokens.canvas)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(KnockTokens.stroke)
                .frame(height: KnockTokens.hairline)
                .accessibilityHidden(true)
        }
    }

    /// 返回胶囊（左侧操作槽；不参与标题居中计算）。
    private var backButton: some View {
        Button {
            dismiss()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 13, weight: .heavy))
                KnockText("返回", role: .captionStrong, lineLimit: 1)
            }
            .foregroundStyle(KnockTokens.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 30)
            .background(Capsule(style: .continuous).fill(KnockTokens.panel))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("返回")
    }

    // MARK: - 说明面板

    private var introPanel: some View {
        HStack(spacing: KnockTokens.spacingM) {
            KnockRowGlyph(systemImage: "square.and.pencil")
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText("选择一位角色开始对话", role: .rowTitle, lineLimit: 1)
                KnockText(
                    "已有会话的角色会直接打开；其余会创建 stable 会话 session_<id>",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineLimit: 2
                )
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, KnockTokens.spacingL)
        .padding(.vertical, KnockTokens.spacingM)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .fill(KnockTokens.panel)
        }
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    // MARK: - 内容

    @ViewBuilder
    private var contentRows: some View {
        if let errorText {
            KnockEmptyState(
                title: "角色读取失败",
                message: errorText,
                systemImage: "exclamationmark.triangle",
                actionTitle: "重试",
                action: { Task { await reload() } }
            )
        } else if isLoading && roles.isEmpty {
            HStack(spacing: KnockTokens.spacingM) {
                ProgressView().controlSize(.small).tint(KnockTokens.electric)
                KnockText("读取可选角色…", role: .caption, color: KnockTokens.textSecondary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, KnockTokens.spacingL)
            .frame(minHeight: 56)
            .background(Capsule(style: .continuous).fill(KnockTokens.panel))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
        } else if roles.isEmpty {
            KnockEmptyState(
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
            KnockEmptyState(
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

    private func roleRow(_ role: RoleItem) -> some View {
        KnockListRow(
            title: role.name,
            subtitle: role.title.isEmpty ? role.relationLabel : role.title,
            action: { Task { await pick(role) } }
        ) {
            KnockAvatar(
                avatarId: role.avatarId,
                name: role.name,
                size: KnockTokens.rowIconSize
            )
            .accessibilityHidden(true)
        } trailing: {
            HStack(spacing: KnockTokens.spacingS) {
                if role.hasSession {
                    KnockBadge(text: "已有会话", variant: .neutral)
                }
                if busyRoleId == role.id {
                    ProgressView().controlSize(.small).tint(KnockTokens.electric)
                } else {
                    Image(systemName: "bubble.left.fill")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textTertiary)
                }
            }
        }
        .disabled(busyRoleId != nil)
        .accessibilityLabel("\(role.name)，\(role.hasSession ? "已有会话" : "新建会话")")
        .accessibilityHint("进入与该角色的聊天")
    }

    private func errorRow(_ text: String) -> some View {
        HStack(spacing: KnockTokens.spacingS) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 14, weight: .heavy))
                .foregroundStyle(KnockTokens.dangerBright)
            KnockText(text, role: .caption, color: KnockTokens.textPrimary, lineLimit: 3)
            Spacer(minLength: 0)
            KnockCloseButton(label: "关闭提示") { actionError = nil }
        }
        .padding(.horizontal, KnockTokens.spacingM)
        .padding(.vertical, KnockTokens.spacingS)
        .background(RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous).fill(KnockTokens.panel))
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    // MARK: - 逻辑（与改版前一致）

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