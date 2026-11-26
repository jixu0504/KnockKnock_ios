//
//  PromptEditorView.swift
//  T46「提示词编辑器」页（T18 契约：PromptTemplates / PromptModuleDefaults / PromptEditor /
//  PromptPlaceholderRegistry），P4 敲敲视觉改造：
//  - 分类目录：私聊 / 群聊 / 内容生成（含 4 模式 × 3 模块），胶囊行 + 徽标（默认/自定义/已禁用）；
//  - 详情：状态面板 + 描边编辑区（**私聊/群聊模板 = 等宽排版；内容生成 = 衬线排版**）+
//    保存 / 恢复默认（KnockDialog 自绘确认）/ 清空禁用（KnockDialog）；
//  - 占位符说明：推荐 / 可用 / 旧版兼容三组 chip。
//  - 存储键（T18 契约）：主模板 prompt_<type>_<mode>；模块 prompt_<module>_<type>_<mode>；
//    保存写值 + `_custom=true` + `_version=39`，恢复默认删三键；空串 = 有意禁用（不回落默认）。
//
//  全部经 Shared.SettingsBridge（真实 SettingsRepository / NSUserDefaults），业务逻辑零改动。
//  验证钩子：`--settings-selftest`；截图钩子：`--settings-prompt-demo` / `--settings-prompt-detail-scroll`。
//

import SwiftUI

