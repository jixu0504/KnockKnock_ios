//
//  PromptEditorView.swift
//  T46「提示词编辑器」页（T18 契约：PromptTemplates / PromptModuleDefaults / PromptEditor / PromptPlaceholderRegistry）：
//  - 分类列表：私聊（线上/线下/导演/主动消息）、群聊（线上/线下/导演/自动群聊）、内容生成（动态/评论/日记/派遣）；
//    私聊/群聊每个模式还可编辑 行为层 / 输出协议 / 运行时资料 三个可自由文本模块；
//  - 详情：当前生效文本（默认/自定义标记）、TextEditor 编辑、保存、恢复默认、禁用（空串语义）、可用变量占位符说明；
//  - 存储键（T18 契约）：主模板 prompt_<type>_<mode>；模块 prompt_<module>_<type>_<mode>；
//    保存写值 + `_custom=true` + `_version=39`，恢复默认删三键；空串 = 有意禁用（不回落默认）。
//
//  全部经 Shared.SettingsBridge（真实 SettingsRepository / NSUserDefaults）。
//  验证钩子：`--settings-selftest`（见 SettingsAutomation.swift）。
//

import SwiftUI

struct PromptEditorView: View {
    @EnvironmentObject private var environment: AppEnvironment

    @State private var entries: [PromptEntryItem] = []
    @State private var scrollOffset: CGFloat = 0
    @State private var loaded = false
    /// 截图证据钩子：`--settings-prompt-demo` 自动进入「私聊 · 线下模式」详情。
    @State private var showDemoDetail = false

    private let groupOrder = ["私聊", "群聊", "内容生成"]

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollView {
                VStack(spacing: 14) {
                    introCard
                    ForEach(groupOrder, id: \.self) { group in
                        let items = entries.filter { $0.groupName == group }
                        if !items.isEmpty {
                            groupCard(group: group, items: items)
                        }
                    }
                    helpCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
        }
        .navigationTitle("提示词编辑器")
        .navigationBarTitleDisplayMode(.inline)
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

    // MARK: - 说明卡

    private var introCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "text.book.closed")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("角色说话规则（模板 v\(promptVersionText)）")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(loaded ? "\(entries.count) 项" : "读取中…")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Text("模板不是发送给 AI 的全部内容：应用还会自动加入固定规则、运行时资料、历史消息和输出协议。修改后只影响对应场景，保存即生效。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    /// T18 出厂模板版本号常量（`PromptTemplates.VERSION`）；详情页会读取每条真实 version。
    private var promptVersionText: String { "39" }

    // MARK: - 分组列表

    private func groupCard(group: String, items: [PromptEntryItem]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: group, systemImage: groupIcon(group))
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 8)

            ForEach(items) { item in
                NavigationLink {
                    PromptTemplateDetailView(
                        type: item.type,
                        mode: item.mode,
                        module: item.module,
                        displayName: item.displayName
                    )
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(item.displayName)
                                    .font(.body)
                                    .foregroundStyle(.primary)
                                if item.isDisabled {
                                    badge("已禁用", color: .red)
                                } else if item.isCustom {
                                    badge("自定义", color: GlassTokens.brandOrange)
                                } else {
                                    badge("默认", color: .secondary)
                                }
                            }
                            Text(keyHint(type: item.type, mode: item.mode, module: item.module))
                                .font(.caption2.monospaced())
                                .foregroundStyle(.tertiary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if item.id != items.last?.id {
                    Divider().padding(.leading, 14).opacity(0.4)
                }
            }
            .padding(.bottom, 6)
        }
        .cardSurface(cornerRadius: 20)
    }

    private func groupIcon(_ group: String) -> String {
        switch group {
        case "私聊": return "bubble.left"
        case "群聊": return "person.3"
        default: return "square.grid.2x2"
        }
    }

    private func badge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 9, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(color.opacity(0.12), in: Capsule())
    }

    private func keyHint(type: String, mode: String, module: String) -> String {
        if module.isEmpty {
            return mode.isEmpty ? "prompt_\(type)" : "prompt_\(type)_\(mode)"
        }
        return mode.isEmpty ? "prompt_\(module)_\(type)" : "prompt_\(module)_\(type)_\(mode)"
    }

    // MARK: - 底部说明

    private var helpCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "编辑规则", systemImage: "info.circle")
            bullet("保存 = 值 + `_custom=true` + `_version` 三键（T18 契约 4）；恢复默认删除三键。")
            bullet("只有标记为自定义的内容才会覆盖出厂默认；旧版随包默认会随 App 升级更新。")
            bullet("空串保存 = 有意禁用（不回落默认），适用于行为层 / 输出协议 / 运行时资料模块。")
            bullet("占位符必须写成 `{{大写英文_数字}}`；畸形语法会阻断保存，未知占位符只警告、原样发送。")
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("•").foregroundStyle(GlassTokens.brandOrange)
            Text(text)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - 详情 / 编辑

