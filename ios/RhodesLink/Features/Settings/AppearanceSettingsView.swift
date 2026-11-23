//
//  AppearanceSettingsView.swift
//  T53 外观设置页（设置 → 外观）：
//  - 主题：跟随系统 / 浅色 / 深色 → `ios_shell_appearance` + shared `dark_mode`；
//    由 `RhodesLinkApp` 的 `preferredColorScheme` 实时生效（原有逻辑）；
//  - 聊天背景：纯色 4 档 + 内置渐变 2 档 → `ios_chat_background`；聊天页背景 token
//    `GlassTokens.chatBackground` 渲染时读取，改完重进聊天页生效；
//  - 字体大小：小 / 标准 / 大 → `ios_shell_font_size`；`RootTabView` 用 `.dynamicTypeSize`
//    全局缩放（聊天与列表字体一并生效；标准档 = 跟随系统）。
//
//  验证钩子：`--settings-extra-selftest` / `-verify`（见 SettingsExtraAutomation.swift）。
//

import SwiftUI

struct AppearanceSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.colorScheme) private var colorScheme

    @State private var readBackMessage = ""
    @State private var scrollOffset: CGFloat = 0

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollView {
                VStack(spacing: 14) {
                    themeCard
                    chatBackgroundCard
                    fontSizeCard
                    readBackCard
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
        .navigationTitle("外观")
        .navigationBarTitleDisplayMode(.inline)
        .task { refreshReadBack() }
    }

    // MARK: 主题

    private var themeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "circle.lefthalf.filled")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("主题")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("当前：\(colorScheme == .dark ? "深色" : "浅色")")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Picker("主题", selection: Binding(
                get: { settings.appearance },
                set: { settings.setAppearance($0) }
            )) {
                ForEach(SettingsStore.Appearance.allCases) { mode in
                    Text(mode.label).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("主题模式")

            infoLine("设置键", "\(SettingsStore.appearanceKey)（shared 同步键 dark_mode）")
            Text("跟随系统时 preferredColorScheme 为 nil；浅色/深色写入 shared SettingsBridge，实时切换全局外观（无需重启）。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    // MARK: 聊天背景

    private var chatBackgroundCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "photo.on.rectangle.angled")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("聊天背景")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("\(settings.chatBackground.kindLabel) · \(settings.chatBackground.shortLabel)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            // 选中背景的实时预览 + 示例气泡（低对比，参考微信/Telegram）
            ZStack {
                ChatBackgroundView(style: settings.chatBackground)
                VStack(spacing: 6) {
                    HStack {
                        Text("博士，今天的巡逻记录已经整理好了。")
                            .font(.caption)
                            .foregroundStyle(GlassTokens.bubbleIncomingText)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(GlassTokens.bubbleIncoming, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        Spacer(minLength: 40)
                    }
                    HStack {
                        Spacer(minLength: 40)
                        Text("收到，辛苦了。")
                            .font(.caption)
                            .foregroundStyle(GlassTokens.bubbleOutgoingText)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(GlassTokens.bubbleOutgoing, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
                .padding(12)
            }
            .frame(height: 120)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(GlassTokens.glassStroke, lineWidth: 1)
            )

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(ChatBackgroundStyle.allCases) { style in
                    backgroundSwatch(style)
                }
            }

            infoLine("设置键", ChatBackgroundStyle.settingsKey)
            Text("背景在聊天页渲染时读取，改完返回并重新进入聊天页即生效（不重启、不影响已有消息）。群聊页仍用系统分组背景（本卡未改群聊页）。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    private func backgroundSwatch(_ style: ChatBackgroundStyle) -> some View {
        let selected = settings.chatBackground == style
        return Button {
            settings.setChatBackground(style)
            refreshReadBack()
        } label: {
            VStack(spacing: 5) {
                ChatBackgroundView(style: style)
                    .frame(height: 46)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(selected ? GlassTokens.brandOrange : GlassTokens.strokeSubtle, lineWidth: selected ? 2 : 1)
                    )
                    .overlay(alignment: .topTrailing) {
                        if selected {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(GlassTokens.brandOrange)
                                .padding(3)
                        }
                    }
                Text(style.label)
                    .font(.system(size: 10))
                    .foregroundStyle(selected ? GlassTokens.brandOrange : .secondary)
                    .lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(style.label)
    }

    // MARK: 字体大小

    private var fontSizeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "textformat.size")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("字体大小")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(settings.fontSize.label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Picker("字体大小", selection: Binding(
                get: { settings.fontSize },
                set: { settings.setFontSize($0) }
            )) {
                ForEach(SettingsStore.FontSize.allCases) { size in
                    Text(size.label).tag(size)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("字体大小")

            VStack(alignment: .leading, spacing: 6) {
                Text("大字号预览：博士，今晚的巡逻记录已经整理好了。ABC abc 123")
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
                Text("会话列表 / 聊天页 / 设置页同时缩放；标准档跟随系统 Dynamic Type（可在系统设置中调字号）。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            infoLine("设置键", SettingsStore.fontSizeKey)
            infoLine("当前档位", settings.fontSize.scaleDescription)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    // MARK: 读回校验

    private var readBackCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "落盘读回", systemImage: "arrow.triangle.2.circlepath")

            VStack(alignment: .leading, spacing: 8) {
                Button {
                    refreshReadBack()
                    SelfTestLog.add("appearance read-back \(readBackMessage)")
                } label: {
                    Label("重新读回设置键", systemImage: "arrow.clockwise")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(GlassTokens.brandOrange)

                infoLine("主题键", "\(SettingsStore.appearanceKey)=\(environment.bridge.settingsString(key: SettingsStore.appearanceKey))", monospaced: true)
                infoLine("背景键", "\(ChatBackgroundStyle.settingsKey)=\(environment.bridge.settingsString(key: ChatBackgroundStyle.settingsKey))", monospaced: true)
                infoLine("字号键", "\(SettingsStore.fontSizeKey)=\(environment.bridge.settingsString(key: SettingsStore.fontSizeKey))", monospaced: true)
                infoLine("并集", readBackMessage, monospaced: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 18)
        }
    }

    private func refreshReadBack() {
        let appearance = environment.bridge.settingsString(key: SettingsStore.appearanceKey)
        let background = environment.bridge.settingsString(key: ChatBackgroundStyle.settingsKey)
        let fontSize = environment.bridge.settingsString(key: SettingsStore.fontSizeKey)
        readBackMessage = "appearance=\(appearance.isEmpty ? settings.appearance.rawValue : appearance) · " +
            "background=\(background.isEmpty ? settings.chatBackground.rawValue : background) · " +
            "font=\(fontSize.isEmpty ? settings.fontSize.rawValue : fontSize)"
    }

    private func infoLine(_ title: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
            Text(value)
                .font(monospaced ? .caption2.monospaced() : .caption2)
                .foregroundStyle(.primary)
                .lineLimit(3)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
