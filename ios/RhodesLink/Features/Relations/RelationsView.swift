//
//  RelationsView.swift
//  关系网编辑页（T57，入口：角色详情「关系网 · 管理」）：
//  - 列表：对方头像 / 名称 / 关系类型标签 / 反向标签 / 方向（我对 TA / TA 对我）/ 亲密度 / 备注；
//  - 添加关系 Sheet：选角色 + 24 种关系类型 Picker + 亲密度 + 备注 + 「同时创建双向互补关系」开关
//    （oracle `RelationshipScreen.kt:333-398`：互补映射逐条复刻，CRUSH 无互补类型）；
//  - 滑动删除（二次确认）：按 source→target 精确删单向关系（oracle `deleteRelationship` 同语义）；
//  - 数据全部来自 shared `RelationBridge`（relationships 表），无 mock。
//  - 截图/演示钩子（仅启动参数）：`--relations-demo-add` 自动打开添加 Sheet。
//

import SwiftUI

@MainActor
struct RelationsView: View {
    @EnvironmentObject private var environment: AppEnvironment

    let operatorId: String
    let operatorName: String

    @State private var entries: [RelationEntryItem] = []
    @State private var candidates: [RelationOperatorItem] = []
    @State private var typeOptions: [RelationTypeOptionItem] = []
    @State private var isLoading = true
    @State private var errorText: String?
    @State private var showAddSheet = false
    @State private var pendingDelete: RelationEntryItem?
    @State private var showDeleteConfirm = false
    @State private var removeError: String?
    @State private var toast: String?

