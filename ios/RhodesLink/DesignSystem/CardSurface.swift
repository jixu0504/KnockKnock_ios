//
//  CardSurface.swift
//  非玻璃内容层容器：卡片 / 行 / 分组标题。
//  说明：正式壳「一屏 ≤5 玻璃元素」，大列表与设置行一律使用非玻璃 surface，
//  只有关键 1~2 个区域使用 GlassPanel。所有文本用语义字号（Dynamic Type）。
//

import SwiftUI

/// 非玻璃卡片背景
struct CardSurface: ViewModifier {
    var cornerRadius: CGFloat = 18
    var strong: Bool = false

    func body(content: Content) -> some View {
        content
            .background(
                strong ? GlassTokens.surfaceStrong : GlassTokens.surface,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(GlassTokens.strokeSubtle, lineWidth: 0.5)
            )
    }
}

extension View {
    /// 非玻璃卡片（内容层默认风格）
    func cardSurface(cornerRadius: CGFloat = 18, strong: Bool = false) -> some View {
        modifier(CardSurface(cornerRadius: cornerRadius, strong: strong))
    }
}

/// 分组标题（列表页 / 设置页共用）
struct SectionTitle: View {
    var text: String
    var systemImage: String?

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.caption.weight(.semibold))
            }
            Text(text)
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
    }
}

/// 空状态占位（列表无数据时）
struct EmptyStateView: View {
    var title: String
    var message: String
    var systemImage: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 42, weight: .light))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.borderedProminent)
                    .tint(GlassTokens.brandOrange)
                    .padding(.top, 4)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .cardSurface(cornerRadius: 22)
    }
}
