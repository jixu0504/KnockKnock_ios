//
//  NewGroupView.swift
//  T43 新建群聊（联系人页「群聊」分区的「+」入口）：
//  群名 + 成员多选（真实 operators 表）→ GroupChatBridge.createGroup 写 chat_sessions
//  （id=operatorId=`group_<hex>`，members 逗号分隔，与 Android NewGroup → SessionViewModel.saveGroup 同表同列）。
//  创建成功后关闭 Sheet 并进入群聊页。
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
        NavigationStack {
            List {
                Section {
                    TextField("群聊名称（如：精英干员茶水间）", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("群名")
                } footer: {
                    Text("群聊写入真实 chat_sessions（operatorId=group_<hex>），成员来自 operators 名册。")
                        .font(.caption2)
                }

                Section {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("搜索成员", text: $searchText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    ForEach(filteredRoles) { role in
                        Button {
                            toggle(role.id)
                        } label: {
                            HStack(spacing: 10) {
                                RoleAvatar(avatarId: role.avatarId, name: role.name, size: 32)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(role.name).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
                                    if !role.title.isEmpty {
                                        Text(role.title).font(.caption2).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Image(systemName: selectedIds.contains(role.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selectedIds.contains(role.id) ? GlassTokens.brandOrange : Color.secondary.opacity(0.4))
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(role.name)，\(selectedIds.contains(role.id) ? "已选择" : "未选择")")
                    }
                } header: {
                    Text("成员（已选 \(selectedIds.count) 人，至少 2 人）")
                }

                if let errorText {
                    Section {
                        Text(errorText).font(.footnote).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("新建群聊")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if isCreating {
                        ProgressView().controlSize(.small)
                    } else {
                        Button("创建") { create() }
                            .disabled(!canCreate)
                    }
                }
            }
            .task { await loadRoles() }
        }
    }

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
