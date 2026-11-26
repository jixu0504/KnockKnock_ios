//
//  NewGroupView.swift
//  T43 新建群聊（P1 视觉重写，业务逻辑零改动）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md：
//  - 黑底 + 斜切迷彩；顶栏自绘（取消胶囊 + 居中 heavy 标题 + 黄色主 CTA「创建」）；
//  - 群名用描边胶囊输入框（`KnockTextFieldStyle` 同款）；成员搜索用 `KnockSearchBar`；
//  - 成员行为**胶囊行**（KnockListRow：左 44pt 头像 + 名称/身份 + 选中标记）；
//  - 隐藏系统导航栏；无毛玻璃 / 无半透明白。
//
//  能力保留：群名 + 成员多选（真实 operators 表）→ GroupChatBridge.createGroup 写 chat_sessions
//  （id=operatorId=`group_<hex>`，members 逗号分隔，与 Android NewGroup → SessionViewModel.saveGroup
//  同表同列）；创建成功后关闭 Sheet 并进入群聊页；`GroupSelfTestLog` 打点原样保留。
//

import SwiftUI

struct NewGroupView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    /// 创建成功回调（参数为新群 sessionId）
    var onCreated: (String) -> Void

    @State private var name: String = ""
    @State private var roles: [RoleItem] = []
    @State private var selectedIds: Set<String> = []
    @State private var isLoading = false
    @State private var isCreating = false
    @State private var errorText: String?
    @State private var searchText = ""

    private var filteredRoles: [RoleItem] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return roles }
        return roles.filter {
            $0.name.localizedCaseInsensitiveContains(keyword) || $0.title.localizedCaseInsensitiveContains(keyword)
        }
    }

    private var canCreate: Bool {
        !isCreating && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedIds.count >= 2
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: KnockTokens.spacingL) {
                    groupNameSection
                    memberSection
                    if let errorText {
                        errorRow(errorText)
                    }
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.top, KnockTokens.spacingM)
                .padding(.bottom, KnockTokens.spacingXL)
            }
            .scrollIndicators(.hidden)
            .knockScrollEdgeEffectHidden()
        }
        .background(KnockCamoBackground())
        .toolbar(.hidden, for: .navigationBar)
        .task { await loadRoles() }
    }

    // MARK: - 顶栏（取消 + 标题 + 创建）

    private var header: some View {
        ZStack {
            KnockText("新建群聊", role: .pageTitle, lineLimit: 1)
                .frame(maxWidth: .infinity, alignment: .center)

            HStack(spacing: KnockTokens.spacingM) {
                Button {
                    dismiss()
                } label: {
                    KnockText("取消", role: .captionStrong, lineLimit: 1)
                        .padding(.horizontal, 14)
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
                .accessibilityLabel("取消新建群聊")

                Spacer(minLength: KnockTokens.spacingS)

                if isCreating {
                    ProgressView().controlSize(.small).tint(KnockTokens.electric)
                } else {
                    Button {
                        create()
                    } label: {
                        KnockText(
                            "创建",
                            role: .captionStrong,
                            color: canCreate ? KnockTokens.onElectric : KnockTokens.textTertiary,
                            lineLimit: 1
                        )
                        .padding(.horizontal, 16)
                        .frame(minHeight: 30)
                        .background(Capsule(style: .continuous).fill(canCreate ? KnockTokens.electric : KnockTokens.row))
                        .overlay {
                            Capsule(style: .continuous)
                                .strokeBorder(
                                    canCreate ? KnockTokens.electricRing : KnockTokens.strokeSecondary,
                                    lineWidth: KnockTokens.strokeWidth
                                )
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!canCreate)
                    .accessibilityLabel("创建群聊")
                }
            }
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(KnockTokens.stroke)
                .frame(height: KnockTokens.hairline)
        }
    }

    // MARK: - 群名

    private var groupNameSection: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockGroupTitle(title: "群名", systemImage: "pencil")
            TextField("群聊名称（如：狡兔屋委托闲聊群）", text: $name)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(KnockTextFieldStyle())
            KnockText(
                "群聊写入真实 chat_sessions（operatorId=group_<hex>），成员来自 operators 名册。",
                role: .caption,
                color: KnockTokens.textTertiary,
                lineSpacing: 2
            )
        }
    }

    // MARK: - 成员

    private var memberSection: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockGroupTitle(title: "成员（已选 \(selectedIds.count) 人，至少 2 人）")
            KnockSearchBar(placeholder: "搜索成员", text: $searchText)

            if isLoading && roles.isEmpty {
                HStack(spacing: KnockTokens.spacingM) {
                    ProgressView().controlSize(.small).tint(KnockTokens.electric)
                    KnockText("读取角色…", role: .caption, color: KnockTokens.textSecondary)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, KnockTokens.spacingL)
                .frame(minHeight: 56)
                .background(Capsule(style: .continuous).fill(KnockTokens.panel))
                .overlay {
                    Capsule(style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }
            } else if filteredRoles.isEmpty {
                KnockEmptyState(
                    title: "没有匹配的成员",
                    message: "没有名字 / 身份匹配「\(searchText)」的角色。",
                    systemImage: "magnifyingglass",
                    pillText: "EMPTY"
                )
            } else {
                ForEach(filteredRoles) { role in
                    memberRow(role)
                }
            }
        }
    }

    private func memberRow(_ role: RoleItem) -> some View {
        let isSelected = selectedIds.contains(role.id)
        return KnockListRow(
            title: role.name,
            subtitle: role.title.isEmpty ? role.relationLabel : role.title,
            action: { toggle(role.id) }
        ) {
            KnockAvatar(
                avatarId: role.avatarId,
                name: role.name,
                size: KnockTokens.rowIconSize
            )
            .accessibilityHidden(true)
        } trailing: {
            if isSelected {
                // 选中 = 黄胶囊 + 浅蓝外圈（计划书 1.2 关键细节）
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundStyle(KnockTokens.onElectric)
                    .frame(width: 26, height: 26)
                    .background(Circle().fill(KnockTokens.electric))
                    .overlay {
                        Circle().strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                    }
            } else {
                Circle()
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                    .frame(width: 26, height: 26)
            }
        }
        .accessibilityLabel("\(role.name)，\(isSelected ? "已选择" : "未选择")")
    }

    // MARK: - 错误条

    private func errorRow(_ text: String) -> some View {
        HStack(spacing: KnockTokens.spacingS) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 14, weight: .heavy))
                .foregroundStyle(KnockTokens.dangerBright)
            KnockText(text, role: .caption, color: KnockTokens.textPrimary, lineLimit: 3)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, KnockTokens.spacingL)
        .padding(.vertical, KnockTokens.spacingM)
        .background(RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous).fill(KnockTokens.panel))
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    // MARK: - 逻辑（与改版前一致）

    private func toggle(_ id: String) {
        if selectedIds.contains(id) { selectedIds.remove(id) } else { selectedIds.insert(id) }
    }

    private func loadRoles() async {
        guard !isLoading, roles.isEmpty else { return }
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadRoles() {
        case .success(let items):
            roles = items
            if let first = items.first { selectedIds.insert(first.id) }
            if let second = items.dropFirst().first { selectedIds.insert(second.id) }
            GroupSelfTestLog.add("newGroup roles loaded count=\(items.count)")
        case .failure(let error):
            errorText = "角色读取失败：\(error.message)"
        }
    }

    private func create() {
        guard canCreate else { return }
        isCreating = true
        errorText = nil
        let groupName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let memberIds = Array(selectedIds)
        Task {
            let result = await environment.bridge.createGroup(name: groupName, memberIds: memberIds)
            isCreating = false
            switch result {
            case .success(let sessionId):
                GroupSelfTestLog.add("newGroup created id=\(sessionId) name=\(groupName) members=\(memberIds.count)")
                onCreated(sessionId)
                dismiss()
            case .failure(let error):
                errorText = "创建失败：\(error.message)"
                GroupSelfTestLog.add("newGroup FAILED error=\(error.message)")
            }
        }
    }
}