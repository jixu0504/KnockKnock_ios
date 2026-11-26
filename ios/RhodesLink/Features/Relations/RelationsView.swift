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
//  P2「敲敲」改版（docs/敲敲UI改版计划书-v1.1.md）：
//  - 自绘 44pt 顶栏（返回胶囊 + 标题 + 黄色「添加关系」胶囊），隐藏系统导航栏 + iOS 26+ 滚动边缘玻璃；
//  - 汇总卡 / 添加 Sheet 全部走 `KnockPanel`；行 = 胶囊行（蓝圈头像 + 灰色说明 + 中性类型角标）；
//  - 「黄选中」只在添加 Sheet 的关系对象 / 24 类型选择上（选中黄胶囊 + 浅蓝外圈）；
//  - 删除确认改自绘 `knockConfirmDialog`（避开系统玻璃背景）；
//  - 布局逻辑（List 分组 / 滑动删除 / 单向语义 / 数据调用 / 自检钩子）不动。
//

import SwiftUI

@MainActor
struct RelationsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

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

            VStack(spacing: 0) {
                header

                Group {
                    if isLoading && entries.isEmpty {
                        loadingCard
                    } else if let errorText {
                        KnockEmptyState(
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
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
        }
        .knockHideSystemChrome()
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
        .knockConfirmDialog(
            isPresented: $showDeleteConfirm,
            title: "删除这条关系？",
            message: deleteConfirmMessage,
            confirmTitle: "删除",
            cancelTitle: "取消",
            isDestructive: true
        ) {
            if let entry = pendingDelete {
                Task { await delete(entry) }
            }
        }
        .overlay(alignment: .bottom) {
            if let toast {
                toastView(toast)
            }
        }
        .overlay {
            if let removeError {
                deleteErrorOverlay(removeError)
            }
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

    // MARK: - 顶栏

    private var header: some View {
        KnockRoleHeader(
            title: "\(operatorName)的关系网",
            subtitle: "共 \(entries.count) 条 · 单向语义",
            showsBack: true,
            onBack: { dismiss() }
        ) {
            KnockCapsuleIconButton(
                systemImage: "plus",
                variant: .accent,
                accessibilityLabel: "添加关系"
            ) {
                showAddSheet = true
            }
        }
    }

    private var deleteConfirmMessage: String {
        guard let entry = pendingDelete else { return "" }
        let source = entry.sourceOperatorId == operatorId ? operatorName : entry.relatedOperatorName
        let target = entry.targetOperatorId == operatorId ? operatorName : entry.relatedOperatorName
        return "将删除「\(source) → \(target)」的【\(entry.typeLabel)】关系；反向关系不会自动删除（与原版单向语义一致）。"
    }

    private var loadingCard: some View {
        KnockPanel {
            HStack(spacing: KnockTokens.spacingM) {
                ProgressView()
                    .tint(KnockTokens.electric)
                KnockText("读取关系网…", role: .caption, color: KnockTokens.textSecondary)
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.top, KnockTokens.spacingM)
    }

    // MARK: - 列表（布局与原版一致：汇总卡 + 我对对方 / 对方对我 两段）

    private var relationList: some View {
        List {
            summaryCard
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 6, leading: KnockTokens.screenPadding, bottom: 6, trailing: KnockTokens.screenPadding))

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
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "person.3.sequence.fill")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textSecondary)
                        .accessibilityHidden(true)
                    KnockText("关系网", role: .sectionTitle, lineLimit: 1)
                    KnockBadge(text: "共 \(entries.count) 条", variant: .neutral)
                    Spacer(minLength: KnockTokens.spacingS)
                }
                .accessibilityAddTraits(.isHeader)

                HStack(spacing: KnockTokens.spacingS) {
                    KnockBadge(text: "我对对方 \(outgoing.count)", variant: .neutral)
                    KnockBadge(text: "对方对我 \(incoming.count)", variant: .neutral)
                }

                KnockText(
                    "关系是单向的：A 把 B 当朋友，不代表 B 也把 A 当朋友。可在添加时勾选创建双向互补关系。亲密度越高，越容易传递公开印象。",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func sectionHeader(_ title: String, count: Int) -> some View {
        HStack(spacing: KnockTokens.spacingS) {
            KnockText(title, role: .sectionTitle, lineLimit: 1)
            KnockBadge(text: "\(count)", variant: .neutral)
            Spacer(minLength: 0)
        }
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 8, leading: KnockTokens.screenPadding + KnockTokens.spacingXS, bottom: 2, trailing: KnockTokens.screenPadding))
        .accessibilityAddTraits(.isHeader)
    }

    private func row(_ entry: RelationEntryItem) -> some View {
        HStack(spacing: KnockTokens.spacingM) {
            RoleAvatar(avatarId: entry.relatedAvatarId, name: entry.relatedOperatorName, size: KnockTokens.rowIconSize)

            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                HStack(spacing: KnockTokens.spacingXS) {
                    KnockText(entry.relatedOperatorName, role: .rowTitle, lineLimit: 1)
                    KnockBadge(text: entry.directionLabel, variant: .neutral)
                    Spacer(minLength: KnockTokens.spacingXXS)
                    KnockBadge(text: entry.typeLabel, variant: .neutral)
                }

                KnockText(
                    entry.note.isEmpty ? entry.relationDescription : "\(entry.relationDescription) · \(entry.note)",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineLimit: 1
                )

                HStack(spacing: KnockTokens.spacingS) {
                    KnockText(entry.reverseLabelText, role: .badge, color: KnockTokens.textTertiary, lineLimit: 1)
                    KnockProgressBar(progress: Double(entry.intimacy) / 100, height: 5)
                        .frame(width: 56)
                    KnockText("\(entry.intimacy)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    Spacer(minLength: 0)
                    KnockChevron()
                }
            }
        }
        .padding(.horizontal, KnockTokens.rowPaddingLeading)
        .padding(.trailing, KnockTokens.rowPaddingTrailing - KnockTokens.rowPaddingLeading)
        .padding(.vertical, KnockTokens.spacingS)
        .frame(minHeight: KnockTokens.rowMinHeight)
        .background {
            Capsule(style: .continuous)
                .fill(KnockTokens.row)
                .overlay(KnockCheckerOverlay())
                .clipShape(Capsule(style: .continuous))
        }
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
        }
        .contentShape(Capsule(style: .continuous))
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 3, leading: KnockTokens.screenPadding, bottom: 3, trailing: KnockTokens.screenPadding))
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                pendingDelete = entry
                showDeleteConfirm = true
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(entry.relatedOperatorName)，\(entry.typeLabel)，亲密度 \(entry.intimacy)")
    }

    private var emptyState: some View {
        KnockEmptyState(
            title: "暂无关系",
            message: "\(operatorName)的关系网还是空的。添加关系后，关系会影响群聊称呼、日记素材与公开印象的传递。",
            systemImage: "person.3.sequence",
            actionTitle: "添加关系",
            action: { showAddSheet = true }
        )
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.top, KnockTokens.spacingM)
    }

    // MARK: - Toast / 错误

    private func toastView(_ text: String) -> some View {
        KnockText(text, role: .caption, color: KnockTokens.textPrimary, lineLimit: 2)
            .padding(.horizontal, KnockTokens.spacingL)
            .padding(.vertical, KnockTokens.spacingS)
            .background(Capsule(style: .continuous).fill(KnockTokens.panel))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
            .padding(.horizontal, KnockTokens.screenPadding)
            .padding(.bottom, 18)
            .transition(.opacity)
            .onAppear {
                Task {
                    try? await Task.sleep(nanoseconds: 2_600_000_000)
                    withAnimation { self.toast = nil }
                }
            }
    }

    private func deleteErrorOverlay(_ text: String) -> some View {
        ZStack {
            Color.black.opacity(0.62)
                .ignoresSafeArea()
                .onTapGesture { removeError = nil }
                .accessibilityHidden(true)

            KnockDialogPanel(title: "删除失败") {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                    KnockText(text, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("好") { removeError = nil }
                        .buttonStyle(KnockPrimaryButtonStyle())
                }
            }
            .padding(.horizontal, KnockTokens.spacingXXL)
        }
        .transition(.opacity)
        .accessibilityAddTraits(.isModal)
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

// MARK: - 添加关系 Sheet（敲敲表单：黄选中 + 蓝圈头像 + 灰色说明）

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
            ZStack {
                AmbientBackground(parallax: 0, showsStandee: false)

                VStack(spacing: 0) {
                    header

                    ScrollView {
                        VStack(spacing: KnockTokens.spacingM) {
                            targetCard
                            typeCard
                            intimacyCard
                            reciprocalCard
                            if let errorText {
                                errorCard(errorText)
                            }
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.top, KnockTokens.spacingS)
                        .padding(.bottom, KnockTokens.spacingXXL)
                    }
                    .scrollIndicators(.hidden)
                }
            }
            .knockHideSystemChrome()
            .task {
                if targetId.isEmpty { targetId = candidates.first?.id ?? "" }
                if typeOptions.contains(where: { $0.code == typeCode }) == false {
                    typeCode = typeOptions.first?.code ?? "FRIEND"
                }
                SelfTestLog.add("relations add sheet opened candidates=\(candidates.count) types=\(typeOptions.count)")
            }
        }
    }

    private var header: some View {
        HStack(spacing: KnockTokens.spacingM) {
            Button("取消") { dismiss() }
                .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 38))
                .accessibilityLabel("取消添加关系")

            KnockText("添加关系", role: .pageTitle, lineLimit: 1)

            Spacer(minLength: KnockTokens.spacingS)

            Button(isSaving ? "保存中…" : "保存") {
                Task { await save() }
            }
            .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 38))
            .disabled(isSaving || targetId.isEmpty)
            .opacity(targetId.isEmpty ? 0.45 : 1)
            .accessibilityLabel("保存关系")
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
    }

    // MARK: 关系对象（黄选中胶囊）

    private var targetCard: some View {
        KnockFormCard(title: "关系对象", systemImage: "person.crop.circle") {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                if candidates.isEmpty {
                    KnockText("所有角色都已建立关系，或暂无其他角色。", role: .caption, color: KnockTokens.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    // 候选角色较多时内部滚动（上限 240pt），保证下方 24 类型 / 亲密度 / 双向开关可达
                    ScrollView {
                        LazyVGrid(
                            columns: Array(repeating: GridItem(.flexible(), spacing: KnockTokens.spacingS), count: 4),
                            spacing: KnockTokens.spacingS
                        ) {
                            ForEach(candidates) { candidate in
                                candidateCell(candidate)
                            }
                        }
                        .padding(.vertical, KnockTokens.spacingXXS)
                    }
                    .frame(maxHeight: 240)
                    .scrollIndicators(.hidden)
                    .accessibilityLabel("候选角色，共 \(candidates.count) 位")
                }
            }
        }
    }

    private func candidateCell(_ candidate: RelationOperatorItem) -> some View {
        let isSelected = candidate.id == targetId
        return Button {
            targetId = candidate.id
        } label: {
            VStack(spacing: KnockTokens.spacingXS) {
                RoleAvatar(avatarId: candidate.avatarId, name: candidate.name, size: 44)
                KnockText(
                    candidate.name,
                    role: .badge,
                    color: isSelected ? KnockTokens.onElectric : KnockTokens.textSecondary,
                    lineLimit: 1
                )
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, KnockTokens.spacingS)
            .background {
                RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                    .fill(isSelected ? KnockTokens.electric : KnockTokens.row)
            }
            .overlay {
                RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                    .strokeBorder(
                        isSelected ? KnockTokens.electricRing : KnockTokens.strokeSecondary,
                        lineWidth: KnockTokens.strokeWidth
                    )
            }
            .contentShape(RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(candidate.title.isEmpty ? candidate.name : "\(candidate.name)，\(candidate.title)")
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    // MARK: 关系类型（24 种 Chip，黄选中）

    private var typeCard: some View {
        KnockFormCard(title: "关系类型", systemImage: "link") {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 86), spacing: KnockTokens.spacingXS)],
                    alignment: .leading,
                    spacing: KnockTokens.spacingXS
                ) {
                    ForEach(typeOptions) { option in
                        KnockChip(
                            title: option.label,
                            isSelected: option.code == typeCode
                        ) {
                            typeCode = option.code
                        }
                    }
                }

                if let type = selectedType {
                    KnockText(
                        "\(operatorName)是\(selectedTarget?.name ?? "对方")的【\(type.label)】",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: 亲密度 + 备注

    private var intimacyCard: some View {
        KnockFormCard(title: "关系设定", systemImage: "slider.horizontal.3") {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockCapsuleIconButton(
                        systemImage: "minus",
                        variant: .neutral,
                        accessibilityLabel: "降低亲密度"
                    ) {
                        adjustIntimacy(-1)
                    }

                    VStack(spacing: KnockTokens.spacingXXS) {
                        KnockText("\(Int(intimacy))", role: .brand, lineLimit: 1)
                        KnockText("亲密度", role: .badge, color: KnockTokens.textTertiary, lineLimit: 1)
                    }
                    .frame(maxWidth: .infinity)

                    KnockCapsuleIconButton(
                        systemImage: "plus",
                        variant: .neutral,
                        accessibilityLabel: "提高亲密度"
                    ) {
                        adjustIntimacy(1)
                    }
                }

                KnockProgressBar(progress: intimacy / 100, height: 6)

                KnockTextArea("备注（选填）", text: $note, minHeight: 72, accessibilityLabel: "关系备注")

                KnockText(
                    "亲密度会决定对方在私聊中能从 \(operatorName) 处自然知道多少用户近况。",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func adjustIntimacy(_ delta: Double) {
        intimacy = min(max(intimacy + delta, 0), 100)
    }

    // MARK: 双向互补开关（自绘黄色开关）

    private var reciprocalCard: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                if let reciprocal = reciprocalType, let target = selectedTarget {
                    Toggle(isOn: $createReciprocal) {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText("同时创建双向互补关系", role: .rowTitle, lineLimit: 1)
                            KnockText(
                                "\(target.name) → \(operatorName)：【\(reciprocal.label)】",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineLimit: 2
                            )
                        }
                    }
                    .toggleStyle(KnockSwitchToggleStyle())
                    .accessibilityLabel("同时创建双向互补关系")
                } else {
                    KnockText(
                        selectedType?.hasReverse == false
                            ? "【\(selectedType?.label ?? "")】没有互补类型，只能建立单向关系。"
                            : "选择关系对象与类型后可创建双向互补关系。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                    .fixedSize(horizontal: false, vertical: true)
                }

                KnockText(
                    "默认只建立单向关系；与 Android 原版一致，双向需要勾选此开关。",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 2
                )
                .fixedSize(horizontal: false, vertical: true)
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