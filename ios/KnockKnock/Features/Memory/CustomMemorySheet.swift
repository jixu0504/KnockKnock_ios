//
//  CustomMemorySheet.swift
//  手动添加记忆（自定义记忆）。
//
//  用途：自动管线只从对话里提取，用户认为「这条该被记住」但对话没提取到、
//  或希望直接写成长期认知时，可以在这里手写一条。
//

import SwiftUI

struct CustomMemorySheet: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    var onSaved: () -> Void

    @State private var roles: [RoleItem] = []
    @State private var selectedRoleId = ""
    @State private var content = ""
    @State private var level = "L2"
    @State private var importance: Double = 60
    @State private var memoryType = "preference_expression"
    @State private var isPrivate = false
    @State private var isSaving = false
    @State private var errorText: String?

    private static let levelOptions: [(value: String, title: String)] = [
        ("L1", "短期"),
        ("L2", "日常"),
        ("L3", "长期"),
    ]

    private static let typeOptions: [(value: String, title: String)] = [
        ("preference_expression", "偏好"),
        ("emotion_state", "情绪"),
        ("behavior_state", "行为状态"),
        ("physiological_state", "身体状态"),
        ("agreement_commitment", "约定"),
        ("care_reminder", "关心提醒"),
        ("intent_wish", "意图愿望"),
        ("evaluation_opinion", "评价观点"),
        ("self_cognition_statement", "自我认知"),
        ("external_knowledge", "外部知识"),
        ("event", "事件"),
    ]

    private var trimmed: String { content.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var canSave: Bool {
        !trimmed.isEmpty && trimmed.count <= 500 && !selectedRoleId.isEmpty && !isSaving
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)
            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        rolePanel
                        contentPanel
                        levelPanel
                        importancePanel
                        typePanel
                        privacyPanel
                        if let errorText {
                            KnockText(errorText, role: .caption, color: KnockTokens.dangerBright, lineSpacing: 2)
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.vertical, KnockTokens.spacingM)
                }
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .knockHideSystemChrome()
        .task { await loadRoles() }
    }

    /// 自绘顶栏（页面已隐藏系统导航栏，必须自带取消/保存，否则无法提交）。
    private var header: some View {
        HStack(spacing: KnockTokens.spacingS) {
            Button("取消") { dismiss() }
                .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 38))
                .accessibilityLabel("取消添加记忆")
            KnockText("添加记忆", role: .pageTitle, lineLimit: 1)
            Spacer(minLength: 0)
            Button(isSaving ? "保存中…" : "保存") { Task { await save() } }
                .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 38))
                .disabled(!canSave)
                .opacity(canSave ? 1 : 0.45)
                .accessibilityIdentifier("memory-custom-save")
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
    }

    // MARK: 表单分区

    private var rolePanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockText("属于哪个角色", role: .captionStrong, lineLimit: 1)
                if roles.isEmpty {
                    KnockText("还没有角色，先去联系人里创建。", role: .caption, color: KnockTokens.textSecondary)
                } else {
                    Menu {
                        ForEach(roles) { role in
                            Button(role.name) { selectedRoleId = role.id }
                        }
                    } label: {
                        HStack {
                            KnockText(currentRoleName, role: .body, lineLimit: 1)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(KnockTokens.textTertiary)
                        }
                        .padding(.horizontal, KnockTokens.spacingM)
                        .frame(height: 44)
                        .background(KnockTokens.row, in: RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous))
                    }
                }
            }
        }
    }

    private var contentPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack {
                    KnockText("记忆内容", role: .captionStrong, lineLimit: 1)
                    Spacer(minLength: 0)
                    KnockText("\(trimmed.count)/500", role: .caption, color: KnockTokens.textTertiary)
                }
                TextEditor(text: $content)
                    .knockFont(.body)
                    .frame(minHeight: 110)
                    .padding(KnockTokens.spacingS)
                    .scrollContentBackground(.hidden)
                    .background(KnockTokens.row, in: RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous))
                    .overlay(alignment: .topLeading) {
                        if content.isEmpty {
                            KnockText("例如：用户对花生过敏，点菜时要避开。", role: .body, color: KnockTokens.textTertiary)
                                .padding(.horizontal, KnockTokens.spacingM)
                                .padding(.vertical, KnockTokens.spacingM)
                                .allowsHitTesting(false)
                        }
                    }
            }
        }
    }

    private var levelPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockText("记忆层级", role: .captionStrong, lineLimit: 1)
                KnockSegmentedBar(
                    items: Self.levelOptions.map { KnockSegmentItem(value: $0.value, title: $0.title) },
                    selection: $level,
                    height: KnockTokens.segmentedSmallHeight,
                    showsIcons: false
                )
                KnockText(levelHint, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
            }
        }
    }

    private var levelHint: String {
        switch level {
        case "L1": return "短期：只在这段对话前后用得上，过一阵会淡出。"
        case "L3": return "长期：角色的稳定认知，会一直带着，不会过期。"
        default: return "日常：这一阵子记得的事，用得上就会被想起来。"
        }
    }

    private var importancePanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack {
                    KnockText("重要度", role: .captionStrong, lineLimit: 1)
                    Spacer(minLength: 0)
                    KnockText("\(Int(importance))", role: .captionStrong, color: KnockTokens.electric, lineLimit: 1)
                }
                Slider(value: $importance, in: 0...100, step: 5)
                    .tint(KnockTokens.electric)
                KnockText("越重要越容易被想起来；80 以上同时算强信号。", role: .caption, color: KnockTokens.textTertiary, lineSpacing: 2)
            }
        }
    }

    private var typePanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockText("记忆类型", role: .captionStrong, lineLimit: 1)
                Menu {
                    ForEach(Self.typeOptions, id: \.value) { option in
                        Button(option.title) { memoryType = option.value }
                    }
                } label: {
                    HStack {
                        KnockText(Self.typeOptions.first { $0.value == memoryType }?.title ?? memoryType, role: .body, lineLimit: 1)
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(KnockTokens.textTertiary)
                    }
                    .padding(.horizontal, KnockTokens.spacingM)
                    .frame(height: 44)
                    .background(KnockTokens.row, in: RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous))
                }
            }
        }
    }

    private var privacyPanel: some View {
        KnockPanel {
            Toggle(isOn: $isPrivate) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText("私密记忆", role: .captionStrong, lineLimit: 1)
                    KnockText("开启后只在私聊里使用，不出现在公开资料里。", role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                }
            }
            .tint(KnockTokens.electric)
        }
    }

    private var currentRoleName: String {
        roles.first { $0.id == selectedRoleId }?.name ?? "选择角色"
    }

    // MARK: 数据

    private func loadRoles() async {
        guard roles.isEmpty else { return }
        if case .success(let list) = await environment.bridge.loadRoles() {
            roles = list
            if selectedRoleId.isEmpty { selectedRoleId = list.first?.id ?? "" }
        }
    }

    private func save() async {
        guard canSave else { return }
        isSaving = true
        errorText = nil
        defer { isSaving = false }
        let result = await environment.bridge.createMemory(
            ownerType: "operator",
            ownerId: selectedRoleId,
            content: trimmed,
            level: level,
            importance: Int(importance),
            explicitPrivate: isPrivate,
            memoryType: memoryType
        )
        switch result {
        case .success(let mutation) where mutation.ok:
            SelfTestLog.add("custom memory saved id=\(mutation.id) level=\(level) type=\(memoryType) imp=\(Int(importance))")
            onSaved()
            dismiss()
        case .success(let mutation):
            errorText = mutation.message
            SelfTestLog.add("custom memory save failed message=\(mutation.message)")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("custom memory save failed error=\(error.message)")
        }
    }
}