struct PromptTemplateDetailView: View {
    @EnvironmentObject private var environment: AppEnvironment

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

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    statusCard
                    editorCard
                    actionCard
                    placeholderCard
                    helpCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
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
        .navigationTitle(displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
        .alert("恢复默认模板？", isPresented: $showResetConfirm) {
            Button("取消", role: .cancel) {}
            Button("恢复默认", role: .destructive) { reset() }
        } message: {
            Text("将删除该场景的自定义内容与标记，之后跟随随包默认模板。")
        }
        .alert("禁用该内容？", isPresented: $showDisableConfirm) {
            Button("取消", role: .cancel) {}
            Button("清空并保存", role: .destructive) { disable() }
        } message: {
            Text("保存空串 = 有意禁用：本场景不再发送这部分内容（不会自动恢复默认）。可用「恢复默认」还原。")
        }
    }

    // MARK: 数据

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

    // MARK: 状态卡

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: isModule ? "square.stack.3d.up" : "text.book.closed")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text(displayName)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if let detail {
                    statusBadge(detail)
                }
            }
            HStack(spacing: 10) {
                Text("键 \(keyName)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                Spacer()
                if let detail {
                    Text("v\(detail.version)")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.tertiary)
                }
            }
            Text(isModule
                 ? "模块内容为空串 = 有意禁用（T18 契约 15/16）：本场景不会发送这部分资料，也不会回落默认。"
                 : "主模板为空串 = 有意禁用该场景的角色规则；正常情况下建议用「恢复默认」而不是清空。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    @ViewBuilder
    private func statusBadge(_ detail: PromptItemDetail) -> some View {
        if detail.isDisabled {
            Text("已禁用").font(.system(size: 10, weight: .semibold)).foregroundStyle(.red)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Color.red.opacity(0.12), in: Capsule())
        } else if detail.isCustom {
            Text("自定义").font(.system(size: 10, weight: .semibold)).foregroundStyle(GlassTokens.brandOrange)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(GlassTokens.brandOrange.opacity(0.14), in: Capsule())
        } else {
            Text("默认").font(.system(size: 10, weight: .semibold)).foregroundStyle(.secondary)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Color.secondary.opacity(0.12), in: Capsule())
        }
    }

    // MARK: 编辑卡

    private var editorCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                SectionTitle(text: "编辑内容", systemImage: "pencil")
                Spacer()
                Text("\(text.count) 字")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            }
            TextEditor(text: $text)
                .font(.system(size: 13, design: .monospaced))
                .frame(minHeight: 300)
                .scrollContentBackground(.hidden)
                .padding(8)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(!loaded)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    // MARK: 操作卡

    private var actionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Button(action: save) {
                    Label("保存", systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(GlassTokens.brandOrange.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(!loaded)

                Button {
                    showResetConfirm = true
                } label: {
                    Label("恢复默认", systemImage: "arrow.counterclockwise")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(!loaded)
            }

            Button(role: .destructive) {
                showDisableConfirm = true
            } label: {
                Label("清空内容并保存（禁用）", systemImage: "eye.slash")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.red)
            }
            .buttonStyle(.plain)
            .disabled(!loaded)

            if !message.isEmpty {
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(messageOK ? .green : .red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    // MARK: 占位符

    private var placeholderCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "可用变量占位符", systemImage: "curlybraces")
            if let detail {
                if !detail.recommended.isEmpty {
                    placeholderGroup(title: "推荐（出厂模板实际使用）", keys: detail.recommended,
                                    tint: GlassTokens.brandOrange)
                }
                let others = detail.placeholders.filter { !detail.recommended.contains($0) }
                if !others.isEmpty {
                    placeholderGroup(title: "本场景可用", keys: others, tint: .secondary)
                }
                if !detail.legacy.isEmpty {
                    placeholderGroup(title: "旧版兼容（运行时可能给默认值）", keys: detail.legacy, tint: .gray)
                }
                Text("未知占位符可以保存，但不会自动替换，会原样发送给 AI。语法错误的占位符（孤立 }} / 未闭合 {{ / 空名）会阻断保存。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
        .id("prompt-placeholders")
    }

    private func placeholderGroup(title: String, keys: [String], tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(tint)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 148), spacing: 6)], alignment: .leading, spacing: 6) {
                ForEach(keys, id: \.self) { key in
                    Text("{{\(key)}}")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
                }
            }
        }
    }

    private var helpCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(text: "说明", systemImage: "info.circle")
            Text("每个模式都有独立的角色规则模板。模板不是发送给 AI 的全部内容：应用还会自动加入系统固定规则、运行时资料、历史 user/assistant 消息、本轮输入和输出协议。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text("保存后只影响对应场景；已开启的会话下一轮请求即使用新模板，无需重启。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }
}
