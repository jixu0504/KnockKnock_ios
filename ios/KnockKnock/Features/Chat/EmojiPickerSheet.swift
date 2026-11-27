//
//  EmojiPickerSheet.swift
//  表情选择面板（仅线上模式）。
//
//  设计含义：表情的「语义」在目录里就是文字（情绪名 + 画面描述），发送时由 shared 侧
//  直接以文字告知模型，因此这里只负责挑选与预览，不涉及任何识图能力。
//

import SwiftUI

struct EmojiPickerSheet: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    var onPick: (EmojiItem) -> Void
    var onCancel: () -> Void

    private var emojis: [EmojiItem] { environment.bridge.emojiCatalog() }

    /// 紧凑网格：只显示小图标（约 44pt 一列），不展示情绪文字。
    private let columns = [GridItem(.adaptive(minimum: 44, maximum: 56), spacing: KnockTokens.spacingS)]

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        LazyVGrid(columns: columns, spacing: KnockTokens.spacingS) {
                            ForEach(emojis) { emoji in
                                Button {
                                    onPick(emoji)
                                } label: {
                                    EmojiImage(assetName: emoji.assetName, size: 40)
                                        .frame(width: 48, height: 48)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("表情：\(emoji.label)，\(emoji.detail)")
                            }
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                    }
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
            }
        }
        .knockHideSystemChrome()
        .presentationDetents([.height(260), .medium])
        .presentationBackground(KnockTokens.canvas)
    }

    private var header: some View {
        HStack(spacing: KnockTokens.spacingM) {
            Button("取消") { onCancel() }
                .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 38))
                .accessibilityLabel("取消选择表情")
            KnockText("选择表情", role: .pageTitle, lineLimit: 1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
    }
}