struct PromptEditorView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var entries: [PromptEntryItem] = []
    @State private var scrollOffset: CGFloat = 0
    @State private var loaded = false
    /// 截图证据钩子：`--settings-prompt-demo` 自动进入「私聊 · 线下模式」详情。
    @State private var showDemoDetail = false

    private let groupOrder = ["私聊", "群聊", "内容生成"]

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "提示词编辑器", showsBack: true, onBack: { dismiss() })

                ScrollView {
                    VStack(spacing: KnockTokens.spacingXL) {
                        introGroup
                        ForEach(groupOrder, id: \.self) { group in
                            let items = entries.filter { $0.groupName == group }
                            if !items.isEmpty {
                                groupSection(group: group, items: items)
                            }
                        }
                        helpGroup
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingM)
                    .padding(.bottom, KnockTokens.spacingXXL)
                }
                .scrollIndicators(.hidden)
                .settingsKnockScrollEdgeHidden()
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentOffset.y
                } action: { _, newValue in
                    scrollOffset = newValue
                }
            }
        }
        .navigationTitle("提示词编辑器")
        .settingsKnockNavigationChrome()
        .onAppear { reload() }
        .navigationDestination(isPresented: $showDemoDetail) {
            PromptTemplateDetailView(
                type: "private",
                mode: "offline",
                module: "",
                displayName: "私聊 · 线下模式"
            )
        }
        .task {
            guard ProcessInfo.processInfo.arguments.contains("--settings-prompt-demo") else { return }
            try? await Task.sleep(nanoseconds: 900_000_000)
            showDemoDetail = true
        }
    }

    private func reload() {
        entries = environment.bridge.promptCatalog()
        loaded = true
        SelfTestLog.add("prompt-editor catalog count=\(entries.count) custom=\(entries.filter(\.isCustom).count) disabled=\(entries.filter(\.isDisabled).count)")
    }

    // MARK: - 说明

    private var introGroup: some View {
        SettingsKnockGroup("角色说话规则", systemImage: "text.book.closed") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(systemImage: "text.book.closed", tint: KnockTokens.electric, size: 36)
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText("模板 v\(promptVersionText)", role: .rowTitle, lineLimit: 1)
                            KnockText("修改后只影响对应场景，保存即生效。", role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockBadge(text: loaded ? "\(entries.count) 项" : "读取中", variant: .neutral)
                    }

                    KnockText(
                        "模板不是发送给 AI 的全部内容：应用还会自动加入固定规则、运行时资料、历史消息和输出协议。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    /// T18 出厂模板版本号常量（`PromptTemplates.VERSION`）；详情页会读取每条真实 version。
    private var promptVersionText: String { "39" }

    // MARK: - 分组列表（胶囊行）

    private func groupSection(group: String, items: [PromptEntryItem]) -> some View {
        SettingsKnockGroup(group, systemImage: groupIcon(group)) {
            VStack(spacing: KnockTokens.spacingS) {
                ForEach(items) { item in
                    NavigationLink {
                        PromptTemplateDetailView(
                            type: item.type,
                            mode: item.mode,
                            module: item.module,
                            displayName: item.displayName
                        )
                    } label: {
                        KnockListRow(
                            title: item.displayName,
                            subtitle: keyHint(type: item.type, mode: item.mode, module: item.module)
                        ) {
                            KnockRowGlyph(systemImage: item.module.isEmpty ? "doc.text" : "square.stack.3d.up",
                                          tint: KnockTokens.textPrimary)
                        } trailing: {
                            HStack(spacing: KnockTokens.spacingS) {
                                promptBadge(item)
                                KnockChevron()
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func promptBadge(_ item: PromptEntryItem) -> some View {
        if item.isDisabled {
            KnockBadge(text: "已禁用", variant: .danger)
        } else if item.isCustom {
            KnockBadge(text: "自定义", variant: .accent)
        } else {
            KnockBadge(text: "默认", variant: .neutral)
        }
    }

    private func groupIcon(_ group: String) -> String {
        switch group {
        case "私聊": return "bubble.left"
        case "群聊": return "person.3"
        default: return "square.grid.2x2"
        }
    }

    private func keyHint(type: String, mode: String, module: String) -> String {
        if module.isEmpty {
            return mode.isEmpty ? "prompt_\(type)" : "prompt_\(type)_\(mode)"
        }
        return mode.isEmpty ? "prompt_\(module)_\(type)" : "prompt_\(module)_\(type)_\(mode)"
    }

    // MARK: - 底部说明

    private var helpGroup: some View {
        SettingsKnockGroup("编辑规则", systemImage: "info.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    SettingsKnockBullet("保存 = 值 + `_custom=true` + `_version` 三键（T18 契约 4）；恢复默认删除三键。")
                    SettingsKnockBullet("只有标记为自定义的内容才会覆盖出厂默认；旧版随包默认会随 App 升级更新。")
                    SettingsKnockBullet("空串保存 = 有意禁用（不回落默认），适用于行为层 / 输出协议 / 运行时资料模块。")
                    SettingsKnockBullet("占位符必须写成 `{{大写英文_数字}}`；畸形语法会阻断保存，未知占位符只警告、原样发送。")
                }
            }
        }
    }
}

// MARK: - 详情 / 编辑

struct PromptTemplateDetailView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    let type: String
    let mode: String
    let module: String
    let displayName: String

    @State private var detail: PromptItemDetail?
    @State private var text: String = ""
    @State private var loaded = false
    @State private var message = ""
    @State private var messageOK = true
    @State private var showResetConfirm = false
    @State private var showDisableConfirm = false
    @State private var scrollOffset: CGFloat = 0

    private var isModule: Bool { !module.isEmpty }

    private var keyName: String {
        if module.isEmpty {
            return mode.isEmpty ? "prompt_\(type)" : "prompt_\(type)_\(mode)"
        }
        return mode.isEmpty ? "prompt_\(module)_\(type)" : "prompt_\(module)_\(type)_\(mode)"
    }

    /// 内容生成类模板（动态 / 评论 / 日记 / 派遣）用衬线排版；角色规则模板用等宽排版。
    private var isContentGeneration: Bool {
        (detail?.groupName ?? "") == "内容生成"
    }

    private var editorFont: Font {
        isContentGeneration
            ? .system(size: 13, design: .serif)
            : .system(size: 13, design: .monospaced)
    }

    private var editorTypographyLabel: String {
        isContentGeneration ? "衬线排版" : "等宽排版"
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: displayName, showsBack: true, onBack: { dismiss() })

                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: KnockTokens.spacingXL) {
                            statusGroup
                            editorGroup
                            actionGroup
                            placeholderGroup
                            helpGroup
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.top, KnockTokens.spacingM)
                        .padding(.bottom, KnockTokens.spacingXXL)
                    }
                    .scrollIndicators(.hidden)
                    .settingsKnockScrollEdgeHidden()
                    .onScrollGeometryChange(for: CGFloat.self) { geometry in
                        geometry.contentOffset.y
                    } action: { _, newValue in
                        scrollOffset = newValue
                    }
                    .task {
                        guard ProcessInfo.processInfo.arguments.contains("--settings-prompt-detail-scroll") else { return }
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                        withAnimation(.none) { proxy.scrollTo("prompt-placeholders", anchor: .top) }
                    }
                }
            }
        }
        .navigationTitle(displayName)
        .settingsKnockNavigationChrome()
        .task { reload() }
        // 恢复默认确认（自绘 KnockDialog 替代系统 alert，避开 iOS 26 玻璃背景）
        .knockConfirmDialog(
            isPresented: $showResetConfirm,
            title: "恢复默认模板？",
            message: "将删除该场景的自定义内容与标记，之后跟随随包默认模板。",
            confirmTitle: "恢复默认",
            cancelTitle: "取消"
        ) {
            reset()
        }
        .knockConfirmDialog(
            isPresented: $showDisableConfirm,
            title: "禁用该内容？",
            message: "保存空串 = 有意禁用：本场景不再发送这部分内容（不会自动恢复默认）。可用「恢复默认」还原。",
            confirmTitle: "清空并保存",
            cancelTitle: "取消",
            isDestructive: true
        ) {
            disable()
        }
    }

    // MARK: 数据（逻辑零改动）

    private func reload() {
        let dto = environment.bridge.promptItem(type: type, mode: mode, module: module)
        detail = dto
        text = dto.currentText
        loaded = true
        SelfTestLog.add("prompt-detail load type=\(type) mode=\(mode) module=\(module) custom=\(dto.isCustom) disabled=\(dto.isDisabled) version=\(dto.version) len=\(dto.currentText.count)")
    }

    private func save() {
        let result = environment.bridge.savePromptItem(type: type, mode: mode, module: module, text: text)
        if result.ok {
            messageOK = true
            message = result.warnings.isEmpty ? "已保存" : "已保存（警告 \(result.warnings.count) 条）"
            SelfTestLog.add("prompt-detail save ok=true type=\(type) mode=\(mode) module=\(module) len=\(text.count) warnings=\(result.warnings.count)")
            if !result.warnings.isEmpty {
                message += "\n" + result.warnings.joined(separator: "\n")
            }
        } else {
            messageOK = false
            message = "保存失败：\(result.error)"
            SelfTestLog.add("prompt-detail save ok=false type=\(type) mode=\(mode) module=\(module) error=\(result.error)")
        }
        reload()
    }

    private func reset() {
        environment.bridge.resetPromptItem(type: type, mode: mode, module: module)
        messageOK = true
        message = "已恢复默认"
        SelfTestLog.add("prompt-detail reset type=\(type) mode=\(mode) module=\(module)")
        reload()
    }

    private func disable() {
        let result = environment.bridge.savePromptItem(type: type, mode: mode, module: module, text: "")
        messageOK = result.ok
        message = result.ok ? "已清空并保存（禁用）" : "保存失败：\(result.error)"
        SelfTestLog.add("prompt-detail disable type=\(type) mode=\(mode) module=\(module) ok=\(result.ok)")
        reload()
    }

    // MARK: 状态

    private var statusGroup: some View {
        SettingsKnockGroup(displayName, systemImage: isModule ? "square.stack.3d.up" : "text.book.closed") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    HStack(spacing: KnockTokens.spacingS) {
                        if let detail {
                            statusBadge(detail)
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        if let detail {
                            KnockBadge(text: "v\(detail.version)", variant: .neutral)
                        }
                    }

                    SettingsKnockInfoLine("设置键", keyName, monospaced: true)
                    SettingsKnockInfoLine("排版", "\(editorTypographyLabel) · \(isContentGeneration ? "内容生成模板" : "角色规则模板")", monospaced: false)

                    KnockText(
                        isModule
                            ? "模块内容为空串 = 有意禁用（T18 契约 15/16）：本场景不会发送这部分资料，也不会回落默认。"
                            : "主模板为空串 = 有意禁用该场景的角色规则；正常情况下建议用「恢复默认」而不是清空。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func statusBadge(_ detail: PromptItemDetail) -> some View {
        if detail.isDisabled {
            KnockBadge(text: "已禁用", variant: .danger)
        } else if detail.isCustom {
            KnockBadge(text: "自定义", variant: .accent)
        } else {
            KnockBadge(text: "默认", variant: .neutral)
        }
    }

    // MARK: 编辑

    private var editorGroup: some View {
        SettingsKnockGroup("编辑内容", systemImage: "pencil") {
            KnockPanel(padding: KnockTokens.spacingM) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    HStack(spacing: KnockTokens.spacingS) {
                        KnockBadge(text: editorTypographyLabel, variant: .neutral)
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockText("\(text.count) 字", role: .mono, color: KnockTokens.textSecondary, lineLimit: 1)
                    }

                    TextEditor(text: $text)
                        .font(editorFont)
                        .foregroundStyle(KnockTokens.textPrimary)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 300)
                        .padding(KnockTokens.spacingS)
                        .background {
                            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                                .fill(KnockTokens.panelInset)
                        }
                        .overlay {
                            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                        }
                        .tint(KnockTokens.electric)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(!loaded)
                }
            }
        }
    }

    // MARK: 操作

    private var actionGroup: some View {
        SettingsKnockGroup("保存与恢复", systemImage: "checkmark.seal") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingM) {
                        Button(action: save) {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.system(size: 14, weight: .heavy))
                                KnockText("保存", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockPrimaryButtonStyle())
                        .disabled(!loaded)

                        Button {
                            showResetConfirm = true
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "arrow.counterclockwise")
                                    .font(.system(size: 14, weight: .heavy))
                                KnockText("恢复默认", role: .rowTitle, lineLimit: 1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .disabled(!loaded)
                    }

                    Button {
                        showDisableConfirm = true
                    } label: {
                        HStack(spacing: KnockTokens.spacingS) {
                            Image(systemName: "eye.slash")
                                .font(.system(size: 12, weight: .heavy))
                            KnockText("清空内容并保存（禁用）", role: .captionStrong, color: KnockTokens.dangerBright, lineLimit: 2)
                            Spacer(minLength: 0)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(!loaded)

                    if !message.isEmpty {
                        SettingsKnockStatusBanner(
                            kind: messageOK ? .success : .failure,
                            title: message
                        )
                    }
                }
            }
        }
    }

    // MARK: 占位符

    private var placeholderGroup: some View {
        SettingsKnockGroup("可用变量占位符", systemImage: "curlybraces") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    if let detail {
                        if !detail.recommended.isEmpty {
                            placeholderGroupContents(
                                title: "推荐（出厂模板实际使用）",
                                keys: detail.recommended,
                                tint: KnockTokens.electric
                            )
                        }
                        let others = detail.placeholders.filter { !detail.recommended.contains($0) }
                        if !others.isEmpty {
                            placeholderGroupContents(
                                title: "本场景可用",
                                keys: others,
                                tint: KnockTokens.textSecondary
                            )
                        }
                        if !detail.legacy.isEmpty {
                            placeholderGroupContents(
                                title: "旧版兼容（运行时可能给默认值）",
                                keys: detail.legacy,
                                tint: KnockTokens.textTertiary
                            )
                        }
                        KnockText(
                            "未知占位符可以保存，但不会自动替换，会原样发送给 AI。语法错误的占位符（孤立 }} / 未闭合 {{ / 空名）会阻断保存。",
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineSpacing: 2
                        )
                    } else {
                        HStack(spacing: KnockTokens.spacingS) {
                            ProgressView().controlSize(.small)
                            KnockText("读取占位符…", role: .caption, color: KnockTokens.textSecondary)
                        }
                    }
                }
            }
            .id("prompt-placeholders")
        }
    }

    private func placeholderGroupContents(title: String, keys: [String], tint: Color) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockText(title, role: .captionStrong, color: tint, lineLimit: 2)
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 148), spacing: KnockTokens.spacingXS)],
                alignment: .leading,
                spacing: KnockTokens.spacingXS
            ) {
                ForEach(keys, id: \.self) { key in
                    Text("{{\(key)}}")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(KnockTokens.textPrimary)
                        .lineLimit(1)
                        .padding(.horizontal, KnockTokens.spacingXS)
                        .padding(.vertical, 3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(KnockTokens.row)
                        }
                        .overlay {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.hairline)
                        }
                }
            }
        }
    }

    // MARK: 说明

    private var helpGroup: some View {
        SettingsKnockGroup("说明", systemImage: "info.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    KnockText(
                        "每个模式都有独立的角色规则模板。模板不是发送给 AI 的全部内容：应用还会自动加入系统固定规则、运行时资料、历史 user/assistant 消息、本轮输入和输出协议。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                    KnockText(
                        "保存后只影响对应场景；已开启的会话下一轮请求即使用新模板，无需重启。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }
}