    private var outgoing: [RelationEntryItem] { entries.filter(\.isOutgoing) }
    private var incoming: [RelationEntryItem] { entries.filter { !$0.isOutgoing } }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            Group {
                if isLoading && entries.isEmpty {
                    ProgressView("读取关系网…")
                        .padding(40)
                } else if let errorText {
                    EmptyStateView(
                        title: "关系网读取失败",
                        message: errorText,
                        systemImage: "exclamationmark.triangle",
                        actionTitle: "重试",
                        action: { Task { await reload() } }
                    )
                } else if entries.isEmpty {
                    emptyState
                } else {
                    relationList
                }
            }
        }
        .navigationTitle("\(operatorName)的关系网")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showAddSheet = true
                } label: {
                    Label("添加关系", systemImage: "plus")
                }
                .accessibilityLabel("添加关系")
            }
        }
        .sheet(isPresented: $showAddSheet) {
            RelationAddSheet(
                operatorId: operatorId,
                operatorName: operatorName,
                candidates: candidates.filter { candidate in
                    candidate.id != operatorId &&
                        !entries.contains { $0.relatedOperatorId == candidate.id }
                },
                typeOptions: typeOptions,
                onSaved: { mutation in
                    toast = mutation.summary
                    Task { await reload() }
                }
            )
        }
        .confirmationDialog(
            "删除这条关系？",
            isPresented: $showDeleteConfirm,
            presenting: pendingDelete
        ) { entry in
            Button("删除（\(entry.typeLabel)）", role: .destructive) {
                Task { await delete(entry) }
            }
            Button("取消", role: .cancel) {}
        } message: { entry in
            Text("将删除「\(entry.sourceOperatorId == operatorId ? operatorName : entry.relatedOperatorName) → \(entry.targetOperatorId == operatorId ? operatorName : entry.relatedOperatorName)」的【\(entry.typeLabel)】关系；反向关系不会自动删除（与原版单向语义一致）。")
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.footnote)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
                    .padding(.bottom, 18)
                    .transition(.opacity)
                    .onAppear {
                        Task {
                            try? await Task.sleep(nanoseconds: 2_600_000_000)
                            withAnimation { self.toast = nil }
                        }
                    }
            }
        }
        .alert("删除失败", isPresented: Binding(
            get: { removeError != nil },
            set: { if !$0 { removeError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(removeError ?? "")
        }
        .task { await reload() }
        .task {
            // 截图钩子：`--relations-demo-add` 自动打开添加 Sheet（仅模拟器自动化使用；
            // 轮询等待候选角色加载完成，避免数据加载慢时错过打开时机）
            guard ProcessInfo.processInfo.arguments.contains("--relations-demo-add") else { return }
            for _ in 0..<24 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if !candidates.isEmpty { showAddSheet = true; return }
            }
        }
    }

    // MARK: - 列表

    private var relationList: some View {
        List {
            summaryCard
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 6, leading: 14, bottom: 6, trailing: 14))

            if !outgoing.isEmpty {
                sectionHeader("我对对方", count: outgoing.count)
                ForEach(outgoing) { entry in
                    row(entry)
                }
            }
            if !incoming.isEmpty {
                sectionHeader("对方对我", count: incoming.count)
                ForEach(incoming) { entry in
                    row(entry)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await reload() }
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Label("关系网", systemImage: "person.3.sequence.fill")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("共 \(entries.count) 条")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Text("关系是单向的：A 把 B 当朋友，不代表 B 也把 A 当朋友。可在添加时勾选创建双向互补关系。亲密度越高，越容易传递公开印象。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private func sectionHeader(_ title: String, count: Int) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text("\(count)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.tertiary)
            Spacer()
        }
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 2, leading: 18, bottom: 2, trailing: 14))
    }

    private func row(_ entry: RelationEntryItem) -> some View {
        let type = typeOptions.first { $0.code == entry.type }
        return HStack(spacing: 10) {
            RoleAvatar(avatarId: entry.relatedAvatarId, name: entry.relatedOperatorName, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(entry.relatedOperatorName)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(entry.directionLabel)
                        .font(.system(size: 9).weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(GlassTokens.bubbleSystem, in: Capsule())
                }
                Text(entry.relationDescription)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if !entry.note.isEmpty {
                    Text(entry.note)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
                HStack(spacing: 8) {
                    Text(entry.reverseLabelText)
                        .font(.system(size: 9))
                        .foregroundStyle(.tertiary)
                    ProgressView(value: min(max(Double(entry.intimacy), 0), 100), total: 100)
                        .tint((type?.tint ?? GlassTokens.brandOrange))
                        .frame(width: 56)
                    Text("\(entry.intimacy)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            Text(entry.typeLabel)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background((type?.tint ?? GlassTokens.brandOrange), in: Capsule())
        }
        .padding(12)
        .background(Color.clear)
        .contentShape(Rectangle())
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 3, leading: 14, bottom: 3, trailing: 14))
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                pendingDelete = entry
                showDeleteConfirm = true
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
        .accessibilityLabel("\(entry.relatedOperatorName)，\(entry.typeLabel)，亲密度 \(entry.intimacy)")
    }

    private var emptyState: some View {
        EmptyStateView(
            title: "暂无关系",
            message: "\(operatorName)的关系网还是空的。添加关系后，关系会影响群聊称呼、日记素材与公开印象的传递。",
            systemImage: "person.3.sequence",
            actionTitle: "添加关系",
            action: { showAddSheet = true }
        )
    }

    // MARK: - 数据

    private func reload() async {
        isLoading = true
        defer { isLoading = false }
        typeOptions = environment.bridge.relationTypeOptions()
        switch await environment.bridge.loadRelationEntries(operatorId: operatorId) {
        case .success(let items):
            entries = items
            errorText = nil
            SelfTestLog.add("relations load operator=\(operatorId) count=\(items.count) out=\(items.filter(\.isOutgoing).count) in=\(items.filter { !$0.isOutgoing }.count)")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("relations load failed operator=\(operatorId) error=\(error.message)")
        }
        if case .success(let list) = await environment.bridge.loadRelationCandidates() {
            candidates = list
        }
    }

    private func delete(_ entry: RelationEntryItem) async {
        switch await environment.bridge.removeRelation(
            sourceOperatorId: entry.sourceOperatorId,
            targetOperatorId: entry.targetOperatorId
        ) {
        case .success:
            entries.removeAll { $0.id == entry.id }
            toast = "已删除与 \(entry.relatedOperatorName) 的【\(entry.typeLabel)】关系"
            SelfTestLog.add("relations remove \(entry.sourceOperatorId)->\(entry.targetOperatorId) type=\(entry.type)")
        case .failure(let error):
            removeError = error.message
            SelfTestLog.add("relations remove failed \(entry.sourceOperatorId)->\(entry.targetOperatorId) error=\(error.message)")
        }
    }
}

// MARK: - 添加关系 Sheet

@MainActor
private struct RelationAddSheet: View {
    @Environment(\.dismiss) private var dismiss

    let operatorId: String
    let operatorName: String
    let candidates: [RelationOperatorItem]
    let typeOptions: [RelationTypeOptionItem]
    let onSaved: (RelationMutationItem) -> Void

    @State private var targetId = ""
    @State private var typeCode = "FRIEND"
    @State private var intimacy: Double = 50
    @State private var note = ""
    @State private var createReciprocal = false
    @State private var isSaving = false
    @State private var errorText: String?

    private var selectedTarget: RelationOperatorItem? { candidates.first { $0.id == targetId } }
    private var selectedType: RelationTypeOptionItem? { typeOptions.first { $0.code == typeCode } }
    private var reciprocalType: RelationTypeOptionItem? {
        guard let type = selectedType, type.hasReverse else { return nil }
        return typeOptions.first { $0.code == type.reverseCode }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("关系对象") {
                    if candidates.isEmpty {
                        Text("所有角色都已建立关系，或暂无其他角色。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("选择角色", selection: $targetId) {
                            Text("请选择").tag("")
                            ForEach(candidates) { candidate in
                                Text(candidate.title.isEmpty ? candidate.name : "\(candidate.name) · \(candidate.title)")
                                    .tag(candidate.id)
                            }
                        }
                    }
                }

                Section {
                    Picker("关系类型", selection: $typeCode) {
                        ForEach(typeOptions) { option in
                            Text(option.label).tag(option.code)
                        }
                    }
                    if let type = selectedType {
                        LabeledContent("关系描述") {
                            Text("\(operatorName)是\(selectedTarget?.name ?? "对方")的【\(type.label)】")
                                .font(.caption)
                                .multilineTextAlignment(.trailing)
                        }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("亲密度")
                            Spacer()
                            Text("\(Int(intimacy))")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        Slider(value: $intimacy, in: 0...100, step: 1)
                    }
                    TextField("备注（选填）", text: $note, axis: .vertical)
                        .lineLimit(1...3)
                } header: {
                    Text("关系设定")
                } footer: {
                    Text("亲密度会决定对方在私聊中能从 \(operatorName) 处自然知道多少用户近况。")
                }

                Section {
                    if let reciprocal = reciprocalType, let target = selectedTarget {
                        Toggle(isOn: $createReciprocal) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("同时创建双向互补关系")
                                Text("\(target.name) → \(operatorName)：【\(reciprocal.label)】")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } else {
                        Text(selectedType?.hasReverse == false
                             ? "【\(selectedType?.label ?? "")】没有互补类型，只能建立单向关系。"
                             : "选择关系对象与类型后可创建双向互补关系。")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("默认只建立单向关系；与 Android 原版一致，双向需要勾选此开关。")
                }

                if let errorText {
                    Section {
                        Label(errorText, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("添加关系")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "保存中…" : "保存") {
                        Task { await save() }
                    }
                    .disabled(isSaving || targetId.isEmpty)
                }
            }
            .task {
                if targetId.isEmpty { targetId = candidates.first?.id ?? "" }
                if typeOptions.contains(where: { $0.code == typeCode }) == false {
                    typeCode = typeOptions.first?.code ?? "FRIEND"
                }
                SelfTestLog.add("relations add sheet opened candidates=\(candidates.count) types=\(typeOptions.count)")
            }
        }
    }

    private func save() async {
        guard !targetId.isEmpty else { return }
        isSaving = true
        defer { isSaving = false }
        errorText = nil
        switch await AppGraphClient.shared.addRelation(
            operatorId: operatorId,
            targetOperatorId: targetId,
            relationType: typeCode,
            intimacy: Int(intimacy),
            note: note,
            createReciprocal: createReciprocal && reciprocalType != nil
        ) {
        case .success(let mutation):
            SelfTestLog.add("relations add saved \(mutation.operatorName)->\(mutation.targetOperatorName) type=\(mutation.type) reciprocal=\(mutation.reciprocalCreated) intimacy=\(mutation.intimacy)")
            onSaved(mutation)
            dismiss()
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("relations add failed error=\(error.message)")
        }
    }
}
